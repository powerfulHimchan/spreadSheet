package com.powerfulhimchan.familyledger.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Values {
    private val epoch = LocalDate.of(1899, 12, 30)
    private val dates = listOf(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("uuuu/M/d"), DateTimeFormatter.ofPattern("uuuu.M.d"))
    fun number(text: String, style: NumberStyle = NumberStyle.DOT_DECIMAL): BigDecimal? {
        var value = text.trim().replace(Regex("[₩$€£\\s]"), "")
        val negative = value.startsWith("(") && value.endsWith(")")
        if (negative) value = value.substring(1, value.length - 1)
        value = value.replace(Regex("(?i)(KRW|USD|EUR|원)$"), "")
        if (style == NumberStyle.DOT_DECIMAL) {
            if (',' in value && !value.matches(Regex("[+-]?[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?"))) return null
            value = value.replace(",", "")
        } else {
            if ('.' in value && !value.matches(Regex("[+-]?[0-9]{1,3}(\\.[0-9]{3})+(,[0-9]+)?"))) return null
            value = value.replace(".", "").replace(',', '.')
        }
        if (!value.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) return null
        return value.toBigDecimalOrNull()?.let { if (negative) it.negate() else it }
    }
    fun isoDate(text: String): LocalDate? = dates.firstNotNullOfOrNull { runCatching { LocalDate.parse(text.trim(), it.withResolverStyle(java.time.format.ResolverStyle.STRICT)) }.getOrNull() }
    fun date(cell: Cell, col: Column, mapping: TableMapping): LocalDate? {
        if (col.dateMode == DateMode.DAY_OF_MONTH) {
            val day = number(cell.value.removeSuffix("일"))?.let { runCatching { it.intValueExact() }.getOrNull() } ?: return null
            return mapping.month?.let { runCatching { LocalDate.of(mapping.year, it, day) }.getOrNull() }
        }
        if (cell.kind == ValueKind.NUMBER && (cell.format in setOf("DATE", "DATE_TIME") || col.type in setOf(FieldType.DATE, FieldType.DATETIME))) {
            val serial = number(cell.value) ?: return null
            return runCatching { epoch.plusDays(serial.toLong()) }.getOrNull()
        }
        return isoDate(cell.value) ?: runCatching { LocalDateTime.parse(cell.value).toLocalDate() }.getOrNull()
    }
    fun serial(date: LocalDate): BigDecimal = BigDecimal(java.time.temporal.ChronoUnit.DAYS.between(epoch, date))

    /** Values remain typed; text beginning with '=' never becomes a formula. */
    fun typed(input: String, col: Column, mapping: TableMapping): Cell {
        val value = input.trim()
        if (value.isEmpty()) { require(!col.required) { "필수 항목입니다." }; return Cell() }
        require(!col.readOnly) { "수식 또는 읽기 전용 열입니다." }
        if (col.strictOptions) require(value in col.options) { "시트에 등록된 선택지를 골라 주세요." }
        return when (col.type) {
            FieldType.INTEGER, FieldType.DECIMAL, FieldType.CURRENCY, FieldType.PERCENT -> {
                var n = number(value.removeSuffix("%"), mapping.numberStyle) ?: error("숫자를 입력해 주세요.")
                if (col.type == FieldType.INTEGER) require(n.stripTrailingZeros().scale() <= 0) { "정수를 입력해 주세요." }
                if (col.type == FieldType.PERCENT) n = n.movePointLeft(2)
                require(n.abs() <= BigDecimal("9007199254740991") && n.precision() <= 15) { "구글 시트가 정확히 보관할 수 있는 숫자 범위를 넘었습니다." }
                Cell(n.toPlainString(), ValueKind.NUMBER, when (col.type) { FieldType.CURRENCY -> "CURRENCY"; FieldType.PERCENT -> "PERCENT"; else -> "NUMBER" })
            }
            FieldType.DATE -> {
                val date = isoDate(value) ?: error("날짜는 yyyy-MM-dd 형식으로 입력해 주세요.")
                if (col.dateMode == DateMode.DAY_OF_MONTH) {
                    require(date.year == mapping.year && date.monthValue == mapping.month) { "선택한 월에 해당하는 날짜를 입력해 주세요." }
                    Cell(date.dayOfMonth.toString(), ValueKind.NUMBER)
                } else Cell(serial(date).toPlainString(), ValueKind.NUMBER, "DATE")
            }
            FieldType.TIME -> {
                val t = runCatching { LocalTime.parse(value) }.getOrNull() ?: error("시간은 HH:mm 형식으로 입력해 주세요.")
                Cell(BigDecimal(t.toSecondOfDay()).divide(BigDecimal(86400), 12, java.math.RoundingMode.HALF_UP).toPlainString(), ValueKind.NUMBER, "TIME")
            }
            FieldType.DATETIME -> {
                val dt = runCatching { LocalDateTime.parse(value.replace(' ', 'T')) }.getOrNull() ?: error("날짜와 시간은 yyyy-MM-ddTHH:mm 형식으로 입력해 주세요.")
                val time = BigDecimal(dt.toLocalTime().toSecondOfDay()).divide(BigDecimal(86400), 12, java.math.RoundingMode.HALF_UP)
                Cell(serial(dt.toLocalDate()).add(time).toPlainString(), ValueKind.NUMBER, "DATE_TIME")
            }
            FieldType.BOOLEAN -> { require(value.lowercase() in setOf("true", "false")) { "체크 여부를 선택해 주세요." }; Cell(value.lowercase(), ValueKind.BOOLEAN) }
            FieldType.ENUM -> if(col.enumNumeric) Cell((value.toBigDecimalOrNull() ?: error("숫자 선택지를 확인해 주세요.")).toPlainString(),ValueKind.NUMBER) else Cell(value,ValueKind.STRING)
            FieldType.EMAIL -> { require(value.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "이메일 형식을 확인해 주세요." };Cell(value,ValueKind.STRING) }
            FieldType.URL -> { require(runCatching { java.net.URI(value).scheme?.isNotBlank()==true }.getOrDefault(false)) { "URL 형식을 확인해 주세요." };Cell(value,ValueKind.STRING) }
            else -> Cell(value, ValueKind.STRING)
        }
    }

    fun input(cell: Cell, col: Column, mapping: TableMapping): String = when {
        cell.blank -> ""
        col.type == FieldType.DATE -> date(cell,col,mapping)?.toString() ?: cell.value
        col.type == FieldType.PERCENT -> number(cell.value)?.movePointRight(2)?.stripTrailingZeros()?.toPlainString()?.let { if(mapping.numberStyle==NumberStyle.COMMA_DECIMAL) it.replace('.',',') else it } ?: cell.value
        cell.kind == ValueKind.NUMBER && col.type in setOf(FieldType.INTEGER,FieldType.CURRENCY,FieldType.DECIMAL) -> if(mapping.numberStyle==NumberStyle.COMMA_DECIMAL) cell.value.replace('.',',') else cell.value
        col.type in setOf(FieldType.TIME, FieldType.DATETIME) && cell.kind == ValueKind.NUMBER -> {
            val serial = number(cell.value) ?: BigDecimal.ZERO
            val seconds = serial.remainder(BigDecimal.ONE).multiply(BigDecimal(86400)).setScale(0,java.math.RoundingMode.HALF_UP).toLong().coerceIn(0,86399)
            val time = LocalTime.ofSecondOfDay(seconds).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            if (col.type == FieldType.TIME) time else "${epoch.plusDays(serial.toLong())}T$time"
        }
        else -> cell.value
    }
}
