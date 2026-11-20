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
fun receipt(role: String, count: Int) = when(role) {
    "小福子" -> "启禀陛下，$count 笔开销已录入御账，请陛下过目。"
    "糯米" -> "$count 笔都记好啦，喵！"
    "阿算盘" -> "已保存 $count 笔账单。"
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
    val receiptImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(model::scanReceipt)
    }
    val selectImage: () -> Unit = { receiptImage.launch("image/*") }
    fun refresh() = model.refresh()
    fun revoke(bill: Bill) {
        ledger.delete(bill.id)
        removed = bill
        refresh()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (tab == 0) RoleAvatar(role, Modifier.size(36.dp))
                        Column {
                            Text(if (tab == 0) role else listOf("", "我的账本", "消费统计", "角色与设置")[tab])
                            Text("大肥鱼记账 · 让每一笔都有回应", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = { AddBillMenu("＋ 记一笔", { adding = true }, selectImage) }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("对话", "账本", "统计", "设置").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(listOf("◉", "▤", "◷", "⚙")[index]) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).imePadding().fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if(notice.isNotEmpty()) { TextButton(onClick={notice=""}) { Text(notice) } }
            removed?.let { b -> TextButton(onClick={ledger.save(listOf(b));refresh();removed=null;notice="已恢复账单"}) {Text("已撤销 ${b.title} · 点击恢复")} }
            when(tab) {
                0 -> ChatPage(model, bills, { adding = true }, selectImage, { editor = it }, ::revoke)
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
    model.receiptReading?.let { mode ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在识别账单图片") },
            text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(if (mode == ReceiptMode.LOCAL_OCR) "在设备上提取文字。" else "正在通过多模态接口提取账单。")
            } },
            confirmButton = {}
        )
    }
    if (adding || editor != null || model.receiptDraft != null) BillEditor(
        editor, model.categories.custom(),
        onDismiss = { adding = false; editor = null; model.dismissReceipt() },
        onSave = { b -> try { ledger.writableDatabase.atomic {
        ledger.save(listOf(b))
        if (editor?.category != null && editor?.category != b.category) model.products.correctCategory(b)
    }; refresh(); adding = false; editor = null; model.dismissReceipt(); notice = "账单已保存"
        } catch(e: Exception) { notice = "保存失败：${e.message}" } },
        receipt = model.receiptDraft
    )
}

