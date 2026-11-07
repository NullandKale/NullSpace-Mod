<#
.SYNOPSIS
  Promotes a draft/prerelease to Latest only when its JAR matches the running hosted server.
.NOTES
  Uses the local SSH key and Git Credential Manager; never uploads either to GitHub.
  The deployment receipt must name the current systemd process, preventing promotion after
  merely copying a JAR without restarting. Published assets are never overwritten.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^v[0-9A-Za-z][0-9A-Za-z.+-]*$')][string]$Tag,
    [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repository='NullandKale/NullSpace-Mod',
    [ValidatePattern('^[A-Za-z0-9.-]+$')][string]$Server='129.121.160.249',
    [string]$SshKey=(Join-Path $env:USERPROFILE '.ssh/nullspace_host'),
    [switch]$CheckOnly
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$credentialLines="protocol=https`nhost=github.com`n`n" | git -c credential.interactive=never credential fill
if($LASTEXITCODE -ne 0){throw 'Sign in to GitHub through Git Credential Manager first'}
$credential=@{}
foreach($line in $credentialLines){$pair=$line.Split('=',2);if($pair.Count -eq 2){$credential[$pair[0]]=$pair[1]}}
try {
    $headers=@{'User-Agent'='NullSpace-release';Accept='application/vnd.github+json';Authorization=('Bearer '+$credential.password)}
    $release=Invoke-RestMethod "https://api.github.com/repos/$Repository/releases/tags/$Tag" -Headers $headers
    if(-not $release.draft -and -not $release.prerelease){throw 'Only drafts or prereleases can be promoted; stable release assets are immutable'}
    $assets=@($release.assets|Where-Object{$_.name -match '^nullspace-[^/]+\.jar$' -and $_.name -notmatch '-sources\.jar$'})
    if($assets.Count -ne 1){throw 'The draft must contain exactly one distributable NullSpace JAR'}
    $asset=$assets[0]
    $directory=Join-Path $root ('build/release-promotion/'+[guid]::NewGuid().ToString('N'))
    $null=New-Item -ItemType Directory -Path $directory
    $download=Join-Path $directory 'candidate.jar'
    $downloadHeaders=@{'User-Agent'='NullSpace-release';Accept='application/octet-stream';Authorization=$headers.Authorization}
    Invoke-WebRequest $asset.url -Headers $downloadHeaders -OutFile $download -UseBasicParsing
    $hash=(Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant()
    $remote=@(& ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 "root@$Server" 'systemctl is-active nullspace; systemctl show nullspace -p MainPID --value; cat /opt/nullspace/deployment.json; sha256sum /opt/nullspace/server/mods/nullspace-*.jar')
    if($LASTEXITCODE -ne 0 -or $remote.Count -ne 4 -or $remote[0] -ne 'active'){throw 'Cannot verify one running server JAR and its deployment receipt'}
    $receipt=$remote[2]|ConvertFrom-Json
    if([string]$receipt.pid -ne [string]$remote[1] -or $receipt.jarSha256 -ne $hash){throw 'The running service is not attested to this release JAR'}
    if($remote[3] -notmatch '^([a-f0-9]{64})\s+/opt/nullspace/server/mods/([^/\s]+)$' -or $Matches[1] -ne $hash -or $Matches[2] -ne $asset.name){throw 'The live server JAR differs from the draft asset'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($download)
    try {
        $reader=[IO.StreamReader]::new($zip.GetEntry('fabric.mod.json').Open())
        try{$meta=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
    }finally{$zip.Dispose()}
    if($meta.id -ne 'nullspace' -or $Tag -ne ('v'+$meta.version)){throw 'The release tag does not match the deployed mod version'}
    if($CheckOnly){Write-Output "Verified draft $Tag matches running server: $hash";return}
    $body=@{draft=$false;prerelease=$false;make_latest='true'}|ConvertTo-Json
    $published=Invoke-RestMethod "https://api.github.com/repos/$Repository/releases/$($release.id)" -Method Patch -Headers $headers -ContentType application/json -Body $body
    $latest=Invoke-RestMethod "https://api.github.com/repos/$Repository/releases/latest" -Headers $headers
    if($latest.id -ne $published.id){throw 'GitHub did not mark the verified release Latest'}
    Write-Output "Latest now matches the running server: $($published.html_url) ($hash)"
} finally {
    $credential.Clear();$credentialLines=$null;$headers=$null;$downloadHeaders=$null
}
