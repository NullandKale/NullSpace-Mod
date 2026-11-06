param([string] $Workspace = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'TestWorldCleanup.psm1') -Force
$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path.TrimEnd('\','/')
$build = Join-Path $workspacePath 'build'
if (-not (Test-Path -LiteralPath $build)) { return }
if ((Get-Item -LiteralPath $build).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a redirected build directory' }
# Older runners did not record worker PIDs. Check their argument-file paths as
# well as session locks before reclaiming completed, recognisable test runs.
$java = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
$crashStages = @('PREPARED','DESTINATION_STAGED','SOURCE_CLEARED','PASSENGERS_MOVED','COMMITTED','TEMP_PREPARED','TEMP_SOURCE_CLEARED')
$yardStages = @('INTENT_SAVED','PARTIAL_UNSAVED','PARTIAL_SAVED','HULL_PLACED','SHIP_SAVED','WORLD_SAVED','ORDER_CLEARED')
foreach ($name in @('local-crash','shipyard-crash','local-multiplayer','ui-core-client','star-systems-client','machine-full-cubes-client')) {
    $root = Join-Path $build $name
    if (-not (Test-Path -LiteralPath $root)) { continue }
    if ((Get-Item -LiteralPath $root).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a redirected test root' }
    foreach ($run in Get-ChildItem -LiteralPath $root -Directory) {
        if ($run.Name -notmatch '^\d{8}-\d{6}(?:-[a-f0-9]{6})?$') { continue }
        if ($run.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a redirected test run' }
        $receiptPath = Join-Path $run.FullName 'result.json'
        if (-not (Test-Path -LiteralPath $receiptPath -PathType Leaf)) { continue }
        if ((Get-Item -LiteralPath $receiptPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a redirected receipt' }
        try { $receipt=Get-Content -LiteralPath $receiptPath -Raw|ConvertFrom-Json } catch { continue }
        if ($receipt.status -notin @('passed','failed') -or ($receipt.PSObject.Properties['preserveWorlds'] -and $receipt.preserveWorlds)) { continue }
        if (@($java | Where-Object { -not $_.CommandLine -or $_.CommandLine.Replace('/','\').IndexOf($run.FullName.Replace('/','\'),[StringComparison]::OrdinalIgnoreCase) -ge 0 }).Count) { continue }
        $workers = switch ($name) {
            'local-crash' { foreach ($direction in @('launch','landing')) { foreach ($stage in $crashStages) { Join-Path $run.FullName "$direction/$stage" } } }
            'shipyard-crash' { foreach ($stage in $yardStages) { Join-Path $run.FullName $stage } }
            'local-multiplayer' { foreach ($role in @('pilot','crew')) { Join-Path $run.FullName $role } }
            default { $run.FullName }
        }
        $cleanup = @(foreach ($worker in $workers) { Remove-TestWorlds -Workspace $workspacePath -WorkRoot $worker })
        if ($cleanup.Count) {
            $record = Join-Path $run.FullName 'world-cleanup.json'
            if ((Test-Path -LiteralPath $record) -and ((Get-Item -LiteralPath $record).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Refusing a redirected cleanup receipt' }
            [IO.File]::WriteAllText($record,(@{finishedUtc=[DateTime]::UtcNow.ToString('o');worlds=$cleanup}|ConvertTo-Json -Depth 6))
            $cleanup
        }
    }
}
