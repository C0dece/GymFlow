param(
    [switch] $Install,
    [string] $Sdk,
    [ValidateSet(34, 36)] [int] $Api = 36,
    [string] $Avd = 'GymFlow_Test',
    [switch] $Compact
)
. "$PSScriptRoot\scripts\android.ps1"
if ($Install) {
    if (!$Sdk) {
        try { $Sdk = (Initialize-GymFlowAndroid).Sdk }
        catch { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    }
    New-Item -ItemType Directory -Path $Sdk -Force | Out-Null
}
$android = Initialize-GymFlowAndroid -Sdk $Sdk
if (!(Test-Path -LiteralPath $android.Manager)) {
    if (!$Install) { throw 'Android SDK Command-line Tools отсутствуют. Используйте -Install.' }
    $download = Join-Path ([IO.Path]::GetTempPath()) ("gymflow-tools-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $download | Out-Null
    $archive = Join-Path $download 'tools.zip'
    & curl.exe -fL --retry 2 -o $archive 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось скачать SDK Command-line Tools' }
    if ((Get-FileHash -LiteralPath $archive).Hash -ine '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a') {
        throw 'Контрольная сумма SDK Command-line Tools не совпала'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $download
    $toolsRoot = Join-Path $android.Sdk 'cmdline-tools'
    New-Item -ItemType Directory -Path $toolsRoot -Force | Out-Null
    $latest = Join-Path $toolsRoot 'latest'
    if (Test-Path -LiteralPath $latest) { throw "Каталог $latest уже существует. Восстановите его через SDK Manager." }
    Move-Item -LiteralPath (Join-Path $download 'cmdline-tools') -Destination $latest
}
$abi = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64-v8a' } else { 'x86_64' }
$image = "system-images;android-$Api;google_apis;$abi"
if ($Install) {
    Write-Host 'Android SDK предложит принять лицензии.'
    & $android.Manager --licenses
    if ($LASTEXITCODE -ne 0) { throw 'Проверка лицензий завершилась ошибкой' }
    & $android.Manager 'platform-tools' 'emulator' 'platforms;android-37.2' 'build-tools;36.0.0' $image
    if ($LASTEXITCODE -ne 0) { throw 'Установка SDK завершилась ошибкой' }
}
foreach ($entry in @($android.Adb, $android.Emulator, (Join-Path $android.Sdk 'platforms\android-37.2\android.jar'),
        (Join-Path $android.Sdk "system-images\android-$Api\google_apis\$abi\source.properties"))) {
    if (!(Test-Path -LiteralPath $entry)) { throw "Не найдено: $entry. Используйте -Install." }
}
& "$env:JAVA_HOME\bin\java.exe" -version
& $android.Emulator -accel-check
if ($LASTEXITCODE -ne 0) { throw 'Эмулятору недоступно ускорение WHPX.' }
$avds = @(& $android.Emulator -list-avds)
if ($Avd -notin $avds) {
    $device = if ($Compact) { 'pixel_4' } else { 'pixel_7' }
    'no' | & $android.AvdManager create avd -n $Avd -k $image -d $device
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать AVD' }
}
Write-Host "SDK: $($android.Sdk) | AVD: $Avd"
Invoke-GymFlowGradle @('--version')
