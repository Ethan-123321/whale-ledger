package com.yuji.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val migrationIssue = runCatching { store.migrate(settings.history) }.exceptionOrNull() != null

    fun refresh() { revision++ }

    fun send(text: String, retry: ChatMessage? = null) {
        if (busy || text.isBlank() || retry != null && !store.canRetry(retry.id)) return
        val sent = java.time.ZonedDateTime.now()
        val source = retry ?: ChatMessage(text = text.trim().take(2000), role = settings.role, user = true,
            date = sent.toLocalDate().toString(), sentAt = sent.toInstant().toEpochMilli(), status = "sending")
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (retry == null) store.insert(source) else store.updateStatus(source.id, "sending")
                }
                refresh()
                val mode = settings.interaction
                val capture = settings.capturePreferences
                val prepared = withContext(Dispatchers.IO) {
                    val custom = categories.custom()
                    val draft = store.draft()
                    val today = LocalDate.parse(requireNotNull(source.date))
                    val expense = custom.filter { !it.income }.map { it.name }.toSet()
                    val income = custom.filter { it.income }.map { it.name }.toSet()
                    val requested = explicitPreference(source.text)
                    val preference = requested?.takeIf { capture }
                    val local = when {
                        requested == null -> TransactionReader.read(source.text, today, draft, expense, income)
                        preference != null -> TransactionInput.Respond("我记下了。你可以在「记忆与偏好」里修改或删除。")
                        else -> TransactionInput.Respond("偏好自动保存已关闭，可以在「记忆与偏好」里开启。")
                    }
                    Prepared(local, draft, expense, income, preference)
                }
                val result = try {
                    when (val local = prepared.input) {
                        is TransactionInput.Record -> AiResult(local.bills, "")
                        is TransactionInput.Respond -> AiResult(emptyList(), local.reply)
                        is TransactionInput.Clarify -> AiResult(emptyList(), local.draft.title + " 的金额是多少元？", local.draft)
                        TransactionInput.AskAi -> {
                            val config = settings.config()
                            withContext(Dispatchers.IO) {
                                val memory = products.preferences().take(10).joinToString("\n") { it.text }
                                val history = store.context(source).filter { !it.user || explicitPreference(it.text) == null }
                                val parsed = Ai.parse(config.copy(role = source.role), source, history,
                                    prepared.draft, prepared.expense, prepared.income, memory)
                                val date = LocalDate.parse(requireNotNull(source.date))
                                require(parsed.bills.none { LocalDate.parse(it.date).isAfter(date) } &&
                                    parsed.draft?.date?.isAfter(date) != true) { "模型返回了未来日期，请核对交易时间。" }
                                parsed.copy(bills = TransactionEvidence.validate(source.text, parsed.bills))
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
                try {
                    withContext(Dispatchers.IO) {
                        val bills = result.bills.map(products::suggestCategory)
                        store.finish(source, bills, result.draft) {
                            if (prepared.preference != null) products.remember(source, prepared.preference)
                            val repeat = bills.firstNotNullOfOrNull { products.repeatReply(it, source, mode) }
                            repeatFact=repeat
                            savedBills=bills
                            ChatMessage(text = if(bills.isEmpty())result.reply else "已入账 ${bills.size} 笔。", role = source.role, user = false).also {savedReply=it}
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
                if(savedBills.isNotEmpty() && savedReply!=null) {
                    try {
                        val config=settings.config().copy(role=source.role)
                        if(config.endpoint.isNotBlank()&&config.model.isNotBlank()&&config.token.isNotBlank()) {
                            withContext(Dispatchers.IO) {
                                val history=store.context(source).filter {it.id!=savedReply!!.id}
                                val memory=products.preferences().take(10).joinToString("\n"){it.text}
                                val reply=Ai.respond(config,source,savedBills,history,memory,repeatFact)
                                store.updateReply(savedReply!!.id,reply)
                            }
                        }
                    } catch(e:CancellationException) {throw e}
                    catch(_:Exception) { /* Keep the already persisted factual confirmation. */ }
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

    private data class Prepared(val input: TransactionInput, val draft: TransactionDraft?,
        val expense: Set<String>, val income: Set<String>, val preference: String?)
}

object TransactionEvidence {
    private val amount = Regex("(?<![A-Za-z0-9.\\-])\\d+(?:\\.\\d+)?(?![A-Za-z0-9.])")
    fun validate(input: String, bills: List<Bill>): List<Bill> {
        val current = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC)
            .replace(Regex("\\d{4}-\\d{2}-\\d{2}"), "")
        val amounts = amount.findAll(current).mapNotNull { runCatching { parseCents(it.value) }.getOrNull() }.toMutableList()
        require(bills.all { amounts.remove(it.cents) }) { "模型返回的金额缺少本条消息中的依据，请核对或手动添加。" }
        return bills
    }
}
