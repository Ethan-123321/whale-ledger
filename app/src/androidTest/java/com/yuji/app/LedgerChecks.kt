package com.yuji.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

class LedgerChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        val fixtureName = "ledger-test-" + java.util.UUID.randomUUID() + ".db"
        val ledger = Ledger(targetContext, fixtureName)
        try {
            val path = targetContext.getDatabasePath(fixtureName)
            path.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE bills(id TEXT PRIMARY KEY,title TEXT NOT NULL,cents INTEGER NOT NULL,income INTEGER NOT NULL,category TEXT NOT NULL,date TEXT NOT NULL)")
                db.execSQL("INSERT INTO bills VALUES('00000000-0000-0000-0000-000000000001','旧午餐',2800,0,'自定义午餐','2026-11-15')")
                db.version = 1
            }
            val store = ChatStore(ledger)
            val products = ProductStore(ledger)
            val categories = CategoryStore(ledger)
            val day = LocalDate.now()
            val today = day.toString()
            val yesterday = day.minusDays(1).toString()
            val legacy = """[{"text":"旧消息","user":true},{"text":"旧回复","user":false}]"""
            store.migrate(legacy)
            store.migrate(legacy)
            val legacyRows = store.page(ChatStore.LEGACY_DAY, 8, 0)
            check(legacyRows.size == 2 && legacyRows.all { it.sentAt == null })
            check(store.links(legacyRows).isEmpty())
            check(ledger.list().single().category == "自定义午餐")
            check(categories.custom().any { it.name == "自定义午餐" })

            val source = ChatMessage("source", "昨天午饭28", "大肥鱼", true, today, status = "sending")
            store.insert(source)
            val amount = TransactionReader.read(source.text, day, null) as TransactionInput.Record
            val bill = amount.bills.single()
            fun reply() = ChatMessage(text = "已保存", role = "大肥鱼", user = false, date = today)
            store.finish(source, listOf(bill), emptyList(), ::reply)
            store.finish(source, listOf(bill.copy(id = "duplicate")), emptyList(), ::reply)
            check(ledger.list().size == 2 && bill.date == yesterday)
            check(store.links(listOf(source))[source.id] == listOf(bill.id))
            check(store.days().first().bills == 1)
            ledger.save(listOf(bill.copy(cents = 2900)))
            check(ledger.list().first { it.id == bill.id }.cents == 2900L)
            ledger.delete(bill.id)
            check(store.links(listOf(source))[source.id] == listOf(bill.id))
            val live = store.page(today, 200, 0)
            check(live.size == 2)
            repeat(55) { store.insert(ChatMessage(text = "分页 " + it, role = "糯米", user = false, date = today)) }
            val first = store.page(today, 40, 0)
            val second = store.page(today, 40, 40)
            check(first.size == 40 && second.size == 17 && first.intersect(second.toSet()).isEmpty())
            check(first.all { it.role == "糯米" } && second.any { it.role == "大肥鱼" })

            val draftMessage = ChatMessage(text = "买了耳机", role = "大肥鱼", user = true, date = yesterday, status = "sending")
            store.insert(draftMessage)
            val draft = TransactionDraft("耳机", false, day.minusDays(1))
            store.finish(draftMessage, emptyList(), listOf(draft), ::reply)
            val complete = ChatMessage(text = "856元", role = "大肥鱼", user = true, date = today, status = "sending")
            val pending = store.reviews(listOf(draftMessage)).getValue(draftMessage.id).single()
            val completed = TransactionReader.read(complete.text, day, pending.draft) as TransactionInput.Record
            val confirmed = completed.bills.single()
            check(store.resolveReview(pending.id, complete, confirmed, null, reply()))
            check(!store.resolveReview(pending.id, complete, confirmed.copy(id = "double-tap"), null, reply()))
            check(store.review(pending.id) == null && confirmed.date == yesterday)
            check(ledger.list().count { it.title == "耳机" } == 1)
            val matching = confirmed.copy(id = "candidate")
            val sentAt = requireNotNull(complete.sentAt)
            check(store.recentDuplicates(listOf(matching), sentAt + 1000)[matching.id] == confirmed)
            check(store.recentDuplicates(listOf(matching), sentAt + 301_000).isEmpty())
            check(store.recentDuplicates(listOf(matching.copy(date = today)), sentAt + 1000).isEmpty())
            val repeatedSource = ChatMessage(text = "昨天耳机856", role = "糯米", user = true, date = today, status = "sending")
            store.insert(repeatedSource)
            store.finish(repeatedSource, emptyList(), listOf(pending.draft.copy(
                kind = ReviewKind.DUPLICATE, cents = confirmed.cents, duplicateId = confirmed.id)), ::reply)
            val repeated = ChatStore(ledger).latestReviews().single()
            check(repeated.role == "糯米" && repeated.draft.duplicateId == confirmed.id)
            val unrelated = ChatMessage(text = "午饭15", role = "糯米", user = true, date = today, status = "sending")
            store.insert(unrelated)
            store.finish(unrelated, emptyList(), emptyList(), ::reply)
            check(store.latestReviews().isEmpty() && store.review(repeated.id) != null)
            check(store.resolveReview(repeated.id, ChatMessage(text = "保留原账单", role = "糯米", user = true), null, null, reply()))
            check(ledger.list().count { it.title == "耳机" } == 1)

            val purchases = listOf("乐事黄瓜味", "黄瓜味乐事薯片", "乐事黄瓜味", "乐事原味").map {
                Bill(title = it, cents = 800, income = false, category = "零食", date = today)
            }
            ledger.save(purchases)
            val product = products.list().first { it.name == "乐事黄瓜味" }
            check(product.total == 3 && product.month == 3)
            check(products.repeatReply(purchases[2], source, ConsumptionInteraction.OCCASIONAL)?.contains("3 次") == true)
            check(products.repeatReply(purchases[2], source, ConsumptionInteraction.OCCASIONAL) == null)
            ledger.delete(purchases[0].id)
            check(products.list().first { it.id == product.id }.total == 2)
            products.assign(purchases[1], null, false)
            check(products.list().first { it.id == product.id }.total == 1)
            val split = products.productFor(purchases[1].id)!!
            products.merge(split, product.id)
            check(products.list().first { it.id == product.id }.total == 2)
            val custom = categories.custom().first { it.name == "零食" }
            categories.rename(custom, "自定义零食")
            check(ledger.list().first { it.id == purchases[1].id }.category == "自定义零食")
            categories.archive(custom)
            check(categories.custom().none { it.id == custom.id })

            products.remember(source, "喜欢黄瓜味")
            val preference = products.preferences().single()
            products.editPreference(preference.id, "给朋友买黄瓜味")
            check(products.preferences().single().text == "给朋友买黄瓜味")
            products.deletePreference(preference.id)
            store.migrate(legacy)
            check(products.preferences().isEmpty())
            store.deleteDay(today)
            check(store.page(today, 5, 0).isEmpty() && ledger.list().any { it.title == "耳机" })

            val response = """{"bills":[{"title":"午饭","amount":"28","income":false,"category":"餐饮/正餐","date":"2026-11-16"}],"reply":"收到"}"""
            check(TransactionEvidence.verify("午饭28", Ai.decode(response)).bills.single().cents == 2800L)
            check(runCatching { TransactionEvidence.verify("聊天", Ai.decode(response)) }.isFailure)
            check(!Ai.decode(response.replace("\"reply\"", "\"conversation\":true,\"reply\"")).conversation)
            val casual = Ai.decode("""{"bills":[],"reply":"尾巴给你拍两下。","conversation":true}""")
            check(casual.conversation && casual.drafts.isEmpty())
            val chatSource = ChatMessage(text = "主要是力量，有氧太累了。", role = "大肥鱼", user = true)
            val prompt = Ai.replyMessages(AiConfig("", "", "", "大肥鱼", ""), chatSource, emptyList(),
                listOf(chatSource), "", null, leaveSpace = true)
            val chatFacts = Ai.jsonObject(prompt.getJSONObject(1).getString("content"))
            check(chatFacts.getString("current_input") == chatSource.text)
            check(chatFacts.getJSONArray("recent_or_related").length() == 0)
            check(chatFacts.getString("app_reply_direction").contains("省去附带的追问"))
            check(prompt.getJSONObject(0).getString("content").contains("角色：尾巴给你拍两下。"))
            check(runCatching { Ai.decode(response.replace("2026-11-16", "2026-02-30")) }.isFailure)
            check(runCatching { Ai.decode(response.replace("28", "28.123")) }.isFailure)
            check(runCatching { Ai.decode(response.replace("false", "\"false\"")) }.isFailure)
            val uncategorized = bill.copy(category = Categories.fallback(false))
            val pendingIncome = uncategorized.copy(income = true, category = Categories.fallback(true))
            val candidates = listOf(uncategorized, pendingIncome, bill, uncategorized)
            val suggestions = """{"0":"自定义午餐","1":"餐饮/甜点","2":"餐饮/饮料","99":"餐饮/甜点"}"""
            val classified = Ai.applyCategories(suggestions, candidates,
                Categories.expense + "自定义午餐", Categories.income)
            check(classified == listOf(uncategorized.copy(category = "自定义午餐"), pendingIncome, bill, uncategorized))
            check(Ai.decode(response.replace("午饭", "无糖可乐").replace("餐饮/正餐", "其他/待分类"))
                .bills.single().category == "餐饮/饮料")
            val snapshot = ledger.list()
            val fixed=RecurringStore(ledger)
            val fixedBill=Bill(title="固定工资",cents=800000,income=true,category="收入/工资")
            val feb=java.time.YearMonth.of(2026,2)
            fixed.save(FixedRule(fixedBill,31,feb.toString()))
            check(fixed.due(LocalDate.of(2026,2,27)).isEmpty())
            check(fixed.due(LocalDate.of(2026,2,28)).size==1)
            fixed.finish(fixed.list().single(),feb,true)
            fixed.finish(fixed.list().single(),feb,true)
            check(ledger.list().count {it.title=="固定工资"}==1)
            check(ledger.list().first {it.title=="固定工资"}.date=="2026-02-28")
            check(fixed.due(LocalDate.of(2026,2,28)).isEmpty())
            check(Backup.decode(Backup.encode(snapshot)) == snapshot)
            val legacyBackup = Backup.encode(snapshot).replace("\"version\": 2", "\"version\": 1")
            check(Backup.decode(legacyBackup) == snapshot)
            val reviewUpgrade = Ledger(targetContext, "$fixtureName-v4")
            try {
                SQLiteDatabase.openOrCreateDatabase(targetContext.getDatabasePath("$fixtureName-v4"), null).use { db ->
                    db.chatSchema()
                    db.execSQL("INSERT INTO messages VALUES('v4-source','昨天买了耳机','糯米',1,?,123,'done')", arrayOf(today))
                    db.execSQL("INSERT INTO transaction_drafts VALUES(1,'耳机',0,?,'v4-source')", arrayOf(yesterday))
                    db.version = 4
                }
                val migrated = ChatStore(reviewUpgrade).latestReviews().single()
                check(migrated.role == "糯米" && migrated.draft == TransactionDraft("耳机", false, day.minusDays(1)))
            } finally {
                reviewUpgrade.close()
                targetContext.deleteDatabase("$fixtureName-v4")
            }
            checkReceiptVision()
            checkImageReceipt()
            report.putString("stream", "\nPASS: migration, history, links, retries, drafts, products, preferences, AI responses, backup, local image OCR, vision receipt settings and decoding\n")
            finish(Activity.RESULT_OK, report)
        } catch (failure: Throwable) {
            val trace = StringWriter()
            failure.printStackTrace(PrintWriter(trace))
            report.putString("stream", "\nFAIL: " + trace.toString())
            finish(Activity.RESULT_CANCELED, report)
        } finally {
            ledger.close()
            targetContext.deleteDatabase(fixtureName)
        }
    }

    private fun checkImageReceipt() {
        val file = File.createTempFile("synthetic-receipt-", ".png", targetContext.cacheDir)
        val bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 52f
            }
            listOf("支付成功", "商户名称：便利店", "实付金额：¥28.50", "支付时间：2026-11-18")
                .forEachIndexed { i, line -> canvas.drawText(line, 70f, 120f + i * 100f, paint) }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val receipt = runBlocking { ReceiptOcr.read(targetContext, Uri.fromFile(file)) }
            check(receipt.cents == 2850L) {
                "OCR didn't extract the receipt amount: $receipt; synthetic input OCR: ${ReceiptOcr.recognize(targetContext, bitmap)}"
            }
            check(receipt.date == LocalDate.of(2026, 11, 18)) { "OCR didn't extract the receipt date: $receipt" }
            check(receipt.income == false) { "OCR didn't extract expense direction: $receipt" }
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    private fun checkReceiptVision() {
        val dataUrl = "data:image/jpeg;base64,dGVzdA=="
        val userMessage = ReceiptVision.messages(dataUrl).getJSONObject(1)
        val image = userMessage.getJSONArray("content").getJSONObject(1)
        check(userMessage.getString("role") == "user")
        check(image.getString("type") == "image_url" && image.getJSONObject("image_url").getString("url") == dataUrl)

        val today = LocalDate.of(2026, 11, 19)
        val completed = ReceiptVision.decode(
            """{"status":"completed","title":"便利店","amount":"28.50","income":false,"date":"2026-11-18"}""", today
        )
        check(completed == ReceiptFields("便利店", 2850, false, today.minusDays(1)))
        val partial = ReceiptVision.decode(
            """{"status":"completed","title":"便利店","amount":"28.555","income":"false","date":"2026-11-20"}""", today
        )
        check(partial.title == "便利店" && partial.cents == null && partial.income == null &&
            partial.date == null && partial.warning != null)
        val unsupported = ReceiptVision.decode(
            """{"status":"unsupported","title":"转账","amount":"28.50","income":true,"date":"2026-11-18"}""", today
        )
        check(unsupported.cents == null && unsupported.income == null && unsupported.warning != null)
        check(runCatching { ReceiptVision.decode("""{"amount":"28.50"}""", today) }.isFailure)

        val fixtureName = "receipt-test-" + java.util.UUID.randomUUID()
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(fixtureName, mode)
        }
        try {
            val settings = Settings(isolated)
            check(settings.receiptMode == ReceiptMode.LOCAL_OCR)
            check(runCatching { settings.useChatReceiptVision() }.isFailure)
            settings.endpoint = "https://chat.example/v1"
            settings.model = "chat-with-images"
            settings.token = "fixture-chat-key"
            settings.useChatReceiptVision()
            check(Settings(isolated).receiptMode == ReceiptMode.CHAT_MODEL)
            val chat = settings.receiptConfig(ReceiptMode.CHAT_MODEL)
            check(chat.endpoint == settings.endpoint && chat.model == settings.model && chat.token == settings.token)
            settings.model = "updated-chat-model"
            check(settings.receiptConfig(ReceiptMode.CHAT_MODEL).model == settings.model)
            settings.useReceiptVision("https://vision.example/v1", "vision", "fixture-vision-key")
            val vision = settings.receiptConfig(ReceiptMode.CUSTOM_MODEL)
            check(vision.endpoint == "https://vision.example/v1" && vision.model == "vision" && vision.token == "fixture-vision-key")
            check(runCatching { settings.useReceiptVision("https://other.example/v1", "vision", "") }.isFailure)
            settings.clearReceiptKey()
            check(settings.receiptMode == ReceiptMode.LOCAL_OCR && !settings.hasReceiptKey)
            check(settings.token == "fixture-chat-key")
        } finally {
            targetContext.deleteSharedPreferences(fixtureName)
        }
    }
}
