package com.yuji.app
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
class StatsRangeTest {
    @Test fun weekCrossesYear() {val r=statsRange(LocalDate.of(2026,1,1),"周");assertEquals("2025-12-29",r.start.toString());assertEquals("2026-01-04",r.end.toString());assertEquals(4,r.elapsed(LocalDate.of(2026,1,1)))}
    @Test fun leapMonthAndCurrent() {val r=statsRange(LocalDate.of(2024,2,10),"月");assertEquals(10,r.elapsed(LocalDate.of(2024,2,10)));assertEquals(29,r.elapsed(LocalDate.of(2026,1,1)))}
    @Test fun year() {assertEquals(366,statsRange(LocalDate.of(2024,8,1),"年").elapsed(LocalDate.of(2026,1,1)))}
}
