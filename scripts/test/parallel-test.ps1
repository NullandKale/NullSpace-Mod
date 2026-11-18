param(
    [switch] $Graphics,
    [switch] $ClientsOnly,
    [switch] $Coverage,
    [ValidateRange(1, 8)] [int] $GraphicsWorkers = 4,
    # Four, because the release CI runs the native tests as four shards and each server worker here is exactly one of them.
    [ValidateRange(1, 8)] [int] $ServerWorkers = 4,
    [ValidateRange(0, 100)] [int] $KeepSuccessfulWorldRuns = 0,
    [ValidateRange(0, 100)] [int] $KeepFailedWorldRuns = 0,
    [switch] $KeepWorlds,
    [ValidateRange(1, 128)] [int] $MinimumFreeGiB = 4,
    [ValidateRange(60, 3600)] [int] $TimeoutSeconds = 1800,
    # Written once everything is compiled and the workers are about to start, so other runners may use the build outputs.
    [string] $ReadyFile = ''
)
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ErrorActionPreference = 'Stop'
if ($ClientsOnly) { $Graphics = $true }
Import-Module (Join-Path $RepoRoot 'scripts/lib/LocalVerification.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $RepoRoot 'scripts/lib/GraphicalWindows.psm1') -Force -DisableNameChecking
$graphicsArea = if ($Graphics) { Get-GraphicalTestWorkArea } else { $null }
$root = $RepoRoot
$prunedWorlds = @(& "$root/scripts/test/Remove-OldParallelWorlds.ps1" -Workspace $root -Keep $KeepSuccessfulWorldRuns -KeepFailed $KeepFailedWorldRuns)
$prunedWorlds += @(& "$root/scripts/test/Remove-OldLocalTestWorlds.ps1" -Workspace $root)
$availableBytes = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($root)).AvailableFreeSpace
Assert-VerificationDiskSpace $availableBytes ([long]$MinimumFreeGiB * 1GB)
$runRoot = Join-Path $root ('build/parallel-verification/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
$null = New-Item -ItemType Directory -Path $runRoot
$manifest = [ordered]@{status='running'; startedUtc=[DateTime]::UtcNow.ToString('o'); jobs=@(); unit=$null}
$manifest.clientsOnly = [bool]$ClientsOnly
$manifest.diskAtStart = @{freeBytes=$availableBytes; requiredBytes=[long]$MinimumFreeGiB*1GB}
$manifest.prunedWorlds = $prunedWorlds
$manifest.preserveWorlds = [bool]$KeepWorlds
$processes = @()
function Save-Manifest { Write-VerificationJson (Join-Path $runRoot 'result.json') $manifest }
# Everything that fails is listed together at the end: a failed check or job never stops the others from running or from being reported.
$problems = [System.Collections.Generic.List[string]]::new()
function Get-JobFailureSummary($item) {
    $lines = @()
    $log = Join-Path $item.work 'test.log'
    if (Test-Path -LiteralPath $log) {
        $lines += @(Select-String -LiteralPath $log -Pattern '\(Minecraft\)    - ' | ForEach-Object { $_.Line.Trim() })
        if (-not $lines.Count) { $lines += @(Select-String -LiteralPath $log -Pattern 'AssertionError|Client gametests failed|STALLED' | Select-Object -First 3 | ForEach-Object { $_.Line.Trim() }) }
    }
    if ($lines.Count) { return "`n    " + (($lines | Select-Object -First 25 | ForEach-Object { if ($_.Length -gt 400) { $_.Substring(0, 400) } else { $_ } }) -join "`n    ") }
    return ''
}
try {
    Save-Manifest
    Assert-VerificationNativeRegistration $root
    # Gradle owns compilation once. Only the resulting isolated JVMs run concurrently.
    # Only a build that does not compile stops the run, since there is then nothing to test. The unit, content and format checks are run next, all
    # of them, and a failure in one is recorded and reported with the rest.
    $tasks = @('assemble')
    if (-not $ClientsOnly) { $tasks += 'exportLocalServerLaunch' }
    if ($Coverage) { $tasks += @('exportCoverageAgent','-Pcoverage=true') }
    if ($Graphics) { $tasks += 'exportLocalClientLaunch' }
    & "$root/scripts/build/Invoke-Gradle.ps1" @tasks *> (Join-Path $runRoot 'prepare.log')
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed. See $runRoot/prepare.log" }
    # WHY THIS SCRIPT NEVER FAILS FAST: its whole point is to find EVERY failure in ONE run, so that the complete list can be fixed as one batch.
    # A runner that stops at the first failed stage hides every stage after it, and then the failures come out one per run: the old verify.ps1 did that
    # (nine sweeps, one failure each, and it was deleted for it on 2026-11-15), and on 2026-11-18 two release attempts ended at the unit and content
    # checks before a single server or graphical test had run, so everything behind them stayed unknown for another 25 minutes each. The agreed rule
    # (AGENTS.md: "Collect outcomes for every selected check before fixing failures") is: run everything, report everything, fix everything, rerun
    # only what changed. Do not add a `throw`, `exit` or "stop on first failure" anywhere between the compile above and the final report below.
    # Here: the unit tests, the content-asset check and the formatter check all run (`--continue`), and a failure in any of them is only added to
    # $problems. The server and graphical tests below still start, whatever these say.
    # The same command the release CI's build job runs (.github/workflows/release.yml), so that what passes here is what CI will run.
    $checks = @('build','verifyContentAssets','-x','runGameTest','--continue')
    if ($Coverage) { $checks += '-Pcoverage=true' }
    & "$root/scripts/build/Invoke-Gradle.ps1" @checks *> (Join-Path $runRoot 'checks.log')
    if ($LASTEXITCODE -ne 0) { $problems.Add("The unit, content or format checks failed. See $runRoot/checks.log") }
    $fingerprint = Get-VerificationFingerprint $root
    $manifest.inputsSha256 = $fingerprint
    $properties=[IO.File]::ReadAllText((Join-Path $root 'gradle.properties'))
    $archive=[regex]::Match($properties,'(?m)^archives_base_name=(.+)$').Groups[1].Value.Trim()
    $version=[regex]::Match($properties,'(?m)^mod_version=(.+)$').Groups[1].Value.Trim()
    if(-not $archive -or -not $version){throw 'Missing packaged artifact coordinates'}
    $jar=Join-Path $root "build/libs/$archive-$version.jar"
    Copy-Item -LiteralPath $jar -Destination $runRoot
    $manifest.artifact=@{file=(Split-Path -Leaf $jar);sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()}
    $unit = [ordered]@{tests=0;failures=0;errors=0;skipped=0}
    $reports = @(Get-ChildItem -LiteralPath "$root/build/test-results/test" -Filter 'TEST-*.xml')
    if ($reports.Count -eq 0) { $problems.Add('Missing unit test reports') }
    $failedUnits = @()
    foreach ($file in $reports) {
        [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($field in @('tests','failures','errors','skipped')) { $unit[$field] += [int]$report.testsuite.$field }
        foreach ($case in @($report.SelectNodes('//testcase[failure or error]'))) { $failedUnits += ($case.classname + '.' + $case.name) }
    }
    if ($unit.failures -or $unit.errors) { $problems.Add("Unit tests failed ($($unit.failures) failures, $($unit.errors) errors):`n    " + ((@($failedUnits) | Select-Object -First 25) -join "`n    ")) }
    $manifest.unit = $unit
    if ($reports.Count) { Copy-Item -LiteralPath "$root/build/test-results/test" -Destination (Join-Path $runRoot 'unit') -Recurse }
    $mod = Get-Content -LiteralPath "$root/src/gametest/resources/fabric.mod.json" -Raw | ConvertFrom-Json
    $jobs = @()
    # Server shards are balanced by the measured seconds of recent passing runs; a class without history costs the median, and with no history at all, its declared case count.
    $nativeCosts = Get-VerificationNativeDurations (Join-Path $root 'build/parallel-verification') $root @($mod.entrypoints.'fabric-gametest')
    if ($nativeCosts.Count -eq 0) {
        foreach($class in @($mod.entrypoints.'fabric-gametest')) {
            $source = Join-Path $root ('src/gametest/java/'+$class.Replace('.','/')+'.java')
            $nativeCosts[$class] = [Math]::Max(1,[regex]::Matches([IO.File]::ReadAllText($source),'(?m)^\s*@GameTest(?:\([^)]*\))?').Count)
        }
    }
    $manifest.nativeTimingEstimates = $nativeCosts
    if (-not $ClientsOnly) {
    # WHY THE SERVER WORKERS ARE CI'S SHARDS: a test passes or fails with the tests that share its JVM and world (leftover entities, records, orbit
    # slots), so a different grouping is a different test. The release CI runs `runGameTest -PgametestShard=N/4`, whose split build.gradle makes by
    # declared case count: classes sorted by cost (descending, then name), each put in the cheapest group (the lowest index on a tie). This is that
    # same split, so server-N here runs exactly the classes of CI's shard N/4 (with four workers). Do not balance by seconds again: it is faster
    # and it is not the same tests.
    $ciCosts = @{}
    foreach ($class in @($mod.entrypoints.'fabric-gametest')) {
        $source = Join-Path $root ('src/gametest/java/' + $class.Replace('.', '/') + '.java')
        $ciCosts[$class] = [Math]::Max(1, [regex]::Matches([IO.File]::ReadAllText($source), '(?m)^\s*@GameTest\b').Count)
    }
    $ciBins = @(1..$ServerWorkers | ForEach-Object { [pscustomobject]@{index = $_; classes = @(); cost = 0} })
    # (Names compare ordinally, as Gradle's do: Sort-Object would compare them by culture and ignoring case, and the groups would differ.)
    $ciOrder = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $ciCosts.GetEnumerator()) { $ciOrder.Add([pscustomobject]@{Key = $entry.Key; Value = $entry.Value}) }
    $ciOrder.Sort([Comparison[object]]{ param($x, $y) if ($x.Value -ne $y.Value) { return $y.Value.CompareTo($x.Value) } return [string]::CompareOrdinal($x.Key, $y.Key) })
    foreach ($entry in $ciOrder) {
        $bin = $ciBins | Sort-Object cost, index | Select-Object -First 1
        $bin.classes += $entry.Key
        $bin.cost += $entry.Value
    }
    foreach ($shard in $ciBins) {
        $jobs += [pscustomobject]@{name = "server-$($shard.index)"; kind = 'server'; expected = @($shard.classes | Sort-Object)}
    }
    }
    if ($Graphics) {
        $expected = @($mod.entrypoints.'fabric-client-gametest')
        $graphicsDurations = Get-VerificationDurations (Join-Path $root 'build/parallel-verification')
        $manifest.graphicsTimingEstimates = $graphicsDurations
        foreach ($shard in @(Get-VerificationShards $expected $GraphicsWorkers -Durations $graphicsDurations)) {
            $jobs += [pscustomobject]@{name="graphics-$($shard.index)";kind='client';expected=@($shard.classes)}
        }
        $graphicsJobs = @($jobs | Where-Object kind -eq 'client')
        $layout = Get-GraphicalWindowLayout -Count $graphicsJobs.Count -X $graphicsArea.x -Y $graphicsArea.y -Width $graphicsArea.width -Height $graphicsArea.height
        for ($index = 0; $index -lt $graphicsJobs.Count; $index++) {
            $graphicsJobs[$index] | Add-Member -NotePropertyName windowBounds -NotePropertyValue $layout.windows[$index]
        }
        $manifest.windowGrid = @{area=$graphicsArea;columns=$layout.columns;rows=$layout.rows}
        Write-Host ("Graphical windows: {0} columns x {1} rows on the active monitor" -f $layout.columns,$layout.rows)
    }
    if ($ReadyFile) { [IO.File]::WriteAllText($ReadyFile, $runRoot) }
    # WHY PARALLEL: every server and graphical worker is started now, side by side, and none waits for another or is stopped when another fails.
    # Running them one after another would take the sum of their times and would invite stopping at the first red one; running them together takes the
    # time of the slowest, and a failure in one worker cannot hide another's. Each worker is its own JVM with its own world, so they cannot disturb one
    # another (see Run-FocusedTests.ps1). Clients are started 15 seconds apart because clients that load in the same seconds can freeze.
    $lastClientStart = $null
    # Starts one worker JVM (its own folder, world and resources) and returns its process. $suffix names the folder of a second attempt.
    function Start-Worker($job, [string] $suffix = '') {
        $work = Join-Path $runRoot ($job.name + $suffix)
        $null = New-Item -ItemType Directory -Path $work
        [IO.File]::WriteAllText((Join-Path $work 'eula.txt'),"eula=true`n")
        $spec = Get-Content -LiteralPath "$root/build/local-$($job.kind)-launch.json" -Raw | ConvertFrom-Json
        $classpath = $spec.classpath
        $config = [IO.File]::ReadAllText((Join-Path $root '.gradle/loom-cache/launch.cfg'))
        $resources = Join-Path $root 'build/resources/gametest'
        $copy = Join-Path $work 'resources'
        Copy-Item -LiteralPath $resources -Destination $copy -Recurse
        $filtered = Get-Content -LiteralPath (Join-Path $copy 'fabric.mod.json') -Raw | ConvertFrom-Json
        $entrypoint = if($job.kind -eq 'client'){'fabric-client-gametest'}else{'fabric-gametest'}
        $filtered.entrypoints.$entrypoint = @($job.expected)
        Write-VerificationJson (Join-Path $copy 'fabric.mod.json') $filtered
        $config = $config.Replace($resources,$copy)
        $classpath = $classpath.Replace($resources.Replace('\','\\'),$copy.Replace('\','\\')).Replace($resources,$copy)
        $launchConfig = Join-Path $work 'launch.cfg'
        [IO.File]::WriteAllText($launchConfig,$config,[Text.UTF8Encoding]::new($false))
        $arguments = @($spec.jvmArgs | Where-Object { -not $_.StartsWith('@') -and -not $_.StartsWith('-Dfabric.dli.config=') })
        # The number of workers is fixed (4 server and 4 graphics by default) and each has the machine's memory to itself: the server workers run the
        # big hab and station tests, the graphics ones the client.
        $heap = if ($job.kind -eq 'server') { '-Xmx4G' } else { '-Xmx4G' }
        $arguments += @($heap,"-Dfabric.dli.config=$launchConfig")
        if ($Coverage) {
            $agent = Join-Path $root 'build/coverage-agent/jacocoagent.jar'
            if (-not (Test-Path -LiteralPath $agent)) { throw 'Missing exported coverage agent' }
            $arguments += "-javaagent:${agent}=destfile=$work/coverage.exec,append=false,includes=com.nullandkale.nullspace.*"
        }
        if($job.kind -eq 'server'){$arguments += "-Dfabric-api.gametest.report-file=$work/native-tests.xml"}
        $arguments += @('-cp',$classpath,$spec.mainClass)
        $arguments += @($spec.args)
        if ($job.kind -eq 'client') { $arguments += @('--gameDir',$work,'--width','1280','--height','720') }
        else { $arguments += @('--universe',$work) }
        $argFile = Join-Path $work 'launch.args'
        Write-JavaArguments $argFile $arguments
        if ($job.kind -eq 'client' -and $null -ne $lastClientStart) {
            $remaining = 15000 - ([DateTime]::UtcNow - $lastClientStart).TotalMilliseconds
            if ($remaining -gt 0) { Start-Sleep -Milliseconds ([int][Math]::Ceiling($remaining)) }
        }
        $process = Start-Process -FilePath $spec.executable -ArgumentList ('@"'+$argFile+'"') -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work 'test.log') -RedirectStandardError (Join-Path $work 'test.err.log')
        if ($job.kind -eq 'client') { $script:lastClientStart = [DateTime]::UtcNow }
        $null = $process.Handle
        return [pscustomobject]@{process = $process; work = $work}
    }
    # A client worker is stalled when it has run for minutes without completing a test and its log has gone quiet: that is a freeze in the
    # client test framework's start-up handshake (the test thread never runs), not a failing test, and waiting does not end it.
    function Test-WorkerStalled($item, $jobState, [DateTime] $now) {
        if ($item.job.kind -ne 'client' -or $item.restarts -ge 1 -or $item.process.HasExited) { return $false }
        if (($now - $item.startedAt).TotalMinutes -lt 5) { return $false }
        if ($jobState.Contains('completed') -and $jobState.completed -gt 0) { return $false }
        if ($jobState.Contains('runningCase') -and $jobState.runningCase) { return $false }
        $log = Join-Path $item.work 'logs/latest.log'
        $last = if (Test-Path -LiteralPath $log) { (Get-Item -LiteralPath $log).LastWriteTimeUtc } else { $item.startedAt }
        return (($now - $last).TotalMinutes -ge 3)
    }
    foreach ($job in $jobs) {
        $started = Start-Worker $job
        $process = $started.process; $work = $started.work
        $processes += [pscustomobject]@{job=$job;process=$process;work=$work;windowHandle=0L;startedAt=[DateTime]::UtcNow;restarts=0}
        $manifest.jobs += [ordered]@{name=$job.name;kind=$job.kind;pid=$process.Id;startedUtc=[DateTime]::UtcNow.ToString('o');expected=@($job.expected);status='running'}
        if ($job.kind -eq 'client') { $manifest.jobs[-1].windowBounds = $job.windowBounds }
        Write-Host ("Started {0}: {1} test classes, PID {2}" -f $job.name,$job.expected.Count,$process.Id)
        Save-Manifest
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    # Wait for ALL workers. A worker that exits with a failure is only noted (see the warning below): the others are left to finish, because the whole
    # list of failures is the result this script exists to produce.
    while (@($processes | Where-Object { -not $_.process.HasExited }).Count -gt 0) {
        if ((Get-Date) -gt $deadline) { throw "Parallel tests timed out. Logs: $runRoot" }
        for($i=0;$i -lt $processes.Count;$i++) {
            $item=$processes[$i];$jobState=$manifest.jobs[$i]
            if($item.process.HasExited) {
                $item.process.WaitForExit()
                if(-not $jobState.Contains('finishedUtc')) {
                    $jobState.finishedUtc=[DateTime]::UtcNow.ToString('o')
                    $jobState.elapsedSeconds=([DateTimeOffset]::UtcNow-[DateTimeOffset]::Parse($jobState.startedUtc)).TotalSeconds
                    $jobState.exitCode=$item.process.ExitCode
                    $jobState.status=if($item.process.ExitCode){'failed'}else{'process-completed'}
                    Write-Host ("Finished {0}: exit {1}, {2:N1}s" -f $item.job.name,$item.process.ExitCode,$jobState.elapsedSeconds)
                    if ($item.process.ExitCode -ne 0) { Write-Warning "Worker $($item.job.name) failed; other isolated workers will finish before verification reports failure." }
                }
            } elseif($item.job.kind -eq 'client') {
                # WATCHDOG: a client that froze while starting is killed and started once more (see Test-WorkerStalled).
                if (Test-WorkerStalled $item $jobState ([DateTime]::UtcNow)) {
                    Write-Warning "Worker $($item.job.name) has done nothing for minutes (a start-up freeze): restarting it once."
                    Stop-Process -Id $item.process.Id -Force -ErrorAction SilentlyContinue
                    $null = $item.process.WaitForExit(15000)
                    $restarted = Start-Worker $item.job '-retry'
                    $item.process = $restarted.process; $item.work = $restarted.work; $item.startedAt = [DateTime]::UtcNow; $item.restarts = 1; $item.windowHandle = 0L
                    $jobState.pid = $restarted.process.Id; $jobState.restartedUtc = [DateTime]::UtcNow.ToString('o'); $jobState.restarts = 1
                    Save-Manifest
                    continue
                }
                $item.windowHandle = Set-GraphicalTestWindow -ProcessId $item.process.Id -PreviousWindow $item.windowHandle -Name $item.job.name -Bounds $item.job.windowBounds
                $jobState.windowArranged = $item.windowHandle -ne 0
                $evidencePath=Join-Path $item.work 'nullspace-test-evidence.json'
                if(Test-Path -LiteralPath $evidencePath) {
                    try {
                        $live=Read-VerificationJson $evidencePath
                        $jobState.completed=@($live.completed).Count
                        if($live.PSObject.Properties['running']){$jobState.runningCase=$live.running}
                    } catch { } # The final validation below always requires a complete valid receipt.
                }
            }
        }
        Save-Manifest
        Start-Sleep -Seconds 1
    }
    $actualGraphics = @(); $actualNativeClasses = @(); $nativeCases = 0
    for ($i=0; $i -lt $processes.Count; $i++) {
        # Each job is checked on its own, and a job that failed is recorded in $problems (with its failing test names) before the next one is looked at.
        # The `throw`s inside only leave this job's own check; the catch below turns them into entries in the list instead of ending the run.
        $item = $processes[$i]; $item.process.WaitForExit()
        try {
        if ($item.process.ExitCode -ne 0) { throw "Test job $($item.job.name) failed (exit $($item.process.ExitCode))" }
        $manifest.jobs[$i].exitCode = $item.process.ExitCode
        if(-not $manifest.jobs[$i].Contains('finishedUtc')) {
            $manifest.jobs[$i].finishedUtc=[DateTime]::UtcNow.ToString('o')
            $manifest.jobs[$i].elapsedSeconds=([DateTimeOffset]::UtcNow-[DateTimeOffset]::Parse($manifest.jobs[$i].startedUtc)).TotalSeconds
        }
        if ($item.job.kind -eq 'client') {
            $evidence = Get-Content -LiteralPath (Join-Path $item.work 'nullspace-test-evidence.json') -Raw | ConvertFrom-Json
            Assert-VerificationInventory @($item.job.expected) @($evidence.completed)
            $actualGraphics += @($evidence.completed)
            $manifest.jobs[$i].tests = @($evidence.completed).Count
            $manifest.jobs[$i].completed = @($evidence.completed).Count
            $manifest.jobs[$i].runningCase = ''
        } else {
            $native = Get-Content -LiteralPath (Join-Path $item.work 'test.log') -Raw
            if ($native -notmatch 'All (\d+) required tests passed') { throw 'Missing server completion marker' }
            $passed = [int]$Matches[1]; $declared = 1
            foreach ($class in @($item.job.expected)) {
                $source = Join-Path $root ('src/gametest/java/'+$class.Replace('.','/')+'.java')
                $declared += [regex]::Matches([IO.File]::ReadAllText($source),'(?m)^\s*@GameTest(?:\([^)]*\))?').Count
            }
            if ($passed -ne $declared -or $native -match '\d+ optional tests failed') { throw 'Server test inventory mismatch' }
            $manifest.jobs[$i].tests = $passed
            $actualNativeClasses += @($item.job.expected)
            $nativeCases += $passed
            [xml]$nativeXml=Get-Content -LiteralPath (Join-Path $item.work 'native-tests.xml') -Raw
            $cases=@($nativeXml.SelectNodes('//testcase'))
            if($cases.Count -ne $passed -or @($nativeXml.SelectNodes('//failure|//error')).Count){throw 'Native XML inventory or outcome mismatch'}
            $expectedNative=@(Get-VerificationNativeInventory $root @($item.job.expected))+@('minecraft:always_pass')
            Assert-VerificationInventory $expectedNative @($cases|ForEach-Object{[string]$_.name})
            $manifest.jobs[$i].nativeCases = @($cases | ForEach-Object { [ordered]@{name=$_.name;class=$_.classname;seconds=$_.time} })
        }
        $manifest.jobs[$i].status = 'passed'
        if ($Coverage) {
            $executionData = Join-Path $item.work 'coverage.exec'
            if (-not (Test-Path -LiteralPath $executionData) -or (Get-Item -LiteralPath $executionData).Length -eq 0) {
                throw "Missing coverage data for $($item.job.name)"
            }
            $manifest.jobs[$i].coverage = $executionData
        }
        } catch {
            $manifest.jobs[$i].status = 'failed'
            $problems.Add("$($item.job.name): $($_.Exception.Message)" + (Get-JobFailureSummary $item))
        }
    }
    # The whole inventory can only be complete when every job was: with a failed job it would only repeat that failure.
    if (-not $problems.Count) {
    if ($Graphics) { Assert-VerificationInventory @($mod.entrypoints.'fabric-client-gametest') $actualGraphics }
    if (-not $ClientsOnly) { Assert-VerificationInventory @($mod.entrypoints.'fabric-gametest') $actualNativeClasses }
    }
    $nativeWorkers=@($processes|Where-Object{$_.job.kind -eq 'server'}).Count
    $manifest.nativeModTests = $nativeCases - $nativeWorkers
    $manifest.nativeSmokeTests = $nativeWorkers
    try { Assert-VerificationFingerprint $root $fingerprint } catch { $problems.Add($_.Exception.Message) }
    if ($Coverage -and -not $problems.Count) {
        & "$root/scripts/build/Invoke-Gradle.ps1" combinedCoverageReport "-PcoverageRun=$runRoot" *> (Join-Path $runRoot 'coverage-report.log')
        if ($LASTEXITCODE -ne 0) { throw 'Combined coverage reporting failed' }
        Copy-Item -LiteralPath "$root/build/reports/jacoco/combinedCoverageReport" -Destination (Join-Path $runRoot 'coverage') -Recurse
        $manifest.coverage = @{scope='Main production sources; unit and isolated Minecraft JVMs';report='coverage/combinedCoverageReport.xml'}
    }
    $manifest.problems = @($problems)
    if ($problems.Count) { throw ("Verification found $($problems.Count) problem(s):`n" + ($problems -join "`n")) }
    $manifest.status = if ($unit.skipped) {'passed-with-unit-skips'} else {'passed'}
} catch {
    $manifest.status = 'failed'; $manifest.error = $_.Exception.Message
    throw
} finally {
    for ($i=0; $i -lt $processes.Count; $i++) {
        $item = $processes[$i]
        if (-not $item.process.HasExited) {
            Stop-Process -Id $item.process.Id -ErrorAction SilentlyContinue
            if (-not $item.process.WaitForExit(10000)) { Write-Warning "Worker $($item.job.name) has not exited; its worlds will be retained" }
            $manifest.jobs[$i].status = 'cancelled'
        } elseif ($manifest.jobs[$i].status -eq 'running') {
            $manifest.jobs[$i].exitCode = $item.process.ExitCode
            $manifest.jobs[$i].status = if ($item.process.ExitCode) {'failed'} else {'completed-unverified'}
        }
    }
    if ($manifest.status -eq 'running') {
        $manifest.status = 'failed'
        $manifest.error = 'Verification was interrupted before results were verified'
    }
    $manifest.finishedUtc = [DateTime]::UtcNow.ToString('o'); Save-Manifest
    try {
        $manifest.worldCleanup = @(& "$root/scripts/test/Remove-OldParallelWorlds.ps1" -Workspace $root -Keep $KeepSuccessfulWorldRuns -KeepFailed $KeepFailedWorldRuns)
        Save-Manifest
        $reclaimed = [long]($manifest.worldCleanup | Measure-Object bytes -Sum).Sum
        Write-Host ('Test world cleanup: {0:N1} MiB reclaimed; logs and screenshots retained' -f ($reclaimed/1MB))
    } catch {
        $manifest.cleanupError = $_.Exception.Message
        Save-Manifest
        Write-Warning "Test world cleanup failed: $($_.Exception.Message)"
    }
    Write-Host "Parallel verification: $runRoot"
}
