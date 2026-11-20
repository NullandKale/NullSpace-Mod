# Releases and CI

## When CI builds and tests

- **Release tags** run the full build and test suite ([`.github/workflows/release.yml`](../../.github/workflows/release.yml)). The native server tests run as four parallel jobs (`-PgametestShard=N/4`, see [`build.gradle`](../../build.gradle)), the unit tests, asset check and JAR build as a fifth, and the release is published only when all five pass:
  - `vN.N.N` publishes a **full release**, marked Latest. `N.N.N` must equal `mod_version` in [`gradle.properties`](../../gradle.properties).
  - `vN.N.N_<label>`, for example `v0.1.0_rc1`, publishes a **prerelease** (not Latest). Its `N.N.N` must also equal `mod_version`; the asset is named after the whole tag (`nullspace-0.1.0_rc1.jar`).
  - Any other tag fails. A tag whose release already exists replaces that release once the tests pass: the old release is deleted and published again from the tag's new commit, with the same version.
- **Build and test** ([`build.yml`](../../.github/workflows/build.yml)) no longer runs on push or pull request. Start it by hand from the Actions tab when you want a check without releasing.
- **Secret scan** ([`secrets.yml`](../../.github/workflows/secrets.yml)) still runs on every push and pull request. It is a security check, not a build.

Every release attaches the JAR and `SHA256SUMS.txt`. The workflow refuses a JAR with the wrong mod id or version, GameTest entrypoints or test output. Without a `LICENSE` file the run summary warns that the release is public without stated distribution terms (see [release-license-proposal.md](release-license-proposal.md)).

## Releasing

1. Set `mod_version` and commit the batch.
2. Run [`scripts/test/release-check.ps1`](../../scripts/test/release-check.ps1): the parallel check and the optional multiplayer and crash scripts together. The `mod_version` line is left out of the source fingerprint ([`LocalVerification.psm1`](../../scripts/lib/LocalVerification.psm1)), so a check that passed before the bump still counts; any other change to sources, scripts or build files needs a new check.
3. Run [`scripts/release/Publish-Release.ps1`](../../scripts/release/Publish-Release.ps1). It refuses sources that differ from the passing check, pushes the matching version tag, waits for the Release workflow (a failed run publishes nothing), downloads the asset and checks its SHA-256 against `SHA256SUMS.txt`, installs it on the playtest server and in the Modrinth testing profile ([`Deploy-Playtest.ps1`](../../scripts/release/Deploy-Playtest.ps1); `-SkipDeploy` stops before that), and records the release in `docs/release/<version>.md` in one commit.
4. Use that exact asset for playtests. Local development builds are not release candidates.

The server's address and key stay out of the repository: `Deploy-Playtest.ps1` reads them from `%USERPROFILE%/.nullspace/deploy.json`.

A full release becomes Latest immediately. Prefer prerelease tags until the candidate has completed playtesting.

To update a release, commit the fix, run the release check, and run `Publish-Release.ps1` again with the same `mod_version`: it moves the tag to the new commit, the workflow replaces the release, and the record in `docs/release/<version>.md` and the deployment are replaced too. Anyone who downloaded the earlier JAR has a different file under the same version number, so bump `mod_version` when players need to tell the two apart.