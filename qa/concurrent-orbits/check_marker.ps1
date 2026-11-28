# Check the pixels a player actually saw in the big map, independently of the UI state packet.
param([Parameter(Mandatory)][string]$Run)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$finished = Join-Path $Run 'server/berth-rounds.json'
if (Test-Path -LiteralPath $finished) {
    $round = @(Get-Content -LiteralPath $finished -Encoding utf8 -Raw | ConvertFrom-Json)[0]
} else {
    $round = Get-Content -LiteralPath (Join-Path $Run 'server/berth-live.json') -Encoding utf8 -Raw | ConvertFrom-Json
}
if ($round.round -ne 0 -or $round.deniedPilot -notin @('OrbitCaptain', 'OrbitEngineer')) { throw 'The server has not recorded the rejected captain.' }
$shots = Join-Path $Run ($round.deniedPilot + '/screenshots')

function Measure-MapMark([string]$Name, [bool]$Blocked) {
    $bitmap = [Drawing.Bitmap]::new((Resolve-Path -LiteralPath (Join-Path $shots $Name)).Path)
    try {
        # The two real QA clients run the same 840 x 480 window with GUI scale 2.
        if ($bitmap.Width -ne 840 -or $bitmap.Height -ne 480) { throw "Unexpected instrument dimensions in $Name" }
        $amber = [Drawing.Color]::FromArgb(255, 255, 200, 130).ToArgb()
        $mint = [Drawing.Color]::FromArgb(255, 131, 224, 203).ToArgb()
        $expected = if ($Blocked) { $amber } else { $mint }
        $unexpected = if ($Blocked) { $mint } else { $amber }
        $minX = $bitmap.Width; $maxX = -1; $minY = $bitmap.Height; $maxY = -1
        $count = 0; $other = 0
        # Left overview only. The text panel, buttons, and readout below the map are outside it.
        for ($y = 60; $y -lt 350; $y++) {
            for ($x = 64; $x -lt 354; $x++) {
                $rgb = $bitmap.GetPixel($x, $y).ToArgb()
                if ($rgb -eq $unexpected) { $other++ }
                if ($rgb -ne $expected) { continue }
                $count++
                $minX = [Math]::Min($minX, $x); $maxX = [Math]::Max($maxX, $x)
                $minY = [Math]::Min($minY, $y); $maxY = [Math]::Max($maxY, $y)
            }
        }
        if ($count -lt 20 -or $other -ne 0) { throw "Missing or misleading clearance symbol in $Name ($count matching pixels, $other opposite pixels)." }
        $midX = [int][Math]::Floor(($maxX + $minX) / 2)
        $midY = [int][Math]::Floor(($maxY + $minY) / 2)
        $centerFilled = $bitmap.GetPixel($midX, $midY).ToArgb() -eq $expected
        $corners = 0
        foreach ($x in @($minX, $maxX)) { foreach ($y in @($minY, $maxY)) { if ($bitmap.GetPixel($x, $y).ToArgb() -eq $expected) { $corners++ } } }
        if ($centerFilled -ne $Blocked -or $corners -ne 4) { throw "Unexpected symbol shape in $Name" }
        return [ordered]@{ screenshot = $Name; matchingPixels = $count; oppositePixels = $other; corners = $corners; centerFilled = $centerFilled; bounds = @($minX, $minY, $maxX, $maxY) }
    } finally { $bitmap.Dispose() }
}

[ordered]@{
    occupied = Measure-MapMark 'occupied-0.png' $true
    reselected = Measure-MapMark 'correct-ready-0.png' $false
} | ConvertTo-Json -Depth 4
