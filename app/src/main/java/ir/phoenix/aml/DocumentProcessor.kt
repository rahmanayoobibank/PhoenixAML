package ir.phoenix.aml

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.File
import java.io.FileInputStream
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import java.io.StringReader
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Extracts supported office/PDF/text formats and commits only quality-checked text. */
class DocumentProcessor(private val context: Context, private val db: KnowledgeDb) {
    data class Outcome(val name: String, val inserted: Int, val message: String, val success: Boolean)

    init { PDFBoxResourceLoader.init(context) }

    fun process(file: File): Outcome {
        val name = file.name.substringAfter('_', file.name)
        val ext = name.substringAfterLast('.', "").lowercase()
        val hash = sha256(file)
        if (db.containsSourceHash(hash)) {
            file.delete()
            return Outcome(name, 0, "این فایل قبلاً وارد پایگاه دانش شده است.", true)
        }

        return try {
            val documentId = UUID.randomUUID().toString()
            val chunks = when (ext) {
                "txt" -> textChunks(file.readText(Charsets.UTF_8), name, hash, documentId, ext)
                "docx" -> textChunks(extractDocx(file), name, hash, documentId, ext)
        "doc" -> textChunks(extractDoc(file), name, hash, documentId, ext)
                "xlsx" -> textChunks(extractXlsx(file), name, hash, documentId, ext)
                "pdf" -> pdfChunks(file, name, hash, documentId)
                else -> emptyList()
            }
            if (chunks.isEmpty()) {
                return Outcome(name, 0, "از فایل متن قابل اعتماد استخراج نشد؛ فایل در صف بررسی باقی می‌ماند.", false)
            }
            val inserted = db.insertDocument(chunks)
            file.delete()
            val id = file.name.substringBefore('_')
            File(file.parentFile, "$id.meta").delete()
            Outcome(name, inserted, "پردازش کامل شد؛ $inserted بخش وارد پایگاه دانش شد.", true)
        } catch (e: Exception) {
            Outcome(name, 0, "پردازش ناموفق بود: ${e.message ?: "خطای ناشناخته"}", false)
        }
    }

    private fun pdfChunks(file: File, name: String, hash: String, documentId: String): List<KnowledgeDb.Chunk> {
        PDDocument.load(file).use { pdf ->
            val result = mutableListOf<KnowledgeDb.Chunk>()
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            for (page in 1..pdf.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                val text = normalize(stripper.getText(pdf))
                if (text.isNotBlank()) {
                    addSplitChunks(result, text, name, hash, documentId, "pdf", page)
                }
            }
            if (result.isEmpty()) throw IllegalArgumentException("PDF تصویری/اسکن‌شده است یا متن آن قابل استخراج نیست")
            return result
        }
    }

    private fun textChunks(text: String, name: String, hash: String, documentId: String, ext: String): List<KnowledgeDb.Chunk> {
        val normalized = normalize(text)
        require(normalized.isNotBlank()) { "متن فایل خالی است" }
        val result = mutableListOf<KnowledgeDb.Chunk>()
        addSplitChunks(result, normalized, name, hash, documentId, ext, null)
        return result
    }

    private fun addSplitChunks(
        out: MutableList<KnowledgeDb.Chunk>, text: String, name: String, hash: String,
        documentId: String, ext: String, page: Int?
    ) {
        val max = 3500
        val overlap = 250
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                val cut = text.lastIndexOfAny(charArrayOf('\n', '.', '؟', '!', '،', ' '), end - 1)
                if (cut > start + 1200) end = cut
            }
            val part = text.substring(start, end).trim()
            if (part.length >= 20) {
                val metadata = db.buildMetadata(name, hash, ext, mapOf("page" to page, "chunk_start" to start, "chunk_end" to end))
                out += KnowledgeDb.Chunk(UUID.randomUUID().toString(), documentId, name, hash, page, part, metadata)
            }
            if (end >= text.length) break
            start = maxOf(end - overlap, start + 1)
        }
    }

    private fun extractDoc(file: File): String = FileInputStream(file).use { input ->
        HWPFDocument(input).use { document ->
            WordExtractor(document).use { extractor ->
                extractor.text ?: ""
            }
        }
    }

    private fun extractDocx(file: File): String = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: throw IllegalArgumentException("ساختار DOCX نامعتبر است")
        zip.getInputStream(entry).use { input -> parseWordXml(input.readBytes().toString(Charsets.UTF_8)) }
    }

    private fun parseWordXml(xml: String): String {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val out = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "t" -> out.append(parser.nextText())
                    "tab" -> out.append('\t')
                    "br", "cr" -> out.append('\n')
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "p") {
                out.append('\n')
            }
            event = parser.next()
        }
        return out.toString()
    }

    private fun extractXlsx(file: File): String = ZipFile(file).use { zip ->
        val shared = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            zip.getInputStream(entry).use { parseSharedStrings(it.readBytes().toString(Charsets.UTF_8)) }
        } ?: emptyList()
        val sheets = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.name }
            .toList()
        require(sheets.isNotEmpty()) { "ساختار XLSX نامعتبر است" }
        buildString {
            for (sheet in sheets) {
                zip.getInputStream(sheet).use { append(parseSheet(it.readBytes().toString(Charsets.UTF_8), shared)); append('\n') }
            }
        }
    }

    private fun parseSharedStrings(xml: String): List<String> {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val out = mutableListOf<String>()
        var current = StringBuilder()
        var inSi = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "si" -> { current = StringBuilder(); inSi = true }
                    "t" -> if (inSi) current.append(parser.nextText())
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "si") {
                out += current.toString(); inSi = false
            }
            event = parser.next()
        }
        return out
    }

    private fun parseSheet(xml: String, shared: List<String>): String {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val out = StringBuilder()
        var cellType: String? = null
        var cellRef: String? = null
        var inV = false
        var value = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "c" -> { cellType = parser.getAttributeValue(null, "t"); cellRef = parser.getAttributeValue(null, "r") }
                    "v", "t" -> if (cellType != "inlineStr" || parser.name == "t") { inV = true; value = StringBuilder() }
                }
            } else if (event == XmlPullParser.TEXT && inV) {
                value.append(parser.text)
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "v", "t" -> if (inV) { inV = false }
                    "c" -> {
                        val raw = value.toString()
                        val finalValue = if (cellType == "s") shared.getOrNull(raw.toIntOrNull() ?: -1) ?: raw else raw
                        if (finalValue.isNotBlank()) out.append(cellRef ?: "").append('=').append(finalValue).append("\t")
                        value = StringBuilder(); cellType = null; cellRef = null
                    }
                    "row" -> out.append('\n')
                }
            }
            event = parser.next()
        }
        return out.toString()
    }

    private fun normalize(text: String): String = PersianTextNormalizer.normalize(text)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
