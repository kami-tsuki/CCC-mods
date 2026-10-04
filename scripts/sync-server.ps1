# Mirrors my local modpack server for tests on the modpack i work on...
param([string]$Instance, [string]$Server)
. (Join-Path $PSScriptRoot "props.ps1")
if (-not $Instance) { $Instance = $Props.deploy_instance_dir }
if (-not $Server) { $Server = $Props.deploy_server_dir }

$skip = Get-Content (Join-Path $PSScriptRoot 'client-only.txt') | Where-Object { $_ -and -not $_.StartsWith('#') }
$mods = Join-Path $Server 'mods'
New-Item -ItemType Directory -Force $mods | Out-Null
Get-ChildItem $mods -Filter *.jar | Remove-Item -Force

Get-ChildItem (Join-Path $Instance 'mods') -Filter *.jar |
    Where-Object { $name = $_.Name; -not ($skip | Where-Object { $name -like "$_*" }) } |
    Copy-Item -Destination $mods

foreach ($folder in 'config', 'defaultconfigs', 'kubejs') {
    if ($folder -eq 'config') {
        # Chat Plus config is synced separately so edits on either side are not clobbered; Discord keys and links exist only on the server.
        robocopy (Join-Path $Instance $folder) (Join-Path $Server $folder) /MIR /XD chatplus /XF kami-discord-bot.json kami-discord-links.json kami-discord-codes.json /NFL /NDL /NJH /NJS /NP | Out-Null
        continue
    }
    robocopy (Join-Path $Instance $folder) (Join-Path $Server $folder) /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
}

& (Join-Path $PSScriptRoot 'sync-chatplus.ps1') -Instance $Instance -Server $Server | Out-Null

(Get-ChildItem $mods -Filter *.jar).Count
