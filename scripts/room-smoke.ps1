# Two isolated Minecraft processes. Tests never use account credentials or user worlds.
$ErrorActionPreference = 'Stop'
$roomExtraArguments = @($args) + @('-PisolatedSmokeBuild=true')
$roomProjectRoot = Split-Path -Parent $PSScriptRoot
$roomLauncher = Join-Path $PSScriptRoot 'dev.ps1'
$roomShell = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
if (!$roomShell) { $roomShell = (Get-Command powershell).Source }
$roomProcesses = @()
# Prepare once before launching both JVMs. NeoForm writes shared temporary artifacts
# and is not safe to run concurrently from two independent Gradle processes.
& $roomShell -NoProfile -ExecutionPolicy Bypass -File $roomLauncher clientSmokeClasses prepareRoomHostSmokeRun prepareRoomGuestSmokeRun --offline @roomExtraArguments
if ($LASTEXITCODE -ne 0) { throw 'Room smoke preparation failed.' }
foreach ($roomRole in @('host', 'guest')) {
    $roomDirectory = Join-Path $roomProjectRoot "runs/room-$roomRole-smoke"
    New-Item -ItemType Directory -Force -Path $roomDirectory | Out-Null
    foreach ($roomMarker in @('room-ready.txt', 'room-measured.txt', 'room-late.txt', 'room-import.txt', 'room-hud-action.txt', 'room-result.txt', 'ftb-stage.txt', 'shared-personal.txt', 'shared-group.txt', 'shared-denied.txt', 'shared-restored.txt', 'shared-disabled.txt', 'shared-enabled.txt', 'shared-expired.txt', 'shared-done.txt', 'admin-denied.txt', 'admin-qr.txt', 'admin-transport.txt', 'admin-revoked.txt', 'admin-done.txt')) {
        $roomMarkerPath = Join-Path $roomDirectory $roomMarker
        if (Test-Path -LiteralPath $roomMarkerPath) { Remove-Item -LiteralPath $roomMarkerPath }
    }
}
foreach ($roomRole in @('Host', 'Guest')) {
    $roomDirectory = Join-Path $roomProjectRoot "runs/room-$($roomRole.ToLower())-smoke"
    $roomArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $roomLauncher + '"'), "runRoom${roomRole}Smoke", '--offline', '--no-daemon')
    foreach ($roomPreparedTask in @('createMinecraftArtifacts', 'compileJava', 'compileClientSmokeJava', 'processResources', 'processClientSmokeResources', 'generateModMetadata', 'downloadAssets', 'prepareRoomHostSmokeRun', 'prepareRoomGuestSmokeRun', 'writeRoomHostSmokeLegacyClasspath', 'writeRoomGuestSmokeLegacyClasspath')) {
        $roomArguments += @('-x', $roomPreparedTask)
    }
    $roomArguments += $roomExtraArguments
    $roomProcess = Start-Process -FilePath $roomShell -ArgumentList $roomArguments -WorkingDirectory $roomProjectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $roomDirectory 'gradle-out.log') -RedirectStandardError (Join-Path $roomDirectory 'gradle-error.log')
    # Windows PowerShell only records ExitCode for processes whose handle was opened before they exit.
    $null = $roomProcess.Handle
    $roomProcesses += $roomProcess
}
Write-Host 'Room smoke running: two clients and loopback server (FTB lifecycle with -PftbTeams=true -PftbSmoke=true; UI only with -ProomUiSmoke=true).'
while ($roomProcesses | Where-Object { !$_.HasExited }) { Start-Sleep -Seconds 2 }
foreach ($roomRole in @('host', 'guest')) {
    $roomResult = Join-Path $roomProjectRoot "runs/room-$roomRole-smoke/room-result.txt"
    if (!(Test-Path -LiteralPath $roomResult) -or !(Get-Content -Raw -LiteralPath $roomResult).StartsWith('PASS:')) {
        throw "Room $roomRole failed. See runs/room-$roomRole-smoke logs."
    }
    Get-Content -LiteralPath $roomResult
}
if ($roomProcesses | Where-Object { $_.ExitCode -ne 0 }) { throw 'A room smoke Gradle process failed.' }
