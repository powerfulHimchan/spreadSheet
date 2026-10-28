package com.powerfulhimchan.familyledger

import com.powerfulhimchan.familyledger.core.*
import java.time.LocalDate
import java.time.YearMonth

object Demo {
    private fun t(v: String)=Cell(v,ValueKind.STRING)
    private fun n(v: String)=Cell(v,ValueKind.NUMBER)
    fun planGrid(): Grid {
        val rows=MutableList(12) { MutableList(14) { Cell() } }
        fun put(r: Int,c: Int,value: String,numeric: Boolean=false) { rows[r][c]=if(numeric) n(value) else t(value) }
        put(0,7,"금액");put(0,8,"이체은행");put(0,9,"이체일");put(0,10,"만기일")
        put(1,1,"월 수입");put(1,2,"금액");put(1,3,"월 지출");put(1,4,"금액")
        put(2,1,"급여 A");put(2,2,"3500000",true);put(3,1,"급여 B");put(3,2,"2500000",true)
        listOf(Triple("주거비","1200000","공동"),Triple("통신비","65000","가족 A"),Triple("생활비","800000","공동"),Triple("구독 서비스","15000","가족 B"),Triple("운동","0","가족 A")).forEachIndexed { i,(label,amount,party) -> put(i+2,3,label);put(i+2,4,amount,true);put(i+2,5,party) }
        put(6,6,"잠시 보류");put(2,9,"25일")
        put(8,3,"기념일 적립");put(8,4,"200000",true);put(8,7,"100000",true);put(8,8,"가족은행");put(8,9,"5일");put(8,10,"2028.12.31");put(8,11,"생일")
        put(9,7,"100000",true);put(9,8,"가족은행");put(9,9,"17일");put(9,11,"가족 기념일")
        put(11,3,"합계");rows[11][4]=Cell("2280000",ValueKind.NUMBER,formula="=SUM(E3:E11)")
        put(1,11,"가족카드 결제일");put(1,12,"25일");put(1,13,"가족은행")
        return Grid("demo",4,"월 계획 샘플",rows,12,14)
    }
    fun grids(): List<Grid> {
        val month=YearMonth.from(LocalDate.now())
        val date=month.atDay(1)
        val monthly=mutableListOf<List<Cell>>(listOf(t("우리 집 월간 장부")),emptyList(),listOf(Cell(),t("일자"),t("카테고리"),t("사용처"),t("금액"),t("결제방법"),t("합계")))
        val labels=listOf("식비","교통","생활","카페","식비","문화","식비","생활","카페","교통","식비","기타")
        val amounts=listOf(18500,3200,12900,4800,23700,15000,9800,27000,6500,1450,32000,7300)
        labels.forEachIndexed { i,c -> monthly+=listOf(Cell(),t("${i*2+1}일"),t(c),t(listOf("가족 식사","출근","생활용품","커피")[i%4]),n(amounts[i].toString()),n("${i%3+1}"),if(i==0) Cell(amounts.sum().toString(),ValueKind.NUMBER,formula="=SUM(E4:E100)") else Cell()) }
        monthly[3]=monthly[3]+listOf(Cell(),t("1. 가족카드"))
        monthly[4]=monthly[4]+listOf(Cell(),t("2. 현금"))
        monthly[5]=monthly[5]+listOf(Cell(),t("3. 체크카드"))
        val standard=mutableListOf<List<Cell>>(listOf(t("날짜"),t("구분"),t("내용"),t("금액"),t("카테고리"),t("작성자")))
        repeat(12) { i -> standard+=listOf(Cell(Values.serial(date.plusDays(i.toLong())).toString(),ValueKind.NUMBER,"DATE"),t(if(i==0) "수입" else "지출"),t(if(i==0) "월 수입" else "거래 ${i+1}"),n(if(i==0) "3200000" else amounts[i].toString()),t(if(i==0) "급여" else labels[i]),t(if(i%2==0) "가족 A" else "가족 B")) }
        val reordered=mutableListOf<List<Cell>>(listOf(t("Shared ledger")),listOf(t("Category"),t("Amount"),t("Merchant"),t("Date"),t("Payment")))
        repeat(8) { i -> reordered+=listOf(t(listOf("Food","Travel","Home")[i%3]),n((i*2500+1000).toString()),t("Shop ${i+1}"),t(date.plusDays(i.toLong()).toString()),t("Card")) }
        return listOf(Grid("demo",1,"${month.monthValue}월",monthly,100,12),Grid("demo",2,"수입·지출 목록",standard,100,10),Grid("demo",3,"English ledger",reordered,100,8))
    }
    fun withIdentity(g: Grid,m: TableMapping): Pair<Grid,TableMapping> {
        if(m.column(Role.ID)!=null) return g to m
        val i=g.columnCount
        val id=Column("__ledger_id#1","__ledger_id",i,FieldType.TEXT,Role.ID,true)
        val del=Column("__ledger_deleted#1","__ledger_deleted",i+1,FieldType.BOOLEAN,Role.DELETED,true)
        val parsed=Ledger.parse(g,m)
        val rows=g.rows.map { it.toMutableList() }.toMutableList()
        fun put(r: Int,c: Int,cell: Cell) { while(rows.size<=r) rows.add(mutableListOf());while(rows[r].size<=c) rows[r].add(Cell());rows[r][c]=cell }
        put(m.headerRow,i,t(id.header));put(m.headerRow,i+1,t(del.header))
        parsed.records.forEach { put(it.sourceRow,i,t("demo-${it.sourceRow}"));put(it.sourceRow,i+1,Cell("false",ValueKind.BOOLEAN)) }
        return g.copy(rows=rows,columnCount=i+2) to m.copy(columns=m.columns+id+del)
    }
    fun apply(g: Grid,plan: WritePlan): Grid {
        val rows=g.rows.map { it.toMutableList() }.toMutableList()
        plan.cells.forEach { w -> while(rows.size<=w.row) rows.add(mutableListOf());while(rows[w.row].size<=w.column) rows[w.row].add(Cell());rows[w.row][w.column]=w.cell }
        return g.copy(rows=rows)
    }
}
