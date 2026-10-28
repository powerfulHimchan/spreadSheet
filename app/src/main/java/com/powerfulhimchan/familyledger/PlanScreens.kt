@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.powerfulhimchan.familyledger

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.powerfulhimchan.familyledger.core.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

@Composable internal fun ImportDialog(g: Grid,existing: TableMapping?,busy: Boolean,onDismiss: ()->Unit,onSave: (TableMapping)->Unit) {
    val suggested=remember(g) { PlanDetection.suggest(g) }
    var planMode by remember(g,existing) { mutableStateOf(existing?.plan!=null || suggested.regions.any { it.kind in setOf(PlanKind.INCOME,PlanKind.EXPENSE) }) }
    if(planMode) PlanImportDialog(g,existing,suggested,busy,onDismiss,onSave,{planMode=false})
    else MappingDialog(g,existing?.takeIf { it.plan==null },busy,onDismiss,onSave,{planMode=true})
}

@Composable private fun PlanImportDialog(g: Grid,existing: TableMapping?,suggested: PlanMapping,busy: Boolean,onDismiss: ()->Unit,onSave: (TableMapping)->Unit,onTransactions: ()->Unit) {
    var regions by remember(g,existing) { mutableStateOf(existing?.plan?.regions ?: suggested.regions) }
    var selected by remember(g,existing) { mutableStateOf(regions.map { it.id }.toSet()) }
    var style by remember(g,existing) { mutableStateOf(existing?.plan?.numberStyle ?: NumberStyle.DOT_DECIMAL) }
    var error by remember { mutableStateOf<String?>(null) }
    var allPreview by remember { mutableStateOf(false) }
    val plan=PlanMapping(regions.filter { it.id in selected },style)
    val parsed=remember(g,plan) { Plans.parse(g,plan) }
    val validation=plan.validate()
    fun update(region: PlanRegion) { regions=regions.map { if(it.id==region.id) region else it };error=null }
    Dialog(onDismissRequest={if(!busy) onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.96f).padding(horizontal=10.dp),color=Background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Text("월 계획 불러오기",fontSize=22.sp,fontWeight=FontWeight.Bold)
                    TextButton(onClick=onDismiss,enabled=!busy) { Text("닫기") }
                }
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Panel {
                        Heading(g.title,"수입·지출·이체 영역을 각각 선택해 주세요. 원본 값과 수식은 그대로 읽습니다.")
                        Text("매월 반복되는 금액을 관리하는 방식입니다. 실제 거래일을 지정할 필요가 없습니다.",color=Muted,fontSize=13.sp)
                        if(!g.complete) Text("시트 일부만 읽었습니다. 표시되는 합계도 불러온 범위 기준입니다.",color=MaterialTheme.colorScheme.error,fontSize=13.sp)
                        TextButton(onClick=onTransactions,enabled=!busy) { Text("날짜별 거래 목록으로 불러오기") }
                        if(regions.isEmpty()) Text("자동으로 찾은 영역이 없습니다. 아래에서 영역을 추가해 항목명과 금액 열을 선택해 주세요.",color=Blue)
                    } }
                    items(regions,key={it.id}) { region ->
                        var expanded by remember(region.id) { mutableStateOf(false) }
                        Panel {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(region.id in selected,{checked -> selected=if(checked) selected+region.id else selected-region.id},enabled=!busy)
                                Column(Modifier.weight(1f)) {
                                    Text(region.name,fontWeight=FontWeight.Bold)
                                    Text("${region.kind.label} · ${region.firstRow+1}~${region.endRow}행 · ${parsed.items.count { it.regionId==region.id }}개",color=Muted,fontSize=12.sp)
                                }
                                TextButton(onClick={expanded=!expanded},enabled=!busy) { Text(if(expanded) "접기" else "설정") }
                            }
                            Text(region.fields.entries.joinToString(" · ") { "${it.key.label} ${A1.col(it.value)}열" },fontSize=12.sp,color=Muted)
                            if(region.kind==PlanKind.TRANSFER) Text("이체금액은 월 지출 합계에 더하지 않습니다.",fontSize=12.sp,color=Blue)
                            if(expanded) {
                                OutlinedTextField(region.name,{update(region.copy(name=it))},Modifier.fillMaxWidth(),label={Text("영역 이름")},singleLine=true,enabled=!busy)
                                Choice("용도",region.kind,PlanKind.entries,{it.label},{update(region.copy(kind=it))})
                                var first by remember(region.id) { mutableStateOf((region.firstRow+1).toString()) }
                                var last by remember(region.id) { mutableStateOf(region.endRow.toString()) }
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(first,{value -> first=value.filter(Char::isDigit);update(region.copy(firstRow=(first.toIntOrNull() ?: 0)-1,anchors=emptyMap()))},Modifier.weight(1f),label={Text("데이터 시작 행")},singleLine=true,enabled=!busy)
                                    OutlinedTextField(last,{value -> last=value.filter(Char::isDigit);update(region.copy(endRow=last.toIntOrNull() ?: 0,anchors=emptyMap()))},Modifier.weight(1f),label={Text("마지막 행")},singleLine=true,enabled=!busy)
                                }
                                PlanField.entries.forEach { field ->
                                    Choice(field.label,region.fields[field] ?: -1,listOf(-1)+(0 until minOf(g.columnCount,200)).toList(),{ col ->
                                        if(col<0) "사용하지 않음" else {
                                            val examples=(region.firstRow.coerceAtLeast(0) until minOf(g.rows.size,region.firstRow.coerceAtLeast(0)+5)).map { g.cell(it,col).value }.filter(String::isNotBlank).take(2)
                                            "${A1.col(col)}열"+if(examples.isEmpty()) "" else " · ${examples.joinToString(" / ").take(24)}"
                                        }
                                    },{ col -> update(region.copy(fields=if(col<0) region.fields-field else region.fields+(field to col),anchors=emptyMap())) })
                                }
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Checkbox(region.carryTitle,{update(region.copy(carryTitle=it))},enabled=!busy)
                                    Text("항목명이 비면 바로 위 항목의 세부내역으로 읽기",fontSize=13.sp,modifier=Modifier.weight(1f))
                                }
                                var excluded by remember(region.id) { mutableStateOf(region.excludedRows.sorted().joinToString(", ") { (it+1).toString() }) }
                                OutlinedTextField(excluded,{ value -> excluded=value.filter { it.isDigit() || it==',' || it==' ' };update(region.copy(excludedRows=excluded.split(',').mapNotNull { it.trim().toIntOrNull()?.minus(1) }.toSet())) },Modifier.fillMaxWidth(),label={Text("추가로 제외할 행 (예: 6, 15)")},enabled=!busy)
                                TextButton(onClick={regions=regions.filter { it.id!=region.id };selected=selected-region.id},enabled=!busy) { Text("영역 삭제") }
                            }
                        }
                    }
                    item { OutlinedButton(onClick={
                        val id=java.util.UUID.randomUUID().toString()
                        regions=regions+PlanRegion(id,"새 영역",PlanKind.EXPENSE,1,minOf(g.rowCount,10000).coerceAtLeast(2),emptyMap())
                        selected=selected+id
                    },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("영역 직접 추가") } }
                    item { Panel {
                        Choice("숫자 표기",style,NumberStyle.entries,{if(it==NumberStyle.DOT_DECIMAL) "1,234.56" else "1.234,56"},{style=it})
                        Text("불러오기 미리보기",fontSize=19.sp,fontWeight=FontWeight.Bold)
                        PlanSummary(parsed)
                        Text("${parsed.items.size}개 항목 · 합계·소계 등 ${parsed.excluded.size}개 제외",color=Muted,fontSize=13.sp)
                        parsed.excluded.take(8).forEach { excluded -> Text("${regions.firstOrNull { it.id==excluded.regionId }?.name} ${excluded.row+1}행 · ${excluded.reason}",fontSize=12.sp,color=Muted) }
                        parsed.items.take(if(allPreview) 160 else 4).forEach { item ->
                            Text("${item.kind.label} · ${item.title} · ${money(item.amount)}",fontSize=13.sp)
                        }
                        if(allPreview && parsed.items.size>160) Text("미리보기는 앞 160개를 표시합니다. 전체 항목은 연결 후 확인할 수 있습니다.",fontSize=12.sp,color=Muted)
                        TextButton(onClick={allPreview=!allPreview}) { Text(if(allPreview) "미리보기 접기" else "항목 더 보기") }
                        parsed.issues.take(12).forEach { Text("${it.row}행 · ${it.column}: ${it.message}",fontSize=12.sp,color=MaterialTheme.colorScheme.error) }
                        if(parsed.issues.size>12) Text("그 외 ${parsed.issues.size-12}건은 연결 후 설정에서 확인할 수 있습니다.",fontSize=12.sp,color=Muted)
                    } }
                    item { validation.forEach { Text(it,color=MaterialTheme.colorScheme.error) };error?.let { Text(it,color=MaterialTheme.colorScheme.error) } }
                }
                Button(onClick={runCatching {
                    check(validation.isEmpty()) { validation.joinToString("\n") }
                    check(parsed.items.isNotEmpty()) { "미리보기에 항목이 없습니다. 영역과 열을 확인해 주세요." }
                    Plans.verifyStructure(g,plan)
                    onSave(Plans.connection(g,plan,existing?.bookTitle.orEmpty()))
                }.onFailure { error=it.message }},enabled=!busy,modifier=Modifier.fillMaxWidth().padding(16.dp),shape=RoundedCornerShape(999.dp)) { Text("이 설정으로 불러오기") }
            }
        }
    }
}

