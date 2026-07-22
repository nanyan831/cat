param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$PackageName = "com.example.catlifepet",
    [ValidateRange(0, 100)]
    [int]$Mood = 60,
    [ValidateSet(80, 120, 160)]
    [int]$PetSizeDp = 160,
    [int]$LastX = [int]::MinValue
)

$ErrorActionPreference = "Stop"
$prefsName = "cat_life_pet_settings.xml"
$deviceTemp = "/data/local/tmp/catlifepet-prefs.xml"
$localTemp = Join-Path $env:TEMP $prefsName

& $Adb shell am force-stop $PackageName | Out-Null
$xmlText = (& $Adb shell run-as $PackageName cat "shared_prefs/$prefsName") -join "`n"
[xml]$xml = $xmlText

function Set-Pref {
    param([string]$Type, [string]$Name, [string]$Value)

    $node = $xml.map.SelectSingleNode("*[@name='$Name']")
    if ($null -eq $node) {
        $node = $xml.CreateElement($Type)
        $node.SetAttribute("name", $Name)
        [void]$xml.map.AppendChild($node)
    }
    if ($Type -eq "string") {
        $node.InnerText = $Value
    } else {
        $node.SetAttribute("value", $Value)
    }
}

Set-Pref "int" "pet_affection" "80"
Set-Pref "int" "highest_unlocked_affection_level" "3"
Set-Pref "int" "pet_energy" "70"
Set-Pref "int" "pet_mood" "$Mood"
Set-Pref "int" "pet_size_dp" "$PetSizeDp"
if ($LastX -ne [int]::MinValue) {
    Set-Pref "int" "last_x" "$LastX"
}
Set-Pref "boolean" "do_not_disturb_enabled" "false"
Set-Pref "boolean" "mute_today_enabled" "false"
Set-Pref "boolean" "onboarding_completed" "true"
Set-Pref "boolean" "first_pet_summon_completed" "true"

$xml.Save($localTemp)
& $Adb push $localTemp $deviceTemp | Out-Null
& $Adb shell run-as $PackageName cp $deviceTemp "shared_prefs/$prefsName"
& $Adb shell run-as $PackageName cat "shared_prefs/$prefsName"
