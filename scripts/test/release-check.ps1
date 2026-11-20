<#
.SYNOPSIS
  The whole local check a release needs, in one run: parallel-test.ps1 -Graphics plus the multiplayer, crash, build-crash and planet-restart scripts.
.NOTES
  Compiles once. The optional scripts start as soon as the build outputs are ready and run in two lanes beside the parallel workers.
  Every part finishes before anything is reported, so one failure never hides another. The receipt (build/release-check/<run>/result.json)
  names the source fingerprint it certified; Publish-Release.ps1 refuses to tag sources that differ from it.
#>
param(
    # Four, because the release CI runs the native tests as four shards and each server worker is exactly one of them (see parallel-test.ps1).
    [ValidateRange(1, 8)] [int] $ServerWorkers = 4,
    [ValidateRange(1, 8)] [int] $GraphicsWorkers = 4,
    # Concurrent JVM pairs inside crash-test.ps1 and shipyard-crash-test.ps1.
    [ValidateRange(1, 8)] [int] $CrashWorkers = 2,
    # Run the optional scripts one after another, after the parallel check, instead of beside it.
    [switch] $Sequential,
    # An optional part starts only with this much memory free. Starting the build-crash JVMs with 5 GB free took the machine to 0.5 GB, it paged, and a
    # graphical test's server fell three minutes behind; held until 8 GB were free, a part waits for the graphical workers to finish instead.
    [ValidateRange(0, 64)] [double] $StartFreeGB = 8,
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
# The machine's load and free memory every five seconds, so that "is the CPU saturated?" is answered by the run's own numbers (cpu.csv here).
$sampler = Start-Job -ArgumentList (Join-Path $runRoot 'cpu.csv') -ScriptBlock {
    param($file)
    'time,cpuPercent,freeGB' | Set-Content -LiteralPath $file
    while ($true) {
        $cpu = (Get-CimInstance Win32_Processor | Measure-Object LoadPercentage -Average).Average
        $free = [Math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB, 1)
        ('{0},{1},{2}' -f (Get-Date -Format 'HH:mm:ss'), $cpu, $free) | Add-Content -LiteralPath $file
        Start-Sleep -Seconds 5
    }
}

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
    # Client JVMs that start in the same few seconds can freeze while they load (see Run-FocusedTests.ps1), and the parallel check is still starting
    # its own clients when it is ready: the lanes wait for that, and then start their parts one at a time, 20 seconds apart.
    $lanesMayStart = (Get-Date).AddSeconds(90)
    $lastStart = [DateTime]::MinValue
    $heldForMemory = $false
    while ($true) {
        foreach ($lane in $lanes) {
            if ($lane.current -and $lane.current.process.HasExited) {
                $finished += $lane.current
                $lane.current = $null
            }
            if (-not $lane.current -and $lane.queue.Count -gt 0 -and (Test-Path -LiteralPath $ready) -and (Get-Date) -gt $lanesMayStart -and ((Get-Date) - $lastStart).TotalSeconds -ge 20) {
                # Wait for memory while the parallel check runs; once it has finished, start regardless (nothing else will free memory).
                $freeGB = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
                if ($freeGB -lt $StartFreeGB -and -not $main.process.HasExited) {
                    if (-not $heldForMemory) { Write-Host ("Holding {0}: {1:N1} GB free, {2} GB needed to start" -f $lane.queue.Peek().name, $freeGB, $StartFreeGB) }
                    $heldForMemory = $true
                    continue
                }
                $heldForMemory = $false
                $next = $lane.queue.Dequeue()
                $lane.current = Start-Part $next.name $next.command
                $running += $lane.current
                $lastStart = Get-Date
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
    Stop-Job $sampler -ErrorAction SilentlyContinue; Remove-Job $sampler -Force -ErrorAction SilentlyContinue
    try {
        $samples = @(Import-Csv (Join-Path $runRoot 'cpu.csv') | Where-Object { $_.cpuPercent -ne '' })
        if ($samples.Count) {
            $cpuValues = @($samples | ForEach-Object { [double]$_.cpuPercent })
            $receipt.machine = [ordered]@{cpuAveragePercent = [Math]::Round(($cpuValues | Measure-Object -Average).Average, 1); cpuPeakPercent = ($cpuValues | Measure-Object -Maximum).Maximum; samplesAbove90Percent = @($cpuValues | Where-Object { $_ -ge 90 }).Count; samples = $cpuValues.Count; minFreeGB = ($samples | ForEach-Object { [double]$_.freeGB } | Measure-Object -Minimum).Minimum}
            Write-Host ('Machine during the run: CPU average {0}%, peak {1}%, {2} of {3} samples at 90% or more; least free memory {4} GB' -f $receipt.machine.cpuAveragePercent, $receipt.machine.cpuPeakPercent, $receipt.machine.samplesAbove90Percent, $receipt.machine.samples, $receipt.machine.minFreeGB)
        }
    } catch { }
    $receipt.finishedUtc = [DateTime]::UtcNow.ToString('o')
    $receipt.elapsedSeconds = [Math]::Round(([DateTime]::UtcNow - [DateTime]::Parse($receipt.startedUtc).ToUniversalTime()).TotalSeconds)
    Save-Receipt
    Write-Host "Release check $($receipt.status) in $([Math]::Round($receipt.elapsedSeconds / 60, 1)) minutes: $runRoot"
}
if ($receipt.status -ne 'passed') { exit 1 }
