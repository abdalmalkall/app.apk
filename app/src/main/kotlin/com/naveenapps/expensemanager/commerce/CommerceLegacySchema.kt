package com.naveenapps.expensemanager.commerce

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Verify archived legacy SQLite against the shipped Room schemas, not the imported CREATE SQL. */
internal object CommerceLegacySchema {
    private data class Column(val affinity:String,val notNull:Boolean,val default:String?)
    private data class Index(val unique:Boolean,val partial:Boolean,val origin:String)

    fun validate(context:Context,db:SQLiteDatabase,includeRows:Boolean=true) {
        require(db.version in 2..13) { "إصدار قاعدة النظام القديم غير مدعوم للاستعادة؛ احتفظ بالأصل للمراجعة" }
        val schema=JSONObject(context.assets.open("commerce-legacy-schemas/${db.version}.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        schema.getJSONArray("entities").objects().forEach { entity ->
            val table=entity.getString("tableName")
            val columns=CommerceStore.query(db,"PRAGMA table_info(`$table`)") { c -> c.getString(c.getColumnIndexOrThrow("name")) to Column(c.getString(c.getColumnIndexOrThrow("type")),c.getLong(c.getColumnIndexOrThrow("notnull"))==1L,c.getColumnIndexOrThrow("dflt_value").let { if(c.isNull(it)) null else normalizeDefault(c.getString(it)) }) }.toMap()
            val fields=entity.getJSONArray("fields").objects()
            require(columns.keys==fields.map { it.getString("columnName") }.toSet()) { "مخطط قديم غير متوافق: $table" }
            fields.forEach { field ->
                val name=field.getString("columnName"); val actual=columns.getValue(name)
                val expectedDefault=if(field.has("defaultValue") && !field.isNull("defaultValue")) normalizeDefault(field.getString("defaultValue")) else null
                require(actual.affinity==field.getString("affinity") && actual.notNull==field.optBoolean("notNull",false) && actual.default==expectedDefault) { "نوع عمود أو قيمته الافتراضية غير متوافق: $table.$name" }
            }
            val primary=CommerceStore.query(db,"PRAGMA table_info(`$table`)") { it.getLong(it.getColumnIndexOrThrow("pk")) to it.getString(it.getColumnIndexOrThrow("name")) }.filter { it.first>0 }.sortedBy { it.first }.map { it.second }
            val expected=entity.getJSONObject("primaryKey").getJSONArray("columnNames").let { a -> (0 until a.length()).map { a.getString(it) } }
            require(primary==expected) { "مفتاح قديم غير صحيح" }
            val foreign=CommerceStore.query(db,"PRAGMA foreign_key_list(`$table`)") { c -> listOf("table","from","to","on_delete","on_update").map { c.getString(c.getColumnIndexOrThrow(it)) } }.toSet()
            val expectedForeign=(entity.optJSONArray("foreignKeys") ?: org.json.JSONArray()).objects().flatMap { f -> val from=f.getJSONArray("columns"); val to=f.getJSONArray("referencedColumns"); (0 until from.length()).map { n -> listOf(f.getString("table"),from.getString(n),to.getString(n),f.getString("onDelete"),f.getString("onUpdate")) } }.toSet()
            require(foreign==expectedForeign) { "علاقات مخطط قديم غير متوافقة" }
            val indices=CommerceStore.query(db,"PRAGMA index_list(`$table`)") { c -> c.getString(c.getColumnIndexOrThrow("name")) to Index(c.getLong(c.getColumnIndexOrThrow("unique"))==1L,c.getLong(c.getColumnIndexOrThrow("partial"))==1L,c.getString(c.getColumnIndexOrThrow("origin"))) }.toMap()
            val expectedIndices=(entity.optJSONArray("indices") ?: JSONArray()).objects()
            // Extra indexes can change future inserts even when the imported Room identity hash matches.
            require(indices.filterValues { it.origin!="pk" }.keys==expectedIndices.map { it.getString("name") }.toSet()) { "فهارس إضافية أو مفقودة في قاعدة النظام القديم: $table" }
            expectedIndices.forEach { index ->
                val name=index.getString("name")
                val actual=indices.getValue(name)
                val keys=CommerceStore.query(db,"PRAGMA index_xinfo(`$name`)") { c -> if(c.getLong(c.getColumnIndexOrThrow("key"))==1L) listOf(c.getString(c.getColumnIndexOrThrow("name")),if(c.getLong(c.getColumnIndexOrThrow("desc"))==1L) "DESC" else "ASC",c.getString(c.getColumnIndexOrThrow("coll"))) else null }.filterNotNull()
                val expectedNames=index.getJSONArray("columnNames").let { a -> (0 until a.length()).map { a.getString(it) } }
                val orders=index.optJSONArray("orders")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().ifEmpty { List(expectedNames.size) { "ASC" } }
                require(!actual.partial && actual.unique==index.optBoolean("unique",false) && keys==expectedNames.mapIndexed { n,column -> listOf(column,orders[n],"BINARY") }) { "فهرس قديم غير متوافق: $name" }
            }
            if(includeRows) validateRows(db,table,fields)
        }
        if(includeRows) {
            val hashes=CommerceStore.query(db,"SELECT identity_hash FROM room_master_table WHERE id=42") { it.getString(0) }
            require(hashes==listOf(schema.getString("identityHash"))) { "هوية مخطط Room القديم غير متوافقة" }
        }
    }

    private fun validateRows(db:SQLiteDatabase,table:String,fields:List<JSONObject>) {
        db.rawQuery("SELECT * FROM `$table`",null).use { c -> while(c.moveToNext()) fields.forEach { field ->
            val name=field.getString("columnName"); val index=c.getColumnIndexOrThrow(name); val type=c.getType(index)
            val valid=if(type==Cursor.FIELD_TYPE_NULL) !field.optBoolean("notNull",false) else when(field.getString("affinity")) {
                "INTEGER" -> type==Cursor.FIELD_TYPE_INTEGER
                "REAL" -> (type==Cursor.FIELD_TYPE_FLOAT || type==Cursor.FIELD_TYPE_INTEGER) && c.getDouble(index).isFinite()
                "TEXT" -> type==Cursor.FIELD_TYPE_STRING
                "BLOB" -> type==Cursor.FIELD_TYPE_BLOB
                else -> false
            }
            require(valid) { "قيمة قديمة ذات نوع غير صحيح: $table.$name؛ لا يمكن تحويلها ضمنيًا" }
        } }
    }

    private fun normalizeDefault(value:String):String {
        var result=value.trim()
        while(result.startsWith('(') && result.endsWith(')')) result=result.substring(1,result.lastIndex).trim()
        return if(result.equals("null",ignoreCase=true)) "NULL" else result
    }
}
