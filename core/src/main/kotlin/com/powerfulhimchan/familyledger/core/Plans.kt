package com.powerfulhimchan.familyledger.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

enum class PlanKind(val label: String) { INCOME("월 수입"), EXPENSE("월 지출"), TRANSFER("이체 정보"), SCHEDULE("결제 일정") }
enum class PlanField(val label: String) {
    TITLE("항목명"), AMOUNT("금액"), PARTY("담당·결제정보"), NOTE("메모·세부항목"), BANK("은행"), DAY("매월 날짜"), MATURITY("만기일")
}
data class PlanRegion(
    val id: String,
    val name: String,
    val kind: PlanKind,
    val firstRow: Int,
    val endRow: Int,
    val fields: Map<PlanField, Int>,
    val carryTitle: Boolean = false,
    val anchors: Map<String, String> = emptyMap(),
    val excludedRows: Set<Int> = emptySet()
)
data class PlanMapping(val regions: List<PlanRegion>, val numberStyle: NumberStyle = NumberStyle.DOT_DECIMAL) {
    fun validate(): List<String> = buildList {
        if (regions.isEmpty()) add("불러올 영역을 하나 이상 선택해 주세요.")
        if (regions.map { it.id }.distinct().size != regions.size) add("영역 식별자가 중복되었습니다.")
        regions.forEach { r ->
            if (r.firstRow < 0 || r.endRow <= r.firstRow || r.endRow > 10000) add("${r.name}: 시작·마지막 행을 확인해 주세요.")
            if (r.fields.values.any { it !in 0 until 200 }) add("${r.name}: 열은 A~GR 범위에서 선택해 주세요.")
            if (PlanField.TITLE !in r.fields && PlanField.NOTE !in r.fields) add("${r.name}: 항목명이나 세부항목 열이 필요합니다.")
            if (r.kind != PlanKind.SCHEDULE && PlanField.AMOUNT !in r.fields) add("${r.name}: 금액 열을 선택해 주세요.")
            if (r.fields.values.distinct().size != r.fields.size) add("${r.name}: 한 열을 여러 정보에 중복 지정하지 마세요.")
        }
        // One monetary cell must not become two items in the same monthly total.
        regions.filter { it.kind in setOf(PlanKind.INCOME, PlanKind.EXPENSE) }.forEachIndexed { index, a ->
            regions.filter { it.kind in setOf(PlanKind.INCOME, PlanKind.EXPENSE) }.drop(index + 1).forEach { b ->
                if (a.fields[PlanField.AMOUNT] == b.fields[PlanField.AMOUNT] && maxOf(a.firstRow,b.firstRow) < minOf(a.endRow,b.endRow))
                    add("${a.name} / ${b.name}: 같은 금액 셀이 월 합계에 중복 포함됩니다. 행 범위를 나눠 주세요.")
            }
        }
    }
}
data class PlanItem(
    val regionId: String, val sourceRow: Int, val kind: PlanKind, val title: String,
    val amount: BigDecimal?, val party: String, val note: String, val bank: String,
    val day: Int?, val maturity: LocalDate?, val cells: Map<PlanField, Cell>,
    val parentRow: Int? = null
) {
    val key get() = "$regionId:$sourceRow"
    val paused get() = Regex("보류|중지|중단|일시정지|paused?",RegexOption.IGNORE_CASE).containsMatchIn(note)
    fun scheduledDate(month: YearMonth): LocalDate? = day?.let { month.atDay(minOf(it,month.lengthOfMonth())) }
}
data class PlanExclusion(val regionId: String, val row: Int, val reason: String)
data class ParsedPlan(val items: List<PlanItem> = emptyList(), val issues: List<ParseIssue> = emptyList(), val excluded: List<PlanExclusion> = emptyList()) {
    val income get() = total(PlanKind.INCOME)
    val expense get() = total(PlanKind.EXPENSE)
    val difference get() = income - expense
    val hasIncome get() = items.any { it.kind == PlanKind.INCOME && it.amount != null }
    val hasExpense get() = items.any { it.kind == PlanKind.EXPENSE && it.amount != null }
    private fun total(kind: PlanKind) = items.filter { it.kind == kind }.fold(BigDecimal.ZERO) { sum,item -> sum + (item.amount ?: BigDecimal.ZERO) }
}

