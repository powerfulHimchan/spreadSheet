package com.powerfulhimchan.familyledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.powerfulhimchan.familyledger.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class AppState(
    val connections: List<TableMapping> = emptyList(),
    val grid: Grid? = null,
    val mapping: TableMapping? = null,
    val parsed: ParsedTable = ParsedTable(emptyList(),emptyList()),
    val history: List<Record> = emptyList(),
    val historyTables: Int = 1,
    val book: BookInfo? = null,
    val setupGrid: Grid? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val cached: Boolean = false,
    val updated: Long? = null,
    val revision: Int = 0
)

class LedgerViewModel(app: Application): AndroidViewModel(app) {
    private val db=Room.databaseBuilder(app,LedgerDatabase::class.java,"ledger.db").build()
    private val dao=db.dao()
    private val mutable=MutableStateFlow(AppState())
    val state=mutable.asStateFlow()
    var accessToken: String? = null
    private val api=SheetsApi { accessToken }
    init { runTask { val connections=dao.connections().map { c -> Codec.mapping(c.payload) };mutable.update { it.copy(connections=connections) } } }
    fun clearMessage() { mutable.update { it.copy(message=null) } }
    fun message(text: String) { mutable.update { it.copy(message=text) } }
    private fun runTask(action: suspend () -> Unit) {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true) }
        viewModelScope.launch {
            try { action() } catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                if(e is ApiFailure && e.status==401) {
                    accessToken?.let { Identity.getAuthorizationClient(getApplication<Application>()).clearToken(ClearTokenRequest.builder().setToken(it).build()) };accessToken=null
                }
                mutable.update { it.copy(message=if(e is java.io.IOException) "네트워크 연결을 확인해 주세요. 저장 중이었다면 새로고침해 반영 여부를 확인해 주세요." else e.message ?: "작업을 완료하지 못했습니다.") }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }
    private suspend fun show(g: Grid,m: TableMapping,cached: Boolean = false,updated: Long? = null) {
        val parsed=withContext(Dispatchers.Default) { Ledger.parse(g,m) }
        val history=mutableListOf<Record>();val seen=mutableSetOf<String>();var count=0
        fun add(source: TableMapping,table: ParsedTable) {
            count++
            table.records.forEach { r -> val identity="${source.sheetId}:${r.sourceRow}:${source.column(Role.DATE)?.index}:${source.column(Role.AMOUNT)?.index}";if(seen.add(identity)) history+=r }
        }
        add(m,parsed)
        if(g.spreadsheetId!="demo") for(connection in dao.connections()) {
            val other=Codec.mapping(connection.payload)
            if(other.spreadsheetId==m.spreadsheetId && other.key!=m.key) dao.snapshot(other.key)?.let { snapshot ->
                val table=withContext(Dispatchers.Default) { Ledger.parse(Codec.grid(snapshot.payload),other) };add(other,table)
            }
        }
        mutable.update { it.copy(grid=g,mapping=m,parsed=parsed,history=history,historyTables=count,cached=cached,updated=updated,revision=it.revision+1,setupGrid=null,book=null) }
    }
    private suspend fun persist(g: Grid,m: TableMapping) {
        val now=System.currentTimeMillis()
        dao.putConnection(ConnectionEntity(m.key,Codec.mapping(m),m.title))
        dao.putSnapshot(SnapshotEntity(m.key,Codec.grid(g),now))
        val connections=dao.connections().map { row -> Codec.mapping(row.payload) }
        mutable.update { it.copy(connections=connections) }
        show(g,m,updated=now)
    }
    fun connect(url: String) = runTask {
        val id=A1.spreadsheetId(url) ?: error("구글 스프레드시트 URL을 확인해 주세요.")
        val book=api.book(id)
        mutable.update { it.copy(book=book,setupGrid=null) }
    }
    fun chooseSheet(info: SheetInfo) = runTask {
        val book=mutable.value.book ?: return@runTask
        val grid=api.grid(book.id,info)
        mutable.update { it.copy(setupGrid=grid) }
    }
    fun cancelSetup() { mutable.update { it.copy(book=null,setupGrid=null) } }
    fun demo(index: Int = 0) = runTask {
        val g=Demo.grids()[index];val m=Detection.candidates(g,LocalDate.now().year).first()
        val pair=Demo.withIdentity(g,m)
        show(pair.first,pair.second)
    }
    fun saveMapping(m: TableMapping) = runTask {
        val errors=m.validate();check(errors.isEmpty()) { errors.joinToString("\n") }
        val g=mutable.value.setupGrid ?: mutable.value.grid ?: return@runTask
        val named=m.copy(bookTitle=mutable.value.book?.takeIf { it.id==m.spreadsheetId }?.title ?: m.bookTitle)
        if(g.spreadsheetId=="demo") show(g,named) else persist(g,named)
    }
    fun openConnection(m: TableMapping) = runTask {
        val s=dao.snapshot(m.key) ?: error("저장된 데이터가 없습니다. Google 계정 연결 후 새로고침해 주세요.")
        show(Codec.grid(s.payload),m,true,s.updated)
    }
    fun openMonth(yearMonth: java.time.YearMonth) {
        val current=mutable.value.mapping ?: return
        if(current.month==null) return
        mutable.value.connections.firstOrNull { it.spreadsheetId==current.spreadsheetId && it.year==yearMonth.year && it.month==yearMonth.monthValue && it.key!=current.key }?.let { openConnection(it) }
    }
    fun linkMonthlyTabs() = runTask {
        val s=mutable.value;val original=s.mapping ?: return@runTask
        check(original.spreadsheetId!="demo") { "실제 장부에서 사용할 수 있습니다." }
        val book=api.book(original.spreadsheetId)
        val mainKeys=original.columns.filter { it.role !in setOf(Role.ID,Role.DELETED) }.map { it.key }.toSet()
        var linked=0
        for(sheet in book.sheets) {
            if(sheet.id==original.sheetId) continue
            if(!sheet.title.matches(Regex(".*(?:[1-9]|1[0-2])\\s*월.*")) && !sheet.title.matches(Regex(".*(?:19|20)\\d{2}[-./].*"))) continue
            val grid=api.grid(book.id,sheet)
            val candidate=Detection.candidates(grid,original.year).firstOrNull { c -> c.month!=null && c.columns.map { it.key }.toSet()==mainKeys } ?: continue
            var mapping=Detection.mapping(grid,candidate.headerRow,candidate.firstColumn,candidate.endColumn,minOf(original.dataEndRow,grid.rowCount,10000),original.year,original).copy(month=candidate.month)
            val aux=(0 until grid.columnCount.coerceAtMost(200)).mapNotNull { index ->
                val name=grid.cell(mapping.headerRow,index).value
                when(name) { "__ledger_id" -> Column("__ledger_id#1",name,index,FieldType.TEXT,Role.ID,true);"__ledger_deleted" -> Column("__ledger_deleted#1",name,index,FieldType.BOOLEAN,Role.DELETED,true);else -> null }
            }
            mapping=mapping.copy(columns=mapping.columns+aux.filter { c->mapping.columns.none { it.index==c.index } })
            dao.putConnection(ConnectionEntity(mapping.key,Codec.mapping(mapping),mapping.title));dao.putSnapshot(SnapshotEntity(mapping.key,Codec.grid(grid),System.currentTimeMillis()));linked++
        }
        val connections=dao.connections().map { Codec.mapping(it.payload) };mutable.update { it.copy(connections=connections) }
        show(s.grid!!,original,s.cached,s.updated)
        message("같은 헤더의 월별 탭 ${linked}개를 연결했습니다. 각 탭의 범위를 설정에서 확인해 주세요.")
    }
    fun refreshAll() = runTask {
        val s=mutable.value;val active=s.mapping ?: return@runTask
        val connections=dao.connections().map { Codec.mapping(it.payload) }.filter { it.spreadsheetId==active.spreadsheetId }
        var activeGrid=s.grid!!
        for(mapping in connections) {
            val grid=fresh(mapping);Writes.verifyStructure(grid,mapping)
            dao.putSnapshot(SnapshotEntity(mapping.key,Codec.grid(grid),System.currentTimeMillis()))
            if(mapping.key==active.key) activeGrid=grid
        }
        show(activeGrid,active,false,System.currentTimeMillis())
    }
    private suspend fun fresh(m: TableMapping): Grid {
        val b=api.book(m.spreadsheetId)
        val info=b.sheets.firstOrNull { it.id==m.sheetId } ?: error("연결한 탭이 삭제되었습니다.")
        return api.grid(m.spreadsheetId,info)
    }
    fun refresh() = runTask {
        val m=mutable.value.mapping ?: return@runTask
        if(m.spreadsheetId=="demo") return@runTask
        val g=fresh(m)
        try { Writes.verifyStructure(g,m) } catch(e: Exception) {
            // Show the new structure for review instead of rendering rows under stale roles.
            mutable.update { it.copy(setupGrid=g,message=e.message) };return@runTask
        }
        persist(g,m)
    }
    fun installIdentity() = runTask {
        val m=mutable.value.mapping ?: return@runTask
        check(m.spreadsheetId!="demo")
        val grid=fresh(m);val updated=api.installIdentity(grid,m)
        // Persist the mapping immediately, so a failed follow-up read can recover without reinstalling IDs.
        dao.putConnection(ConnectionEntity(updated.key,Codec.mapping(updated),updated.title))
        persist(fresh(updated),updated)
    }
    fun save(original: Record?,input: Map<String,String>,delete: Boolean=false) = runTask {
        val s=mutable.value;val m=s.mapping ?: return@runTask
        val g=if(m.spreadsheetId=="demo") s.grid!! else fresh(m)
        val plan=if(original==null) Writes.add(g,m,input) else Writes.edit(g,m,original,input,delete)
        if(m.spreadsheetId=="demo") show(Demo.apply(g,plan),m) else {
            api.write(m.spreadsheetId,m.sheetId,plan)
            persist(fresh(m),m)
        }
    }
    fun disconnect() = runTask {
        mutable.value.mapping?.let { dao.removeConnection(it.key);dao.removeSnapshot(it.key) }
        mutable.value=AppState(connections=dao.connections().map { Codec.mapping(it.payload) },busy=true)
    }
    fun clearDeviceData() = runTask {
        accessToken?.let { Identity.getAuthorizationClient(getApplication<Application>()).clearToken(ClearTokenRequest.builder().setToken(it).build()) }
        accessToken=null;dao.clearSnapshots();dao.clearConnections();mutable.value=AppState(busy=true)
    }
}
