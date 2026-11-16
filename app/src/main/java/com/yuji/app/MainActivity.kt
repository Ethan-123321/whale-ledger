package com.yuji.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF28796C),background=Color(0xFFF6F7F3))) { App(viewModel()) } }
    }
}
val roles = linkedMapOf("大肥鱼" to "🐟", "Claude·大小姐" to "🍊", "小福子" to "👑", "糯米" to "🐱", "阿算盘" to "🧮", "自定义" to "✨")
fun receipt(role: String, count: Int) = when(role) {
    "小福子" -> "启禀陛下，$count 笔开销已录入御账，请陛下过目。"
    "糯米" -> "喵，主人！$count 笔账记好啦，摸摸我的小脑袋吧。"
    "阿算盘" -> "$count 笔账单已保存，财务部今日正常营业。"
    else -> "咕噜……$count 笔账记好了，本鱼继续漂一会儿。"
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App(model: ChatViewModel) {
    val ledger = model.ledger
    val settings = model.settings
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val bills = remember(model.revision) { ledger.list() }
    var role by remember { mutableStateOf(settings.role) }
    var editor by remember { mutableStateOf<Bill?>(null) }
    var adding by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var removed by remember { mutableStateOf<Bill?>(null) }
    var manage by rememberSaveable { mutableStateOf("") }
    fun refresh() = model.refresh()
    fun revoke(bill: Bill) {
        ledger.delete(bill.id)
        removed = bill
        refresh()
    }
    Scaffold(topBar={ TopAppBar(title={ Column { Text(if(tab==0) "${roles[role]} $role" else listOf("","我的账本","消费统计","角色与设置")[tab]); Text("大肥鱼记账 · 让每一笔都有回应",style=MaterialTheme.typography.labelSmall) } },actions={ TextButton(onClick={ adding=true }) { Text("＋ 记一笔") } }) },bottomBar={ NavigationBar { listOf("对话","账本","统计","设置").forEachIndexed { i,s -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("◉","▤","◷","⚙")[i])},label={Text(s)}) } } }) { padding ->
        Column(Modifier.padding(padding).imePadding().fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if(notice.isNotEmpty()) { TextButton(onClick={notice=""}) { Text(notice) } }
            removed?.let { b -> TextButton(onClick={ledger.save(listOf(b));refresh();removed=null;notice="已恢复账单"}) {Text("已撤销 ${b.title} · 点击恢复")} }
            when(tab) {
                0 -> ChatPage(model, bills, { adding = true }, { editor = it }, ::revoke)
                1 -> LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(bills.isEmpty()) item { Text("账本还是空的，点击右上角记下第一笔。") }
                    items(bills,key={it.id}) { b -> BillCard(b,{editor=b},{ledger.delete(b.id);removed=b;refresh()}) }
                }
                2 -> StatsPage(bills)
                3 -> SettingsPage(settings,ledger,role,{role=it;settings.role=it},{refresh();notice=it},{manage=it})
            }
        }
    }
    if (manage == "categories") CategoryManager(model) { manage = "" }
    if (manage == "memory") MemoryManager(model, bills) { manage = "" }
    if (manage == "recurring") RecurringPage(model) { manage = "" }
    if(adding||editor!=null) BillEditor(editor, model.categories.custom(),onDismiss={adding=false;editor=null},onSave={b -> try {ledger.writableDatabase.atomic {
        ledger.save(listOf(b))
        if (editor?.category != null && editor?.category != b.category) model.products.correctCategory(b)
    };refresh();adding=false;editor=null;notice="账单已保存"}catch(e:Exception){notice="保存失败：${e.message}"} })
}
@Composable fun BillCard(b: Bill, edit:()->Unit, delete:()->Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(b.title,style=MaterialTheme.typography.titleMedium);Text("${if(b.income) "+" else "−"}¥${money(b.cents)}") };Text("${b.date} · ${b.category} · 已入账",style=MaterialTheme.typography.bodySmall);Row {TextButton(onClick=edit){Text("编辑")};TextButton(onClick=delete){Text("撤销")}} } }
}
@Composable fun BillEditor(b:Bill?,customCategories:List<CustomCategory>,onDismiss:()->Unit,onSave:(Bill)->Unit) {
    var title by remember {mutableStateOf(b?.title ?: "")};var amount by remember {mutableStateOf(b?.let{money(it.cents)} ?: "")};var category by remember {mutableStateOf(b?.category ?: "未分类")};var date by remember {mutableStateOf(b?.date ?: LocalDate.now().toString())};var income by remember {mutableStateOf(b?.income ?: false)};var error by remember {mutableStateOf("")}
    var categoryMenu by remember {mutableStateOf(false)}
    var custom by remember {mutableStateOf(b!=null && b.category !in Categories.options(b.income) && customCategories.none { it.income == b.income && it.name == b.category } && b.category!="未分类")}
    val options = Categories.options(income) + customCategories.filter { it.income == income }.map { it.name }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(b==null) "手动记一笔" else "编辑账单")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title,{title=it},label={Text("用途 / 名称")});OutlinedTextField(amount,{amount=it},label={Text("金额（元）")})
        Row(verticalAlignment=Alignment.CenterVertically){Text(if(income) "收入" else "支出");Switch(income,{income=it;custom=false;category=if(it) "收入/其他" else "其他/待分类"})}
        Text("分类 · $category")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            (if(income) options.take(4) else listOf("餐饮/正餐","餐饮/奶茶咖啡","交通/公交地铁","数字服务/AI与API","购物/数码耳机")).forEach {c->FilterChip(selected=category==c&&!custom,onClick={category=c;custom=false},label={Text(c)})}
        }
        Row {Box {TextButton(onClick={categoryMenu=true}){Text("全部分类 ▾")};DropdownMenu(expanded=categoryMenu,onDismissRequest={categoryMenu=false},modifier=Modifier.heightIn(max=280.dp)){options.forEach {c->DropdownMenuItem(text={Text(c)},onClick={category=c;custom=false;categoryMenu=false})}}};TextButton(onClick={if(!custom){category="";custom=true}}){Text("＋ 自定义")}}
        if(custom)OutlinedTextField(category,{category=it.take(40)},label={Text("自定义分类")},placeholder={Text("例如：宠物/猫粮")})
        OutlinedTextField(date,{date=it},label={Text("日期 YYYY-MM-DD")});if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={try {require(title.isNotBlank()){ "请输入用途" };LocalDate.parse(date);onSave(Bill(id=b?.id ?: java.util.UUID.randomUUID().toString(),title=title.trim().take(100),cents=parseCents(amount),income=income,category=category.trim().ifBlank{"未分类"},date=date))}catch(e:Exception){error="请检查名称、金额和日期；金额最多两位小数。"}}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
