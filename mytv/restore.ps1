# Undo one room's debloat and launcher swap.
#   pwsh mytv\restore.ps1 -Room 'Jeremys Office'                 re-enable everything and give Home back to Google TV
#   pwsh mytv\restore.ps1 -Room 'Jeremys Office' -Package <pkg>  re-enable just one app
# Needs USB debugging (and Network debugging) switched back on in Developer options, and the TV awake.
param(
    [Parameter(Mandatory)][ValidateSet('Jeremys Office', 'Master Bedroom', 'LivingRoom TV')][string]$Room,
    [string]$Package = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rooms.ps1')
$r = Get-Room $Room
$repo = Split-Path -Parent $PSScriptRoot
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
$tv = Join-Path $repo 'tools\tv.py'
$env:TV_HOST = $r.Host

if ($Package) {
    & $py $tv "pm enable $Package"
    exit $LASTEXITCODE
}

# Replay the states recorded when each batch was disabled (records in %USERPROFILE%\.cleanyourtv\, named after
# the batch file's path), newest batch first. Without a record, tools\restore.ps1 enables the whole list.
$batches = Get-ChildItem $r.Dir -Filter 'batch*.txt' | Sort-Object Name -Descending
foreach ($b in $batches) {
    & (Join-Path $repo 'tools\restore.ps1') -TvHost $r.Host $b.FullName -Stock $r.Stock
}

# Make sure Google TV Home is enabled and default, whatever the recorded state said.
& $py $tv "pm enable $($r.Stock)"
& $py $tv "cmd package set-home-activity --user 0 $($r.Stock)/.home.HomeActivity"
Write-Host 'Done. Press Home on the remote. To remove the Clean Your TV launcher too: tools\tv.py "pm uninstall com.gathin.tvhome"'
