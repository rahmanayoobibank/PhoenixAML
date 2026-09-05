# گزارش مرحله ۱۷ — Phoenix AML

## وضعیت
مرحله ۱۷ با ابزار جایگزین بررسی و اصلاح شد.

## بررسی‌های انجام‌شده
- ساختار پروژه Android بررسی شد.
- Java 17 در محیط فعلی موجود است.
- Android SDK و Gradle در محیط فعلی موجود نیستند؛ بنابراین Build باینری APK در این محیط قابل ادعا نیست.
- SQLite asset و ساختار FTS5 بررسی شدند.
- فایل‌های Kotlin، Manifest، Gradle و resourceها بررسی شدند.
- Workflow ساخت APK اصلاح شد تا در GitHub Actions ابتدا Android SDK 35 و Build-Tools 35.0.0 را آماده کند و سپس Gradle 8.9 را اجرا کند.

## نتیجه
مسیر Build از حالت «فقط وابسته به وجود SDK روی runner» به مسیر صریح و قابل تکرار تبدیل شد.

## محدودیت
APK واقعی فقط در محیط دارای Android SDK/Gradle یا GitHub Actions ساخته می‌شود. این محیط فعلی SDK/Gradle ندارد.
