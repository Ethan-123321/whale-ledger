package com.yuji.app

import android.database.sqlite.SQLiteDatabase
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

data class Product(val id: String, val name: String, val total: Int, val month: Int, val latest: String?)
data class Preference(val id: String, val text: String, val source: String, val date: String)
enum class ConsumptionInteraction(val label: String) { OFF("关闭"), OCCASIONAL("偶尔"), ACTIVE("活跃") }

internal fun SQLiteDatabase.productSchema() {
    execSQL("CREATE TABLE products(id TEXT PRIMARY KEY, name TEXT NOT NULL)")
    execSQL("CREATE TABLE product_aliases(alias TEXT PRIMARY KEY, product_id TEXT NOT NULL)")
    execSQL("CREATE TABLE bill_items(bill_id TEXT PRIMARY KEY, product_id TEXT NOT NULL, title_key TEXT NOT NULL)")
    execSQL("CREATE INDEX bill_items_product ON bill_items(product_id)")
    execSQL("CREATE TABLE category_corrections(product_id TEXT PRIMARY KEY, category_id TEXT NOT NULL)")
    execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY, body TEXT NOT NULL, source_message TEXT NOT NULL, source_text TEXT NOT NULL, day TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)")
    execSQL("CREATE TABLE interaction_events(product_id TEXT PRIMARY KEY, day TEXT NOT NULL)")
    val existing = rawQuery("SELECT id,title FROM bills WHERE income=0", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
    }
    for ((id, title) in existing) assignProduct(id, title)
}

object ProductNames {
    // Keep the flavor and specification in the key. Only reorder known brand/product forms.
    private val lays = Regex("^(?:乐事(.+?)(?:薯片)?|(.+?味)乐事(?:薯片)?)([0-9]+(?:g|克|kg))?$")

    fun key(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val match = lays.matchEntire(normalized) ?: return normalized
        val flavor = (match.groupValues[1].ifEmpty { match.groupValues[2] }).removeSuffix("味")
        if (flavor.isBlank() || flavor == "薯片") return normalized
        return "乐事:" + flavor + ":" + match.groupValues[3].replace("克", "g")
    }
}

