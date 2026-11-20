package com.yuji.app

import org.junit.Assert.*
import org.junit.Test

class TransactionEvidenceTest {
    private val duck = Bill(title = "酱鸭", cents = 700, income = false, category = "餐饮/正餐", date = "2026-11-18")
    private val ai = AiResult(listOf(duck), "这笔是酱鸭。")

    @Test fun verifiesAnExplicitSpokenAmountExtractedByTheApi() {
        assertEquals(listOf(duck), TransactionEvidence.verify("加了份酱鸭，七块", ai).bills)
        assertEquals(listOf(duck), TransactionEvidence.verify("加了份酱鸭，7块", ai).bills)
    }

    @Test fun verifiesUnitlessSpokenPriceAfterQuantity() {
        val bread = Bill(title = "面包", cents = 3500, income = false, category = "餐饮/甜点", date = "2026-11-21")
        val parsed = AiResult(listOf(bread), "")
        for (text in listOf("面包一箱三十五", "面包1箱三十五", "面包 三十五", "面包实付三十五")) {
            val checked = TransactionEvidence.verify(text, parsed)
            assertEquals(text, listOf(bread), checked.bills)
            assertTrue(text, checked.drafts.isEmpty())
        }
        val modelUsedQuantity = parsed.copy(bills = listOf(bread.copy(cents = 100)))
        val checked = TransactionEvidence.verify("面包1箱三十五", modelUsedQuantity)
        assertTrue(checked.bills.isEmpty())
        assertEquals("面包", checked.drafts.single().title)
    }

    @Test fun ambiguousUnitlessPriceDoesNotBecomeAnAutomaticBill() {
        val proposed = ai.copy(bills = listOf(duck.copy(cents = 3500)))
        for (text in listOf("酱鸭三十五", "酱鸭一盒三十四或三十五", "酱鸭一盒大概三十五")) {
            val checked = TransactionEvidence.verify(text, proposed)
            assertTrue(text, checked.bills.isEmpty())
            assertEquals(text, "酱鸭", checked.drafts.single().title)
        }
        assertTrue(runCatching { TransactionEvidence.verify("产品编号三十五", proposed) }.isFailure)
        assertTrue(runCatching { TransactionEvidence.verify("气温：三十五", proposed) }.isFailure)
    }

    @Test fun mismatchOrUncertainAmountsNeedReview() {
        for (text in listOf("酱鸭七块五", "酱鸭七八块", "酱鸭大概七块", "酱鸭六块还是七块", "酱鸭六块")) {
            val checked = TransactionEvidence.verify(text, ai)
            assertTrue(text, checked.bills.isEmpty())
            assertEquals(text, "酱鸭", checked.drafts.single().title)
            assertTrue(text, checked.drafts.single().choices.isEmpty())
        }
    }

    @Test fun aSingleAmountCannotSupportMultipleRows() {
        val repeated = duck.copy(id = "extra")
        val result = TransactionEvidence.verify("酱鸭七块", ai.copy(bills = listOf(duck, repeated)))
        assertEquals(listOf(duck), result.bills)
        assertEquals(1, result.drafts.size)
    }
}
