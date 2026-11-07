param(
    [switch] $Draft,
    [switch] $SkipBuild,
    [string] $Workspace = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)
$ErrorActionPreference = 'Stop'
$workspacePath = [IO.Path]::GetFullPath($Workspace)
$license = Join-Path $workspacePath 'LICENSE'
if (-not $Draft -and -not (Test-Path -LiteralPath $license)) {
    throw 'No release license has been approved. Review docs/release/release-license-proposal.md, or use -Draft for a private review package.'
}
$properties = @{}
Get-Content -LiteralPath (Join-Path $workspacePath 'gradle.properties') | ForEach-Object {
    if ($_ -match '^([a-z_]+)=(.+)$') { $properties[$Matches[1]] = $Matches[2].Trim() }
}
if (-not $SkipBuild) {
    & (Join-Path $workspacePath 'scripts/build/Invoke-Gradle.ps1') assemble verifyContentAssets
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
}
$jarName = '{0}-{1}.jar' -f $properties.archives_base_name, $properties.mod_version
$jar = Join-Path $workspacePath ('build/libs/' + $jarName)
if (-not (Test-Path -LiteralPath $jar)) { throw "Build the release JAR first: $jar" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entry = $archive.GetEntry('fabric.mod.json')
    if ($null -eq $entry) { throw 'Missing Fabric metadata' }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($metadata.id -ne 'nullspace' -or $metadata.version -ne $properties.mod_version) { throw 'Artifact identity does not match project properties' }
    if ($metadata.entrypoints.PSObject.Properties.Name -match 'gametest') { throw 'Release JAR contains GameTest entrypoints' }
    if (@($archive.Entries | Where-Object FullName -match '(?:^|/)(?:gametest|test-results)/').Count) { throw 'Release JAR contains test output' }
    if (-not $Draft -and (-not $metadata.PSObject.Properties['license'] -or [string]::IsNullOrWhiteSpace(($metadata.license -join ' ')))) { throw 'Release metadata has no declared license' }
    if (-not $Draft -and $null -eq $archive.GetEntry('META-INF/LICENSE')) { throw 'Release JAR does not include its license' }
} finally { $archive.Dispose() }
$runPath = Join-Path $workspacePath ('build/alpha-packages/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
$serverPath = Join-Path $runPath server
$modsPath = Join-Path $serverPath mods
$null = New-Item -ItemType Directory -Path $modsPath
Copy-Item -LiteralPath $jar -Destination (Join-Path $modsPath $jarName)
$headers = @{ 'User-Agent' = 'NullSpace-Release-Preparation/0.1' }
$api = Invoke-RestMethod -Uri ('https://api.modrinth.com/v2/project/fabric-api/version/' + [Uri]::EscapeDataString($properties.fabric_api_version)) -Headers $headers
if ($api.version_number -ne $properties.fabric_api_version -or $properties.minecraft_version -notin $api.game_versions) { throw 'Fabric API metadata does not match the pinned versions' }
$apiFile = @($api.files | Where-Object primary)
if ($apiFile.Count -ne 1 -or [IO.Path]::GetFileName($apiFile[0].filename) -ne $apiFile[0].filename) { throw 'Invalid Fabric API download metadata' }
$apiPath = Join-Path $modsPath $apiFile[0].filename
Invoke-WebRequest -Uri $apiFile[0].url -Headers $headers -OutFile $apiPath -UseBasicParsing
if ((Get-FileHash -LiteralPath $apiPath -Algorithm SHA512).Hash.ToLowerInvariant() -ne $apiFile[0].hashes.sha512) { throw 'Fabric API download checksum mismatch' }
$installer = '1.1.2'
$launcherUrl = 'https://meta.fabricmc.net/v2/versions/loader/{0}/{1}/{2}/server/jar' -f $properties.minecraft_version,$properties.loader_version,$installer
Invoke-WebRequest -Uri $launcherUrl -Headers $headers -OutFile (Join-Path $serverPath 'fabric-server-launch.jar') -UseBasicParsing
$utf8 = [Text.UTF8Encoding]::new($false)
$serverProperties = @'
online-mode=true
white-list=false
enforce-whitelist=false
server-ip=
server-port=25565
enable-rcon=false
enable-query=false
op-permission-level=2
gamemode=survival
hardcore=false
pvp=false
view-distance=8
simulation-distance=6
level-name=world
motd=NullSpace public alpha
nullspace-space-start=true
nullspace-starting-ship=sparrow
'@
[IO.File]::WriteAllText((Join-Path $serverPath 'server.properties'),$serverProperties + "`n",$utf8)
$windowsLauncher = @'
param([string] $Java = 'java', [ValidatePattern('^[2-9][0-9]*G$')] [string] $MaxHeap = '6G')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Test-Path -LiteralPath eula.txt) -or [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'eula.txt')) -notmatch '(?m)^\s*eula\s*=\s*true\s*$') {
    throw 'Read https://aka.ms/MinecraftEULA and create eula.txt containing eula=true only if you agree. Java 25 is required.'
}
& $Java '-Xms2G' "-Xmx$MaxHeap" '-jar' 'fabric-server-launch.jar' 'nogui'
exit $LASTEXITCODE
'@
[IO.File]::WriteAllText((Join-Path $serverPath 'start.ps1'),$windowsLauncher + "`n",$utf8)
$linuxLauncher = @'
#!/bin/sh
set -eu
CDPATH= cd "$(dirname "$0")"
if [ ! -f eula.txt ] || ! grep -Eq '^[[:space:]]*eula[[:space:]]*=[[:space:]]*true[[:space:]]*$' eula.txt; then
    echo 'Read https://aka.ms/MinecraftEULA and create eula.txt containing eula=true only if you agree. Java 25 is required.' >&2
    exit 1
