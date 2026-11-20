package com.yuji.app

import java.text.Normalizer
import java.time.LocalDate

data class ReceiptFields(
    val title: String? = null,
    val cents: Long? = null,
    val income: Boolean? = null,
    val date: LocalDate? = null,
    val warning: String? = null
)

internal object ReceiptReader {
    private val paid = Regex("实付(?:金额|款)?|实收(?:金额|款)?|实际支付|实际到账|付款金额|支付金额|收款金额|到账金额|扣款金额")
    private val total = Regex("共计|合计|总计|交易金额|金额")
    private val adjustment = Regex("(?:优惠|折扣|减免|原价|退款|余额|手续费|找零|待付|应付|应收|已省)(?:金额|款)?")
    private val unsupported = Regex("待支付|等待付款|支付失败|付款失败|交易关闭|已取消|已退款|退款金额|退款成功|转账成功|转账金额")
    private val amount = Regex("^([+\u2212-]?)\\s*([¥￥]?)\\s*([+\u2212-]?)\\s*((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)\\s*(元|人民币)?$")
    private val timeLabel = Regex("交易时间|支付时间|付款时间|收款时间|扣款时间|入账时间|完成时间|交易日期|支付日期")
    private val dateText = Regex("(?<!\\d)(\\d{4})[-/.年]\\s*(\\d{1,2})[-/.月]\\s*(\\d{1,2})(?:日)?(?!\\d)")
    private val titleLabels = listOf(Regex("商品名称|商品说明|商品|订单标题"), Regex("商户全称|商户名称|店铺名称|商户|收款商家|收款方|交易对方"))
    private val noise = Regex("订单号|流水号|交易单号|订单编号|手机号|\\d{6,}|^[*•·]+|^[¥￥]")
    private val screenLabels = Regex("账单详情|交易详情|支付详情|当前状态|付款方式|支付方式|收款成功|支付成功|付款成功|^微信支付$|^支付宝$|^收入$|^支出$")

    fun read(text: String): ReceiptFields {
        val lines = Normalizer.normalize(text, Normalizer.Form.NFKC).lineSequence()
            .map { it.trim().replace(Regex("(?<=\\p{IsHan})\\h+(?=\\p{IsHan})"), "") }
            .filter(String::isNotBlank).toList()
        val labeledTitle = titleLabels.asSequence().map { label ->
            valuesAfter(lines, label, allowAdjacentName = true).filter { value ->
                value.length in 1..100 && value.any(Char::isLetter) && !noise.containsMatchIn(value) &&
                    !timeLabel.containsMatchIn(value) && !paid.containsMatchIn(value)
            }.distinct().singleOrNull()
        }.firstOrNull { it != null }
        val labeledDates = valuesAfter(lines, timeLabel).flatMap(::dates).distinct()
        val date = if (labeledDates.isNotEmpty()) labeledDates.singleOrNull()
            else lines.flatMap(::dates).distinct().singleOrNull()
        if (lines.any { unsupported.containsMatchIn(it) }) {
            return ReceiptFields(title = labeledTitle, date = date, warning = "图片可能是退款、转账或未完成的交易，请核实后手动填写。")
        }

        val settled = valuesAfter(lines, paid).mapNotNull(::amountValue)
        val totals = valuesAfter(lines, total).mapNotNull(::amountValue)
        val marked = lines.mapIndexedNotNull { index, line ->
            if (index > 0 && adjustment.matches(lines[index - 1].trimEnd(':', '：'))) return@mapIndexedNotNull null
            amountValue(line.removePrefix(":").trimStart())?.takeIf { it.marked }?.let { index to it }
        }
        val candidates = settled.ifEmpty { totals.ifEmpty { marked.map { it.second } } }
        val cents = candidates.map { it.cents }.distinct().singleOrNull()
        val title = labeledTitle ?: marked.singleOrNull()?.takeIf { it.second.cents == cents }?.let { (index, _) ->
            lines.getOrNull(index - 1)?.takeIf { line ->
                line.length in 2..50 && line.any(Char::isLetter) && ':' !in line &&
                    !screenLabels.containsMatchIn(line) && !noise.containsMatchIn(line) &&
                    !adjustment.matches(line) && !timeLabel.containsMatchIn(line) && !paid.containsMatchIn(line) &&
                    !total.matches(line) && titleLabels.none { it.matches(line) }
            }
        }
        val signals = buildList {
            candidates.mapNotNullTo(this) { it.income }
            if (lines.any { Regex("^(?:收入|收款成功|已收款)$|^(?:实收|收款金额|到账金额|实际到账)").containsMatchIn(it) }) add(true)
            if (lines.any { Regex("^(?:支出|付款成功|支付成功|已支付)$|^(?:实付|付款金额|支付金额|扣款金额|实际支付)").containsMatchIn(it) }) add(false)
        }
        val warning = when {
            candidates.map { it.cents }.distinct().size > 1 -> "图片里有多个可能的金额，请填写这一笔的实际金额。"
            cents == null -> "没找到可确认的实付或实收金额，请手动填写。"
            else -> null
        }
        return ReceiptFields(title, cents, signals.distinct().singleOrNull(), date, warning)
    }

    private fun valuesAfter(lines: List<String>, label: Regex, allowAdjacentName: Boolean = false): List<String> =
        lines.mapIndexedNotNull { index, line ->
            val match = label.find(line)?.takeIf { it.range.first == 0 } ?: return@mapIndexedNotNull null
            val rest = line.substring(match.range.last + 1)
            if (!allowAdjacentName && rest.isNotEmpty() && rest.first() !in " :：¥￥+-\u2212" && !rest.first().isDigit()) {
                return@mapIndexedNotNull null
            }
            rest.trim().trimStart(':', '：').trim().ifBlank { lines.getOrNull(index + 1).orEmpty() }
        }

    private fun dates(text: String): List<LocalDate> = dateText.findAll(text).mapNotNull { match ->
        val (_, year, month, day) = match.groupValues
        runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
    }.toList()

    private fun amountValue(value: String): Amount? {
        val cleaned = value.trim().replace(Regex("(?<=\\d)\\h*([.,])\\h*(?=\\d)"), "$1")
        val match = amount.matchEntire(cleaned) ?: return null
        val (_, signBefore, currency, signAfter, digits) = match.groupValues
        val suffix = match.groupValues[5]
        if (signBefore.isNotEmpty() && signAfter.isNotEmpty()) return null
        val sign = signBefore.ifEmpty { signAfter }
        val cents = runCatching { parseCents(digits.replace(",", "")) }.getOrNull() ?: return null
        val income = when (sign) {
            "+" -> true
            "-", "−" -> false
            else -> null
        }
        return Amount(cents, income, currency.isNotEmpty() || suffix.isNotEmpty() || sign.isNotEmpty())
    }

    private data class Amount(val cents: Long, val income: Boolean?, val marked: Boolean)
}
