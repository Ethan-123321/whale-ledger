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
