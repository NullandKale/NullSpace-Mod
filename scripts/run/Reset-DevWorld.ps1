param(
    [string]$StartingShip = 'halcyon_horizon',
    [switch]$PrepareOnly,
    [string]$PreparedRun = ''
)
$ErrorActionPreference = 'Stop'
$workspacePath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
Import-Module (Join-Path $PSScriptRoot '../lib/LocalVerification.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot '../lib/TestWorldCleanup.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot '../lib/LocalPlay.psm1') -Force -DisableNameChecking

function Assert-DevPath([string]$Path,[string]$Parent) {
    $absolute = [IO.Path]::GetFullPath($Path).TrimEnd('\','/')
    $boundary = [IO.Path]::GetFullPath($Parent).TrimEnd('\','/')
    if (-not $absolute.StartsWith($boundary + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw "Path escapes its intended directory: $absolute" }
    $walk = $absolute
    while ($walk -ne $workspacePath) {
        if (-not $walk -or -not $walk.StartsWith($workspacePath + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'World path is outside the workspace' }
        if ((Test-Path -LiteralPath $walk) -and ((Get-Item -LiteralPath $walk -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Redirected world paths are not supported: $walk" }
        $walk = Split-Path -Parent $walk
    }
    return $absolute
}
function Assert-WorldClosed([string]$World) {
    $lockPath = Join-Path $World 'session.lock'
    if (Test-Path -LiteralPath $lockPath) {
        try { $lock = [IO.File]::Open($lockPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None); $lock.Dispose() }
        catch { throw 'World 5 is open. Save and quit it before resetting; its current files have not been moved.' }
    }
}
$target = Assert-DevPath (Join-Path $workspacePath 'run/saves/World 5') (Join-Path $workspacePath 'run/saves')
Assert-WorldClosed $target
$preparationRoot = Join-Path $workspacePath 'build/dev-world-preparation'
$process = $null
$receipt = $null
$runRoot = ''
try {
    if ($PreparedRun) {
        $runRoot = Assert-DevPath $PreparedRun $preparationRoot
        $receipt = Read-VerificationJson (Join-Path $runRoot 'result.json')
        if ($receipt.status -eq 'installed') { Write-Host 'This prepared world is already installed. World 5 was preserved.'; return }
        if ($receipt.status -ne 'prepared') { throw 'This preparation did not finish successfully' }
    } else {
        Push-Location $workspacePath
        try {
            & (Join-Path $PSScriptRoot '../build/Invoke-Gradle.ps1') assemble exportLocalClientLaunch --no-configuration-cache --no-daemon --console=plain *> (Join-Path $workspacePath 'build/dev-world-prepare-compile.log')
            if ($LASTEXITCODE -ne 0) { throw 'World preparation compilation failed; see build/dev-world-prepare-compile.log' }
        } finally { Pop-Location }
        $runRoot = Join-Path $preparationRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
        $null = New-Item -ItemType Directory -Path $runRoot -Force
        $receipt = [pscustomobject]@{status='running'; startedUtc=[DateTime]::UtcNow.ToString('o'); startingShip=$StartingShip; target=$target; artifactSha256=(Get-FileHash (Join-Path $workspacePath 'build/libs/nullspace-0.1.0.jar') -Algorithm SHA256).Hash.ToLowerInvariant()}
        Write-VerificationJson (Join-Path $runRoot 'result.json') $receipt
        $spec = Read-VerificationJson (Join-Path $workspacePath 'build/local-client-launch.json')
        $resources = Join-Path $workspacePath 'build/resources/gametest'
        $copy = Join-Path $runRoot 'resources'
        Copy-Item -LiteralPath $resources -Destination $copy -Recurse
        $mod = Read-VerificationJson (Join-Path $copy 'fabric.mod.json')
        $mod.entrypoints.'fabric-client-gametest' = @('com.nullandkale.nullspace.gameplay.start.SpaceStartClientGameTest')
        Write-VerificationJson (Join-Path $copy 'fabric.mod.json') $mod
        $configPath = Join-Path $runRoot 'launch.cfg'
        [IO.File]::WriteAllText($configPath,([IO.File]::ReadAllText((Join-Path $workspacePath '.gradle/loom-cache/launch.cfg')).Replace($resources,$copy)))
        $arguments = @($spec.jvmArgs | Where-Object { -not $_.StartsWith('@') -and -not $_.StartsWith('-Xmx') -and -not $_.StartsWith('-Dfabric.dli.config=') })
        $arguments += @('-Xmx2G',('-Dfabric.dli.config='+$configPath),'-Dnullspace.dev.prepare=true',('-Dnullspace.dev.startShip='+$StartingShip),'-cp',$spec.classpath.Replace($resources,$copy),$spec.mainClass)
        for ($index=0; $index -lt $spec.args.Count; $index++) {
            $value=[string]$spec.args[$index]
            if ($value -in @('--username','--uuid','--gameDir')) { $index++; continue }
            $arguments += $value
        }
        # Minecraft's offline UUID for the existing scripts/run/dev.ps1 identity, not a new test character.
        $arguments += @('--username','Player451','--uuid',(Get-LocalPlayUuid 'Player451').Replace('-',''),'--gameDir',$runRoot)
        [IO.File]::WriteAllText((Join-Path $runRoot 'options.txt'),"fullscreen:false`nmaxFps:60`nrenderDistance:6`nsimulationDistance:5`n")
        Write-JavaArguments (Join-Path $runRoot 'launch.args') $arguments
        Write-Host 'Preparing a fresh Horizon space-start world in an isolated client. Existing World 5 stays untouched until preparation passes.'
        $process = Start-Process -FilePath $spec.executable -ArgumentList ('@"'+(Join-Path $runRoot 'launch.args')+'"') -WorkingDirectory $runRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runRoot 'test.log') -RedirectStandardError (Join-Path $runRoot 'test.err.log')
        $null=$process.Handle
        $deadline=[DateTime]::UtcNow.AddMinutes(8)
        while (-not $process.WaitForExit(500)) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Native world preparation timed out' }
        }
        if ($process.ExitCode -ne 0) { throw "Native world preparation failed; see $runRoot/test.log" }
        $evidence=Read-VerificationJson (Join-Path $runRoot 'nullspace-test-evidence.json')
        Assert-VerificationInventory @('com.nullandkale.nullspace.gameplay.start.SpaceStartClientGameTest') @($evidence.completed)
        $worlds=@(Get-ChildItem -LiteralPath (Join-Path $runRoot 'saves') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'nullspace/dev-story-profile.json') })
        if ($worlds.Count -ne 1) { throw 'Preparation must produce exactly one verified native world' }
        $profile=Read-VerificationJson (Join-Path $worlds[0].FullName 'nullspace/dev-story-profile.json')
        if ($profile.player -ne 'Player451' -or $profile.playerUuid -ne (Get-LocalPlayUuid 'Player451') -or $profile.ship -ne $StartingShip) { throw 'Prepared world identity or ship differs from the requested profile' }
        $receipt | Add-Member -NotePropertyName sourceWorld -NotePropertyValue $worlds[0].FullName
        $receipt | Add-Member -NotePropertyName profile -NotePropertyValue $profile
        $receipt.status='prepared'
        Write-VerificationJson (Join-Path $runRoot 'result.json') $receipt
    }
    if ($PrepareOnly) { Write-Host "Verified world prepared: $runRoot"; return }
    $sourceWorld=Assert-DevPath $receipt.sourceWorld (Join-Path $runRoot 'saves')
    Assert-WorldClosed $sourceWorld
    if (-not (Test-Path -LiteralPath (Join-Path $sourceWorld 'level.dat'))) { throw 'Prepared native level.dat is missing' }
    $artifact=(Get-FileHash (Join-Path $workspacePath 'build/libs/nullspace-0.1.0.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($artifact -ne $receipt.artifactSha256) { throw 'The production build changed after preparation. Prepare a fresh world with the current build.' }
    Assert-WorldClosed $target
    $backup = ''
    if (Test-Path -LiteralPath $target) {
        $backupRoot=Join-Path $workspacePath 'run/backups'
        $null=New-Item -ItemType Directory -Path $backupRoot -Force
        $backup=Assert-DevPath (Join-Path $backupRoot ('World 5-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))) $backupRoot
        Move-Item -LiteralPath $target -Destination $backup
    }
    try {
        $null=New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force
        Move-Item -LiteralPath $sourceWorld -Destination $target
    } catch {
        if ($backup -and -not (Test-Path -LiteralPath $target)) { Move-Item -LiteralPath $backup -Destination $target }
        throw
    }
    $receipt.status='installed'
    $receipt | Add-Member -NotePropertyName backup -NotePropertyValue $backup
    $receipt | Add-Member -NotePropertyName installedUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o'))
    Write-VerificationJson (Join-Path $runRoot 'result.json') $receipt
    Write-Host "Fresh World 5 installed. Previous world backup: $backup"
} catch {
    if ($process -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
    if ($runRoot -and (Test-Path -LiteralPath $runRoot)) {
        Write-VerificationJson (Join-Path $runRoot 'failure.json') @{error=$_.Exception.Message;finishedUtc=[DateTime]::UtcNow.ToString('o')}
        if (-not $receipt -or $receipt.status -eq 'running') { Remove-TestWorlds -Workspace $workspacePath -WorkRoot $runRoot -Leaves saves | Out-Null }
    }
    throw
} finally {
    if ($runRoot -and $receipt -and $receipt.status -eq 'installed') { Remove-TestWorlds -Workspace $workspacePath -WorkRoot $runRoot -Leaves saves | Out-Null }
}
