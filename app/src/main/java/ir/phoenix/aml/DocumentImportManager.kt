package ir.phoenix.aml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Secure inbox: copies a user-selected document into app-private storage. */
class DocumentImportManager(private val context: Context) {
    data class Imported(val id: String, val name: String, val sha256: String, val file: File, val extension: String)

    private val inbox = File(context.filesDir, "import_inbox").apply { mkdirs() }
    private val allowed = setOf("pdf", "docx", "xlsx", "txt")

    fun import(uri: Uri): Imported {
        val original = displayName(uri)
        val ext = original.substringAfterLast('.', "").lowercase()
        require(ext in allowed) { "قالب فایل پشتیبانی نمی‌شود: .$ext" }
        val safeName = original.replace(Regex("[^A-Za-z0-9._-آ-ی]"), "_").take(120).ifBlank { "document.$ext" }
        val id = UUID.randomUUID().toString()
        val target = File(inbox, "${id}_$safeName")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "امکان خواندن فایل وجود ندارد" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        require(target.length() > 0) { "فایل خالی است" }
        val sha = sha256(target)
        File(inbox, "$id.meta").writeText(
            "name=$safeName\nsha256=$sha\nextension=$ext\nauthoritative=false\nstatus=pending\n",
            Charsets.UTF_8
        )
        return Imported(id, safeName, sha, target, ext)
    }

    fun pending(): List<File> = inbox.listFiles { f ->
        f.isFile && f.name.endsWith(".meta")
    }?.mapNotNull { meta ->
        val id = meta.name.removeSuffix(".meta")
        inbox.listFiles { f -> f.isFile && f.name.startsWith("${id}_") }?.firstOrNull()
    }?.sortedBy { it.name } ?: emptyList()

    fun pendingCount(): Int = pending().size

    fun remove(imported: Imported) {
        imported.file.delete()
        File(inbox, "${imported.id}.meta").delete()
    }

    fun removeByFile(file: File) {
        val id = file.name.substringBefore('_')
        file.delete()
        File(inbox, "$id.meta").delete()
    }

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
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
