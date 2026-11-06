param(
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Instance='default',
    [ValidateRange(0,65535)][int]$Port=0,
    [ValidatePattern('^[a-z_]+$')][string]$StartingShip='sparrow',
    [switch]$VanillaStart,
    [ValidateRange(60,1200)][int]$TimeoutSeconds=240,
    [switch]$SmokeTest
)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
Import-Module (Join-Path $root 'scripts/LocalVerification.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $root 'scripts/GraphicalWindows.psm1') -Force
Import-Module (Join-Path $root 'scripts/LocalPlay.psm1') -Force
$area=Get-GraphicalTestWorkArea
$layout=@(Get-LocalPlayWindowLayout $area)
# Interactive saves deliberately live outside build and automated retention.
$instanceRoot=if($SmokeTest){Join-Path $root ('build/local-play-smoke/'+[guid]::NewGuid().ToString('N'))}else{Join-Path $root ('run-multiplayer/'+$Instance)}
foreach($process in @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")) {
    if($process.CommandLine -and $process.CommandLine.IndexOf($instanceRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -ge 0){throw "This multiplayer instance is already running. Close it, or use -Instance another-name."}
}
$ancestor=$instanceRoot
while($ancestor -ne $root){if((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Refusing a redirected local play directory'};$ancestor=Split-Path -Parent $ancestor}
$null=New-Item -ItemType Directory -Force -Path $instanceRoot
$catalog=[IO.File]::ReadAllText((Join-Path $root 'src/main/java/com/nullandkale/nullspace/shipyard/ShipBlueprints.java'))
if($catalog -notmatch ('new Preset\(\s*"'+[regex]::Escape($StartingShip)+'"')){throw "Unknown starting ship: $StartingShip. See docs/space-start.md."}
& "$root/scripts/Invoke-Gradle.ps1" exportLocalPlayLaunches --no-configuration-cache --no-daemon --console=plain
if($LASTEXITCODE -ne 0){throw 'Could not prepare the ordinary multiplayer launches'}
if(-not $Port){$Port=Get-LocalPlayPort}
$address='127.0.0.1:'+$Port
$session=Join-Path $instanceRoot ('launches/'+(Get-Date -Format yyyyMMdd-HHmmss)+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))
$serverRoot=Join-Path $instanceRoot 'server'
$null=New-Item -ItemType Directory -Force -Path $session,$serverRoot
Set-LocalPlayProperties -File (Join-Path $serverRoot 'server.properties') -Port $Port -StartingShip $StartingShip -VanillaStart:$VanillaStart
[IO.File]::WriteAllText((Join-Path $serverRoot 'eula.txt'),"eula=true`n")
# Snapshot Loom's configuration so later Gradle commands cannot change live launches.
$config=Join-Path $session 'launch.cfg'
Copy-Item -LiteralPath (Join-Path $root '.gradle/loom-cache/launch.cfg') -Destination $config
$serverSpec=Get-Content -LiteralPath "$root/build/local-play-server-launch.json" -Raw|ConvertFrom-Json
$serverArgs=Join-Path $session 'server.args'
Write-JavaArguments $serverArgs (Get-LocalPlayArguments -Spec $serverSpec -Work $serverRoot -Config $config)
$consoleArgs=@('-NoProfile')
if(-not $SmokeTest){$consoleArgs+='-NoExit'}
$consoleArgs+=@('-File',('"'+(Join-Path $root 'scripts/Start-LocalPlayServer.ps1')+'"'),'-Executable',('"'+$serverSpec.executable+'"'),'-ArgumentFile',('"'+$serverArgs+'"'),'-Work',('"'+$serverRoot+'"'),'-Session',('"'+$session+'"'))
if($SmokeTest){$consoleArgs+='-SmokeTest'}
$serverConsole=Start-Process powershell.exe -ArgumentList $consoleArgs -WorkingDirectory $serverRoot -WindowStyle Normal -PassThru
$clients=@();$serverId=0
$receipt=[ordered]@{status='starting';startedUtc=[DateTime]::UtcNow.ToString('o');address=$address;world=$serverRoot;serverConsolePid=$serverConsole.Id;clients=@();smoke=[bool]$SmokeTest}
function Save-Session { Write-VerificationJson (Join-Path $session 'session.json') $receipt }
Save-Session
try {
    $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $log=Join-Path $serverRoot 'logs/latest.log'
    do {
        $processFile=Join-Path $session 'server-process.json'
        if(Test-Path -LiteralPath $processFile){$serverId=[int](Read-VerificationJson $processFile).pid}
        if($serverConsole.HasExited -or ($serverId -and -not(Get-Process -Id $serverId -ErrorAction SilentlyContinue))){throw "Local server exited before it was ready. See $log"}
        if([DateTime]::UtcNow -gt $deadline){throw "Server startup timed out. See $log"}
        $ready=$serverId -and (Test-LocalPlayServerReady -Log $log -StartedUtc ([DateTime]::Parse($receipt.startedUtc)))
        if(-not $ready){Start-Sleep -Milliseconds 250}
    }while(-not $ready)
    $receipt.serverPid=$serverId
    Write-Host "Server ready: $address. Launching two players; close the clients when finished and type stop in the server terminal."
    $spec=Get-Content -LiteralPath "$root/build/local-play-client-launch.json" -Raw|ConvertFrom-Json
    $names=@('NullSpace_One','NullSpace_Two')
    for($index=0;$index -lt 2;$index++){
        $work=Join-Path $instanceRoot ('client-'+($index+1))
        $null=New-Item -ItemType Directory -Force -Path $work
        Set-LocalPlayClientOptions (Join-Path $work 'options.txt')
        $argFile=Join-Path $session ('client-'+($index+1)+'.args')
        Write-JavaArguments $argFile (Get-LocalPlayArguments -Spec $spec -Work $work -Config $config -Username $names[$index] -Uuid (Get-LocalPlayUuid $names[$index]) -Address $address)
        $process=Start-Process -FilePath $spec.executable -ArgumentList ('@"'+$argFile+'"') -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $session ('client-'+($index+1)+'.log')) -RedirectStandardError (Join-Path $session ('client-'+($index+1)+'.err.log'))
        $null=$process.Handle
        $clients+=@{process=$process;window=0L;name=$names[$index];bounds=$layout[$index]}
        $receipt.clients+=@{name=$names[$index];uuid=(Get-LocalPlayUuid $names[$index]);pid=$process.Id;work=$work;bounds=$layout[$index]}
        Save-Session
    }
    $receipt.status='running';Save-Session
    $connected=$false;$verified=$false
    while(@($clients|Where-Object{-not $_.process.HasExited}).Count){
        foreach($client in $clients){
            if(-not $client.process.HasExited){$client.window=Set-GraphicalTestWindow -ProcessId $client.process.Id -PreviousWindow $client.window -Name $client.name -Bounds $client.bounds}
            elseif($client.process.ExitCode -ne 0){throw "Client $($client.name) exited with $($client.process.ExitCode). Logs: $session"}
        }
        if(-not(Get-Process -Id $serverId -ErrorAction SilentlyContinue)){Write-Warning 'The server stopped. The clients remain open.';break}
        if($SmokeTest){
            if([DateTime]::UtcNow -gt $deadline){throw "Two-client connection check timed out. Logs: $session"}
            $text=Read-LocalPlayLog $log
            $connected=@($names|Where-Object{$text -match ([regex]::Escape($_)+'.*logged in with entity id')}).Count -eq 2 -and @($clients|Where-Object{$_.window -eq 0}).Count -eq 0
            $aboard=$VanillaStart -or @((1..2)|Where-Object { $clientLog=Join-Path $session ('client-'+$_+'.log'); (Read-LocalPlayLog $clientLog) -match 'Welcome aboard' }).Count -eq 2
            if($connected -and $aboard){
                $aligned=$true
                for($index=0;$index -lt 2;$index++){
                    $actual=[NullSpace.TestWindowGrid]::Bounds([IntPtr]$clients[$index].window)
                    $wanted=$clients[$index].bounds
                    if([Math]::Abs($actual.Left-$wanted.x) -gt 2 -or [Math]::Abs($actual.Top-$wanted.y) -gt 2 -or [Math]::Abs(($actual.Right-$actual.Left)-$wanted.width) -gt 2 -or [Math]::Abs(($actual.Bottom-$actual.Top)-$wanted.height) -gt 2){$aligned=$false}
                    $receipt.clients[$index].actualBounds=@{x=$actual.Left;y=$actual.Top;width=$actual.Right-$actual.Left;height=$actual.Bottom-$actual.Top}
                }
                if($aligned){$verified=$true;break}
            }
        }
        Start-Sleep -Milliseconds 500
    }
    if($SmokeTest -and -not $verified){throw 'Both clients must connect, finish their chosen start, and occupy their side-by-side windows'}
    $receipt.status=if($SmokeTest){'passed'}elseif(Get-Process -Id $serverId -ErrorAction SilentlyContinue){'clients-closed'}else{'server-stopped'}
}catch{
    $receipt.status='failed';$receipt.error=$_.Exception.Message
    throw
}finally{
    if($SmokeTest){
        [IO.File]::WriteAllText((Join-Path $session 'stop-server'),'stop')
        $null=$serverConsole.WaitForExit(60000)
        foreach($client in $clients){if(-not $client.process.HasExited){Stop-Process -Id $client.process.Id -ErrorAction SilentlyContinue;$null=$client.process.WaitForExit(10000)}}
        Import-Module (Join-Path $root 'scripts/TestWorldCleanup.psm1') -Force
        $receipt.cleanup=@(Remove-TestWorlds -Workspace $root -WorkRoot $serverRoot -ProcessIds @($serverId))
    }
    $receipt.finishedUtc=[DateTime]::UtcNow.ToString('o');Save-Session
    Write-Host "Session details and client logs: $session"
    if(-not $SmokeTest){Write-Host "World preserved at $serverRoot. Use the server terminal's stop command when finished."}
}