/** Read a monthly plan independently of dated, posted transactions. No source cells are changed. */
object Plans {
    private val summary = Regex("^(?:(?:월|수입|지출|전체|총|중간|grand)\\s*)*(?:합계|소계|총계|차액|잔액|sub\\s*total|total|balance)(?:\\s*[:：].*)?$",RegexOption.IGNORE_CASE)
    private fun summaryFormula(cell: Cell, column: Int?, row: Int): Boolean {
        val col = column?.let(A1::col) ?: return false
        val formula = cell.formula?.replace("$", "") ?: return false
        if (!Regex("(?i)(?:SUM|SUBTOTAL)\\s*\\(").containsMatchIn(formula)) return false
        return Regex("(?i)(?<![A-Z])$col([0-9]+):$col([0-9]+)").findAll(formula).any {
            it.groupValues[1].toInt() <= it.groupValues[2].toInt() && it.groupValues[2].toInt() <= row
        }
    }
    fun monthlyDay(cell: Cell): Int? {
        val text=cell.value.trim()
        val value=Regex("^(?:매월\\s*)?([0-9]{1,2})(?:\\.0)?\\s*(?:일)?$").matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
        return value?.takeIf { it in 1..31 }
    }
    fun maturity(cell: Cell): LocalDate? = if (cell.kind == ValueKind.NUMBER && cell.format == "DATE")
        Values.date(cell,Column("date","만기일",0,FieldType.DATE),TableMapping("",0,"",0,0,1,2,emptyList()))
        else Values.isoDate(cell.value)

    fun parse(grid: Grid, mapping: PlanMapping): ParsedPlan {
        val items=mutableListOf<PlanItem>();val issues=mutableListOf<ParseIssue>();val excluded=mutableListOf<PlanExclusion>()
        val counted=mutableSetOf<String>()
        mapping.regions.forEach { region ->
            var parentTitle="";var parentRow: Int?=null
            for (row in region.firstRow until minOf(region.endRow,grid.rows.size)) {
                val cells=region.fields.mapValues { (_,col) -> grid.cell(row,col) }
                fun cell(field: PlanField)=cells[field] ?: Cell()
                fun text(field: PlanField)=cell(field).value.trim()
                if (cells.values.all { it.blank }) continue
                if (row in region.excludedRows) { excluded+=PlanExclusion(region.id,row,"설정에서 제외");continue }
                val title=text(PlanField.TITLE)
                if (summary.matches(title.replace(Regex("\\s+"),"")) || summaryFormula(cell(PlanField.AMOUNT),region.fields[PlanField.AMOUNT],row)) {
                    excluded+=PlanExclusion(region.id,row,"합계·소계");parentTitle="";parentRow=null;continue
                }
                if (title.isNotBlank()) { parentTitle=title;parentRow=row }
                val amountCell=cell(PlanField.AMOUNT)
                // An empty transfer amount with an adjacent due day is not an additional transfer.
                if (region.kind == PlanKind.TRANSFER && amountCell.blank) continue
                if (region.kind != PlanKind.SCHEDULE && amountCell.blank && title.isBlank()) continue
                val amount=if (region.kind==PlanKind.SCHEDULE && amountCell.blank) null else Values.number(amountCell.value,
                    if(amountCell.kind==ValueKind.NUMBER) NumberStyle.DOT_DECIMAL else mapping.numberStyle)
                if (region.kind != PlanKind.SCHEDULE && amount == null)
                    issues+=ParseIssue(row+1,"${region.name} · 금액","금액이 비어 있거나 숫자로 읽을 수 없습니다. 합계에서 제외됩니다.")
                val resolvedTitle=title.ifBlank { if(region.carryTitle) parentTitle else "" }.ifBlank { text(PlanField.NOTE) }.ifBlank { "항목명 없음" }
                if (resolvedTitle=="항목명 없음") issues+=ParseIssue(row+1,region.name,"항목명을 확인해 주세요.")
                if (region.kind in setOf(PlanKind.INCOME,PlanKind.EXPENSE)) {
                    val identity="$row:${region.fields[PlanField.AMOUNT]}"
                    if (!counted.add(identity)) { excluded+=PlanExclusion(region.id,row,"다른 영역과 중복된 금액 셀");continue }
                }
                val day=monthlyDay(cell(PlanField.DAY));val maturity=maturity(cell(PlanField.MATURITY))
                if (!cell(PlanField.DAY).blank && day==null) issues+=ParseIssue(row+1,"${region.name} · 매월 날짜","1~31일 형식인지 확인해 주세요.")
                if (!cell(PlanField.MATURITY).blank && maturity==null) issues+=ParseIssue(row+1,"${region.name} · 만기일","전체 날짜를 확인해 주세요.")
                cells.filterValues { it.kind==ValueKind.ERROR }.forEach { (field,_) -> issues+=ParseIssue(row+1,field.label,"원본 셀에 오류가 있습니다.") }
                val noteCell=cell(PlanField.NOTE)
                val note=if(summaryFormula(noteCell,region.fields[PlanField.AMOUNT],row+1)) "" else text(PlanField.NOTE)
                items+=PlanItem(region.id,row,region.kind,resolvedTitle,amount,text(PlanField.PARTY),note,text(PlanField.BANK),day,maturity,cells,
                    parentRow.takeIf { region.carryTitle && title.isBlank() })
            }
        }
        return ParsedPlan(items,issues.distinct(),excluded)
    }
    fun connection(grid: Grid, plan: PlanMapping, bookTitle: String="") = TableMapping(grid.spreadsheetId,grid.sheetId,grid.title,0,0,
        grid.columnCount.coerceAtLeast(1),minOf(grid.rowCount,10000).coerceAtLeast(2),emptyList(),bookTitle=bookTitle,plan=plan)

