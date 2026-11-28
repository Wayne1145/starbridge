# Run Serena against this checkout without Eclipse probing every locally installed JDK.
# Examples: ./scripts/serena.ps1 project index . ; ./scripts/serena.ps1 memories read start-here .
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localCli = Join-Path $projectRoot '.tools/uv-tools/serena-agent/Scripts/serena.exe'
$serenaCommand = if (Test-Path -LiteralPath $localCli) { $localCli } else { (Get-Command serena -CommandType Application -ErrorAction Stop).Source }
$previousOptions = $env:JAVA_TOOL_OPTIONS
$previousPythonIo = $env:PYTHONIOENCODING
$previousSerenaHome = $env:SERENA_HOME
$localSerenaHome = Join-Path $projectRoot '.tools/serena-home'
try {
    # Match the encoding PowerShell uses to decode native pipes so Chinese memories survive redirection.
    $OutputEncoding = [Console]::OutputEncoding
    $env:PYTHONIOENCODING = "cp$($OutputEncoding.CodePage):backslashreplace"
    # This checkout's bundled installation registers the project in its own configuration.
    if (-not $previousSerenaHome -and (Test-Path -LiteralPath (Join-Path $localSerenaHome 'serena_config.yml'))) {
        $env:SERENA_HOME = $localSerenaHome
    }
    $env:JAVA_TOOL_OPTIONS = "$previousOptions -DDetectVMInstallationsJob.disabled=true".Trim()
    Push-Location -LiteralPath $projectRoot
    try {
        if ($MyInvocation.ExpectingInput) { $input | & $serenaCommand @args }
        else { & $serenaCommand @args }
        $result = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    $env:PYTHONIOENCODING = $previousPythonIo
    $env:JAVA_TOOL_OPTIONS = $previousOptions
    $env:SERENA_HOME = $previousSerenaHome
}
exit $result
