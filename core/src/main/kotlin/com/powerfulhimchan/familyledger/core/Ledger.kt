package com.powerfulhimchan.familyledger.core

import java.math.BigDecimal
import java.time.YearMonth

object Ledger {
    fun parse(grid: Grid, mapping: TableMapping): ParsedTable {
        val issues = mutableListOf<ParseIssue>()
        val records = mutableListOf<Record>()
        for (row in mapping.headerRow + 1 until minOf(mapping.dataEndRow, grid.rows.size)) {
            val cells = mapping.columns.associate { it.key to grid.cell(row,it.index) }
            fun at(role: Role) = mapping.column(role)?.let { cells[it.key] }
            fun text(role: Role) = at(role)?.value.orEmpty()
            if (text(Role.DELETED).lowercase() in setOf("true","1","삭제")) continue
            val business = mapping.columns.filter { it.role !in setOf(Role.ID,Role.DELETED) && !it.readOnly }
            if (business.all { cells[it.key]?.blank != false }) continue
            var date: java.time.LocalDate? = null
            var amount: BigDecimal? = null
            mapping.column(Role.DATE)?.let { col ->
                date = Values.date(cells.getValue(col.key),col,mapping)
                if (date == null) issues += ParseIssue(row+1,col.header,"올바른 날짜로 읽을 수 없습니다.")
            }
            mapping.column(Role.AMOUNT)?.let { col ->
                val amountCell=cells.getValue(col.key)
                amount = Values.number(amountCell.value,if(amountCell.kind==ValueKind.NUMBER) NumberStyle.DOT_DECIMAL else mapping.numberStyle)
                if (amount == null) issues += ParseIssue(row+1,col.header,"금액이 비어 있거나 숫자 형식이 아닙니다.")
            }
            val direction = text(Role.DIRECTION).trim().lowercase()
            val income = direction in mapping.incomeValues.map { it.lowercase() }
            if (mapping.column(Role.DIRECTION) != null && !income && direction !in mapping.expenseValues.map { it.lowercase() }) {
                issues += ParseIssue(row+1,mapping.column(Role.DIRECTION)!!.header,"수입·지출 구분 값의 매핑을 확인해 주세요.")
                amount = null
            }
            mapping.columns.forEach { col ->
                val cell = cells.getValue(col.key)
                if (cell.kind == ValueKind.ERROR) issues += ParseIssue(row+1,col.header,"시트 셀에 오류가 있습니다.")
            }
            val payCol = mapping.column(Role.PAYMENT)
            val pay = text(Role.PAYMENT)
            val canonicalPay = Values.number(pay)?.stripTrailingZeros()?.toPlainString() ?: pay
            val description=text(Role.DESCRIPTION).ifBlank { mapping.columns.firstOrNull { it.role==Role.NONE && !it.readOnly && cells[it.key]?.value?.isNotBlank()==true }?.let { cells[it.key]?.value }.orEmpty() }
            records += Record(row,text(Role.ID).ifBlank { null },cells,date,amount,income,text(Role.CATEGORY).ifBlank { "미분류" },description,payCol?.labels?.get(canonicalPay) ?: pay,text(Role.AUTHOR))
        }
        records.filter { it.id != null }.groupBy { it.id }.filterValues { it.size > 1 }.values.flatten().forEach { issues += ParseIssue(it.sourceRow+1,"ID","거래 ID가 중복되어 수정할 수 없습니다.") }
        return ParsedTable(records,issues.distinct())
    }
    fun totals(records: List<Record>, month: YearMonth? = null): Totals {
        val data = records.filter { it.amount != null && it.date != null && (month == null || YearMonth.from(it.date) == month) }
        return Totals(data.filter { it.income }.fold(BigDecimal.ZERO) { n,r -> n + r.amount!! },data.filter { !it.income }.fold(BigDecimal.ZERO) { n,r -> n + r.amount!! },data.size)
    }
    fun categories(records: List<Record>, month: YearMonth): Map<String, BigDecimal> = records.filter { !it.income && it.amount != null && it.date?.let { d -> YearMonth.from(d) == month } == true }
        .groupBy { it.category }.mapValues { (_,rs) -> rs.fold(BigDecimal.ZERO) { n,r -> n + r.amount!! } }.toList().sortedByDescending { it.second }.toMap()
    fun monthly(records: List<Record>): Map<YearMonth, Totals> = records.filter { it.date != null }.groupBy { YearMonth.from(it.date) }.toSortedMap().mapValues { totals(it.value) }
}

data class CellWrite(val row: Int, val column: Int, val cell: Cell)
data class WritePlan(val cells: List<CellWrite>, val recordId: String)
class Conflict(message: String): IllegalStateException(message)

