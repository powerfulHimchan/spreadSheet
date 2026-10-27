package com.powerfulhimchan.familyledger.core

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class CoreTest {
    private fun str(s: String) = Cell(s,ValueKind.STRING)
    private fun num(s: String) = Cell(s,ValueKind.NUMBER)
    private fun grid(): Grid = Grid("test-spreadsheet",1,"2026-02",listOf(
        listOf(str("가족 장부")), emptyList(),
        listOf(str("ID"),str("날짜"),str("금액"),str("내용"),str("카테고리"),str("삭제됨")),
        listOf(str("a"),str("2026-02-28"),num("1200"),str("점심"),str("식비"),Cell("false",ValueKind.BOOLEAN)),
        listOf(str("b"),str("2026-02-29"),num("3000"),str("오류"),str("식비"),Cell("false",ValueKind.BOOLEAN)),
        emptyList(),listOf(str("c"),str("2026-03-01"),num("500"),str("빵"),str("식비"),Cell("true",ValueKind.BOOLEAN))
    ),20,12)
    private fun mapping(g: Grid = grid()) = Detection.mapping(g,2,0,6,20,2026)

    @Test fun detectsOffsetHeaderAndSeparateTables() {
        val g=Grid("s",1,"4월",listOf(emptyList(),listOf(Cell(),str("일자"),str("사용처"),str("금액"),Cell(),str("안내")),listOf(Cell(),str("1일"),str("점심"),num("1000"))),100,8)
        val m=Detection.candidates(g,2026).first()
        assertEquals(1,m.headerRow);assertEquals(1,m.firstColumn);assertEquals(4,m.endColumn)
        assertEquals(LocalDate.of(2026,4,1),Ledger.parse(g,m).records.single().date)
    }
    @Test fun invalidDateDoesNotRollIntoNextMonth() {
        val parsed=Ledger.parse(grid(),mapping());assertEquals(2,parsed.records.size);assertEquals(1,parsed.issues.size)
        assertEquals(BigDecimal("1200"),Ledger.totals(parsed.records,YearMonth.of(2026,2)).expense)
    }
    @Test fun leapYearAndSerialDate() {
        assertNull(Values.isoDate("2026-02-29"));assertNotNull(Values.isoDate("2024-02-29"))
        val m=mapping();val col=m.column(Role.DATE)!!
        assertEquals(LocalDate.of(2024,2,29),Values.date(Cell(Values.serial(LocalDate.of(2024,2,29)).toString(),ValueKind.NUMBER,"DATE"),col,m))
    }
    @Test fun currencyUsesExactDecimalAndLocale() {
        assertEquals(BigDecimal("1234.50"),Values.number("₩1,234.50"));assertEquals(BigDecimal("1234.50"),Values.number("1.234,50",NumberStyle.COMMA_DECIMAL))
        assertNull(Values.number("12,34"));assertEquals(BigDecimal("-1000"),Values.number("(1,000원)"))
    }
    @Test fun formulaAndIdentifierAreProtected() {
        assertEquals(FieldType.TEXT,Detection.infer(listOf(str("00123"),str("00234"))))
        val g=grid();val rows=g.rows.toMutableList();rows[3]=rows[3].toMutableList().apply { this[2]=Cell("1200",ValueKind.NUMBER,formula="=600*2") }
        assertTrue(mapping(g.copy(rows=rows)).column(Role.AMOUNT)!!.readOnly)
    }
    @Test fun repeatedStringsAreSuggestionsNotStrictValidation() {
        val g=grid();val m=mapping();val c=m.columns.first { it.header=="内容" || it.header=="내용" }
        assertEquals(ValueKind.STRING,Values.typed("=IMPORTXML(hello)",c,m).kind)
        assertNull(Values.typed("=SUM(A1)",c,m).formula)
    }
    @Test fun editsFollowStableIdAfterReordering() {
        val g=grid();val m=mapping();val original=Ledger.parse(g,m).records.first()
        val rows=g.rows.toMutableList();val a=rows[3];rows[3]=rows[4];rows[4]=a
        val plan=Writes.edit(g.copy(rows=rows),m,original,mapOf(m.column(Role.DESCRIPTION)!!.key to "저녁"))
        assertEquals(4,plan.cells.single().row);assertEquals(3,plan.cells.single().column)
    }
    @Test fun concurrentChangeBlocksWrite() {
        val g=grid();val m=mapping();val record=Ledger.parse(g,m).records.first();val rows=g.rows.toMutableList()
        rows[3]=rows[3].toMutableList().apply { this[2]=num("9999") }
        assertThrows(Conflict::class.java) { Writes.edit(g.copy(rows=rows),m,record,emptyMap()) }
    }
    @Test fun duplicateIdBlocksWrite() {
        val g=grid();val m=mapping();val rows=g.rows.toMutableList();rows[4]=rows[4].toMutableList().apply { this[0]=str("a") }
        assertThrows(Conflict::class.java) { Writes.edit(g.copy(rows=rows),m,Ledger.parse(g,m).records.first(),emptyMap()) }
    }
    @Test fun deletionIsOnlyFlagNoRowRemoval() {
        val g=grid();val m=mapping();val plan=Writes.edit(g,m,Ledger.parse(g,m).records.first(),emptyMap(),true)
        assertEquals(listOf(5),plan.cells.map { it.column });assertEquals("true",plan.cells.single().cell.value)
    }
    @Test fun addsOnlyInConfirmedEmptyRange() {
        val g=grid();val m=mapping();val plan=Writes.add(g,m,mapOf(m.column(Role.DATE)!!.key to "2026-02-28",m.column(Role.AMOUNT)!!.key to "0"),"new")
        assertEquals(setOf(5),plan.cells.map { it.row }.toSet());assertEquals("new",plan.recordId)
    }
    @Test fun missingMappingAndEmptyDataAreHonest() {
        assertEquals(0,Ledger.totals(emptyList()).count);assertEquals(BigDecimal.ZERO,Ledger.totals(emptyList()).expense)
        assertTrue(mapping().copy(columns=mapping().columns.map { it.copy(role=Role.NONE) }).validate().isEmpty())
    }
    @Test fun dayOfMonthRejectsApril31() {
        val m=mapping().copy(month=4);val col=m.column(Role.DATE)!!.copy(dateMode=DateMode.DAY_OF_MONTH)
        assertNull(Values.date(str("31일"),col,m));assertEquals(LocalDate.of(2026,4,30),Values.date(str("30일"),col,m))
    }
    @Test fun uniqueColumnKeysAndOverridesSurviveReorder() {
        val g=grid();val old=mapping().let { m -> m.copy(columns=m.columns.map { if(it.role==Role.DESCRIPTION) it.copy(type=FieldType.EMAIL) else it }) }
        val rows=g.rows.map { row -> if(row.size<6) row else row.toMutableList().apply { val a=this[3];this[3]=this[4];this[4]=a } }
        val new=Detection.mapping(g.copy(rows=rows),2,0,6,20,2026,old)
        assertEquals(FieldType.EMAIL,new.columns.first { it.header=="내용" }.type);assertEquals(4,new.column(Role.DESCRIPTION)!!.index)
    }
    @Test fun a1EscapesTitlesAndParsesOnlyGoogleSheetsUrl() {
        assertEquals("AA",A1.col(26));assertEquals(701,A1.index("ZZ"));assertEquals("'엄마''s'",A1.quote("엄마's"))
        assertNull(A1.spreadsheetId("https://evil.test/spreadsheets/d/abc"));assertEquals("abc",A1.spreadsheetId("https://docs.google.com/spreadsheets/d/abc/edit"))
    }
    @Test fun percentAndIntegerInputValidation() {
        val m=mapping();val c=m.column(Role.AMOUNT)!!
        assertEquals("0.125",Values.typed("12.5",c.copy(type=FieldType.PERCENT),m).value)
        assertThrows(IllegalArgumentException::class.java) { Values.typed("1.5",c.copy(type=FieldType.INTEGER),m) }
    }
    @Test fun rejectsUnrepresentableNumbersAndUnknownDirections() {
        val m=mapping();assertThrows(IllegalArgumentException::class.java) { Values.typed("9007199254740993",m.column(Role.AMOUNT)!!,m) }
        val withDirection=m.copy(columns=m.columns.map { if(it.role==Role.CATEGORY) it.copy(role=Role.DIRECTION) else it })
        assertEquals(0,Ledger.totals(Ledger.parse(grid(),withDirection).records).count)
    }
    @Test fun incompleteGridCannotBeWritten() {
        assertThrows(IllegalStateException::class.java) { Writes.add(grid().copy(complete=false),mapping(),emptyMap()) }
    }
    @Test fun canonicalNumbersIgnoreDisplayLocaleDuringAggregation() {
        val m=mapping().copy(numberStyle=NumberStyle.COMMA_DECIMAL)
        assertEquals(BigDecimal("1200"),Ledger.totals(Ledger.parse(grid(),m).records).expense)
        assertEquals("12,5",Values.input(Cell("12.5",ValueKind.NUMBER),m.column(Role.AMOUNT)!!.copy(type=FieldType.DECIMAL),m))
    }
    @Test fun numericDropdownPreservesNumberType() {
        val m=mapping();val col=Column("code","카드",8,FieldType.ENUM,options=listOf("1","2"),strictOptions=true,enumNumeric=true)
        assertEquals(ValueKind.NUMBER,Values.typed("2",col,m).kind)
        assertThrows(IllegalArgumentException::class.java) { Values.typed("3",col,m) }
    }
    @Test fun addingPreservesPrefilledFormulaColumn() {
        val g=grid();val rows=g.rows.map { it.toMutableList() }.toMutableList()
        rows[2].add(str("합계"));while(rows[5].size<=6) rows[5].add(Cell());rows[5][6]=Cell("0",ValueKind.NUMBER,formula="=C6*2")
        val gg=g.copy(rows=rows)
        val m=Detection.mapping(gg,2,0,7,20,2026)
        val p=Writes.add(gg,m,mapOf(m.column(Role.DATE)!!.key to "2026-02-28",m.column(Role.AMOUNT)!!.key to "100"))
        assertEquals(5,p.cells.first().row);assertFalse(p.cells.any { it.column==6 })
    }
    @Test fun changedHeadersBlockStaleCoordinates() {
        val g=grid();val rows=g.rows.toMutableList();rows[2]=rows[2].toMutableList().apply { this[2]=str("新しい金額") }
        assertThrows(Conflict::class.java) { Writes.verifyStructure(g.copy(rows=rows),mapping()) }
    }
    @Test fun refundsAndIncomeHaveIndependentExactTotals() {
        val g=grid();val m=mapping();val records=Ledger.parse(g,m).records.filter { it.date!=null }
        val data=records+records.first().copy(amount=BigDecimal("-200"))+records.first().copy(amount=BigDecimal("5000"),income=true)
        val totals=Ledger.totals(data);assertEquals(BigDecimal("1000"),totals.expense);assertEquals(BigDecimal("5000"),totals.income);assertEquals(BigDecimal("4000"),totals.balance)
    }
    @Test fun explicitTextMetadataOutranksNumericSamples() {
        assertEquals(FieldType.TEXT,Detection.infer(listOf(Cell("12345",ValueKind.STRING,"TEXT"),Cell("67890",ValueKind.STRING,"TEXT"))))
    }
}
