param([switch] $Write)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'scripts/LocalVerification.psm1') -Force -DisableNameChecking
$directory=Join-Path $PSScriptRoot '.tools/format'
$formatter=Join-Path $directory 'google-java-format-1.35.0-all-deps.jar'
$expected='bfb7f9ead6cd328389bc2da53860443bc0e805dfd08cc889bfdf43b26cb2a6e8'
if(-not(Test-Path -LiteralPath $formatter)){
    $null=New-Item -ItemType Directory -Force -Path $directory
    Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.35.0/google-java-format-1.35.0-all-deps.jar' -OutFile $formatter
}
if((Get-FileHash -LiteralPath $formatter -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected){throw 'Java formatter digest mismatch'}
$jdk=Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'jdk-25*' | Sort-Object Name -Descending | Select-Object -First 1
$java=if($jdk){Join-Path $jdk.FullName 'bin/java.exe'}elseif($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{'java'}
$files=@(& rg --files "$PSScriptRoot/src" -g '*.java')
$arguments=@('-jar',$formatter)
if($Write){$arguments+='--replace'}else{$arguments+=@('--dry-run','--set-exit-if-changed')}
$arguments+=$files
$null=New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'build/formatter')
$argFile=Join-Path $PSScriptRoot 'build/formatter/java.args'
Write-JavaArguments $argFile $arguments
$ErrorActionPreference='Continue'
& $java ('@'+$argFile)
exit $LASTEXITCODE
