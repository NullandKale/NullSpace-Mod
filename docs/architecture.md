# Runtime architecture

Implementation record for the October 26, 2026 repository review. The original findings remain in [repository-architecture-review.md](repository-architecture-review.md); the verification receipt for this implementation is [repository-refactor-verification.json](repository-refactor-verification.json).

## Ownership and persistence

`ServerContext` owns each server's repositories, computers, navigation, engineering runtimes, terminal sessions, survival state, previews, diagnostics and scene subscriptions. One end-of-tick dispatcher runs phases in this order:

`SESSIONS ? COMPUTERS ? TRAVEL ? SYSTEMS ? SCENES ? TERRAIN ? COMBAT ? NAVIGATION ? SURVIVAL ? FIXTURES ? SAVE`

Computers therefore observe the previous completed simulation tick unless a physical edit has already invalidated a grant. Physical edits invalidate grants immediately. Stop handlers flush while the context is still available; the final stopped handler releases the context. Lighting workers access only concurrent chunk-watch counters, and never enter the server context or read the world.

`ShipRepository` owns the metadata root. Identity, location and simulated pose have typed adapters. Callers obtain deep snapshots for journals. Only TRANSFER and RECOVERY can replace the root, and replacement invalidates engineering records and recovery-bound computers. Computer and navigation services resolve current records by stable ID. The engineering runtime checks its source-record identity before using cached air/device arrays.

NBT domain adapters remain for the existing save and journal schemas. Returned ship records and named sections are borrowed, server-thread-only views; do not retain their nested compounds across a transaction. The atomic `mutate` operation validates a copy, publishes changed fields, and emits an edit notification. Unchanged nested fields retain identity. This is an incremental migration boundary; the entire save model has not been converted to immutable domain objects.

`ShipPlacement` owns physical snapshot placement/clearing and the replay guard. `TravelService` retains checkpoint ordering, passenger readiness, entity authority and recovery decisions. Physical edits publish topology/visual invalidations through `ShipMutations`. Source geometry is keyed to physical world regions and is invalidated by real block/light edits; replacing unrelated metadata does not flush those snapshots.

Ordinary edits request a coalesced save. The SAVE phase services requests every 20 ticks. An unchanged root skips compression, verification, force and replacement. Mandatory travel/recovery/shutdown saves remain synchronous. Save publication compares the complete decoded NBT with its source, so Minecraft's silent overlong-string fallback cannot replace a good file. Windows sharing violations receive at most five 10 ms retries. Failed ordinary writes remain pending; mandatory checkpoint errors propagate.

Computer images use a versioned UTF-8 byte envelope with SHA-256 integrity, capped at 8 MiB per envelope. Existing string images are read and migrate on a successful save. Invalid envelopes remain stored; the affected computer displays a read-only recovery message. The complete store retains its 64 MiB NBT read budget. Oversized/corrupt snapshots fail before promotion. Disk quotas, job quotas and sandbox capabilities remain unchanged. Images are reused while disk, directory, working-directory and detached-job state remain unchanged; idle clock advancement alone does not serialize the disk again.

## Pads

`PadRepository` loads `nullspace-pads.json` once per server and indexes IDs, controllers and dimensions. Registration publishes its cached indexes only after file promotion. Manual edits require the operator command `/nullspace reload-pads` or a server restart.

`PadStatusService` provides advisory destination status. Listing first reports UNKNOWN, queues work, and uses at most 2,048 controller/floor/heightmap reads per tick across all queued pads. It never loads chunks. Results expire after 40 ticks and affected edits invalidate them immediately. The heightmap detects sky obstructions without scanning every vertical block. Final landing keeps the authoritative surface, hull and sky checks, so a cached READY reading cannot permit an obstructed landing.

## Engineering and computers

`ShipSystems` coordinates `ShipRuntime`, `DeviceInventory`, `PowerSystem`, `DoorSystem` and `AirSystem`. `PowerBus`, `Atmosphere` and `SpatialAir` remain pure engines. Device identities and energy still travel in the original journal snapshot.

`ShipCommand` classifies engineering queries and mutations. UI authorization and the engineering executor use that same parser; scripted engineering calls reach the same executor. Transport authentication, proximity, revisions and block permissions stay at the server boundary. Flight, weapons, travel and sensors retain their existing authoritative native services.

`FairScheduler` rotates first claim on both job budgets and the server's computer budget. A positive remaining allowance is never rounded down to zero. Expression syntax becomes a bounded immutable AST when a program is compiled; evaluation reads current variables and short-circuits native calls. Bash-style and Python-style statement syntax retain their shared instruction layout. Images carry compiler ABI 1; old images without that field use the compatible original layout, and unknown ABIs reject before replacing a working machine. Guest paths never reach host filesystem/process/network APIs.

