# Read-only snapshot of the TV's package, property and settings state.
# Usage: pwsh mytv\backup.ps1 -Room 'Master Bedroom' [-Out <folder>]   (default: mytv\<room>\backups\<date>)
param(
    [Parameter(Mandatory)][ValidateSet('Jeremys Office', 'Master Bedroom', 'LivingRoom TV')][string]$Room,
    [string]$Out = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rooms.ps1')
$r = Get-Room $Room
$TvHost = $r.Host
if (-not $Out) { $Out = Join-Path $r.Dir "backups\$(Get-Date -Format yyyy-MM-dd-HHmm)" }
$root = Split-Path -Parent $PSScriptRoot
$py = Join-Path $root 'tools\venv\Scripts\python.exe'
$tv = Join-Path $root 'tools\tv.py'
$env:TV_HOST = $TvHost
New-Item -ItemType Directory -Force $Out | Out-Null

$jobs = [ordered]@{
    'packages.txt'          = 'pm list packages -f'
    'packages-disabled.txt' = 'pm list packages -d'
    'packages-system.txt'   = 'pm list packages -s'
    'packages-3rdparty.txt' = 'pm list packages -3'
    'getprop.txt'           = 'getprop'
    'settings-system.txt'   = 'settings list system'
    'settings-secure.txt'   = 'settings list secure'
    'settings-global.txt'   = 'settings list global'
    'wm-size.txt'           = 'wm size'
    'wm-density.txt'        = 'wm density'
    'density-forced.txt'    = 'settings get secure display_density_forced'
    'home.txt'              = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    'home-candidates.txt'   = 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    'wifi-owner.txt'        = "dumpsys wifi | grep -o 'cname=[^ ]*'"
    'transport.txt'         = "dumpsys connectivity | grep -o 'Transports: [A-Z]*'"
    'usb.txt'               = 'dumpsys usb | grep host_connected'
    'accessibility.txt'     = 'settings get secure enabled_accessibility_services'
}
foreach ($k in $jobs.Keys) {
    & $py $tv $jobs[$k] | Out-File -Encoding utf8 (Join-Path $Out $k)
}
Get-ChildItem $Out | ForEach-Object { '{0,-24} {1,6} lines' -f $_.Name, (Get-Content $_.FullName).Count }
