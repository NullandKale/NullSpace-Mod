param([string] $ArtifactRoot)
$ErrorActionPreference = 'Stop'
$workspace = Join-Path $ArtifactRoot 'fixture'
$root = Join-Path $workspace 'build/parallel-verification'
$null = New-Item -ItemType Directory -Force -Path $root
function Make-Run([string]$Id,[string]$Status,[string]$Finished) {
    $run = Join-Path $root $Id
    foreach ($job in @('server','graphics-1')) {
        $leaf=if($job -eq 'server'){'world'}else{'saves/New World'}
        $null=New-Item -ItemType Directory -Force -Path (Join-Path $run "$job/$leaf")
        [IO.File]::WriteAllText((Join-Path $run "$job/$leaf/level.dat"),'test world')
        [IO.File]::WriteAllText((Join-Path $run "$job/test.log"),'evidence')
    }
    [IO.File]::WriteAllText((Join-Path $run 'result.json'),(@{status=$Status;finishedUtc=$Finished;jobs=@(@{name='server'},@{name='graphics-1'})}|ConvertTo-Json -Depth 4))
    return $run
}
$old=Make-Run '20260101-000000-aaaaaa' 'passed' '2026-01-01T00:00:00Z'
$recent=Make-Run '20260102-000000-bbbbbb' 'passed-with-unit-skips' '2026-01-02T00:00:00Z'
$failed=Make-Run '20260103-000000-cccccc' 'failed' '2026-01-03T00:00:00Z'
$running=Make-Run '20260104-000000-dddddd' 'running' '2026-01-04T00:00:00Z'
$active=Make-Run '20251231-000000-eeeeee' 'passed' '2025-12-31T00:00:00Z'
$sentinels=@((Join-Path $workspace 'run/saves/World 5'),(Join-Path $workspace 'run/backups'),(Join-Path $root 'unknown'),(Join-Path $workspace 'build/recovery'))
foreach($path in $sentinels){$null=New-Item -ItemType Directory -Force -Path $path;[IO.File]::WriteAllText((Join-Path $path 'precious'),'preserve')}
$lockPath=Join-Path $active 'server/world/session.lock'
[IO.File]::WriteAllText($lockPath,'lock')
$handle=[IO.File]::Open($lockPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
    $removed=@(& (Join-Path $PSScriptRoot 'Remove-OldParallelWorlds.ps1') -Workspace $workspace -Keep 1)
    if(Test-Path -LiteralPath (Join-Path $old 'server/world')){throw 'Old closed native world was retained'}
    if(Test-Path -LiteralPath (Join-Path $old 'graphics-1/saves')){throw 'Old closed graphical worlds were retained'}
    if(Test-Path -LiteralPath (Join-Path $failed 'server/world')){throw 'Failed-run worlds accumulated instead of being cleaned'}
    foreach($run in @($recent,$running)) {
        if(-not(Test-Path -LiteralPath (Join-Path $run 'server/world/level.dat'))){throw 'Retention removed a recent or running world'}
    }
    if(-not(Test-Path -LiteralPath (Join-Path $active 'server/world/level.dat'))){throw 'Retention removed an active world'}
    foreach($run in @($old,$recent,$failed,$running,$active)) {
        if([IO.File]::ReadAllText((Join-Path $run 'server/test.log')) -ne 'evidence'){throw 'Retention changed a test log'}
        if(-not(Test-Path -LiteralPath (Join-Path $run 'result.json'))){throw 'Retention removed a receipt'}
    }
} finally {$handle.Dispose()}
foreach($path in $sentinels){if([IO.File]::ReadAllText((Join-Path $path 'precious')) -ne 'preserve'){throw 'Retention changed protected data'}}
$kept=Make-Run '20260105-000000-111111' 'failed' '2026-01-05T00:00:00Z'
$preserved=Make-Run '20260106-000000-222222' 'passed' '2026-01-06T00:00:00Z'
$receipt=Get-Content -LiteralPath (Join-Path $preserved 'result.json') -Raw|ConvertFrom-Json
$receipt|Add-Member -NotePropertyName preserveWorlds -NotePropertyValue $true
[IO.File]::WriteAllText((Join-Path $preserved 'result.json'),($receipt|ConvertTo-Json -Depth 5))
& (Join-Path $PSScriptRoot 'Remove-OldParallelWorlds.ps1') -Workspace $workspace -Keep 0 -KeepFailed 1 | Out-Null
if(Test-Path -LiteralPath (Join-Path $recent 'server/world')){throw 'Keep zero did not clean the newest passing run'}
foreach($run in @($kept,$preserved)) {
    if(-not(Test-Path -LiteralPath (Join-Path $run 'server/world/level.dat'))){throw 'Debug world retention was ignored'}
}
& (Join-Path $PSScriptRoot 'Remove-OldParallelWorlds.ps1') -Workspace $workspace | Out-Null
if(Test-Path -LiteralPath (Join-Path $kept 'server/world')){throw 'Default cleanup retained failed worlds'}
# An apparently valid worker redirected toward other data must be rejected.
$redirected=Make-Run '20250101-000000-ffffff' 'passed' '2025-01-01T00:00:00Z'
$linkPath=Join-Path $redirected 'graphics-2'
$null=New-Item -ItemType Junction -Path $linkPath -Value $sentinels[0]
$receipt=Get-Content -LiteralPath (Join-Path $redirected 'result.json') -Raw|ConvertFrom-Json
$receipt.jobs+=@{name='graphics-2'}
[IO.File]::WriteAllText((Join-Path $redirected 'result.json'),($receipt|ConvertTo-Json -Depth 5))
$rejected=$false
try { & (Join-Path $PSScriptRoot 'Remove-OldParallelWorlds.ps1') -Workspace $workspace -Keep 1 | Out-Null } catch {$rejected=$true}
if(-not $rejected){throw 'Retention followed a redirected worker'}
if([IO.File]::ReadAllText((Join-Path $sentinels[0] 'precious')) -ne 'preserve'){throw 'Retention traversed the redirected target'}
[IO.File]::WriteAllText((Join-Path $ArtifactRoot 'result.json'),(@{status='passed';cases=@('recent passing runs','old closed worlds','active locks','failed runs cleaned','running runs retained','logs and receipts','player saves and backups','recovery files','unknown folders','junction rejection','keep zero','failed retention count','explicit debug preservation')}|ConvertTo-Json))
Write-Output 'Parallel world retention checks passed'
