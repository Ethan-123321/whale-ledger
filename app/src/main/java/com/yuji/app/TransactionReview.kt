package com.yuji.app

import java.text.Normalizer
import java.util.UUID

enum class ReviewKind { AMOUNT, DIRECTION, OCCURRENCE, DUPLICATE }

data class PendingReview(
    val sourceId: String,
    val role: String,
    val draft: TransactionDraft,
    val id: String = UUID.randomUUID().toString()
)

sealed interface ReviewAnswer {
    data class Save(val cents: Long, val income: Boolean) : ReviewAnswer
    data object Dismiss : ReviewAnswer
}

internal fun TransactionDraft.answer(input: String): ReviewAnswer? {
    val text = Normalizer.normalize(input, Normalizer.Form.NFKC).trim().trimEnd('。', '！', '!', '，', ',')
    if (text in setOf("取消", "取消记账", "取消这笔", "不用记了", "别记了", "算了", "不记了")) return ReviewAnswer.Dismiss
    return when (kind) {
        ReviewKind.AMOUNT -> {
            val amount = text.removeSuffix("人民币").removeSuffix("块钱").removeSuffix("块").removeSuffix("元").trim()
            if (!Regex("\\d+(?:\\.\\d{1,2})?").matches(amount)) return null
            val value = runCatching { parseCents(amount) }.getOrNull() ?: return null
            if (choices.isEmpty() || value in choices) ReviewAnswer.Save(value, income) else null
        }
        ReviewKind.DIRECTION -> when (text) {
            "收入", "是收入" -> ReviewAnswer.Save(requireNotNull(cents), true)
            "支出", "是支出" -> ReviewAnswer.Save(requireNotNull(cents), false)
            else -> null
        }
        ReviewKind.OCCURRENCE -> when (text) {
            "已经发生", "已经买了", "确实花了" -> ReviewAnswer.Save(requireNotNull(cents), income)
            "还没发生", "还没买", "没买" -> ReviewAnswer.Dismiss
            else -> null
        }
        ReviewKind.DUPLICATE -> when (text) {
            "再记一笔", "另买了一次", "是另一笔" -> ReviewAnswer.Save(requireNotNull(cents), income)
            "保留原账单", "重复发了", "是同一笔", "重复了" -> ReviewAnswer.Dismiss
            else -> null
        }
    }
}

internal fun reviewQuestion(review: PendingReview): String {
    val draft = review.draft
    val title = "「${draft.title}」"
    val question = when (draft.kind) {
        ReviewKind.AMOUNT -> if (draft.choices.isEmpty()) {
            "$title ${if (draft.income) "收了" else "花了"}多少元？"
        } else {
            val choices = draft.choices.joinToString("还是") { money(it) + " 元" }
            "$title 的金额是 $choices？"
        }
        ReviewKind.DIRECTION -> "$title 的 ${money(requireNotNull(draft.cents))} 元是收入还是支出？"
        ReviewKind.OCCURRENCE -> "$title 这笔 ${money(requireNotNull(draft.cents))} 元的交易已经发生了吗？"
        ReviewKind.DUPLICATE -> "刚刚记过一笔同金额的 $title。这次是另一笔，还是同一笔发了两次？"
    }
    return when (review.role) {
        "糯米" -> "我先帮你留着这笔。$question"
        "小福子" -> "陛下，小福子跟您核对一下。$question"
        "Claude·大小姐" -> "容我确认一下。$question"
        "阿算盘" -> "待确认。$question"
        "大肥鱼" -> "本鱼先问清楚这笔。$question"
        else -> question
    }
}
