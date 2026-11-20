package com.yuji.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.time.LocalDate

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val ledger = Ledger(app)
    val settings = Settings(app)
    val store = ChatStore(ledger)
    val categories = CategoryStore(ledger)
    val products = ProductStore(ledger)
    var revision by mutableStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var reviewError by mutableStateOf("")
        private set
    var receiptReading by mutableStateOf<ReceiptMode?>(null)
        private set
    var receiptDraft by mutableStateOf<ReceiptFields?>(null)
        private set
    val migrationIssue = runCatching { store.migrate(settings.history) }.exceptionOrNull() != null

    fun refresh() { revision++ }

    fun scanReceipt(uri: Uri) {
        if (receiptReading != null || receiptDraft != null) return
        val mode = settings.receiptMode
        receiptReading = mode
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                receiptDraft = when (mode) {
                    ReceiptMode.LOCAL_OCR -> ReceiptOcr.read(context, uri)
                    ReceiptMode.CHAT_MODEL, ReceiptMode.CUSTOM_MODEL -> {
                        val config = withContext(Dispatchers.IO) { runCatching { settings.receiptConfig(mode) }.getOrNull() }
                        val source = if (mode == ReceiptMode.CHAT_MODEL) "对话 AI" else "多模态识图"
                        if (config == null) ReceiptFields(warning = "请到设置检查${source}的 API 地址、模型和密钥。")
                        else ReceiptVision.read(context, uri, config)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Ai.RequestFailed) {
                val message = when (e.status) {
                    400, 415, 422 -> "服务商拒绝了识图请求，请检查接口和模型是否支持图片输入。"
                    401, 403 -> "识图接口鉴权失败，请检查 API Key 和模型权限。"
                    404 -> "找不到识图接口或模型，请检查基础地址和模型 ID。"
                    413 -> "图片超过服务商的大小限制，请裁剪后重试。"
                    429 -> "识图请求过多，请稍后重试。"
                    else -> "识图服务请求失败（HTTP ${e.status}）。"
                }
                receiptDraft = ReceiptFields(warning = "$message 可以手动填写账单。")
            } catch (_: SocketTimeoutException) {
                receiptDraft = ReceiptFields(warning = "识图请求超时，可以重试或手动填写。")
            } catch (_: Exception) {
                val warning = if (mode == ReceiptMode.LOCAL_OCR) "这张图片无法读取或识别，可以手动填写账单。"
                    else "多模态识图失败，请检查图片、网络和接口设置，或手动填写。"
                receiptDraft = ReceiptFields(warning = warning)
            } catch (_: LinkageError) {
                receiptDraft = ReceiptFields(warning = "这台设备暂时无法运行文字识别，可以手动填写账单。")
            } finally {
                receiptReading = null
            }
        }
    }

    fun dismissReceipt() {
        receiptDraft = null
    }

    fun send(text: String, retry: ChatMessage? = null) {
        if (busy || text.isBlank() || retry != null && !store.canRetry(retry.id)) return
        val sent = java.time.ZonedDateTime.now()
        val source = retry ?: ChatMessage(text = text.trim().take(2000), role = settings.role, user = true,
            date = sent.toLocalDate().toString(), sentAt = sent.toInstant().toEpochMilli(), status = "sending")
        busy = true
        reviewError = ""
        viewModelScope.launch {
            try {
                if (retry == null) {
                    val review = withContext(Dispatchers.IO) { store.latestReviews().singleOrNull() }
                    val answer = review?.draft?.answer(source.text)
                    if (review != null && answer != null) {
                        try {
                            completeReview(review, answer, source)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            reviewError = "处理失败，待确认账单还在，可以重试。"
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.IO) {
                    if (retry == null) store.insert(source) else store.updateStatus(source.id, "sending")
                }
                refresh()
                val mode = settings.interaction
                val capture = settings.capturePreferences
                val prepared = withContext(Dispatchers.IO) {
                    val custom = categories.custom()
                    val today = LocalDate.parse(requireNotNull(source.date))
                    val expense = custom.filter { !it.income }.map { it.name }.toSet()
                    val income = custom.filter { it.income }.map { it.name }.toSet()
                    val requested = explicitPreference(source.text)
                    val preference = requested?.takeIf { capture }
                    val local = when {
                        requested == null -> TransactionReader.read(source.text, today, null, expense, income)
                        preference != null -> TransactionInput.Respond("我记下了。你可以在「记忆与偏好」里修改或删除。")
                        else -> TransactionInput.Respond("偏好自动保存已关闭，可以在「记忆与偏好」里开启。")
                    }
                    Prepared(local, expense, income, preference)
                }
                val result = try {
                    when (val local = prepared.input) {
                        is TransactionInput.Record -> AiResult(local.bills, "", local.drafts)
                        is TransactionInput.Respond -> {
                            val wording = if (local.conversation) optionalAi { characterReply(source) } else null
                            AiResult(emptyList(), wording ?: local.reply)
                        }
                        is TransactionInput.Clarify -> AiResult(emptyList(), "", listOf(local.draft))
                        TransactionInput.AskAi -> {
                            val config = settings.config()
                            withContext(Dispatchers.IO) {
                                val memory = products.preferences().take(10).joinToString("\n") { it.text }
                                val history = store.context(source).filter { !it.user || explicitPreference(it.text) == null }
                                val parsed = Ai.parse(config.copy(role = source.role), source, history,
                                    null, prepared.expense, prepared.income, memory)
                                val date = LocalDate.parse(requireNotNull(source.date))
                                require(parsed.bills.none { LocalDate.parse(it.date).isAfter(date) } &&
                                    parsed.drafts.none { it.date.isAfter(date) }) { "模型返回了未来日期，请核对交易时间。" }
                                TransactionEvidence.verify(source.text, parsed)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(source, "识别失败 · " + requestError(e))
                    return@launch
                }
                var savedReply:ChatMessage?=null
                var savedBills:List<Bill> = emptyList()
                var repeatFact:String?=null
                var question: PendingReview? = null
                var hasPending = false
                try {
                    withContext(Dispatchers.IO) {
                        val classified = if (prepared.input is TransactionInput.Record &&
                            result.bills.any { it.category == Categories.fallback(it.income) }
                        ) {
                            try {
                                Ai.classify(settings.config(), result.bills, prepared.expense, prepared.income)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                result.bills
                            }
                        } else result.bills
                        val candidates = classified.map(products::suggestCategory)
                        val explicitRepeat = candidates.size == 1 &&
                            Regex("又买了|另买了|再记一笔").containsMatchIn(source.text)
                        val duplicates = if (explicitRepeat) emptyMap() else store.recentDuplicates(candidates, source.sentAt)
                        val bills = candidates.filterNot { it.id in duplicates }
                        val drafts = result.drafts + candidates.mapNotNull { bill ->
                                duplicates[bill.id]?.let { original -> duplicateDraft(bill, original) }
                            }
                        store.finish(source, bills, drafts) {
                            if (prepared.preference != null) products.remember(source, prepared.preference)
                            val repeat = bills.firstNotNullOfOrNull { products.repeatReply(it, source, mode) }
                            repeatFact=repeat
                            savedBills=bills
                            hasPending = drafts.isNotEmpty()
                            question = drafts.singleOrNull()?.let { PendingReview(source.id, source.role, it) }
                            val confirmation = if (bills.isEmpty()) "" else "已入账 ${bills.size} 笔。"
                            val next = when {
                                question != null -> reviewQuestion(requireNotNull(question))
                                drafts.isNotEmpty() -> "还有 ${drafts.size} 笔需要核对，请逐笔确认下面的内容。"
                                bills.isEmpty() -> result.reply
                                else -> ""
                            }
                            ChatMessage(text = listOf(confirmation, next).filter(String::isNotBlank).joinToString("\n"),
                                role = source.role, user = false, date = source.date).also {savedReply=it}
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    fail(source, "保存失败 · 账单尚未保存，请重试。")
                    return@launch
                }
                refresh()
                // Reply generation cannot change the committed transaction or enable transaction retry.
                val reply = savedReply
                val pending = question
                if (reply != null && pending != null) {
                    optionalAi {
                        val wording = Ai.phraseQuestion(settings.config().copy(role = source.role), reviewQuestion(pending))
                        if (wording != null) {
                            val prefix = if (savedBills.isEmpty()) "" else "已入账 ${savedBills.size} 笔。\n"
                            store.updateReply(reply.id, prefix + wording)
                        }
                    }
                } else if (reply != null && !hasPending && (savedBills.isNotEmpty() || result.conversation)) {
                    respondInCharacter(source, reply, savedBills, repeatFact, prepared.input is TransactionInput.Record)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                runCatching { fail(source, "处理失败 · 请重试或手动记账。") }
            } finally {
                busy = false
                refresh()
            }
        }
    }

    fun answerReview(id: String, text: String) {
        if (busy) return
        busy = true
        reviewError = ""
        viewModelScope.launch {
            try {
                val review = withContext(Dispatchers.IO) { store.review(id) } ?: return@launch
                val answer = review.draft.answer(text)
                if (answer == null) {
                    reviewError = "请填写有效金额，最多两位小数。"
                    return@launch
                }
                completeReview(review, answer, ChatMessage(text = text, role = review.role, user = true))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reviewError = "处理失败，待确认账单还在，可以重试。"
            } finally {
                busy = false
                refresh()
            }
        }
    }

    private suspend fun completeReview(review: PendingReview, answer: ReviewAnswer, source: ChatMessage) {
        val draft = review.draft
        val confirmation = source.copy(role = review.role)
        val (reply, saved) = withContext(Dispatchers.IO) {
            val bill = if (answer is ReviewAnswer.Save) {
                val custom = categories.custom().filter { it.income == answer.income }.map { it.name }.toSet()
                products.suggestCategory(Bill(title = draft.title, cents = answer.cents, income = answer.income,
                    category = Categories.normalize(draft.title, draft.category, answer.income, custom),
                    date = draft.date.toString()))
            } else null
            val original = if (bill != null && draft.kind != ReviewKind.DUPLICATE) {
                store.recentDuplicates(listOf(bill), source.sentAt)[bill.id]
            } else null
            val next = if (bill != null && original != null) duplicateDraft(bill, original) else null
            val saved = bill.takeIf { next == null }
            val text = when {
                next != null -> reviewQuestion(PendingReview(confirmation.id, review.role, next))
                saved != null -> "已入账 1 笔。"
                draft.kind == ReviewKind.DUPLICATE -> "这次未新增账单。"
                else -> "已取消这笔待确认账单。"
            }
            val reply = ChatMessage(text = text, role = review.role, user = false, date = source.date)
            if (store.resolveReview(review.id, confirmation, saved, next, reply)) reply to saved
            else null to null
        }
        refresh()
        if (reply != null && saved != null) respondInCharacter(confirmation, reply, listOf(saved), null, simpleEntry = true)
    }

    private fun duplicateDraft(bill: Bill, original: Bill) =
        TransactionDraft(bill.title, bill.income, LocalDate.parse(bill.date), ReviewKind.DUPLICATE, bill.cents,
            duplicateId = original.id, category = bill.category)

    private suspend fun respondInCharacter(
        source: ChatMessage, reply: ChatMessage, bills: List<Bill>, repeat: String?, simpleEntry: Boolean
    ) =
        optionalAi {
            val text = characterReply(source, bills, repeat, simpleEntry, reply.id)
            if (text != null) store.updateReply(reply.id, text)
        }

    private fun characterReply(
        source: ChatMessage, bills: List<Bill> = emptyList(), repeat: String? = null,
        simpleEntry: Boolean = false, excludedReplyId: String? = null
    ): String? {
        val config = settings.config().copy(role = source.role)
        if (config.endpoint.isBlank() || config.model.isBlank() || config.token.isBlank()) return null
        val history = store.context(source).filter { it.id != excludedReplyId && (!it.user || explicitPreference(it.text) == null) }
        val memory = products.preferences().take(10).joinToString("\n") { it.text }
        return Ai.respond(config, source, bills, history, memory, repeat, simpleEntry)
    }

    private suspend fun <T> optionalAi(block: () -> T): T? =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun fail(source: ChatMessage, message: String) = withContext(Dispatchers.IO) {
        ledger.writableDatabase.atomic {
            store.insert(ChatMessage(text = message, role = source.role, user = false, status = "error"))
            store.updateStatus(source.id, "failed")
        }
    }

    private fun requestError(error: Exception) = when (error) {
        is java.net.SocketTimeoutException -> "连接超时，请重试。"
        is java.net.UnknownHostException, is java.io.IOException -> "无法连接服务商，请检查网络和地址。"
        is IllegalStateException, is IllegalArgumentException -> error.message ?: "请检查输入和 AI 配置。"
        else -> "模型响应格式无效，请重试或手动记账。"
    }

    private fun explicitPreference(text: String): String? = Regex(
        "^(?:请记住|记住|以后叫我|我最喜欢|我不喜欢|我偏好)[：:，, ]*(.+)$"
    ).matchEntire(text.trim())?.let { match ->
        if (text.startsWith("请记住") || text.startsWith("记住")) match.groupValues[1] else match.value
    }

    private data class Prepared(val input: TransactionInput,
        val expense: Set<String>, val income: Set<String>, val preference: String?)
}
