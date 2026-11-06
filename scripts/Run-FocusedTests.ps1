<#
.SYNOPSIS
  Runs selected GameTest classes in one isolated JVM, like a single parallel-test.ps1 worker.
.EXAMPLE
  .\scripts\Run-FocusedTests.ps1 -Classes com.nullandkale.nullspace.ship.ShipTitleGameTest
  .\scripts\Run-FocusedTests.ps1 -Client -Classes com.nullandkale.nullspace.ScannerBlockClientGameTest
.NOTES
  Builds once unless -SkipBuild. A run that hangs for 8 minutes is killed and fails the command.
  Work folders: build/focused-tests/<time>-<class>-<id>; worlds are deleted afterwards.
  Needs rg on PATH (scripts/LocalVerification.psm1). Start parallel client runs >= 15 s apart:
  several clients launched in the same second can freeze during startup.
#>
param([string[]] $Classes = @('com.nullandkale.nullspace.ship.RecoveryGameTest'), [switch] $SkipBuild, [switch] $Client)
$kind = if ($Client) { 'client' } else { 'server' }
$entry = if ($Client) { 'fabric-client-gametest' } else { 'fabric-gametest' }
# Focused native GameTest run for a subset of classes; mirrors parallel-test.ps1's server job.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $root 'scripts/LocalVerification.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $root 'scripts/TestWorldCleanup.psm1') -Force
if (-not $SkipBuild) {
    & "$root/scripts/Invoke-Gradle.ps1" gametestClasses processGametestResources exportLocalServerLaunch exportLocalClientLaunch --console=plain *> "$root/build/focused-build.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$root/build/focused-build.log" | Select-String 'error|FAIL' | Select-Object -First 30; throw 'build failed' }
}
$work = Join-Path $root ('build/focused-tests/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + ($Classes[0].Split('.')[-1]) + '-' + [guid]::NewGuid().ToString('N').Substring(0,4))
$null = New-Item -ItemType Directory -Path $work
[IO.File]::WriteAllText((Join-Path $work 'eula.txt'), "eula=true`n")
$spec = Get-Content -LiteralPath "$root/build/local-$kind-launch.json" -Raw | ConvertFrom-Json
$resources = Join-Path $root 'build/resources/gametest'
$copy = Join-Path $work 'resources'
Copy-Item -LiteralPath $resources -Destination $copy -Recurse
$mod = Get-Content -LiteralPath (Join-Path $copy 'fabric.mod.json') -Raw | ConvertFrom-Json
$mod.entrypoints.$entry = @($Classes)
Write-VerificationJson (Join-Path $copy 'fabric.mod.json') $mod
$config = [IO.File]::ReadAllText((Join-Path $root '.gradle/loom-cache/launch.cfg')).Replace($resources, $copy)
$classpath = $spec.classpath.Replace($resources.Replace('\','\\'), $copy.Replace('\','\\')).Replace($resources, $copy)
$launchConfig = Join-Path $work 'launch.cfg'
[IO.File]::WriteAllText($launchConfig, $config, [Text.UTF8Encoding]::new($false))
$arguments = @($spec.jvmArgs | Where-Object { -not $_.StartsWith('@') -and -not $_.StartsWith('-Dfabric.dli.config=') })
$arguments += @('-Xmx2G', "-Dfabric.dli.config=$launchConfig", "-Dfabric-api.gametest.report-file=$work/native-tests.xml")
$arguments += @('-cp', $classpath, $spec.mainClass) + @($spec.args)
if ($Client) { $arguments += @('--gameDir', $work, '--width', '1280', '--height', '720') } else { $arguments += @('--universe', $work) }
$argFile = Join-Path $work 'launch.args'
Write-JavaArguments $argFile $arguments
$fingerprint = Get-VerificationFingerprint $root
$p = Start-Process -FilePath $spec.executable -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work 'test.log') -RedirectStandardError (Join-Path $work 'test.err.log')
$null = $p.Handle  # keep the handle so ExitCode survives (Windows PowerShell 5.1)
try {
    $deadline = [DateTime]::UtcNow.AddMinutes(8)
    while (-not $p.WaitForExit(1000)) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Focused test timed out after 8 minutes' }
    }
    "exit $($p.ExitCode)  work=$work"
    if ($p.ExitCode -ne 0) { throw "Focused test process failed. See $work/test.log" }
    if ($Client) {
        $evidence = Read-VerificationJson (Join-Path $work 'nullspace-test-evidence.json')
        Assert-VerificationInventory $Classes @($evidence.completed)
    } else {
        [xml]$x = Get-Content -LiteralPath (Join-Path $work 'native-tests.xml')
        $cases = @($x.SelectNodes('//testcase'))
        $failed = @($x.SelectNodes('//testcase/failure|//testcase/error|//testcase/skipped'))
        foreach ($c in $cases) {
            $failure = $c.SelectSingleNode('failure|error|skipped')
            '{0}  {1}' -f $(if ($failure) { 'FAIL' } else { 'pass' }), $c.name
        }
        $expected = @(Get-VerificationNativeInventory $root $Classes) + @('minecraft:always_pass')
        Assert-VerificationInventory $expected @($cases | ForEach-Object { $_.name })
        if ($failed.Count) { throw "$($failed.Count) focused tests failed or were skipped. See $work/native-tests.xml" }
    }
    Assert-VerificationFingerprint $root $fingerprint
    Write-VerificationJson (Join-Path $work 'result.json') @{status='passed';classes=$Classes;inputsSha256=$fingerprint;exitCode=0}
} finally {
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; $p.WaitForExit() }
    Remove-TestWorlds -Workspace $root -WorkRoot $work -ProcessIds @($p.Id) | Out-Null
}
