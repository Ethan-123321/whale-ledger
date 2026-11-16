package com.yuji.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.background
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
        val ledger = Ledger(applicationContext)
        val settings = Settings(applicationContext)
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF28796C),background=Color(0xFFF6F7F3))) { App(ledger,settings) } }
    }
}
data class Message(val text: String, val user: Boolean = false)
val roles = linkedMapOf("大肥鱼" to "🐟", "小福子" to "👑", "糯米" to "🐱", "阿算盘" to "🧮", "自定义" to "✨")
fun receipt(role: String, count: Int) = when(role) {
    "小福子" -> "启禀陛下，$count 笔开销已录入御账，请陛下过目。"
    "糯米" -> "喵，主人！$count 笔账记好啦，摸摸我的小脑袋吧。"
    "阿算盘" -> "$count 笔账单已保存，财务部今日正常营业。"
    else -> "咕噜……$count 笔账记好了，本鱼继续漂一会儿。"
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App(ledger: Ledger, settings: Settings) {
    var tab by remember { mutableIntStateOf(0) }
    var bills by remember { mutableStateOf(ledger.list()) }
    var role by remember { mutableStateOf(settings.role) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Bill?>(null) }
    var adding by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var removed by remember { mutableStateOf<Bill?>(null) }
    val messages = remember { mutableStateListOf<Message>().apply {
        runCatching { val a=JSONArray(settings.history); for(i in 0 until a.length()) { val m=a.getJSONObject(i);add(Message(m.getString("text"),m.getBoolean("user"))) } }
        if(isEmpty())add(Message("你好，今天有什么想记下来的？\n可以说：午饭花了 28 元。"))
    } }
    LaunchedEffect(messages.size,busy) { settings.history=JSONArray().apply {messages.takeLast(100).forEach {put(JSONObject().put("text",it.text).put("user",it.user))}}.toString() }
    val scope = rememberCoroutineScope()
    fun refresh() { bills=ledger.list() }
    Scaffold(topBar={ TopAppBar(title={ Column { Text(if(tab==0) "${roles[role]} $role" else listOf("","我的账本","消费统计","角色与设置")[tab]); Text("鱼记 · 让每一笔都有回应",style=MaterialTheme.typography.labelSmall) } },actions={ TextButton(onClick={ adding=true }) { Text("＋ 记一笔") } }) },bottomBar={ NavigationBar { listOf("对话","账本","统计","设置").forEachIndexed { i,s -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("◉","▤","◷","⚙")[i])},label={Text(s)}) } } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if(notice.isNotEmpty()) { TextButton(onClick={notice=""}) { Text(notice) } }
            removed?.let { b -> TextButton(onClick={ledger.save(listOf(b));refresh();removed=null;notice="已恢复账单"}) {Text("已撤销 ${b.title} · 点击恢复")} }
            when(tab) {
                0 -> {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        items(messages) { m -> Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.user) Arrangement.End else Arrangement.Start) {
                            if(!m.user) Text(roles[role] ?: "✨",Modifier.padding(end=8.dp))
                            Surface(color=if(m.user) Color(0xFFD4EDE2) else Color.White,shape=RoundedCornerShape(14.dp),modifier=Modifier.widthIn(max=290.dp)) { Text(m.text,Modifier.padding(14.dp)) }
                        } }
                        if(busy) item { Text("正在识别，尚未入账……") }
                        items(bills.take(4),key={"chat"+it.id}) { b -> BillCard(b,{editor=b},{ledger.delete(b.id);removed=b;refresh();notice="已撤销 ${b.title}"}) }
                    }
                    Row(Modifier.fillMaxWidth().padding(10.dp).imePadding(),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(input,{input=it},placeholder={Text("说说这笔收支…")},modifier=Modifier.weight(1f),maxLines=4)
                        TextButton(enabled=!busy&&input.isNotBlank(),onClick={
                            val text=input; input=""; messages.add(Message(text,true)); busy=true
                            scope.launch {
                                try {
                                    val result=withContext(Dispatchers.IO){Ai.parse(settings,text)}
                                    if(result.bills.isNotEmpty()) { withContext(Dispatchers.IO){ledger.save(result.bills)};refresh(); messages.add(Message("已入账 ${result.bills.size} 笔。\n"+result.reply)) }
                                    else messages.add(Message(result.reply))
                                } catch(e:Exception) { messages.add(Message(e.message ?: "请求失败，未入账。请重试或手动添加。")) }
                                finally { busy=false }
                            }
                        }) { Text("发送") }
                        TextButton(onClick={adding=true}) { Text("＋") }
                    }
                }
                1 -> LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(bills.isEmpty()) item { Text("账本还是空的，点击右上角记下第一笔。") }
                    items(bills,key={it.id}) { b -> BillCard(b,{editor=b},{ledger.delete(b.id);removed=b;refresh()}) }
                }
                2 -> {
                    val month=LocalDate.now().toString().take(7); val current=bills.filter { it.date.startsWith(month) }
                    val expense=current.filter { !it.income }.sumOf { it.cents }; val income=current.filter { it.income }.sumOf { it.cents }
                    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                        item { Text("$month · 本月概览",style=MaterialTheme.typography.headlineSmall) }
                        item { Text("收入 ¥${money(income)}\n支出 ¥${money(expense)}\n结余 ¥${money(income-expense)}",style=MaterialTheme.typography.titleLarge) }
                        items(current.filter { !it.income }.groupBy { it.category }.entries.toList().sortedByDescending { e-> e.value.sumOf { it.cents } }) { e ->
                            val total=e.value.sumOf { it.cents }; Column { Text("${e.key}    ¥${money(total)}"); LinearProgressIndicator(progress={if(expense==0L) 0f else total.toFloat()/expense},modifier=Modifier.fillMaxWidth().padding(top=8.dp)) }
                        }
                        if(current.isEmpty()) item { Text("本月暂无数据，所有统计来自实际账单。") }
                    }
                }
                3 -> SettingsPage(settings,ledger,role,{role=it;settings.role=it},{refresh();notice=it})
            }
        }
    }
    if(adding||editor!=null) BillEditor(editor,onDismiss={adding=false;editor=null},onSave={b -> try {ledger.save(listOf(b));refresh();adding=false;editor=null;notice="账单已保存"}catch(e:Exception){notice="保存失败：${e.message}"} })
}
@Composable fun BillCard(b: Bill, edit:()->Unit, delete:()->Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(b.title,style=MaterialTheme.typography.titleMedium);Text("${if(b.income) "+" else "−"}¥${money(b.cents)}") };Text("${b.date} · ${b.category} · 已入账",style=MaterialTheme.typography.bodySmall);Row {TextButton(onClick=edit){Text("编辑")};TextButton(onClick=delete){Text("撤销")}} } }
}
@Composable fun BillEditor(b:Bill?,onDismiss:()->Unit,onSave:(Bill)->Unit) {
    var title by remember {mutableStateOf(b?.title ?: "")};var amount by remember {mutableStateOf(b?.let{money(it.cents)} ?: "")};var category by remember {mutableStateOf(b?.category ?: "未分类")};var date by remember {mutableStateOf(b?.date ?: LocalDate.now().toString())};var income by remember {mutableStateOf(b?.income ?: false)};var error by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(b==null) "手动记一笔" else "编辑账单")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title,{title=it},label={Text("用途 / 名称")});OutlinedTextField(amount,{amount=it},label={Text("金额（元）")});OutlinedTextField(category,{category=it},label={Text("分类")});OutlinedTextField(date,{date=it},label={Text("日期 YYYY-MM-DD")});Row(verticalAlignment=Alignment.CenterVertically){Text(if(income) "收入" else "支出");Switch(income,{income=it})};if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={try {require(title.isNotBlank()){ "请输入用途" };LocalDate.parse(date);onSave(Bill(id=b?.id ?: java.util.UUID.randomUUID().toString(),title=title.trim(),cents=parseCents(amount),income=income,category=category.ifBlank{"未分类"},date=date))}catch(e:Exception){error="请检查名称、金额和日期；金额最多两位小数。"}}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
