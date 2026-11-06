param([Parameter(Mandatory)][string] $ArtifactRoot)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'TestWorldCleanup.psm1') -Force
$null = New-Item -ItemType Directory -Force -Path $ArtifactRoot
$workspace = (Resolve-Path -LiteralPath $ArtifactRoot).Path
$worker = Join-Path $workspace 'build/worker'
function Check([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Reject([scriptblock]$Action) {
    $rejected=$false
    try { & $Action | Out-Null } catch { $rejected=$true }
    Check $rejected 'Expected an unsafe cleanup target to be rejected'
}
function World([string]$Leaf='world') {
    $path = Join-Path $worker $Leaf
    $null = New-Item -ItemType Directory -Force -Path $path
    [IO.File]::WriteAllText((Join-Path $path 'level.dat'),'world')
}
World
World 'saves/Test'
foreach ($name in @('test.log','native-tests.xml','nullspace-test-evidence.json','screenshot.png','result.json','launch.args')) {
    [IO.File]::WriteAllText((Join-Path $worker $name),'evidence')
}
$active=@(Remove-TestWorlds -Workspace $workspace -WorkRoot $worker -ProcessIds @($PID))
Check ($active.Count -eq 1 -and $active[0].status -eq 'skipped-active-process') 'Cleanup ignored a running worker'
Check (Test-Path -LiteralPath (Join-Path $worker 'world/level.dat')) 'Active worker world was removed'
[IO.File]::WriteAllText((Join-Path $worker 'world/session.lock'),'lock')
$handle=[IO.File]::Open((Join-Path $worker 'world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
    $cleanup=@(Remove-TestWorlds -Workspace $workspace -WorkRoot $worker)
    Check (@($cleanup|Where-Object status -eq 'skipped-locked-world').Count -eq 1) 'Locked world was not reported'
    Check (Test-Path -LiteralPath (Join-Path $worker 'world/level.dat')) 'Locked world was removed'
    Check (-not(Test-Path -LiteralPath (Join-Path $worker 'saves'))) 'Closed sibling saves were retained'
} finally { $handle.Dispose() }
$cleanup=@(Remove-TestWorlds -Workspace $workspace -WorkRoot $worker)
Check ($cleanup.Count -eq 1 -and $cleanup[0].bytes -eq 9 -and $cleanup[0].status -eq 'removed') 'Closed world cleanup byte accounting failed'
Check (@(Remove-TestWorlds -Workspace $workspace -WorkRoot $worker).Count -eq 0) 'Repeated cleanup was not idempotent'
foreach ($name in @('test.log','native-tests.xml','nullspace-test-evidence.json','screenshot.png','result.json','launch.args')) {
    Check ([IO.File]::ReadAllText((Join-Path $worker $name)) -eq 'evidence') 'Cleanup damaged retained evidence'
}
$protected = Join-Path $workspace 'run/saves/World 5'
$null=New-Item -ItemType Directory -Force -Path $protected
[IO.File]::WriteAllText((Join-Path $protected 'precious'),'preserve')
Reject { Remove-TestWorlds -Workspace $workspace -WorkRoot $protected }
Reject { Remove-TestWorlds -Workspace $workspace -WorkRoot (Join-Path $workspace 'build/../run') }
Reject { Remove-TestWorlds -Workspace $workspace -WorkRoot $worker -Leaves @('../run','logs') }
World
$null=New-Item -ItemType Junction -Path (Join-Path $worker 'world/redirected') -Value $protected
Reject { Remove-TestWorlds -Workspace $workspace -WorkRoot $worker }
Check ([IO.File]::ReadAllText((Join-Path $protected 'precious')) -eq 'preserve') 'Cleanup followed a redirected descendant'
$null=New-Item -ItemType Junction -Path (Join-Path $workspace 'build/redirected') -Value $protected
Reject { Remove-TestWorlds -Workspace $workspace -WorkRoot (Join-Path $workspace 'build/redirected/nested') }
# The redirected worker itself must be rejected even when no world exists yet.
Reject { Remove-TestWorlds -Workspace $workspace -WorkRoot (Join-Path $workspace 'build/redirected') }
[IO.File]::WriteAllText((Join-Path $workspace 'result.json'),(@{status='passed';cases=@('active process','locked world','closed sibling','byte accounting','idempotency','evidence preserved','player saves','path traversal','leaf allowlist','redirected descendants','redirected ancestors')}|ConvertTo-Json))
Write-Host 'Shared test world cleanup checks passed'
