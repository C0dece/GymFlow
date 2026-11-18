$ErrorActionPreference = 'Stop'
$GymFlowRoot = Split-Path -Parent $PSScriptRoot

function Initialize-GymFlowAndroid {
    param([string] $Sdk)
    $candidates = @($Sdk, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $config = Join-Path $GymFlowRoot 'local.properties'
    if (Test-Path -LiteralPath $config) {
        $setting = Get-Content -LiteralPath $config | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
        if ($setting) { $candidates += $setting.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    $candidates += @((Join-Path $env:LOCALAPPDATA 'Android\Sdk'), [IO.Path]::GetFullPath((Join-Path $GymFlowRoot '..\..\..\SDKs\Android')))
    $base = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
    if (!$base) { throw 'Android SDK не найден. Запустите setup-dev.ps1 -Install или укажите ANDROID_HOME.' }
    $env:ANDROID_HOME = [IO.Path]::GetFullPath($base)
    if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }

    if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
        $studio = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\java.exe'
        $java = if (Test-Path -LiteralPath $studio) { $studio } else { (Get-Command java.exe -ErrorAction Stop).Source }
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
    }
    return @{
        Sdk = $env:ANDROID_HOME
        Adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        Emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
        Manager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
        AvdManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\avdmanager.bat'
    }
}

function Invoke-GymFlowGradle {
    param([string[]] $Tasks)
    Push-Location $GymFlowRoot
    try {
        & (Join-Path $GymFlowRoot 'gradlew.bat') @Tasks
        if ($LASTEXITCODE -ne 0) { throw "Gradle завершился с кодом $LASTEXITCODE" }
    } finally { Pop-Location }
}

function Get-GymFlowAvdSerial {
    param([string] $Adb, [string] $Avd)
    foreach ($line in @(& $Adb devices)) {
        if ($line -match '^(emulator-\d+)\s+device\s*$') {
            $serial = $Matches[1]
            $name = @(& $Adb -s $serial emu avd name 2>$null)
            if ($name -contains $Avd) { return $serial }
        }
    }
}
