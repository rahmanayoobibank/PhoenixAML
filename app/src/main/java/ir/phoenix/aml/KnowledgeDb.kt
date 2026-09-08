package ir.phoenix.aml

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

class KnowledgeDb(private val context: Context) : AutoCloseable {
    private val db: SQLiteDatabase

    init {
        val target = File(context.filesDir, "phoenix_aml.db")
        if (!target.exists()) {
            context.assets.open("phoenix_aml.db").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_chunks (id TEXT PRIMARY KEY, document_name TEXT NOT NULL, page INTEGER, article TEXT, text TEXT NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS imported_chunks_fts USING fts5(id UNINDEXED, text, document_name)")
    }

    data class Chunk(val page: Int?, val article: String?, val text: String)

    data class Hit(
        val document: String,
        val page: Int?,
        val article: String?,
        val paragraph: String?,
        val text: String
    )

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

        val matchTerms = (tokens + listOfNotNull(articleNumber?.toString())).distinct().filter { it.isNotBlank() }
        val matchExpr = if (matchTerms.isEmpty()) null
            else matchTerms.joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }
        val candidateLimit = 400

        fun scoreRow(document: String, page: Int?, article: String?, text: String) {
            val normalizedText = PersianTextNormalizer.normalize(text)
            if (articleNumber != null) {
                val segment = extractArticle(normalizedText, articleNumber)
                if (segment != null) {
                    exactHits.add(Hit(document, page, articleNumber.toString(), null, segment))
                    return
                }
            }
            val tokenHits = tokens.count { normalizedText.contains(it) }
            if (tokenHits > 0) {
                val snippet = if (normalizedText.length > 700) normalizedText.take(700) + "…" else normalizedText
                relatedHits.add(Hit(document, page, article, null, snippet))
            }
        }

        if (matchExpr != null) {
            runCatching {
                db.rawQuery(
                    """
                    SELECT s.document_name, s.page, s.article, s.text
                    FROM chunks_fts f JOIN chunks s ON s.rowid = f.rowid
                    WHERE f MATCH ? ORDER BY bm25(f) LIMIT ?
                    """.trimIndent(),
                    arrayOf(matchExpr, candidateLimit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        scoreRow(c.getString(0), if (c.isNull(1)) null else c.getInt(1),
                            if (c.isNull(2)) null else c.getString(2), c.getString(3))
                    }
                }
            }
            runCatching {
                db.rawQuery(
                    """
                    SELECT s.document_name, s.page, s.article, s.text
                    FROM imported_chunks_fts f JOIN imported_chunks s ON s.id = f.id
                    WHERE f MATCH ? ORDER BY bm25(f) LIMIT ?
                    """.trimIndent(),
                    arrayOf(matchExpr, candidateLimit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        scoreRow(c.getString(0), if (c.isNull(1)) null else c.getInt(1),
                            if (c.isNull(2)) null else c.getString(2), c.getString(3))
                    }
                }
            }
        }

        if (articleNumber != null && exactHits.isEmpty()) {
            runCatching {
                db.rawQuery(
                    "SELECT document_name, page, article, text FROM chunks UNION ALL SELECT document_name, page, article, text FROM imported_chunks",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        scoreRow(c.getString(0), if (c.isNull(1)) null else c.getInt(1),
                            if (c.isNull(2)) null else c.getString(2), c.getString(3))
                    }
                }
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
            db.delete("imported_chunks_fts", "document_name = ?", arrayOf(documentName))
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
                val ftsValues = ContentValues().apply {
                    put("id", id)
                    put("document_name", documentName)
                    put("text", normalized)
                }
                db.insertOrThrow("imported_chunks_fts", null, ftsValues)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteDocument(documentName: String) {
        db.delete("imported_chunks", "document_name = ?", arrayOf(documentName))
        db.delete("imported_chunks_fts", "document_name = ?", arrayOf(documentName))
    }

    override fun close() = db.close()
}