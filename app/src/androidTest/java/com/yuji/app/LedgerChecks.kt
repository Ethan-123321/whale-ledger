package com.yuji.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDate

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
            store.finish(source, listOf(bill), null, ::reply)
            store.finish(source, listOf(bill.copy(id = "duplicate")), null, ::reply)
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
            store.finish(draftMessage, emptyList(), draft, ::reply)
            val complete = ChatMessage(text = "856元", role = "大肥鱼", user = true, date = today, status = "sending")
            store.insert(complete)
            val completed = TransactionReader.read(complete.text, day, store.draft()) as TransactionInput.Record
            store.finish(complete, completed.bills, null, ::reply)
            check(store.draft() == null && completed.bills.single().date == yesterday)
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
            check(TransactionEvidence.validate("午饭28", Ai.decode(response).bills).single().cents == 2800L)
            check(runCatching { TransactionEvidence.validate("聊天", Ai.decode(response).bills) }.isFailure)
            check(runCatching { Ai.decode(response.replace("2026-11-16", "2026-02-30")) }.isFailure)
            check(runCatching { Ai.decode(response.replace("28", "28.123")) }.isFailure)
            check(runCatching { Ai.decode(response.replace("false", "\"false\"")) }.isFailure)
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
            report.putString("stream", "\nPASS: migration, history, links, retries, drafts, products, preferences, AI responses, backup\n")
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
}
