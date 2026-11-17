package com.yuji.app

import java.time.LocalDate
import java.text.Normalizer

data class TransactionDraft(
    val title: String,
    val income: Boolean,
    val date: LocalDate,
    val kind: ReviewKind = ReviewKind.AMOUNT,
    val cents: Long? = null,
    val choices: List<Long> = emptyList(),
    val duplicateId: String? = null,
    val category: String = ""
)

sealed interface TransactionInput {
    data class Record(val bills: List<Bill>, val drafts: List<TransactionDraft> = emptyList()) : TransactionInput
    data class Clarify(val draft: TransactionDraft) : TransactionInput
    data class Respond(val reply: String) : TransactionInput
    data object AskAi : TransactionInput
}

object TransactionReader {
    private val blocked = Regex("可能买|可能花|考虑买|想买|想花|准备买|计划|打算|报价|多少钱|要不要|没买|没花|未买|没有买|没有花|不买|如果|假如|退款|转账|删除|撤销|修改|改成|预算|合计|总共|一共|余额|统计|多少[块笔次]|[?？]")
    private val cancel = Regex("^(取消(记账|这笔)?|不用记了?|别记了?|算了|不记了)$")
    private val confirmation = Regex("^(已经买了|买了|是的|是|对|确认)$")
    private val amountOnly = Regex("^(\\d+(?:\\.\\d+)?)[ ]*(?:元|块钱?|人民币)?$")
    private val amountAtEnd = Regex("(?<![A-Za-z0-9.\\-])(\\d+(?:\\.\\d+)?)[ ]*(?:元|块钱?|人民币)?$")
    private val datePrefix = Regex("^(昨天|前天|今天|\\d{4}-\\d{2}-\\d{2})[ ：:]*")
    private val incomeWord = Regex("收入|到账|收到|赚了|工资|奖金|兼职|发薪")
    private val prefix = Regex("^(?:(?:又|另)?买了|再记一笔|购入|购买了?|支出|消费了?|支付了?|付了|花了|收到了?|收入)[ ：:]*")
    private val suffix = Regex("[ ：:]*(?:花了|支出|消费了?|支付|收入|到账|赚了)[ ：:]*$")
    private val quantity = Regex("^(?:[一二三四五六七八九十两\\d]+)(?:包|袋|盒|瓶|罐|份|杯|个|台|件)")
    private val separators = Regex("[，,；;、\\n。]+|\\s+和\\s+|还有|另外|以及|(?<=[元块])和")
    private val standalone = Regex("午饭|晚饭|早餐|早饭|外卖|奶茶|咖啡|地铁|打车|房租|话费|工资|奖金|超市|耳机|(?i:freeclip2?)|乐事.+味")
    private val invalidAmount = Regex("[-−]\\s*\\d|\\d[,，]\\d|\\d+\\.\\d{3,}(?:元|块|$)|\\d(?:元|块)\\s*\\d")
    private val alternativeAmounts = Regex("^(.+?)(?<![A-Za-z0-9.\\-])(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块)?\\s*(?:或者|或|还是)\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块)?(?:\\s*(?:不确定|记不清(?:了)?))?$")
    private val uncertainDirection = Regex("\\s*(?:不确定|没弄清)?(?:是)?(?:收入还是支出|支出还是收入)$")
    private val uncertainOccurrence = Regex("^(?:好像|似乎)(?=买了|付了|支付了|花了|收到了?)")
    private val approximateAmount = Regex("^(.+?)\\s*(?:(?:大概|估计|约)\\s*\\d+(?:\\.\\d+)?\\s*(?:元|块)?|\\d+(?:\\.\\d+)?\\s*(?:元|块)?\\s*左右)$")
    private val uncertain = Regex("好像|似乎|大概|估计|也许|不确定|记不清|左右|约[ ]*\\d|\\d[ ]*(?:元|块)?[ ]*(?:或|还是)")

