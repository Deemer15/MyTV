# Portable, no-admin build toolchain for the launcher: Temurin JDK 17 + Android SDK (platform 34, build-tools 34.0.0).
# Everything lands in %LOCALAPPDATA%\cleanyourtv-toolchain; delete that folder to remove it.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$t = Join-Path $env:LOCALAPPDATA 'cleanyourtv-toolchain'
New-Item -ItemType Directory -Force $t | Out-Null

if (-not (Get-ChildItem "$t\jdk" -Directory -ErrorAction SilentlyContinue)) {
    Write-Host 'Downloading Temurin JDK 17...'
    Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile "$t\jdk17.zip"
    Expand-Archive "$t\jdk17.zip" "$t\jdk" -Force
    Remove-Item "$t\jdk17.zip"
}
$jdk = (Get-ChildItem "$t\jdk" -Directory | Select-Object -First 1).FullName

$sdk = "$t\sdk"
if (-not (Test-Path "$sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Write-Host 'Downloading Android command-line tools...'
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile "$t\clt.zip"
    Expand-Archive "$t\clt.zip" "$t\clt-tmp" -Force
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
    Move-Item "$t\clt-tmp\cmdline-tools" "$sdk\cmdline-tools\latest" -Force
    Remove-Item "$t\clt.zip"; Remove-Item -Recurse "$t\clt-tmp"
}

$env:JAVA_HOME = $jdk
$sm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
Write-Host 'Accepting SDK licences and installing platform 34 + build-tools 34.0.0...'
(1..20 | ForEach-Object { 'y' }) | & $sm --sdk_root=$sdk --licenses | Out-Null
& $sm --sdk_root=$sdk 'platforms;android-34' 'build-tools;34.0.0' 'platform-tools' | Select-String -NotMatch '^\[|^$'
Write-Host "JAVA_HOME=$jdk"
Write-Host "ANDROID_HOME=$sdk"
& "$jdk\bin\java.exe" -version
