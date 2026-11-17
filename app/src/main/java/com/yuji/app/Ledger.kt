package com.yuji.app

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class Bill(val id: String = UUID.randomUUID().toString(), val title: String, val cents: Long, val income: Boolean, val category: String, val date: String = LocalDate.now().toString())
fun parseCents(value: String): Long {
    val cents = BigDecimal(value.trim()).movePointRight(2).longValueExact()
    require(cents in 1..99999999999L) { "金额必须大于零且最多两位小数" }
    return cents
}
fun money(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString()
class Ledger(context: Context, databaseName: String = "ledger.db") : SQLiteOpenHelper(context, databaseName, null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bills(id TEXT PRIMARY KEY, title TEXT NOT NULL, cents INTEGER NOT NULL CHECK(cents>0), income INTEGER NOT NULL, category TEXT NOT NULL, date TEXT NOT NULL)")
        onUpgrade(db, 1, 5)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.categorySchema()
            db.chatSchema()
        }
        if (oldVersion < 3) db.productSchema()
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE recurring(id TEXT PRIMARY KEY,title TEXT,cents INTEGER,income INTEGER,category TEXT,day INTEGER,start TEXT,enabled INTEGER)")
            db.execSQL("CREATE TABLE recurring_done(rule TEXT,month TEXT,PRIMARY KEY(rule,month))")
        }
        if (oldVersion < 5) db.reviewSchema()
    }
    fun list(): List<Bill> = readableDatabase.rawQuery("SELECT id,title,cents,income,category,date FROM bills ORDER BY date DESC,rowid DESC", null).use { c -> buildList { while(c.moveToNext()) add(Bill(c.getString(0),c.getString(1),c.getLong(2),c.getInt(3)==1,c.getString(4),c.getString(5))) } }
    fun save(bills: List<Bill>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            bills.forEach { b ->
                require(b.cents > 0 && b.title.isNotBlank()); LocalDate.parse(b.date)
                val v = ContentValues().apply { put("id",b.id); put("title",b.title); put("cents",b.cents); put("income",if(b.income) 1 else 0); put("category",b.category); put("category_id", db.categoryId(b.category, b.income)); put("date",b.date) }
                db.insertWithOnConflict("bills",null,v,SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
                if (!b.income) db.assignProduct(b.id, b.title)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun delete(id: String) { writableDatabase.delete("bills","id=?",arrayOf(id)) }
}
