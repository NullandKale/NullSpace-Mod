param(
    [Parameter(Mandatory)][string]$Executable,
    [Parameter(Mandatory)][string]$ArgumentFile,
    [Parameter(Mandatory)][string]$Work,
    [Parameter(Mandatory)][string]$Session,
    [switch]$SmokeTest
)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'LocalVerification.psm1') -Force -DisableNameChecking
$Host.UI.RawUI.WindowTitle='NullSpace local multiplayer server'
$start=[Diagnostics.ProcessStartInfo]::new()
$start.FileName=$Executable
$start.Arguments='@"'+$ArgumentFile+'"'
$start.WorkingDirectory=$Work
$start.UseShellExecute=$false
$start.RedirectStandardInput=[bool]$SmokeTest
$process=[Diagnostics.Process]::Start($start)
$null=$process.Handle
Write-VerificationJson (Join-Path $Session 'server-process.json') @{pid=$process.Id;startedUtc=[DateTime]::UtcNow.ToString('o')}
Write-Host 'Server world:' $Work
if (-not $SmokeTest) { Write-Host 'Type stop to save and shut down the server.' }
try {
    while (-not $process.WaitForExit(250)) {
        if ($SmokeTest -and (Test-Path -LiteralPath (Join-Path $Session 'stop-server'))) {
            $process.StandardInput.WriteLine('stop')
            $process.StandardInput.Flush()
            if (-not $process.WaitForExit(60000)) { throw 'Server did not stop after its save request' }
        }
    }
    Write-VerificationJson (Join-Path $Session 'server-exit.json') @{exitCode=$process.ExitCode;finishedUtc=[DateTime]::UtcNow.ToString('o')}
    Write-Host ('Server stopped, exit '+$process.ExitCode+'. World and logs are saved.')
} finally {
    # Normal interactive runs are owned by their console. Only the automated
    # smoke session is forcibly stopped if graceful shutdown fails.
    if ($SmokeTest -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
}
