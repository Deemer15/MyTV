# Build the launcher with one room's config.xml and install it on that room's TV.
#   pwsh mytv\build-and-install.ps1 -Room 'Jeremys Office'
#   pwsh mytv\build-and-install.ps1 -Room 'Master Bedroom'
#   add -HomeKeyService for the remote helper (see README "Remote buttons")
# Edit mytv\<room>\config.xml, not the one in launcher\: it is copied over the launcher's on every build.
# Uses the portable JDK 17 + Android SDK in %LOCALAPPDATA%\cleanyourtv-toolchain (mytv\toolchain.ps1 sets it up).
param(
    [Parameter(Mandatory)][ValidateSet('Jeremys Office', 'Master Bedroom', 'LivingRoom TV')][string]$Room,
    [switch]$HomeKeyService,
    [switch]$InputKeyService
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rooms.ps1')
$r = Get-Room $Room
$repo = Split-Path -Parent $PSScriptRoot
Copy-Item (Join-Path $r.Dir 'config.xml') (Join-Path $repo 'launcher\app\src\main\res\values\config.xml') -Force
$t = Join-Path $env:LOCALAPPDATA 'cleanyourtv-toolchain'
if (-not (Test-Path "$t\sdk\platforms\android-34")) { & (Join-Path $PSScriptRoot 'toolchain.ps1') }
$env:JAVA_HOME = (Get-ChildItem "$t\jdk" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$t\sdk"
$args2 = @{ TvHost = $r.Host; StockLauncher = $r.Stock }
if ($HomeKeyService) { $args2.HomeKeyService = $true }
if ($InputKeyService) { $args2.InputKeyService = $true }
Write-Host "Building for $Room ($($r.Host))"
& (Join-Path $repo 'launcher\build-and-install.ps1') @args2
