package com.yuji.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class FixedRule(val bill:Bill,val day:Int,val start:String,val enabled:Boolean=true)
class RecurringStore(private val ledger:Ledger) {
    private val db get()=ledger.writableDatabase
    fun list():List<FixedRule> = db.rawQuery("SELECT id,title,cents,income,category,day,start,enabled FROM recurring ORDER BY rowid",null).use {c->buildList {while(c.moveToNext())add(FixedRule(Bill(c.getString(0),c.getString(1),c.getLong(2),c.getInt(3)==1,c.getString(4)),c.getInt(5),c.getString(6),c.getInt(7)==1))}}
    fun save(r:FixedRule) {require(r.day in 1..31);YearMonth.parse(r.start);db.execSQL("INSERT OR REPLACE INTO recurring VALUES(?,?,?,?,?,?,?,?)",arrayOf(r.bill.id,r.bill.title,r.bill.cents,if(r.bill.income)1 else 0,r.bill.category,r.day,r.start,if(r.enabled)1 else 0))}
    fun due(today:LocalDate=LocalDate.now()):List<Pair<FixedRule,YearMonth>> = buildList {
        list().filter {it.enabled}.forEach {r->var m=YearMonth.parse(r.start);val end=YearMonth.from(today)
            while(m<=end) {val done=db.rawQuery("SELECT 1 FROM recurring_done WHERE rule=? AND month=?",arrayOf(r.bill.id,m.toString())).use{it.moveToFirst()};if(!done && !m.atDay(minOf(r.day,m.lengthOfMonth())).isAfter(today))add(r to m);m=m.plusMonths(1)}
        }
    }
    fun finish(r:FixedRule,m:YearMonth,record:Boolean) = db.atomic {
        val done=db.rawQuery("SELECT 1 FROM recurring_done WHERE rule=? AND month=?",arrayOf(r.bill.id,m.toString())).use{it.moveToFirst()}
        if(!done) {if(record)ledger.save(listOf(r.bill.copy(id=UUID.nameUUIDFromBytes((r.bill.id+":"+m).toByteArray()).toString(),date=m.atDay(minOf(r.day,m.lengthOfMonth())).toString())));db.execSQL("INSERT INTO recurring_done VALUES(?,?)",arrayOf(r.bill.id,m.toString()))}
    }
}

@Composable fun RecurringPage(model:ChatViewModel,close:()->Unit) {
    val store=remember {RecurringStore(model.ledger)}
    val rules=remember(model.revision){store.list()};val due=remember(model.revision){store.due()}
    var edit by remember {mutableStateOf<FixedRule?>(null)};var adding by remember {mutableStateOf(false)}
    var schedule by remember {mutableStateOf<Bill?>(null)}
    var day by remember {mutableStateOf("1")};var start by remember {mutableStateOf(YearMonth.now().toString())};var error by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=close,title={Text("固定收支")},text={LazyColumn(Modifier.heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Text("每月到期后确认入账，不自动扣款。31号遇短月按月末处理。规则与待确认记录保存在本机，账本备份暂不包含规则。")}
        if(due.isNotEmpty())item {Text("待确认 ${due.size} 笔",style=MaterialTheme.typography.titleMedium)}
        items(due){(r,m)->Column {Text("$m · ${r.bill.title} ${if(r.bill.income) "+" else "−"}¥${money(r.bill.cents)}");Row {TextButton(onClick={store.finish(r,m,true);model.refresh()}){Text("确认入账")};TextButton(onClick={store.finish(r,m,false);model.refresh()}){Text("跳过本月")}}}}
        item {Text("每月规则",style=MaterialTheme.typography.titleMedium)}
        items(rules,key={it.bill.id}){r->Column {Text("${r.bill.title} · 每月${r.day}日 · ¥${money(r.bill.cents)}");Row {Switch(r.enabled,{store.save(r.copy(enabled=it));model.refresh()});TextButton(onClick={edit=r;adding=true}){Text("修改")}}}}
    }},confirmButton={TextButton(onClick={edit=null;adding=true}){Text("新增固定收支")}},dismissButton={TextButton(onClick=close){Text("关闭")}})
    if(adding)BillEditor(edit?.bill,model.categories.custom(),{adding=false},{b->schedule=b;day=(edit?.day ?: 1).toString();start=edit?.start ?: YearMonth.now().toString();adding=false;error=""})
    if(schedule!=null)AlertDialog(onDismissRequest={schedule=null},title={Text("每月计划")},text={Column {
        OutlinedTextField(day,{day=it},label={Text("每月几号（1—31）")});OutlinedTextField(start,{start=it},label={Text("起始月份 YYYY-MM")});Text("修改规则会影响尚未确认的月份，已入账账单不变。");if(error.isNotBlank())Text(error)
    }},confirmButton={TextButton(onClick={try {val m=YearMonth.parse(start);require(m>=YearMonth.now().minusYears(5)&&m<=YearMonth.now().plusYears(5));store.save(FixedRule(schedule!!,day.toInt(),m.toString(),edit?.enabled ?: true));model.refresh();schedule=null}catch(e:Exception){error="请检查日期，起始月份需在前后5年内"}}){Text("保存计划")}},dismissButton={TextButton(onClick={schedule=null}){Text("取消")}})
}
