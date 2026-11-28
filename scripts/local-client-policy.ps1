# Locate the same marker used by the Python QA hosts and the Gradle client task.
function Assert-StarbridgeClientAllowed([string]$ProjectRoot) {
    $directory = [IO.DirectoryInfo]::new([IO.Path]::GetFullPath($ProjectRoot))
    while ($null -ne $directory) {
        $marker = Join-Path $directory.FullName '.tools/no-local-game-clients'
        if (Test-Path -LiteralPath $marker) { throw "Local graphical game tests are disabled on this workstation: $marker" }
        $link = Join-Path $directory.FullName '.git'
        if ([IO.File]::Exists($link)) {
            $pointer = [IO.File]::ReadAllText($link).Trim()
            if ($pointer.StartsWith('gitdir:')) {
                $metadata = [IO.Path]::GetFullPath([IO.Path]::Combine($directory.FullName, $pointer.Substring(7).Trim()))
                $common = Join-Path $metadata 'commondir'
                if ([IO.File]::Exists($common)) {
                    $shared = [IO.Path]::GetFullPath([IO.Path]::Combine($metadata, [IO.File]::ReadAllText($common).Trim()))
                    $marker = Join-Path ([IO.Path]::GetDirectoryName($shared)) '.tools/no-local-game-clients'
                    if (Test-Path -LiteralPath $marker) { throw "Local graphical game tests are disabled on this workstation: $marker" }
                }
            }
        }
        $directory = $directory.Parent
    }
}