## Scene rendering

The server sends block states, light and bounded visual update tags. It does not send Minecraft block meshes. `VisualSnapshot` supplies the same update-tag contract for 32-cube ships and 16-cube terrain sections: at most 64 block entities, 8 KiB per entity and 16 KiB for the complete wrapped list. Both producer and decoder account for the same list. Inventory/private full-save tags are not substituted for visual update tags. Detached client visuals never register or tick world entities.

`SceneViewport` shares immutable local geometry across views and retains bounded transformed-quad storage. Camera-relative transforms use doubles until final vertex output. Moving views reuse quad/vector storage after warm-up. Geometry still undergoes CPU transforms and GPU staging for each rendered view; resident local-space GPU meshes are a further optimization, not part of this implementation. Transparent surfaces keep their per-view ordering. The existing native/entity/celestial passes remain independent of the main world renderer.

`ResourceGroup` owns reverse-order cleanup for native buffers, images, GL objects and staged targets. Constructor failures release already-created resources; repeated close is safe. Compute validates dimensions and scale before allocating view resources. Shared programs still compile successfully before replacing the previous set. Native volume patch input now comes from the active resource manager instead of a classpath-frozen optics string, matching resource-pack reload behavior in custom composition shaders.

## Content and maintenance

`ContentCatalog` reads typed equipment capabilities and planet definitions from `data/nullspace/catalog/`. The catalogs supply equipment discovery/menu capabilities, guide recipe ordering, navigation metadata and surface atmosphere/dimension links. Item IDs, recipes, addresses and generated planet terrain remain compatible.

`generateContentAssets` produces repetitive blockstates, item wrappers, self-drops, mining tags, recipe-unlock advancements and flat planet dimensions. `verifyContentAssets` compares those checked-in wrappers with the definitions. Block models, textures, shaders and shaped recipes remain authored resources; recipe tests retain independently specified expectations and exercise the real server recipe manager.

```powershell
.\scripts\Invoke-Gradle.ps1 generateContentAssets
.\scripts\Invoke-Gradle.ps1 verifyContentAssets
.\format.ps1 -Write     # pinned google-java-format 1.35.0
.\format.ps1            # Java formatting check
.\verify.ps1            # unit, native server, assets, JAR
.\verify.ps1 -Graphics -Multiplayer -Crash
```

Java formatting uses the checksum-pinned upstream formatter. GLSL uses `.clang-format`; this pass used clang-format 16.0.5, with `--assume-filename=shader.cpp`, on `.comp`, `.glsl`, `.vsh` and `.fsh` files. Explicit JOML imports prevent the formatter's `java.lang.Math` shortening from introducing an ambiguous import.

The verification runner creates `build/verification/<run>/manifest.json` before starting work. Each suite has selected/status/exit evidence; unselected suites are marked not-selected. Reports and client artifacts are copied before another Gradle configuration can recreate their source directory. Graphical entrypoints emit test-only completion receipts. Multiplayer requires both real client exit codes and its completed scenario marker. Crash stages require exit 86 during prepare, exit zero during verify, and a fresh completion marker. Interrupted runs keep logs and an incomplete/failed manifest. Root harnesses share Java argument-file handling through `scripts/LocalVerification.psm1`.

## Documentation map

- This file describes current ownership and maintenance boundaries.
- `ship-and-space-plan.md`, `ship-systems-and-viewports.md`, `space-compute-renderer-plan.md` and `ship-navigation.md` contain the feature implementation records and accepted gameplay decisions.
- `ship-recipes.md`, `nullos.md`, `in-game-guide.md`, `test-gallery.md` and `ship-block-support.md` describe user-facing workflows.
- Dated review documents and earlier verification JSON files are historical evidence. They are not a substitute for a new run's manifest.

Git initialization, staging and commits were excluded at the user's request. A pre-refactor source/tooling backup was created at `build/refactor-backup/20261026-125929.zip`. Automated validation uses disposable build-directory worlds and does not open the user's survival saves.

## Review disposition

