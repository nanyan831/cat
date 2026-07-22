param(
    [ValidateSet("on", "off")]
    [string]$Mode = "on",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$PackageName = "com.example.catlifepet"
)

$ErrorActionPreference = "Stop"
$prefsName = "cat_life_pet_settings.xml"
$deviceTemp = "/data/local/tmp/catlifepet-reminder-prefs.xml"
$localTemp = Join-Path $env:TEMP $prefsName

& $Adb shell am force-stop $PackageName | Out-Null
$xmlText = (& $Adb shell run-as $PackageName cat "shared_prefs/$prefsName") -join "`n"
[xml]$xml = $xmlText

$debugNode = $xml.map.SelectSingleNode("*[@name='debug_reminder_enabled']")
if ($null -eq $debugNode) {
    $debugNode = $xml.CreateElement("boolean")
    $debugNode.SetAttribute("name", "debug_reminder_enabled")
    [void]$xml.map.AppendChild($debugNode)
}
$debugNode.SetAttribute("value", ($Mode -eq "on").ToString().ToLowerInvariant())

foreach ($name in @("water_reminder_enabled", "food_reminder_enabled", "rest_reminder_enabled", "sleep_reminder_enabled")) {
    $node = $xml.map.SelectSingleNode("*[@name='$name']")
    if ($null -eq $node) {
        $node = $xml.CreateElement("boolean")
        $node.SetAttribute("name", $name)
        [void]$xml.map.AppendChild($node)
    }
    $node.SetAttribute("value", "true")
}

$xml.Save($localTemp)
& $Adb push $localTemp $deviceTemp | Out-Null
& $Adb shell run-as $PackageName cp $deviceTemp "shared_prefs/$prefsName"
& $Adb shell run-as $PackageName cat "shared_prefs/$prefsName"
