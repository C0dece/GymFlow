param([string] $Avd = 'GymFlow_Test', [string] $Destination, [switch] $Seed)
. "$PSScriptRoot\android.ps1"
$android = Initialize-GymFlowAndroid
$serial = Get-GymFlowAvdSerial $android.Adb $Avd
if (!$serial) { throw "AVD $Avd не запущен" }
$devicePath = '/sdcard/Documents/GymFlow_TestData'
& $android.Adb -s $serial shell mkdir -p $devicePath
if ($Seed) {
    & $android.Adb -s $serial push "$GymFlowRoot\app\src\test\resources\golden\session-v1.md" "$devicePath/sample-session.md"
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось скопировать тестовую тренировку' }
}
if ($Destination) {
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    & $android.Adb -s $serial pull $devicePath $Destination
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось выгрузить тестовую папку' }
}
Write-Host $devicePath
