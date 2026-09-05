package ir.phoenix.aml

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class ImportWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("phoenix_import", Context.MODE_PRIVATE)
        prefs.edit().putString("status", "در حال پردازش اسناد…").apply()
        return try {
            val imports = DocumentImportManager(applicationContext)
            val db = KnowledgeDb(applicationContext)
            var successCount = 0
            var failureCount = 0
            try {
                for (file in imports.pending()) {
                    val outcome = DocumentProcessor(applicationContext, db).process(file)
                    if (outcome.success) successCount++ else failureCount++
                    prefs.edit().putString("last_message", outcome.message).apply()
                }
            } finally {
                db.close()
            }
            val remaining = imports.pendingCount()
            prefs.edit()
                .putString(
                    "status",
                    if (remaining == 0) "پردازش کامل شد | فایل‌های در صف: 0"
                    else "پردازش انجام شد | فایل‌های نیازمند بررسی: $remaining"
                )
                .putInt("success_count", successCount)
                .putInt("failure_count", failureCount)
                .apply()
            if (failureCount > 0 && successCount == 0) Result.failure() else Result.success()
        } catch (e: Exception) {
            prefs.edit().putString("status", "پردازش متوقف شد: ${e.message ?: "خطای ناشناخته"}").apply()
            Result.retry()
        }
    }
}
