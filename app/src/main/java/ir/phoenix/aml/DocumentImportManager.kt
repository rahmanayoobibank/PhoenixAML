package ir.phoenix.aml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class DocumentImportManager(private val context: Context) {
    data class Imported(val name: String, val sha256: String, val file: File)

    private val inbox = File(context.filesDir, "import_inbox").apply { mkdirs() }
    private val allowed = setOf("pdf", "doc", "docx", "xls", "xlsx", "txt", "jpg", "jpeg", "png", "bmp", "webp")

    fun import(uri: Uri): Imported {
        val original = displayName(uri)
        val ext = original.substringAfterLast('.', "").lowercase()
        require(ext in allowed) { "قالب فایل پشتیبانی نمی‌شود" }
        val safeBase = original.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "document" }
        val target = File(inbox, "${UUID.randomUUID()}__$safeBase")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "امکان خواندن فایل وجود ندارد" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val sha = sha256(target)
        return Imported(original, sha, target)
    }

    fun pending(): List<File> = inbox.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()

    fun pendingCount(): Int = pending().size

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return "document"
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}