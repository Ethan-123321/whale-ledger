package com.yuji.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private val chartColors=listOf(0xFF28796C,0xFFEAAD57,0xFF708BC3,0xFFDE857B,0xFF9273AF,0xFF66A9AB,0xFF9AAB66,0xFFC18E65).map {Color(it)}

@Composable private fun SpendingLine(daily:List<Long>,labels:List<String>,elapsed:Int,kind:String,selected:Int?,onSelect:(Int)->Unit) {

    val peak=daily.take(elapsed).maxOrNull() ?: 0L
    val scale=peak.coerceAtLeast(100)
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("${kind}趋势",style=MaterialTheme.typography.titleLarge)
            Text(selected?.let {"${labels[it-1]} · ¥${money(daily[it-1])}"} ?: "点击折线查看金额",style=MaterialTheme.typography.bodySmall)
            Text("¥${money(scale)}",style=MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(160.dp).pointerInput(daily,elapsed) {
                detectTapGestures {p->onSelect((p.x/size.width*(daily.size-1)).roundToInt().coerceIn(0,elapsed-1)+1)}
            }) {
                val h=size.height-8.dp.toPx();val top=4.dp.toPx()
                fun point(i:Int)=Offset(i.toFloat()/(daily.size-1)*size.width,top+h*(1-daily[i].toFloat()/scale))
                for(i in 0..4)drawLine(Color(0xFFE6ECE8),Offset(0f,top+h*i/4),Offset(size.width,top+h*i/4),1.dp.toPx())
                val path=Path().apply {val p=point(0);moveTo(p.x,p.y);for(i in 1 until elapsed){val q=point(i);lineTo(q.x,q.y)}}
                drawPath(path,Color(0xFF28796C),style=Stroke(2.5.dp.toPx()))
                for(i in 0 until elapsed)drawCircle(if(selected==i+1)Color(0xFFEAAD57) else Color(0xFF28796C),if(selected==i+1)5.dp.toPx() else 2.5.dp.toPx(),point(i))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(labels.first());Text(labels[labels.size/2]);Text(labels.last())}
            Text(if(peak==0L) "暂无记录" else "区间最高 ¥${money(peak)}",style=MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable fun StatsPage(bills:List<Bill>) {
    var unit by rememberSaveable {mutableStateOf("月")}
    var anchorText by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    var incomeMode by rememberSaveable {mutableStateOf(false)}
    val anchor=LocalDate.parse(anchorText);val range=statsRange(anchor,unit)
    val kind=if(incomeMode) "收入" else "支出"
    var category by rememberSaveable(anchorText,unit,incomeMode) {mutableStateOf<String?>(null)}
    var selectedDay by rememberSaveable(anchorText,unit,incomeMode) {mutableStateOf<Int?>(null)}
    val today=LocalDate.now()
    val current=bills.filter {range.contains(it.date)&&!LocalDate.parse(it.date).isAfter(today)}
    val expenses=current.filter {it.income==incomeMode}
    val spent=expenses.sumOf {it.cents};val earned=current.filter {it.income}.sumOf {it.cents}
    val out=current.filter {!it.income}.sumOf {it.cents}
    val previousRange=statsRange(shiftPeriod(anchor,unit,-1),unit)
    val previous=bills.filter {it.income==incomeMode&&previousRange.contains(it.date)}.sumOf {it.cents}
    val groups=expenses.groupBy {it.category}.entries.sortedByDescending {e->e.value.sumOf {it.cents}}
    val dates=if(unit=="年") (0..11).map {range.start.plusMonths(it.toLong())} else (0 until java.time.temporal.ChronoUnit.DAYS.between(range.start,range.end).toInt()+1).map {range.start.plusDays(it.toLong())}
    val labels=dates.map {if(unit=="年") "${it.monthValue}月" else "${it.monthValue}/${it.dayOfMonth}"}
    fun bucket(b:Bill,i:Int)=if(unit=="年") b.date.startsWith(dates[i].toString().take(7)) else b.date==dates[i].toString()
    val daily=dates.indices.map {i->expenses.filter {bucket(it,i)}.sumOf {it.cents}}
    val elapsed=dates.count {!it.isAfter(today)}.coerceAtLeast(1)
    val title=when(unit){"年"->"${anchor.year}年";"周"->"${range.start} — ${range.end}";else->"${anchor.year}年${anchor.monthValue}月"}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("年","月","周").forEach {u->FilterChip(unit==u,{unit=u},{Text(u)})};Spacer(Modifier.weight(1f));FilterChip(!incomeMode,{incomeMode=false},{Text("支出")});FilterChip(incomeMode,{incomeMode=true},{Text("收入")})}}
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={anchorText=shiftPeriod(anchor,unit,-1).toString()}){Text("‹ 上$unit")};Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(enabled=range.end<today,onClick={anchorText=shiftPeriod(anchor,unit,1).toString()}){Text("下$unit ›")}}}
        item {Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF174F49)),modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("所选${unit}${kind}",color=Color(0xFFB8D9D1));Text("¥${money(spent)}",style=MaterialTheme.typography.headlineLarge,color=Color.White)
            Text(if(previous==0L) "上$unit 无$kind，暂无环比" else "较完整上$unit${if(spent>=previous) "增加" else "减少"} ${String.format(Locale.CHINA,"%.1f",kotlin.math.abs(spent-previous)*100.0/previous)}%",color=Color(0xFFB8D9D1))
            HorizontalDivider(color=Color(0xFF47756D))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column {Text("收入",color=Color(0xFFB8D9D1));Text("¥${money(earned)}",color=Color.White)};Column {Text("结余",color=Color(0xFFB8D9D1));Text("¥${money(earned-out)}",color=Color.White)};Column {Text("${kind}笔数",color=Color(0xFFB8D9D1));Text("${expenses.size} 笔",color=Color.White)}}
        }}}
        item { Text("日均${kind} ¥${money(java.math.BigDecimal(spent).divide(java.math.BigDecimal(range.elapsed(today)),0,java.math.RoundingMode.HALF_UP).toLong())} · 按 ${range.elapsed(today)} 个自然日计算",style=MaterialTheme.typography.titleMedium) }
        item { SpendingLine(daily, labels, elapsed, kind, selectedDay) { selectedDay=it;category=null } }
        item {Text(if(incomeMode) "收入来源" else "钱花在哪里",style=MaterialTheme.typography.titleLarge)}
        if(expenses.isEmpty()) item {Text("所选范围暂无${kind}记录。",Modifier.padding(vertical=24.dp))}
        else {
            item {Box(Modifier.fillMaxWidth().height(210.dp),contentAlignment=Alignment.Center) {
                Canvas(Modifier.size(190.dp)) {var start=-90f;groups.forEachIndexed {i,e->val sweep=e.value.sumOf {it.cents}.toFloat()/spent*360f;drawArc(chartColors[i%chartColors.size],start,sweep,false,Offset(16.dp.toPx(),16.dp.toPx()),Size(size.width-32.dp.toPx(),size.height-32.dp.toPx()),style=Stroke(22.dp.toPx()));start+=sweep}}
                Column(horizontalAlignment=Alignment.CenterHorizontally){Text("${groups.size} 个分类",style=MaterialTheme.typography.titleLarge);Text("点选下方分类看明细",style=MaterialTheme.typography.labelSmall)}
            }}
            items(groups,key={it.key}) {entry->val index=groups.indexOf(entry);val total=entry.value.sumOf {it.cents};Row(Modifier.fillMaxWidth().background(if(category==entry.key) Color(0xFFD4EDE2) else Color.White,RoundedCornerShape(12.dp)).clickable{category=if(category==entry.key)null else entry.key;selectedDay=null}.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(12.dp).background(chartColors[index%chartColors.size],RoundedCornerShape(4.dp)));Column(Modifier.weight(1f).padding(start=10.dp)){Text(entry.key);Text("${entry.value.size} 笔 · ${String.format(Locale.CHINA,"%.1f",total*100.0/spent)}%",style=MaterialTheme.typography.labelSmall)};Text("¥${money(total)}")}}

        }
        if(category!=null||selectedDay!=null) {
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(category ?: "${labels[selectedDay!!-1]} · ¥${money(daily[selectedDay!!-1])}",style=MaterialTheme.typography.titleMedium);TextButton(onClick={category=null;selectedDay=null}){Text("收起")}}}
            val filtered=expenses.filter {if(category!=null)it.category==category else bucket(it,selectedDay!!-1)}
            if(filtered.isEmpty())item {Text("该区间没有${kind}")}
            items(filtered,key={"detail-${it.id}"}){b->Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Column {Text(b.title);Text(b.date,style=MaterialTheme.typography.labelSmall)};Text("${if(incomeMode) "+" else "−"}¥${money(b.cents)}")}}
        }
    }
}
