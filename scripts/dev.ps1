param(
    [ValidateSet('build','test','client','server','gametest','prepare')]
    [string]$Task = 'build',
    [switch]$Ships,
    [switch]$Computer,
    [switch]$Unbound,
    [switch]$Offline,
    [string]$JavaHome,
    [ValidatePattern('^[a-zA-Z0-9_]{1,16}$')][string]$ClientName,
    [string]$ClientDir,
    [string]$Connect
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($Task -eq 'client') {
    . (Join-Path $PSScriptRoot 'local-client-policy.ps1')
    Assert-StarbridgeClientAllowed $projectRoot
}
$candidates = if ($JavaHome) { @($JavaHome) } else {
    @($env:JAVA_HOME, $env:JAVA_HOME_17_X64, 'C:\Program Files\Java\jdk-17')
}
$selectedJava = $null
foreach ($candidate in $candidates) {
    if (-not $candidate) { continue }
    $releaseFile = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) -and
        (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) -and
        (Test-Path -LiteralPath $releaseFile) -and
        (Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="17(?:\.|"|\+|-)' -Quiet)) {
        $selectedJava = $candidate
        break
    }
}
if (-not $selectedJava) {
    throw 'JDK 17 was not found. Set JAVA_HOME to JDK 17 or supply -JavaHome with its directory.'
}
$previousJava = $env:JAVA_HOME
$previousGradle = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $selectedJava
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user'
    $gradleTask = switch ($Task) {
        'client' { 'runClient' }
        'server' { 'runServer' }
        'gametest' { 'runGameTestServer' }
        'prepare' { 'prepareRuns' }
        default { $Task }
    }
    $arguments = @($gradleTask, '--console=plain', '--no-daemon')
    if ($Ships) { $arguments += '-PwithShips' }
    if ($Computer) { $arguments += '-PwithComputer' }
    if ($Unbound) { $arguments += '-PwithUnbound' }
    if ($Offline) { $arguments += '--offline' }
    if ($ClientName -or $ClientDir) {
        if ($Task -ne 'client') { throw '-ClientName and -ClientDir are only supported with -Task client.' }
        if ($ClientName) { $arguments += ('-PclientName=' + $ClientName) }
        if ($ClientDir) { $arguments += ('-PclientDir=' + $ClientDir) }
    }
    if ($Connect) {
        if ($Task -ne 'client') { throw '-Connect is only supported with -Task client.' }
        $arguments += ('-Pconnect=' + $Connect)
    }
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') @arguments
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:GRADLE_USER_HOME = $previousGradle
}
