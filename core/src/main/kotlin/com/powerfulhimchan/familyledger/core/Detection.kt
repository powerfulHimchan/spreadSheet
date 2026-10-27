package com.powerfulhimchan.familyledger.core

object Detection {
    private val hints = mapOf(
        Role.DATE to setOf("날짜", "일자", "거래일", "사용일", "결제일", "date", "transactiondate"),
        Role.AMOUNT to setOf("금액", "사용금액", "지출액", "결제금액", "amount", "cost", "가격"),
        Role.CATEGORY to setOf("카테고리", "분류", "항목", "category"),
        Role.DESCRIPTION to setOf("사용처", "내용", "내역", "거래내용", "상호", "description", "merchant"),
        Role.PAYMENT to setOf("결제방법", "결제수단", "카드", "payment", "paymentmethod"),
        Role.AUTHOR to setOf("작성자", "사용자", "이름", "담당자", "author", "member"),
        Role.DIRECTION to setOf("구분", "수입지출", "수입/지출", "type", "direction"),
        Role.ID to setOf("id", "uuid", "거래id", "__ledger_id"),
        Role.DELETED to setOf("삭제됨", "__ledger_deleted")
    )
    fun normalize(value: String) = value.trim().lowercase().replace(Regex("\\s+"), "")
    fun role(header: String): Role = hints.entries.firstOrNull { normalize(header) in it.value }?.key ?: Role.NONE

    /** Recommend contiguous header blocks, never the entire occupied worksheet. */
    fun candidates(grid: Grid, year: Int): List<TableMapping> {
        val out = mutableListOf<Pair<Int, TableMapping>>()
        for (r in 0 until minOf(grid.rows.size, 50)) {
            var c = 0
            while (c < grid.columnCount.coerceAtMost(200)) {
                if (grid.cell(r, c).value.isBlank() || grid.cell(r, c).kind != ValueKind.STRING || grid.cell(r,c).formula != null) { c++; continue }
                val start = c
                while (c < grid.columnCount.coerceAtMost(200) && grid.cell(r,c).value.isNotBlank() && grid.cell(r,c).kind == ValueKind.STRING && grid.cell(r,c).formula == null) c++
                val score = (start until c).count { role(grid.cell(r,it).value) != Role.NONE }
                if (c - start >= 2 && score >= 2) out += score to mapping(grid, r, start, c, minOf(grid.rowCount, 10000), year)
            }
        }
        return out.sortedWith(compareByDescending<Pair<Int,TableMapping>> { it.first }.thenBy { it.second.headerRow }).map { it.second }.distinctBy { it.key }
    }

    fun mapping(grid: Grid, header: Int, start: Int, end: Int, lastRow: Int, year: Int, old: TableMapping? = null): TableMapping {
        require(header >= 0 && start >= 0 && end > start && end <= grid.columnCount && lastRow > header + 1 && lastRow <= minOf(grid.rowCount, 10000)) { "헤더·열·마지막 행 범위를 확인해 주세요." }
        val month = Regex("(?:^|[^0-9])(1[0-2]|[1-9])\\s*월").find(grid.title)?.groupValues?.get(1)?.toInt()
            ?: Regex("(?:19|20)\\d{2}[-./](0?[1-9]|1[0-2])(?:$|[^0-9])").find(grid.title)?.groupValues?.get(1)?.toInt()
        val seen = mutableMapOf<String, Int>()
        val columns = (start until end).map { index ->
            val headerText = grid.cell(header, index).value.trim().ifEmpty { "열 ${A1.col(index)}" }
            val ordinal = seen.getOrDefault(headerText, 0) + 1
            seen[headerText] = ordinal
            val key = "$headerText#$ordinal"
            val samples = (header + 1 until minOf(lastRow, grid.rows.size)).map { grid.cell(it, index) }
            val filled = samples.filter { !it.blank }.take(100)
            val preserved = old?.columns?.firstOrNull { it.key == key }
            val inferredRole = role(headerText)
            val inferredType = infer(filled)
            val dayMode = month != null && inferredRole == Role.DATE && filled.isNotEmpty() && filled.all { it.format != "DATE" && it.value.matches(Regex("(?:[1-9]|[12][0-9]|3[01])(?:\\.0)?일?")) }
            val options = filled.flatMap { it.options }.distinct()
            val labels = if(inferredRole == Role.PAYMENT) codeLabels(grid, filled) else emptyMap()
            val readOnly = samples.any { it.formula != null } || filled.any { it.validationRange != null && it.options.isEmpty() }
            preserved?.copy(index = index, readOnly = readOnly || preserved.readOnly,
                options = if (options.isNotEmpty()) options else preserved.options,
                labels = if (labels.isNotEmpty()) labels else preserved.labels)
                ?: Column(key, headerText, index, if (dayMode) FieldType.DATE else inferredType, inferredRole, readOnly,
                    if (options.isNotEmpty()) options else if (labels.isNotEmpty()) labels.keys.toList() else if (inferredType == FieldType.ENUM) filled.map { it.value }.distinct() else emptyList(),
                    labels, options.isNotEmpty(), inferredRole in setOf(Role.DATE, Role.AMOUNT), filled.firstOrNull { it.validationRange != null }?.validationRange,
                    if (dayMode) DateMode.DAY_OF_MONTH else DateMode.FULL_DATE,filled.isNotEmpty() && filled.all { it.kind==ValueKind.NUMBER })
        }
        val uniqueRoles = columns.toMutableList()
        columns.filter { it.role != Role.NONE }.groupBy { it.role }.filterValues { it.size > 1 }.forEach { (role, matches) ->
            // A header alias is a suggestion, not a reason to choose the wrong monetary total.
            // Keep one explicit previous choice; otherwise require the user to select a column.
            val previous = old?.columns?.filter { it.role == role }?.singleOrNull()
            val retainedKey = previous?.key?.takeIf { key -> matches.any { it.key == key } }
            matches.forEach { column ->
                if (column.key != retainedKey) {
                    val position = uniqueRoles.indexOfFirst { it.key == column.key }
                    uniqueRoles[position] = column.copy(role = Role.NONE)
                }
            }
        }
        return TableMapping(grid.spreadsheetId, grid.sheetId, grid.title, header, start, end, lastRow, uniqueRoles, year, month)
            .let { if (old == null) it else it.copy(month = old.month, numberStyle = old.numberStyle, expenseValues = old.expenseValues, incomeValues = old.incomeValues,bookTitle=old.bookTitle) }
    }

