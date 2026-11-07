param([string]$ArtifactRoot='')
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../lib/LocalPlay.psm1') -Force
if(-not $ArtifactRoot){$ArtifactRoot=Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) ('build/local-play-harness/'+[guid]::NewGuid().ToString('N'))}
$null=New-Item -ItemType Directory -Force -Path $ArtifactRoot
$ArtifactRoot=(Resolve-Path -LiteralPath $ArtifactRoot).Path
function Check([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message}}
$spec=[pscustomobject]@{jvmArgs=@('-Dfabric.dli.env=client','-Dfabric.dli.config=old','-Xmx1G');classpath='normal classes;libraries';mainClass='Main';args=@('--username','OldPlayer','--uuid','old','--gameDir','old world','--quickPlaySingleplayer','World 5','--assetIndex','26','--quickPlayMultiplayer=old:123')}
$arguments=Get-LocalPlayArguments -Spec $spec -Work 'new world' -Config 'private config' -Username 'NullSpace_One' -Uuid 'new-id' -Address '127.0.0.1:2345'
Check (@($arguments|Where-Object{$_ -eq '--username'}).Count -eq 1) 'Username must occur once'
Check (@($arguments|Where-Object{$_ -eq '--quickPlayMultiplayer'}).Count -eq 1) 'Automatic multiplayer connection is missing'
foreach($removed in @('OldPlayer','old','old world','--quickPlaySingleplayer','World 5','--quickPlayMultiplayer=old:123','-Xmx1G','-Dfabric.dli.config=old')){Check ($removed -notin $arguments) 'Old world, identity or connection leaked into a new client'}
foreach($kept in @('--assetIndex','26','normal classes;libraries','private config','new world','127.0.0.1:2345')){
    if($kept -eq 'private config'){Check ('-Dfabric.dli.config=private config' -in $arguments) 'Private launch config missing'}else{Check ($kept -in $arguments) 'Required launch argument missing'}
}
$spec.jvmArgs+= '-Dfabric.client.gametest'
$rejected=$false
try{Get-LocalPlayArguments -Spec $spec -Work 'server' -Config 'config'|Out-Null}catch{$rejected=$true}
Check $rejected 'Interactive launcher accepted automated GameTests'
$spec.jvmArgs=@();$spec.args=@()
$server=Get-LocalPlayArguments -Spec $spec -Work 'server' -Config 'config'
Check ($server[-1] -eq 'nogui' -and '--quickPlayMultiplayer' -notin $server) 'Server must be a normal console server'
foreach($area in @(@{x=0;y=0;width=2560;height=1369},@{x=-1080;y=40;width=1080;height=1800})){
    $windows=@(Get-LocalPlayWindowLayout $area)
    Check ($windows.Count -eq 2 -and $windows[0].y -eq $windows[1].y) 'Clients must be side by side even on portrait displays'
    Check ($windows[0].x+$windows[0].width -le $windows[1].x -and $windows[1].x+$windows[1].width -le $area.x+$area.width) 'Client windows overlap or escape their monitor'
}
$one=Get-LocalPlayUuid 'NullSpace_One';$two=Get-LocalPlayUuid 'NullSpace_Two'
Check ($one -eq (Get-LocalPlayUuid 'NullSpace_One') -and $one -ne $two) 'Player identities must be distinct and stable'
Check ($one -match '^[a-f0-9-]{14}3[a-f0-9-]{3}-[89ab]') 'Offline identity must use Java UUID version and variant bits'
$properties=Join-Path $ArtifactRoot 'server.properties'
[IO.File]::WriteAllLines($properties,@('# keep this','gamemode=creative','server-port=25565','online-mode=true','nullspace-space-start = false'))
Set-LocalPlayProperties -File $properties -Port 2345 -StartingShip 'mule'
$text=[IO.File]::ReadAllText($properties)
foreach($line in @('server-ip=127.0.0.1','server-port=2345','online-mode=false','white-list=false','enforce-whitelist=false','enable-rcon=false','enable-query=false','nullspace-space-start=true','nullspace-starting-ship=mule','gamemode=creative','# keep this')){Check ($text.Contains($line)) 'Server configuration lost a required or custom setting'}
Set-LocalPlayProperties -File $properties -Port 2345 -StartingShip 'sparrow' -VanillaStart
Check (([IO.File]::ReadAllText($properties)).Contains('nullspace-space-start=false')) 'Vanilla start was ignored'
$options=Join-Path $ArtifactRoot 'options.txt'
[IO.File]::WriteAllLines($options,@('fullscreen:true','maxFps:90','guiScale:2'))
Set-LocalPlayClientOptions $options
$text=[IO.File]::ReadAllText($options)
Check ($text.Contains('fullscreen:false') -and $text.Contains('maxFps:90') -and $text.Contains('guiScale:2') -and -not $text.Contains('fullscreen:true')) 'Windowed launch must preserve other client preferences'
$port=Get-LocalPlayPort
Check ($port -gt 0 -and $port -le 65535) 'Loopback port allocation failed'
$log=Join-Path $ArtifactRoot 'latest.log'
[IO.File]::WriteAllText($log,'Done (1s)!')
$started=[DateTime]::UtcNow
(Get-Item -LiteralPath $log).LastWriteTimeUtc=$started.AddMinutes(-1)
Check (-not(Test-LocalPlayServerReady -Log $log -StartedUtc $started)) 'A prior run cannot satisfy current server readiness'
(Get-Item -LiteralPath $log).LastWriteTimeUtc=$started.AddSeconds(1)
$writer=[IO.File]::Open($log,[IO.FileMode]::Open,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite)
try { Check (Test-LocalPlayServerReady -Log $log -StartedUtc $started.ToLocalTime()) 'Live logs must remain readable, including local/UTC timestamp conversion' } finally { $writer.Dispose() }
[IO.File]::WriteAllText((Join-Path $ArtifactRoot 'result.json'),(@{status='passed';cases=@('ordinary launch','argument replacement','private game directories','no GameTests','console server','side by side','portrait monitor','negative monitor coordinates','stable offline players','loopback only','custom server settings','space and vanilla starts','windowed client preferences','available port','stale startup log','live log sharing','local and UTC timestamps')}|ConvertTo-Json))
Write-Host 'Local play launcher checks passed'
