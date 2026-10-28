package com.powerfulhimchan.familyledger.core

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class PlansTest {
    private fun sample(): Grid {
        val rows=MutableList(16) { MutableList(17) { Cell() } }
        fun text(r: Int,c: Int,value: String) { rows[r][c]=Cell(value,ValueKind.STRING) }
        fun num(r: Int,c: Int,value: String,formula: String?=null) { rows[r][c]=Cell(value,ValueKind.NUMBER,formula=formula) }
        text(0,8,"금액");text(0,9,"이체은행 To");text(0,10,"이체 날짜");text(0,11,"만기일")
        text(1,2,"월 수입");text(1,3,"금액");text(1,4,"월 지출");text(1,5,"금액")
        text(2,2,"급여 A");num(2,3,"4000");text(3,2,"급여 B");num(3,3,"0");text(4,2,"기타 수입");num(4,3,"1000")
        text(5,2,"합계");num(5,3,"5000","=SUM(D3:D5)")
        text(2,4,"보험");num(2,5,"200");text(2,6,"가족 A");text(2,10,"5일")
        text(3,4,"통신");num(3,5,"65","=50+15");text(3,6,"가족 B");num(3,7,"265","=SUM(F3:F4)")
        text(4,4,"운동");num(4,5,"0");text(4,6,"가족 A");text(4,7,"잠시 보류")
        text(6,4,"생활비");num(6,5,"800")
        text(8,4,"기념일 적립");num(8,5,"300");num(8,8,"100");text(8,9,"가족은행");text(8,10,"5일");text(8,11,"2028.12.31");text(8,12,"생일 A")
        num(9,8,"100");text(9,9,"가족은행");text(9,10,"17일");text(9,12,"생일 B")
        num(10,8,"100");text(10,9,"가족은행");text(10,10,"25일");text(10,12,"기념일")
        text(14,4,"합계");num(14,5,"1365","=SUM(F3:F14)");num(14,8,"3635","=D6-F15")
        text(1,13,"가족카드 이체일");text(1,14,"25일");text(1,15,"가족은행")
        text(2,13,"공동카드 결제일");text(2,14,"27일");text(2,15,"다른은행")
        return Grid("synthetic",4,"테스트용 월 계획",rows,16,17)
    }
    @Test fun findsIndependentIncomeExpenseTransferAndPaymentRegions() {
        val plan=PlanDetection.suggest(sample())
        assertEquals(PlanKind.entries.toSet(),plan.regions.map { it.kind }.toSet())
        assertTrue(plan.validate().toString(),plan.validate().isEmpty())
    }
    @Test fun monthlyTotalsNeedNoDatesAndExcludeTransfersAndSummaries() {
        val g=sample();val parsed=Plans.parse(g,PlanDetection.suggest(g))
        assertEquals(BigDecimal("5000"),parsed.income)
        assertEquals(BigDecimal("1365"),parsed.expense)
        assertEquals(BigDecimal("3635"),parsed.difference)
        assertEquals(5,parsed.items.count { it.kind==PlanKind.EXPENSE })
        assertEquals(3,parsed.items.count { it.kind==PlanKind.TRANSFER })
        assertTrue(parsed.issues.toString(),parsed.issues.isEmpty())
        assertEquals(3,parsed.excluded.size)
    }
    @Test fun retainsFormulaItemsZeroAndItemsAfterBlankRows() {
        val g=sample();val parsed=Plans.parse(g,PlanDetection.suggest(g))
        assertEquals(BigDecimal("65"),parsed.items.single { it.title=="통신" }.amount)
        assertEquals("",parsed.items.single { it.title=="통신" }.note)
        assertTrue(parsed.items.single { it.title=="운동" }.paused)
        assertTrue(parsed.items.any { it.title=="생활비" })
        assertTrue(parsed.items.any { it.title=="급여 B" && it.amount==BigDecimal.ZERO })
    }
    @Test fun transferDetailsInheritOnlyTheirExplicitParentTitle() {
        val g=sample();val transfers=Plans.parse(g,PlanDetection.suggest(g)).items.filter { it.kind==PlanKind.TRANSFER }
        assertTrue(transfers.all { it.title=="기념일 적립" })
        assertEquals(listOf(null,8,8),transfers.map { it.parentRow })
        assertEquals(LocalDate.of(2028,12,31),transfers.first().maturity)
    }
    @Test fun schedulesDoNotRepeatTheParentExpenseAndNeverPretendPaid() {
        val g=sample();val parsed=Plans.parse(g,PlanDetection.suggest(g));val schedule=Plans.scheduleItems(parsed)
        assertEquals(6,schedule.size)
        assertFalse(schedule.any { it.kind==PlanKind.EXPENSE && it.sourceRow==8 })
        assertTrue(schedule.any { it.title=="보험" && it.day==5 })
        assertEquals(LocalDate.of(2026,2,28),schedule.first().copy(day=31).scheduledDate(YearMonth.of(2026,2)))
    }
    @Test fun unknownNumericValuesAreNotConvertedToZero() {
        val g=sample();val rows=g.rows.map { it.toMutableList() };rows[2][5]=Cell("금액 확인",ValueKind.STRING)
        val next=g.copy(rows=rows);val parsed=Plans.parse(next,PlanDetection.suggest(next))
        assertNull(parsed.items.single { it.title=="보험" }.amount)
        assertTrue(parsed.issues.any { it.row==3 && it.column.contains("금액") })
    }
    @Test fun aPausedParentDoesNotCreateUpcomingTransfers() {
        val g=sample();val rows=g.rows.map { it.toMutableList() };rows[8][7]=Cell("일시 중지")
        val next=g.copy(rows=rows);val parsed=Plans.parse(next,PlanDetection.suggest(next))
        assertTrue(parsed.items.any { it.kind==PlanKind.TRANSFER })
        assertTrue(Plans.scheduleItems(parsed).none { it.kind==PlanKind.TRANSFER })
        assertEquals(BigDecimal("1365"),parsed.expense)
    }
    @Test fun manualMappingsSupportDifferentNamesAndReorderedColumns() {
        val g=Grid("x",1,"Custom",listOf(listOf(Cell("Amount"),Cell("Label")),listOf(Cell("1.234,50"),Cell("Rent"))))
        val plan=PlanMapping(listOf(PlanRegion("custom","Bills",PlanKind.EXPENSE,1,2,mapOf(PlanField.AMOUNT to 0,PlanField.TITLE to 1))),NumberStyle.COMMA_DECIMAL)
        assertTrue(plan.validate().isEmpty());assertEquals(BigDecimal("1234.50"),Plans.parse(g,plan).expense)
    }
    @Test fun duplicateMonetaryRegionsAreRejectedAndNeverDoubleCounted() {
        val g=sample();val base=PlanDetection.suggest(g);val expense=base.regions.single { it.kind==PlanKind.EXPENSE }
        val duplicate=base.copy(regions=base.regions+expense.copy(id="copy"))
        assertTrue(duplicate.validate().any { it.contains("중복") })
        assertEquals(BigDecimal("1365"),Plans.parse(g,duplicate).expense)
    }
    @Test fun excludedRowsApplyToTheirOwnRegionOnly() {
        val g=sample();val plan=PlanDetection.suggest(g).let { p -> p.copy(regions=p.regions.map { if(it.kind==PlanKind.EXPENSE) it.copy(excludedRows=setOf(2)) else it }) }
        val parsed=Plans.parse(g,plan)
        assertEquals(BigDecimal("1165"),parsed.expense);assertEquals(BigDecimal("5000"),parsed.income)
    }
    @Test fun savedPlansDetectMovedHeadersAndCannotUseTransactionWritePaths() {
        val g=sample();val plan=PlanDetection.suggest(g);val m=Plans.connection(g,plan)
        assertTrue(m.validate().isEmpty());Writes.verifyStructure(g,m)
        val rows=g.rows.map { it.toMutableList() };rows[1][4]=Cell("변경된 표")
        assertThrows(Conflict::class.java) { Writes.verifyStructure(g.copy(rows=rows),m) }
        assertThrows(IllegalStateException::class.java) { Writes.add(g,m,emptyMap()) }
        assertTrue(Ledger.parse(g,m).records.isEmpty())
    }
    @Test fun englishSemanticHeadersDoNotDependOnSheetNameOrCoordinates() {
        val g=Grid("g",5,"Unrelated",listOf(emptyList(),listOf(Cell(),Cell("Monthly Income"),Cell("Amount"),Cell(),Cell("Expenses"),Cell("Amount")),
            listOf(Cell(),Cell("Salary"),Cell("2000",ValueKind.NUMBER),Cell(),Cell("Rent"),Cell("500",ValueKind.NUMBER))))
        val parsed=Plans.parse(g,PlanDetection.suggest(g))
        assertEquals(BigDecimal("2000"),parsed.income);assertEquals(BigDecimal("500"),parsed.expense)
    }
    @Test fun invalidDaysAndMaturityRemainVisibleForReview() {
        assertNull(Plans.monthlyDay(Cell("32일")));assertNull(Plans.monthlyDay(Cell("2026-10-25")))
        assertEquals(25,Plans.monthlyDay(Cell("매월 25일")))
        assertNull(Plans.maturity(Cell("2026.02.29")))
        assertEquals(LocalDate.of(2024,2,29),Plans.maturity(Cell(Values.serial(LocalDate.of(2024,2,29)).toString(),ValueKind.NUMBER,"DATE")))
    }
}
