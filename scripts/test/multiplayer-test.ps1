param([int] $TimeoutSeconds = 360, [string] $ArtifactRoot = '', [switch] $KeepWorlds, [switch] $Profile)
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $RepoRoot 'scripts/lib/LocalVerification.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $RepoRoot 'scripts/lib/TestWorldCleanup.psm1') -Force
$projectRoot = $RepoRoot
& "$projectRoot/scripts/build/Invoke-Gradle.ps1" exportLocalClientLaunch --no-configuration-cache --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$spec = Get-Content -LiteralPath "$projectRoot/build/local-client-launch.json" -Raw | ConvertFrom-Json
$runRoot = Join-Path $projectRoot ('build/local-multiplayer/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
if ($ArtifactRoot) { $runRoot = [System.IO.Path]::GetFullPath($ArtifactRoot) }
$null = New-Item -ItemType Directory -Path $runRoot
$fingerprint = Get-VerificationFingerprint $projectRoot
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$processes = @()
try {
    foreach ($role in @('pilot','crew')) {
        $work = Join-Path $runRoot $role
        $null = New-Item -ItemType Directory -Path $work
        [System.IO.File]::WriteAllText((Join-Path $work 'eula.txt'),"eula=true`n")
        $arguments = @($spec.jvmArgs | Where-Object { -not $_.StartsWith('@') })
        if ($Profile) { $arguments += @('-XX:FlightRecorderOptions=stackdepth=128', ('-XX:StartFlightRecording=name=NullSpacePlaytest,settings=profile,dumponexit=true,maxsize=128m,filename=' + (Join-Path $work 'profile.jfr'))) }
        $arguments += @('-Xmx2G',"-Dnullspace.local.role=$role","-Dnullspace.local.directory=$runRoot","-Dnullspace.local.port=$port",'-cp',$spec.classpath,$spec.mainClass)
        $arguments += @($spec.args)
        $arguments += @('--gameDir',$work,'--username',"NullSpace_$role",'--uuid',[guid]::NewGuid().ToString(),'--width','640','--height','480')
        $argFile = Join-Path $work 'launch.args'
        Write-JavaArguments $argFile $arguments
        $process = Start-Process -FilePath $spec.executable -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work 'client.log') -RedirectStandardError (Join-Path $work 'client.err.log')
        $null = $process.Handle
        $processes += $process
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (@($processes | Where-Object { -not $_.HasExited }).Count -gt 0) {
        $stalled = Join-Path $runRoot 'stalled'
        if (Test-Path $stalled) { Start-Sleep -Seconds 2; throw "Local multiplayer STALLED: $(Get-Content $stalled -Raw) Logs: $runRoot" }
        if ((Get-Date) -gt $deadline) { throw "Local multiplayer test timed out. Logs: $runRoot" }
        foreach ($process in $processes) {
            if ($process.HasExited -and $process.ExitCode -ne 0) { throw "Local client failed. Logs: $runRoot" }
        }
        Start-Sleep -Seconds 2
    }
    foreach ($process in $processes) { $process.WaitForExit(); if ($process.ExitCode -ne 0) { throw "Local client failed. Logs: $runRoot" } }
    Assert-VerificationMarker (Join-Path $runRoot 'passed') 'ok'
    Assert-VerificationMarker (Join-Path $runRoot 'shipyard-race-passed') 'ok'
    Assert-VerificationFingerprint $projectRoot $fingerprint
    Write-VerificationJson (Join-Path $runRoot 'result.json') @{ status='passed'; preserveWorlds=[bool]$KeepWorlds; profile=[bool]$Profile; inputsSha256=$fingerprint; roles=@('pilot','crew'); exitCodes=@($processes | ForEach-Object { $_.ExitCode }); marker='passed'; shipyardRace='shipyard-race-passed' }
    Write-Host "Local multiplayer passed: two graphical clients, loopback dedicated server, typed-session validation, private pad/ship previews, launch, offline return, reconnect tracking, shared power revisions, distant GPU feeds, and concurrent free ship purchases. Logs: $runRoot"
}
finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
        $null = $process.WaitForExit(10000)
    }
    if (-not $KeepWorlds) {
        try {
            $cleanup = @(foreach ($role in @('pilot','crew')) {
                Remove-TestWorlds -Workspace $projectRoot -WorkRoot (Join-Path $runRoot $role) -ProcessIds @($processes | ForEach-Object Id)
            })
            Write-VerificationJson (Join-Path $runRoot 'world-cleanup.json') @{worlds=$cleanup}
        } catch { Write-Warning "Test world cleanup failed: $($_.Exception.Message)" }
    }
}
