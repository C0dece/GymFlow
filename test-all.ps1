param([string] $Avd = 'GymFlow_Test', [switch] $Headless, [switch] $JvmOnly)
. "$PSScriptRoot\scripts\android.ps1"
$android = Initialize-GymFlowAndroid
$tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:checkDebugAarMetadata', ':app:checkDebugDuplicateClasses', ':app:assembleDebug')
if (!$JvmOnly) { $tasks += ':app:assembleDebugAndroidTest' }
Invoke-GymFlowGradle $tasks
if (!$JvmOnly) {
    $serial = & "$PSScriptRoot\start-emulator.ps1" -Avd $Avd -Headless:$Headless
    $app = Join-Path $GymFlowRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $tests = Join-Path $GymFlowRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    & $android.Adb -s $serial install -r $app
    if ($LASTEXITCODE -ne 0) { throw 'Установка GymFlow не удалась' }
    & $android.Adb -s $serial install -r -t $tests
    if ($LASTEXITCODE -ne 0) { throw 'Установка тестового APK не удалась' }
    $qa = Join-Path $GymFlowRoot 'qa-output'
    New-Item -ItemType Directory -Path $qa -Force | Out-Null
    $result = & $android.Adb -s $serial shell am instrument -w -r 'app.gymflow.test/androidx.test.runner.AndroidJUnitRunner' 2>&1
    $adbCode = $LASTEXITCODE
    $result | Tee-Object -FilePath (Join-Path $qa 'instrumentation.log')
    & $android.Adb -s $serial logcat -d -s GymFlowQA:I | Set-Content (Join-Path $qa 'performance.log')
    $crashes = [string[]]@(& $android.Adb -s $serial logcat -d -b crash)
    [IO.File]::WriteAllLines((Join-Path $qa 'crash.log'), $crashes)
    $screens = Join-Path $qa 'screens'
    New-Item -ItemType Directory -Path $screens -Force | Out-Null
    & $android.Adb -s $serial shell test -d '/sdcard/Android/data/app.gymflow/files/qa'
    if ($LASTEXITCODE -eq 0) {
        & $android.Adb -s $serial pull '/sdcard/Android/data/app.gymflow/files/qa/.' $screens
    }
    if ($adbCode -ne 0 -or !($result -match '^OK \([1-9][0-9]* tests?\)')) {
        throw "Инструментальные тесты завершились ошибкой. Отчёт: $qa\instrumentation.log"
    }
}
