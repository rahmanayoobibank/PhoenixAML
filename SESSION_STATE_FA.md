# وضعیت پایدار جلسه — PhoenixAML

## وضعیت پایه
- پروژه: `PhoenixAML`
- شاخه Git: `master`
- مخزن GitHub: `https://github.com/rahmanayoobibank/PhoenixAML.git`
- آخرین پایه قبلی: `61a9967 Save PhoenixAML project state`

## مشکل کشف‌شده
نسخه قبلی فقط فایل را به `import_inbox` می‌برد و پردازش/استخراج/ورود به DB نداشت.

## تغییرات مرحله ۱۹
- `KnowledgeDb.kt`: DB داخلی writable + WAL + درج سند + FTS5 rebuild.
- `DocumentProcessor.kt`: پردازش PDF/TXT/DOCX/XLSX، hash، chunking، کنترل خالی بودن متن و جلوگیری از duplicate.
- `ImportWorker.kt`: پردازش پس‌زمینه با WorkManager.
- `DocumentImportManager.kt`: صف امن و مدیریت pending.
- `MainActivity.kt`: پردازش خودکار صف، دکمه پردازش دستی، نمایش وضعیت.
- `activity_main.xml`: دکمه پردازش صف.
- `app/build.gradle`: WorkManager + PDFBox، نسخه `1.1.0`/`16`.
- `.github/workflows/android-apk.yml`: build روی `master`/`main` و `clean assembleDebug`.

## معیار پذیرش نسخه بعدی
1. GitHub Actions باید سبز شود.
2. APK جدید نصب شود.
3. TXT وارد شود و قابل جست‌وجو باشد.
4. PDF متنی وارد شود و قابل جست‌وجو باشد.
5. DOCX وارد شود و قابل جست‌وجو باشد.
6. XLSX وارد شود و قابل جست‌وجو باشد.
7. فایل تکراری دوباره به DB اضافه نشود.
8. فایل خراب/خالی DB را تغییر ندهد و در صف باقی بماند.
9. PDF اسکن‌شده بدون OCR وارد دانش معتبر نشود.
10. بعد از بستن/بازکردن برنامه، داده‌های واردشده باقی بمانند.
