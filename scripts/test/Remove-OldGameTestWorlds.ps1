param(
    [string] $Workspace = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [ValidateRange(0, 100)] [int] $Keep = 0
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../lib/TestWorldCleanup.psm1') -Force
$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path.TrimEnd('\', '/')
$expectedRoot = Join-Path $workspacePath 'build/run/gameTest-sessions'
if (-not (Test-Path -LiteralPath $expectedRoot)) { return }
foreach ($ancestor in @((Join-Path $workspacePath 'build'),(Join-Path $workspacePath 'build/run'))) {
    if ((Get-Item -LiteralPath $ancestor).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a redirected generated-world parent' }
}
$root = (Resolve-Path -LiteralPath $expectedRoot).Path
if ($root -ne [IO.Path]::GetFullPath($expectedRoot) -or (Get-Item -LiteralPath $root).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'Refusing an unexpected or redirected generated-world root'
}
$candidates = @(Get-ChildItem -LiteralPath $root -Directory | Where-Object {
    $sessionId = [guid]::Empty
    [guid]::TryParseExact($_.Name, 'D', [ref] $sessionId) -and -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint)
} | Sort-Object LastWriteTime -Descending | Select-Object -Skip $Keep)
foreach ($candidate in $candidates) {
    $target = (Resolve-Path -LiteralPath $candidate.FullName).Path
    if ((Split-Path -Parent $target) -ne $root) { throw 'Generated world escaped its expected parent' }
    Remove-TestWorlds -Workspace $workspacePath -WorkRoot $target -Leaves world
    # The session directory contains only the world today. Preserve any future
    # reports/configuration rather than treating all session contents as waste.
    if (@(Get-ChildItem -LiteralPath $target -Force).Count -eq 0) { Remove-Item -LiteralPath $target }
}
