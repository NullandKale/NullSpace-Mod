param([switch] $Graphics, [switch] $Multiplayer, [switch] $Crash)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'scripts/LocalVerification.psm1') -Force -DisableNameChecking
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6)
$artifacts = Join-Path $PSScriptRoot "build/verification/$runId"
$null = New-Item -ItemType Directory -Path $artifacts
$manifest = [ordered]@{ version=1; runId=$runId; startedUtc=[DateTime]::UtcNow.ToString('o'); status='running'; suites=[ordered]@{}; jar=$null }
$manifest.inputsSha256=Get-VerificationFingerprint $PSScriptRoot
foreach ($suite in @('unit','server','assets','graphics','multiplayer','crash')) {
    $selected = $suite -in @('unit','server','assets') -or ($suite -eq 'graphics' -and $Graphics) -or ($suite -eq 'multiplayer' -and $Multiplayer) -or ($suite -eq 'crash' -and $Crash)
    $manifest.suites[$suite] = [ordered]@{ selected=[bool]$selected; status=$(if($selected){'pending'}else{'not-selected'}) }
}
function Save-Manifest { Write-VerificationJson (Join-Path $artifacts 'manifest.json') $manifest }
function Preserve-Reports([string] $Name) {
    $destination=Join-Path $artifacts $Name
    $null=New-Item -ItemType Directory -Force -Path $destination
    foreach ($source in @('build/test-results','build/reports','build/run/clientGameTest')) {
        $path=Join-Path $PSScriptRoot $source
        if(Test-Path -LiteralPath $path){Copy-Item -LiteralPath $path -Destination $destination -Recurse -Force}
    }
}
Save-Manifest
try {
    & "$PSScriptRoot/scripts/Test-LocalVerification.ps1" -ArtifactRoot (Join-Path $artifacts 'harness') *> (Join-Path $artifacts 'harness.log')
    $manifest.harness=@{status='passed';result='harness/result.json'}
    & "$PSScriptRoot/scripts/Invoke-Gradle.ps1" cleanTest build runGameTest verifyContentAssets --no-daemon --console=plain *> (Join-Path $artifacts 'baseline.log')
    $baselineExit=$LASTEXITCODE
    Assert-VerificationFingerprint $PSScriptRoot $manifest.inputsSha256
    Preserve-Reports 'baseline'
    foreach($suite in @('unit','server','assets')){$manifest.suites[$suite].exitCode=$baselineExit}
    if($baselineExit -ne 0){throw "Baseline failed (exit $baselineExit)"}
    $xmlFiles=@(Get-ChildItem -LiteralPath "$PSScriptRoot/build/test-results/test" -Filter 'TEST-*.xml')
    if($xmlFiles.Count -eq 0){throw 'Unit suite produced no XML reports'}
    $unitCount=0;$actualClasses=@()
    foreach($file in $xmlFiles){[xml]$report=Get-Content -LiteralPath $file.FullName -Raw;$unitCount += [int]$report.testsuite.tests;$actualClasses+=@([string]$report.testsuite.name);if([int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0 -or [int]$report.testsuite.tests -eq 0){throw "Failed, skipped or empty unit report: $($file.Name)"}}
    $unitRoot=Join-Path $PSScriptRoot 'src/test/java/'
    $expectedClasses=@(& rg --files $unitRoot -g '*Test.java' | ForEach-Object { $_.Substring($unitRoot.Length).Replace('\','.').Replace('/','.').Replace('.java','') })
    Assert-VerificationInventory $expectedClasses $actualClasses
    $native=Get-Content -LiteralPath (Join-Path $artifacts 'baseline.log') -Raw
    if($native -notmatch 'All (\d+) required tests passed'){throw 'Server suite is missing its completed-test marker'}
    $manifest.suites.server.tests=[int]$Matches[1]
    $testMod=Get-Content -LiteralPath "$PSScriptRoot/src/gametest/resources/fabric.mod.json" -Raw | ConvertFrom-Json
    $nativeCount=0
    foreach($class in $testMod.entrypoints.'fabric-gametest'){
        $path=Join-Path $PSScriptRoot ('src/gametest/java/'+$class.Replace('.','/')+'.java')
        $nativeCount+=[regex]::Matches((Get-Content -LiteralPath $path -Raw),'(?m)^\s*@GameTest(?:\([^)]*\))?').Count
    }
    $manifest.suites.server.modTests=$nativeCount
    # Minecraft 26.3 also discovers its optional minecraft:always_pass fixture. Its success
    # message misleadingly labels getTotalCount(), including this fixture, as "required".
    $manifest.suites.server.vanillaOptionalTests=1
    $nativeCount+=1
    if($manifest.suites.server.tests -ne $nativeCount -or $native -match '\d+ optional tests failed'){throw "Server suite did not pass all $nativeCount declared mod and vanilla tests"}
    $manifest.suites.server.note='The physical-crash entrypoint returns early here; fresh-JVM coverage is reported separately.'
    $manifest.suites.unit.tests=$unitCount
    foreach($suite in @('unit','server','assets')){$manifest.suites[$suite].status='passed'}
    Save-Manifest
    if($Graphics){
        $previousEvidence=Join-Path $PSScriptRoot 'build/run/clientGameTest/nullspace-test-evidence.json'
        if(Test-Path -LiteralPath $previousEvidence){Remove-Item -LiteralPath $previousEvidence}
        & "$PSScriptRoot/scripts/Invoke-Gradle.ps1" runClientGameTest --no-daemon --console=plain *> (Join-Path $artifacts 'graphics.log')
        $code=$LASTEXITCODE;Preserve-Reports 'graphics';$manifest.suites.graphics.exitCode=$code
        Assert-VerificationFingerprint $PSScriptRoot $manifest.inputsSha256
        if($code -ne 0){throw "Graphical suite failed (exit $code)"}
        $evidencePath=Join-Path $PSScriptRoot 'build/run/clientGameTest/nullspace-test-evidence.json'
        if(-not(Test-Path -LiteralPath $evidencePath)){throw 'Graphical suite is missing its entrypoint receipts'}
        $evidence=Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
        $testMod=Get-Content -LiteralPath "$PSScriptRoot/src/gametest/resources/fabric.mod.json" -Raw | ConvertFrom-Json
        $expected=@($testMod.entrypoints.'fabric-client-gametest')
        $actual=@($evidence.completed)
        Assert-VerificationInventory $expected $actual
        $manifest.suites.graphics.tests=$actual.Count
        $manifest.suites.graphics.status='passed';Save-Manifest
    }
    if($Multiplayer){
        & "$PSScriptRoot/multiplayer-test.ps1" -ArtifactRoot (Join-Path $artifacts 'multiplayer') *> (Join-Path $artifacts 'multiplayer.log')
        Assert-VerificationMarker (Join-Path $artifacts 'multiplayer/passed') 'ok'
        Assert-VerificationFingerprint $PSScriptRoot $manifest.inputsSha256
        $manifest.suites.multiplayer.status='passed';$manifest.suites.multiplayer.result='multiplayer/result.json';Save-Manifest
    }
    if($Crash){
        & "$PSScriptRoot/crash-test.ps1" -ArtifactRoot (Join-Path $artifacts 'crash') *> (Join-Path $artifacts 'crash.log')
        $result=Get-Content -LiteralPath (Join-Path $artifacts 'crash/result.json') -Raw | ConvertFrom-Json
        Assert-VerificationFingerprint $PSScriptRoot $manifest.inputsSha256
        $expectedCases=@(foreach($direction in @('launch','landing')){foreach($stage in @('PREPARED','DESTINATION_STAGED','SOURCE_CLEARED','PASSENGERS_MOVED','COMMITTED','TEMP_PREPARED','TEMP_SOURCE_CLEARED')){"$direction/$stage"}})
        if($result.status -ne 'passed' -or @($result.cases).Count -ne 14 -or @(Compare-Object $expectedCases @($result.cases)).Count -ne 0){throw 'Crash suite did not complete all fourteen launch/landing cases'}
        $manifest.suites.crash.status='passed';$manifest.suites.crash.result='crash/result.json';Save-Manifest
    }
    $properties=Get-Content -LiteralPath "$PSScriptRoot/gradle.properties" -Raw
    $archive=[regex]::Match($properties,'(?m)^archives_base_name=(.+)$').Groups[1].Value.Trim()
    $version=[regex]::Match($properties,'(?m)^mod_version=(.+)$').Groups[1].Value.Trim()
    if(-not $archive -or -not $version){throw 'Missing artifact coordinates in gradle.properties'}
    $jar=Join-Path $PSScriptRoot "build/libs/$archive-$version.jar"
    if(-not(Test-Path -LiteralPath $jar)){throw 'Missing built mod JAR'}
    Copy-Item -LiteralPath $jar -Destination $artifacts
    $manifest.jar=@{file=(Split-Path -Leaf $jar);sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()}
    $manifest.status='passed'
} catch {
    $manifest.status='failed';$manifest.error=$_.Exception.Message
    foreach($suite in $manifest.suites.Values){if($suite.status -eq 'pending'){$suite.status='incomplete'}}
    throw
} finally {
    $manifest.finishedUtc=[DateTime]::UtcNow.ToString('o');Save-Manifest
    Write-Host "Verification artifacts: $artifacts"
}