| Finding | Implemented boundary |
| --- | --- |
| R01 | Byte-based image codec, legacy reader, integrity checks, native round-trip verification and last-good-file preservation |
| R02 | Private repository root, typed identity/location/pose adapters, atomic edit seam, explicit transaction replacement, runtime rebind and extracted physical replay primitives |
| R03 | Cached indexed pad registry, explicit reload, globally budgeted advisory status and authoritative landing validation |
| R04 | Coalesced ordinary saves, unchanged-root write suppression and unchanged-computer-image reuse; durable checkpoints retained |
| R05 | Device/power/door/air components with a shared parsed engineering command boundary |
| R06 | Server-owned services/sessions/caches and one documented phase dispatcher |
| R07 | Shared local geometry, reused transformed storage, common bounded ship/terrain visual snapshots and native visual submission |
| R08 | Failure-safe resource ownership, idempotent close, allocation fault tests and resource-manager-backed native optics |
| R09 | Fair job/computer scheduler, compiled expressions and a versioned resume ABI |
| R10 | Typed catalogs and deterministic generation/checking of repetitive content resources |
| R11 | Combined verification runner, explicit suite selection/receipts, stable artifacts and common Java argument handling |
| R12 | Source/tooling backup, pinned Java formatting, readable GLSL and architecture/documentation map; Git excluded by instruction |

The adapters described above preserve the current schemas. Full immutable save models, separate language-specific compiler classes, resident GPU hull meshes and further subdivision of shared compute programs are possible follow-ups. They are not prerequisites for the corrected persistence, invalidation, fairness and allocation behavior, and are not claimed as completed here.

## Verified implementation

The final matrix passed **294 unit scenarios, 83 required native server tests, 12 graphical entrypoints, two real local clients and all seven real crash/restart stages**. The source tree passes the pinned Java formatting check. All 154 resource JSON files parse; 122 model references and 59 texture references resolve with no duplicate release resource paths. The 13 formatted shaders have identical token streams to the pre-refactor backup.

The graphics receipts are preserved in `build/verification/20261026-133633-0af409/graphics/`. That run later stopped at an obsolete multiplayer assertion about raw NBT text. After changing the assertion to decode the persisted virtual disk, `build/verification/20261026-134521-28c303/` passed the unit/native/assets/multiplayer/crash suites. Production code and resources are identical between those runs; the second manifest explicitly leaves graphics not-selected. The implementation receipt links each actual result and retains the failed run for diagnosis.

The release JAR is `build/libs/nullspace-0.1.0.jar` (878,933 bytes), SHA-256 `a44d0debb84c09c1b4cab1849a74d6784c2e688f6736e149e2f068503f12388f`. It contains no GameTest/evidence classes. The GPU benchmark is one local fixture measurement, not a general frame-rate guarantee: 640 ? 360 main view plus two staggered 320 ? 180 monitors; median celestial surface 0.61 ms, main volume integration/reconstruction 7.12 ms, and compute CPU submission 0.40 ms on the RTX 3070 Laptop GPU. These timings do not measure the complete game frame.

## Test-review follow-up

The [test-review follow-up](test-gap-review.md) fixes document-save scheduling, corrupt computer ownership metadata, synchronized device-budget retries and cleanup after `Error`. It adds service-level rollback and persistence tests, actual resource/datapack overrides, repeated world/GPU lifecycle checks and a landing crash matrix. Earlier verification receipts above remain historical; current evidence is recorded separately.

## Test review follow-up - October 26, 2026

The final frozen-source run passed **303 unit scenarios, 93 mod GameTests plus Minecraft's optional smoke test, all 14 graphical entrypoints, two real local multiplayer clients, 14 physical launch/landing crash-restart cases and 15 verification-harness checks**. Minecraft labels its total count of 94 as required tests; the manifest distinguishes the 93 mod tests from the optional vanilla fixture. The ordinary crash entrypoint returns early; its full behavior is checked separately in the 14-case process matrix.

Evidence: [verification receipt](test-gap-verification.json), `build/verification/20261026-145734-01e044/manifest.json`, preserved logs/reports/screenshots and completion receipts. All selected suites pass against unchanged source/tooling fingerprint `9909f5c4da9e2582a5ccc0c89174bccee5c6b396b9a2aecee083e1b5bcf696f7`. The pinned formatter check passes.

The new checks cover actual Unicode storage, ordinary save retries and standalone guest-edit durability, corrupt computer preservation/migration, warm rollback, competing computers, unloaded hulls, real pad lifecycle changes, resource/datapack overrides and repeated world/GPU lifetimes. They exposed document/computer save-request gaps, malformed-owner data loss, synchronized device-budget starvation and cleanup failures after `Error`. [Detailed changes and limits](test-gap-review.md) also document the validation fixture corrections.

The release JAR is `build/libs/nullspace-0.1.0.jar`, **881,387 bytes**, SHA-256 `289bbee042449d7cb379dd0333c3f2241adf9321bcec4224bc8868c809a01755`. All 152 packaged JSON resources parse and no test/evidence classes are packaged. Player survival saves were not opened or edited; Git initialization, staging and commits were excluded.
