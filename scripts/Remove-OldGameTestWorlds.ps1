param(
    [string] $Workspace = (Split-Path -Parent $PSScriptRoot),
    [ValidateRange(1, 100)] [int] $Keep = 4
)
$ErrorActionPreference = 'Stop'
$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path.TrimEnd('\', '/')
$expectedRoot = Join-Path $workspacePath 'build/run/gameTest-sessions'
if (-not (Test-Path -LiteralPath $expectedRoot)) { return }
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
    $lock = Join-Path $target 'world/session.lock'
    if (Test-Path -LiteralPath $lock) {
        try {
            $probe = [IO.File]::Open($lock, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            $probe.Dispose()
        } catch [IO.IOException] { continue }
    }
    # Refuse directory junctions inside a candidate rather than traversing another location.
    if (@(Get-ChildItem -LiteralPath $target -Recurse -Force -Attributes ReparsePoint).Count -ne 0) {
        throw 'Refusing a generated world containing redirected paths'
    }
    Remove-Item -LiteralPath $target -Recurse -Force
    Write-Output ('Pruned disposable server-test session ' + $candidate.Name)
}
