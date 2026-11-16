package com.yuji.app
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
class MoneyTest {
    @Test fun exactAmounts() {assertEquals(29L,parseCents("0.29"));assertEquals(10010L,parseCents("100.10"));assertEquals("0.29",money(29))}
    @Test fun rejectsInvalidMoney() {listOf("0","-1","0.001","NaN","100000000000").forEach {v->assertThrows(Exception::class.java){parseCents(v)}}}
}
