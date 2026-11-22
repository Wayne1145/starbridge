param([string]$JavaHome, [switch]$Offline, [string]$Server = '127.0.0.1:25579')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (Test-Path -LiteralPath (Join-Path $root '.tools/no-local-game-clients')) {
    throw 'Local graphical game tests are disabled on this workstation.'
}
Set-Location -LiteralPath $root
New-Item -ItemType Directory -Path 'build' -Force | Out-Null
& (Join-Path $PSScriptRoot 'dev.ps1') -Task client -Ships -Computer -JavaHome $JavaHome -Offline:$Offline -Connect $Server *> (Join-Path $root 'build/observer-client-console.log')
