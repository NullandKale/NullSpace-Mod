<#
.SYNOPSIS
  The whole local check a release needs, in one run: parallel-test.ps1 -Graphics plus the multiplayer, crash, build-crash and planet-restart scripts.
.NOTES
  Compiles once. The optional scripts start as soon as the build outputs are ready and run in two lanes beside the parallel workers.
  Every part finishes before anything is reported, so one failure never hides another. The receipt (build/release-check/<run>/result.json)
  names the source fingerprint it certified; Publish-Release.ps1 refuses to tag sources that differ from it.
#>
param(
    [ValidateRange(1, 8)] [int] $ServerWorkers = 3,
    [ValidateRange(1, 8)] [int] $GraphicsWorkers = 4,
    # Concurrent JVM pairs inside crash-test.ps1 and shipyard-crash-test.ps1.
    [ValidateRange(1, 8)] [int] $CrashWorkers = 2,
    # Run the optional scripts one after another, after the parallel check, instead of beside it.
    [switch] $Sequential,
    [ValidateRange(10, 240)] [int] $TimeoutMinutes = 90
)
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $RepoRoot 'scripts/lib/LocalVerification.psm1') -Force -DisableNameChecking
$runRoot = Join-Path $RepoRoot ('build/release-check/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
$null = New-Item -ItemType Directory -Path $runRoot
$shell = (Get-Process -Id $PID).Path
$fingerprint = Get-VerificationFingerprint $RepoRoot
$commit = (& git -C $RepoRoot rev-parse HEAD).Trim()
$receipt = [ordered]@{status = 'running'; startedUtc = [DateTime]::UtcNow.ToString('o'); commit = $commit; inputsSha256 = $fingerprint; parts = @()}
function Save-Receipt { Write-VerificationJson (Join-Path $runRoot 'result.json') $receipt }

function Start-Part([string] $Name, [string] $Command) {
    $part = [pscustomobject]@{name = $Name; started = [DateTime]::UtcNow; process = $null; log = (Join-Path $runRoot "$Name.log")}
    $part.process = Start-Process -FilePath $shell -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', ('"' + $Command.Replace('"', '\"') + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput $part.log -RedirectStandardError (Join-Path $runRoot "$Name.err.log")
    $null = $part.process.Handle
    Write-Host ("Started {0}" -f $Name)
    return $part
}

$scripts = Join-Path $RepoRoot 'scripts/test'
$ready = Join-Path $runRoot 'parallel-ready'
# Crash is the longest of the optional scripts, so it has a lane to itself; the other three share the second.
$laneA = @(
    @{name = 'multiplayer'; command = "& '$scripts/multiplayer-test.ps1' -SkipBuild -ArtifactRoot '$runRoot/multiplayer'"},
    @{name = 'planet-restart'; command = "& '$scripts/planet-restart-test.ps1' -SkipBuild -ArtifactRoot '$runRoot/planet-restart'"},
    @{name = 'shipyard-crash'; command = "& '$scripts/shipyard-crash-test.ps1' -SkipBuild -Workers $CrashWorkers -ArtifactRoot '$runRoot/shipyard-crash'"}
)
$laneB = @(
    @{name = 'crash'; command = "& '$scripts/crash-test.ps1' -SkipBuild -Workers $CrashWorkers -ArtifactRoot '$runRoot/crash'"}
)
$running = @()
$finished = @()
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
try {
    Save-Receipt
    $main = Start-Part 'parallel' ("& '$scripts/parallel-test.ps1' -Graphics -ServerWorkers $ServerWorkers -GraphicsWorkers $GraphicsWorkers -ReadyFile '$ready'")
    $running += $main
    # The optional scripts reuse the launch files that the parallel run exports, so they wait for its build.
    while (-not (Test-Path -LiteralPath $ready) -and -not $main.process.HasExited) {
        if ((Get-Date) -gt $deadline) { throw 'Timed out waiting for the build' }
        Start-Sleep -Seconds 2
    }
    if (-not (Test-Path -LiteralPath $ready)) { $main.process.WaitForExit(); throw "The parallel check ended before it was ready to share the build (exit $($main.process.ExitCode)). See $($main.log) and its .err.log" }
    $lanes = @(
        [pscustomobject]@{queue = [System.Collections.Generic.Queue[object]]::new(@($laneA)); current = $null},
        [pscustomobject]@{queue = [System.Collections.Generic.Queue[object]]::new(@($laneB)); current = $null}
    )
    if ($Sequential) {
        $lanes = @([pscustomobject]@{queue = [System.Collections.Generic.Queue[object]]::new(@($laneA + $laneB)); current = $null})
        $main.process.WaitForExit()
    }
    while ($true) {
        foreach ($lane in $lanes) {
            if ($lane.current -and $lane.current.process.HasExited) {
                $finished += $lane.current
                $lane.current = $null
            }
            if (-not $lane.current -and $lane.queue.Count -gt 0 -and (Test-Path -LiteralPath $ready)) {
                $next = $lane.queue.Dequeue()
                $lane.current = Start-Part $next.name $next.command
                $running += $lane.current
            }
        }
        $busy = @($lanes | Where-Object { $_.current -or $_.queue.Count }).Count
        if (-not $busy -and $main.process.HasExited) { break }
        if ((Get-Date) -gt $deadline) { throw "Release check timed out after $TimeoutMinutes minutes. Logs: $runRoot" }
        Start-Sleep -Seconds 2
    }
    $finished += $main
    foreach ($part in $finished) {
        $part.process.WaitForExit()
        $receipt.parts += [ordered]@{
            name = $part.name
            status = $(if ($part.process.ExitCode -eq 0) { 'passed' } else { 'failed' })
            exitCode = $part.process.ExitCode
            seconds = [Math]::Round(($part.process.ExitTime.ToUniversalTime() - $part.started).TotalSeconds)
            log = $part.log
        }
        Write-Host ("{0,-16} {1}" -f $part.name, $receipt.parts[-1].status)
    }
    $failed = @($receipt.parts | Where-Object status -ne 'passed')
    if (Test-Path -LiteralPath $ready) { $receipt.parallelRun = (Get-Content -LiteralPath $ready -Raw).Trim() }
    Assert-VerificationFingerprint $RepoRoot $fingerprint
    $receipt.status = if ($failed.Count) { 'failed' } else { 'passed' }
    if ($failed.Count) { $receipt.error = 'Failed: ' + (($failed | ForEach-Object name) -join ', ') }
} catch {
    $receipt.status = 'failed'; $receipt.error = $_.Exception.Message
    throw
} finally {
    foreach ($part in $running) { if (-not $part.process.HasExited) { Stop-Process -Id $part.process.Id -ErrorAction SilentlyContinue } }
    $receipt.finishedUtc = [DateTime]::UtcNow.ToString('o')
    $receipt.elapsedSeconds = [Math]::Round(([DateTime]::UtcNow - [DateTime]::Parse($receipt.startedUtc).ToUniversalTime()).TotalSeconds)
    Save-Receipt
    Write-Host "Release check $($receipt.status) in $([Math]::Round($receipt.elapsedSeconds / 60, 1)) minutes: $runRoot"
}
if ($receipt.status -ne 'passed') { exit 1 }
