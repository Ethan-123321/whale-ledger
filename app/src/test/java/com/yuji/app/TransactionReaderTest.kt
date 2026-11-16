package com.yuji.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TransactionReaderTest {
    private val today = LocalDate.of(2026, 11, 16)
    private fun read(text: String, draft: TransactionDraft? = null) = TransactionReader.read(text, today, draft)
    private fun recorded(text: String) = (read(text) as TransactionInput.Record).bills

    @Test fun apiAndModelNumber() {
        val api = recorded("deepseekapi 支出50").single()
        assertEquals(5000L, api.cents)
        assertEquals("数字服务/AI与API", api.category)
        val headphones = recorded("freeclip2 856元").single()
        assertEquals("freeclip2", headphones.title)
        assertEquals(85600L, headphones.cents)
        assertFalse(headphones.income)
    }

    @Test fun plannedOrInvalidTransactionsAreBlocked() {
        for (text in listOf("想买freeclip2，856元", "还没买freeclip2 856元", "午饭-28元", "午饭28.555元",
            "午饭0元", "freeclip2多少钱", "房租1,200元")) {
            assertTrue(text, read(text) is TransactionInput.Respond)
        }
        assertFalse(read("华为FreeClip2") is TransactionInput.Record)
    }

    @Test fun distinctDaysAndMultipleAmounts() {
        val bills = recorded("昨天午饭28，今天地铁4元")
        assertEquals(listOf(2800L, 400L), bills.map { it.cents })
        assertEquals(listOf("2026-11-15", "2026-11-16"), bills.map { it.date })
        assertTrue(recorded("工资到账5000").single().income)
    }

    @Test fun draftPreservesDateAndNewTransactionTakesPrecedence() {
        val draft = (read("昨天买了耳机") as TransactionInput.Clarify).draft
        assertTrue(read("是的", draft) is TransactionInput.Clarify)
        val confirmed = TransactionReader.read("856元", today.plusDays(1), draft) as TransactionInput.Record
        assertEquals("2026-11-15", confirmed.bills.single().date)
        assertEquals("耳机", confirmed.bills.single().title)
        assertEquals("午饭", (read("午饭28", draft) as TransactionInput.Record).bills.single().title)
        assertTrue(read("别记了", draft) is TransactionInput.Respond)
        assertTrue(read("856元") is TransactionInput.Respond)
    }

    @Test fun productAliasesPreserveFlavorAndSize() {
        assertEquals(ProductNames.key("乐事黄瓜味"), ProductNames.key("黄瓜味乐事薯片"))
        assertNotEquals(ProductNames.key("乐事黄瓜味"), ProductNames.key("乐事原味"))
        assertNotEquals(ProductNames.key("乐事黄瓜味50g"), ProductNames.key("乐事黄瓜味90g"))
        assertEquals(ProductNames.key("乐事黄瓜味薯片50g"), ProductNames.key("黄瓜味乐事薯片50克"))
    }
}