@Composable fun SettingsPage(s:Settings,ledger:Ledger,role:String,onRole:(String)->Unit,notify:(String)->Unit) {
    val context=LocalContext.current
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null)runCatching {context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use {it.write(Backup.encode(ledger.list()))}}.onSuccess {notify("账本已导出，不包含密钥和聊天记录")}.onFailure {notify("导出失败")} }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null)runCatching {val raw=context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()};ledger.save(Backup.decode(raw))}.onSuccess {notify("账本已合并恢复，相同 ID 不重复导入")}.onFailure {notify("恢复失败：备份格式或数据无效")} }
    var endpoint by remember {mutableStateOf(s.endpoint)};var model by remember {mutableStateOf(s.model)};var token by remember {mutableStateOf("")};var persona by remember {mutableStateOf(s.persona)}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("选择记账搭子",style=MaterialTheme.typography.titleLarge)}
        items(roles.entries.toList()) { e -> FilterChip(selected=role==e.key,onClick={onRole(e.key)},label={Text("${e.value} ${e.key}")}) }
        item {OutlinedTextField(persona,{persona=it},label={Text("自定义人设")},modifier=Modifier.fillMaxWidth())}
        item {Text("自带 AI 接口",style=MaterialTheme.typography.titleLarge);Text("启用 AI 后，输入文字将发送到下方服务商。账本保存在本机；请勿填写不信任的地址。")}
        item {OutlinedTextField(endpoint,{endpoint=it},label={Text("API 基础地址（含 /v1）")},placeholder={Text("https://example.com/v1")},modifier=Modifier.fillMaxWidth())}
        item {OutlinedTextField(model,{model=it},label={Text("模型名称")},modifier=Modifier.fillMaxWidth())}
        item {OutlinedTextField(token,{token=it},label={Text("API Key（留空保留原密钥）")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())}
        item {Button(onClick={try {require(endpoint.isBlank()||java.net.URL(endpoint).protocol=="https");s.endpoint=endpoint.trim();s.model=model.trim();s.persona=persona;if(token.isNotBlank())s.token=token.trim();token="";notify("设置已保存")}catch(e:Exception){notify("保存失败，请检查 HTTPS 地址")}}){Text("保存设置")}}
        item {TextButton(onClick={s.token="";notify("API 密钥已清除")}){Text("清除密钥")}}
        item {Row {TextButton(onClick={export.launch("yuji-backup.json")}){Text("导出账本")};TextButton(onClick={restore.launch(arrayOf("application/json"))}){Text("恢复账本")}}}
        item {Text("鱼记 0.1 · 本地优先\n截图识别尚未开放。退款、转账和对话修改暂不自动执行，请通过账单编辑操作。")}
    }
}