@Composable private fun PlanSummary(plan: ParsedPlan) {
    Text("월 지출 계획",color=Muted,fontSize=13.sp)
    Text(money(plan.expense.takeIf { plan.hasExpense }),fontSize=32.sp,fontWeight=FontWeight.Bold)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Column { Text("월 수입 계획",color=Muted,fontSize=12.sp);Text(money(plan.income.takeIf { plan.hasIncome }),fontWeight=FontWeight.Bold) }
        Column { Text("계획 차액",color=Muted,fontSize=12.sp);Text(money(plan.difference.takeIf { plan.hasIncome && plan.hasExpense }),fontWeight=FontWeight.Bold) }
    }
    Text("월 기준 금액입니다. 이체금액은 별도로 표시하며 납부 여부를 뜻하지 않습니다.",color=Muted,fontSize=12.sp)
    if(plan.issues.any { it.message.contains("합계에서 제외") }) Text("금액을 읽지 못한 항목이 있어 합계 확인이 필요합니다.",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
}

@Composable internal fun PlanContent(state: AppState,vm: LedgerViewModel,tab: Int,onConfigure: ()->Unit,onClear: ()->Unit) {
    val mapping=state.mapping ?: return
    val parsed=state.plan
    var search by remember(mapping.key) { mutableStateOf("") }
    var kind by remember(mapping.key) { mutableStateOf<PlanKind?>(null) }
    var month by remember(mapping.key) { mutableStateOf(YearMonth.now()) }
    var detail by remember(state.revision) { mutableStateOf<PlanItem?>(null) }
    val records=parsed.items.filter { item -> (kind==null || item.kind==kind) && (search.isBlank() || listOf(item.title,item.party,item.note,item.bank).any { it.contains(search,true) }) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,8.dp,16.dp,32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        when(tab) {
            0 -> {
                item { Panel { Heading("월 수입·고정비");PlanSummary(parsed) } }
                if(state.grid?.complete==false) item { Panel { Text("시트 일부만 읽었습니다. 합계는 불러온 범위 기준입니다.",color=MaterialTheme.colorScheme.error) } }
                if(parsed.issues.isNotEmpty()) item { Panel { Text("확인할 데이터 ${parsed.issues.size}건",fontWeight=FontWeight.Bold);TextButton(onClick=onConfigure) { Text("영역 설정과 미리보기 확인") } } }
                item { Panel {
                    Heading("담당·결제정보별 월 지출")
                    val groups=parsed.items.filter { it.kind==PlanKind.EXPENSE && it.amount!=null }.groupBy { it.party.ifBlank { "미지정" } }
                        .mapValues { (_,items) -> items.fold(BigDecimal.ZERO) { sum,item -> sum+item.amount!! } }.toList().sortedByDescending { it.second }
                    if(groups.isEmpty()) Text("집계할 월 지출 항목이 없습니다.",color=Muted)
                    groups.forEach { (name,amount) -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(name,Modifier.weight(1f));Text(money(amount),fontWeight=FontWeight.Bold) } }
                    Text("원본에 적힌 담당·결제정보를 기준으로 묶습니다.",color=Muted,fontSize=12.sp)
                } }
                item { Panel {
                    Heading("다가오는 이체·결제")
                    val today=LocalDate.now()
                    val upcoming=Plans.scheduleItems(parsed).mapNotNull { item ->
                        val current=item.scheduledDate(YearMonth.from(today)) ?: return@mapNotNull null
                        item to if(current<today) item.scheduledDate(YearMonth.from(today).plusMonths(1))!! else current
                    }.sortedBy { it.second }.take(5)
                    if(upcoming.isEmpty()) Text("설정된 매월 일정이 없습니다.",color=Muted)
                    upcoming.forEach { (item,date) -> Text("${date.monthValue}/${date.dayOfMonth} · ${item.title}${item.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",Modifier.fillMaxWidth().clickable { detail=item }) }
                } }
            }
            1 -> {
                item { Heading("월 계획 항목","항목을 누르면 이체 정보와 원본 위치를 볼 수 있습니다.") }
                item { OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),label={Text("항목·담당·은행·메모 검색")},singleLine=true) }
                item { FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected=kind==null,onClick={kind=null},label={Text("전체")})
                    PlanKind.entries.forEach { option -> FilterChip(selected=kind==option,onClick={kind=option},label={Text(option.label)}) }
                } }
                item { Text("${records.size}개 항목",color=Muted,fontWeight=FontWeight.Bold) }
                items(records.sortedWith(compareBy<PlanItem> { it.kind.ordinal }.thenBy { it.sourceRow }),key={it.key}) { item -> PlanItemRow(item,{detail=item}) }
                if(records.isEmpty()) item { Panel { Text("조건에 맞는 항목이 없습니다.",color=Muted) } }
            }
            2 -> {
                item { MonthSelector(month,{month=it}) }
                item { Text("매월 반복 일정 · 29~31일이 없는 달은 말일로 표시합니다. 보류 항목과 연결된 이체는 일정에서 제외합니다.",color=Muted,fontSize=12.sp) }
                val schedule=Plans.scheduleItems(parsed).sortedBy { it.scheduledDate(month) }
                items(schedule,key={"schedule:${it.key}"}) { item ->
                    Panel { Text("${item.scheduledDate(month)} · ${item.kind.label}",color=Blue,fontSize=13.sp);PlanItemRow(item,{detail=item}) }
                }
                if(schedule.isEmpty()) item { Panel { Text("설정된 매월 일정이 없습니다.",color=Muted) } }
                item { Text("만기일",fontSize=20.sp,fontWeight=FontWeight.Bold) }
                val maturities=parsed.items.filter { it.maturity!=null }.sortedBy { it.maturity }
                items(maturities,key={"maturity:${it.key}"}) { item ->
                    Panel { Text("${item.maturity}"+if(item.maturity!!<LocalDate.now()) " · 지난 날짜" else "",color=Blue,fontSize=13.sp);PlanItemRow(item,{detail=item}) }
                }
                if(maturities.isEmpty()) item { Panel { Text("설정된 만기일이 없습니다.",color=Muted) } }
            }
            else -> {
                item { Panel {
                    Heading("불러오기 설정",mapping.displayName)
                    Text("선택한 ${mapping.plan?.regions?.size ?: 0}개 영역 · ${parsed.items.size}개 항목",color=Muted)
                    Button(onClick=onConfigure,enabled=!state.busy,shape=RoundedCornerShape(999.dp)) { Text("영역·금액·일정 설정") }
                    Text("설정은 현재 기기에 저장됩니다. 월 계획은 원본 시트에서 수정한 뒤 새로고침하면 반영됩니다.",fontSize=13.sp,color=Muted)
                } }
                item { Panel { Heading("데이터 확인");Text("합계·소계 등 ${parsed.excluded.size}개 제외",color=Muted);parsed.excluded.forEach { e -> Text("${mapping.plan?.regions?.firstOrNull { it.id==e.regionId }?.name} · ${e.row+1}행 · ${e.reason}",fontSize=12.sp,color=Muted) };if(parsed.issues.isEmpty()) Text("읽기 오류가 없습니다.",color=Muted);parsed.issues.take(80).forEach { Text("${it.row}행 · ${it.column}: ${it.message}",fontSize=12.sp,color=MaterialTheme.colorScheme.error) } } }
                if(state.connections.isNotEmpty()) item { Panel { Heading("저장한 연결");state.connections.forEach { connection -> TextButton(onClick={vm.openConnection(connection)},enabled=!state.busy) { Text(connection.displayName) } } } }
                item { Panel { TextButton(onClick={vm.demo(3)},enabled=!state.busy) { Text("다른 배치의 월 계획 샘플 보기") };TextButton(onClick={vm.disconnect()},enabled=!state.busy) { Text("현재 연결 해제") };TextButton(onClick=onClear,enabled=!state.busy) { Text("기기에 저장한 연결 지우기") } } }
            }
        }
    }
    detail?.let { item -> PlanDetail(state,item,{detail=null}) }
}

