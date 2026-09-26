param([string]$Instance, [string]$Server, [string]$Prism = "$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe", [switch]$NoBuild)
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

Write-Host "Syncing server with the client instance (mods, config, kubejs)..."
& (Join-Path $PSScriptRoot 'sync-server.ps1') -Instance $Instance -Server $Server | Out-Null

if (-not $NoBuild) {
    Write-Host "Building and deploying..."
    & (Join-Path $RepoRoot 'gradlew.bat') -p $RepoRoot build --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "Build failed, nothing started" }
}

Write-Host "Removing kami configs..."
foreach ($target in $Server, $Instance) { Remove-Item (Join-Path $target 'config/kami') -Recurse -Force -ErrorAction SilentlyContinue }

Write-Host "Starting server and client..."
Start-Process -FilePath (Join-Path $Server 'run.bat') -WorkingDirectory $Server
Start-Process -FilePath $Prism -ArgumentList '--launch', (Split-Path (Split-Path $Instance -Parent) -Leaf)
