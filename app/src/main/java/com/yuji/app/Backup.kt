package com.yuji.app
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object Backup {
    fun encode(bills:List<Bill>):String=JSONObject().put("version",1).put("bills",JSONArray().apply {bills.forEach {b->put(JSONObject().put("id",b.id).put("title",b.title).put("cents",b.cents).put("income",b.income).put("category",b.category).put("date",b.date))}}).toString(2)
    fun decode(raw:String):List<Bill> {
        require(raw.length<=10_000_000)
        val root=JSONObject(raw);require(root.getInt("version")==1);val a=root.getJSONArray("bills");require(a.length()<=50000)
        return (0 until a.length()).map {i -> val b=a.getJSONObject(i);val cents=b.getLong("cents");require(cents in 1..99999999999L);val id=b.getString("id");java.util.UUID.fromString(id);val title=b.getString("title");require(title.isNotBlank());Bill(id,title,cents,b.getBoolean("income"),b.getString("category"),LocalDate.parse(b.getString("date")).toString())}
    }
}
