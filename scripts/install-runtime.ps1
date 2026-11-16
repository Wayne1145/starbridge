param([string]$Proxy)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $root 'runtime\mods'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$artifacts = @(
    @{ Name='valkyrienskies-120-2.4.11.jar'; Url='https://cdn.modrinth.com/data/V5ujR2yw/versions/pOkdwuUh/valkyrienskies-120-2.4.11.jar'; Hash='6a0d959ed4bf76f08dcad0e6a360bda842d85386a05f510069eafbcc6b46b4216cdcfd9e377f82e4266b0f07e6b74118d8382af0bafcbdd65712f8a92f16e146' },
    @{ Name='kotlinforforge-4.11.0-all.jar'; Url='https://cdn.modrinth.com/data/ordsPcFz/versions/hmeyC41q/kotlinforforge-4.11.0-all.jar'; Hash='d72707078f0c4fde78981c638ca976722f22a64ce74bdb489258790fe9886d763a7b1e0dfbc5429ddd3105d8a65d177f06242dfb1457e219e56c70dd3e8cbf2c' }
)
foreach ($artifact in $artifacts) {
    $target = Join-Path $destination $artifact.Name
    if (Test-Path -LiteralPath $target) {
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA512).Hash.ToLowerInvariant() -eq $artifact.Hash) {
            Write-Output ('Verified: ' + $artifact.Name)
            continue
        }
        throw ('Existing file has unexpected content; preserve it and inspect before replacing: ' + $target)
    }
    $partial = $target + '.part'
    $request = @{ Uri=$artifact.Url; OutFile=$partial; TimeoutSec=180; UseBasicParsing=$true }
    if ($Proxy) { $request.Proxy = $Proxy }
    Invoke-WebRequest @request
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA512).Hash.ToLowerInvariant() -ne $artifact.Hash) {
        throw ('Download checksum mismatch; not installed: ' + $artifact.Name)
    }
    Move-Item -LiteralPath $partial -Destination $target
    Write-Output ('Installed in project runtime: ' + $artifact.Name)
}
Write-Output 'CC:Tweaked 1.116.2 is resolved by Gradle when -Computer is specified. Existing Minecraft folders were not modified.'
