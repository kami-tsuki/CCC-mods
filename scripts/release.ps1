param([string]$Instance, [string]$Server, [string]$Channel = '1552219072769298504', [switch]$NoBuild, [switch]$DryRun)
. (Join-Path $PSScriptRoot "props.ps1")
if (-not $Instance) { $Instance = $Props.deploy_instance_dir }
if (-not $Server) { $Server = $Props.deploy_server_dir }
$ErrorActionPreference = 'Stop'

Write-Host "Stopping client and server..."
Get-CimInstance Win32_Process | Where-Object {
    ($_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'net\.minecraft\.client\.main\.Main|neoforged[\\/]neoforge[\\/].*win_args\.txt') -or
    ($_.Name -eq 'cmd.exe' -and $_.CommandLine -match 'run\.bat')
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

Write-Host "Syncing server with the client instance (mods, config, kubejs, chatplus)..."
& (Join-Path $PSScriptRoot 'sync-server.ps1') -Instance $Instance -Server $Server | Out-Null

if (-not $NoBuild) {
    Write-Host "Building, testing and deploying..."
    & (Join-Path $RepoRoot 'gradlew.bat') -p $RepoRoot build --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "Build failed, nothing released" }
}

Write-Host "Removing kami configs..."
foreach ($target in $Server, $Instance) { Remove-Item (Join-Path $target 'config/kami') -Recurse -Force -ErrorAction SilentlyContinue }

$jar = Get-ChildItem (Join-Path $Instance 'mods') -Filter 'kami_libs-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar -or $jar.BaseName -notmatch '^kami_libs-(.+)$') { throw "No kami_libs jar in $Instance/mods, cannot detect the version" }
$Version = $Matches[1]

Write-Host "Exporting and sending $Version..."
$pyArgs = @((Join-Path $PSScriptRoot 'release_pack.py'), '--instance', (Split-Path $Instance -Parent), '--version', $Version,
    '--discord-config', (Join-Path $Server 'config/kami-discord-bot.json'), '--channel', $Channel,
    '--out', (Join-Path $RepoRoot "build/release/$Version"))
if ($DryRun) { $pyArgs += '--dry-run' }
& python @pyArgs
if ($LASTEXITCODE -ne 0) { throw "Release export or upload failed" }
