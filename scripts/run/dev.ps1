[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$Restart,
    [switch]$ResetWorld,
    [switch]$Remote,
    [string]$Server = '129.121.160.249:25565',
    [string]$StartingShip = 'halcyon_horizon',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$LaunchArguments
)
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$gradleArguments = @()
foreach ($argument in $LaunchArguments) {
    if ($argument -eq '--restart') { $Restart = $true }
    elseif ($argument -eq '--remote') { $Remote = $true }
    elseif ($argument -like '--remote=*') { $Remote = $true; $Server = $argument.Substring(9) }
    else { $gradleArguments += $argument }
}
if ($Restart -and $ResetWorld) { throw 'Choose --restart for the creation menu or -ResetWorld for the prepared playtest world.' }
if ($Remote -and ($Restart -or $ResetWorld)) { throw '--remote joins the hosted server; it does not use or reset a local world.' }
if ($Remote) {
    # Hosted servers use online-mode, so play through the Modrinth App, which holds the signed-in
    # Minecraft session. Build the current sources and install that JAR into the profile.
    $modrinthProfile = if ($env:NULLSPACE_MODRINTH_PROFILE) { $env:NULLSPACE_MODRINTH_PROFILE } else { Join-Path $env:APPDATA 'ModrinthApp\profiles\Fabric 26.3' }
    if (-not (Test-Path -LiteralPath (Join-Path $modrinthProfile 'mods'))) { throw "Modrinth profile not found: $modrinthProfile (set NULLSPACE_MODRINTH_PROFILE)" }
    & "$RepoRoot/scripts/build/Invoke-Gradle.ps1" assemble @gradleArguments
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $properties = @{}
    Get-Content (Join-Path $RepoRoot 'gradle.properties') | ForEach-Object { if ($_ -match '^([a-z_]+)=(.+)$') { $properties[$Matches[1]] = $Matches[2].Trim() } }
    $jarName = '{0}-{1}.jar' -f $properties.archives_base_name, $properties.mod_version
    $jar = Join-Path $RepoRoot "build/libs/$jarName"
    Get-ChildItem -LiteralPath (Join-Path $modrinthProfile 'mods') -Filter "$($properties.archives_base_name)-*.jar" | Remove-Item -Force
    Copy-Item -LiteralPath $jar -Destination (Join-Path $modrinthProfile "mods/$jarName")
    $localHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "Installed $jarName into $modrinthProfile ($localHash)"
    # Client and server must run the same build. Compare with the deployed JAR when the key is available.
    $key = Join-Path $env:USERPROFILE '.ssh\nullspace_host'
    $hostName = $Server.Split(':')[0]
    if (Test-Path -LiteralPath $key) {
        $remoteHash = (& ssh -i $key -o BatchMode=yes -o ConnectTimeout=8 "root@$hostName" "sha256sum /opt/nullspace/server/mods/$jarName 2>/dev/null | cut -d' ' -f1" 2>$null)
        if (-not $remoteHash) { Write-Warning "Could not read the server's $jarName to compare builds." }
        elseif ($remoteHash.Trim() -ne $localHash) { Write-Warning "The server runs a different NullSpace build ($($remoteHash.Trim())). Deploy this JAR there or expect mismatches." }
        else { Write-Host 'Server runs the same build.' }
    }
    $app = Join-Path $env:LOCALAPPDATA 'Modrinth App\Modrinth App.exe'
    if (Test-Path -LiteralPath $app) { Start-Process -FilePath $app } else { Write-Warning "Modrinth App not found at $app; open it yourself." }
    Write-Host "In the Modrinth App, press Play on '$(Split-Path $modrinthProfile -Leaf)', then Multiplayer > $Server."
    exit 0
}
Import-Module "$RepoRoot/scripts/lib/DevWorldLaunch.psm1" -Force -DisableNameChecking
if ($ResetWorld) {
    & "$RepoRoot/scripts/run/Reset-DevWorld.ps1" -StartingShip $StartingShip
    if (-not $?) { exit 1 }
    [IO.File]::WriteAllText((Join-Path $RepoRoot 'run/nullspace-dev-world.txt'),'World 5')
}
$launch = Get-DevWorldLaunch -Workspace $RepoRoot -Restart:$Restart
if ($launch.backup) { Write-Host "Previous dev world backed up: $($launch.backup)" }
if ($launch.create) {
    Write-Host 'Opening Create New World. Choose generation settings and starting ship in the UI.'
    & "$RepoRoot/scripts/build/Invoke-Gradle.ps1" runClient '-PdevCreateWorld=World 5' "-PdevStartingShip=$StartingShip" @gradleArguments
} else {
    Write-Host "Opening development world: $($launch.folder)"
    & "$RepoRoot/scripts/build/Invoke-Gradle.ps1" runClient "-PdevWorld=$($launch.folder)" @gradleArguments
}
exit $LASTEXITCODE
