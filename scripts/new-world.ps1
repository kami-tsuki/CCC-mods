param([string]$Instance, [string]$Server)
. (Join-Path $PSScriptRoot "props.ps1")
if (-not $Instance) { $Instance = $Props.deploy_instance_dir }
if (-not $Server) { $Server = $Props.deploy_server_dir }
$ErrorActionPreference = 'Stop'

function Move-IfExists($Path, $Destination) {
    if (-not (Test-Path $Path)) { return }
    New-Item -ItemType Directory -Force (Split-Path $Destination -Parent) | Out-Null
    Move-Item $Path $Destination
}

$levelName = ((Get-Content (Join-Path $Server 'server.properties') | Where-Object { $_ -match '^level-name=' }) -replace '^level-name=', '').Trim()
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$serverBackup = Join-Path $Server "world-backup-$stamp"
$world = Join-Path $Server $levelName
$oldWorld = Join-Path $serverBackup $levelName

Write-Host "Moving the world and the Kami database to $serverBackup ..."
Move-IfExists $world $oldWorld
foreach ($name in 'kami.db', 'kami.db-shm', 'kami.db-wal', 'backups') { Move-IfExists (Join-Path $Server "kami/$name") (Join-Path $serverBackup "kami/$name") }

Write-Host "Creating the new world with the old settings (difficulty, game rules) and a new seed ..."
New-Item -ItemType Directory $world | Out-Null
python -I (Join-Path $PSScriptRoot 'new_world.py') (Join-Path $oldWorld 'level.dat') (Join-Path $world 'level.dat') | ForEach-Object { Write-Host "New seed: $_" }
if ($LASTEXITCODE -ne 0) { throw "level.dat could not be rewritten, the old world is in $oldWorld" }
if (Test-Path (Join-Path $oldWorld 'serverconfig')) { Copy-Item (Join-Path $oldWorld 'serverconfig') (Join-Path $world 'serverconfig') -Recurse }

Write-Host "Moving client map data of the old world to a backup ..."
$clientBackup = Join-Path $Instance "world-reset-backup-$stamp"
Move-IfExists (Join-Path $Instance 'xaero/world-map/Multiplayer_localhost') (Join-Path $clientBackup 'xaero/world-map/Multiplayer_localhost')
Move-IfExists (Join-Path $Instance 'xaero/minimap/Multiplayer_localhost') (Join-Path $clientBackup 'xaero/minimap/Multiplayer_localhost')
Move-IfExists (Join-Path $Instance 'Distant_Horizons_server_data/Minecraft+Server') (Join-Path $clientBackup 'Distant_Horizons_server_data/Minecraft+Server')
foreach ($name in 'geology', 'mapcache') { Move-IfExists (Join-Path $Instance "kami/$name") (Join-Path $clientBackup "kami/$name") }
Write-Host "New world ready. Backups: $serverBackup and $clientBackup"
