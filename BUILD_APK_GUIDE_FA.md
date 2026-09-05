# راهنمای ساخت APK ققنوس — نسخه عملی

## روش ۱: GitHub Actions (پیشنهادی)
1. کل پوشه `android_app` را داخل یک repository قرار دهید.
2. repository را روی GitHub قرار دهید.
3. وارد تب Actions شوید.
4. Workflow با نام `Build Phoenix AML APK` را اجرا کنید.
5. پس از موفقیت، Artifact با نام `phoenix-aml-debug-apk` را دریافت کنید.
6. فایل `app-debug.apk` را روی گوشی Android نصب کنید.

Workflow خودش Android SDK 35، Build-Tools 35.0.0 و Gradle 8.9 را آماده می‌کند.

## روش ۲: Android Studio
- Android Studio را نصب کنید.
- SDK Platform 35 و Build-Tools 35.0.0 را نصب کنید.
- پوشه `android_app` را Open کنید.
- Gradle Sync و سپس Build APK(s) را اجرا کنید.

## نکته
نسخه فعلی Debug است. برای Release عمومی باید keystore امن و signing configuration جداگانه اضافه شود.
