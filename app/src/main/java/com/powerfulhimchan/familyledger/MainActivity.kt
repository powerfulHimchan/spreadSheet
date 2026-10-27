@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.powerfulhimchan.familyledger

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.powerfulhimchan.familyledger.core.*
import java.math.BigDecimal
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val Ink=Color(0xFF0B1220)
private val Blue=Color(0xFF2563EB)
private val Muted=Color(0xFF64748B)
private val Background=Color(0xFFEEF2F9)
private val Line=Color(0xFFCFD8E6)
private fun money(n: BigDecimal?) = n?.let { DecimalFormat("#,##0.##").format(it) } ?: "—"
private fun roleName(r: Role)=when(r) { Role.NONE->"역할 없음";Role.DATE->"날짜";Role.AMOUNT->"금액";Role.CATEGORY->"카테고리";Role.DESCRIPTION->"내용·사용처";Role.PAYMENT->"결제수단";Role.AUTHOR->"작성자";Role.DIRECTION->"수입·지출 구분";Role.ID->"거래 ID";Role.DELETED->"삭제 여부" }
private fun typeName(t: FieldType)=when(t) { FieldType.TEXT->"문자";FieldType.INTEGER->"정수";FieldType.DECIMAL->"소수";FieldType.CURRENCY->"통화";FieldType.PERCENT->"퍼센트";FieldType.DATE->"날짜";FieldType.TIME->"시간";FieldType.DATETIME->"날짜+시간";FieldType.BOOLEAN->"체크박스";FieldType.ENUM->"선택 목록";FieldType.PHONE->"전화번호";FieldType.EMAIL->"이메일";FieldType.URL->"URL" }

class MainActivity: ComponentActivity() {
    private val model: LedgerViewModel by viewModels()
    private var continuation: (() -> Unit)? = null
    private val authorization=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if(result.resultCode!=Activity.RESULT_OK || result.data==null) { continuation=null;model.message("Google 계정 연결을 취소했습니다.");return@registerForActivityResult }
        runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data!!) }
            .onSuccess { response ->
                val token=response.accessToken
                if(token.isNullOrBlank()) { continuation=null;model.message("시트 접근 권한을 받지 못했습니다.") }
                else { model.accessToken=token;val action=continuation;continuation=null;action?.invoke() }
            }.onFailure { continuation=null;model.message("Google 인증에 실패했습니다. OAuth의 패키지명과 SHA-1 등록을 확인해 주세요.") }
    }
    private fun authorize(action: ()->Unit) {
        if(continuation!=null) return
        continuation=action
        val request=AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/spreadsheets"))).build()
        Identity.getAuthorizationClient(this).authorize(request).addOnSuccessListener { response ->
            if(response.hasResolution()) authorization.launch(IntentSenderRequest.Builder(response.pendingIntent!!.intentSender).build())
            else {
                val token=response.accessToken
                if(token.isNullOrBlank()) { continuation=null;model.message("시트 접근 권한을 받지 못했습니다.") }
                else { model.accessToken=token;val next=continuation;continuation=null;next?.invoke() }
            }
        }.addOnFailureListener { continuation=null;model.message("Google 인증을 시작하지 못했습니다. Google Cloud에서 이 앱의 Android OAuth 설정을 완료해 주세요.") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Blue,onPrimary=Color.White,background=Background,surface=Color.White,onSurface=Ink,outline=Line),shapes=Shapes(small=RoundedCornerShape(0.dp),medium=RoundedCornerShape(0.dp),large=RoundedCornerShape(0.dp))) {
            LedgerApp(model,::authorize)
        } }
    }
}

@Composable private fun Panel(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),color=Color.White,shape=RoundedCornerShape(0.dp)) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content) }
}
@Composable private fun Heading(title: String,detail: String?=null) { Text(title,fontSize=22.sp,fontWeight=FontWeight.Bold);if(detail!=null) Text(detail,color=Muted,fontSize=13.sp) }
@Composable private fun <T> Choice(label: String,value: T,options: List<T>,name: (T)->String,onChange: (T)->Unit,modifier: Modifier=Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label,color=Muted,fontSize=12.sp)
        Box {
            OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(999.dp)) { Text(name(value),maxLines=2);Icon(Icons.Outlined.ExpandMore,null) }
            DropdownMenu(expanded,onDismissRequest={expanded=false}) { options.forEach { option -> DropdownMenuItem(text={Text(name(option))},onClick={onChange(option);expanded=false}) } }
        }
    }
}
private data class Editor(val record: Record?,val copy: Boolean=false)

