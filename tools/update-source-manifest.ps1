[CmdletBinding()]
param(
    [string]$RepositoryRoot
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    Split-Path -Parent $PSScriptRoot
} else {
    $RepositoryRoot
}
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$manifestPath = Join-Path $root "SOURCE_MANIFEST.sha256"
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$indexExportRoot = [IO.Path]::GetFullPath(
    (Join-Path $temporaryRoot ("NavOnWebSourceManifest-" + [Guid]::NewGuid().ToString("N")))
)

$gitCommand = Get-Command git -ErrorAction Stop
$relativePaths = @(
    & $gitCommand.Source -C $root ls-files --cached
)
if ($LASTEXITCODE -ne 0) {
    throw "git ls-files failed with exit code $LASTEXITCODE"
}

if (-not $indexExportRoot.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Index export path escaped the temporary directory: $indexExportRoot"
}

try {
    New-Item -ItemType Directory -Path $indexExportRoot | Out-Null
    $gitPrefix = $indexExportRoot.Replace("\", "/").TrimEnd("/") + "/"
    & $gitCommand.Source -C $root checkout-index --all --force "--prefix=$gitPrefix"
    if ($LASTEXITCODE -ne 0) {
        throw "git checkout-index failed with exit code $LASTEXITCODE"
    }

    $sourcePaths = @(
        $relativePaths |
            Where-Object {
                -not [string]::IsNullOrWhiteSpace($_) -and
                $_ -ne "SOURCE_MANIFEST.sha256"
            } |
            Sort-Object -Unique
    )
    $lines = foreach ($relativePath in $sourcePaths) {
        $candidate = Join-Path $indexExportRoot $relativePath
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "Tracked index file is missing from the export: $relativePath"
        }
        $hash = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relativePath"
    }

    [System.IO.File]::WriteAllText(
        $manifestPath,
        (($lines -join "`n") + "`n"),
        [System.Text.UTF8Encoding]::new($false)
    )

    Write-Host "Wrote $($lines.Count) staged-index entries to $manifestPath"
}
finally {
    $resolvedExportRoot = [IO.Path]::GetFullPath($indexExportRoot)
    $safeLeaf = (Split-Path -Leaf $resolvedExportRoot).StartsWith(
        "NavOnWebSourceManifest-",
        [StringComparison]::Ordinal
    )
    $insideTemp = $resolvedExportRoot.StartsWith(
        $temporaryRoot,
        [StringComparison]::OrdinalIgnoreCase
    )
    if ($insideTemp -and $safeLeaf -and (Test-Path -LiteralPath $resolvedExportRoot)) {
        Remove-Item -LiteralPath $resolvedExportRoot -Recurse -Force
    }
}
