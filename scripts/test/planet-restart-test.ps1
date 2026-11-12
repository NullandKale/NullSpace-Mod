param([string] $ArtifactRoot = '', [switch] $KeepWorlds)
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $RepoRoot 'scripts/lib/LocalVerification.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $RepoRoot 'scripts/lib/TestWorldCleanup.psm1') -Force
& "$RepoRoot/scripts/build/Invoke-Gradle.ps1" exportLocalServerLaunch --no-configuration-cache --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$spec = Get-Content -LiteralPath "$RepoRoot/build/local-server-launch.json" -Raw | ConvertFrom-Json
$runRoot = Join-Path $RepoRoot ('build/planet-restart/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
if ($ArtifactRoot) { $runRoot = [System.IO.Path]::GetFullPath($ArtifactRoot) }
# A fresh directory is mandatory: this runner deliberately terminates JVMs.
$null = New-Item -ItemType Directory -Path $runRoot
$fingerprint = Get-VerificationFingerprint $RepoRoot
foreach ($stage in @('surfaces')) {
    $work = Join-Path $runRoot $stage
    $null = New-Item -ItemType Directory -Path $work
    [System.IO.File]::WriteAllText((Join-Path $work 'eula.txt'),"eula=true`n")
    $process = $null
    try {
    foreach ($mode in @('prepare','verify')) {
        $arguments = @($spec.jvmArgs | Where-Object { -not $_.StartsWith('@') })
        $arguments += @('-Xmx2G',"-Dnullspace.planet.restart.mode=$mode","-Dnullspace.planet.restart.result=$work/passed",'-Dfabric-api.gametest.filter=nullspace-test:planet_restart_game_test_restart','-cp',$spec.classpath,$spec.mainClass)
        $arguments += @($spec.args)
        $argFile = Join-Path $work "$mode.args"
        Write-JavaArguments $argFile $arguments
        $process = Start-Process -FilePath $spec.executable -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work "$mode.log") -RedirectStandardError (Join-Path $work "$mode.err.log")
        $null = $process.Handle
        try {
            if (-not $process.WaitForExit(180000)) { throw "Planet restart test timed out at $stage/$mode. Logs: $work" }
            $expected = if ($mode -eq 'prepare') { 86 } else { 0 }
            if ($process.ExitCode -ne $expected) { throw "Planet restart test failed at $stage/$mode (exit $($process.ExitCode), expected $expected). Logs: $work" }
        } finally {
            if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
            $null = $process.WaitForExit(10000)
        }
    }
    Assert-VerificationMarker (Join-Path $work 'passed')
    Assert-VerificationFingerprint $RepoRoot $fingerprint
    Write-Host "Planet restart passed: $stage"
    } finally {
        if (-not $KeepWorlds) {
            try {
                $ids = if ($process) { @($process.Id) } else { @() }
                $cleanup = @(Remove-TestWorlds -Workspace $RepoRoot -WorkRoot $work -ProcessIds $ids)
                Write-VerificationJson (Join-Path $work 'world-cleanup.json') @{worlds=$cleanup}
            } catch { Write-Warning "Test world cleanup failed: $($_.Exception.Message)" }
        }
    }
}
Write-VerificationJson (Join-Path $runRoot 'result.json') @{ status='passed'; preserveWorlds=[bool]$KeepWorlds; inputsSha256=$fingerprint; stages=@('surfaces'); prepareExit=86; verifyExit=0; markers=@('surfaces/passed') }
Write-Host "Planet dimension restart passed. Logs: $runRoot"
