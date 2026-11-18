param(
    [string] $Avd = 'GymFlow_Test',
    [switch] $Headless,
    [ValidateSet('auto','host','software','swiftshader')] [string] $Gpu,
    [switch] $ColdBoot
)
. "$PSScriptRoot\scripts\android.ps1"
$android = Initialize-GymFlowAndroid
if (!(Test-Path -LiteralPath $android.Emulator)) { throw 'Эмулятор отсутствует. Запустите setup-dev.ps1 -Install.' }
$available = @(& $android.Emulator -list-avds)
if ($Avd -notin $available) { throw "AVD $Avd отсутствует. Запустите setup-dev.ps1 -Install." }
$serial = Get-GymFlowAvdSerial $android.Adb $Avd
$alreadyStarting = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe' OR Name='qemu-system-aarch64.exe'" |
    Where-Object { $_.CommandLine -match ("(?:@|-avd\s+)" + [regex]::Escape($Avd) + "(?:\s|$)") }
if (!$serial -and !$alreadyStarting) {
    $renderer = if ($Gpu) { $Gpu } elseif ($Headless) { 'swiftshader' } else { 'auto' }
    $argsList = @('-avd', $Avd, '-gpu', $renderer)
    if ($ColdBoot) { $argsList += '-no-snapshot-load' }
    if ($Headless) {
        $argsList += @('-no-window', '-no-audio', '-no-boot-anim')
        $qa = Join-Path $GymFlowRoot 'qa-output'
        New-Item -ItemType Directory -Path $qa -Force | Out-Null
        Start-Process -FilePath $android.Emulator -ArgumentList $argsList -WindowStyle Hidden -RedirectStandardOutput "$qa\emulator.log" -RedirectStandardError "$qa\emulator.err" -ErrorAction Stop | Out-Null
    } else {
        Start-Process -FilePath $android.Emulator -ArgumentList $argsList -ErrorAction Stop | Out-Null
    }
}
Write-Host "Ожидаем загрузку $Avd"
$deadline = [DateTime]::UtcNow.AddMinutes(4)
while ([DateTime]::UtcNow -lt $deadline) {
    if (!$serial) { $serial = Get-GymFlowAvdSerial $android.Adb $Avd }
    if ($serial -and ([string](& $android.Adb -s $serial shell getprop sys.boot_completed 2>$null)).Trim() -eq '1') {
        return $serial
    }
    Start-Sleep -Seconds 2
}
throw "AVD $Avd не загрузился за 4 минуты"
