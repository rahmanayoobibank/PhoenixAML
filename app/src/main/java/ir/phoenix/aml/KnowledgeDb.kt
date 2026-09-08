package ir.phoenix.aml

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

class KnowledgeDb(private val context: Context) : AutoCloseable {
    private val db: SQLiteDatabase
    private data class Row(val document: String, val page: Int?, val article: String?, val text: String)
    private val cache = ArrayList<Row>()

    init {
        val target = File(context.filesDir, "phoenix_aml.db")
        if (!target.exists()) {
            context.assets.open("phoenix_aml.db").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_chunks (id TEXT PRIMARY KEY, document_name TEXT NOT NULL, page INTEGER, article TEXT, text TEXT NOT NULL)")
        loadCache()
    }

    private fun loadCache() {
        cache.clear()
        runCatching {
            db.rawQuery("SELECT document_name, page, article, text FROM chunks", null).use { c ->
                while (c.moveToNext()) {
                    cache.add(Row(c.getString(0), if (c.isNull(1)) null else c.getInt(1),
                        if (c.isNull(2)) null else c.getString(2), c.getString(3)))
                }
            }
        }
        runCatching {
            db.rawQuery("SELECT document_name, page, article, text FROM imported_chunks", null).use { c ->
                while (c.moveToNext()) {
                    cache.add(Row(c.getString(0), if (c.isNull(1)) null else c.getInt(1),
                        if (c.isNull(2)) null else c.getString(2), c.getString(3)))
                }
            }
        }
    }

    data class Chunk(val page: Int?, val article: String?, val text: String)
    data class Hit(val document: String, val page: Int?, val article: String?, val paragraph: String?, val text: String)

    fun search(query: String, limit: Int = 10): List<Hit> {
        val normalizedQuery = PersianTextNormalizer.normalize(query)
        if (normalizedQuery.length < 2 || limit <= 0) return emptyList()

        val articleNumber = Regex("(?:ماده|ماد\\s*ه)\\s*([0-9]+)").find(normalizedQuery)?.groupValues?.get(1)?.toIntOrNull()
        val stopwords = setOf("ماده", "بخشنامه", "دستورالعمل", "در", "خصوص", "با", "از", "به", "و", "را")
        val tokens = normalizedQuery.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stopwords && it.toIntOrNull() == null }
            .distinct()

        val exactHits = ArrayList<Hit>()
        val relatedHits = ArrayList<Hit>()

        for (row in cache) {
            val normalizedText = PersianTextNormalizer.normalize(row.text)
            if (articleNumber != null) {
                val segment = extractArticle(normalizedText, articleNumber)
                if (segment != null) {
                    exactHits.add(Hit(row.document, row.page, articleNumber.toString(), null, segment))
                    continue
                }
            }
            val tokenHits = tokens.count { normalizedText.contains(it) }
            if (tokenHits > 0) {
                val snippet = if (normalizedText.length > 700) normalizedText.take(700) + "…" else normalizedText
                relatedHits.add(Hit(row.document, row.page, row.article, null, snippet))
            }
        }

        val hits = if (articleNumber != null && exactHits.isNotEmpty()) exactHits else relatedHits
        return hits.distinctBy { "${it.document}|${it.page}|${it.article}|${it.text.take(200)}" }.take(limit)
    }

    private fun extractArticle(text: String, number: Int): String? {
        val headerRegex = Regex("(?<![0-9])(?:ماده|ماد\\s*ه)\\s*$number(?![0-9])")
        val start = headerRegex.find(text) ?: return null
        val next = Regex("(?<![0-9])(?:ماده|ماد\\s*ه)\\s*[0-9]+(?![0-9])").find(text, start.range.last + 1)
        val end = next?.range?.first ?: text.length
        return text.substring(start.range.first, end).trim().takeIf { it.length > 12 }
    }

    fun indexDocumentChunks(documentName: String, chunks: List<Chunk>) {
        db.beginTransaction()
        try {
            db.delete("imported_chunks", "document_name = ?", arrayOf(documentName))
            chunks.forEachIndexed { index, chunk ->
                val id = "${documentName.hashCode()}_${index}_${chunk.page ?: 0}"
                val normalized = PersianTextNormalizer.normalize(chunk.text)
                val values = ContentValues().apply {
                    put("id", id)
                    put("document_name", documentName)
                    put("page", chunk.page)
                    put("article", chunk.article)
                    put("text", normalized)
                }
                db.insertOrThrow("imported_chunks", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loadCache()
    }

    fun deleteDocument(documentName: String) {
        db.delete("imported_chunks", "document_name = ?", arrayOf(documentName))
        loadCache()
    }

    override fun close() = db.close()
}