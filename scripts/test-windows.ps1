param(
    [string]$Gradle = '',
    [string]$StagingParent = [System.IO.Path]::GetTempPath(),
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$parent = (Resolve-Path -LiteralPath $StagingParent).Path
if ($parent -match '[^\x00-\x7F]') {
    throw 'Choose an existing ASCII-only directory with -StagingParent for the Gradle test worker.'
}
if ($Gradle) {
    $Gradle = (Resolve-Path -LiteralPath $Gradle).Path
}
$stage = Join-Path $parent ('dafeiyu-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
try {
    foreach ($file in 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew.bat', 'gradle') {
        Copy-Item -LiteralPath (Join-Path $root $file) -Destination $stage -Recurse
    }
    $app = New-Item -ItemType Directory -Path (Join-Path $stage 'app')
    Copy-Item -LiteralPath (Join-Path $root 'app/build.gradle.kts') -Destination $app.FullName
    Copy-Item -LiteralPath (Join-Path $root 'app/src') -Destination $app.FullName -Recurse
    $gradleArgs = @('-p', $stage, '--no-daemon', '--max-workers=3', 'testDebugUnitTest')
    if ($Offline) { $gradleArgs += '--offline' }
    if (!$Gradle) { $Gradle = Join-Path $stage 'gradlew.bat' }
    & $Gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle tests failed. Inspect the retained staging directory: $stage"
    }
    Get-ChildItem -LiteralPath (Join-Path $stage 'app/build/test-results/testDebugUnitTest') -Filter 'TEST-*.xml' |
        ForEach-Object { ([xml](Get-Content -Raw -LiteralPath $_.FullName)).testsuite } |
        Select-Object name, tests, failures, errors
    $passed = $true
} finally {
    if ($passed) {
        $target = (Resolve-Path -LiteralPath $stage).Path
        if ([IO.Path]::GetDirectoryName($target) -eq $parent -and [IO.Path]::GetFileName($target) -match '^dafeiyu-tests-[0-9a-f]{32}$') {
            Remove-Item -LiteralPath $target -Recurse -Force
        }
    }
}