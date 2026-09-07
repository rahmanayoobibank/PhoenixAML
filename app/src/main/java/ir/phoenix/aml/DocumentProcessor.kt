package ir.phoenix.aml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class DocumentProcessor(private val context: Context) {
    data class Chunk(val page: Int?, val article: String?, val paragraph: String?, val text: String)

    fun process(file: File): List<Chunk> {
        return when (file.extension.lowercase()) {
            "pdf" -> processPdf(file)
            "txt" -> listOf(Chunk(1, null, null, file.readText(Charsets.UTF_8)))
            "docx" -> listOf(Chunk(null, null, null, extractDocx(file)))
            "xlsx" -> listOf(Chunk(null, null, null, extractXlsx(file)))
            "jpg", "jpeg", "png", "bmp", "webp" -> processImage(file)
            else -> emptyList()
        }.filter { it.text.isNotBlank() }
    }

    private fun processImage(file: File): List<Chunk> {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return emptyList()
        val scaled = scaleForOcr(bitmap)
        val text = runOcr(scaled)
        if (scaled !== bitmap) bitmap.recycle()
        if (text.isBlank()) return emptyList()
        return listOf(Chunk(null, LegalQueryEngine.articleNumberIn(text)?.toString(), null, text))
    }

    private fun scaleForOcr(bitmap: Bitmap): Bitmap {
        val targetWidth = 1800
        if (bitmap.width <= targetWidth) return bitmap
        val targetHeight = (targetWidth.toFloat() * bitmap.height / bitmap.width).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun processPdf(file: File): List<Chunk> {
        PDFBoxResourceLoader.init(context)
        val chunks = ArrayList<Chunk>()
        PDDocument.load(file).use { doc ->
            for (pageNo in 1..doc.numberOfPages) {
                val stripper = PDFTextStripper().apply {
                    startPage = pageNo
                    endPage = pageNo
                    sortByPosition = true
                }
                val text = runCatching { stripper.getText(doc) }.getOrDefault("")
                if (isUsablePersianText(text)) {
                    chunks += Chunk(pageNo, LegalQueryEngine.articleNumberIn(text)?.toString(), null, text)
                } else {
                    val ocr = ocrPdfPage(file, pageNo)
                    if (ocr.isNotBlank()) chunks += Chunk(pageNo, LegalQueryEngine.articleNumberIn(ocr)?.toString(), null, ocr)
                }
            }
        }
        return chunks
    }

    private fun isUsablePersianText(text: String): Boolean {
        val n = PersianSearchNormalizer.normalize(text)
        if (n.length < 25) return false
        val fa = n.count { it in '\u0600'..'\u06FF' }
        val letters = n.count { it.isLetter() }
        return fa >= 8 && letters > 0 && fa.toDouble() / letters.toDouble() > 0.08
    }

    private fun ocrPdfPage(file: File, pageNumber: Int): String {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val page = renderer.openPage(pageNumber - 1)
        val width = 1800
        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(900)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page.render(bitmap, Rect(0, 0, width, height), null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        renderer.close()
        pfd.close()

        val text = runOcr(bitmap)
        bitmap.recycle()
        return text
    }

    private fun runOcr(bitmap: Bitmap): String {
        val tessRoot = File(context.filesDir, "tesseract").apply { mkdirs() }
        val tessData = File(tessRoot, "tessdata").apply { mkdirs() }
        val trained = File(tessData, "fas.traineddata")
        if (!trained.exists()) context.assets.open("tessdata/fas.traineddata").use { input ->
            FileOutputStream(trained).use { output -> input.copyTo(output) }
        }

        val tess = TessBaseAPI()
        return try {
            if (!tess.init(tessRoot.absolutePath, "fas")) return ""
            tess.setImage(bitmap)
            tess.getUTF8Text()?.trim().orEmpty()
        } finally {
            tess.recycle()
        }
    }

    private fun extractDocx(file: File): String {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return ""
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(zip.getInputStream(entry))
            return doc.getElementsByTagNameNS("*", "t").let { nodes ->
                buildString {
                    for (i in 0 until nodes.length) append(nodes.item(i).textContent).append(' ')
                }
            }
        }
    }

    private fun extractXlsx(file: File): String {
        ZipFile(file).use { zip ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val shared = mutableListOf<String>()
            zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                val doc = factory.newDocumentBuilder().parse(zip.getInputStream(entry))
                val nodes = doc.getElementsByTagNameNS("*", "t")
                for (i in 0 until nodes.length) shared += nodes.item(i).textContent
            }
            val sheets = zip.entries().asSequence().filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.toList()
            return buildString {
                for (sheet in sheets) {
                    val doc = factory.newDocumentBuilder().parse(zip.getInputStream(sheet))
                    val cells = doc.getElementsByTagNameNS("*", "c")
                    for (i in 0 until cells.length) {
                        val cell = cells.item(i)
                        val type = cell.attributes?.getNamedItem("t")?.nodeValue
                        val values = cell.childNodes
                        var value = ""
                        for (j in 0 until values.length) {
                            val n = values.item(j)
                            if (n.localName == "v") value = n.textContent
                            if (n.localName == "t") value = n.textContent
                        }
                        if (type == "s") value = value.toIntOrNull()?.let { shared.getOrNull(it).orEmpty() } ?: value
                        if (value.isNotBlank()) append(value).append(' ')
                    }
                    append('\n')
                }
            }
        }
    }
}