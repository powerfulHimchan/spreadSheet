package com.powerfulhimchan.familyledger

import com.powerfulhimchan.familyledger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.math.BigDecimal

data class SheetInfo(val id: Int,val title: String,val rows: Int,val columns: Int)
data class BookInfo(val id: String,val title: String,val sheets: List<SheetInfo>)
class ApiFailure(val status: Int,message: String): Exception(message)

class SheetsApi(private val token: () -> String?) {
    private val base="https://sheets.googleapis.com/v4/spreadsheets/"
    private fun enc(s: String)=URLEncoder.encode(s,"UTF-8")
    private suspend fun call(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val access=token() ?: throw ApiFailure(401,"Google 계정 연결이 필요합니다.")
        var attempts=0
        while(true) {
            val conn=URI(base+path).toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout=20000;conn.readTimeout=30000;conn.instanceFollowRedirects=false
                conn.setRequestProperty("Authorization","Bearer $access")
                if(body!=null) {
                    conn.requestMethod="POST";conn.doOutput=true;conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status=conn.responseCode
                if(status==429 || status in 500..599) {
                    // Mutations are not blindly retried: the first request may already have committed.
                    if(body==null && attempts<3) { delay((1000L shl attempts)+java.util.Random().nextInt(300));attempts++;continue }
                }
                if(status !in 200..299) throw ApiFailure(status,when(status) {
                    401 -> "인증이 만료되었습니다. Google 계정을 다시 연결해 주세요."
                    403 -> "시트 공유 권한과 Google Sheets API 활성화 여부를 확인해 주세요."
                    404 -> "스프레드시트를 찾을 수 없습니다. 주소와 접근 권한을 확인해 주세요."
                    429 -> "요청이 많습니다. 잠시 후 새로고침해 주세요."
                    else -> "시트 요청에 실패했습니다($status). 저장 중이었다면 새로고침하여 반영 여부를 확인해 주세요."
                })
                val bytes=conn.inputStream.use { input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) { val count=input.read(buffer);if(count<0) break;check(out.size()+count<=25*1024*1024) { "데이터가 큽니다. 입력 영역을 나누어 연결해 주세요." };out.write(buffer,0,count) }
                    out.toByteArray()
                }
                return@withContext JSONObject(bytes.toString(Charsets.UTF_8))
            } finally { conn.disconnect() }
        }
        @Suppress("UNREACHABLE_CODE") JSONObject()
    }

    suspend fun book(id: String): BookInfo {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        val o=call("$id?fields="+enc("spreadsheetId,properties(title),sheets(properties(sheetId,title,gridProperties(rowCount,columnCount)))"))
        val arr=o.getJSONArray("sheets")
        return BookInfo(id,o.getJSONObject("properties").getString("title"),(0 until arr.length()).mapNotNull { i ->
            val p=arr.getJSONObject(i).getJSONObject("properties");val g=p.optJSONObject("gridProperties") ?: return@mapNotNull null
            SheetInfo(p.getInt("sheetId"),p.getString("title"),g.optInt("rowCount"),g.optInt("columnCount"))
        })
    }
    suspend fun grid(id: String, sheet: SheetInfo): Grid {
        val range="${A1.quote(sheet.title)}!A1:${A1.col(minOf(sheet.columns,200)-1)}${minOf(sheet.rows,10000)}"
        val mask="sheets(properties(sheetId,title,gridProperties(rowCount,columnCount)),data(startRow,startColumn,rowData(values(userEnteredValue,effectiveValue,effectiveFormat(numberFormat),dataValidation))))"
        val o=call("$id?ranges=${enc(range)}&fields=${enc(mask)}")
        val s=o.getJSONArray("sheets").getJSONObject(0);val data=s.optJSONArray("data")
        val rows=mutableListOf<List<Cell>>()
        if(data!=null) for(d in 0 until data.length()) {
            val block=data.getJSONObject(d);val start=block.optInt("startRow");val offset=block.optInt("startColumn");val rr=block.optJSONArray("rowData") ?: continue
            while(rows.size<start) rows.add(emptyList())
            for(r in 0 until rr.length()) {
                val cc=rr.getJSONObject(r).optJSONArray("values")
                val cells=MutableList(offset){ Cell() }
                if(cc!=null) for(c in 0 until cc.length()) cells.add(parseCell(cc.getJSONObject(c)))
                while(rows.size<=start+r) rows.add(emptyList())
                rows[start+r]=cells
            }
        }
        // Grid completeness refers to the loaded coordinate limits, even when trailing rows are empty.
        var result=Grid(id,sheet.id,sheet.title,rows,sheet.rows,sheet.columns,sheet.rows<=10000 && sheet.columns<=200)
        val refs=rows.flatten().mapNotNull { it.validationRange }.filter { it!="custom-checkbox" }.distinct()
        val resolved=mutableMapOf<String,List<String>>()
        for(ref in refs.take(30)) runCatching {
            val values=call("$id/values/${enc(ref.removePrefix("="))}?valueRenderOption=UNFORMATTED_VALUE").optJSONArray("values")
            if(values!=null) resolved[ref]=(0 until values.length()).flatMap { i -> val row=values.getJSONArray(i);(0 until row.length()).map { j -> row.get(j).toString() } }.filter { it.isNotBlank() }.distinct()
        }
        result=result.copy(rows=result.rows.map { row -> row.map { c -> if(c.validationRange in resolved) c.copy(options=resolved[c.validationRange].orEmpty()) else c } })
        return result
    }
    private fun parseCell(o: JSONObject): Cell {
        val entered=o.optJSONObject("userEnteredValue");val value=o.optJSONObject("effectiveValue") ?: entered
        val fmt=o.optJSONObject("effectiveFormat")?.optJSONObject("numberFormat")
        val condition=o.optJSONObject("dataValidation")?.optJSONObject("condition")
        val v=condition?.optJSONArray("values")
        val opts=if(v==null) emptyList() else (0 until v.length()).map { v.getJSONObject(it).optString("userEnteredValue") }
        val validation=condition?.optString("type")
        val kind=when { value==null -> ValueKind.EMPTY;value.has("numberValue") -> ValueKind.NUMBER;value.has("boolValue") -> ValueKind.BOOLEAN;value.has("errorValue") -> ValueKind.ERROR;else -> ValueKind.STRING }
        val text=when(kind) { ValueKind.NUMBER -> BigDecimal(value!!.get("numberValue").toString()).stripTrailingZeros().toPlainString();ValueKind.BOOLEAN -> value!!.getBoolean("boolValue").toString();ValueKind.ERROR -> value!!.getJSONObject("errorValue").optString("type");ValueKind.STRING -> value?.optString("stringValue").orEmpty();else -> "" }
        // Non-standard checkbox values stay read-only until explicitly remapped.
        return Cell(text,kind,fmt?.optString("type").orEmpty(),fmt?.optString("pattern").orEmpty(),entered?.optString("formulaValue")?.ifEmpty { null },
            if(validation=="ONE_OF_LIST") opts else emptyList(),if(validation=="ONE_OF_RANGE") opts.firstOrNull() else if(validation=="BOOLEAN" && opts.isNotEmpty()) "custom-checkbox" else null,validation=="BOOLEAN" && opts.isEmpty())
    }
    private fun entered(c: Cell): JSONObject = JSONObject().apply {
        when(c.kind) {
            ValueKind.NUMBER -> put("numberValue",BigDecimal(c.value))
            ValueKind.BOOLEAN -> put("boolValue",c.value.toBooleanStrict())
            ValueKind.STRING -> put("stringValue",c.value)
            ValueKind.EMPTY -> Unit
            ValueKind.ERROR -> error("오류 값은 저장할 수 없습니다.")
        }
    }
    private fun cellRequest(sheetId: Int,w: CellWrite): JSONObject {
        val data=JSONObject()
        if(!w.cell.blank) data.put("userEnteredValue",entered(w.cell))
        val format=when(w.cell.format) { "DATE" -> "yyyy-mm-dd";"TIME" -> "hh:mm";"DATE_TIME" -> "yyyy-mm-dd hh:mm";else -> null }
        if(format!=null) data.put("userEnteredFormat",JSONObject().put("numberFormat",JSONObject().put("type",w.cell.format).put("pattern",format)))
        return JSONObject().put("updateCells",JSONObject().put("start",JSONObject().put("sheetId",sheetId).put("rowIndex",w.row).put("columnIndex",w.column))
            .put("rows",JSONArray().put(JSONObject().put("values",JSONArray().put(data))))
            .put("fields",if(format==null) "userEnteredValue" else "userEnteredValue,userEnteredFormat.numberFormat"))
    }
    suspend fun write(id: String,sheetId: Int,plan: WritePlan) {
        if(plan.cells.isEmpty()) return
        call("$id:batchUpdate",JSONObject().put("requests",JSONArray(plan.cells.map { cellRequest(sheetId,it) })))
    }
    suspend fun installIdentity(grid: Grid,m: TableMapping): TableMapping {
        Writes.verifyStructure(grid,m)
        var nextIndex=grid.columnCount
        fun recover(role: Role,name: String,type: FieldType): Column? {
            m.column(role)?.let { return it }
            val indices=(0 until grid.columnCount.coerceAtMost(200)).filter { grid.cell(m.headerRow,it).value==name }
            check(indices.size<=1) { "식별용 헤더가 중복되어 있습니다. 연결 설정을 확인해 주세요." }
            return indices.singleOrNull()?.let { Column("$name#1",name,it,type,role,true) }
        }
        val id=recover(Role.ID,"__ledger_id",FieldType.TEXT) ?: Column("__ledger_id#1","__ledger_id",nextIndex++,FieldType.TEXT,Role.ID,true)
        val deleted=recover(Role.DELETED,"__ledger_deleted",FieldType.BOOLEAN) ?: Column("__ledger_deleted#1","__ledger_deleted",nextIndex++,FieldType.BOOLEAN,Role.DELETED,true)
        val added=listOf(id,deleted).filter { it.index>=grid.columnCount }
        check(nextIndex<=200) { "식별용 열을 추가하면 지원 열 범위를 넘습니다. 기존 ID·삭제 열을 매핑해 주세요." }
        val writes=added.map { CellWrite(m.headerRow,it.index,Cell(it.header,ValueKind.STRING)) }.toMutableList()
        val parsed=Ledger.parse(grid,m)
        check(parsed.records.map { grid.cell(it.sourceRow,id.index).value }.filter { it.isNotBlank() }.let { it.size==it.distinct().size }) { "기존 거래 ID가 중복되어 있습니다. 시트에서 먼저 확인해 주세요." }
        for(r in parsed.records) {
            check(grid.cell(r.sourceRow,id.index).formula==null) { "ID 열에는 수식을 사용할 수 없습니다." }
            if(grid.cell(r.sourceRow,id.index).value.isBlank()) writes+=CellWrite(r.sourceRow,id.index,Cell(java.util.UUID.randomUUID().toString(),ValueKind.STRING))
            if(deleted in added) writes+=CellWrite(r.sourceRow,deleted.index,Cell("false",ValueKind.BOOLEAN))
        }
        check(writes.size<=12000) { "한 번에 설정할 거래가 많습니다. 표 범위를 나누어 주세요." }
        val requests=JSONArray()
        if(added.isNotEmpty()) {
            requests.put(JSONObject().put("appendDimension",JSONObject().put("sheetId",grid.sheetId).put("dimension","COLUMNS").put("length",added.size)))
            requests.put(JSONObject().put("updateDimensionProperties",JSONObject().put("range",JSONObject().put("sheetId",grid.sheetId).put("dimension","COLUMNS").put("startIndex",grid.columnCount).put("endIndex",nextIndex)).put("properties",JSONObject().put("hiddenByUser",true)).put("fields","hiddenByUser")))
        }
        writes.forEach { requests.put(cellRequest(grid.sheetId,it)) }
        if(requests.length()>0) call("${grid.spreadsheetId}:batchUpdate",JSONObject().put("requests",requests))
        return m.copy(columns=m.columns+listOf(id,deleted).filter { col->m.columns.none { it.role==col.role } })
    }
}