@Composable private fun LedgerApp(vm: LedgerViewModel,authorize: (() -> Unit)->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var connect by remember { mutableStateOf(false) }
    var mappingEdit by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var confirmIdentity by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmMonthly by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("전체") }
    var author by remember { mutableStateOf("전체") }
    var direction by remember { mutableStateOf("전체") }
    val snackbar=remember { SnackbarHostState() }
    val m=state.mapping
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it);vm.clearMessage() } }
    LaunchedEffect(m?.key) {
        month=state.parsed.records.mapNotNull { it.date }.map { YearMonth.from(it) }.let { list -> if(YearMonth.now() in list) YearMonth.now() else list.maxOrNull() ?: YearMonth.now() }
        category="전체";author="전체";search="";direction="전체"
    }
    LaunchedEffect(state.revision) { editor=null;mappingEdit=false }
    fun online(action: ()->Unit) { if(m?.spreadsheetId=="demo") action() else authorize(action) }
    fun changeMonth(next: YearMonth) { month=next;vm.openMonth(next) }
    val records=if(m?.column(Role.DATE)==null) state.parsed.records else state.parsed.records.filter { it.date?.let { d -> YearMonth.from(d)==month } == true }
    val filtered=records.filter { r -> (category=="전체" || r.category==category) && (author=="전체" || r.author==author) && (direction=="전체" || r.income==(direction=="수입")) && (search.isBlank() || listOf(r.description,r.category,r.payment,r.author).any { it.contains(search,true) }) }
    val analysisReady=m?.column(Role.DATE)!=null && m.column(Role.AMOUNT)!=null
    Scaffold(containerColor=Background,snackbarHost={SnackbarHost(snackbar)},topBar={
        TopAppBar(title={Column { Text("시트장부",fontWeight=FontWeight.Bold);Text(m?.displayName ?: "스프레드시트를 우리 집 장부로",fontSize=12.sp,color=Muted) }},actions={
            if(m!=null) IconButton(onClick={online { vm.refresh() }},enabled=!state.busy) { Icon(Icons.Outlined.Refresh,"새로고침") }
            IconButton(onClick={connect=true},enabled=!state.busy) { Icon(Icons.Outlined.AddLink,"장부 연결") }
        },colors=TopAppBarDefaults.topAppBarColors(containerColor=Background))
    },bottomBar={if(m!=null) NavigationBar(containerColor=Color.White) { listOf("홈","거래","분석","설정").forEachIndexed { i,label -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(listOf(Icons.Outlined.Home,Icons.Outlined.ReceiptLong,Icons.Outlined.BarChart,Icons.Outlined.Settings)[i],label)},label={Text(label)}) } }},
        floatingActionButton={if(m!=null && tab in 0..1) ExtendedFloatingActionButton(onClick={if(m.column(Role.ID)==null || m.column(Role.DELETED)==null) confirmIdentity=true else editor=Editor(null)},icon={Icon(Icons.Outlined.Add,null)},text={Text("거래 추가")},shape=RoundedCornerShape(999.dp),containerColor=Blue,contentColor=Color.White)}) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(m==null) Landing(state,vm,{connect=true}) else {
                if(m.spreadsheetId=="demo") Text("샘플 모드 · 실제 스프레드시트에는 저장되지 않습니다",Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),color=Blue,fontSize=12.sp)
                else if(state.cached) Text("저장된 데이터 · 새로고침하면 최신 내용을 불러옵니다",Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),color=Muted,fontSize=12.sp)
                when(tab) {
                    0 -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,8.dp,16.dp,100.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item { MonthSelector(month,::changeMonth) }
                        if(!analysisReady) item { MappingNotice { mappingEdit=true } }
                        else item { Summary(records,month) }
                        if(state.parsed.issues.isNotEmpty()) item { Panel { Text("확인할 데이터 ${state.parsed.issues.size}건",fontWeight=FontWeight.Bold);Text("날짜·금액 오류가 있는 거래는 합계에서 제외됩니다.",color=Muted);TextButton(onClick={tab=3}) { Text("오류 확인") } } }
                        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text("최근 거래",fontWeight=FontWeight.Bold,fontSize=18.sp);TextButton(onClick={tab=1}) { Text("전체 보기") } } }
                        items(records.sortedByDescending { it.date }.take(5),key={"${it.sourceRow}:${it.id}"}) { r -> RecordRow(r,{editor=Editor(r)},{editor=Editor(r,true)}) }
                        if(records.isEmpty()) item { Empty("이 달에는 거래가 없습니다.") }
                    }
                    1 -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,8.dp,16.dp,110.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        item { MonthSelector(month,::changeMonth) }
                        item { OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),label={Text("내용·카테고리·결제수단 검색")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)}) }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Choice("카테고리",category,listOf("전체")+records.map { it.category }.distinct(),{it},{category=it},Modifier.weight(1f))
                            Choice("작성자",author,listOf("전체")+records.map { it.author }.filter { it.isNotBlank() }.distinct(),{it},{author=it},Modifier.weight(1f))
                        } }
                        if(m.column(Role.DIRECTION)!=null) item { Choice("구분",direction,listOf("전체","수입","지출"),{it},{direction=it}) }
                        item { val totals=Ledger.totals(filtered);Text("${filtered.size}건 · 지출 ${money(totals.expense)}",fontWeight=FontWeight.Bold,color=Muted) }
                        items(filtered.sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.sourceRow }),key={"${it.sourceRow}:${it.id}"}) { r -> RecordRow(r,{editor=Editor(r)},{editor=Editor(r,true)}) }
                        if(filtered.isEmpty()) item { Empty("조건에 맞는 거래가 없습니다.") }
                    }
                    2 -> Analysis(state,month,::changeMonth,{mappingEdit=true})
                    else -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,8.dp,16.dp,28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item { Panel { Heading("장부 연결",m.displayName);Text("${A1.col(m.firstColumn)}${m.headerRow+1}:${A1.col(m.endColumn-1)}${m.dataEndRow}",color=Muted);Button(onClick={mappingEdit=true},shape=RoundedCornerShape(999.dp)) { Text("표 범위·컬럼 매핑") };if(m.column(Role.ID)==null || m.column(Role.DELETED)==null) OutlinedButton(onClick={confirmIdentity=true},enabled=!state.busy) { Text("안전한 편집 설정") };Text("설정은 현재 기기에 저장됩니다.",color=Muted,fontSize=12.sp) } }
                        if(m.spreadsheetId!="demo") item { Panel { Text("월별 탭 통합",fontWeight=FontWeight.Bold);Text("같은 헤더의 월별 탭을 함께 연결하면 분석에서 합쳐 볼 수 있습니다. 현재 표의 연도·마지막 행 설정을 사용합니다.",color=Muted,fontSize=13.sp);OutlinedButton(onClick={confirmMonthly=true},enabled=!state.busy){Text("월별 탭 함께 연결")};TextButton(onClick={online { vm.refreshAll() }},enabled=!state.busy){Text("연결된 표 전체 새로고침")} } }
                        if(state.connections.isNotEmpty()) item { Panel { Text("저장한 연결",fontWeight=FontWeight.Bold);state.connections.forEach { connection -> TextButton(onClick={vm.openConnection(connection)},enabled=!state.busy) { Text(connection.displayName) } } } }
                        item { Panel { Text("다른 구조 체험",fontWeight=FontWeight.Bold);listOf("월별 탭 + 일자","일반 수입·지출 목록","열 순서가 다른 영문 장부").forEachIndexed { i,label -> TextButton(onClick={vm.demo(i)},enabled=!state.busy) { Text(label) } } } }
                        item { Panel { Text("데이터 확인",fontWeight=FontWeight.Bold);if(state.parsed.issues.isEmpty()) Text("파싱 오류가 없습니다.",color=Muted);state.parsed.issues.take(80).forEach { issue -> Text("${issue.row}행 · ${issue.column}: ${issue.message}",fontSize=13.sp) };if(state.parsed.issues.size>80) Text("그 외 ${state.parsed.issues.size-80}건") } }
                        item { Panel { Text("개인정보",fontWeight=FontWeight.Bold);Text("장부는 Google Sheets와 기기 안에서 처리됩니다. 별도 서버로 전송하지 않습니다. 로컬 데이터 백업과 화면 캡처는 차단됩니다.",color=Muted,fontSize=13.sp);OutlinedButton(onClick={confirmClear=true},enabled=!state.busy) { Text("기기에 저장한 연결·데이터 지우기") };TextButton(onClick={vm.disconnect()},enabled=!state.busy) { Text("현재 장부 연결 해제") } } }
                    }
                }
            }
        }
    }
    if(connect) ConnectDialog(state.busy,{connect=false}) { url -> connect=false;authorize { vm.connect(url) } }
    if(state.book!=null && state.setupGrid==null) AlertDialog(onDismissRequest={if(!state.busy) vm.cancelSetup()},title={Text(state.book!!.title)},text={Column(Modifier.verticalScroll(rememberScrollState())) { Text("연결할 탭을 선택해 주세요.");state.book!!.sheets.forEach { sheet -> TextButton(onClick={vm.chooseSheet(sheet)},enabled=!state.busy) { Text(sheet.title) } } }},confirmButton={},dismissButton={TextButton(onClick={vm.cancelSetup()},enabled=!state.busy){Text("취소")}})
    if(state.setupGrid!=null || (mappingEdit && m!=null && state.grid!=null)) MappingDialog(state.setupGrid ?: state.grid!!,if(state.setupGrid==null || state.setupGrid?.let { m!=null && it.sheetId==m.sheetId && it.spreadsheetId==m.spreadsheetId }==true) m else null,state.busy,{mappingEdit=false;vm.cancelSetup()},{vm.saveMapping(it)})
    if(editor!=null && m!=null) {
        val editing=editor!!
        EditorDialog(m,editing,state.parsed.records,state.busy,{editor=null},onSave={values -> online { vm.save(if(editing.copy) null else editing.record,values) }},onDelete={online { vm.save(editing.record,emptyMap(),true) }})
    }
    if(confirmIdentity) AlertDialog(onDismissRequest={confirmIdentity=false},title={Text("안전한 편집 설정")},text={Text("시트 오른쪽 끝에 필요한 ID·삭제 상태 열을 추가하고 숨깁니다(최대 2개). 기존 ID가 비어 있으면 고유 ID를 채웁니다. 원래 거래 값과 수식은 유지됩니다. 지정한 입력 범위를 먼저 확인해 주세요.")},confirmButton={TextButton(onClick={confirmIdentity=false;online { vm.installIdentity() }},enabled=!state.busy){Text("열 추가하고 설정")}},dismissButton={TextButton(onClick={confirmIdentity=false}){Text("나중에")}})
    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("이 기기의 데이터 지우기")},text={Text("저장한 연결 설정과 오프라인 데이터를 지웁니다. 원본 스프레드시트는 유지됩니다.")},confirmButton={TextButton(onClick={confirmClear=false;vm.clearDeviceData()}){Text("지우기")}},dismissButton={TextButton(onClick={confirmClear=false}){Text("취소")}})
    if(confirmMonthly) AlertDialog(onDismissRequest={confirmMonthly=false},title={Text("월별 탭을 함께 연결할까요?")},text={Text("같은 스프레드시트에서 헤더가 일치하는 월별 탭을 읽고 현재 표의 매핑을 적용합니다. 시트에는 쓰지 않습니다. 연결 후 각 탭의 입력 범위를 확인해 주세요.")},confirmButton={TextButton(onClick={confirmMonthly=false;online { vm.linkMonthlyTabs() }},enabled=!state.busy){Text("함께 연결")}},dismissButton={TextButton(onClick={confirmMonthly=false}){Text("취소")}})
}

