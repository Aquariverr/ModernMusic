# Project-scoped Java 17 launcher. No permanent environment changes.
$ErrorActionPreference = 'Stop'
$taskArgs = @($args)
if ($taskArgs.Count -eq 0) { $taskArgs = @('build') }
$projectRoot = Split-Path -Parent $PSScriptRoot
$localJavaFile = Join-Path $projectRoot '.local/java-home.txt'

function Test-Jdk17([string] $candidate) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { return $false }
    $releaseFile = Join-Path $candidate 'release'
    if (!(Test-Path -LiteralPath $releaseFile)) { return $false }
    if (!(Test-Path -LiteralPath (Join-Path $candidate 'bin/javac.exe'))) { return $false }
    return [bool]((Get-Content -LiteralPath $releaseFile) -match '^JAVA_VERSION="17(?:\.|\+|")')
}

$jdkCandidates = @()
if ($env:MODERN_MUSIC_JAVA_HOME) { $jdkCandidates += $env:MODERN_MUSIC_JAVA_HOME }
if (Test-Path -LiteralPath $localJavaFile) { $jdkCandidates += (Get-Content -Raw -LiteralPath $localJavaFile).Trim() }
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
foreach ($jdkRoot in @("$env:ProgramFiles/Java", "$env:ProgramFiles/Eclipse Adoptium", "$env:ProgramFiles/Microsoft", "$env:USERPROFILE/.jdks")) {
    if (Test-Path -LiteralPath $jdkRoot) {
        $jdkCandidates += Get-ChildItem -LiteralPath $jdkRoot -Directory | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName
    }
}
$selectedJdk = $jdkCandidates | Where-Object { Test-Jdk17 $_ } | Select-Object -First 1
if (!$selectedJdk) { throw 'Java 17 JDK not found. Set MODERN_MUSIC_JAVA_HOME to a JDK 17 directory.' }

if (($taskArgs -contains 'runServer') -or ($taskArgs -contains ':runServer')) {
    $serverDirectory = Join-Path $projectRoot 'runs/server'
    $serverProperties = Join-Path $serverDirectory 'server.properties'
    if (!(Test-Path -LiteralPath $serverProperties)) {
        New-Item -ItemType Directory -Force -Path $serverDirectory | Out-Null
        # Development servers use test identities; bind new instances locally.
        Set-Content -LiteralPath $serverProperties -Value 'server-ip=127.0.0.1' -Encoding ascii
    }
}

if (($taskArgs -contains 'runRoomServerSmoke') -or ($taskArgs -contains ':runRoomServerSmoke')) {
    $smokeServerDirectory = Join-Path $projectRoot 'runs/room-server-smoke'
    $smokeServerProperties = Join-Path $smokeServerDirectory 'server.properties'
    if (!(Test-Path -LiteralPath $smokeServerProperties)) {
        New-Item -ItemType Directory -Force -Path $smokeServerDirectory | Out-Null
        Set-Content -LiteralPath $smokeServerProperties -Value @('server-ip=127.0.0.1', 'server-port=25576') -Encoding ascii
    }
}

$previousJavaHome = $env:JAVA_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
$previousPath = $env:PATH
$resultCode = 1
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $selectedJdk
    $env:PATH = "$(Join-Path $selectedJdk 'bin');$previousPath"
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
    Write-Host "Java 17: $selectedJdk"
    & (Join-Path $projectRoot 'gradlew.bat') @taskArgs
    $resultCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:PATH = $previousPath
    Pop-Location
}
exit $resultCode
