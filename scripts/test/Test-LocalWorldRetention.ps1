param([Parameter(Mandatory)][string] $ArtifactRoot)
$ErrorActionPreference='Stop'
$null=New-Item -ItemType Directory -Force -Path $ArtifactRoot
$workspace=(Resolve-Path -LiteralPath $ArtifactRoot).Path
function Check([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Run([string]$Root,[string]$Id,[string]$Status,[string]$Worker,[bool]$Preserve=$false) {
    $run=Join-Path $workspace "build/$Root/$Id"
    $work=if($Worker){Join-Path $run $Worker}else{$run}
    $null=New-Item -ItemType Directory -Force -Path (Join-Path $work 'world')
    [IO.File]::WriteAllText((Join-Path $work 'world/level.dat'),'world')
    [IO.File]::WriteAllText((Join-Path $work 'test.log'),'evidence')
    [IO.File]::WriteAllText((Join-Path $run 'result.json'),(@{status=$Status;preserveWorlds=$Preserve}|ConvertTo-Json))
    return $work
}
$crash=Run 'local-crash' '20260101-000000-aaaaaa' 'passed' 'launch/PREPARED'
$yard=Run 'shipyard-crash' '20260101-000000-bbbbbb' 'failed' 'INTENT_SAVED'
$multi=Run 'local-multiplayer' '20260101-000000-cccccc' 'passed' 'pilot'
$focused=Run 'ui-core-client' '20260101-000000' 'passed' ''
$active=Run 'local-multiplayer' '20260102-000000-dddddd' 'passed' 'pilot'
$running=Run 'star-systems-client' '20260101-000000' 'running' ''
$debug=Run 'local-crash' '20260103-000000-eeeeee' 'passed' 'launch/PREPARED' $true
$unknown=Run 'local-crash' 'unknown' 'passed' 'launch/PREPARED'
# Process discovery is injected so this fixture never depends on the desktop.
function Get-CimInstance { [pscustomobject]@{CommandLine=('java.exe @"'+(Join-Path (Split-Path -Parent $active) 'pilot/launch.args')+'"')} }
$removed=@(& (Join-Path $PSScriptRoot 'Remove-OldLocalTestWorlds.ps1') -Workspace $workspace)
Check (@($removed|Where-Object status -eq 'removed').Count -eq 4) 'Completed known local runners were not cleaned'
foreach($work in @($crash,$yard,$multi,$focused)) {
    Check (-not(Test-Path -LiteralPath (Join-Path $work 'world'))) 'A completed disposable world remains'
    Check ([IO.File]::ReadAllText((Join-Path $work 'test.log')) -eq 'evidence') 'Local retention damaged evidence'
}
foreach($work in @($active,$running,$debug,$unknown)) {
    Check (Test-Path -LiteralPath (Join-Path $work 'world/level.dat')) 'Local retention removed an active, preserved or unknown world'
}
# Missing command lines mean process ownership cannot be established safely.
function Get-CimInstance { [pscustomobject]@{CommandLine=$null} }
Check (@(& (Join-Path $PSScriptRoot 'Remove-OldLocalTestWorlds.ps1') -Workspace $workspace).Count -eq 0) 'Unknown Java ownership was ignored'
function Get-CimInstance { }
$removed=@(& (Join-Path $PSScriptRoot 'Remove-OldLocalTestWorlds.ps1') -Workspace $workspace)
Check (@($removed|Where-Object status -eq 'removed').Count -eq 1) 'Closed former active run was not cleaned'
[IO.File]::WriteAllText((Join-Path $workspace 'result.json'),(@{status='passed';cases=@('crash world','shipyard failure','multiplayer','focused client','live argument file','missing process command line','running receipt','debug preservation','unknown directory','retained evidence')}|ConvertTo-Json))
Write-Host 'Local runner world retention checks passed'
