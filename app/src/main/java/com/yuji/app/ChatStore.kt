package com.yuji.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.time.LocalDate
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val role: String,
    val user: Boolean,
    val date: String? = LocalDate.now().toString(),
    val sentAt: Long? = System.currentTimeMillis(),
    val status: String = "done"
)
data class ChatDay(val date: String, val preview: String, val bills: Int)

internal fun SQLiteDatabase.chatSchema() {
    execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, body TEXT NOT NULL, role TEXT NOT NULL, user INTEGER NOT NULL, day TEXT, sent_at INTEGER, status TEXT NOT NULL)")
    execSQL("CREATE INDEX messages_day ON messages(day)")
    execSQL("CREATE TABLE message_bill_links(message_id TEXT NOT NULL, bill_id TEXT NOT NULL, PRIMARY KEY(message_id,bill_id))")
    execSQL("CREATE TABLE transaction_drafts(slot INTEGER PRIMARY KEY CHECK(slot=1), title TEXT NOT NULL, income INTEGER NOT NULL, day TEXT NOT NULL, source TEXT NOT NULL)")
    execSQL("CREATE TABLE migrations(name TEXT PRIMARY KEY)")
}

internal inline fun <T> SQLiteDatabase.atomic(block: () -> T): T {
    beginTransaction()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}

class ChatStore(private val ledger: Ledger) {
    private val db get() = ledger.writableDatabase

    fun migrate(history: String) = db.atomic {
        val done = db.rawQuery("SELECT 1 FROM migrations WHERE name='legacy_messages'", null).use { it.moveToFirst() }
        if (!done) {
            val rows = JSONArray(history)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                insert(ChatMessage("legacy-$i", row.getString("text"), "旧版角色", row.getBoolean("user"), null, null))
            }
            db.execSQL("INSERT INTO migrations VALUES('legacy_messages')")
        }
        db.execSQL("UPDATE messages SET status='failed' WHERE status='sending'")
    }

    fun insert(message: ChatMessage) {
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", message.id)
            put("body", message.text)
            put("role", message.role)
            put("user", message.user)
            put("day", message.date)
            put("sent_at", message.sentAt)
            put("status", message.status)
        })
    }

    fun updateStatus(id: String, status: String) {
        db.execSQL("UPDATE messages SET status=? WHERE id=?", arrayOf(status, id))
    }

    fun canRetry(id: String): Boolean = db.rawQuery(
        "SELECT id,status FROM messages WHERE user=1 ORDER BY rowid DESC LIMIT 1", null
    ).use { it.moveToFirst() && it.getString(0) == id && it.getString(1) == "failed" }

    fun draft(): TransactionDraft? = db.rawQuery("SELECT title,income,day FROM transaction_drafts", null).use {
        if (it.moveToFirst()) TransactionDraft(it.getString(0), it.getInt(1) != 0, LocalDate.parse(it.getString(2))) else null
    }

    fun finish(source: ChatMessage, bills: List<Bill>, draft: TransactionDraft?, reply: () -> ChatMessage) = db.atomic {
        val pending = db.rawQuery("SELECT status FROM messages WHERE id=?", arrayOf(source.id)).use {
            it.moveToFirst() && it.getString(0) == "sending"
        }
        if (!pending) return@atomic
        ledger.save(bills)
        for (bill in bills) {
            db.execSQL("INSERT INTO message_bill_links VALUES(?,?)", arrayOf(source.id, bill.id))
        }
        db.delete("transaction_drafts", null, null)
        if (draft != null) {
            db.execSQL("INSERT INTO transaction_drafts VALUES(1,?,?,?,?)",
                arrayOf(draft.title, if (draft.income) 1 else 0, draft.date.toString(), source.id))
        }
        insert(reply())
        updateStatus(source.id, "done")
    }

    fun deleteDay(day: String) = db.atomic {
        val where = if (day == LEGACY_DAY) "day IS NULL" else "day = ?"
        val args = if (day == LEGACY_DAY) emptyArray() else arrayOf(day)
        db.execSQL("DELETE FROM transaction_drafts WHERE source IN (SELECT id FROM messages WHERE $where)", args)
        db.execSQL("DELETE FROM message_bill_links WHERE message_id IN (SELECT id FROM messages WHERE $where)", args)
        db.delete("messages", where, args)
        Unit
    }

    fun links(messages: List<ChatMessage>): Map<String, List<String>> {
        if (messages.isEmpty()) return emptyMap()
        return messages.chunked(200).flatMap { page ->
            val placeholders = page.joinToString(",") { "?" }
            db.rawQuery("SELECT message_id,bill_id FROM message_bill_links WHERE message_id IN ($placeholders) ORDER BY rowid",
                page.map { it.id }.toTypedArray()).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
            }
        }.groupBy({ it.first }, { it.second })
    }

    fun page(day: String, limit: Int, offset: Int): List<ChatMessage> =
        query(if (day == LEGACY_DAY) "day IS NULL" else "day=?", if (day == LEGACY_DAY) emptyArray() else arrayOf(day), limit, offset)

    fun context(source: ChatMessage): List<ChatMessage> {
        val recent = query("day IS NOT NULL AND id<>? AND status='done'", arrayOf(source.id), 12, 0)
        val terms = Regex("[A-Za-z0-9]{3,}|[\\p{IsHan}]{2,}").findAll(source.text.take(300))
            .flatMap { if (it.value.first().isLetter() && it.value.first().code > 127) it.value.windowed(2).asSequence() else sequenceOf(it.value) }
            .filterNot { it in setOf("今天", "昨天", "这个", "那个", "买了", "这个月") }
            .distinct().take(6).toList()
        val older = if (terms.isEmpty()) emptyList() else {
            val filter = terms.joinToString(" OR ") { "instr(lower(body), lower(?)) > 0" }
            query("day IS NOT NULL AND id<>? AND status='done' AND ($filter)", (listOf(source.id) + terms).toTypedArray(), 6, 0)
        }
        return (recent + older).distinctBy { it.id }.sortedBy { it.sentAt }
    }

    private fun query(where: String, args: Array<String>, limit: Int, offset: Int): List<ChatMessage> =
        db.rawQuery("SELECT id,body,role,user,day,sent_at,status FROM messages WHERE $where ORDER BY rowid DESC LIMIT ? OFFSET ?",
            args + arrayOf(limit.toString(), offset.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ChatMessage(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getInt(3) != 0, cursor.getString(4), if (cursor.isNull(5)) null else cursor.getLong(5), cursor.getString(6)))
            }
        }

    fun days(): List<ChatDay> = db.rawQuery("""
        SELECT m.day, substr(m.body,1,70),
            (SELECT count(l.bill_id) FROM message_bill_links l JOIN messages s ON s.id=l.message_id WHERE s.day IS m.day)
        FROM messages m JOIN (SELECT max(rowid) AS last FROM messages GROUP BY day) d ON m.rowid=d.last
        ORDER BY m.day DESC
    """.trimIndent(), null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(ChatDay(cursor.getString(0) ?: LEGACY_DAY, cursor.getString(1), cursor.getInt(2))) }
    }

    companion object {
        const val LEGACY_DAY = "旧版对话（日期未知）"
    }
}