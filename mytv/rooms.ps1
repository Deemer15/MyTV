# The TVs, by room. Dot-source it: . (Join-Path $PSScriptRoot 'rooms.ps1')
# Each room's folder (mytv\<room>\) holds its config.xml, debloat lists and backups.
$Rooms = [ordered]@{
    'Office' = @{ Host = '192.168.1.131'; Stock = 'com.google.android.apps.tv.launcherx'; Model = 'Hisense SmartTV 4K (lushan), Google TV 12' }
    'Master Bedroom' = @{ Host = '192.168.1.214'; Stock = 'com.google.android.apps.tv.launcherx'; Model = 'onn 4K Pro Streaming Device (jarvis2), Google TV 14' }
    'LivingRoom TV'  = @{ Host = '192.168.1.233'; Stock = 'com.google.android.apps.tv.launcherx'; Model = 'onn. Streaming Device 4K pro (SNA), Google TV 14' }
}
function Get-Room([string]$Name) {
    if (-not $Rooms.Contains($Name)) { throw "unknown room '$Name'; use one of: $($Rooms.Keys -join ', ')" }
    $r = $Rooms[$Name]
    $r.Name = $Name
    $r.Dir = Join-Path $PSScriptRoot $Name
    $r
}
