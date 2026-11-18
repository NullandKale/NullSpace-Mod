<#
.SYNOPSIS
  Installs a verified release JAR on the hosted playtest server and in the Modrinth testing profile.
.NOTES
  The server address and key are private and never live in the repository. They are read from
  %USERPROFILE%\.nullspace\deploy.json: {"host": "<address>", "sshKey": "<path to the private key>"}.
  The server side is /opt/nullspace/incoming/install-release.sh, which stops the service, backs up the world and the old JAR, swaps the JAR,
  waits for the server to start and restores everything if it does not. A server with players online is left alone.
  Returns the deployment as an object; -DryRun checks everything and installs nothing.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidatePattern('^v[0-9]+\.[0-9]+\.[0-9]+(_[A-Za-z0-9._-]+)?$')] [string] $Tag,
    [Parameter(Mandatory)] [string] $JarPath,
    [switch] $DryRun
)
$ErrorActionPreference = 'Stop'
$configPath = Join-Path $env:USERPROFILE '.nullspace/deploy.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw "Missing $configPath (host and sshKey of the playtest server)." }
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$key = $config.sshKey.Replace('~', $env:USERPROFILE)
$target = "root@$($config.host)"
$label = $Tag.Substring(1)
$hash = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()
$sshOptions = @('-i', $key, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15')
function Invoke-Remote([string] $Command) {
    $output = & ssh @sshOptions $target $Command
    if ($LASTEXITCODE -ne 0) { throw "Remote command failed ($LASTEXITCODE): $Command`n$output" }
    return $output
}

$players = (Invoke-Remote 'mc list') -join ' '
if ($players -notmatch 'There are 0 of') { throw "Players are online; not touching the server: $players" }
$profile = Join-Path $env:APPDATA 'ModrinthApp/profiles/Fabric 26.3/mods'
if (-not (Test-Path -LiteralPath $profile)) { Write-Warning "No Modrinth profile at $profile; skipping it."; $profile = $null }
if ($DryRun) {
    Write-Host "Dry run: $Tag ($hash) would be installed on the server (no players online)$(if ($profile) { ' and in the Modrinth profile' })."
    return
}

& scp @sshOptions $JarPath "${target}:/opt/nullspace/incoming/nullspace-$label.jar"
if ($LASTEXITCODE -ne 0) { throw 'Could not copy the JAR to the server' }
$null = Invoke-Remote "bash /opt/nullspace/incoming/install-release.sh $Tag $hash"
$receipt = (Invoke-Remote 'cat /opt/nullspace/deployment.json') -join "`n" | ConvertFrom-Json
if ($receipt.jarSha256 -ne $hash -or $receipt.tag -ne $Tag) { throw 'The deployment receipt does not name this release' }
$active = (Invoke-Remote 'systemctl is-active nullspace') -join ''
if ($active -ne 'active') { throw "The service is $active after the install" }
$errors = [int]((Invoke-Remote 'grep -c " ERROR\]" /opt/nullspace/server/logs/latest.log || true') -join '')

$modrinth = $null
if ($profile) {
    Get-ChildItem -LiteralPath $profile -Filter 'nullspace-*.jar' | Remove-Item -Force
    Copy-Item -LiteralPath $JarPath -Destination (Join-Path $profile "nullspace-$label.jar")
    $modrinth = (Get-FileHash -LiteralPath (Join-Path $profile "nullspace-$label.jar") -Algorithm SHA256).Hash.ToLowerInvariant() -eq $hash
    if (-not $modrinth) { throw 'The JAR in the Modrinth profile does not match' }
}
[pscustomobject]@{tag = $Tag; sha256 = $hash; pid = $receipt.pid; backup = $receipt.backup; deployedUtc = $receipt.deployedUtc; serverErrors = $errors; modrinthProfile = $modrinth}
