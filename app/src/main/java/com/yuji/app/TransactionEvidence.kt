package com.yuji.app

import java.text.Normalizer
import java.time.LocalDate

internal object TransactionEvidence {
    private const val DIGITS = "一二三四五六七八九"
    private const val NUMERALS = "${DIGITS}两零〇十百千万亿点"
    private val amount = Regex("(?<![A-Za-z0-9.\\-])\\d+(?:\\.\\d+)?(?![A-Za-z0-9.])")
    private val spokenAmount = Regex(
        "(?<![$NUMERALS\\d.\\-−])([$NUMERALS]+)\\h*(?:元|块钱?)(\\h*[$NUMERALS\\d]+\\h*(?:角|毛|分)?)?(?=$|[\\s\\p{P}])(?!\\h*[$NUMERALS\\d角毛分])"
    )
    private val exactWholeYuan = Regex("[$DIGITS]?(?:十[$DIGITS]?)|[${DIGITS}两]")
    private val tentative = Regex(
        "好像|大概|大约|估计|左右|差不多|不确定|[$NUMERALS]\\h*(?:元|块钱?)?\\h*(?:或(?:者)?|还是|到|至)\\h*[$NUMERALS]"
    )

    fun verify(input: String, result: AiResult): AiResult {
        if (result.bills.isEmpty()) return result
        val current = Normalizer.normalize(input, Normalizer.Form.NFKC)
            .replace(Regex("\\d{4}-\\d{2}-\\d{2}"), "")
        val spoken = spokenAmount.findAll(current).toList()
        val numbers = amount.findAll(spokenAmount.replace(current, " "))
            .mapNotNull { runCatching { parseCents(it.value) }.getOrNull() }.toList()
        require(numbers.isNotEmpty() || spoken.isNotEmpty()) { "模型返回的金额缺少本条消息中的依据，请核对或手动添加。" }
        val amounts = numbers.toMutableList()
        if (!tentative.containsMatchIn(current)) {
            amounts += spoken.mapNotNull { match ->
                val (whole, fractional) = match.destructured
                whole.takeIf { fractional.isEmpty() && exactWholeYuan.matches(it) }?.let(::wholeYuanCents)
            }
        }
        val (confirmed, needsReview) = result.bills.partition { amounts.remove(it.cents) }
        val drafts = needsReview.map { bill ->
            TransactionDraft(bill.title, bill.income, LocalDate.parse(bill.date), category = bill.category)
        }
        return result.copy(bills = confirmed, drafts = result.drafts + drafts)
    }

    // Verify common exact amounts independently from the model's transaction extraction.
    private fun wholeYuanCents(numeral: String): Long {
        val tens = numeral.indexOf('十')
        fun digit(character: Char) = DIGITS.indexOf(character) + 1
        val value = when {
            numeral == "两" -> 2
            tens == -1 -> digit(numeral.single())
            else -> (if (tens == 0) 1 else digit(numeral.first())) * 10 +
                (if (tens == numeral.lastIndex) 0 else digit(numeral.last()))
        }
        return value * 100L
    }
}
