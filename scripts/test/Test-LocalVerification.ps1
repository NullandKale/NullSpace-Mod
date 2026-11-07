param([string] $ArtifactRoot = '')
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../lib/LocalVerification.psm1') -Force -DisableNameChecking
if(-not $ArtifactRoot){$ArtifactRoot=Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) ('build/harness-tests/'+[guid]::NewGuid().ToString('N'))}
$null=New-Item -ItemType Directory -Force -Path $ArtifactRoot
& (Join-Path $PSScriptRoot 'Test-GameTestRetention.ps1') -ArtifactRoot (Join-Path $ArtifactRoot 'retention')
& (Join-Path $PSScriptRoot 'Test-ParallelWorldRetention.ps1') -ArtifactRoot (Join-Path $ArtifactRoot 'parallel-retention')
& (Join-Path $PSScriptRoot 'Test-TestWorldCleanup.ps1') -ArtifactRoot (Join-Path $ArtifactRoot 'world-cleanup')
& (Join-Path $PSScriptRoot 'Test-LocalWorldRetention.ps1') -ArtifactRoot (Join-Path $ArtifactRoot 'local-retention')
& (Join-Path $PSScriptRoot 'Test-LocalPlay.ps1') -ArtifactRoot (Join-Path $ArtifactRoot 'local-play')
& (Join-Path $PSScriptRoot 'Test-GraphicalWindows.ps1') -ArtifactRoot (Join-Path $ArtifactRoot 'window-layout')
$fixture=Join-Path $ArtifactRoot 'fixture'
foreach($directory in @('src','gradle','scripts','docs','build')){$null=New-Item -ItemType Directory -Force -Path (Join-Path $fixture $directory)}
function Check([bool] $Condition,[string] $Message){if(-not $Condition){throw $Message}}
function Reject([scriptblock] $Action){$rejected=$false;try{& $Action}catch{$rejected=$true};Check $rejected 'Expected rejection'}
$source=Join-Path $fixture 'src/Example.java'
[IO.File]::WriteAllText($source,'before')
$initial=Get-VerificationFingerprint $fixture
Assert-VerificationFingerprint $fixture $initial
[IO.File]::WriteAllText((Join-Path $fixture 'docs/note.md'),'documentation')
[IO.File]::WriteAllText((Join-Path $fixture 'build/log.txt'),'log output')
Check ((Get-VerificationFingerprint $fixture) -eq $initial) 'Build output and documentation must not invalidate a run'
[IO.File]::WriteAllText($source,'after')
Reject { Assert-VerificationFingerprint $fixture $initial }
[IO.File]::WriteAllText($source,'before')
Assert-VerificationFingerprint $fixture $initial
$shader=Join-Path $fixture 'src/space.glsl'
[IO.File]::WriteAllText($shader,'shader')
Reject { Assert-VerificationFingerprint $fixture $initial }
Remove-Item -LiteralPath $shader
Assert-VerificationFingerprint $fixture $initial
Move-Item -LiteralPath $source -Destination (Join-Path $fixture 'src/Renamed.java')
Reject { Assert-VerificationFingerprint $fixture $initial }
$marker=Join-Path $ArtifactRoot 'marker'
Reject { Assert-VerificationMarker $marker }
[IO.File]::WriteAllText($marker,'wrong')
Reject { Assert-VerificationMarker $marker }
[IO.File]::WriteAllText($marker,"passed`n")
Assert-VerificationMarker $marker
Assert-VerificationInventory @('A','B') @('B','A')
Reject { Assert-VerificationInventory @('A','B') @('A') }
Reject { Assert-VerificationInventory @('A','B') @('A','A') }
Reject { Assert-VerificationInventory @('A','B') @('A','C') }
Reject { Assert-VerificationInventory @() @() }
$inventory = @((Get-Content -LiteralPath (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'src/gametest/resources/fabric.mod.json') -Raw | ConvertFrom-Json).entrypoints.'fabric-client-gametest')
foreach ($workers in @(1,2,6,8)) {
    $shards = @(Get-VerificationShards $inventory $workers)
    Check ($shards.Count -eq [Math]::Min($workers,$inventory.Count)) "Wrong worker count for $workers workers"
    $assigned = @($shards | ForEach-Object { $_.classes })
    Assert-VerificationInventory $inventory $assigned
    $sizes = @($shards | ForEach-Object { $_.classes.Count })
    Check ((($sizes | Measure-Object -Maximum).Maximum - ($sizes | Measure-Object -Minimum).Minimum) -le 1) 'Uneven shard distribution'
    Check (@($sizes | Where-Object { $_ -eq 0 }).Count -eq 0) 'Empty worker'
}
$small = @(Get-VerificationShards @('A','B','C') 8)
Check ($small.Count -eq 3) 'Do not launch empty workers when the inventory is small'
Assert-VerificationInventory @('A','B','C') @($small | ForEach-Object { $_.classes })
Reject { Get-VerificationShards $inventory 0 }
Reject { Get-VerificationShards $inventory 9 }
Reject { Get-VerificationShards @() 8 }
Reject { Get-VerificationShards @('A','A') 8 }
Assert-VerificationDiskSpace 4GB 4GB
Assert-VerificationDiskSpace 5GB 4GB
Reject { Assert-VerificationDiskSpace 3GB 4GB }
Reject { Assert-VerificationDiskSpace -1 4GB }
Reject { Assert-VerificationDiskSpace 5GB 0 }
$weighted=@(Get-VerificationShards @('A','B','C','D','E','F') 2 -Durations @{A=100;B=1;C=100;D=1;E=1;F=1})
Assert-VerificationInventory @('A','B','C','D','E','F') @($weighted|ForEach-Object{$_.classes})
Check (($weighted|Measure-Object cost -Maximum).Maximum -eq 102) 'Long tests must be separated instead of clustered by registration order'
Reject { Get-VerificationShards @('A','B') 2 -Durations @{A=-1} }
Reject { Get-VerificationShards @('A','B') 2 -Durations @{A=[double]::NaN} }
$history=Join-Path $fixture 'build/timing-history'
foreach($runName in @('old','new','failed','malformed')){$null=New-Item -ItemType Directory -Force -Path (Join-Path $history "$runName/graphics-1")}
foreach($runName in @('old','new','failed')){
    Write-VerificationJson (Join-Path $history "$runName/result.json") @{status=$(if($runName -eq 'failed'){'failed'}else{'passed'});jobs=@(@{name='graphics-1'})}
    Write-VerificationJson (Join-Path $history "$runName/graphics-1/nullspace-test-evidence.json") @{completed=@('A','B');timings=@{A=@{milliseconds=$(if($runName -eq 'failed'){999}else{100})};B=@{milliseconds=20};notCompleted=@{milliseconds=900}}}
}
[IO.File]::WriteAllText((Join-Path $history 'malformed/result.json'),'{')
$timings=Get-VerificationDurations $history
Check ($timings -is [hashtable] -and $timings.Count -eq 2 -and $timings.A -eq 100 -and $timings.B -eq 20) 'Scheduling may use only completed timings from passing runs'
Write-VerificationJson (Join-Path $fixture 'shared.json') @{marker='complete'}
Check ((Read-VerificationJson (Join-Path $fixture 'shared.json')).marker -eq 'complete') 'Shared receipt reader failed'
$nativeResources=Join-Path $fixture 'src/gametest/resources'
$nativeSources=Join-Path $fixture 'src/gametest/java/example'
$null=New-Item -ItemType Directory -Force -Path $nativeResources,$nativeSources
Write-VerificationJson (Join-Path $nativeResources 'fabric.mod.json') @{id='fixture-test'}
[IO.File]::WriteAllText((Join-Path $nativeSources 'FooGameTest.java'),"package example;`nclass FooGameTest {`n@GameTest(maxTicks=7)`npublic void utf8ByteLimits() {}`n@GameTest`npublic void simpleRun() {}`n}")
$nativeNames=@(Get-VerificationNativeInventory $fixture @('example.FooGameTest'))
Assert-VerificationInventory @('fixture-test:foo_game_test_utf8byte_limits','fixture-test:foo_game_test_simple_run') $nativeNames
Reject { Get-VerificationNativeInventory $fixture @('../escape') }
[IO.File]::WriteAllText((Join-Path $nativeSources 'EmptyGameTest.java'),'class EmptyGameTest {}')
Reject { Get-VerificationNativeInventory $fixture @('example.EmptyGameTest') }
Write-VerificationJson (Join-Path $fixture 'src/gametest/resources/fabric.mod.json') @{id='fixture-test';entrypoints=@{'fabric-gametest'=@('example.FooGameTest')}}
Assert-VerificationNativeRegistration $fixture
[IO.File]::WriteAllText((Join-Path $nativeSources 'LostGameTest.java'),"package example;`nclass LostGameTest {`n@GameTest`npublic void unregisteredCase() {}`n}")
Reject { Assert-VerificationNativeRegistration $fixture }
Write-VerificationJson (Join-Path $ArtifactRoot 'result.json') @{status='passed';checks=38;shardWorkers=@(1,2,6,8);retentionResult='retention/result.json';parallelRetentionResult='parallel-retention/result.json';worldCleanupResult='world-cleanup/result.json';localRetentionResult='local-retention/result.json';localPlayResult='local-play/result.json';windowLayoutResult='window-layout/result.json';fingerprint=$initial}
Write-Host 'Local verification harness checks passed'
