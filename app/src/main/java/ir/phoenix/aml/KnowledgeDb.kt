package ir.phoenix.aml

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject

/**
 * Local knowledge store.
 *
 * The packaged DB is the initial verified bundle. On first run it is copied to
 * app-private storage and then kept writable so successfully processed imports
 * can be added without modifying the APK asset.
 */
class KnowledgeDb(private val context: Context) : AutoCloseable {
    private val lock = Any()
    private val db: SQLiteDatabase

    init {
        val target = File(context.filesDir, "phoenix_aml.db")
        if (!target.exists()) {
            context.assets.open("phoenix_aml.db").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA foreign_keys=ON")
    }

    data class Hit(
        val document: String,
        val page: Int?,
        val article: String?,
        val paragraph: String?,
        val text: String
    )

    data class Chunk(
        val id: String,
        val documentId: String,
        val documentName: String,
        val sourceHash: String,
        val page: Int?,
        val text: String,
        val metadataJson: String,
        val needsOcr: Boolean = false
    )

    fun search(query: String, limit: Int = 8): List<Hit> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val tokens = query.trim()
            .replace('"', ' ')
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length > 1 }
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" ") { token ->
            "\"${token.replace('"', ' ')}\"*"
        }
        synchronized(lock) {
            val sql = "SELECT c.document_name,c.page,c.article,c.paragraph,c.text " +
                "FROM chunks_fts f JOIN chunks c ON c.rowid=f.rowid " +
                "WHERE chunks_fts MATCH ? LIMIT ?"
            db.rawQuery(sql, arrayOf(match, limit.toString())).use { cur ->
                return buildList {
                    while (cur.moveToNext()) add(
                        Hit(
                            cur.getString(0),
                            if (cur.isNull(1)) null else cur.getInt(1),
                            if (cur.isNull(2)) null else cur.getString(2),
                            if (cur.isNull(3)) null else cur.getString(3),
                            cur.getString(4)
                        )
                    )
                }
            }
        }
    }

    fun containsSourceHash(sourceHash: String): Boolean = synchronized(lock) {
        db.rawQuery("SELECT 1 FROM chunks WHERE source_hash=? LIMIT 1", arrayOf(sourceHash)).use { it.moveToFirst() }
    }

    fun insertDocument(chunks: List<Chunk>): Int {
        if (chunks.isEmpty()) return 0
        synchronized(lock) {
            val uniqueHash = chunks.first().sourceHash
            if (containsSourceHash(uniqueHash)) return 0
            db.beginTransaction()
            try {
                val sql = "INSERT INTO chunks(" +
                    "id,document_id,document_name,source_hash,page,article,paragraph,item,chapter,section," +
                    "version_label,issue_date,effective_date,validity_status,source_priority,needs_ocr,text,metadata_json" +
                    ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                db.compileStatement(sql).use { stmt ->
                    for (chunk in chunks) {
                        stmt.clearBindings()
                        stmt.bindString(1, chunk.id)
                        stmt.bindString(2, chunk.documentId)
                        stmt.bindString(3, chunk.documentName)
                        stmt.bindString(4, chunk.sourceHash)
                        if (chunk.page == null) stmt.bindNull(5) else stmt.bindLong(5, chunk.page.toLong())
                        stmt.bindNull(6)
                        stmt.bindNull(7)
                        stmt.bindNull(8)
                        stmt.bindNull(9)
                        stmt.bindNull(10)
                        stmt.bindNull(11)
                        stmt.bindNull(12)
                        stmt.bindNull(13)
                        stmt.bindString(14, "verified_import")
                        stmt.bindLong(15, 0)
                        stmt.bindLong(16, if (chunk.needsOcr) 1 else 0)
                        stmt.bindString(17, chunk.text)
                        stmt.bindString(18, chunk.metadataJson)
                        stmt.executeInsert()
                    }
                }
                // chunks_fts is an external-content FTS5 table. Rebuild from the
                // authoritative chunks table so the new rows are searchable.
                db.execSQL("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')")
                db.setTransactionSuccessful()
                return chunks.size
            } finally {
                db.endTransaction()
            }
        }
    }

    fun buildMetadata(sourceName: String, sourceHash: String, extension: String, extra: Map<String, Any?> = emptyMap()): String {
        val json = JSONObject()
        json.put("source_name", sourceName)
        json.put("source_hash", sourceHash)
        json.put("extension", extension)
        json.put("authoritative", true)
        extra.forEach { (k, v) -> json.put(k, v) }
        return json.toString()
    }

    override fun close() {
        synchronized(lock) { db.close() }
    }
}