fi
exec "${JAVA:-java}" -Xms2G "-Xmx${MAX_HEAP:-6G}" -jar fabric-server-launch.jar nogui "$@"
'@
[IO.File]::WriteAllText((Join-Path $serverPath 'start.sh'),$linuxLauncher.Replace("`r`n","`n") + "`n",$utf8)
$guide = [IO.File]::ReadAllText((Join-Path $workspacePath 'docs/release/public-alpha.md')).Replace('(release-license-proposal.md)','(docs/release-license-proposal.md)')
if (-not $Draft) { $guide = [regex]::Replace($guide,'(?m)^Licensing remains pending\.[^\r\n]*','License: see [LICENSE](LICENSE) for the approved release terms.') }
[IO.File]::WriteAllText((Join-Path $runPath 'README.md'),$guide,$utf8)
$null = New-Item -ItemType Directory -Path (Join-Path $runPath 'docs')
Copy-Item -LiteralPath (Join-Path $workspacePath 'docs/release/alpha-test-report.md') -Destination (Join-Path $runPath 'docs/alpha-test-report.md')
if ($Draft) { Copy-Item -LiteralPath (Join-Path $workspacePath 'docs/release/release-license-proposal.md') -Destination (Join-Path $runPath 'docs/release-license-proposal.md') }
Copy-Item -LiteralPath $jar -Destination (Join-Path $runPath $jarName)
if (Test-Path -LiteralPath $license) {
    Copy-Item -LiteralPath $license -Destination (Join-Path $runPath 'LICENSE')
    Copy-Item -LiteralPath $license -Destination (Join-Path $serverPath 'LICENSE')
}
else { [IO.File]::WriteAllText((Join-Path $runPath 'LICENSE-PENDING.txt'),'Private review package only. No public distribution license has been applied. See docs/release-license-proposal.md.',$utf8) }
if ($Draft) { [IO.File]::WriteAllText((Join-Path $runPath 'DRAFT-NOT-FOR-PUBLICATION.txt'),'Unpublished review package. Release gates and license approval may still be outstanding.',$utf8) }
$clientMods = Join-Path $runPath 'client/mods'
$null = New-Item -ItemType Directory -Path $clientMods
Copy-Item -LiteralPath (Join-Path $modsPath $jarName),$apiPath -Destination $clientMods
[IO.File]::WriteAllText((Join-Path $runPath 'client/INSTALL.txt'),
    "Install Minecraft $($properties.minecraft_version), Fabric Loader $($properties.loader_version), and Java 25. Copy the two mods in this folder into that instance's mods folder. Use exactly these files on the server as well. This package does not include Minecraft, a launcher, a world, or test mods.",$utf8)
[IO.File]::WriteAllText((Join-Path $runPath 'THIRD-PARTY-NOTICES.txt'),
    'Fabric Loader, the Fabric installer/launcher and Fabric API remain under their respective licenses. Their bundled notices are not replaced by the NullSpace license. Minecraft is obtained separately by the official Fabric installer. No Minecraft binary is included in this package.',$utf8)
$files = @(Get-ChildItem -LiteralPath $runPath -File -Recurse | ForEach-Object {
    [ordered]@{file=$_.FullName.Substring($runPath.Length + 1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$manifest = [ordered]@{draft=[bool]$Draft;createdUtc=[DateTime]::UtcNow.ToString('o');minecraft=$properties.minecraft_version;java=25;loader=$properties.loader_version;fabricApi=$properties.fabric_api_version;fabricInstaller=$installer;mod=$properties.mod_version;apiVersionId=$api.id;launcherUrl=$launcherUrl;files=$files}
[IO.File]::WriteAllText((Join-Path $runPath 'manifest.json'),($manifest | ConvertTo-Json -Depth 6),$utf8)
$zipPath = $runPath + '.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($runPath,$zipPath)
Write-Output ([pscustomobject]@{directory=$runPath;archive=$zipPath;sha256=(Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant();draft=[bool]$Draft})
