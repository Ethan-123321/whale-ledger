package com.yuji.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun ChatPage(
    model: ChatViewModel, bills: List<Bill>, add: () -> Unit, edit: (Bill) -> Unit, revoke: (Bill) -> Unit
) {
    var today by remember { mutableStateOf(LocalDate.now().toString()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now().toString()
            kotlinx.coroutines.delay(1000)
        }
    }
    var pastDay by rememberSaveable { mutableStateOf<String?>(null) }
    val day = pastDay ?: today
    var history by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val days = remember(model.revision, today) { model.store.days() }
    val available = remember(days, today) { (listOf(today) + days.map { it.date }).distinct() }
    val index = available.indexOf(day)
    fun select(date: String) { pastDay = date.takeUnless { it == today } }
    var limit by rememberSaveable(day) { mutableIntStateOf(41) }
    val page = remember(day, limit, model.revision) { model.store.page(day, limit, 0) }
    val rows = page.take(limit - 1)
    val latestUser = rows.firstOrNull { it.user }?.id
    val links = remember(rows, model.revision) { model.store.links(rows) }
    val linked = remember(bills) { bills.associateBy { it.id } }
    val list = key(day) { rememberLazyListState() }
    var follow by remember(day) { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex == 0 }.collect { follow = it }
    }
    LaunchedEffect(rows.firstOrNull()?.id) {
        if (follow) list.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = index in 0 until available.lastIndex, onClick = { select(available[index + 1]) }) { Text("‹") }
            Text(day, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(enabled = index > 0, onClick = { select(available[index - 1]) }) { Text("›") }
            TextButton(onClick = { history = true }) { Text("历史对话") }
        }
        if (model.migrationIssue) Text("旧版对话迁移遇到问题，将在下次启动重试。", Modifier.padding(horizontal = 16.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (model.busy && day == today) item { Text("正在处理…") }
            items(rows, key = { it.id }) { row ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (row.user) Arrangement.End else Arrangement.Start) {
                        if (!row.user) Text(roles[row.role] ?: "💬", Modifier.padding(end = 8.dp))
                        Surface(color = if (row.user) Color(0xFFD4EDE2) else Color.White,
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 290.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                if (!row.user) Text(row.role, style = MaterialTheme.typography.labelSmall)
                                Text(row.text)
                                if (row.status == "failed" && row.id == latestUser && pastDay == null) {
                                    TextButton(enabled = !model.busy, onClick = { model.send(row.text, row) }) { Text("重试") }
                                }
                            }
                        }
                    }
                    for (id in links[row.id].orEmpty()) {
                        val bill = linked[id]
                        if (bill == null) Text("关联账单 · 已撤销", style = MaterialTheme.typography.labelMedium)
                        else BillCard(bill, { edit(bill) }, { revoke(bill) })
                    }
                }
            }
            if (page.size == limit) item { TextButton(onClick = { limit += 40 }) { Text("加载更早消息") } }
            if (rows.isEmpty()) item {
                Text(if (pastDay == null) "你好，今天有什么想记下来的？可以说：午饭28。" else "这天没有对话。")
            }
        }
        if (pastDay != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { pastDay = null }) { Text("返回今天继续聊天") }
                TextButton(enabled = !model.busy && day in days.map { it.date }, onClick = { confirmDelete = true }) { Text("删除对话") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = add) { Text("＋") }
                OutlinedTextField(input, { input = it.take(2000) }, placeholder = { Text("说说这笔收支…") },
                    modifier = Modifier.weight(1f), maxLines = 3)
                TextButton(enabled = !model.busy && input.isNotBlank(), onClick = {
                    follow = true
                    model.send(input)
                    input = ""
                }) { Text("发送") }
            }
        }
    }
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("历史对话") }, text = {
        if (days.isEmpty()) Text("暂无历史对话。")
        else LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(days, key = { it.date }) { row ->
                TextButton(onClick = { select(row.date); history = false }) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(row.date + " · " + row.bills + " 笔关联账单")
                        Text(row.preview, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { history = false }) { Text("关闭") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这天的对话？") },
        text = { Text("关联账单仍保存在账本。") },
        confirmButton = { TextButton(onClick = {
            model.store.deleteDay(day)
            model.refresh()
            pastDay = null
            confirmDelete = false
        }) { Text("删除对话") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}