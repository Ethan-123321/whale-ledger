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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private val chartColors=listOf(0xFF28796C,0xFFEAAD57,0xFF708BC3,0xFFDE857B,0xFF9273AF,0xFF66A9AB,0xFF9AAB66,0xFFC18E65).map {Color(it)}

@Composable fun StatsPage(bills:List<Bill>) {
    var monthText by rememberSaveable {mutableStateOf(YearMonth.now().toString())}
    val month=YearMonth.parse(monthText)
    var category by rememberSaveable(monthText) {mutableStateOf<String?>(null)}
    var selectedDay by rememberSaveable(monthText) {mutableStateOf<Int?>(null)}
    val current=remember(bills,monthText){bills.filter {it.date.startsWith(monthText)}}
    val expenses=current.filter {!it.income}
    val spent=expenses.sumOf {it.cents}; val earned=current.filter {it.income}.sumOf {it.cents}
    val previous=bills.filter {!it.income&&it.date.startsWith(month.minusMonths(1).toString())}.sumOf {it.cents}
    val groups=expenses.groupBy {it.category}.entries.sortedByDescending {e->e.value.sumOf {it.cents}}
    val daily=(1..month.lengthOfMonth()).map {d->expenses.filter {LocalDate.parse(it.date).dayOfMonth==d}.sumOf {it.cents}}
    val max=daily.maxOrNull()?.coerceAtLeast(1) ?: 1L
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={monthText=month.minusMonths(1).toString()}){Text("‹ 上月")};Text("${month.year}年${month.monthValue}月",style=MaterialTheme.typography.titleLarge);TextButton(enabled=month<YearMonth.now(),onClick={monthText=month.plusMonths(1).toString()}){Text("下月 ›")}}}
        item {Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF174F49)),modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("本月支出",color=Color(0xFFB8D9D1));Text("¥${money(spent)}",style=MaterialTheme.typography.headlineLarge,color=Color.White)
            Text(if(previous==0L) "上月无支出，暂无环比" else "较上月${if(spent>=previous) "增加" else "减少"} ${String.format(Locale.CHINA,"%.1f",kotlin.math.abs(spent-previous)*100.0/previous)}%",color=Color(0xFFB8D9D1))
            HorizontalDivider(color=Color(0xFF47756D))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column {Text("收入",color=Color(0xFFB8D9D1));Text("¥${money(earned)}",color=Color.White)};Column {Text("结余",color=Color(0xFFB8D9D1));Text("¥${money(earned-spent)}",color=Color.White)};Column {Text("支出笔数",color=Color(0xFFB8D9D1));Text("${expenses.size} 笔",color=Color.White)}}
        }}}
        item {Text("钱花在哪里",style=MaterialTheme.typography.titleLarge)}
        if(expenses.isEmpty()) item {Text("这个月还没有支出。记一笔后，分类与趋势会显示在这里。",Modifier.padding(vertical=24.dp))}
        else {
            item {Box(Modifier.fillMaxWidth().height(210.dp),contentAlignment=Alignment.Center) {
                Canvas(Modifier.size(190.dp)) {var start=-90f;groups.forEachIndexed {i,e->val sweep=e.value.sumOf {it.cents}.toFloat()/spent*360f;drawArc(chartColors[i%chartColors.size],start,sweep,false,Offset(16.dp.toPx(),16.dp.toPx()),Size(size.width-32.dp.toPx(),size.height-32.dp.toPx()),style=Stroke(22.dp.toPx()));start+=sweep}}
                Column(horizontalAlignment=Alignment.CenterHorizontally){Text("${groups.size} 个分类",style=MaterialTheme.typography.titleLarge);Text("点选下方分类看明细",style=MaterialTheme.typography.labelSmall)}
            }}
            items(groups,key={it.key}) {entry->val index=groups.indexOf(entry);val total=entry.value.sumOf {it.cents};Row(Modifier.fillMaxWidth().background(if(category==entry.key) Color(0xFFD4EDE2) else Color.White,RoundedCornerShape(12.dp)).clickable{category=if(category==entry.key)null else entry.key;selectedDay=null}.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(12.dp).background(chartColors[index%chartColors.size],RoundedCornerShape(4.dp)));Column(Modifier.weight(1f).padding(start=10.dp)){Text(entry.key);Text("${entry.value.size} 笔 · ${String.format(Locale.CHINA,"%.1f",total*100.0/spent)}%",style=MaterialTheme.typography.labelSmall)};Text("¥${money(total)}")}}
            item {Text("每日支出",style=MaterialTheme.typography.titleLarge);Text("最高 ¥${money(max)} · 点击柱形查看当天",style=MaterialTheme.typography.labelSmall)}
            item {Row(Modifier.fillMaxWidth().height(160.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(2.dp)){daily.forEachIndexed {i,value->Box(Modifier.weight(1f).fillMaxHeight().clickable{selectedDay=i+1;category=null},contentAlignment=Alignment.BottomCenter){Box(Modifier.fillMaxWidth().fillMaxHeight(if(value==0L)0.015f else (value.toFloat()/max).coerceAtLeast(0.025f)).background(if(selectedDay==i+1)Color(0xFFEAAD57) else Color(0xFF28796C),RoundedCornerShape(topStart=3.dp,topEnd=3.dp)))}}}}
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("1日");Text("${month.lengthOfMonth()/2}日");Text("${month.lengthOfMonth()}日")}}
        }
        if(category!=null||selectedDay!=null) {
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(category ?: "${selectedDay}日 · ¥${money(daily[selectedDay!!-1])}",style=MaterialTheme.typography.titleMedium);TextButton(onClick={category=null;selectedDay=null}){Text("收起")}}}
            val filtered=expenses.filter {if(category!=null)it.category==category else LocalDate.parse(it.date).dayOfMonth==selectedDay}
            if(filtered.isEmpty())item {Text("当天没有支出")}
            items(filtered,key={"detail-${it.id}"}){b->Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Column {Text(b.title);Text(b.date,style=MaterialTheme.typography.labelSmall)};Text("−¥${money(b.cents)}")}}
        }
    }
}