    fun read(input: String, today: LocalDate, pending: TransactionDraft?, expense: Set<String> = emptySet(), income: Set<String> = emptySet()): TransactionInput {
        val text = Normalizer.normalize(input, Normalizer.Form.NFKC).trim().trimEnd('。', '！', '!', '，', ',')
        if (cancel.matches(text)) return TransactionInput.Respond("已结束待补充的记账。")
        if (blocked.containsMatchIn(text)) return TransactionInput.Respond("这条消息没有新增账单。")
        if (invalidAmount.containsMatchIn(text.replace(Regex("\\d{4}-\\d{2}-\\d{2}"), ""))) {
            return TransactionInput.Respond("请核对金额，使用大于零且最多两位小数的数字。")
        }
        amountOnly.matchEntire(text)?.let {
            val cents = runCatching { parseCents(it.groupValues[1]) }.getOrNull()
                ?: return TransactionInput.Respond("请填写有效金额。")
            if (pending == null) return TransactionInput.Respond("这笔金额对应什么用途？请和金额一起发送。")
            return TransactionInput.Record(listOf(bill(pending, cents, if (pending.income) income else expense)))
        }
        if (confirmation.matches(text)) {
            return pending?.let { TransactionInput.Clarify(it) }
                ?: TransactionInput.Respond("请告诉我商品或用途和金额。")
        }
        val parts = separators.split(text).map(String::trim).filter(String::isNotEmpty)
        val joined = mutableListOf<String>()
        for (part in parts) {
            if (part in setOf("记不清", "记不清了", "不确定") && joined.isNotEmpty()) {
                joined[joined.lastIndex] += " " + part
            } else if (amountOnly.matches(part) && joined.isNotEmpty() && amountAtEnd.find(joined.last()) == null) {
                joined[joined.lastIndex] += " " + part
            } else joined.add(part)
        }
        val bills = mutableListOf<Bill>()
        val drafts = mutableListOf<TransactionDraft>()
        if (joined.size > 20) return TransactionInput.Respond("一次最多记录 20 笔。")
        for (part in joined) {
            val (rawBody, date) = dated(part, today) ?: return TransactionInput.Respond("请核对交易日期。")
            if (date.isAfter(today)) return TransactionInput.Respond("交易日期还没到，请在交易发生后再记账。")
            val direction = uncertainDirection.containsMatchIn(rawBody)
            val occurrence = uncertainOccurrence.containsMatchIn(rawBody)
            val body = rawBody.replace(uncertainDirection, "").replace(uncertainOccurrence, "").trim()
            if (Regex("^(明天|后天|上周|上个?月|去年|下周|下月)").containsMatchIn(body)) return TransactionInput.AskAi
            val alternatives = alternativeAmounts.matchEntire(body)
            val approximate = approximateAmount.matchEntire(body)
            val amount = amountAtEnd.find(body)
            val head = (alternatives ?: approximate)?.groupValues?.get(1)?.trim()
                ?: if (amount == null) body else body.substring(0, amount.range.first).trim()
            val title = head.replace(prefix, "").replace(suffix, "").replace(quantity, "").trim()
            if (title.isBlank() || !title.any(Char::isLetter) || title.any { it in ":：/=+" } ||
                Regex("\\s\\d|(?:元|块)\\s*\\D.*\\d").containsMatchIn(title) || title.startsWith("我")) {
                return TransactionInput.AskAi
            }
            val draft = TransactionDraft(title, incomeWord.containsMatchIn(head), date)
            if (draft.title.length > 100) return TransactionInput.Respond("单笔用途最多 100 字。")
            if (alternatives != null) {
                if (direction || occurrence || uncertain.containsMatchIn(head)) return TransactionInput.AskAi
                val choices = runCatching { alternatives.groupValues.drop(2).map(::parseCents).distinct() }.getOrNull()
                    ?: return TransactionInput.Respond("请填写有效金额。")
                drafts.add(draft.copy(choices = choices))
                continue
            }
            if (approximate != null && !occurrence && !direction && !uncertain.containsMatchIn(head)) {
                drafts.add(draft)
                continue
            }
            if (uncertain.containsMatchIn(body)) return TransactionInput.Respond("这笔还有信息需要核对。请确认交易已发生，再补充准确金额和收支方向。")
            if (amount == null) {
                if (!direction && !occurrence && (prefix.containsMatchIn(body) || standalone.matches(body) ||
                    Regex("^(?:午饭|晚饭|早餐|早饭|午餐|晚餐)吃了.+$").matches(body))) {
                    drafts.add(draft)
                    continue
                }
                return TransactionInput.AskAi
            }
            val cents = runCatching { parseCents(amount.groupValues[1]) }.getOrNull()
                ?: return TransactionInput.Respond("请填写有效金额。")
            when {
                direction && occurrence -> return TransactionInput.AskAi
                direction -> drafts.add(draft.copy(kind = ReviewKind.DIRECTION, cents = cents))
                occurrence -> drafts.add(draft.copy(kind = ReviewKind.OCCURRENCE, cents = cents))
                else -> bills.add(bill(draft, cents, if (draft.income) income else expense))
            }
        }
        return when {
            bills.isEmpty() && drafts.size == 1 -> TransactionInput.Clarify(drafts.single())
            bills.isEmpty() && drafts.isEmpty() -> TransactionInput.AskAi
            else -> TransactionInput.Record(bills, drafts)
        }
    }

    private fun dated(text: String, today: LocalDate): Pair<String, LocalDate>? {
        val match = datePrefix.find(text) ?: return text to today
        val date = when (match.groupValues[1]) {
            "昨天" -> today.minusDays(1)
            "前天" -> today.minusDays(2)
            "今天" -> today
            else -> runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
        } ?: return null
        return text.substring(match.range.last + 1).trim() to date
    }

    private fun bill(draft: TransactionDraft, cents: Long, custom: Set<String>) = Bill(
        title = draft.title, cents = cents, income = draft.income,
        category = Categories.normalize(draft.title, "", draft.income, custom), date = draft.date.toString()
    )
}
