package ir.phoenix.aml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class DocumentImportManager(private val context: Context) {
    data class Imported(val id: String, val name: String, val sha256: String, val file: File)
    private val inbox = File(context.filesDir, "import_inbox").apply { mkdirs() }
    private val allowed = setOf("pdf", "doc", "docx", "xls", "xlsx", "txt", "jpg", "jpeg", "png", "bmp", "webp")

    fun import(uri: Uri): Imported {
        val original = displayName(uri)
        val ext = original.substringAfterLast('.', "").lowercase()
        require(ext in allowed) { "قالب فایل پشتیبانی نمی‌شود" }
        val safeName = original.replace(Regex("[^A-Za-z0-9._-آ-ی]"), "_").take(120).ifBlank { "document.$ext" }
        val id = UUID.randomUUID().toString()
        val target = File(inbox, "${id}_$safeName")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "امکان خواندن فایل وجود ندارد" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val sha = sha256(target)
        File(inbox, "$id.meta").writeText("name=$safeName\nsha256=$sha\nextension=$ext\nindexed=false\n", Charsets.UTF_8)
        return Imported(id, safeName, sha, target)
    }

    fun pendingCount(): Int = metaFiles().size

    fun listPending(): List<Pair<String, File>> = metaFiles().mapNotNull { meta ->
        val name = meta.readLines(Charsets.UTF_8).firstOrNull { it.startsWith("name=") }?.removePrefix("name=") ?: return@mapNotNull null
        val id = meta.name.removeSuffix(".meta")
        name to File(inbox, inbox.listFiles()?.firstOrNull { it.name.startsWith(id + "_") }?.name ?: return@mapNotNull null)
    }

    fun deletePending(file: File): Boolean {
        val id = file.name.substringBefore('_')
        file.delete()
        return File(inbox, "$id.meta").delete()
    }

    fun markIndexed(file: File, chunkCount: Int) {
        val id = file.name.substringBefore('_')
        val meta = File(inbox, "$id.meta")
        val name = displayNameFromMeta(meta)
        val sha = sha256(file)
        val ext = file.extension.lowercase()
        meta.writeText("name=$name\nsha256=$sha\nextension=$ext\nindexed=true\nchunks=$chunkCount\n", Charsets.UTF_8)
    }

    fun isIndexed(file: File): Boolean {
        val id = file.name.substringBefore('_')
        return File(inbox, "$id.meta").readLines(Charsets.UTF_8).any { it == "indexed=true" }
    }

    private fun displayNameFromMeta(meta: File): String =
        meta.takeIf { it.exists() }?.readLines(Charsets.UTF_8)?.firstOrNull { it.startsWith("name=") }?.removePrefix("name=") ?: "document"

    private fun metaFiles(): List<File> = inbox.listFiles { f -> f.isFile && f.name.endsWith(".meta") }?.sortedBy { it.name } ?: emptyList()

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