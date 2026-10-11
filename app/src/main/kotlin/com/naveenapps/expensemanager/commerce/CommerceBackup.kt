package com.naveenapps.expensemanager.commerce

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

internal class CommerceRestoreRestartException(message: String, cause: Exception) : IllegalArgumentException(message, cause)

/** Version 2 full, portable package. Logical SQLite snapshots are made under read/write locks,
 * so committed WAL rows are included without copying a live database file. */
internal class CommerceBackup(private val context: Context, private val store: CommerceStore) {
    private val journal = File(context.filesDir, "commerce-restore-journal.json")
    private val legacyPath get() = context.getDatabasePath("expense_manager_database.db")
    private val managed = listOf("category_images", "account_images", "transaction_attachments", "datastore")
    internal var failureHook: (() -> Unit)? = null

    fun create(ledger: JSONObject): JSONObject {
        require(!journal.exists()) { "يلزم إكمال التراجع عن الاستعادة السابقة عند إعادة تشغيل التطبيق" }
        val legacy = if (legacyPath.isFile) captureLegacy(context) else null
        val files = linkedMapOf<String, ByteArray>()
        val result=JSONObject(ledger.toString()).put("payload",JSONObject().put("legacy",legacy ?: JSONObject.NULL).put("files",JSONArray()))
        var encodedBytes=result.toString().toByteArray().size.toLong()
        require(encodedBytes<=MAX_BYTES) { "النسخة الكاملة أكبر من 50 ميغابايت" }
        managed.forEach { folder ->
            val root = File(context.filesDir,folder)
            require(!root.exists() || root.canonicalPath==File(context.filesDir.canonicalFile,folder).path) { "مجلد بيانات مرتبط بمسار آخر؛ لا يمكن نسخه بأمان" }
            if (root.exists()) root.walkTopDown().filter { it.isFile }.forEach { file ->
                require(files.size<10000) { "ملفات كثيرة جدًا" }
                require(file.canonicalPath.startsWith(root.canonicalPath+File.separator)) { "ملف خارج مجلد البيانات" }
                val path = "$folder/${file.relativeTo(root).invariantSeparatorsPath}"
                safePath(path)
                val length=file.length()
                val overhead=JSONObject().put("path",path).put("size",length).put("sha256","0".repeat(64)).put("data","").toString().toByteArray().size+(if(files.isEmpty()) 0 else 1)
                require(length<=MAX_BYTES && encodedBytes+overhead+4*((length+2)/3)<=MAX_BYTES) { "النسخة الكاملة أكبر من 50 ميغابايت" }
                val rawBudget=((MAX_BYTES-encodedBytes-overhead)/4*3).coerceAtMost(MAX_BYTES.toLong()).toInt()
                val bytes=file.inputStream().use { it.readLimited(rawBudget) }
                // Reserve the encoded representation before retaining another attachment in memory.
                encodedBytes+=overhead+4*((bytes.size.toLong()+2)/3)
                require(encodedBytes<=MAX_BYTES) { "النسخة الكاملة أكبر من 50 ميغابايت" }
                files[path]=bytes
            }
        }
        legacy?.getJSONObject("tables")?.let { tables -> remapPaths(tables) { old ->
            if (old.startsWith("content:")) error("مرفق قديم مرتبط بمزود خارجي؛ انسخه إلى مجلد التطبيق قبل النسخ الاحتياطي")
            val file=File(old)
            require(file.isFile && file.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator)) { "مرفق قديم مفقود أو خارج بيانات التطبيق" }
            val relative=file.relativeTo(context.filesDir).invariantSeparatorsPath
            safePath(relative); require(files.containsKey(relative)) { "مرفق خارج المجلدات المدعومة" }
            "backup-file:$relative"
        } }
        // Atomic DataStore files and immutable attachments are checked again: no mixed versions silently.
        files.forEach { (path, bytes) -> require(File(context.filesDir,path).inputStream().use { sha(it,bytes.size) }==sha(bytes)) { "تغير ملف أثناء النسخ؛ أعد المحاولة" } }
        val entries=JSONArray(files.map { (path,bytes) -> JSONObject().put("path",path).put("size",bytes.size).put("sha256",sha(bytes)).put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)) })
        result.getJSONObject("payload").put("files",entries)
        require(result.toString().toByteArray().size<=MAX_BYTES) { "النسخة الكاملة أكبر من 50 ميغابايت" }
        return result
    }

    /** Stage and validate *everything* before producing the durable rollback journal or changing live data. */
    fun restore(text: String): Boolean {
        require(text.toByteArray().size<=MAX_BYTES) { "النسخة أكبر من 50 ميغابايت" }
        require(!journal.exists()) { "هناك استعادة غير مكتملة؛ أغلق التطبيق وأعد فتحه لإكمال التراجع" }
        val root=try { JSONObject(text) } catch(e:Exception) { throw IllegalArgumentException("ملف النسخة غير صالح",e) }
        val data=store.validateLedger(root)
        val payload=root.optJSONObject("payload")
        require(root.getInt("version")==1 || payload!=null) { "النسخة الكاملة لا تحتوي ملفاتها" }
        val stage=File(context.cacheDir,"commerce-restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val stagedLegacy=if(payload!=null) stagePayload(payload,stage) else null
            val safety=store.writeSafety("before-restore")
            val needsRestart=payload!=null && (legacyPath.exists() || File(context.filesDir,"datastore").exists() || stagedLegacy!=null || payload.getJSONArray("files").objects().any { it.getString("path").startsWith("datastore/") })
            writeDurable(journal,JSONObject().put("safety",safety.name).toString().toByteArray())
            try {
                if(payload!=null) closeLegacyRoom()
                store.replaceLedger(data) {
                    if(payload!=null) applyExternal(stage,stagedLegacy)
                    failureHook?.invoke()
                }
                check(journal.delete()) { "تعذر إنهاء سجل الاستعادة؛ أعد تشغيل التطبيق" }
            } catch(e:Exception) {
                try { recover() } catch(rollback:Exception) { e.addSuppressed(rollback); throw CommerceRestoreRestartException("تعذر إكمال الاستعادة أو التراجع؛ نسخة الأمان محفوظة، أعد تشغيل التطبيق",e) }
                val message="فشلت الاستعادة؛ أُعيدت البيانات والملفات السابقة"
                if(needsRestart) throw CommerceRestoreRestartException("$message؛ أغلق التطبيق وأعد فتحه",e)
                throw IllegalArgumentException(message,e)
            }
            return needsRestart
        } finally { stage.deleteRecursively() }
    }

    /** Invoked before Koin/Room/DataStore at application startup; never clears the journal on failure. */
    fun recover() {
        if(!journal.exists()) return
        val pending=JSONObject(journal.inputStream().use { it.readLimited(4096).toString(Charsets.UTF_8) })
        require(pending.keys().asSequence().toSet()==setOf("safety")) { "سجل الاستعادة غير صالح؛ نسخة الأمان لم تُحذف" }
        val name=pending.getString("safety")
        val root=JSONObject(store.safetyBackupFile(name).inputStream().use { it.readLimited(MAX_BYTES).toString(Charsets.UTF_8) })
        val stage=File(context.cacheDir,"commerce-rollback-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val payload=root.getJSONObject("payload")
            val oldLegacy=stagePayload(payload,stage)
            closeLegacyRoom()
            // This is a local exact rollback snapshot, including pre-existing records that may need review.
            store.replaceLedger(root.getJSONObject("tables")) { applyExternal(stage,oldLegacy) }
            check(journal.delete()) { "تعذر إنهاء التراجع؛ لم تُحذف نسخة الأمان" }
        } finally { stage.deleteRecursively() }
    }

    private fun stagePayload(payload: JSONObject,stage:File): File? {
        require(payload.keys().asSequence().toSet()==setOf("legacy","files")) { "بنية ملفات النسخة غير صحيحة" }
        val files=payload.getJSONArray("files").objects(); require(files.size<=10000) { "ملفات كثيرة جدًا" }
        val names=hashSetOf<String>(); var total=0L
        files.forEach { record -> val path=record.getString("path"); safePath(path); require(names.add(path)) { "مسار مكرر في النسخة" } }
        names.forEach { path ->
            var prefix=path.substringBeforeLast('/',"")
            while(prefix.isNotEmpty()) { require(prefix !in names) { "ملف يتعارض مع مجلد مرفقات في النسخة" }; prefix=prefix.substringBeforeLast('/',"") }
        }
        files.forEach { record ->
            require(record.keys().asSequence().toSet()==setOf("path","size","sha256","data"))
            val path=record.getString("path")
            val size=record.get("size"); require(size is Int || size is Long) { "حجم ملف غير صالح" }
            require((size as Number).toLong() in 0L..MAX_BYTES.toLong() && total+size.toLong()<=MAX_BYTES) { "حجم ملفات النسخة غير صالح" }
            val bytes=Base64.decode(record.getString("data"),Base64.DEFAULT)
            total+=bytes.size; require(total<=MAX_BYTES && bytes.size.toLong()==size.toLong() && sha(bytes)==record.getString("sha256")) { "ملف تالف أو حجم غير صحيح" }
            if(path.startsWith("datastore/")) {
                try { androidx.datastore.preferences.PreferencesProto.PreferenceMap.parseFrom(bytes) }
                catch(e:Exception) { throw IllegalArgumentException("ملف إعدادات تالف",e) }
            }
            writeDurable(File(stage,"files/$path"),bytes)
        }
        if(payload.isNull("legacy")) return null
        val legacy=JSONObject(payload.getJSONObject("legacy").toString())
        remapPaths(legacy.getJSONObject("tables")) { ref ->
            require(ref.startsWith("backup-file:")) { "مرفق قديم ذو مسار غير قابل للنقل" }
            val path=ref.removePrefix("backup-file:"); safePath(path)
            require(path in names) { "مرفق مرتبط غير موجود في النسخة" }
            File(context.filesDir,path).absolutePath
        }
        val file=File(stage,"legacy.db")
        buildLegacy(file,legacy,context)
        return file
    }

    private fun applyExternal(stage:File,oldLegacy:File?) {
        managed.forEach { folder ->
            val live=File(context.filesDir,folder)
            deleteWithoutFollowingLinks(live)
            val source=File(stage,"files/$folder")
            if(source.exists()) source.walkTopDown().filter { it.isFile }.forEach { file ->
                file.inputStream().use { writeDurable(File(live,file.relativeTo(source).path),it) }
            }
        }
        listOf("","-wal","-shm","-journal").forEach { suffix -> val file=File(legacyPath.path+suffix); if(file.exists()) check(file.delete()) { "تعذر استبدال قاعدة النظام القديم" } }
        if(oldLegacy!=null) { legacyPath.parentFile!!.mkdirs(); oldLegacy.inputStream().use { writeDurable(legacyPath,it) } }
    }

    companion object {
        const val MAX_BYTES=50_000_000
        private val fileRoots=setOf("category_images","account_images","transaction_attachments","datastore")
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun sha(input:InputStream,limit:Int):String {
            val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(8192); var total=0L
            while(true) { val read=input.read(buffer); if(read<0) break; total+=read; require(total<=limit) { "تغير ملف أثناء النسخ؛ أعد المحاولة" }; digest.update(buffer,0,read) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun safePath(path:String) {
            require(path.isNotBlank() && !path.contains('\u0000') && !path.contains('\\') && !path.contains(':') && !path.startsWith('/') && path.split('/').size>=2 && path.split('/').all { it.isNotBlank() && it!="." && it!=".." } && path.substringBefore('/') in fileRoots) { "مسار غير آمن في النسخة" }
            if(path.startsWith("datastore/")) require(path.matches(Regex("datastore/[A-Za-z0-9_.-]+\\.preferences_pb"))) { "ملف إعدادات غير صالح" }
        }
        fun writeDurable(file:File,bytes:ByteArray) {
            bytes.inputStream().use { writeDurable(file,it) }
        }
        private fun writeDurable(file:File,input:InputStream) {
            file.parentFile!!.mkdirs()
            val temp=File(file.parentFile,"${file.name}.${UUID.randomUUID()}.tmp")
            try { FileOutputStream(temp).use { input.copyTo(it); it.flush(); it.fd.sync() }; check(temp.renameTo(file)) { "تعذر حفظ الملف" } } finally { temp.delete() }
        }
        private fun deleteWithoutFollowingLinks(file:File) {
            if(Files.isSymbolicLink(file.toPath())) { check(file.delete()) { "تعذر إزالة رابط ملف بيانات" }; return }
            if(!file.exists()) return
            if(file.isDirectory) (file.listFiles() ?: error("تعذر قراءة مجلد البيانات")).forEach { deleteWithoutFollowingLinks(it) }
            check(file.delete()) { "تعذر استبدال ملف بيانات" }
        }
        private fun closeLegacyRoom() { GlobalContext.getOrNull()?.getOrNull<ExpenseManagerDatabase>()?.close() }
        fun captureLegacy(context:Context): JSONObject {
            val path=context.getDatabasePath("expense_manager_database.db")
            SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { db ->
                db.beginTransactionNonExclusive()
                try {
                    val schema=JSONArray(); val data=JSONObject()
                    var encodedBytes=128L
                    db.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index') AND sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, name",null).use { c ->
                        while(c.moveToNext()) {
                            val name=c.getString(1); require(name.matches(Regex("[A-Za-z0-9_]+"))) { "اسم جدول قديم غير مدعوم" }
                            require(schema.length()<500) { "جداول قديمة كثيرة جدًا" }
                            val entry=JSONObject().put("type",c.getString(0)).put("name",name).put("sql",c.getString(2))
                            encodedBytes+=entry.toString().toByteArray().size+name.length+8
                            require(encodedBytes<=MAX_BYTES) { "النسخة الكاملة أكبر من 50 ميغابايت" }; schema.put(entry)
                        }
                    }
                    schema.objects().filter { it.getString("type")=="table" }.forEach { entry ->
                        val rows=JSONArray()
                        db.rawQuery("SELECT * FROM \"${entry.getString("name")}\"",null).use { c -> while(c.moveToNext()) {
                            val row=JSONObject().apply { c.columnNames.forEachIndexed { n,key -> put(key,when(c.getType(n)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(n)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(n).toString()
                            Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob",Base64.encodeToString(c.getBlob(n),Base64.NO_WRAP))
                            else -> c.getString(n)
                            }) } }
                            encodedBytes+=row.toString().toByteArray().size+1
                            require(encodedBytes<=MAX_BYTES) { "النسخة الكاملة أكبر من 50 ميغابايت" }; rows.put(row)
                        } }
                        data.put(entry.getString("name"),rows)
                    }
                    return JSONObject().put("version",db.version).put("schema",schema).put("tables",data)
                } finally { db.endTransaction() }
            }
        }
        private fun remapPaths(tables:JSONObject,transform:(String)->String) {
            listOf("transaction" to "image_path","transaction_attachment" to "image_path","category" to "custom_image_path","account" to "custom_image_path").forEach { (table,column) ->
                tables.optJSONArray(table)?.objects()?.forEach { row -> if(row.has(column) && !row.isNull(column) && row.getString(column).isNotBlank()) row.put(column,transform(row.getString(column))) }
            }
        }
        private fun buildLegacy(file:File,legacy:JSONObject,context:Context) {
            require(legacy.keys().asSequence().toSet()==setOf("version","schema","tables"))
            val version=legacy.get("version"); require(version is Int && version in 0..13) { "إصدار قاعدة النظام القديم غير مدعوم" }
            val schema=legacy.getJSONArray("schema").objects(); val tables=legacy.getJSONObject("tables")
            require(schema.size<=500 && schema.map { it.getString("name") }.distinct().size==schema.size)
            require(tables.keys().asSequence().toSet()==schema.filter { it.getString("type")=="table" }.map { it.getString("name") }.toSet())
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                db.beginTransaction()
                try {
                    db.execSQL("DROP TABLE IF EXISTS android_metadata")
                    schema.forEach { entry ->
                        val type=entry.getString("type"); val name=entry.getString("name"); val sql=entry.getString("sql")
                        require(name.matches(Regex("[A-Za-z0-9_]+")) && type in listOf("table","index")) { "مخطط قاعدة قديم غير صحيح" }
                        val prefix=if(type=="table") "CREATE TABLE" else "CREATE (?:UNIQUE )?INDEX"
                        val body=if(type=="table") "\\(.*" else "ON\\s+.*"
                        require(sql.trim().matches(Regex("(?is)$prefix\\s+(?:IF NOT EXISTS\\s+)?[\\\"`\\[]?${Regex.escape(name)}[\\\"`\\]]?\\s*$body")) && !sql.contains(Regex("(?i)\\b(?:SELECT|CHECK|GENERATED|ATTACH|DETACH|PRAGMA)\\b"))) { "أمر مخطط غير مدعوم" }
                        db.execSQL(sql)
                    }
                    db.version=version
                    CommerceLegacySchema.validate(context,db,includeRows=false)
                    tables.keys().forEach { table ->
                        val columns=db.rawQuery("SELECT * FROM \"$table\" LIMIT 0",null).use { it.columnNames.toSet() }
                        tables.getJSONArray(table).objects().forEach { row ->
                            require(row.keys().asSequence().toSet()==columns) { "أعمدة قاعدة قديمة غير متوافقة" }
                            val values=ContentValues()
                            row.keys().forEach { key -> when(val value=row.get(key)) {
                                JSONObject.NULL -> values.putNull(key)
                                is Int -> values.put(key,value)
                                is Long -> values.put(key,value)
                                is String -> values.put(key,value)
                                is JSONObject -> { require(value.keys().asSequence().toSet()==setOf("blob")); values.put(key,Base64.decode(value.getString("blob"),Base64.DEFAULT)) }
                                else -> error("قيمة قديمة غير مدعومة")
                            } }
                            db.insertOrThrow("\"$table\"",null,values)
                        }
                    }
                    db.version=version
                    CommerceLegacySchema.validate(context,db)
                    require(CommerceStore.query(db,"PRAGMA integrity_check") { it.getString(0) }==listOf("ok")) { "قاعدة قديمة تالفة" }
                    require(CommerceStore.query(db,"PRAGMA foreign_key_check") { it.getString(0) }.isEmpty()) { "علاقات قديمة غير صحيحة" }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
    }
}
