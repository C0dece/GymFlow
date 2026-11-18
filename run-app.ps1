param([string] $Avd = 'GymFlow_Test', [switch] $Headless)
. "$PSScriptRoot\scripts\android.ps1"
$android = Initialize-GymFlowAndroid
$serial = & "$PSScriptRoot\start-emulator.ps1" -Avd $Avd -Headless:$Headless
Invoke-GymFlowGradle @(':app:assembleDebug')
$apk = Join-Path $GymFlowRoot 'app\build\outputs\apk\debug\app-debug.apk'
& $android.Adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Установка GymFlow не удалась' }
& $android.Adb -s $serial shell am start -W -n 'app.gymflow/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'Запуск GymFlow не удался' }
