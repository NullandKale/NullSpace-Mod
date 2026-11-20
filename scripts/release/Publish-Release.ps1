<#
.SYNOPSIS
  From a committed, locally verified version to a released, deployed and recorded one: tag and push, wait for release CI, verify the
  downloaded JAR, deploy it to the playtest server and Modrinth profile, and record all of it in one commit.
.NOTES
  Run release-check.ps1 on the commit first. This refuses to tag sources that differ from the newest passing receipt, so the
  tag can only name what was tested. Needs a clean tree on main with mod_version equal to the tag. -SkipDeploy stops after the verified download.
  The default tag is vMOD_VERSION from gradle.properties. Running it again for a version that is already tagged or released moves
  the tag to this commit and replaces the release, its record and the deployment: see docs/release/release-policy.md.
#>
[CmdletBinding()]
param(
    [string] $Tag = '',
    [switch] $SkipDeploy,
    [switch] $NoRecord,
    [ValidateRange(5, 90)] [int] $CiTimeoutMinutes = 40
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $root 'scripts/lib/LocalVerification.psm1') -Force -DisableNameChecking
$version = [regex]::Match([IO.File]::ReadAllText((Join-Path $root 'gradle.properties')), '(?m)^mod_version=(.+)$').Groups[1].Value.Trim()
if (-not $Tag) { $Tag = "v$version" }
if ($Tag -notmatch '^v([0-9]+\.[0-9]+\.[0-9]+)(_[A-Za-z0-9._-]+)?$' -or $Matches[1] -ne $version) { throw "Tag $Tag does not match mod_version $version" }
$label = $Tag.Substring(1)
$remote = (& git -C $root remote get-url origin).Trim()
$repo = [regex]::Match($remote, 'github\.com[:/](.+?)(\.git)?$').Groups[1].Value
if (-not $repo) { throw "Cannot read the GitHub repository from $remote" }
$headers = @{'User-Agent' = 'NullSpace-release'; Accept = 'application/vnd.github+json'}

# 1. The tree and the local check
if ((& git -C $root branch --show-current).Trim() -ne 'main') { throw 'Release from main' }
if (& git -C $root status --porcelain) { throw 'The working tree is not clean; commit the release first' }
$commit = (& git -C $root rev-parse HEAD).Trim()
$receiptDir = Get-ChildItem -LiteralPath (Join-Path $root 'build/release-check') -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $receiptDir) { throw 'No release check on record: run scripts/test/release-check.ps1' }
$check = Read-VerificationJson (Join-Path $receiptDir.FullName 'result.json')
if ($check.status -ne 'passed') { throw "The newest release check ($($receiptDir.Name)) did not pass" }
if ($check.inputsSha256 -ne (Get-VerificationFingerprint $root)) { throw 'The sources changed since the release check passed; run it again' }
Write-Host "Local check $($receiptDir.Name) passed in $([Math]::Round($check.elapsedSeconds / 60, 1)) min for these exact sources."

# 2. Tag and push
# An existing tag on another commit is moved here: the Release workflow then replaces that version's release.
$existing = (& git -C $root tag --list $Tag)
$moved = $existing -and (& git -C $root rev-parse "$Tag^{commit}").Trim() -ne $commit
if (-not $existing -or $moved) { & git -C $root tag -f -a $Tag -m "NullSpace $label" }
& git -C $root push origin main
if ($LASTEXITCODE -ne 0) { throw 'Pushing main failed' }
& git -C $root push --force origin "refs/tags/$Tag"
if ($LASTEXITCODE -ne 0) { throw "Pushing $Tag failed" }
Write-Host "$(if ($moved) { 'Moved' } else { 'Pushed' }) $Tag ($($commit.Substring(0, 7)))."

