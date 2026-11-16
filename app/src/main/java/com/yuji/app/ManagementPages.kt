package com.yuji.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CategoryManager(model: ChatViewModel, close: () -> Unit) {
    val categories = remember(model.revision) { model.categories.custom() }
    var edit by remember { mutableStateOf<CustomCategory?>(null) }
    var archive by remember { mutableStateOf<CustomCategory?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("自定义分类") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (categories.isEmpty()) item { Text("可在手动记账时添加自定义分类。") }
            items(categories, key = { it.id }) { category ->
                Text((if (category.income) "收入 · " else "支出 · ") + category.name)
                Row {
                    TextButton(onClick = { edit = category }) { Text("改名") }
                    TextButton(onClick = { archive = category }) { Text("删除") }
                }
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
    edit?.let { category ->
        TextEditDialog("修改分类", category.name, { edit = null }) {
            model.categories.rename(category, it)
            model.refresh()
        }
    }
    archive?.let { category ->
        ConfirmDialog("删除「" + category.name + "」？", "已有账单继续显示原分类，新账单的分类选项会移除它。", { archive = null }) {
            model.categories.archive(category)
            model.refresh()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryManager(model: ChatViewModel, bills: List<Bill>, close: () -> Unit) {
    val preferences = remember(model.revision) { model.products.preferences() }
    val products = remember(model.revision) { model.products.list() }
    var mode by remember { mutableStateOf(model.settings.interaction) }
    var capture by remember { mutableStateOf(model.settings.capturePreferences) }
    var edit by remember { mutableStateOf<Preference?>(null) }
    var product by remember { mutableStateOf<Product?>(null) }
    var clear by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("记忆与偏好") }, text = {
        LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("消费互动")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConsumptionInteraction.entries.forEach { option ->
                        FilterChip(option == mode, { mode = option; model.settings.interaction = option }, { Text(option.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(capture, { capture = it; model.settings.capturePreferences = it })
                    Text("从新对话保存明确表达的偏好")
                }
                Text("例如「请记住，我喜欢黄瓜味薯片」。购买频率仅用于商品统计。")
                TextButton(enabled = preferences.isNotEmpty(), onClick = { clear = true }) { Text("清除长期偏好") }
                HorizontalDivider()
            }
            items(preferences, key = { it.id }) { item ->
                Text(item.text)
                Text("用户明确指定 · " + item.date + "\n来源：" + item.source, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { edit = item }) { Text("编辑") }
                    TextButton(onClick = { model.products.deletePreference(item.id); model.refresh() }) { Text("删除") }
                }
            }
            item { Text("商品购买记录", style = MaterialTheme.typography.titleMedium) }
            if (products.isEmpty()) item { Text("暂无购买记录。") }
            items(products, key = { it.id }) { item ->
                TextButton(onClick = { product = item }) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(item.name)
                        Text("本月 " + item.month + " 次 · 累计 " + item.total + " 次 · 最近 " + item.latest,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
    edit?.let { item ->
        TextEditDialog("编辑偏好", item.text, { edit = null }) {
            model.products.editPreference(item.id, it)
            model.refresh()
        }
    }
    if (clear) ConfirmDialog("清除长期偏好？", "账单和购买次数仍会保留。旧聊天不会重新生成这些偏好。", { clear = false }) {
        model.products.clearPreferences()
        model.refresh()
    }
    product?.let { item ->
        ProductManager(model, item, products, bills, { product = null })
    }
}

@Composable
private fun ProductManager(model: ChatViewModel, item: Product, products: List<Product>, bills: List<Bill>, close: () -> Unit) {
    val current = products.firstOrNull { it.id == item.id } ?: item
    val associated = remember(item.id, model.revision) { bills.filter { !it.income && model.products.productFor(it.id) == item.id } }
    var rename by remember { mutableStateOf(false) }
    var merge by remember { mutableStateOf(false) }
    var reassign by remember { mutableStateOf<Bill?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text(current.name) }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            item {
                Row {
                    TextButton(onClick = { rename = true }) { Text("改名") }
                    TextButton(enabled = products.size > 1, onClick = { merge = true }) { Text("合并商品") }
                }
                Text("一笔账单算一次购买。点选账单可更改商品关联。")
            }
            items(associated, key = { it.id }) { bill ->
                TextButton(onClick = { reassign = bill }) { Text(bill.date + " · " + bill.title + " · ¥" + money(bill.cents)) }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("返回") } })
    if (rename) TextEditDialog("商品名称", current.name, { rename = false }) {
        model.products.rename(item.id, it)
        model.refresh()
    }
    if (merge) ProductPicker("合并至商品", products.filter { it.id != item.id }, { merge = false }) { target ->
        model.products.merge(item.id, target.id)
        model.refresh()
        close()
    }
    reassign?.let { bill ->
        var rememberAlias by remember { mutableStateOf(false) }
        ProductPicker("调整商品关联", products.filter { it.id != item.id }, { reassign = null }, extra = {
            Row { Checkbox(rememberAlias, { rememberAlias = it }); Text("以后的同名账单也关联到所选商品") }
            TextButton(onClick = {
                model.products.assign(bill, null, rememberAlias)
                model.refresh()
                reassign = null
            }) { Text("拆分成独立商品") }
        }) { target ->
            model.products.assign(bill, target.id, rememberAlias)
            model.refresh()
            reassign = null
        }
    }
}

@Composable
private fun ProductPicker(title: String, products: List<Product>, close: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {}, select: (Product) -> Unit
) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column {
            extra()
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(products, key = { it.id }) { item ->
                    TextButton(onClick = { select(item) }) { Text(item.name) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable
private fun TextEditDialog(title: String, initial: String, close: () -> Unit, save: (String) -> Unit) {
    var input by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        OutlinedTextField(input, { input = it; error = false }, isError = error,
            supportingText = { if (error) Text("请检查名称、长度或是否重复。") })
    }, confirmButton = {
        TextButton(enabled = input.isNotBlank(), onClick = {
            runCatching { save(input) }.onSuccess { close() }.onFailure { error = true }
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable
private fun ConfirmDialog(title: String, detail: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = { confirm(); close() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = close) { Text("取消") } })
}