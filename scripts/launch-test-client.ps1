param([string]$JavaHome, [switch]$Offline)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (Test-Path -LiteralPath (Join-Path $root '.tools/no-local-game-clients')) {
    throw 'Local graphical game tests are disabled on this workstation.'
}
Set-Location -LiteralPath $root
New-Item -ItemType Directory -Path 'run-client' -Force | Out-Null
New-Item -ItemType Directory -Path 'build' -Force | Out-Null
if (-not (Test-Path -LiteralPath 'run-client/options.txt')) {
    @('lang:zh_cn','renderDistance:8','simulationDistance:6','guiScale:2','fullscreen:false',
      'overrideWidth:1280','overrideHeight:800','pauseOnLostFocus:false','soundCategory_master:0.2') |
      Set-Content -LiteralPath 'run-client/options.txt' -Encoding ascii
}
& (Join-Path $PSScriptRoot 'dev.ps1') -Task client -Ships -Computer -JavaHome $JavaHome -Offline:$Offline *> (Join-Path $root 'build/client-console.log')