# 3. Release CI
$deadline = (Get-Date).AddMinutes($CiTimeoutMinutes)
$run = $null
while ($true) {
    $runs = (Invoke-RestMethod "https://api.github.com/repos/$repo/actions/runs?event=push&per_page=30" -Headers $headers).workflow_runs
    $run = $runs | Where-Object { $_.name -eq 'Release' -and $_.head_branch -eq $Tag -and $_.head_sha -eq $commit } | Select-Object -First 1
    if ($run -and $run.status -eq 'completed') { break }
    if ((Get-Date) -gt $deadline) { throw "Release CI did not finish within $CiTimeoutMinutes minutes: https://github.com/$repo/actions" }
    Start-Sleep -Seconds 20
}
$jobs = (Invoke-RestMethod "https://api.github.com/repos/$repo/actions/runs/$($run.id)/jobs?per_page=30" -Headers $headers).jobs
$ciMinutes = [Math]::Round(([DateTime]::Parse($run.updated_at) - [DateTime]::Parse($run.run_started_at)).TotalMinutes, 1)
foreach ($job in $jobs) { Write-Host ("  {0,-24} {1}" -f $job.name, $job.conclusion) }
if ($run.conclusion -ne 'success') { throw "Release CI $($run.conclusion): $($run.html_url)" }
Write-Host "Release CI passed in $ciMinutes min: $($run.html_url)"

# 4. The downloaded asset
$download = Join-Path $root "build/release-downloads/$Tag"
$null = New-Item -ItemType Directory -Force -Path $download
$base = "https://github.com/$repo/releases/download/$Tag"
Invoke-WebRequest "$base/nullspace-$label.jar" -OutFile (Join-Path $download "nullspace-$label.jar") -UseBasicParsing
Invoke-WebRequest "$base/SHA256SUMS.txt" -OutFile (Join-Path $download 'SHA256SUMS.txt') -UseBasicParsing
$listed = ([regex]::Match((Get-Content -LiteralPath (Join-Path $download 'SHA256SUMS.txt') -Raw), '(?m)^([0-9a-f]{64})\s+\*?nullspace-' + [regex]::Escape($label) + '\.jar\s*$')).Groups[1].Value
$hash = (Get-FileHash -LiteralPath (Join-Path $download "nullspace-$label.jar") -Algorithm SHA256).Hash.ToLowerInvariant()
if (-not $listed -or $hash -ne $listed) { throw "The downloaded JAR ($hash) does not match SHA256SUMS.txt ($listed)" }
Write-Host "Downloaded JAR matches SHA256SUMS.txt: $hash"

# 5. Deploy
$deployment = $null
if (-not $SkipDeploy) {
    $deployment = & (Join-Path $PSScriptRoot 'Deploy-Playtest.ps1') -Tag $Tag -JarPath (Join-Path $download "nullspace-$label.jar")
    Write-Host "Deployed: server pid $($deployment.pid), $($deployment.serverErrors) ERROR lines in the startup log, backup $($deployment.backup)."
}

# 6. One record, one commit
if (-not $NoRecord) {
    $doc = Join-Path $root "docs/release/$version.md"
    $lines = @('', '## Release record', '')
    $lines += "- **Tag and commit:** [$Tag](https://github.com/$repo/releases/tag/$Tag) at ``$($commit.Substring(0, 7))``."
    $lines += "- **Local check:** passed in $([Math]::Round($check.elapsedSeconds / 60, 1)) minutes. Parts: " + (($check.parts | ForEach-Object { "$($_.name) $([Math]::Round($_.seconds / 60, 1)) min" }) -join ', ') + '.'
    $lines += "- **Release CI:** [passed]($($run.html_url)) in $ciMinutes minutes. Jobs: " + (($jobs | ForEach-Object { "$($_.name) $($_.conclusion)" }) -join ', ') + '.'
    $lines += "- **Download:** ``nullspace-$label.jar`` matches ``SHA256SUMS.txt``, SHA-256 ``$hash``."
    if ($deployment) {
        $lines += "- **Playtest server:** installed $($deployment.deployedUtc) (process $($deployment.pid), $($deployment.serverErrors) ERROR lines at startup); the previous JAR and world are backed up on the host. The same JAR is in the Modrinth testing profile."
    } else { $lines += '- **Deployment:** not deployed by this run.' }
    # A re-release replaces the record of the previous one: the record is always the last section.
    $existing = if (Test-Path -LiteralPath $doc) { [IO.File]::ReadAllText($doc) } else { '' }
    $existing = $existing -replace '(?s)\r?\n## Release record\r?\n.*$', ''
    [IO.File]::WriteAllText($doc, $existing.TrimEnd() + "`n" + ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    & git -C $root add -- $doc
    & git -C $root commit -m "Record the $version release$(if ($deployment) { ' and deployment' })" -m 'Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>'
    & git -C $root push origin main
    if ($LASTEXITCODE -ne 0) { throw 'Pushing the record failed' }
}
Write-Host "Released $Tag."
