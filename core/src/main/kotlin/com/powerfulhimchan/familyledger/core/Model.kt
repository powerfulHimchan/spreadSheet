package com.powerfulhimchan.familyledger.core

import java.math.BigDecimal
import java.time.LocalDate

enum class FieldType { TEXT, INTEGER, DECIMAL, CURRENCY, PERCENT, DATE, TIME, DATETIME, BOOLEAN, ENUM, PHONE, EMAIL, URL }
enum class Role { NONE, DATE, AMOUNT, CATEGORY, DESCRIPTION, PAYMENT, AUTHOR, DIRECTION, ID, DELETED }
val Role.label: String get() = when (this) {
    Role.NONE -> "역할 없음"; Role.DATE -> "날짜"; Role.AMOUNT -> "금액"; Role.CATEGORY -> "카테고리"
    Role.DESCRIPTION -> "내용·사용처"; Role.PAYMENT -> "결제수단"; Role.AUTHOR -> "작성자"
    Role.DIRECTION -> "수입·지출 구분"; Role.ID -> "거래 ID"; Role.DELETED -> "삭제 여부"
}
enum class DateMode { FULL_DATE, DAY_OF_MONTH }
enum class NumberStyle { DOT_DECIMAL, COMMA_DECIMAL }
enum class ValueKind { EMPTY, STRING, NUMBER, BOOLEAN, ERROR }

data class Cell(
    val value: String = "",
    val kind: ValueKind = if (value.isEmpty()) ValueKind.EMPTY else ValueKind.STRING,
    val format: String = "",
    val pattern: String = "",
    val formula: String? = null,
    val options: List<String> = emptyList(),
    val validationRange: String? = null,
    val checkbox: Boolean = false
) {
    val blank get() = kind == ValueKind.EMPTY && formula == null
}

data class Grid(
    val spreadsheetId: String,
    val sheetId: Int,
    val title: String,
    val rows: List<List<Cell>>,
    val rowCount: Int = rows.size,
    val columnCount: Int = rows.maxOfOrNull { it.size } ?: 0,
    val complete: Boolean = true,
    val revision: String? = null
) {
    fun cell(row: Int, col: Int): Cell = rows.getOrNull(row)?.getOrNull(col) ?: Cell()
}

data class Column(
    val key: String,
    val header: String,
    val index: Int,
    val type: FieldType,
    val role: Role = Role.NONE,
    val readOnly: Boolean = false,
    val options: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val strictOptions: Boolean = false,
    val required: Boolean = false,
    val validationRange: String? = null,
    val dateMode: DateMode = DateMode.FULL_DATE,
    val enumNumeric: Boolean = false
)

data class TableMapping(
    val spreadsheetId: String,
    val sheetId: Int,
    val title: String,
    val headerRow: Int,
    val firstColumn: Int,
    val endColumn: Int,
    val dataEndRow: Int,
    val columns: List<Column>,
    val year: Int = LocalDate.now().year,
    val month: Int? = null,
    val numberStyle: NumberStyle = NumberStyle.DOT_DECIMAL,
    val expenseValues: Set<String> = setOf("지출", "출금", "expense", "debit"),
    val incomeValues: Set<String> = setOf("수입", "입금", "income", "credit"),
    val bookTitle: String = ""
) {
    val key get() = "$spreadsheetId:$sheetId:$headerRow:$firstColumn:$endColumn"
    val displayName get() = if(bookTitle.isBlank()) title else "$bookTitle · $title"
    fun column(role: Role) = columns.firstOrNull { it.role == role }
    /** Move a unique analysis role without removing the underlying input columns or values. */
    fun withRole(columnKey: String, role: Role): TableMapping {
        require(columns.any { it.key == columnKey }) { "선택한 열을 찾을 수 없습니다." }
        return copy(columns = columns.map { column ->
            when {
                column.key == columnKey -> column.copy(role = role, type = when (role) {
                    Role.DATE -> FieldType.DATE
                    Role.AMOUNT -> if (column.type in setOf(FieldType.INTEGER, FieldType.CURRENCY, FieldType.DECIMAL)) column.type else FieldType.CURRENCY
                    else -> column.type
                })
                role != Role.NONE && column.role == role -> column.copy(role = Role.NONE)
                else -> column
            }
        })
    }
    fun validate(): List<String> = buildList {
        if (firstColumn < 0 || endColumn <= firstColumn || headerRow < 0 || dataEndRow <= headerRow + 1) add("표 범위를 확인해 주세요.")
        if (month != null && month !in 1..12) add("월은 1~12 사이여야 합니다.")
        if (year !in 1900..9999) add("연도를 확인해 주세요.")
        columns.filter { it.role != Role.NONE }.groupBy { it.role }.filterValues { it.size > 1 }.forEach { (role, matches) ->
            add("${role.label} 역할이 여러 열에 지정되어 있습니다: ${matches.joinToString { "${A1.col(it.index)}열(${it.header})" }}. 한 열만 선택해 주세요.")
        }
        if (columns.map { it.key }.distinct().size != columns.size) add("컬럼 식별자가 중복되었습니다.")
        column(Role.AMOUNT)?.let { if (it.type !in setOf(FieldType.INTEGER, FieldType.CURRENCY, FieldType.DECIMAL)) add("금액 역할에는 숫자 타입을 지정해 주세요.") }
        column(Role.DATE)?.let { if (it.type !in setOf(FieldType.DATE, FieldType.DATETIME)) add("날짜 역할에는 날짜 타입을 지정해 주세요.") }
    }
}

data class ParseIssue(val row: Int, val column: String, val message: String)
data class Record(val sourceRow: Int, val id: String?, val cells: Map<String, Cell>, val date: LocalDate?, val amount: BigDecimal?, val income: Boolean, val category: String, val description: String, val payment: String, val author: String)
data class ParsedTable(val records: List<Record>, val issues: List<ParseIssue>)
data class Totals(val income: BigDecimal, val expense: BigDecimal, val count: Int) {
    val balance get() = income.subtract(expense)
}

object A1 {
    fun col(index: Int): String {
        require(index >= 0)
        var n = index + 1
        var out = ""
        while (n > 0) { n--; out = ('A' + n % 26) + out; n /= 26 }
        return out
    }
    fun index(text: String): Int {
        require(text.matches(Regex("[A-Za-z]+"))) { "열은 A, B, AA 형태로 입력해 주세요." }
        return text.uppercase().fold(0) { n, c -> Math.addExact(Math.multiplyExact(n, 26), c - 'A' + 1) } - 1
    }
    fun quote(title: String) = "'${title.replace("'", "''")}'"
    fun spreadsheetId(input: String): String? {
        val value = input.trim()
        if (value.matches(Regex("[A-Za-z0-9_-]{20,}"))) return value
        return Regex("^https://docs\\.google\\.com/spreadsheets/d/([A-Za-z0-9_-]+)(?:/|$)").find(value)?.groupValues?.get(1)
    }
}
