param([string] $ArtifactRoot = '')
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'LocalVerification.psm1') -Force -DisableNameChecking
if(-not $ArtifactRoot){$ArtifactRoot=Join-Path (Split-Path $PSScriptRoot -Parent) ('build/harness-tests/'+[guid]::NewGuid().ToString('N'))}
$null=New-Item -ItemType Directory -Force -Path $ArtifactRoot
& (Join-Path $PSScriptRoot 'Test-GameTestRetention.ps1') -ArtifactRoot (Join-Path $ArtifactRoot 'retention')
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
Write-VerificationJson (Join-Path $ArtifactRoot 'result.json') @{status='passed';checks=15;retentionResult='retention/result.json';fingerprint=$initial}
Write-Host 'Local verification harness checks passed'
