Set-StrictMode -Version Latest

function Get-LocalPlayArguments {
    param($Spec,[string]$Work,[string]$Config,[string]$Username='',[string]$Uuid='',[string]$Address='')
    if (@($Spec.jvmArgs | Where-Object { $_ -match 'fabric[.-].*gametest' }).Count -or $Spec.classpath -match '[\\/]gametest(?:[;\\/]|$)') {
        throw 'Interactive play must use ordinary client/server launches, without GameTests'
    }
    $arguments = @($Spec.jvmArgs | Where-Object { -not $_.StartsWith('@') -and -not $_.StartsWith('-Dfabric.dli.config=') -and -not $_.StartsWith('-Xmx') })
    $arguments += @('-Xmx2G',"-Dfabric.dli.config=$Config",'-cp',$Spec.classpath,$Spec.mainClass)
    $remove = @('--gameDir','--username','--uuid','--width','--height','--quickPlaySingleplayer','--quickPlayMultiplayer','--quickPlayRealms','--quickPlayPath','--server','--port')
    for ($index=0; $index -lt $Spec.args.Count; $index++) {
        $value=[string]$Spec.args[$index]
        if ($value -in $remove) {
            if ($index+1 -ge $Spec.args.Count) { throw "Missing value after $value in launch specification" }
            $index++; continue
        }
        if (@($remove|Where-Object { $value.StartsWith($_+'=') }).Count) { continue }
        if ($value -in @('--nogui','nogui')) { continue }
        $arguments += $value
    }
    if ($Username) {
        $arguments += @('--gameDir',$Work,'--username',$Username,'--uuid',$Uuid,'--quickPlayMultiplayer',$Address,'--width','960','--height','720')
    } else { $arguments += 'nogui' }
    return ,$arguments
}

function Get-LocalPlayWindowLayout {
    param($Area)
    # Always left/right, including portrait monitors; do not let the test-grid
    # optimizer choose a vertical stack for this interactive launcher.
    $half=[int][Math]::Floor($Area.width/2)
    @(
        [pscustomobject]@{x=$Area.x+4;y=$Area.y+4;width=$half-8;height=$Area.height-8},
        [pscustomobject]@{x=$Area.x+$half+4;y=$Area.y+4;width=$Area.width-$half-8;height=$Area.height-8}
    )
}

function Get-LocalPlayPort {
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    try { $listener.Start(); return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Read-LocalPlayLog {
    param([string]$File)
    if(-not(Test-Path -LiteralPath $File)){return ''}
    try { $stream=[IO.File]::Open($File,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)) }
    catch [IO.FileNotFoundException] { return '' }
    $reader=[IO.StreamReader]::new($stream)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Test-LocalPlayServerReady {
    param([string]$Log,[datetime]$StartedUtc)
    return (Test-Path -LiteralPath $Log) -and (Get-Item -LiteralPath $Log).LastWriteTimeUtc -ge $StartedUtc.ToUniversalTime() -and (Read-LocalPlayLog $Log) -match 'Done \('
}

function Get-LocalPlayUuid {
    param([string]$Username)
    $hash=[Security.Cryptography.MD5]::Create()
    try { $bytes=$hash.ComputeHash([Text.Encoding]::UTF8.GetBytes('OfflinePlayer:'+$Username)) } finally { $hash.Dispose() }
    $bytes[6]=[byte](($bytes[6] -band 15) -bor 48)
    $bytes[8]=[byte](($bytes[8] -band 63) -bor 128)
    $hex=[BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
    return $hex.Substring(0,8)+'-'+$hex.Substring(8,4)+'-'+$hex.Substring(12,4)+'-'+$hex.Substring(16,4)+'-'+$hex.Substring(20,12)
}

function Set-LocalPlayProperties {
    param([string]$File,[int]$Port,[string]$StartingShip,[switch]$VanillaStart)
    $managed=[ordered]@{
        'server-ip'='127.0.0.1';'server-port'=[string]$Port;'online-mode'='false';
        'enforce-secure-profile'='false';'enable-query'='false';'enable-rcon'='false';
        'white-list'='false';'enforce-whitelist'='false';
        'allow-flight'='true';'spawn-protection'='0';'motd'='NullSpace local multiplayer';
        'nullspace-space-start'=([string](-not $VanillaStart)).ToLowerInvariant();
        'nullspace-starting-ship'=$StartingShip
    }
    if (Test-Path -LiteralPath $File) {
        if ((Get-Item -LiteralPath $File).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing redirected local server properties' }
        $lines=@([IO.File]::ReadAllLines($File) | Where-Object {
            $line=$_
            -not @($managed.Keys | Where-Object { $line -match ('^\s*'+[regex]::Escape($_)+'\s*[:=]') }).Count
        })
    } else { $lines=@('gamemode=survival','difficulty=normal','view-distance=8','simulation-distance=6','max-players=8') }
    $lines += @($managed.GetEnumerator() | ForEach-Object { $_.Key+'='+$_.Value })
    [IO.File]::WriteAllLines($File,[string[]]$lines,[Text.UTF8Encoding]::new($false))
}

function Set-LocalPlayClientOptions {
    param([string]$File)
    $lines=if(Test-Path -LiteralPath $File){
        if((Get-Item -LiteralPath $File).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing redirected client options'}
        @([IO.File]::ReadAllLines($File)|Where-Object{$_ -notmatch '^fullscreen:'})
    }else{@('maxFps:60')}
    [IO.File]::WriteAllLines($File,[string[]](@($lines)+@('fullscreen:false')),[Text.UTF8Encoding]::new($false))
}

Export-ModuleMember -Function Get-LocalPlayArguments,Get-LocalPlayWindowLayout,Get-LocalPlayPort,Read-LocalPlayLog,Test-LocalPlayServerReady,Get-LocalPlayUuid,Set-LocalPlayProperties,Set-LocalPlayClientOptions
