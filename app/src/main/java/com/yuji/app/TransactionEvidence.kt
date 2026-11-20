package com.yuji.app

import java.text.Normalizer
import java.time.LocalDate

internal object TransactionEvidence {
    private const val DIGITS = "一二三四五六七八九"
    private const val NUMERALS = "${DIGITS}两零〇十百千万亿点"
    private const val COUNT_UNITS = "箱包袋盒瓶罐份杯个台件盘碗斤"
    private val amount = Regex("(?<![A-Za-z0-9.\\-])\\d+(?:\\.\\d+)?(?![A-Za-z0-9.])")
    private val quantityUnit = Regex("\\h*[$COUNT_UNITS]")
    private val spokenAmount = Regex(
        "(?<![$NUMERALS\\d.\\-−])([$NUMERALS]+)\\h*(?:元|块钱?)(\\h*[$NUMERALS\\d]+\\h*(?:角|毛|分)?)?(?=$|[\\s\\p{P}])(?!\\h*[$NUMERALS\\d角毛分])"
    )
    private val clauseSeparator = Regex("[,;。!?\\n\\r]+")
    private val shorthandAtEnd = Regex("(?<![A-Za-z0-9$NUMERALS.\\-−])([$NUMERALS]+)$")
    private val shorthandCue = Regex(
        "[$NUMERALS\\d]+\\h*[$COUNT_UNITS]$|[：:]$|(?:花了|实付|实收|付了|收了|到账)\\h*$"
    )
    private val nonMonetaryCue = Regex(
        "(?:第|星期|周|月|年|编号|号码|型号|版本|尺寸|温度|气温|度数|年龄|身高|体重)\\h*[：:]?\\h*$"
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
        val remaining = spokenAmount.replace(current, " ")
        val shorthand = remaining.split(clauseSeparator).mapNotNull { bareSpokenAmount(it.trim()) }
        val numbers = amount.findAll(remaining)
            .filterNot { quantityUnit.matchAt(remaining, it.range.last + 1) != null }
            .mapNotNull { runCatching { parseCents(it.value) }.getOrNull() }.toList()
        require(numbers.isNotEmpty() || spoken.isNotEmpty() || shorthand.isNotEmpty()) {
            "模型返回的金额缺少本条消息中的依据，请核对或手动添加。"
        }
        val amounts = numbers.toMutableList()
        if (!tentative.containsMatchIn(current)) {
            amounts += spoken.mapNotNull { match ->
                val (whole, fractional) = match.destructured
                whole.takeIf { fractional.isEmpty() && exactWholeYuan.matches(it) }?.let(::wholeYuanCents)
            }
            amounts += shorthand.mapNotNull { (prefix, numeral) ->
                numeral.takeIf {
                    exactWholeYuan.matches(it) &&
                        (prefix.last().isWhitespace() || shorthandCue.containsMatchIn(prefix.trimEnd()))
                }?.let(::wholeYuanCents)
            }
        }
        val (confirmed, needsReview) = result.bills.partition { amounts.remove(it.cents) }
        val drafts = needsReview.map { bill ->
            TransactionDraft(bill.title, bill.income, LocalDate.parse(bill.date), category = bill.category)
        }
        return result.copy(bills = confirmed, drafts = result.drafts + drafts)
    }

    private fun bareSpokenAmount(clause: String): Pair<String, String>? {
        val match = shorthandAtEnd.find(clause) ?: return null
        val prefix = clause.substring(0, match.range.first)
        if (prefix.none(Char::isLetter) || nonMonetaryCue.containsMatchIn(prefix)) return null
        return prefix to match.groupValues[1]
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
