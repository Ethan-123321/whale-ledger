package com.yuji.app
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class StatsRange(val start:LocalDate,val end:LocalDate) {
    fun elapsed(today:LocalDate)= (ChronoUnit.DAYS.between(start,minOf(end,today))+1).coerceAtLeast(1).toInt()
    fun contains(date:String)=LocalDate.parse(date).let {it>=start&&it<=end}
}
fun statsRange(anchor:LocalDate,unit:String):StatsRange {
    val start=when(unit){"年"->anchor.withDayOfYear(1);"周"->anchor.minusDays(anchor.dayOfWeek.value-1L);else->anchor.withDayOfMonth(1)}
    val end=when(unit){"年"->start.plusYears(1).minusDays(1);"周"->start.plusDays(6);else->start.plusMonths(1).minusDays(1)}
    return StatsRange(start,end)
}
fun shiftPeriod(date:LocalDate,unit:String,steps:Long)=when(unit){"年"->date.plusYears(steps);"周"->date.plusWeeks(steps);else->date.plusMonths(steps)}