@Composable
internal fun AddBillMenu(label: String, manual: () -> Unit, image: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("手动记一笔") }, onClick = { expanded = false; manual() })
            DropdownMenuItem(text = { Text("从图片记账") }, onClick = { expanded = false; image() })
        }
    }
}
@Composable fun BillCard(b: Bill, edit:()->Unit, delete:()->Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(b.title,style=MaterialTheme.typography.titleMedium);Text("${if(b.income) "+" else "−"}¥${money(b.cents)}") };Text("${b.date} · ${b.category} · 已入账",style=MaterialTheme.typography.bodySmall);Row {TextButton(onClick=edit){Text("编辑")};TextButton(onClick=delete){Text("撤销")}} } }
}
@Composable fun BillEditor(
    b: Bill?,
    customCategories: List<CustomCategory>,
    onDismiss: () -> Unit,
    onSave: (Bill) -> Unit,
    receipt: ReceiptFields? = null
) {
    val billId = remember { b?.id ?: java.util.UUID.randomUUID().toString() }
    var title by remember { mutableStateOf(b?.title ?: receipt?.title.orEmpty()) }
    var amount by remember { mutableStateOf((b?.cents ?: receipt?.cents)?.let(::money).orEmpty()) }
    var date by remember { mutableStateOf(b?.date ?: if (receipt == null) LocalDate.now().toString() else receipt.date?.toString().orEmpty()) }
    var direction by remember { mutableStateOf(if (receipt == null) b?.income ?: false else receipt.income) }
    val income = direction == true
    fun receiptCategory(isIncome: Boolean): String = Categories.normalize(
        title, "", isIncome, customCategories.filter { it.income == isIncome }.map { it.name }.toSet()
    )
    var category by remember {
        mutableStateOf(b?.category ?: direction?.takeIf { receipt != null }?.let(::receiptCategory) ?: "未分类")
    }
    var error by remember { mutableStateOf("") }
    var categoryMenu by remember {mutableStateOf(false)}
    var custom by remember {mutableStateOf(b!=null && b.category !in Categories.options(b.income) && customCategories.none { it.income == b.income && it.name == b.category } && b.category!="未分类")}
    val options = (Categories.options(income) + customCategories.filter { it.income == income }.map { it.name }).distinct()
    fun chooseDirection(value: Boolean) {
        direction = value
        custom = false
        category = if (receipt == null) Categories.fallback(value) else receiptCategory(value)
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text(when {
        receipt != null -> "核对图片账单"
        b == null -> "手动记一笔"
        else -> "编辑账单"
    })},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if (receipt != null) {
            Text("请核对识别结果，保存后才会入账。", style = MaterialTheme.typography.bodySmall)
            receipt.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        OutlinedTextField(title,{title=it},label={Text("用途 / 名称")});OutlinedTextField(amount,{amount=it},label={Text("金额（元）")})
        if (receipt != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = direction == false, onClick = { chooseDirection(false) }, label = { Text("支出") })
            FilterChip(selected = direction == true, onClick = { chooseDirection(true) }, label = { Text("收入") })
        } else Row(verticalAlignment=Alignment.CenterVertically){Text(if(income) "收入" else "支出");Switch(income,::chooseDirection)}
        if (direction != null) {
        Text("分类 · $category")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            (if(income) options.take(4) else listOf("餐饮/正餐","餐饮/奶茶咖啡","交通/公交地铁","数字服务/AI与API","购物/数码耳机")).forEach {c->FilterChip(selected=category==c&&!custom,onClick={category=c;custom=false},label={Text(c)})}
        }
        Row {Box {TextButton(onClick={categoryMenu=true}){Text("全部分类 ▾")};DropdownMenu(expanded=categoryMenu,onDismissRequest={categoryMenu=false},modifier=Modifier.heightIn(max=280.dp)){options.forEach {c->DropdownMenuItem(text={Text(c)},onClick={category=c;custom=false;categoryMenu=false})}}};TextButton(onClick={if(!custom){category="";custom=true}}){Text("＋ 自定义")}}
        if(custom)OutlinedTextField(category,{category=it.take(40)},label={Text("自定义分类")},placeholder={Text("例如：宠物/猫粮")})
        }
        OutlinedTextField(date,{date=it},label={Text("日期 YYYY-MM-DD")})
        if (receipt != null && date.isBlank()) TextButton(onClick = { date = LocalDate.now().toString() }) { Text("填入今天") }
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(enabled = direction != null, onClick = {
        try {
            require(title.isNotBlank())
            val verifiedDate = LocalDate.parse(date).toString()
            onSave(Bill(billId, title.trim().take(100), parseCents(amount), income, category.trim().ifBlank { "未分类" }, verifiedDate))
        } catch (_: Exception) {
            error = "请检查名称、金额和日期。金额须大于零，最多两位小数。"
        }
    }){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
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
        item { ReceiptSettings(s, notify) }
        item {Text("选择记账搭子",style=MaterialTheme.typography.titleLarge)}
        items(roles.keys.toList(), key = { it }) { name ->
            val selected = role == name
            Column {
                FilterChip(
                    selected = selected,
                    onClick = { onRole(name) },
                    label = { Text(name) },
                    leadingIcon = { RoleAvatar(name, Modifier.size(32.dp)) }
                )
                if (selected) {
                    Text(
                        roles.getValue(name).introduction,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
        item {Text("大肥鱼记账 0.4.8 · 本地优先\n从图片提取的账单需核对后保存。退款、转账和对话修改请通过账单编辑操作。")}
    }
}

@Composable
private fun ReceiptSettings(settings: Settings, notify: (String) -> Unit) {
    var savedMode by remember { mutableStateOf(settings.receiptMode) }
    var mode by remember { mutableStateOf(savedMode) }
    var endpoint by remember { mutableStateOf(settings.receiptEndpoint) }
    var model by remember { mutableStateOf(settings.receiptModel) }
    var token by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(settings.hasReceiptKey) }
    var error by remember { mutableStateOf("") }
    val modeLabels = mapOf(
        ReceiptMode.LOCAL_OCR to "本地 OCR",
        ReceiptMode.CHAT_MODEL to "对话模型",
        ReceiptMode.CUSTOM_MODEL to "独立识图模型"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("图片账单识别", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ReceiptMode.LOCAL_OCR,
                onClick = { mode = ReceiptMode.LOCAL_OCR; error = "" },
                label = { Text("本地 OCR") }
            )
            FilterChip(
                selected = mode != ReceiptMode.LOCAL_OCR,
                onClick = {
                    if (mode == ReceiptMode.LOCAL_OCR) {
                        mode = if (savedMode == ReceiptMode.CUSTOM_MODEL) savedMode else ReceiptMode.CHAT_MODEL
                        error = ""
                    }
                },
                label = { Text("多模态模型") }
            )
        }
        if (mode != savedMode) Text("当前使用 ${modeLabels.getValue(savedMode)}，保存后切换。")
        if (mode == ReceiptMode.LOCAL_OCR) {
            Text("在设备上提取账单文字，可离线使用。")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (source in listOf(ReceiptMode.CHAT_MODEL, ReceiptMode.CUSTOM_MODEL)) {
                    FilterChip(
                        selected = mode == source,
                        onClick = { mode = source; error = "" },
                        label = { Text(modeLabels.getValue(source)) }
                    )
                }
            }
            Text("模型需支持图片输入。应用会将你选中的账单图片发送给对应的服务商。")
        }
        if (mode == ReceiptMode.CHAT_MODEL) {
            Text("使用下方「自带 AI 接口」中已保存的地址、模型和 API Key。")
        }
        if (mode == ReceiptMode.CUSTOM_MODEL) {
            OutlinedTextField(
                endpoint, { endpoint = it }, label = { Text("识图接口 HTTPS 基础地址") },
                placeholder = { Text("https://example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                model, { model = it }, label = { Text("多模态模型 ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                token, { token = it }, label = { Text("识图 API Key") },
                supportingText = if (hasKey) ({ Text("同一地址留空可保留已保存的密钥。") }) else null,
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
        }
        Button(enabled = mode == ReceiptMode.CUSTOM_MODEL || mode != savedMode, onClick = {
            try {
                when (mode) {
                    ReceiptMode.LOCAL_OCR -> settings.useLocalReceiptOcr()
                    ReceiptMode.CHAT_MODEL -> settings.useChatReceiptVision()
                    ReceiptMode.CUSTOM_MODEL -> settings.useReceiptVision(endpoint, model, token)
                }
                savedMode = mode
                hasKey = settings.hasReceiptKey
                token = ""
                error = ""
                notify("图片识别设置已保存 · ${modeLabels.getValue(mode)}")
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "请检查识图设置。"
            } catch (_: Exception) {
                error = "无法保存识图设置，请重试。"
            }
        }) { Text("保存识图设置") }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (mode == ReceiptMode.CUSTOM_MODEL && hasKey) TextButton(onClick = {
            settings.clearReceiptKey()
            hasKey = false
            token = ""
            savedMode = settings.receiptMode
            mode = savedMode
            error = ""
            notify("独立识图密钥已清除 · 当前使用 ${modeLabels.getValue(savedMode)}")
        }) { Text("清除独立识图密钥") }
    }
}