    fun scheduleItems(plan: ParsedPlan): List<PlanItem> {
        val transfers=plan.items.filter { it.kind==PlanKind.TRANSFER && it.day!=null }
        val pausedExpenses=plan.items.filter { it.kind==PlanKind.EXPENSE && it.paused }.map { it.sourceRow }.toSet()
        return plan.items.filter { item -> item.day!=null && !item.paused &&
            (item.kind!=PlanKind.TRANSFER || (item.parentRow ?: item.sourceRow) !in pausedExpenses) &&
            (item.kind!=PlanKind.EXPENSE || transfers.none { it.sourceRow==item.sourceRow || it.parentRow==item.sourceRow }) }
    }

    fun verifyStructure(grid: Grid, plan: PlanMapping) {
        check(plan.validate().isEmpty()) { plan.validate().joinToString("\n") }
        plan.regions.forEach { region ->
            check(region.fields.values.all { it < grid.columnCount }) { "${region.name}: 열이 이동했습니다. 불러오기 설정을 확인해 주세요." }
            region.anchors.forEach { (coordinate,value) ->
                val (row,col)=coordinate.split(':').map(String::toInt)
                if(grid.cell(row,col).value != value) throw Conflict("${region.name}: 표 구성이 바뀌었습니다. 영역 설정을 다시 확인해 주세요.")
            }
        }
    }
}