    fun infer(cells: List<Cell>): FieldType {
        if (cells.isEmpty()) return FieldType.TEXT
        if (cells.any { it.checkbox }) return FieldType.BOOLEAN
        if (cells.any { it.options.isNotEmpty() || it.validationRange != null }) return FieldType.ENUM
        // Inconsistent metadata is ambiguous; don't constrain input to a guessed type.
        val formats = cells.map { it.format }.filter { it.isNotEmpty() }.distinct()
        if (formats.size == 1) when (formats.single()) {
            "TEXT" -> return FieldType.TEXT
            "DATE" -> return FieldType.DATE
            "TIME" -> return FieldType.TIME
            "DATE_TIME" -> return FieldType.DATETIME
            "CURRENCY" -> return FieldType.CURRENCY
            "PERCENT" -> return FieldType.PERCENT
            "NUMBER", "SCIENTIFIC" -> {
                if (cells.any { it.pattern.matches(Regex("0{2,}")) }) return FieldType.TEXT
                return if (cells.all { Values.number(it.value)?.stripTrailingZeros()?.scale()?.let { s -> s <= 0 } == true }) FieldType.INTEGER else FieldType.DECIMAL
            }
        }
        if (formats.size > 1) return FieldType.TEXT
        if (cells.any { it.value.matches(Regex("0[0-9]+")) }) return FieldType.TEXT
        fun ratio(predicate: (Cell) -> Boolean) = cells.count(predicate).toDouble() / cells.size
        if (ratio { it.value.lowercase() in setOf("true", "false") } >= .95) return FieldType.BOOLEAN
        if (ratio { it.value.matches(Regex("[0-9]{4}[-./][0-9]{1,2}[-./][0-9]{1,2}")) } >= .95) return FieldType.DATE
        if (ratio { Values.number(it.value) != null } >= .95) return if (cells.mapNotNull { Values.number(it.value) }.all { it.stripTrailingZeros().scale() <= 0 }) FieldType.INTEGER else FieldType.DECIMAL
        val distinct = cells.map { it.value }.distinct().size
        if (cells.size >= 4 && distinct <= 20 && distinct <= cells.size / 2) return FieldType.ENUM
        return FieldType.TEXT
    }

    private fun codeLabels(grid: Grid, samples: List<Cell>): Map<String, String> {
        if (samples.isEmpty() || !samples.all { it.value.matches(Regex("[0-9]+(?:\\.0)?")) }) return emptyMap()
        val groups = mutableMapOf<Int, MutableMap<String, String>>()
        for ((_, row) in grid.rows.take(100).withIndex()) for ((c, cell) in row.withIndex()) {
            Regex("^([0-9]+)[.)]\\s+(.+)$").matchEntire(cell.value.trim())?.let { match -> groups.getOrPut(c) { linkedMapOf() }[match.groupValues[1]] = match.groupValues[2] }
        }
        return groups.values.filter { it.size >= 2 && samples.all { cell -> it.containsKey(Values.number(cell.value)?.stripTrailingZeros()?.toPlainString()) } }.singleOrNull()?.toMap() ?: emptyMap()
    }
}
