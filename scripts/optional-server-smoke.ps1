# Real connection regression: only the client loads Modern Music. Uses isolated test worlds and silent fixture audio.
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Run this development smoke with PowerShell 7 (pwsh), required for reliable child-process cleanup.' }
$optionalExtraArguments = @($args) + @('-PisolatedSmokeBuild=true')
$optionalProjectRoot = Split-Path -Parent $PSScriptRoot
$optionalLauncher = Join-Path $PSScriptRoot 'dev.ps1'
$optionalShell = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
if (!$optionalShell) { $optionalShell = (Get-Command powershell).Source }
$optionalProcesses = @()
& $optionalShell -NoProfile -ExecutionPolicy Bypass -File $optionalLauncher clientSmokeClasses optionalServerSmokeClasses prepareOptionalClientSmokeRun prepareOptionalServerSmokeRun --offline @optionalExtraArguments
if ($LASTEXITCODE -ne 0) { throw 'Optional-server smoke preparation failed.' }
foreach ($optionalSide in @('client', 'server')) {
    $optionalDirectory = Join-Path $optionalProjectRoot "runs/optional-$optionalSide-smoke"
    New-Item -ItemType Directory -Force -Path $optionalDirectory | Out-Null
    Set-Content -LiteralPath (Join-Path $optionalDirectory 'optional-result.txt') -Value 'RUNNING' -Encoding ascii
    $optionalReady = Join-Path $optionalDirectory 'optional-ready.txt'
    if (Test-Path -LiteralPath $optionalReady) { Remove-Item -LiteralPath $optionalReady }
}
$optionalServerDirectory = Join-Path $optionalProjectRoot 'runs/optional-server-smoke'
Set-Content -LiteralPath (Join-Path $optionalServerDirectory 'server.properties') -Value @(
    'server-ip=127.0.0.1', 'server-port=25577', 'online-mode=false', 'level-name=compatibility-world',
    'view-distance=2', 'simulation-distance=5', 'max-players=2', 'spawn-protection=0', 'sync-chunk-writes=false', 'enable-rcon=false'
) -Encoding ascii
try {
    foreach ($optionalSide in @('Server', 'Client')) {
        $optionalDirectory = Join-Path $optionalProjectRoot "runs/optional-$($optionalSide.ToLower())-smoke"
        $optionalArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $optionalLauncher + '"'), "runOptional${optionalSide}Smoke", '--offline', '--no-daemon')
        foreach ($optionalPreparedTask in @('createMinecraftArtifacts', 'compileJava', 'compileClientSmokeJava', 'compileOptionalServerSmokeJava', 'processResources', 'processClientSmokeResources', 'processOptionalServerSmokeResources', 'generateModMetadata', 'downloadAssets', 'prepareOptionalClientSmokeRun', 'prepareOptionalServerSmokeRun', 'writeOptionalClientSmokeLegacyClasspath', 'writeOptionalServerSmokeLegacyClasspath')) {
            $optionalArguments += @('-x', $optionalPreparedTask)
        }
        $optionalArguments += $optionalExtraArguments
        $optionalProcess = Start-Process -FilePath $optionalShell -ArgumentList $optionalArguments -WorkingDirectory $optionalProjectRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $optionalDirectory 'gradle-out.log') -RedirectStandardError (Join-Path $optionalDirectory 'gradle-error.log')
        $null = $optionalProcess.Handle
        $optionalProcesses += $optionalProcess
    }
    Write-Host 'Optional-server smoke running: client with Modern Music, dedicated server without Modern Music, then integrated-world restore and reconnect.'
    $optionalDeadline = [DateTime]::UtcNow.AddMinutes(9)
    while ($optionalProcesses | Where-Object { !$_.HasExited }) {
        if ($optionalProcesses | Where-Object { $_.HasExited -and $_.ExitCode -ne 0 }) { throw 'An optional-server smoke process failed; see its logs.' }
        if ([DateTime]::UtcNow -gt $optionalDeadline) { throw 'Optional-server smoke timed out.' }
        Start-Sleep -Seconds 2
    }
    foreach ($optionalSide in @('client', 'server')) {
        $optionalResult = Join-Path $optionalProjectRoot "runs/optional-$optionalSide-smoke/optional-result.txt"
        if (!(Test-Path -LiteralPath $optionalResult) -or !(Get-Content -Raw -LiteralPath $optionalResult).StartsWith('PASS:')) {
            throw "Optional-server $optionalSide failed. See runs/optional-$optionalSide-smoke logs."
        }
        Get-Content -LiteralPath $optionalResult
    }
    if ($optionalProcesses | Where-Object { $_.ExitCode -ne 0 }) { throw 'An optional-server smoke Gradle process failed.' }
} finally {
    foreach ($optionalProcess in $optionalProcesses) {
        if (!$optionalProcess.HasExited) { $optionalProcess.Kill($true) }
    }
}