object Writes {
    fun verifyStructure(grid: Grid, mapping: TableMapping) {
        check(grid.complete) { "시트 일부만 읽혀 저장할 수 없습니다. 표 범위를 줄여 다시 연결해 주세요." }
        check(grid.sheetId == mapping.sheetId && grid.spreadsheetId == mapping.spreadsheetId) { "장부가 변경되었습니다." }
        check(mapping.validate().isEmpty()) { mapping.validate().joinToString("\n") }
        check(mapping.columns.map { it.header }.distinct().size==mapping.columns.size) { "중복된 헤더가 있습니다. 안전한 저장을 위해 시트의 열 이름을 고유하게 지정해 주세요." }
        mapping.columns.filter { it.role !in setOf(Role.ID,Role.DELETED) }.forEach { col ->
            val current = grid.cell(mapping.headerRow,col.index).value.trim().ifEmpty { "열 ${A1.col(col.index)}" }
            if (current != col.header) throw Conflict("시트의 열 구성이 바뀌었습니다. 연결 설정에서 매핑을 다시 확인해 주세요.")
        }
        mapping.columns.filter { it.role in setOf(Role.ID,Role.DELETED) }.forEach { col ->
            if (grid.cell(mapping.headerRow,col.index).value != col.header) throw Conflict("거래 식별 열이 이동했습니다. 연결 설정을 다시 확인해 주세요.")
        }
    }
    fun edit(grid: Grid, mapping: TableMapping, original: Record, input: Map<String,String>, delete: Boolean = false): WritePlan {
        verifyStructure(grid,mapping)
        val idCol = mapping.column(Role.ID) ?: error("안전한 편집 설정을 먼저 완료해 주세요.")
        val id = original.id ?: error("거래 ID가 없습니다. 새로고침 후 다시 확인해 주세요.")
        val matches = (mapping.headerRow+1 until minOf(mapping.dataEndRow,grid.rows.size)).filter { grid.cell(it,idCol.index).value == id }
        if (matches.size != 1) throw Conflict("거래가 삭제되거나 ID가 중복되었습니다. 새로고침해 주세요.")
        val row = matches.single()
        check(grid.cell(row,idCol.index).formula==null) { "수식으로 생성한 ID는 수정에 사용할 수 없습니다." }
        mapping.columns.forEach { col -> if (grid.cell(row,col.index) != original.cells[col.key]) throw Conflict("다른 곳에서 이 거래가 변경되었습니다. 새로고침 후 다시 수정해 주세요.") }
        if (delete) {
            val col = mapping.column(Role.DELETED) ?: error("삭제 상태 열이 필요합니다.")
            check(grid.cell(row,col.index).formula == null) { "삭제 상태 셀에 수식이 있어 변경할 수 없습니다." }
            return WritePlan(listOf(CellWrite(row,col.index,Cell("true",ValueKind.BOOLEAN))),id)
        }
        val cells = mapping.columns.filter { !it.readOnly && it.role !in setOf(Role.ID,Role.DELETED) && input.containsKey(it.key) }.mapNotNull { col ->
            check(grid.cell(row,col.index).formula == null) { "수식 셀은 변경할 수 없습니다." }
            val cell = Values.typed(input.getValue(col.key),col,mapping)
            if (cell.value == original.cells[col.key]?.value && cell.kind == original.cells[col.key]?.kind) null else CellWrite(row,col.index,cell)
        }
        return WritePlan(cells,id)
    }
    fun add(grid: Grid, mapping: TableMapping, input: Map<String,String>, id: String = java.util.UUID.randomUUID().toString()): WritePlan {
        verifyStructure(grid,mapping)
        val idCol = mapping.column(Role.ID) ?: error("안전한 편집 설정을 먼저 완료해 주세요.")
        val deleted = mapping.column(Role.DELETED) ?: error("삭제 상태 열이 필요합니다.")
        val business = mapping.columns.filter { it.role !in setOf(Role.ID,Role.DELETED) }
        val row = (mapping.headerRow+1 until mapping.dataEndRow).firstOrNull { r ->
            business.all { col -> val cell=grid.cell(r,col.index);cell.blank || (col.readOnly && cell.formula!=null) } && grid.cell(r,idCol.index).blank && grid.cell(r,deleted.index).blank
        } ?: error("지정한 입력 영역에 빈 행이 없습니다. 시트에서 입력 영역을 확장하고 다시 연결해 주세요.")
        val cells = business.filter { !it.readOnly }.map { col -> CellWrite(row,col.index,Values.typed(input[col.key].orEmpty(),col,mapping)) }
        require(cells.any { !it.cell.blank }) { "입력한 내용이 없습니다." }
        return WritePlan(cells + CellWrite(row,idCol.index,Cell(id,ValueKind.STRING)) + CellWrite(row,deleted.index,Cell("false",ValueKind.BOOLEAN)),id)
    }
}