@Composable private fun MappingDialog(g: Grid,existing: TableMapping?,busy: Boolean,onDismiss: ()->Unit,onSave: (TableMapping)->Unit) {
    val suggestions=remember(g) { Detection.candidates(g,LocalDate.now().year) }
    val rebound=existing?.let { old ->
        if(runCatching { Writes.verifyStructure(g,old) }.isSuccess) old else {
            val keys=old.columns.filter { it.role !in setOf(Role.ID,Role.DELETED) }.map { it.key }.toSet()
            suggestions.firstOrNull { it.columns.map { c->c.key }.toSet()==keys }?.let { candidate ->
                val detected=Detection.mapping(g,candidate.headerRow,candidate.firstColumn,candidate.endColumn,minOf(old.dataEndRow,g.rowCount,10000),old.year,old)
                val aux=old.columns.filter { it.role in setOf(Role.ID,Role.DELETED) && detected.columns.none { c->c.key==it.key } }.mapNotNull { c ->
                    val matches=(0 until g.columnCount.coerceAtMost(200)).filter { g.cell(candidate.headerRow,it).value==c.header }
                    matches.singleOrNull()?.let { c.copy(index=it) }
                }
                detected.copy(columns=detected.columns+aux)
            }
        }
    }
    val initial=rebound ?: suggestions.firstOrNull() ?: runCatching { Detection.mapping(g,0,0,minOf(g.columnCount,5),minOf(g.rowCount,10000),LocalDate.now().year) }.getOrNull()
    if(initial==null) { AlertDialog(onDismissRequest=onDismiss,title={Text("입력 영역 확인")},text={Text("연결할 탭에 헤더와 입력할 행이 필요합니다.")},confirmButton={TextButton(onClick=onDismiss){Text("닫기")}});return }
    var mapping by remember(g,existing) { mutableStateOf(initial) }
    var header by remember(g) { mutableStateOf((initial.headerRow+1).toString()) }
    var start by remember(g) { mutableStateOf(A1.col(initial.firstColumn)) }
    var end by remember(g) { mutableStateOf(A1.col(initial.endColumn-1)) }
    var last by remember(g) { mutableStateOf(initial.dataEndRow.toString()) }
    var year by remember(g) { mutableStateOf(initial.year.toString()) }
    var month by remember(g) { mutableStateOf(initial.month?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun choose(m: TableMapping) { mapping=m;header=(m.headerRow+1).toString();start=A1.col(m.firstColumn);end=A1.col(m.endColumn-1);last=m.dataEndRow.toString();month=m.month?.toString().orEmpty();error=null }
    fun update(c: Column) { mapping=mapping.copy(columns=mapping.columns.map { if(it.key==c.key) c else it }) }
    Dialog(onDismissRequest={if(!busy) onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.96f).padding(horizontal=10.dp),color=Background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text("표 연결 설정",fontSize=22.sp,fontWeight=FontWeight.Bold);TextButton(onClick=onDismiss,enabled=!busy){Text("닫기")} }
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Panel { Text(g.title,fontWeight=FontWeight.Bold);Text("사용할 표와 입력 영역을 확인해 주세요. 수식은 자동으로 보호됩니다.",fontSize=13.sp,color=Muted)
                        if(suggestions.size>1) Choice("발견한 표",mapping,suggestions,{"${A1.col(it.firstColumn)}${it.headerRow+1}:${A1.col(it.endColumn-1)} · ${it.columns.joinToString { c->c.header }.take(38)}"},{choose(it)})
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(header,{header=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("헤더 행")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                            OutlinedTextField(last,{last=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("입력 마지막 행")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedTextField(start,{start=it.uppercase().filter(Char::isLetter)},Modifier.weight(1f),label={Text("시작 열")},singleLine=true);OutlinedTextField(end,{end=it.uppercase().filter(Char::isLetter)},Modifier.weight(1f),label={Text("끝 열")},singleLine=true) }
                        OutlinedButton(onClick={runCatching {
                            val detected=Detection.mapping(g,header.toInt()-1,A1.index(start),A1.index(end)+1,last.toInt(),year.toInt(),mapping)
                            val aux=mapping.columns.filter { it.role in setOf(Role.ID,Role.DELETED) && detected.columns.none { d->d.index==it.index } && g.cell(detected.headerRow,it.index).value==it.header }
                            mapping=detected.copy(columns=detected.columns+aux);error=null
                        }.onFailure { error=it.message ?: "범위를 확인해 주세요." }},enabled=!busy){Text("이 범위로 다시 인식")}
                        Text("다른 표·소계·안내가 입력 영역에 포함되지 않게 설정해 주세요.",fontSize=12.sp,color=Muted)
                    } }
                    item { Panel { Text("날짜 해석",fontWeight=FontWeight.Bold);Text("월별 탭에 일자만 있는 경우 연도와 월이 필요합니다.",fontSize=12.sp,color=Muted)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(year,{year=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("연도")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                            OutlinedTextField(month,{month=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("월 (선택)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        }
                        Choice("숫자 표기",mapping.numberStyle,NumberStyle.entries,{if(it==NumberStyle.DOT_DECIMAL) "1,234.56 (쉼표 천 단위)" else "1.234,56 (쉼표 소수점)"},{mapping=mapping.copy(numberStyle=it)})
                    } }
                    items(mapping.columns.filter { it.role !in setOf(Role.ID,Role.DELETED) || it.index in mapping.firstColumn until mapping.endColumn },key={it.key}) { c ->
                        Panel {
                            Text("${A1.col(c.index)} · ${c.header}",fontWeight=FontWeight.Bold,fontSize=17.sp)
                            val sample=(mapping.headerRow+1 until minOf(g.rows.size,mapping.headerRow+8)).map { g.cell(it,c.index).value }.filter { it.isNotEmpty() }.take(3)
                            if(sample.isNotEmpty()) Text("예: ${sample.joinToString(" / ")}",color=Muted,fontSize=12.sp,maxLines=2)
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Choice("타입",c.type,FieldType.entries,{typeName(it)},{update(c.copy(type=it))},Modifier.weight(1f))
                                Choice("분석 역할",c.role,Role.entries,{roleName(it)},{role->update(c.copy(role=role,type=when(role){Role.DATE->FieldType.DATE;Role.AMOUNT->if(c.type in setOf(FieldType.INTEGER,FieldType.CURRENCY,FieldType.DECIMAL)) c.type else FieldType.CURRENCY;else->c.type}))},Modifier.weight(1f))
                            }
                            if(c.type==FieldType.DATE) Choice("날짜 저장 방식",c.dateMode,DateMode.entries,{if(it==DateMode.DAY_OF_MONTH) "탭의 연·월 + 일자" else "전체 날짜"},{update(c.copy(dateMode=it))})
                            val locked=(mapping.headerRow+1 until minOf(mapping.dataEndRow,g.rows.size)).any { g.cell(it,c.index).formula!=null } || c.validationRange!=null && c.options.isEmpty()
                            Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(c.readOnly,{update(c.copy(readOnly=it))},enabled=!locked);Text(if(locked) "수식·미확인 검증 규칙으로 읽기 전용" else "읽기 전용",fontSize=13.sp) }
                            if(!c.readOnly) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(c.required,{update(c.copy(required=it))});Text("필수 입력",fontSize=13.sp) }
                            if(c.labels.isNotEmpty()) Text("코드 표시: ${c.labels.entries.joinToString { "${it.key}=${it.value}" }}",fontSize=12.sp,color=Muted)
                            if(c.options.isNotEmpty()) Text("선택지: ${c.options.take(12).joinToString()}",fontSize=12.sp,color=Muted)
                            var labelEdit by remember(c.key) { mutableStateOf(false) }
                            TextButton(onClick={labelEdit=!labelEdit}){Text("선택지·코드 표시 설정")}
                            if(labelEdit) {
                                var text by remember(c.key) { mutableStateOf(c.labels.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
                                OutlinedTextField(text,{value->text=value;val labels=value.lines().mapNotNull { line->val pair=line.split('=',limit=2);if(pair.size==2 && pair[0].trim().isNotEmpty()) pair[0].trim() to pair[1].trim() else null }.toMap();update(c.copy(labels=labels,options=if(c.strictOptions) c.options else (c.options+labels.keys).distinct()))},label={Text("한 줄에 코드=표시명")},placeholder={Text("1=가족카드\n2=현금")},modifier=Modifier.fillMaxWidth())
                                if(c.type==FieldType.ENUM) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(c.enumNumeric,{update(c.copy(enumNumeric=it))});Text("선택지 코드를 숫자로 저장",fontSize=13.sp) }
                            }
                        }
                    }
                    if(mapping.column(Role.DIRECTION)!=null) item { Panel { Text("수입·지출 값",fontWeight=FontWeight.Bold);var expense by remember { mutableStateOf(mapping.expenseValues.joinToString(",")) };var income by remember { mutableStateOf(mapping.incomeValues.joinToString(",")) };OutlinedTextField(expense,{expense=it;mapping=mapping.copy(expenseValues=it.split(',').map(String::trim).filter(String::isNotEmpty).toSet())},label={Text("지출 값 (쉼표로 구분)")});OutlinedTextField(income,{income=it;mapping=mapping.copy(incomeValues=it.split(',').map(String::trim).filter(String::isNotEmpty).toSet())},label={Text("수입 값 (쉼표로 구분)")}) } }
                    item { val messages=mapping.validate();if(messages.isNotEmpty()) Text(messages.joinToString("\n"),color=MaterialTheme.colorScheme.error);error?.let { Text(it,color=MaterialTheme.colorScheme.error) };if(!g.complete) Text("이 시트는 초기 지원 범위(10,000행·200열)를 초과하여 조회만 가능합니다.",color=MaterialTheme.colorScheme.error) }
                }
                Button(onClick={runCatching {
                    // Require re-detection after coordinate edits; never save roles bound to stale positions.
                    check(header.toInt()-1==mapping.headerRow && A1.index(start)==mapping.firstColumn && A1.index(end)+1==mapping.endColumn && last.toInt()==mapping.dataEndRow) { "변경한 범위로 다시 인식을 눌러 주세요." }
                    val m=mapping.copy(year=year.toInt(),month=month.toIntOrNull());check(m.validate().isEmpty()){m.validate().joinToString("\n")}
                    if(m.columns.any { it.dateMode==DateMode.DAY_OF_MONTH && it.type==FieldType.DATE }) check(m.month!=null) { "일자 방식에는 월을 지정해 주세요." }
                    onSave(m)
                }.onFailure { error=it.message ?: "설정을 확인해 주세요." }},enabled=!busy,modifier=Modifier.fillMaxWidth().padding(16.dp),shape=RoundedCornerShape(999.dp)){Text("이 설정으로 연결")}
            }
        }
    }
}

@Composable private fun EditorDialog(m: TableMapping,editor: Editor,records: List<Record>,busy: Boolean,onDismiss: ()->Unit,onSave: (Map<String,String>)->Unit,onDelete: ()->Unit) {
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val requester=remember { FocusRequester() }
    val editable=m.columns.filter { !it.readOnly && it.role !in setOf(Role.ID,Role.DELETED) }
    val values=remember(m.key,editor) { mutableStateMapOf<String,String>().apply { editable.forEach { col ->
        val original=editor.record?.cells?.get(col.key)
        val initial=when {
            original!=null && !original.blank -> Values.input(original,col,m)
            col.type==FieldType.DATE -> if(col.dateMode==DateMode.DAY_OF_MONTH && m.month!=null) LocalDate.of(m.year,m.month!!,LocalDate.now().dayOfMonth.coerceAtMost(YearMonth.of(m.year,m.month!!).lengthOfMonth())).toString() else LocalDate.now().toString()
            col.type==FieldType.BOOLEAN -> "false"
            else -> ""
        }
        this[col.key]=if(col.labels.isNotEmpty()) Values.number(initial)?.stripTrailingZeros()?.toPlainString() ?: initial else initial
    } } }
    val errors=remember { mutableStateMapOf<String,String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    fun save() {
        errors.clear()
        editable.forEach { c -> runCatching { Values.typed(values[c.key].orEmpty(),c,m) }.onFailure { errors[c.key]=it.message ?: "입력값을 확인해 주세요." } }
        if(errors.isEmpty()) { focus.clearFocus();onSave(values.toMap()) }
    }
    val firstText=editable.firstOrNull { it.type !in setOf(FieldType.DATE,FieldType.TIME,FieldType.DATETIME,FieldType.BOOLEAN) && !it.strictOptions }
    LaunchedEffect(firstText?.key) { if(firstText!=null) runCatching { requester.requestFocus() } }
    Dialog(onDismissRequest={if(!busy) onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.96f).padding(horizontal=10.dp),color=Background) {
            Column(Modifier.imePadding()) {
                Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text(if(editor.record==null || editor.copy) "거래 추가" else "거래 수정",fontSize=22.sp,fontWeight=FontWeight.Bold);TextButton(onClick=onDismiss,enabled=!busy){Text("닫기")} }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    editable.forEachIndexed { index,c ->
                        val value=values[c.key].orEmpty()
                        val label=c.header+(if(c.required) " *" else "")
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            when {
                                c.type==FieldType.BOOLEAN -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text(label);Switch(value.toBoolean(),{values[c.key]=it.toString()},enabled=!busy) }
                                c.strictOptions || c.labels.isNotEmpty() -> Choice(label,value,listOf("")+c.options,{if(it.isEmpty()) "선택해 주세요" else c.labels[it] ?: it},{values[c.key]=it})
                                c.type in setOf(FieldType.DATE,FieldType.TIME,FieldType.DATETIME) -> {
                                    Text(label,fontSize=13.sp,color=Muted)
                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        if(c.type!=FieldType.TIME) OutlinedButton(onClick={
                                            val date=Values.isoDate(value.take(10)) ?: LocalDate.now()
                                            DatePickerDialog(context,{_,y,mo,d->val selected=LocalDate.of(y,mo+1,d).toString();values[c.key]=if(c.type==FieldType.DATETIME) selected+"T"+(value.substringAfter('T',"09:00")) else selected},date.year,date.monthValue-1,date.dayOfMonth).show()
                                        },enabled=!busy,modifier=Modifier.weight(1f)){Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(value.take(10).ifBlank { "날짜 선택" })}
                                        if(c.type!=FieldType.DATE) OutlinedButton(onClick={
                                            val time=runCatching { LocalTime.parse(value.substringAfter('T',value)) }.getOrDefault(LocalTime.of(9,0))
                                            TimePickerDialog(context,{_,h,min->val selected=String.format(java.util.Locale.ROOT,"%02d:%02d",h,min);values[c.key]=if(c.type==FieldType.DATETIME) value.take(10).ifBlank { LocalDate.now().toString() }+"T"+selected else selected},time.hour,time.minute,true).show()
                                        },enabled=!busy,modifier=Modifier.weight(1f)){Text(value.substringAfter('T',if(c.type==FieldType.TIME) value else "09:00").ifBlank { "시간 선택" })}
                                    }
                                }
                                else -> {
                                    val numeric=c.type in setOf(FieldType.INTEGER,FieldType.DECIMAL,FieldType.CURRENCY,FieldType.PERCENT)
                                    val decimal=c.type in setOf(FieldType.DECIMAL,FieldType.CURRENCY,FieldType.PERCENT)
                                    val ime=if(index==editable.lastIndex) ImeAction.Done else ImeAction.Next
                                    val keyboard=when(c.type) { FieldType.INTEGER->KeyboardType.Number;FieldType.DECIMAL,FieldType.CURRENCY,FieldType.PERCENT->KeyboardType.Decimal;FieldType.PHONE->KeyboardType.Phone;FieldType.EMAIL->KeyboardType.Email;FieldType.URL->KeyboardType.Uri;else->KeyboardType.Text }
                                    OutlinedTextField(value,{new ->
                                        if(!numeric) values[c.key]=new else {
                                            val sep=if(m.numberStyle==NumberStyle.COMMA_DECIMAL) ',' else '.'
                                            val raw=if(m.numberStyle==NumberStyle.DOT_DECIMAL) new.replace(",","") else new
                                            if(raw.matches(Regex(if(decimal) "-?[0-9]*[${Regex.escape(sep.toString())}]?[0-9]*" else "-?[0-9]*"))) values[c.key]=raw
                                        }
                                    },modifier=Modifier.fillMaxWidth().then(if(c.key==firstText?.key) Modifier.focusRequester(requester) else Modifier),label={Text(label)},singleLine=true,enabled=!busy,isError=c.key in errors,
                                        keyboardOptions=KeyboardOptions(keyboardType=keyboard,imeAction=ime),keyboardActions=KeyboardActions(onNext={focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)},onDone={save()}),
                                        visualTransformation=if(c.type==FieldType.INTEGER) GroupedNumber else VisualTransformation.None,
                                        suffix=if(c.type==FieldType.PERCENT) {{Text("%")}} else null,
                                        trailingIcon=if(numeric) {{TextButton(onClick={values[c.key]=if(value.startsWith('-')) value.removePrefix("-") else "-$value"},enabled=!busy){Text("±")}}} else null)
                                    if(c.type in setOf(FieldType.CURRENCY,FieldType.DECIMAL)) Values.number(value,m.numberStyle)?.let { Text(money(it),fontSize=12.sp,color=Muted) }
                                    val recent=(c.options+records.mapNotNull { it.cells[c.key]?.value }).filter { it.isNotBlank() }.distinct().take(4)
                                    if(!numeric && recent.isNotEmpty()) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { recent.forEach { item -> SuggestionChip(onClick={values[c.key]=item},label={Text(item.take(20))},enabled=!busy) } }
                                }
                            }
                            errors[c.key]?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
                        }
                    }
                    if(m.columns.any { it.readOnly && it.role !in setOf(Role.ID,Role.DELETED) }) Text("수식·읽기 전용 열은 저장 시 변경하지 않습니다.",color=Muted,fontSize=12.sp)
                    if(editor.record!=null && !editor.copy) TextButton(onClick={confirmDelete=true},enabled=!busy) { Icon(Icons.Outlined.DeleteOutline,null);Text("거래 삭제") }
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick={save()},enabled=!busy,modifier=Modifier.fillMaxWidth().padding(16.dp),shape=RoundedCornerShape(999.dp)) { if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=Color.White) else Text("저장") }
            }
        }
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("거래를 삭제할까요?")},text={Text("원본 값을 보존하고 앱 집계에서 제외합니다. 기존 시트의 합계 수식은 삭제 상태를 반영하도록 설정하지 않았다면 이 금액을 계속 포함할 수 있습니다.")},confirmButton={TextButton(onClick={confirmDelete=false;onDelete()},enabled=!busy){Text("삭제 표시")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("취소")}})
}

