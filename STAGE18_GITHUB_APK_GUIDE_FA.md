# راهنمای سریع ساخت APK ققنوس

این نسخه برای ساخت APK با GitHub Actions آماده است.

1. وارد GitHub شوید و یک Repository خالی بسازید.
2. تمام محتویات این بسته را در Repository قرار دهید و Commit کنید.
3. به تب Actions بروید و workflow با نام `Build Phoenix AML APK` را اجرا کنید.
4. پس از موفقیت، Artifact با نام `phoenix-aml-debug-apk` را باز کنید.
5. فایل `app-debug.apk` را روی گوشی منتقل و نصب کنید.

در محیط فعلی Android SDK و Gradle نصب نیست؛ بنابراین ساخت واقعی APK اینجا ممکن نیست و آن را جعل نمی‌کنیم. Workflow روی runner گیت‌هاب SDK و Build Tools لازم را نصب می‌کند.
