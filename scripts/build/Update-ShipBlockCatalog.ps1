param([string] $Report)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Report) {
    $latestReport = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build/run/gameTest-sessions') -Filter 'ship-block-support.json' -File -Recurse |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latestReport) { throw 'Run .\scripts\test\test.ps1 first to generate the tested vanilla block catalog.' }
    $Report = $latestReport.FullName
}
$rows = Get-Content -LiteralPath $Report -Raw | ConvertFrom-Json
if ($rows.Count -lt 1000) { throw 'The registry catalog is incomplete; no documentation was updated.' }
$builder = [System.Text.StringBuilder]::new()
$null = $builder.AppendLine('# Vanilla ship block catalog')
$null = $builder.AppendLine()
$null = $builder.AppendLine('Generated from the pinned Minecraft registry and the same policy used by ship preflight. Run `./scripts/test/test.ps1`, then `./scripts/build/Update-ShipBlockCatalog.ps1` to refresh. See [the support guide](ship-block-support.md) for gameplay rules and verification scope.')
$null = $builder.AppendLine()
foreach ($status in @('SUPPORTED','CONDITIONAL','UNSUPPORTED')) {
    $count = @($rows | Where-Object { $_.status -eq $status }).Count
    $null = $builder.AppendLine("- ${status}: $count block IDs")
}
$null = $builder.AppendLine()
$null = $builder.AppendLine('These are **transport eligibility** labels, not proof that every possible state or arrangement works. Every row requires a connected, bounded ship; no pistons mid-move or queued block events (pending ticks travel with the ship); an intact cabin and usable exit; and clear launch/landing columns. Conditions describe additional restrictions. Supported transport does not make a partial block airtight, retarget respawn/compass bindings, or supply a machine with power, water, fuel, or a valid vanilla structure.')
$null = $builder.AppendLine()
$null = $builder.AppendLine('| Block ID | Name | Transport | Block entity | Additional conditions |')
$null = $builder.AppendLine('| --- | --- | --- | --- | --- |')
foreach ($row in $rows) {
    $name = $row.name.Replace('|','\|')
    $notes = ($row.notes -join ' ').Replace('|','\|').Replace("`r",' ').Replace("`n",' ')
    $entity = if ($row.blockEntity) { '`' + $row.blockEntity + '`' } else { '-' }
    $null = $builder.AppendLine(('| `{0}` | {1} | {2} | {3} | {4} |' -f $row.id,$name,$row.status,$entity,$notes))
}
$encoding = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText((Join-Path $projectRoot 'docs/development/ship-block-catalog.md'),$builder.ToString(),$encoding)
[System.IO.File]::WriteAllText((Join-Path $projectRoot 'docs/development/ship-block-catalog.json'),(Get-Content -LiteralPath $Report -Raw),$encoding)
Write-Host "Updated ship block catalog: $($rows.Count) vanilla block IDs from $Report"