private object GroupedNumber: VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val input=text.text;val sign=if(input.startsWith('-')) "-" else "";val digits=input.removePrefix("-")
        val output=sign+digits.reversed().chunked(3).joinToString(",").reversed()
        val positions=IntArray(input.length+1)
        var raw=0
        for(i in output.indices) if(output[i]!=',') { positions[raw]=i;raw++ }
        positions[input.length]=output.length
        return TransformedText(AnnotatedString(output),object: OffsetMapping {
            override fun originalToTransformed(offset: Int)=positions[offset.coerceIn(0,input.length)]
            override fun transformedToOriginal(offset: Int)=output.take(offset.coerceIn(0,output.length)).count { it!=',' }.coerceAtMost(input.length)
        })
    }
}

@Composable private fun Landing(state: AppState,vm: LedgerViewModel,onConnect: ()->Unit) {
    LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { Spacer(Modifier.height(24.dp));Icon(Icons.Outlined.TableChart,null,tint=Blue,modifier=Modifier.size(48.dp));Spacer(Modifier.height(20.dp));Text("시트는 그대로,\n장부는 더 편하게.",fontSize=32.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp));Text("구글 스프레드시트를 연결하고\n우리 집에 맞는 입력 화면과 분석을 만나보세요.",color=Muted,lineHeight=24.sp) }
        item { Button(onClick=onConnect,Modifier.fillMaxWidth().height(52.dp),enabled=!state.busy,shape=RoundedCornerShape(999.dp)) { Text("스프레드시트 연결") } }
        item { Panel { Text("먼저 살펴보기",fontWeight=FontWeight.Bold);Text("실제 계정 연결 없이 세 가지 장부 구조를 체험합니다.",color=Muted,fontSize=13.sp);listOf("월별 탭 장부","일반 수입·지출 장부","영문 장부").forEachIndexed { i,label -> OutlinedButton(onClick={vm.demo(i)},Modifier.fillMaxWidth(),enabled=!state.busy,shape=RoundedCornerShape(999.dp)) { Text(label) } } } }
        if(state.connections.isNotEmpty()) item { Panel { Text("저장한 장부",fontWeight=FontWeight.Bold);state.connections.forEach { m -> TextButton(onClick={vm.openConnection(m)},enabled=!state.busy) { Text(m.displayName) } } } }
    }
}
@Composable private fun ConnectDialog(busy: Boolean,onDismiss: ()->Unit,onConnect: (String)->Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text("스프레드시트 연결")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { Text("공유받은 시트의 URL을 붙여 넣어 주세요. Google 계정의 공유 권한으로 접근합니다.",color=Muted);OutlinedTextField(url,{url=it},label={Text("구글 스프레드시트 URL")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth()) }},confirmButton={TextButton(onClick={onConnect(url)},enabled=A1.spreadsheetId(url)!=null && !busy){Text("계정 연결하고 불러오기")}},dismissButton={TextButton(onClick=onDismiss){Text("취소")}})
}
@Composable private fun MonthSelector(month: YearMonth,onChange: (YearMonth)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick={onChange(month.minusMonths(1))}){Icon(Icons.Outlined.ChevronLeft,"이전 달")}
        Text("${month.year}년 ${month.monthValue}월",fontSize=20.sp,fontWeight=FontWeight.Bold)
        IconButton(onClick={onChange(month.plusMonths(1))}){Icon(Icons.Outlined.ChevronRight,"다음 달")}
    }
}
@Composable private fun Summary(records: List<Record>,month: YearMonth) {
    val totals=Ledger.totals(records,month)
    Panel { Text("이번 달 지출",color=Muted);Text(money(totals.expense),fontSize=36.sp,fontWeight=FontWeight.Bold,color=Ink);HorizontalDivider(color=Line);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Column { Text("수입",color=Muted,fontSize=12.sp);Text(money(totals.income),fontWeight=FontWeight.Bold) };Column { Text("수입 − 지출",color=Muted,fontSize=12.sp);Text(money(totals.balance),fontWeight=FontWeight.Bold) };Column { Text("집계 거래",color=Muted,fontSize=12.sp);Text("${totals.count}건",fontWeight=FontWeight.Bold) } };if(records.size>totals.count) Text("금액 오류 ${records.size-totals.count}건은 집계에서 제외됩니다.",color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
}
@Composable private fun MappingNotice(onConfigure: ()->Unit) { Panel { Text("분석에 사용할 열을 지정해 주세요.",fontWeight=FontWeight.Bold);Text("날짜와 금액 역할을 연결하면 월별 요약과 그래프를 볼 수 있습니다.",color=Muted);TextButton(onClick=onConfigure){Text("컬럼 매핑 열기")} } }
@Composable private fun Empty(text: String) { Panel { Text(text,color=Muted,modifier=Modifier.padding(vertical=20.dp)) } }
@Composable private fun RecordRow(r: Record,onEdit: ()->Unit,onCopy: ()->Unit) {
    Surface(color=Color.White,modifier=Modifier.fillMaxWidth().clickable(onClick=onEdit)) { Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) { Text(r.description.ifBlank { r.category },fontWeight=FontWeight.Medium);Text(listOfNotNull(r.date?.format(DateTimeFormatter.ofPattern("M/d")),r.category,r.payment.ifBlank { null },r.author.ifBlank { null }).joinToString(" · "),fontSize=12.sp,color=Muted) }
        Text((if(r.income) "+" else "")+money(r.amount),fontWeight=FontWeight.Bold,color=if(r.income) Blue else Ink)
        IconButton(onClick=onCopy,modifier=Modifier.size(32.dp)){Icon(Icons.Outlined.ContentCopy,"복사해서 추가",modifier=Modifier.size(17.dp))}
    } }
}
@Composable private fun Analysis(s: AppState,month: YearMonth,onMonth: (YearMonth)->Unit,onMapping: ()->Unit) {
    val ready=s.mapping?.column(Role.DATE)!=null && s.mapping.column(Role.AMOUNT)!=null
    val rows=s.history;val categories=Ledger.categories(rows,month);val totals=Ledger.totals(rows,month);val previous=Ledger.totals(rows,month.minusMonths(1))
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { MonthSelector(month,onMonth) }
        item { Text("연결된 표 ${s.historyTables}개 · 마지막 동기화 데이터 기준",color=Muted,fontSize=12.sp) }
        if(!ready) item { MappingNotice(onMapping) } else {
            item { Summary(rows.filter { it.date?.let { d -> YearMonth.from(d)==month }==true },month) }
            item { Panel { Heading("카테고리별 지출");if(categories.isEmpty()) Text("집계할 지출이 없습니다.",color=Muted);val maximum=categories.values.maxOrNull()?.max(BigDecimal.ONE) ?: BigDecimal.ONE
                categories.forEach { (label,amount) -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label);Text(money(amount),fontWeight=FontWeight.Medium) };LinearProgressIndicator(progress={amount.divide(maximum,6,java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(8.dp),color=Blue,trackColor=Background) }
            } }
            item { Panel { Heading("월별 지출 추이","같은 장부의 연결된 표를 합산합니다.");val monthly=Ledger.monthly(rows).toList().takeLast(12);if(monthly.isEmpty()) Text("날짜와 금액을 확인해 주세요.",color=Muted) else {
                val max=monthly.maxOf { it.second.expense.max(BigDecimal.ZERO) }.max(BigDecimal.ONE)
                Canvas(Modifier.fillMaxWidth().height(120.dp)) { val width=size.width/monthly.size;monthly.forEachIndexed { i,(_,t) -> val height=t.expense.divide(max,6,java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f,1f)*size.height;drawRect(Blue,topLeft=androidx.compose.ui.geometry.Offset(i*width+4,size.height-height),size=androidx.compose.ui.geometry.Size((width-8).coerceAtLeast(1f),height)) } }
                monthly.forEach { (m,t) -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("${m.year}.${m.monthValue}",color=Muted);Text(money(t.expense)) } }
            } } }
            item { Panel { Heading("소비 요약");if(totals.count==0) Text("이 달의 집계 데이터가 없습니다.",color=Muted) else {
                val average=totals.expense.divide(BigDecimal(month.lengthOfMonth()),0,java.math.RoundingMode.HALF_UP);Text("월 전체 일수 기준 일평균 지출은 ${money(average)}입니다.")
                if(previous.count>0) Text("전월 대비 지출은 ${money(totals.expense-previous.expense)}만큼 변했습니다.") else Text("전월 비교 데이터가 없습니다.",color=Muted)
                categories.entries.firstOrNull()?.let { Text("가장 지출이 많은 카테고리는 ${it.key}입니다.") }
            } } }
        }
    }
}
