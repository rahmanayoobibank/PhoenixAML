$ErrorActionPreference = 'Stop'
Write-Host 'PHOENIX AML - APK BUILD'
if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) {
  throw 'Gradle پیدا نشد. Android Studio را نصب کنید یا Gradle 8.9 را به PATH اضافه کنید.'
}
Set-Location $PSScriptRoot
gradle --no-daemon assembleDebug
Write-Host "APK: $PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
