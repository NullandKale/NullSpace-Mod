# Releases and CI

## When CI builds and tests

- **Release tags** run the full build and test suite ([`.github/workflows/release.yml`](../../.github/workflows/release.yml)):
  - `vN.N.N` publishes a **full release**, marked Latest. `N.N.N` must equal `mod_version` in [`gradle.properties`](../../gradle.properties).
  - `vN.N.N_<label>`, for example `v0.1.0_rc1`, publishes a **prerelease** (not Latest). Its `N.N.N` must also equal `mod_version`; the asset is named after the whole tag (`nullspace-0.1.0_rc1.jar`).
  - Any other tag fails. A tag whose release already exists fails: published releases are never changed, so fix a problem with a new tag.
- **Build and test** ([`build.yml`](../../.github/workflows/build.yml)) no longer runs on push or pull request. Start it by hand from the Actions tab when you want a check without releasing.
- **Secret scan** ([`secrets.yml`](../../.github/workflows/secrets.yml)) still runs on every push and pull request. It is a security check, not a build.

Every release attaches the JAR and `SHA256SUMS.txt`. The workflow refuses a JAR with the wrong mod id or version, GameTest entrypoints or test output. Without a `LICENSE` file the run summary warns that the release is public without stated distribution terms (see [release-license-proposal.md](release-license-proposal.md)).

## Releasing

1. Set `mod_version`, commit the verified batch, and push a matching version tag.
2. Wait for the Release workflow. A failed run publishes nothing.
3. Download the published release asset and check its SHA-256 against `SHA256SUMS.txt`.
4. Use that exact asset in the Modrinth testing profile. Local development builds are not release candidates.

A full release becomes Latest immediately. Prefer prerelease tags until the candidate has completed playtesting. Published releases are immutable; fixes need a new tag.