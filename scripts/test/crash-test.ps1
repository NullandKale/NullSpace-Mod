param([string[]] $Stages = @('PREPARED','DESTINATION_STAGED','SOURCE_CLEARED','PASSENGERS_MOVED','COMMITTED','TEMP_PREPARED','TEMP_SOURCE_CLEARED'), [string] $ArtifactRoot = '', [ValidateSet('launch','landing')][string[]] $Directions = @('launch','landing'), [switch] $KeepWorlds)
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $RepoRoot 'scripts/lib/LocalVerification.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $RepoRoot 'scripts/lib/TestWorldCleanup.psm1') -Force
& "$RepoRoot/scripts/build/Invoke-Gradle.ps1" exportLocalServerLaunch --no-configuration-cache --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$spec = Get-Content -LiteralPath "$RepoRoot/build/local-server-launch.json" -Raw | ConvertFrom-Json
$runRoot = Join-Path $RepoRoot ('build/local-crash/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
if ($ArtifactRoot) { $runRoot = [System.IO.Path]::GetFullPath($ArtifactRoot) }
$null = New-Item -ItemType Directory -Path $runRoot
$fingerprint = Get-VerificationFingerprint $RepoRoot
if (@($Stages | Select-Object -Unique).Count -ne $Stages.Count -or @($Directions | Select-Object -Unique).Count -ne $Directions.Count -or $Stages.Count -eq 0 -or $Directions.Count -eq 0) { throw 'Crash cases must be nonempty and unique' }
$cases = @()
foreach ($direction in $Directions) {
foreach ($stage in $Stages) {
    if ($stage -notin @('PREPARED','DESTINATION_STAGED','SOURCE_CLEARED','PASSENGERS_MOVED','COMMITTED','TEMP_PREPARED','TEMP_SOURCE_CLEARED')) { throw "Unknown crash stage: $stage" }
    $case = "$direction/$stage"
    $work = Join-Path $runRoot $case
    $null = New-Item -ItemType Directory -Force -Path $work
    [System.IO.File]::WriteAllText((Join-Path $work 'eula.txt'),"eula=true`n")
    $process = $null
    try {
    foreach ($mode in @('prepare','verify')) {
        $arguments = @($spec.jvmArgs | Where-Object { -not $_.StartsWith('@') })
        $arguments += @('-Xmx2G',"-Dnullspace.crash.mode=$mode","-Dnullspace.crash.stage=$stage","-Dnullspace.crash.direction=$direction","-Dnullspace.crash.result=$work/passed",'-Dfabric-api.gametest.filter=nullspace-test:crash_recovery_game_test_restart','-cp',$spec.classpath,$spec.mainClass)
        $arguments += @($spec.args)
        $argFile = Join-Path $work "$mode.args"
        Write-JavaArguments $argFile $arguments
        $process = Start-Process -FilePath $spec.executable -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work "$mode.log") -RedirectStandardError (Join-Path $work "$mode.err.log")
        $null = $process.Handle
        try {
            if (-not $process.WaitForExit(180000)) { throw "Crash test timed out at $stage/$mode. Logs: $work" }
            $expected = if ($mode -eq 'prepare') { 86 } else { 0 }
            if ($process.ExitCode -ne $expected) { throw "Crash test failed at $stage/$mode (exit $($process.ExitCode), expected $expected). Logs: $work" }
        }
        finally {
            if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
            $null = $process.WaitForExit(10000)
        }
    }
    Assert-VerificationMarker (Join-Path $work 'passed')
    Assert-VerificationFingerprint $RepoRoot $fingerprint
    Write-Host "Crash/restart passed: $case"
    $cases += $case
    } finally {
        # Recovery verification needs the prepare world's journals. Clean up only
        # after BOTH JVMs have finished, including an unsuccessful recovery.
        if (-not $KeepWorlds) {
            try {
                $ids = if ($process) { @($process.Id) } else { @() }
                $cleanup = @(Remove-TestWorlds -Workspace $RepoRoot -WorkRoot $work -ProcessIds $ids)
                Write-VerificationJson (Join-Path $work 'world-cleanup.json') @{worlds=$cleanup}
            } catch { Write-Warning "Test world cleanup failed: $($_.Exception.Message)" }
        }
    }
}
}
Write-Host "All requested crash/restart tests passed. Logs: $runRoot"

Write-VerificationJson (Join-Path $runRoot 'result.json') @{ status='passed'; preserveWorlds=[bool]$KeepWorlds; inputsSha256=$fingerprint; stages=@($Stages); directions=@($Directions); cases=@($cases); prepareExit=86; verifyExit=0; markers=@($cases | ForEach-Object { "$_/passed" }) }
