param([string] $ArtifactRoot = '')
$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $PSScriptRoot '../lib/LocalVerification.psm1') -Force -DisableNameChecking
if (-not $ArtifactRoot) { $ArtifactRoot = Join-Path $workspacePath ('build/evidence-tests/' + [guid]::NewGuid().ToString('N')) }
$artifactPath = [IO.Path]::GetFullPath($ArtifactRoot)
$null = New-Item -ItemType Directory -Path $artifactPath
$classes = Join-Path $artifactPath 'classes'
$null = New-Item -ItemType Directory -Path $classes
$spec = Get-Content -LiteralPath (Join-Path $workspacePath 'build/local-server-launch.json') -Raw | ConvertFrom-Json
$probe = Join-Path $artifactPath 'EvidenceProbe.java'
[IO.File]::WriteAllText($probe,@'
package com.nullandkale.nullspace;
import java.nio.file.*;
public final class EvidenceProbe {
  public static void main(String[] args) throws Exception {
    Files.writeString(Path.of(args[1]), "ready");
    ClientTestEvidence.publishReceipt(Path.of(args[0]), "{\"completed\":[\"probe\"]}");
  }
}
'@)
$javac = Join-Path (Split-Path -Parent $spec.executable) 'javac.exe'
$source = Join-Path $workspacePath 'src/gametest/java/com/nullandkale/nullspace/ClientTestEvidence.java'
$compileArgs = Join-Path $artifactPath 'compile.args'
Write-JavaArguments $compileArgs @('-cp',$spec.classpath,'-d',$classes,$source,$probe)
& $javac ('@' + $compileArgs)
if ($LASTEXITCODE -ne 0) { throw 'Evidence probe compilation failed' }
foreach ($mode in @('unlocked','temporary-lock','persistent-lock')) {
    $work = Join-Path $artifactPath $mode
    $null = New-Item -ItemType Directory -Path $work
    $receipt = Join-Path $work 'evidence.json'
    $ready = Join-Path $work 'ready'
    $previous = '{"completed":[]}'
    [IO.File]::WriteAllText($receipt,$previous)
    $handle = $null
    $process = $null
    try {
        if ($mode -ne 'unlocked') {
            # Deny delete/rename, matching transient Windows readers and scanners.
            $handle = [IO.File]::Open($receipt,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
        }
        $argFile = Join-Path $work 'java.args'
        Write-JavaArguments $argFile @('-cp',($classes + [IO.Path]::PathSeparator + $spec.classpath),'com.nullandkale.nullspace.EvidenceProbe',$receipt,$ready)
        $process = Start-Process -FilePath $spec.executable -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work 'test.log') -RedirectStandardError (Join-Path $work 'test.err.log')
        $null = $process.Handle
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while (-not (Test-Path -LiteralPath $ready)) {
            if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Evidence probe did not start' }
            Start-Sleep -Milliseconds 20
        }
        if ($mode -eq 'temporary-lock') {
            while (-not (Test-Path -LiteralPath ($receipt + '.tmp'))) {
                if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Evidence probe did not attempt publication' }
                Start-Sleep -Milliseconds 20
            }
            Start-Sleep -Milliseconds 200
            if ($process.HasExited -or [IO.File]::ReadAllText($receipt) -ne $previous) { throw 'A temporary lock did not preserve the previous complete receipt' }
            $handle.Dispose(); $handle = $null
        }
        if (-not $process.WaitForExit(15000)) { throw 'Evidence retry exceeded its bounded deadline' }
        if ($handle) { $handle.Dispose(); $handle = $null }
        if ($mode -eq 'persistent-lock') {
            if ($process.ExitCode -eq 0 -or [IO.File]::ReadAllText($receipt) -ne $previous) { throw 'A persistent lock must fail without publishing success' }
            if ([IO.File]::ReadAllText((Join-Path $work 'test.err.log')) -notmatch 'AccessDeniedException') { throw 'Persistent-lock failure did not report its cause' }
        } else {
            if ($process.ExitCode -ne 0) { throw "Evidence probe failed: $mode" }
            $actual = Read-VerificationJson $receipt
            Assert-VerificationInventory @('probe') @($actual.completed)
        }
    } finally {
        if ($handle) { $handle.Dispose() }
        if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    }
}
Write-VerificationJson (Join-Path $artifactPath 'result.json') @{status='passed';cases=@('unlocked','temporary-lock','persistent-lock');sourceSha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()}
Write-Host "Client evidence lock regressions passed: $artifactPath"
