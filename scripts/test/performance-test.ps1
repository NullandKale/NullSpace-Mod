param(
    [ValidateRange(8, 300)][int[]] $Ships = @(40),
    [ValidateSet('idle','store-save','world-save','move','move-burst','build','record','startup')][string[]] $Scenarios = @('idle','store-save','world-save','move','move-burst','build','record','startup'),
    [ValidateRange(1, 10)][int] $Repeat = 3,
    [int[]] $StartupJournals = @(0, 100, 1000),
    [ValidateRange(1, 120)][int] $WarmupSeconds = 60,
    [ValidateRange(1, 120)][int] $StepSeconds = 30,
    [ValidateRange(1, 120)][int] $FlatSeconds = 30,
    [ValidateRange(1, 240)][int] $IdleSeconds = 120,
    [string[]] $Compare = @(),
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string] $Label = 'current',
    [string] $ArtifactRoot = '',
    [switch] $KeepWorlds
)
# Opt-in save and journal performance test (docs/development/performance-test.md). Never part of build, check or CI.
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ErrorActionPreference = 'Stop'
if ($Compare.Count) {
    if ($Compare.Count -ne 2) { throw '-Compare needs two run directories' }
    & python (Join-Path $PSScriptRoot 'compare-performance.py') @Compare
    exit $LASTEXITCODE
}
if (@($StartupJournals | Where-Object { $_ -lt 0 -or $_ -gt 10000 }).Count) { throw 'Startup journal counts must be between 0 and 10000' }

