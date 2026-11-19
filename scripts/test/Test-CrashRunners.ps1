# Exercise the actual crash runners with small JVMs, without launching Minecraft.
param([string] $ArtifactRoot = '')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $PSScriptRoot '../lib/LocalVerification.psm1') -Force -DisableNameChecking
if (-not $ArtifactRoot) { $ArtifactRoot = Join-Path $workspace ('build/crash-runner-tests/' + [guid]::NewGuid().ToString('N')) }
$artifact = [IO.Path]::GetFullPath($ArtifactRoot)
$fixture = Join-Path $artifact 'fixture'
foreach ($directory in @('scripts/test','scripts/lib','src','gradle','build/classes')) { $null = New-Item -ItemType Directory -Force -Path (Join-Path $fixture $directory) }
foreach ($name in @('crash-test.ps1','shipyard-crash-test.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $fixture 'scripts/test') }
foreach ($name in @('LocalVerification.psm1','TestWorldCleanup.psm1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot "../lib/$name") -Destination (Join-Path $fixture 'scripts/lib') }
$launch = Read-VerificationJson (Join-Path $workspace 'build/local-server-launch.json')
$probe = Join-Path $fixture 'src/CrashProbe.java'
[IO.File]::WriteAllText($probe, @'
import java.nio.file.*;
public final class CrashProbe {
    public static void main(String[] args) throws Exception {
        String prefix = System.getProperty("nullspace.crash.mode") == null ? "nullspace.shipyard.crash." : "nullspace.crash.";
        String stage = System.getProperty(prefix + "stage");
        String mode = System.getProperty(prefix + "mode");
        Files.writeString(Path.of("attempts.log"), mode + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (stage.equals("PREPARED")) System.exit(7);
        if (mode.equals("prepare")) Runtime.getRuntime().halt(86);
        if (stage.equals("QUEUED")) System.exit(9);
        Files.writeString(Path.of(System.getProperty(prefix + "result")), "passed");
    }
}
'@)
$classes = Join-Path $fixture 'build/classes'
$javac = Join-Path (Split-Path -Parent $launch.executable) 'javac.exe'
& $javac -d $classes $probe
if ($LASTEXITCODE -ne 0) { throw 'Crash runner probe compilation failed' }
Write-VerificationJson (Join-Path $fixture 'build/local-server-launch.json') @{executable=$launch.executable;jvmArgs=@();classpath=$classes;mainClass='CrashProbe';args=@()}
function Check([bool] $Condition, [string] $Message) { if (-not $Condition) { throw $Message } }
$checks = @()
foreach ($kind in @('move','build')) {
    $script = Join-Path $fixture ('scripts/test/' + $(if ($kind -eq 'move') { 'crash-test.ps1' } else { 'shipyard-crash-test.ps1' }))
    $stages = if ($kind -eq 'move') { @('PREPARED','SOURCE_CLEARED','COMMITTED') } else { @('PREPARED','QUEUED','COMMITTED') }
    foreach ($workers in @(1,2)) {
        $run = Join-Path $fixture "build/$kind-$workers"
        $rejected = $false
        try { & $script -Stages $stages -Workers $workers -SkipBuild -ArtifactRoot $run *> (Join-Path $artifact "$kind-$workers.log") } catch { $rejected = $true }
        Check $rejected 'A failed subprocess must reject the run'
        $receipt = Read-VerificationJson (Join-Path $run 'result.json')
        Check ($receipt.status -eq 'failed' -and @($receipt.problems).Count -gt 0) 'Failures need a final receipt'
        $rows = if ($workers -eq 1) { @($receipt.results) } else {
            @(Get-ChildItem -LiteralPath $run -Directory -Filter 'worker-*' | ForEach-Object { (Read-VerificationJson (Join-Path $_.FullName 'result.json')).results })
        }
        $expected = if ($kind -eq 'move') { 6 } else { 3 }
        Check ($rows.Count -eq $expected) 'A failed checkpoint hid later independent checkpoints'
        Check (@($rows | Where-Object { $_.case -match 'COMMITTED$' -and $_.status -eq 'passed' }).Count -eq $(if ($kind -eq 'move') { 2 } else { 1 })) 'Later successful checkpoints must still complete'
        foreach ($trace in Get-ChildItem -LiteralPath $run -Recurse -Filter 'attempts.log') {
            if ($trace.Directory.Name -eq 'PREPARED') { Check (([IO.File]::ReadAllText($trace.FullName)).Trim() -eq 'prepare') 'Verify must not run after a failed prepare' }
        }
        $checks += "$kind-$workers failures collected"
    }
    $passing = Join-Path $fixture "build/$kind-passing"
    & $script -Stages @('COMMITTED') -SkipBuild -ArtifactRoot $passing *> (Join-Path $artifact "$kind-passing.log")
    Check ((Read-VerificationJson (Join-Path $passing 'result.json')).status -eq 'passed') 'A complete prepare/verify pair must pass'
    $checks += "$kind passing pair"
}
Write-VerificationJson (Join-Path $artifact 'result.json') @{status='passed';checks=$checks;scope='Actual runner orchestration with small JVMs; no Minecraft recovery claims'}
Write-Host "Crash runner checks passed: $artifact"
