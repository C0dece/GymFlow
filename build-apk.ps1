param([switch] $Release)
. "$PSScriptRoot\scripts\android.ps1"
$null = Initialize-GymFlowAndroid
if ($Release) {
    Invoke-GymFlowGradle @(':app:assembleRelease')
    Write-Host 'Release APK без подписи: app/build/outputs/apk/release/app-release-unsigned.apk'
} else {
    Invoke-GymFlowGradle @(':app:assembleDebug')
    Get-Item -LiteralPath (Join-Path $GymFlowRoot 'app\build\outputs\apk\debug\app-debug.apk') | Select-Object -ExpandProperty FullName
}
