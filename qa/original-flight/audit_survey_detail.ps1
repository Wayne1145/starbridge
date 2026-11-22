param([Parameter(Mandatory)][string]$Instance)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$side = Join-Path (Resolve-Path -LiteralPath $Instance).Path 'OrbitEngineer'
$check = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $side 'field-zoom-check.json') | ConvertFrom-Json
if (($check.spans -join ',') -ne '128,256,128,64,32' -or $check.zoomInAtClosest -or -not $check.zoomOutAtClosest) {
    throw 'The zoom controls did not reach both ends of the scale.'
}
$image = [System.Drawing.Bitmap]::new((Join-Path $side 'screenshots/field-one-block-scale.png'))
try {
    $colors = [ordered]@{}
    foreach ($kind in @('ledge', 'gentle')) {
        $point = $check.boundaries.$kind
        if ($null -eq $point) { throw "This view did not contain a $kind boundary to sample." }
        $drop = [Math]::Abs($point.heights[0] - $point.heights[1])
        $color = $image.GetPixel($point.pixel[0], $point.pixel[1]).ToArgb().ToString('x8')
        if (($drop -ge 2) -ne ($kind -eq 'ledge') -or ($color -eq 'ffe7b48d') -ne ($kind -eq 'ledge')) {
            throw "Unexpected $kind boundary with heights $($point.heights -join ',') and pixel $color."
        }
        $colors[$kind] = [ordered]@{ heights = $point.heights; pixel = $point.pixel; argb = $color }
    }
    [ordered]@{ spans = $check.spans; surfaceHeightAtCursor = $check.surfaceHeightAtCursor; observed = $colors; pass = $true } | ConvertTo-Json -Depth 6
} finally {
    $image.Dispose()
}
