package com.yuji.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReceiptReaderTest {
    @Test fun paidAmountWinsOverOriginalPriceAndDiscount() {
        val result = ReceiptReader.read("""
            支付成功
            商户名称：大肥鱼便利店
            商品说明：无糖可乐
            订单金额：¥5.00
            优惠金额：¥1.00
            实 付 金 额：￥４．００
            支付时间：2026年11月18日 13:25:08
            商户单号：202611181234560000
        """.trimIndent())
        assertEquals("无糖可乐", result.title)
        assertEquals(400L, result.cents)
        assertEquals(false, result.income)
        assertEquals(LocalDate.of(2026, 11, 18), result.date)
        assertNull(result.warning)
    }

    @Test fun parsesSplitLabelsAndSignedGroupedAmount() {
        val result = ReceiptReader.read("""
            收款成功
            商户名称 大肥鱼设计室
            收款金额
            + ¥ 1, 250. 50
            收款时间
            2026/11/8 09:32
        """.trimIndent())
        assertEquals("大肥鱼设计室", result.title)
        assertEquals(125050L, result.cents)
        assertEquals(true, result.income)
        assertEquals(LocalDate.of(2026, 11, 8), result.date)
    }

    @Test fun acceptsCommonStandalonePaymentAndIgnoresOrderNumber() {
        val result = ReceiptReader.read("支出\n大肥鱼便利店\n-28.00\n订单号\n8726544190028714\n支付时间：2026-11-18")
        assertEquals("大肥鱼便利店", result.title)
        assertEquals(2800L, result.cents)
        assertEquals(false, result.income)
        assertNull(ReceiptReader.read("订单号\n8726544190028714\n优惠金额\n¥1.00").cents)
    }

    @Test fun recognizesCurrencyAmountWithLeadingColonFromSplitColumns() {
        val result = ReceiptReader.read("支付成功\n商户名称\n支付时间:\n: 便利店\n: ¥28.50\n2026-11-18")
        assertEquals(2850L, result.cents)
        assertEquals(false, result.income)
        assertNull(ReceiptReader.read("支付成功\n: 12345").cents)
        assertNull(ReceiptReader.read("优惠金额\n: ¥1.00").cents)
    }

    @Test fun leavesConflictingFieldsForReview() {
        val result = ReceiptReader.read("支付金额：¥20\n支付金额：¥32\n2026-11-18\n2026-11-17")
        assertNull(result.cents)
        assertNull(result.date)
        assertNotNull(result.warning)
        assertNull(ReceiptReader.read("+ ¥25\n支付成功").income)
        assertNull(ReceiptReader.read("合计：¥25").income)
    }

    @Test fun refusesToPrefillFailedPaymentsTransfersAndRefunds() {
        for (status in listOf("支付失败", "待支付", "转账成功", "退款成功")) {
            val result = ReceiptReader.read("$status\n实付金额：28.00")
            assertNull(status, result.cents)
            assertNull(status, result.income)
            assertNotNull(status, result.warning)
        }
    }
}
