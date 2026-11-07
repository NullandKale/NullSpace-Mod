# Exercises the real promotion script with fake GitHub/SSH endpoints; never publishes a release.
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$area=Join-Path $root ('build/release-policy-tests/'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $area
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$jar=Join-Path $area 'fixture.jar'
$zip=[IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Create)
try {
    $writer=[IO.StreamWriter]::new($zip.CreateEntry('fabric.mod.json').Open())
    try{$writer.Write('{"id":"nullspace","version":"0.1.0"}')}finally{$writer.Dispose()}
}finally{$zip.Dispose()}
$hash=(Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant()
function git {
    $global:LASTEXITCODE=0
    'username=fixture'
    $field='password'
    "$field=$([guid]::NewGuid().ToString('N'))"
}
function ssh {
    $global:LASTEXITCODE=0
    'active'
    '1234'
    @{jarSha256=$global:releasePolicyFixture.receiptHash;pid=$global:releasePolicyFixture.receiptPid}|ConvertTo-Json -Compress
    $global:releasePolicyFixture.diskHash+'  /opt/nullspace/server/mods/nullspace-0.1.0.jar'
}
function Invoke-WebRequest {
    param($Uri,$Headers,$OutFile,[switch]$UseBasicParsing)
    Copy-Item -LiteralPath $global:releasePolicyFixture.jar -Destination $OutFile
}
function Invoke-RestMethod {
    param($Uri,$Headers,$Method,$ContentType,$Body)
    if($Method -eq 'Patch'){
        $global:releasePolicyFixture.patches++
        $bodyData=$Body|ConvertFrom-Json
        if($bodyData.draft -or $bodyData.prerelease -or $bodyData.make_latest -ne 'true'){throw 'Incorrect publication state'}
    }
    return $global:releasePolicyFixture.release
}
function Reset-Fixture {
    $global:releasePolicyFixture=[pscustomobject]@{
        jar=$jar;diskHash=$hash;receiptHash=$hash;receiptPid=1234;patches=0
        release=[pscustomobject]@{id=17;draft=$true;prerelease=$true;html_url='https://example.invalid/release';assets=@([pscustomobject]@{name='nullspace-0.1.0.jar';url='https://example.invalid/asset'})}
    }
}
$command=Join-Path $PSScriptRoot '../release/Promote-DeployedRelease.ps1'
function Expect-Rejection([string]$Name,[scriptblock]$Change,[string]$Tag='v0.1.0'){
    Reset-Fixture
    & $Change
    $rejected=$false
    try{& $command -Tag $Tag -Server localhost -SshKey not-used -CheckOnly|Out-Null}catch{$rejected=$true}
    if(-not $rejected -or $global:releasePolicyFixture.patches -ne 0){throw "$Name did not reject publication safely"}
    Write-Output "Passed: $Name"
}
try {
    Expect-Rejection 'published stable release is immutable' {$global:releasePolicyFixture.release.draft=$false;$global:releasePolicyFixture.release.prerelease=$false}
    Expect-Rejection 'copied JAR differs from the running receipt' {$global:releasePolicyFixture.receiptHash=('0'*64)}
    Expect-Rejection 'disk JAR differs from the draft' {$global:releasePolicyFixture.diskHash=('0'*64)}
    Expect-Rejection 'receipt belongs to an old service process' {$global:releasePolicyFixture.receiptPid=9999}
    Expect-Rejection 'tag differs from mod version' {} 'v0.2.0'
    Expect-Rejection 'ambiguous release assets' {$global:releasePolicyFixture.release.assets+=@($global:releasePolicyFixture.release.assets[0])}
    Reset-Fixture
    & $command -Tag v0.1.0 -Server localhost -SshKey not-used -CheckOnly|Out-Null
    if($global:releasePolicyFixture.patches -ne 0){throw 'CheckOnly published a release'}
    Write-Output 'Passed: matching server can be checked without publishing'
    Reset-Fixture
    & $command -Tag v0.1.0 -Server localhost -SshKey not-used|Out-Null
    if($global:releasePolicyFixture.patches -ne 1){throw 'A verified matching draft did not publish exactly once'}
    Write-Output 'Passed: matching draft promotes exactly once after verification'
    Reset-Fixture
    $global:releasePolicyFixture.release.draft=$false
    & $command -Tag v0.1.0 -Server localhost -SshKey not-used|Out-Null
    if($global:releasePolicyFixture.patches -ne 1){throw 'A verified matching prerelease did not promote exactly once'}
    Write-Output 'Passed: matching prerelease promotes without replacing its assets'
}finally{Remove-Variable -Name releasePolicyFixture -Scope Global -ErrorAction SilentlyContinue}
