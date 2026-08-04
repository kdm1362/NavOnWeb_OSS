[CmdletBinding()]
param(
    [string]$InputPath,
    [string]$ResourceRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($InputPath)) {
    $InputPath = Join-Path $projectRoot 'app\src\main\res\drawable-nodpi\navonweb_icon.png'
}
if ([string]::IsNullOrWhiteSpace($ResourceRoot)) {
    $ResourceRoot = Join-Path $projectRoot 'app\src\main\res'
}

$resolvedInput = [IO.Path]::GetFullPath($InputPath)
$resolvedResourceRoot = [IO.Path]::GetFullPath($ResourceRoot)
if (-not (Test-Path -LiteralPath $resolvedInput -PathType Leaf)) {
    throw "Launcher icon source is missing: $resolvedInput"
}
$densitySizes = [ordered]@{
    'mipmap-mdpi' = 48
    'mipmap-hdpi' = 72
    'mipmap-xhdpi' = 96
    'mipmap-xxhdpi' = 144
    'mipmap-xxxhdpi' = 192
}

$source = [Drawing.Image]::FromFile($resolvedInput)
try {
    if ($source.Width -ne $source.Height) {
        throw "Launcher icon source must be square: $($source.Width)x$($source.Height)"
    }

    $drawableDirectory = Join-Path $resolvedResourceRoot 'drawable-nodpi'
    New-Item -ItemType Directory -Path $drawableDirectory -Force | Out-Null
    $canonicalSourcePath = [IO.Path]::GetFullPath((Join-Path $drawableDirectory 'navonweb_icon.png'))
    if (-not $resolvedInput.Equals($canonicalSourcePath, [StringComparison]::OrdinalIgnoreCase)) {
        Copy-Item -LiteralPath $resolvedInput -Destination $canonicalSourcePath -Force
    }

    foreach ($entry in $densitySizes.GetEnumerator()) {
        $directory = Join-Path $resolvedResourceRoot $entry.Key
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $size = [int]$entry.Value
        $bitmap = New-Object Drawing.Bitmap(
            $size,
            $size,
            [Drawing.Imaging.PixelFormat]::Format32bppArgb
        )
        try {
            $graphics = [Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.Clear([Drawing.Color]::Black)
                $graphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
                $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.DrawImage(
                    $source,
                    (New-Object Drawing.Rectangle(0, 0, $size, $size)),
                    0,
                    0,
                    $source.Width,
                    $source.Height,
                    [Drawing.GraphicsUnit]::Pixel
                )
            }
            finally {
                $graphics.Dispose()
            }

            foreach ($fileName in @('ic_launcher.png', 'ic_launcher_round.png')) {
                $bitmap.Save(
                    (Join-Path $directory $fileName),
                    [Drawing.Imaging.ImageFormat]::Png
                )
            }
        }
        finally {
            $bitmap.Dispose()
        }
    }
}
finally {
    $source.Dispose()
}

Write-Host "Generated launcher icons from $resolvedInput"
