# Releases, CI and the hosted server

## When CI builds and tests

- **Release tags** run the full build and test suite (`.github/workflows/release.yml`):
  - `vN.N.N` publishes a **full release**, marked Latest. `N.N.N` must equal `mod_version` in `gradle.properties`.
  - `vN.N.N_<label>`, for example `v0.1.0_rc1`, publishes a **prerelease** (not Latest). Its `N.N.N` must also equal `mod_version`; the asset is named after the whole tag (`nullspace-0.1.0_rc1.jar`).
  - Any other tag fails. A tag whose release already exists fails: published releases are never changed, so fix a problem with a new tag.
- **Build and test** (`build.yml`) no longer runs on push or pull request. Start it by hand from the Actions tab when you want a check without releasing.
- **Secret scan** (`secrets.yml`) still runs on every push and pull request. It is a security check, not a build.

Every release attaches the JAR and `SHA256SUMS.txt`. The workflow refuses a JAR with the wrong mod id or version, GameTest entrypoints or test output. Without a `LICENSE` file the run summary warns that the release is public without stated distribution terms (see [release-license-proposal.md](release-license-proposal.md)).

## Releasing and deploying

1. Set `mod_version`, commit, and push a tag: `git tag v0.1.1_rc1 && git push origin v0.1.1_rc1` for a test build, or `git tag v0.1.1 && git push origin v0.1.1` for a full release.
2. Wait for the Release workflow. A failed run publishes nothing.
3. Deploy the release's JAR to the server: back up the current JAR and world, install, restart, confirm the server is healthy, and record `/opt/nullspace/deployment.json` with the JAR's SHA-256 and the service PID. `scripts/release/Promote-DeployedRelease.ps1 -Tag <tag> -CheckOnly` compares the release asset with the live server.
4. Put the same JAR in the Modrinth client profile so clients match the server.

A full release becomes Latest immediately. Prefer prerelease tags until a build is deployed and playtested, so Latest keeps matching the server. If a deployment fails, restore the previous JAR from `/opt/nullspace/backups/`.
