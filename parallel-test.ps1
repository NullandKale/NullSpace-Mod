param(
    [switch] $Graphics,
    [switch] $ClientsOnly,
    [switch] $Coverage,
    [ValidateRange(1, 8)] [int] $GraphicsWorkers = 6,
    [ValidateRange(1, 8)] [int] $ServerWorkers = 1,
    [ValidateRange(0, 100)] [int] $KeepSuccessfulWorldRuns = 0,
    [ValidateRange(0, 100)] [int] $KeepFailedWorldRuns = 0,
    [switch] $KeepWorlds,
    [ValidateRange(1, 128)] [int] $MinimumFreeGiB = 4,
    [ValidateRange(60, 3600)] [int] $TimeoutSeconds = 1200
)
$ErrorActionPreference = 'Stop'
if ($ClientsOnly) { $Graphics = $true }
Import-Module (Join-Path $PSScriptRoot 'scripts/LocalVerification.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot 'scripts/GraphicalWindows.psm1') -Force -DisableNameChecking
$graphicsArea = if ($Graphics) { Get-GraphicalTestWorkArea } else { $null }
$root = $PSScriptRoot
$prunedWorlds = @(& "$root/scripts/Remove-OldParallelWorlds.ps1" -Workspace $root -Keep $KeepSuccessfulWorldRuns -KeepFailed $KeepFailedWorldRuns)
$prunedWorlds += @(& "$root/scripts/Remove-OldLocalTestWorlds.ps1" -Workspace $root)
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
try {
    Save-Manifest
    Assert-VerificationNativeRegistration $root
    # Gradle owns compilation once. Only the resulting isolated JVMs run concurrently.
    $tasks = @('test','assemble','verifyContentAssets')
    if (-not $ClientsOnly) { $tasks += 'exportLocalServerLaunch' }
    if ($Coverage) { $tasks += @('exportCoverageAgent','-Pcoverage=true') }
    if ($Graphics) { $tasks += 'exportLocalClientLaunch' }
    & "$root/scripts/Invoke-Gradle.ps1" @tasks *> (Join-Path $runRoot 'prepare.log')
    if ($LASTEXITCODE -ne 0) { throw "Compilation or unit/content verification failed. See $runRoot/prepare.log" }
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
    if ($reports.Count -eq 0) { throw 'Missing unit test reports' }
    foreach ($file in $reports) {
        [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($field in @('tests','failures','errors','skipped')) { $unit[$field] += [int]$report.testsuite.$field }
    }
    if ($unit.failures -or $unit.errors) { throw 'Unit tests failed' }
    $manifest.unit = $unit
    Copy-Item -LiteralPath "$root/build/test-results/test" -Destination (Join-Path $runRoot 'unit') -Recurse
    $mod = Get-Content -LiteralPath "$root/src/gametest/resources/fabric.mod.json" -Raw | ConvertFrom-Json
    $jobs = @()
    $nativeCosts = @{}
    foreach($class in @($mod.entrypoints.'fabric-gametest')) {
        $source = Join-Path $root ('src/gametest/java/'+$class.Replace('.','/')+'.java')
        $nativeCosts[$class] = [Math]::Max(1,[regex]::Matches([IO.File]::ReadAllText($source),'(?m)^\s*@GameTest(?:\([^)]*\))?').Count)
    }
    if (-not $ClientsOnly) {
    foreach($shard in @(Get-VerificationShards @($mod.entrypoints.'fabric-gametest') $ServerWorkers -Durations $nativeCosts)) {
        $jobs += [pscustomobject]@{name="server-$($shard.index)";kind='server';expected=@($shard.classes)}
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
    foreach ($job in $jobs) {
        $work = Join-Path $runRoot $job.name
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
        $arguments += @('-Xmx2G',"-Dfabric.dli.config=$launchConfig")
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
        $process = Start-Process -FilePath $spec.executable -ArgumentList ('@"'+$argFile+'"') -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work 'test.log') -RedirectStandardError (Join-Path $work 'test.err.log')
        $null = $process.Handle
        $processes += [pscustomobject]@{job=$job;process=$process;work=$work;windowHandle=0L}
        $manifest.jobs += [ordered]@{name=$job.name;kind=$job.kind;pid=$process.Id;startedUtc=[DateTime]::UtcNow.ToString('o');expected=@($job.expected);status='running'}
        if ($job.kind -eq 'client') { $manifest.jobs[-1].windowBounds = $job.windowBounds }
        Write-Host ("Started {0}: {1} test classes, PID {2}" -f $job.name,$job.expected.Count,$process.Id)
        Save-Manifest
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
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
        $item = $processes[$i]; $item.process.WaitForExit()
        if ($item.process.ExitCode -ne 0) { throw "Test job $($item.job.name) failed" }
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
    }
    if ($Graphics) { Assert-VerificationInventory @($mod.entrypoints.'fabric-client-gametest') $actualGraphics }
    if (-not $ClientsOnly) { Assert-VerificationInventory @($mod.entrypoints.'fabric-gametest') $actualNativeClasses }
    $nativeWorkers=@($processes|Where-Object{$_.job.kind -eq 'server'}).Count
    $manifest.nativeModTests = $nativeCases - $nativeWorkers
    $manifest.nativeSmokeTests = $nativeWorkers
    Assert-VerificationFingerprint $root $fingerprint
    if ($Coverage) {
        & "$root/scripts/Invoke-Gradle.ps1" combinedCoverageReport "-PcoverageRun=$runRoot" *> (Join-Path $runRoot 'coverage-report.log')
        if ($LASTEXITCODE -ne 0) { throw 'Combined coverage reporting failed' }
        Copy-Item -LiteralPath "$root/build/reports/jacoco/combinedCoverageReport" -Destination (Join-Path $runRoot 'coverage') -Recurse
        $manifest.coverage = @{scope='Main production sources; unit and isolated Minecraft JVMs';report='coverage/combinedCoverageReport.xml'}
    }
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
        $manifest.worldCleanup = @(& "$root/scripts/Remove-OldParallelWorlds.ps1" -Workspace $root -Keep $KeepSuccessfulWorldRuns -KeepFailed $KeepFailedWorldRuns)
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
