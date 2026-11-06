# GitHub releases and the hosted server

The GitHub release marked **Latest** represents the exact JAR running on the public server. Newer versions remain **drafts or prereleases**. Publishing source commits or CI artifacts does not promote a release.

The release workflow always creates a draft prerelease with `latest=false`. It can refresh an existing draft, but refuses to replace assets on an already published release. A published version needs a new version/tag for further changes.

After verification, deploy the exact JAR attached to the draft/prerelease, restart the server, check that it is healthy and record `/opt/nullspace/deployment.json` with `jarSha256` and the current systemd `pid`. Keep the previous JAR and world backup. Then run:

```powershell
.\scripts\Promote-DeployedRelease.ps1 -Tag v0.1.0 -CheckOnly
.\scripts\Promote-DeployedRelease.ps1 -Tag v0.1.0
```

The script downloads the candidate asset, checks its version and SHA-256 against the live server, and verifies that the deployment receipt belongs to the currently running service. Copying a file without restarting is insufficient. Only then can it promote that draft/prerelease to Latest. SSH keys stay local; GitHub gets no server credentials. CI exercises mismatched hashes, stale process receipts, ambiguous assets, wrong versions and successful promotion through fake endpoints, without publishing anything.

If deployment fails, keep the candidate a draft/prerelease and restore the previous deployed build. After a later rollback, verify the live JAR and restore the corresponding Latest release too. Do not overwrite an older release's files to disguise a version mismatch.
