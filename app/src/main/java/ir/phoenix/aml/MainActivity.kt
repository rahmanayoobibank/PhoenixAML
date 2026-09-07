package ir.phoenix.aml

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.*
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class MainActivity : Activity() {
    private lateinit var knowledge: KnowledgeDb
    private lateinit var imports: DocumentImportManager
    private lateinit var result: TextView
    private lateinit var status: TextView
    private val pickCode = 401
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        result = findViewById(R.id.result)
        status = findViewById(R.id.status)
        knowledge = KnowledgeDb(this)
        imports = DocumentImportManager(this)
        refreshStatus()
        findViewById<Button>(R.id.search).setOnClickListener {
            search(findViewById<EditText>(R.id.query).text.toString())
        }
        findViewById<Button>(R.id.importFile).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.processQueue).setOnClickListener { enqueueProcessing() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences("phoenix_import", MODE_PRIVATE)
        val queue = imports.pendingCount()
        val workerStatus = prefs.getString("status", "") ?: ""
        status.text = if (workerStatus.isNotBlank()) {
            "$workerStatus\nصف فعلی: $queue"
        } else {
            "پایگاه دانش آماده است | فایل‌های در صف: $queue"
        }
    }

    private fun search(q: String) {
        if (q.trim().length < 2) {
            result.text = "لطفاً سؤال یا عبارت دقیق‌تری وارد کنید."
            return
        }
        try {
            val hits = knowledge.search(q, 8)
            result.text = if (hits.isEmpty()) {
                "برای این پرسش، منبع کافی در پایگاه دانش پیدا نشد؛ ققنوس حدس نمی‌زند."
            } else {
                hits.joinToString("\n\n────────────\n\n") { hit ->
                    "سند: ${hit.document}\nصفحه: ${hit.page ?: "—"}\nماده: ${hit.article ?: "—"}\nتبصره/بند: ${hit.paragraph ?: "—"}\n\n${hit.text}"
                }
            }
        } catch (e: Exception) {
            result.text = "جست‌وجو انجام نشد: ${e.message ?: "خطای ناشناخته"}"
        }
    }
private fun pickFile() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
            "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/plain",
            "image/jpeg", "image/png", "image/bmp", "image/webp"
        ))
        addCategory(Intent.CATEGORY_OPENABLE)
    }, pickCode)
}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK || data?.data == null) return
        try {
            val imported = imports.import(data.data!!)
            result.text = "سند با موفقیت وارد صف امن شد:\n${imported.name}\n\nSHA-256: ${imported.sha256}\n\nاکنون «پردازش صف» را بزنید. فقط فایل‌هایی که استخراج متن و کنترل کیفیت خودکار را با موفقیت بگذرانند وارد پایگاه دانش می‌شوند."
            refreshStatus()
            enqueueProcessing()
        } catch (e: Exception) {
            result.text = "ورود فایل انجام نشد: ${e.message ?: "خطای ناشناخته"}"
        }
    }

    private fun enqueueProcessing() {
        val request = OneTimeWorkRequestBuilder<ImportWorker>().build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork("phoenix-import", ExistingWorkPolicy.KEEP, request)
        getSharedPreferences("phoenix_import", MODE_PRIVATE).edit()
            .putString("status", "پردازش صف در حال شروع است…")
            .apply()
        refreshStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        knowledge.close()
        super.onDestroy()
    }
}
