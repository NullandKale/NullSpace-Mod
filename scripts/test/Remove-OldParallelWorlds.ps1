param(
    [string] $Workspace = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [ValidateRange(0, 100)] [int] $Keep = 0,
    [ValidateRange(0, 100)] [int] $KeepFailed = 0
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../lib/TestWorldCleanup.psm1') -Force
$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path.TrimEnd('\','/')
$root = Join-Path $workspacePath 'build/parallel-verification'
if (-not (Test-Path -LiteralPath $root)) { return }
foreach ($ancestor in @((Join-Path $workspacePath 'build'),$root)) {
    if ((Get-Item -LiteralPath $ancestor).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a redirected parallel-verification root' }
}
$runs = @(foreach ($directory in Get-ChildItem -LiteralPath $root -Directory) {
    if ($directory.Name -notmatch '^\d{8}-\d{6}-[a-f0-9]{6}$') { continue }
    if ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a redirected test run' }
    $receiptPath = Join-Path $directory.FullName 'result.json'
    if (-not (Test-Path -LiteralPath $receiptPath -PathType Leaf)) { continue }
    if ((Get-Item -LiteralPath $receiptPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a redirected test receipt' }
    try {
        $receipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
        if ($receipt.status -notin @('passed','passed-with-unit-skips','failed') -or -not $receipt.finishedUtc -or -not $receipt.jobs) { continue }
        if ($receipt.PSObject.Properties['preserveWorlds'] -and $receipt.preserveWorlds) { continue }
        [pscustomobject]@{path=$directory.FullName;receipt=$receipt;finished=[DateTime]::Parse($receipt.finishedUtc)}
    } catch { continue }
})
foreach ($group in @('passing','failed')) {
    $selected = @($runs | Where-Object { ($_.receipt.status -eq 'failed') -eq ($group -eq 'failed') } | Sort-Object finished -Descending)
    $retain = if ($group -eq 'failed') { $KeepFailed } else { $Keep }
    foreach ($run in @($selected | Select-Object -Skip $retain)) {
        $cleanup = @(foreach ($job in @($run.receipt.jobs)) {
            if ($job.name -notmatch '^(server(?:-\d+)?|graphics-\d+)$') { continue }
            $jobRoot = Join-Path $run.path $job.name
            $leaf = if ($job.name.StartsWith('server')) {'world'} else {'saves'}
            $ids = if ($job.PSObject.Properties['pid']) { @([int]$job.pid) } else { @() }
            Remove-TestWorlds -Workspace $workspacePath -WorkRoot $jobRoot -Leaves $leaf -ProcessIds $ids
        })
        if ($cleanup.Count) {
            $record = Join-Path $run.path 'world-cleanup.json'
            if ((Test-Path -LiteralPath $record) -and ((Get-Item -LiteralPath $record).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Refusing a redirected cleanup receipt' }
            [IO.File]::WriteAllText($record,(@{finishedUtc=[DateTime]::UtcNow.ToString('o');worlds=$cleanup} | ConvertTo-Json -Depth 6))
            $cleanup
        }
    }
}
