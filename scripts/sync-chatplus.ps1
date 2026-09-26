param([string]$Instance, [string]$Server, [string]$Canonical)

. (Join-Path $PSScriptRoot "props.ps1")
if (-not $Instance) { $Instance = $Props.deploy_instance_dir }
if (-not $Server) { $Server = $Props.deploy_server_dir }
if (-not $Canonical -and $Props.ContainsKey('deploy_chatplus_source')) { $Canonical = $Props.deploy_chatplus_source }

$ErrorActionPreference = 'Stop'

$clientDir = Join-Path $Instance 'config/chatplus'
$serverDir = Join-Path $Server 'config/chatplus'

function Normalize-ChatPlusFilters([string]$filePath) {
    if (-not (Test-Path $filePath -PathType Leaf)) { return }
    $raw = Get-Content -Raw -Path $filePath
    $json = $raw | ConvertFrom-Json
    if (-not $json.chatWindows -or $json.chatWindows.Count -eq 0) { return }

    $tabs = $json.chatWindows[0].tabSettings.tabs
    if (-not $tabs -or $tabs.Count -lt 3) { return }

    # Match both legacy text tags and icon tags using Unicode codepoint escapes.
    # U+1F5FA = map icon (country), U+23FB = power icon (admin).
    # We match near start to tolerate formatting prefixes from chat styling mods.
    $globalPattern = '(?s)^(?!.*\[(?:CC|AC|\x{1F5FA}|\x{23FB})]\s).*'
    $countryPattern = '(?s)^.*\[(?:CC|\x{1F5FA})]\s.*'
    $adminPattern = '(?s)^.*\[(?:AC|\x{23FB})]\s.*'

    $tabs[0].settings[0].pattern = $globalPattern
    $tabs[0].settings[0].notificationSettings.notificationMatch.pattern = $globalPattern

    $tabs[1].settings[0].pattern = $countryPattern
    $tabs[1].settings[0].notificationSettings.notificationMatch.pattern = $countryPattern

    $tabs[2].settings[0].pattern = $adminPattern
    $tabs[2].settings[0].notificationSettings.notificationMatch.pattern = $adminPattern

    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($filePath, ($json | ConvertTo-Json -Depth 100), $enc)
}

function Resolve-Canonical([string]$source) {
    if ([string]::IsNullOrWhiteSpace($source)) { return $null }
    if ($source -match '^https?://') {
        $tmpDir = Join-Path $env:TEMP 'kami-chatplus-sync'
        New-Item -ItemType Directory -Force $tmpDir | Out-Null
        $name = [IO.Path]::GetFileName(([Uri]$source).AbsolutePath)
        if ([string]::IsNullOrWhiteSpace($name)) { $name = 'chatplus-v2.7.0.json' }
        $tmpFile = Join-Path $tmpDir $name
        Invoke-WebRequest -Uri $source -OutFile $tmpFile
        return Get-Item $tmpFile
    }
    if (Test-Path $source -PathType Container) {
        return Get-ChildItem -Path $source -Filter 'chatplus-v*.json' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
    }
    if (Test-Path $source -PathType Leaf) { return Get-Item $source }
    return $null
}

$canonicalFile = Resolve-Canonical $Canonical

if ($canonicalFile) {
    New-Item -ItemType Directory -Force $clientDir | Out-Null
    New-Item -ItemType Directory -Force $serverDir | Out-Null
    $clientTarget = Join-Path $clientDir $canonicalFile.Name
    $serverTarget = Join-Path $serverDir $canonicalFile.Name
    Copy-Item -Path $canonicalFile.FullName -Destination $clientTarget -Force
    Copy-Item -Path $canonicalFile.FullName -Destination $serverTarget -Force
    Normalize-ChatPlusFilters $clientTarget
    Normalize-ChatPlusFilters $serverTarget
    Write-Host "ChatPlus sync: canonical -> client+server ($($canonicalFile.Name))"
    exit 0
}

$clientFile = Get-ChildItem -Path $clientDir -Filter 'chatplus-v*.json' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
$serverFile = Get-ChildItem -Path $serverDir -Filter 'chatplus-v*.json' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1

if (-not $clientFile -and -not $serverFile) {
    Write-Host 'ChatPlus sync: no chatplus-v*.json found on client or server.'
    exit 0
}

if (-not $serverFile -and $clientFile) {
    New-Item -ItemType Directory -Force $serverDir | Out-Null
    $serverTarget = Join-Path $serverDir $clientFile.Name
    Copy-Item -Path $clientFile.FullName -Destination $serverTarget -Force
    Normalize-ChatPlusFilters $serverTarget
    Normalize-ChatPlusFilters $clientFile.FullName
    Write-Host "ChatPlus sync: copied client -> server ($($clientFile.Name))"
    exit 0
}

if (-not $clientFile -and $serverFile) {
    New-Item -ItemType Directory -Force $clientDir | Out-Null
    $clientTarget = Join-Path $clientDir $serverFile.Name
    Copy-Item -Path $serverFile.FullName -Destination $clientTarget -Force
    Normalize-ChatPlusFilters $clientTarget
    Normalize-ChatPlusFilters $serverFile.FullName
    Write-Host "ChatPlus sync: copied server -> client ($($serverFile.Name))"
    exit 0
}

if ($clientFile.Name -ne $serverFile.Name) {
    # Keep file versions aligned by copying newer file to both sides.
    $newest = @($clientFile, $serverFile) | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    New-Item -ItemType Directory -Force $clientDir | Out-Null
    New-Item -ItemType Directory -Force $serverDir | Out-Null
    $clientTarget = Join-Path $clientDir $newest.Name
    $serverTarget = Join-Path $serverDir $newest.Name
    Copy-Item -Path $newest.FullName -Destination $clientTarget -Force
    Copy-Item -Path $newest.FullName -Destination $serverTarget -Force
    Normalize-ChatPlusFilters $clientTarget
    Normalize-ChatPlusFilters $serverTarget
    Write-Host "ChatPlus sync: unified both sides to $($newest.Name)"
    exit 0
}

$clientTime = $clientFile.LastWriteTimeUtc
$serverTime = $serverFile.LastWriteTimeUtc

if ($clientTime -gt $serverTime) {
    Copy-Item -Path $clientFile.FullName -Destination $serverFile.FullName -Force
    Normalize-ChatPlusFilters $clientFile.FullName
    Normalize-ChatPlusFilters $serverFile.FullName
    Write-Host "ChatPlus sync: copied client -> server ($($clientFile.Name))"
} elseif ($serverTime -gt $clientTime) {
    Copy-Item -Path $serverFile.FullName -Destination $clientFile.FullName -Force
    Normalize-ChatPlusFilters $clientFile.FullName
    Normalize-ChatPlusFilters $serverFile.FullName
    Write-Host "ChatPlus sync: copied server -> client ($($serverFile.Name))"
} else {
    Normalize-ChatPlusFilters $clientFile.FullName
    Normalize-ChatPlusFilters $serverFile.FullName
    Write-Host "ChatPlus sync: already in sync ($($clientFile.Name))"
}