internal fun SQLiteDatabase.assignProduct(billId: String, title: String) {
    val key = ProductNames.key(title)
    val existingKey = rawQuery("SELECT title_key FROM bill_items WHERE bill_id=?", arrayOf(billId)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    if (existingKey == key) return
    val product = rawQuery("SELECT product_id FROM product_aliases WHERE alias=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: UUID.randomUUID().toString().also { id ->
        execSQL("INSERT INTO products VALUES(?,?)", arrayOf(id, title))
        execSQL("INSERT INTO product_aliases VALUES(?,?)", arrayOf(key, id))
    }
    execSQL("INSERT OR REPLACE INTO bill_items VALUES(?,?,?)", arrayOf(billId, product, key))
}

class ProductStore(private val ledger: Ledger) {
    private val db get() = ledger.writableDatabase

    fun list(today: LocalDate = LocalDate.now()): List<Product> = db.rawQuery("""
        SELECT p.id, p.name, count(b.id), sum(CASE WHEN substr(b.date,1,7)=? THEN 1 ELSE 0 END), max(b.date)
        FROM products p LEFT JOIN bill_items i ON i.product_id=p.id LEFT JOIN bills b ON b.id=i.bill_id AND b.income=0
        GROUP BY p.id HAVING count(b.id)>0 ORDER BY max(b.date) DESC, p.name
    """.trimIndent(), arrayOf(YearMonth.from(today).toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Product(cursor.getString(0), cursor.getString(1),
            cursor.getInt(2), cursor.getInt(3), cursor.getString(4))) }
    }

    fun productFor(billId: String): String? = db.rawQuery(
        "SELECT product_id FROM bill_items WHERE bill_id=?", arrayOf(billId)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun rename(id: String, name: String) {
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= 100)
        db.execSQL("UPDATE products SET name=? WHERE id=?", arrayOf(label, id))
    }

    fun merge(from: String, to: String) = db.atomic {
        require(from != to)
        db.execSQL("UPDATE bill_items SET product_id=? WHERE product_id=?", arrayOf(to, from))
        db.execSQL("UPDATE product_aliases SET product_id=? WHERE product_id=?", arrayOf(to, from))
        db.execSQL("INSERT OR IGNORE INTO category_corrections SELECT ?,category_id FROM category_corrections WHERE product_id=?", arrayOf(to, from))
        db.execSQL("INSERT OR REPLACE INTO interaction_events SELECT ?,max(day) FROM interaction_events WHERE product_id IN (?,?) HAVING count(*)>0", arrayOf(to, from, to))
        db.execSQL("DELETE FROM category_corrections WHERE product_id=?", arrayOf(from))
        db.execSQL("DELETE FROM interaction_events WHERE product_id=?", arrayOf(from))
        db.execSQL("DELETE FROM products WHERE id=?", arrayOf(from))
    }

    fun assign(bill: Bill, target: String?, rememberAlias: Boolean) = db.atomic {
        require(!bill.income)
        val key = ProductNames.key(bill.title)
        val id = target ?: UUID.randomUUID().toString().also {
            db.execSQL("INSERT INTO products VALUES(?,?)", arrayOf(it, bill.title))
        }
        db.execSQL("INSERT OR REPLACE INTO bill_items VALUES(?,?,?)", arrayOf(bill.id, id, key))
        if (rememberAlias) db.execSQL("INSERT OR REPLACE INTO product_aliases VALUES(?,?)", arrayOf(key, id))
    }

    fun correctCategory(bill: Bill) {
        val id = productFor(bill.id) ?: return
        db.execSQL("INSERT OR REPLACE INTO category_corrections SELECT ?,category_id FROM bills WHERE id=?", arrayOf(id, bill.id))
    }

    fun suggestCategory(bill: Bill): Bill {
        if (bill.income) return bill
        val category = db.rawQuery("""
            SELECT c.label FROM category_corrections r JOIN product_aliases a ON a.product_id=r.product_id
            JOIN categories c ON c.id=r.category_id WHERE a.alias=? AND c.income=0 AND c.active=1
        """.trimIndent(), arrayOf(ProductNames.key(bill.title))).use { if (it.moveToFirst()) it.getString(0) else null }
        return category?.let { bill.copy(category = it) } ?: bill
    }

    fun repeatReply(bill: Bill, source: ChatMessage, mode: ConsumptionInteraction): String? {
        if (mode == ConsumptionInteraction.OFF || bill.income ||
            Regex("难过|不开心|伤心|烦|讨厌|别调侃|别开玩笑|帮.*买").containsMatchIn(source.text)) return null
        val day = LocalDate.now()
        val id = productFor(bill.id) ?: return null
        val last = db.rawQuery("SELECT day FROM interaction_events WHERE product_id=?", arrayOf(id)).use {
            if (it.moveToFirst()) LocalDate.parse(it.getString(0)) else null
        }
        val interval = if (mode == ConsumptionInteraction.ACTIVE) 2L else 7L
        if (last != null && day.isBefore(last.plusDays(interval))) return null
        val earliest = YearMonth.from(day).toString()
        val (count, previous) = db.rawQuery("""
            SELECT count(CASE WHEN substr(b.date,1,7)=? THEN 1 END),
                max(CASE WHEN b.id<>? AND b.date<=? THEN b.date END)
            FROM bills b JOIN bill_items i ON i.bill_id=b.id WHERE i.product_id=? AND b.income=0
        """.trimIndent(), arrayOf(earliest, bill.id, day.toString(), id)).use {
            it.moveToFirst()
            it.getInt(0) to it.getString(1)
        }
        val threshold = if (mode == ConsumptionInteraction.ACTIVE) 2 else 3
        if (count < threshold || !bill.date.startsWith(earliest)) return null
        db.execSQL("INSERT OR REPLACE INTO interaction_events VALUES(?,?)", arrayOf(id, day.toString()))
        val fact = "这个月已经买了 " + count + " 次" + (previous?.let { "，上次是 " + it } ?: "")
        return when (source.role) {
            "大肥鱼" -> "又见面啦，本鱼认得这款！" + fact + "。"
            "糯米" -> "又是这款，喵！" + fact + "。"
            else -> "又是这款！" + fact + "。"
        }
    }

    fun preferences(): List<Preference> = db.rawQuery(
        "SELECT id,body,source_text,day FROM memories WHERE enabled=1 ORDER BY rowid DESC", null
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Preference(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))) }
    }

    fun remember(source: ChatMessage, preference: String) {
        val text = preference.trim().take(300)
        require(text.isNotBlank())
        db.execSQL("INSERT OR IGNORE INTO memories(id,body,source_message,source_text,day) VALUES(?,?,?,?,?)",
            arrayOf(source.id, text, source.id, source.text, requireNotNull(source.date)))
    }

    fun editPreference(id: String, text: String) {
        val content = text.trim().take(300)
        require(content.isNotBlank())
        db.execSQL("UPDATE memories SET body=? WHERE id=?", arrayOf(content, id))
    }

    fun deletePreference(id: String) {
        db.delete("memories", "id=?", arrayOf(id))
    }

    fun clearPreferences() {
        db.delete("memories", null, null)
    }
}