Import-Module (Join-Path $RepoRoot 'scripts/lib/LocalVerification.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $RepoRoot 'scripts/lib/TestWorldCleanup.psm1') -Force
& "$RepoRoot/scripts/build/Invoke-Gradle.ps1" exportLocalServerLaunch --no-configuration-cache --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$spec = Get-Content -LiteralPath "$RepoRoot/build/local-server-launch.json" -Raw | ConvertFrom-Json
$runRoot = Join-Path $RepoRoot ('build/performance/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Label)
if ($ArtifactRoot) { $runRoot = [System.IO.Path]::GetFullPath($ArtifactRoot) }
$null = New-Item -ItemType Directory -Path $runRoot
$commit = (git -C $RepoRoot rev-parse --short HEAD).Trim()
$dirty = [bool](git -C $RepoRoot status --porcelain)
$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name
$disk = (Get-PhysicalDisk | Select-Object -First 1 | ForEach-Object { "$($_.FriendlyName) $($_.MediaType)" })
$cases = @()
$fingerprint = Get-VerificationFingerprint $RepoRoot
$receipt = [ordered]@{ status='running'; label=$Label; commit=$commit; uncommittedChanges=$dirty; cpu=$cpu; disk=$disk; ships=@($Ships); scenarios=@($Scenarios); repeat=$Repeat; cases=@(); inputsSha256=$fingerprint; startupJournals=@($StartupJournals); warmupSeconds=$WarmupSeconds; stepSeconds=$StepSeconds; flatSeconds=$FlatSeconds; idleSeconds=$IdleSeconds }
Write-VerificationJson (Join-Path $runRoot 'result.json') $receipt
function Invoke-PerformanceProcess([string] $Work, [string] $Scenario, [int] $Count, [string] $Output, [int] $JournalCount, [int] $ExpectedExit = 0, [string] $Phase = 'run') {
    $arguments = @($spec.jvmArgs | Where-Object { -not $_.StartsWith('@') })
    $arguments += @('-Xms4G', '-Xmx4G', "-Dnullspace.perf.scenario=$Scenario", "-Dnullspace.perf.ships=$Count", "-Dnullspace.perf.out=$Output", "-Dnullspace.perf.journals=$JournalCount", "-Dnullspace.perf.processStartedMillis=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())", "-Dnullspace.perf.warmupSeconds=$WarmupSeconds", "-Dnullspace.perf.stepSeconds=$StepSeconds", "-Dnullspace.perf.flatSeconds=$FlatSeconds", "-Dnullspace.perf.idleSeconds=$IdleSeconds", '-Dfabric-api.gametest.filter=nullspace-test:performance_game_test_run', '-cp', $spec.classpath, $spec.mainClass)
    $arguments += @($spec.args) + @('--universe', $Work)
    $argFile = Join-Path $Work "$Phase.args"
    Write-JavaArguments $argFile $arguments
    $child = Start-Process -FilePath $spec.executable -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory $Work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Work "$Phase.log") -RedirectStandardError (Join-Path $Work "$Phase.err.log")
    $null = $child.Handle
    try {
        $deadline = [DateTime]::UtcNow.AddMinutes(30)
        while (-not $child.WaitForExit(1000)) {
            if ([DateTime]::UtcNow -gt $deadline) { throw "Performance phase timed out: $Scenario. Logs: $Work" }
        }
        if ($child.ExitCode -ne $ExpectedExit) { throw "Performance phase failed: $Scenario (exit $($child.ExitCode), expected $ExpectedExit). Logs: $Work" }
    } finally {
        if (-not $child.HasExited) { Stop-Process -Id $child.Id -ErrorAction SilentlyContinue; $child.WaitForExit() }
    }
    Assert-VerificationFingerprint $RepoRoot $fingerprint
}

try {
foreach ($count in $Ships) {
foreach ($scenario in $Scenarios) {
for ($attempt = 1; $attempt -le $Repeat; $attempt++) {
foreach ($journalCount in $(if ($scenario -eq 'startup') { $StartupJournals } else { @(-1) })) {
    $variant = if ($scenario -eq 'startup') { "startup-journals$journalCount" } else { $scenario }
    $case = "$variant-$count-run$attempt"
    $work = Join-Path $runRoot $case
    $null = New-Item -ItemType Directory -Force -Path $work
    [System.IO.File]::WriteAllText((Join-Path $work 'eula.txt'), "eula=true`n")
    $result = Join-Path $work 'result.json'
    $process = $null
    try {
        Write-Host "Performance: $case"
        if ($scenario -eq 'startup') {
            Invoke-PerformanceProcess $work 'startup-fixture' $count (Join-Path $work 'fixture.json') 0 0 'fixture'
            Invoke-PerformanceProcess $work 'startup-seed' $count $result $journalCount 86 'seed'
        }
        Invoke-PerformanceProcess $work $scenario $count $result $journalCount 0 'measure'
        if (-not (Test-Path -LiteralPath $result)) { throw "Missing performance result: $case" }
        $cases += $case
        $receipt.cases = @($cases)
        Write-VerificationJson (Join-Path $runRoot 'result.json') $receipt
    } finally {
        if (-not $KeepWorlds) {
            try {
                $ids = if ($process) { @($process.Id) } else { @() }
                $null = @(Remove-TestWorlds -Workspace $RepoRoot -WorkRoot $work -ProcessIds $ids)
            } catch { Write-Warning "Test world cleanup failed: $($_.Exception.Message)" }
        }
    }
}
}
}
}
# One summary table per scenario: the median of the repeats for each ramp step.
$lines = @("# Performance run $Label", "", "Commit $commit$(if ($dirty) { ' (uncommitted changes)' }), $cpu, $disk, Java $($spec.executable)", "")
foreach ($scenario in @($cases | ForEach-Object { $_ -replace '-\d+-run\d+$', '' } | Select-Object -Unique)) {
    $lines += "## $scenario"
    $lines += ""
    $lines += "| Ships | Step | Ops/s | Op ms (median / p95 / max) | Tick ms (median / p95 / max) | Ticks over 50 ms | Refused |"
    $lines += "| --- | --- | --- | --- | --- | --- | --- |"
    foreach ($count in $Ships) {
        $runs = @($cases | Where-Object { $_ -like "$scenario-$count-run*" } | ForEach-Object { Get-Content (Join-Path $runRoot "$_/result.json") -Raw | ConvertFrom-Json })
        if ($runs.Count -eq 0) { continue }
        for ($i = 0; $i -lt $runs[0].steps.Count; $i++) {
            $rows = @($runs | ForEach-Object { $_.steps[$i] })
            $mid = { param($values) $sorted = @($values | Sort-Object); if ($sorted.Count -eq 0) { 0 } elseif ($sorted.Count % 2) { $sorted[[int][math]::Floor($sorted.Count / 2)] } else { ($sorted[$sorted.Count / 2 - 1] + $sorted[$sorted.Count / 2]) / 2 } }
            $f = { param($v) '{0:N1}' -f $v }
            $ops = & $mid ($rows | ForEach-Object { $_.operationsPerSecond })
            $om = & $mid ($rows | ForEach-Object { $_.operationMs.median }); $op = & $mid ($rows | ForEach-Object { $_.operationMs.p95 }); $ox = & $mid ($rows | ForEach-Object { $_.operationMs.max })
            $tm = & $mid ($rows | ForEach-Object { $_.tickMs.median }); $tp = & $mid ($rows | ForEach-Object { $_.tickMs.p95 }); $tx = & $mid ($rows | ForEach-Object { $_.tickMs.max })
            $over = & $mid ($rows | ForEach-Object { $_.ticksOver50ms }); $refused = & $mid ($rows | ForEach-Object { $_.refused })
            $lines += "| $count | $($rows[0].step) | $(& $f $ops) | $(& $f $om) / $(& $f $op) / $(& $f $ox) | $(& $f $tm) / $(& $f $tp) / $(& $f $tx) | $over | $refused |"
        }
    }
    $lines += ""
}
[System.IO.File]::WriteAllText((Join-Path $runRoot 'summary.md'), ($lines -join "`n"))
$receipt.status = 'passed'
Write-VerificationJson (Join-Path $runRoot 'result.json') $receipt
Write-Host "Performance run complete. Summary: $runRoot/summary.md"
} catch {
    $receipt.status = 'failed'
    $receipt.error = $_.Exception.Message
    $receipt.cases = @($cases)
    Write-VerificationJson (Join-Path $runRoot 'result.json') $receipt
    throw
}
