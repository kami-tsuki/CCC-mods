# syncs my server, then launches a tests server and my client is ready to rumble as well :D
param([string]$Instance, [string]$Server)
. (Join-Path $PSScriptRoot "props.ps1")
if (-not $Instance) { $Instance = $Props.deploy_instance_dir }
if (-not $Server) { $Server = $Props.deploy_server_dir }

& (Join-Path $PSScriptRoot 'sync-server.ps1') -Instance $Instance -Server $Server

Start-Process -FilePath (Join-Path $Server 'run.bat') -WorkingDirectory $Server
