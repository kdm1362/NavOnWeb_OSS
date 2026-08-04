[CmdletBinding()]
param(
    [string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug'),
    [string[]]$GradleArguments = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$wrapper = Join-Path $projectRoot 'gradlew.bat'

if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw "Gradle wrapper is missing: $wrapper"
}

Push-Location $projectRoot
try {
    & $wrapper @Tasks @GradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