/** Semantic headers propose regions; every proposal remains editable before saving. */
object PlanDetection {
    private val income=setOf("월수입","수입","수입내역","수입항목","monthlyincome","income","incomes")
    private val expense=setOf("월지출","지출","지출내역","지출항목","고정비","고정지출","monthlyexpenses","recurringexpenses","expense","expenses")
    private val banks=setOf("이체은행to","이체은행","은행","출금은행","bank","tobank","transferbank")
    private val days=setOf("이체날짜","이체일","결제일","출금일","납부일","매월날짜","day","paymentday","transferday")
    private val maturities=setOf("만기일","만기","종료일","maturity","maturitydate","enddate")
    private fun n(value: String)=Detection.normalize(value).replace(Regex("[():·._-]"),"")
    private fun amount(value: String)=n(value) in setOf("금액","월금액","월지출금액","월수입금액","amount","monthlyamount","cost")
    fun suggest(grid: Grid): PlanMapping {
        val regions=mutableListOf<PlanRegion>();val last=minOf(grid.rowCount,10000)
        fun anchor(row: Int, fields: Map<PlanField,Int>)=fields.values.distinct().mapNotNull { col -> grid.cell(row,col).value.takeIf { it.isNotBlank() }?.let { "$row:$col" to it } }.toMap()
        fun sample(col: Int, row: Int)= (row+1 until minOf(grid.rows.size,row+45)).map { grid.cell(it,col) }.filter { !it.blank }
        for(row in 0 until minOf(grid.rows.size,30)) for(col in 0 until minOf(grid.columnCount,200)) {
            val header=n(grid.cell(row,col).value)
            val kind=when(header) { in income -> PlanKind.INCOME; in expense -> PlanKind.EXPENSE;else -> null }
            if(kind!=null && row+1<last && amount(grid.cell(row,col+1).value)) {
                val fields=linkedMapOf(PlanField.TITLE to col,PlanField.AMOUNT to col+1)
                if(kind==PlanKind.EXPENSE) {
                    for((field,offset) in listOf(PlanField.PARTY to 2,PlanField.NOTE to 3)) {
                        val index=col+offset
                        if(index<grid.columnCount && grid.cell(row,index).blank && sample(index,row).any { it.kind==ValueKind.STRING && Values.number(it.value)==null }) fields[field]=index
                    }
                }
                regions+=PlanRegion("${kind.name}:$row:$col",grid.cell(row,col).value,kind,row+1,last,fields,anchors=anchor(row,fields))
            }
            if(header in banks && row+1<last && col>0 && amount(grid.cell(row,col-1).value)) {
                val fields=linkedMapOf(PlanField.AMOUNT to col-1,PlanField.BANK to col)
                (col+1 until minOf(col+4,grid.columnCount)).forEach { index -> when(n(grid.cell(row,index).value)) {
                    in days -> fields[PlanField.DAY]=index
                    in maturities -> fields[PlanField.MATURITY]=index
                } }
                if(PlanField.DAY in fields || PlanField.MATURITY in fields) {
                    // The note immediately after maturity belongs to this block only when its header is empty.
                    fields[PlanField.MATURITY]?.plus(1)?.takeIf { it<grid.columnCount && grid.cell(row,it).blank && sample(it,row).any { c -> c.kind==ValueKind.STRING } }?.let { fields[PlanField.NOTE]=it }
                    regions+=PlanRegion("TRANSFER:$row:$col","이체 정보",PlanKind.TRANSFER,row+1,last,fields,anchors=anchor(row,fields))
                }
            }
        }
        // Link an explicit transfer block to the nearest expense title column on its left.
        val linked=regions.map { region ->
            if(region.kind==PlanKind.EXPENSE) {
                val transfer=regions.filter { it.kind==PlanKind.TRANSFER && it.fields.getValue(PlanField.AMOUNT)>region.fields.getValue(PlanField.AMOUNT) }
                    .minByOrNull { it.fields.getValue(PlanField.AMOUNT) }
                val day=transfer?.fields?.get(PlanField.DAY)
                if(day==null) region else region.copy(fields=region.fields+(PlanField.DAY to day))
            } else if(region.kind!=PlanKind.TRANSFER) region else {
                val left=regions.filter { it.kind==PlanKind.EXPENSE && it.fields.getValue(PlanField.TITLE)<region.fields.getValue(PlanField.AMOUNT) }
                    .maxByOrNull { it.fields.getValue(PlanField.TITLE) }
                if(left==null) region else region.copy(fields=region.fields+(PlanField.TITLE to left.fields.getValue(PlanField.TITLE)),carryTitle=true)
            }
        }.toMutableList()
        // Compact card/payment schedules often have no header row: label | 25일 | bank.
        for(col in 0 until minOf(grid.columnCount-2,198)) {
            val matches=(0 until minOf(grid.rows.size,100)).filter { row ->
                Regex("카드.*(?:이체일|결제일)|card.*(?:payment|due)",RegexOption.IGNORE_CASE).containsMatchIn(grid.cell(row,col).value) &&
                    Plans.monthlyDay(grid.cell(row,col+1))!=null && grid.cell(row,col+2).value.isNotBlank()
            }
            if(matches.isNotEmpty()) {
                val fields=mapOf(PlanField.TITLE to col,PlanField.DAY to col+1,PlanField.BANK to col+2)
                linked+=PlanRegion("SCHEDULE:$col","카드 결제 일정",PlanKind.SCHEDULE,matches.first(),matches.last()+1,fields)
            }
        }
        return PlanMapping(linked.distinctBy { it.id })
    }
}
