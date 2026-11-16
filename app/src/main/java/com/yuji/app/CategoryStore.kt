package com.yuji.app

import android.database.sqlite.SQLiteDatabase
import java.util.UUID

data class CustomCategory(val id: String, val name: String, val income: Boolean)

class CategoryStore(private val ledger: Ledger) {
    fun custom(): List<CustomCategory> = ledger.readableDatabase.rawQuery(
        "SELECT id,label,income FROM categories WHERE custom=1 AND active=1 ORDER BY label", null
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(CustomCategory(cursor.getString(0), cursor.getString(1), cursor.getInt(2) != 0)) }
    }

    fun rename(category: CustomCategory, label: String) = ledger.writableDatabase.atomic {
        val db = ledger.writableDatabase
        val name = label.trim()
        require(name.isNotEmpty() && name.length <= 40)
        require(name !in Categories.options(category.income))
        db.execSQL("UPDATE categories SET label=? WHERE id=? AND custom=1 AND active=1", arrayOf(name, category.id))
        db.execSQL("UPDATE bills SET category=? WHERE category_id=?", arrayOf(name, category.id))
    }

    fun archive(category: CustomCategory) {
        ledger.writableDatabase.execSQL("UPDATE categories SET active=0 WHERE id=? AND custom=1", arrayOf(category.id))
    }
}

internal fun SQLiteDatabase.categorySchema() {
    execSQL("CREATE TABLE categories(id TEXT PRIMARY KEY, label TEXT NOT NULL, parent_id TEXT, income INTEGER NOT NULL, custom INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1, UNIQUE(label,income))")
    execSQL("ALTER TABLE bills ADD COLUMN category_id TEXT")
    for (income in listOf(false, true)) {
        for (label in Categories.options(income)) categoryId(label, income)
    }
    val rows = rawQuery("SELECT DISTINCT category,income FROM bills", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to (cursor.getInt(1) != 0)) }
    }
    for ((old, income) in rows) {
        val label = Categories.migrate(old, income)
        execSQL("UPDATE bills SET category=?,category_id=? WHERE category=? AND income=?",
            arrayOf(label, categoryId(label, income), old, income))
    }
}

internal fun SQLiteDatabase.categoryId(label: String, income: Boolean): String {
    val direction = if (income) 1 else 0
    rawQuery("SELECT id FROM categories WHERE label=? AND income=?", arrayOf(label, direction.toString())).use {
        if (it.moveToFirst()) return it.getString(0)
    }
    val parent = label.substringBefore('/', "")
    val parentId = parent.takeIf { it.isNotBlank() && it != label }?.let {
        categoryId(it, income)
    }
    val builtIn = label in Categories.options(income) || Categories.options(income).any { it.startsWith("$label/") }
    val id = if (builtIn) UUID.nameUUIDFromBytes("builtin:$direction:$label".toByteArray()).toString() else UUID.randomUUID().toString()
    execSQL("INSERT INTO categories(id,label,parent_id,income,custom) VALUES(?,?,?,?,?)",
        arrayOf(id, label, parentId, direction, if (builtIn || parent.isEmpty() && label in Categories.parents) 0 else 1))
    return id
}