@Composable fun SettingsPage(s:Settings,ledger:Ledger,role:String,onRole:(String)->Unit,notify:(String)->Unit,manage:(String)->Unit) {
    val context=LocalContext.current
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null)runCatching {context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use {it.write(Backup.encode(ledger.list()))}}.onSuccess {notify("账本已导出，不包含密钥、聊天或记忆")}.onFailure {notify("导出失败")} }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null)runCatching {val raw=context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()};ledger.save(Backup.decode(raw))}.onSuccess {notify("账本已合并恢复，相同 ID 不重复导入")}.onFailure {notify("恢复失败：备份格式或数据无效")} }
    var endpoint by remember {mutableStateOf(s.endpoint)};var model by remember {mutableStateOf(s.model)};var token by remember {mutableStateOf("")};var persona by remember {mutableStateOf(s.persona)}
    var provider by remember {mutableStateOf(if(s.endpoint.isBlank() || s.endpoint.trimEnd('/') in listOf("https://api.deepseek.com","https://api.deepseek.com/v1")) "DeepSeek" else "自定义")}
    var loading by remember {mutableStateOf(false)}
    var models by remember {mutableStateOf<List<String>>(emptyList())}
    var manual by remember {mutableStateOf(false)}
    var expanded by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("")}
    val scope=rememberCoroutineScope()
    fun address()=if(provider=="DeepSeek") "https://api.deepseek.com" else endpoint.trim().trimEnd('/')
    fun credential():String {
        if(token.isNotBlank()) return token.trim()
        require(address()==s.endpoint.trim().trimEnd('/')) {"更换平台或地址后，请重新填写对应的密钥"}
        return s.token
    }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { manage("categories") }) { Text("管理自定义分类") } }
        item { TextButton(onClick = { manage("memory") }) { Text("记忆与偏好") } }
        item { TextButton(onClick = { manage("recurring") }) { Text("固定收入与支出 · 查看待确认") } }
        item {Text("选择记账搭子",style=MaterialTheme.typography.titleLarge)}
        items(roles.entries.toList()) { e -> FilterChip(selected=role==e.key,onClick={onRole(e.key)},label={Text("${e.value} ${e.key}")}) }
        item {OutlinedTextField(persona,{persona=it},label={Text("自定义人设")},modifier=Modifier.fillMaxWidth())}
        item {Text("自带 AI 接口",style=MaterialTheme.typography.titleLarge);Text("需要 AI 处理时，本条输入、近期或相关历史、未完成交易和部分偏好会发送到你配置的服务商。完整账本保存在本机。")}
        item {Text("1 · 选择服务商");Row {listOf("DeepSeek","自定义").forEach {p->FilterChip(selected=provider==p,enabled=!loading,onClick={provider=p;models=emptyList();model="";token="";status=""},label={Text(p)},modifier=Modifier.padding(end=8.dp))}}}
        if(provider=="自定义") item {OutlinedTextField(endpoint,{endpoint=it;models=emptyList();model=""},enabled=!loading,label={Text("HTTPS 基础地址")},placeholder={Text("https://example.com/v1")},modifier=Modifier.fillMaxWidth())}
        else item {Text("已自动配置 DeepSeek 官方接口")}
        item {OutlinedTextField(token,{token=it;models=emptyList()},enabled=!loading,label={Text("2 · API Key（同一地址留空保留密钥）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())}
        item {Button(enabled=!loading,onClick={
            scope.launch {
                loading=true;status="正在获取模型…"
                try {val base=address();val key=credential();val found=withContext(Dispatchers.IO){Ai.models(base,key)};models=found;if(model !in found)model="";status="连接成功，获取到 ${found.size} 个模型，请选择";expanded=true}
                catch(e:Exception){status=when(e){is java.net.SocketTimeoutException->"连接超时，请检查网络后重试";is java.net.UnknownHostException->"无法连接服务商，请检查网络和地址";else->e.message ?: "获取失败，请检查网络或手动输入模型"}}
                finally {loading=false}
            }
        }){Text(if(loading) "连接中…" else "连接并获取模型")};if(status.isNotBlank())Text(status)}
        item {Text("3 · 选择模型");Box {OutlinedButton(enabled=models.isNotEmpty()&&!loading,onClick={expanded=true}){Text(model.ifBlank {"请选择模型"})};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){models.forEach {id->DropdownMenuItem(text={Text(id)},onClick={model=id;expanded=false})}}};TextButton(onClick={manual=!manual}){Text(if(manual) "收起手动输入" else "平台不支持？手动输入模型")}}
        if(manual) item {OutlinedTextField(model,{model=it},enabled=!loading,label={Text("模型 ID")},modifier=Modifier.fillMaxWidth())}
        item {Button(enabled=!loading,onClick={try {val base=address();val url=java.net.URL(base);require(url.protocol=="https"&&url.userInfo==null&&url.query==null&&url.ref==null){"请检查 HTTPS 地址"};require(model.isNotBlank()){ "请先选择模型" };val key=credential();require(key.isNotBlank()){ "请填写 API Key" };s.token=key;s.endpoint=base;s.model=model.trim();s.persona=persona;token="";notify("设置已保存：${s.model}")}catch(e:Exception){status=e.message ?: "保存失败"}}){Text("保存设置")}}
        item {TextButton(onClick={s.token="";notify("API 密钥已清除")}){Text("清除密钥")}}
        item {Row {TextButton(onClick={export.launch("yuji-backup.json")}){Text("导出账本")};TextButton(onClick={restore.launch(arrayOf("application/json"))}){Text("恢复账本")}}}
        item {Text("大肥鱼记账 0.4.4 · 本地优先\n截图识别尚未开放。退款、转账和对话修改暂不自动执行，请通过账单编辑操作。")}
    }
}