@Composable private fun PlanItemRow(item: PlanItem,onClick: ()->Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick=onClick),color=androidx.compose.ui.graphics.Color.White) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(item.title,fontWeight=FontWeight.Bold)
                Text(listOf(item.kind.label,item.party,item.bank,if(item.paused) "보류" else "",item.note).filter(String::isNotBlank).joinToString(" · "),color=Muted,fontSize=12.sp)
            }
            if(item.kind!=PlanKind.SCHEDULE) Text(money(item.amount),fontWeight=FontWeight.Bold,color=if(item.kind==PlanKind.INCOME) Blue else Ink)
        }
    }
}

@Composable private fun PlanDetail(state: AppState,item: PlanItem,onDismiss: ()->Unit) {
    val context=LocalContext.current
    val mapping=state.mapping ?: return
    val region=mapping.plan?.regions?.firstOrNull { it.id==item.regionId } ?: return
    val related=state.plan.items.filter { it.key!=item.key && ((item.kind==PlanKind.EXPENSE && it.kind==PlanKind.TRANSFER && (it.sourceRow==item.sourceRow || it.parentRow==item.sourceRow)) ||
        (item.kind==PlanKind.TRANSFER && it.kind==PlanKind.EXPENSE && it.sourceRow==(item.parentRow ?: item.sourceRow))) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(item.title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(item.kind.label,color=Blue)
        item.cells.forEach { (field,cell) -> if(!cell.blank) {
            Text("${field.label}: ${cell.value}")
            Text("${A1.col(region.fields.getValue(field))}${item.sourceRow+1}"+if(cell.formula!=null) " · 계산식" else "",color=Muted,fontSize=12.sp)
        } }
        item.parentRow?.let { Text("${it+1}행 항목의 세부내역으로 읽었습니다.",color=Muted,fontSize=12.sp) }
        if(related.isNotEmpty()) { Text("같은 행에 연결된 정보",fontWeight=FontWeight.Bold);related.forEach { r -> Text("${r.kind.label} · ${r.note.ifBlank { r.title }} · ${money(r.amount)} · ${r.bank}",fontSize=13.sp) };Text("원본 행 위치로 연결한 정보입니다. 실제 관계는 영역 설정에서 확인할 수 있습니다.",fontSize=12.sp,color=Muted) }
    }},confirmButton={TextButton(onClick=onDismiss) { Text("닫기") }},dismissButton={if(mapping.spreadsheetId!="demo") TextButton(onClick={
        val col=region.fields[PlanField.TITLE] ?: region.fields.values.first()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://docs.google.com/spreadsheets/d/${mapping.spreadsheetId}/edit#gid=${mapping.sheetId}&range=${A1.col(col)}${item.sourceRow+1}"))) }
    }) { Text("원본에서 보기") }})
}
