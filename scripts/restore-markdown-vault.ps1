param(
    [Parameter(Mandatory)][string] $Vault,
    [Parameter(Mandatory)][string] $Archive,
    [switch] $Apply
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$vaultPath = (Resolve-Path -LiteralPath $Vault).ProviderPath
$prefix = $vaultPath.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$archivePath = (Resolve-Path -LiteralPath $Archive).ProviderPath

function Resolve-VaultFile([string] $Relative) {
    $parts = $Relative.Split('/')
    if ($parts.Count -eq 0 -or @($parts | Where-Object { $_ -in @('', '.', '..') -or $_ -match '[\\:\x00-\x1f]' }).Count) {
        throw "Недопустимый путь в архиве: $Relative"
    }
    $destination = [IO.Path]::GetFullPath((Join-Path $vaultPath ($parts -join [IO.Path]::DirectorySeparatorChar)))
    if (!$destination.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Путь вне папки: $Relative" }
    $folder = Split-Path -Parent $destination
    while ($folder -ne $vaultPath.TrimEnd([IO.Path]::DirectorySeparatorChar)) {
        $item = Get-Item -LiteralPath $folder
        if ($item.LinkType) { throw "Путь проходит через ссылку: $folder" }
        $folder = Split-Path -Parent $folder
    }
    if ((Test-Path -LiteralPath $destination) -and (Get-Item -LiteralPath $destination).LinkType) {
        throw "Файл является ссылкой: $destination"
    }
    return $destination
}

function Read-Hash([string] $Path) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) { return (Get-FileHash -LiteralPath $Path).Hash }
    if (Test-Path -LiteralPath $Path) { throw "На месте файла находится папка: $Path" }
    return $null
}

$zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    $map = $zip.GetEntry('Переименования.tsv')
    if (!$map -or $map.Length -gt 4MB) { throw 'Нет пригодной таблицы переименований' }
    $reader = [IO.StreamReader]::new($map.Open(), [Text.Encoding]::UTF8)
    try { $lines = $reader.ReadToEnd() -split '\r?\n' } finally { $reader.Dispose() }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $plan = @(
        foreach ($line in ($lines | Select-Object -Skip 1)) {
            if (!$line) { continue }
            $id, $oldRelative, $newRelative, $oldHash, $newHash = $line.Split("`t")
            if ($line.Split("`t").Count -ne 5 -or $oldHash -notmatch '^[0-9a-f]{64}$' -or $newHash -notmatch '^[0-9a-f]{64}$') {
                throw 'Повреждена таблица переименований'
            }
            $oldPath = Resolve-VaultFile $oldRelative
            $newPath = Resolve-VaultFile $newRelative
            if (!$seen.Add($oldPath) -or ($oldPath -ne $newPath -and !$seen.Add($newPath))) { throw 'В таблице повторяется путь' }
            $entry = $zip.GetEntry("Исходники/$oldRelative")
            if (!$entry -or $entry.Length -gt 512KB) { throw "Отсутствует исходник: $oldRelative" }
            $stream = $entry.Open()
            try { $original = (Get-FileHash -InputStream $stream).Hash } finally { $stream.Dispose() }
            if ($original -ne $oldHash) { throw "Неверный хеш исходника: $oldRelative" }
            $onOld = Read-Hash $oldPath
            $onNew = if ($oldPath -ne $newPath) { Read-Hash $newPath } else { $null }
            if ($onOld -and $onNew) { throw "Оба пути заняты: $oldRelative" }
            if ($onOld -eq $oldHash -and !$onNew) { continue }
            if (($onOld -or $onNew) -ne $true -or ($onOld -and $onOld -ne $newHash) -or ($onNew -and $onNew -ne $newHash)) {
                throw "Файл отсутствует или изменён после миграции: $oldRelative"
            }
            [pscustomobject]@{ Old = $oldPath; New = $newPath; Moved = [bool]$onNew; Entry = $entry; Before = $oldHash; After = $newHash }
        }
    )
    if (!$Apply) {
        $plan | Select-Object New, Old
        Write-Host "К откату: $($plan.Count). Для записи укажите -Apply."
        return
    }
    foreach ($row in $plan) {
        if ($row.Moved) {
            if ((Read-Hash $row.New) -ne $row.After -or (Test-Path -LiteralPath $row.Old)) { throw "Файл изменился: $($row.New)" }
            Move-Item -LiteralPath $row.New -Destination $row.Old
        }
        if ((Read-Hash $row.Old) -ne $row.After) { throw "Файл изменился: $($row.Old)" }
        $temporary = [IO.Path]::GetTempFileName()
        try {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($row.Entry, $temporary, $true)
            [IO.File]::WriteAllBytes($row.Old, [IO.File]::ReadAllBytes($temporary))
            if ((Read-Hash $row.Old) -ne $row.Before) { throw "Исходник не подтверждён: $($row.Old)" }
        } finally {
            Remove-Item -LiteralPath $temporary
        }
    }
    Write-Host "Восстановлено: $($plan.Count)"
} finally {
    $zip.Dispose()
}
