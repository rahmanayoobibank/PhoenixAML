package ir.phoenix.aml

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
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_chunks (id TEXT PRIMARY KEY, document_name TEXT NOT NULL, page INTEGER, article TEXT, paragraph TEXT, text TEXT NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS imported_chunks_fts USING fts5(id UNINDEXED, text, document_name)")
        runCatching {
            val count = db.rawQuery("SELECT COUNT(*) FROM imported_chunks_fts", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            if (count == 0) {
                db.rawQuery("SELECT id, document_name, text FROM imported_chunks", null).use { c ->
                    while (c.moveToNext()) {
                        val values = android.content.ContentValues().apply {
                            put("id", c.getString(0))
                            put("document_name", c.getString(1))
                            put("text", c.getString(2))
                        }
                        db.insert("imported_chunks_fts", null, values)
                    }
                }
            }
        }
    }

    data class Hit(
        val document: String,
        val page: Int?,
        val article: String?,
        val paragraph: String?,
        val text: String,
        val exactArticle: Boolean = false,
        val score: Double = 0.0
    )

    data class SearchPack(
        val hits: List<Hit>,
        val suggestions: List<String>,
        val exactRequestedArticleFound: Boolean,
        val requestedArticle: Int? = null
    )

    fun search(query: String, limit: Int = 10): List<Hit> = searchPack(query, limit).hits

    fun searchPack(query: String, limit: Int = 10): SearchPack {
        val parsed = LegalQueryEngine.parse(query)
        if (parsed.normalized.length < 2 || limit <= 0) {
            return SearchPack(emptyList(), emptyList(), false, parsed.articleNumber)
        }

        val exactRows = ArrayList<Hit>()
        val relatedRows = ArrayList<Hit>()

        val matchTerms = (parsed.tokens + listOfNotNull(parsed.articleNumber?.toString()))
            .distinct()
            .filter { it.isNotBlank() }
        val matchExpr = if (matchTerms.isEmpty()) null
            else matchTerms.joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }

        val candidateLimit = 400

        if (matchExpr != null) {
            runCatching {
                db.rawQuery(
                    """
                    SELECT s.document_name, s.page, s.article, s.paragraph, s.text
                    FROM chunks_fts f JOIN chunks s ON s.rowid = f.rowid
                    WHERE f MATCH ? ORDER BY bm25(f) LIMIT ?
                    """.trimIndent(),
                    arrayOf(matchExpr, candidateLimit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        scoreRow(parsed, c.getString(0), if (c.isNull(1)) null else c.getInt(1),
                            if (c.isNull(2)) null else c.getString(2), if (c.isNull(3)) null else c.getString(3),
                            c.getString(4), exactRows, relatedRows)
                    }
                }
            }
            runCatching {
                db.rawQuery(
                    """
                    SELECT s.document_name, s.page, s.article, s.paragraph, s.text
                    FROM imported_chunks_fts f JOIN imported_chunks s ON s.id = f.id
                    WHERE f MATCH ? ORDER BY bm25(f) LIMIT ?
                    """.trimIndent(),
                    arrayOf(matchExpr, candidateLimit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        scoreRow(parsed, c.getString(0), if (c.isNull(1)) null else c.getInt(1),
                            if (c.isNull(2)) null else c.getString(2), if (c.isNull(3)) null else c.getString(3),
                            c.getString(4), exactRows, relatedRows)
                    }
                }
            }
        }

        if (parsed.articleNumber != null && exactRows.isEmpty()) {
            runCatching {
                db.rawQuery(
                    "SELECT document_name,page,article,paragraph,text FROM chunks UNION ALL SELECT document_name,page,article,paragraph,text FROM imported_chunks",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        scoreRow(parsed, c.getString(0), if (c.isNull(1)) null else c.getInt(1),
                            if (c.isNull(2)) null else c.getString(2), if (c.isNull(3)) null else c.getString(3),
                            c.getString(4), exactRows, relatedRows)
                    }
                }
            }
        }

        val exactFound = parsed.articleNumber != null && exactRows.isNotEmpty()
        val hits = if (parsed.articleNumber != null) {
            if (exactFound) exactRows else relatedRows
        } else relatedRows

        val sorted = hits
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.page ?: Int.MAX_VALUE })
            .distinctBy { "${it.document}|${it.page}|${it.article}|${PersianSearchNormalizer.compact(it.text).take(220)}" }
            .take(limit)

        return SearchPack(sorted, buildSuggestions(parsed, exactFound), exactFound, parsed.articleNumber)
    }

    private fun scoreRow(
        parsed: LegalQueryEngine.ParsedQuery,
        document: String,
        page: Int?,
        metaArticle: String?,
        paragraph: String?,
        text: String,
        exactRows: ArrayList<Hit>,
        relatedRows: ArrayList<Hit>
    ) {
        val normalizedText = PersianSearchNormalizer.normalize(text)
        val compactText = PersianSearchNormalizer.compact(normalizedText)
        val compactQuery = PersianSearchNormalizer.compact(parsed.normalized)

        if (parsed.articleNumber != null) {
            val exactSegment = extractArticle(normalizedText, parsed.articleNumber)
            if (exactSegment != null) {
                val score = 5000.0 + topicScore(parsed, document, exactSegment) + phraseScore(parsed, document, exactSegment)
                exactRows.add(Hit(document, page, parsed.articleNumber.toString(), paragraph,
                    cleanForDisplay(exactSegment), true, score))
                return
            }
        }

        val tokenHits = parsed.tokens.count { token ->
            val ct = PersianSearchNormalizer.compact(token)
            normalizedText.contains(token) || (ct.length >= 2 && compactText.contains(ct))
        }
        val phrase = parsed.normalized.isNotBlank() &&
            (normalizedText.contains(parsed.normalized) || compactText.contains(compactQuery))
        val topic = topicScore(parsed, document, normalizedText)
        val title = titleScore(parsed, document)

        if (tokenHits > 0 || phrase || topic > 0) {
            var score = tokenHits * 18.0 + topic + title
            if (phrase) score += 110.0
            if (parsed.tokens.size > 1 && tokenHits == parsed.tokens.size) score += 80.0
            if (parsed.articleNumber != null) score -= 3000.0
            val snippet = makeSnippet(normalizedText, parsed.tokens, parsed.normalized)
            relatedRows.add(Hit(document, page, metaArticle, paragraph, snippet, false, score))
        }
    }

    private fun topicScore(q: LegalQueryEngine.ParsedQuery, document: String, text: String): Double {
        if (q.topic.isBlank()) return 0.0
        val nt = PersianSearchNormalizer.normalize(text)
        val nd = PersianSearchNormalizer.normalize(document)
        val compactText = PersianSearchNormalizer.compact(nt)
        val topicTokens = q.topic.split(Regex("\\s+")).filter { it.length >= 2 }
        if (topicTokens.isEmpty()) return 0.0
        val covered = topicTokens.count { token ->
            nt.contains(token) || nd.contains(token) || compactText.contains(PersianSearchNormalizer.compact(token))
        }
        return covered * 22.0 + if (covered == topicTokens.size) 45.0 else 0.0
    }

    private fun titleScore(q: LegalQueryEngine.ParsedQuery, document: String): Double {
        val title = PersianSearchNormalizer.normalize(document)
        return q.tokens.count { title.contains(it) } * 16.0
    }

    private fun phraseScore(q: LegalQueryEngine.ParsedQuery, document: String, text: String): Double {
        if (q.topic.isBlank()) return 0.0
        val compactTopic = PersianSearchNormalizer.compact(q.topic)
        val hay = PersianSearchNormalizer.compact(PersianSearchNormalizer.normalize(document + " " + text))
        return if (compactTopic.length >= 4 && hay.contains(compactTopic)) 120.0 else 0.0
    }

    private fun makeSnippet(text: String, tokens: List<String>, phrase: String): String {
        val clean = cleanForDisplay(text)
        if (clean.length <= 650) return clean
        val normalized = PersianSearchNormalizer.normalize(clean)
        val candidates = tokens.mapNotNull { normalized.indexOf(it, ignoreCase = false).takeIf { i -> i >= 0 } }
        val phraseIndex = if (phrase.length >= 4) normalized.indexOf(phrase) else -1
        val center = (listOfNotNull(phraseIndex.takeIf { it >= 0 }, candidates.minOrNull())).firstOrNull() ?: 0
        val start = (center - 230).coerceAtLeast(0)
        val end = (center + 420).coerceAtMost(clean.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < clean.length) "…" else ""
        return prefix + clean.substring(start, end).trim() + suffix
    }

    private fun extractArticle(text: String, number: Int): String? {
        val n = PersianSearchNormalizer.normalize(text)
        val headerRegex = Regex("(?<![0-9])(?:ماده|ماد\\s*ه)(?:\\s*[یي])?\\s*$number(?![0-9])")
        val start = headerRegex.find(n) ?: return null
        val next = Regex("(?<![0-9])(?:ماده|ماد\\s*ه)(?:\\s*[یي])?\\s*[0-9]+(?![0-9])")
            .find(n, start.range.last + 1)
        val end = next?.range?.first ?: n.length
        return n.substring(start.range.first, end).trim().takeIf { it.length > 12 }
    }

    private fun cleanForDisplay(text: String): String = PersianSearchNormalizer.normalize(text)
        .replace(Regex("[ \\t]+([،؛:,.])"), "$1")
        .replace(Regex("\\n{3,}"), "\\n\\n")
        .trim()

    private fun buildSuggestions(q: LegalQueryEngine.ParsedQuery, exactFound: Boolean): List<String> {
        val compact = PersianSearchNormalizer.compact(q.normalized)
        if (q.articleNumber != null && !exactFound) {
            return listOf(
                "جست‌وجوی موضوع بدون شماره ماده",
                "نمایش نزدیک‌ترین منابع مرتبط؛ بدون معرفی آن‌ها به‌عنوان ماده ${q.articleNumber}"
            )
        }
        if (compact.contains("سطحمشتری") || compact.contains("مشتریسطح")) {
            return listOf("سطح فعالیت مشتری", "تعیین سطح فعالیت مشتریان", "رهنمودهای تعیین سطح فعالیت مشتریان")
        }
        if (compact.contains("افتتاححساب") || compact.contains("نگهداریحساب")) {
            return listOf("ضوابط افتتاح و نگهداری حساب‌های سپرده ریالی اشخاص حقیقی")
        }
        if (q.tokens.size == 1 && q.tokens.first() == "مشتری") {
            return listOf("سطح فعالیت مشتری", "تعیین سطح فعالیت مشتریان", "افتتاح و نگهداری حساب مشتری")
        }
        if (q.tokens.size == 1 && q.tokens.first() == "سطح") {
            return listOf("سطح فعالیت مشتری", "تعیین سطح فعالیت مشتریان")
        }
        return emptyList()
    }

    fun indexImportedDocument(documentName: String, chunks: List<ImportedChunk>) {
        db.beginTransaction()
        try {
            db.delete("imported_chunks", "document_name = ?", arrayOf(documentName))
            db.delete("imported_chunks_fts", "document_name = ?", arrayOf(documentName))
            chunks.forEachIndexed { index, chunk ->
                val id = "${documentName.hashCode()}_${index}_${chunk.page}"
                val normalized = PersianSearchNormalizer.normalize(chunk.text)
                val values = android.content.ContentValues().apply {
                    put("id", id)
                    put("document_name", documentName)
                    put("page", chunk.page)
                    put("article", chunk.article)
                    put("paragraph", chunk.paragraph)
                    put("text", normalized)
                }
                db.insertOrThrow("imported_chunks", null, values)
                val ftsValues = android.content.ContentValues().apply {
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

    data class ImportedChunk(val page: Int?, val article: String?, val paragraph: String?, val text: String)

    fun deleteImportedDocument(documentName: String) {
        db.delete("imported_chunks", "document_name = ?", arrayOf(documentName))
        db.delete("imported_chunks_fts", "document_name = ?", arrayOf(documentName))
    }

    override fun close() = db.close()
}