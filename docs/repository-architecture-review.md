# Repository and architecture review

Implementation follow-up: [current runtime architecture and review fixes](architecture.md), with [verification evidence](repository-refactor-verification.json). Git work is excluded at the user?s request.

Reviewed October 26, 2026. This is an assessment and proposed refactoring order. Production code, assets, save formats and player worlds were not changed during this review.

The important cleanup is to give persisted ship state, runtime simulation, commands and rendering resources explicit owners. Several useful small systems already exist; the integration services currently carry too many responsibilities and reach into one another's mutable NBT.

## Scope and evidence

- Reviewed a production tree containing 120 Java files across common/client source sets. Traced persistence, simulation, networking, computers, rendering, mixins and content registration, using targeted reads and repository-wide searches. Read the build, launch scripts, test layout and documentation structure.
- Parsed all 150 resource JSON files across common/client resources. Checked 122 model references and 59 texture references against repository assets and the pinned Minecraft 26.3 client JAR: **no missing references found in that check**. There are 163 resource files total and no overlapping resource paths between source sets. This does not validate every resource schema or every possible rendered appearance.
- Ran an isolated Java probe against the compiled mod and pinned Minecraft classes. A legal computer image of 66,116 characters became an empty string through both `NbtIo.writeCompressed` and the actual `ShipStore.write/read` path. Also reproduced scheduler starvation with eight jobs and a one-instruction allowance. Probe source/output are in `build/review/ReviewProbe.java` and `build/review/probe.log`; these are disposable review artifacts.
- `git rev-parse --verify HEAD` fails: this checkout has no initial commit. Source/build/docs currently appear as untracked files.
- The previous verification baseline is 280 unit scenarios, 73 required server tests, 12 graphical entrypoints, two real clients and seven crash/restart stages. Those results remain useful evidence, but the full suite was **not rerun for this documentation-only review**. The new persistence probe exposes a boundary the existing computer tests do not cover.

## Findings, in priority order

### R01 — P1: valid computer images can be lost at the world-save boundary

[ComputerService.java](../src/main/java/com/nullandkale/nullspace/computer/ComputerService.java), line 43, puts the entire JSON image into one NBT string. [VirtualFiles.java](../src/main/java/com/nullandkale/nullspace/computer/VirtualFiles.java) permits 131,072 characters of file content; detached programs and variables add more. The existing large-image test exercises JSON restoration directly, bypassing world persistence.

Minecraft's pinned `StringTag` writes through `DataOutput.writeUTF`. NBT's fallback catches an overlong string, logs the problem and substitutes an empty string. [ShipStore.java](../src/main/java/com/nullandkale/nullspace/ship/ShipStore.java), line 51, checks that the temporary file can be read, so this valid-but-altered file is promoted. On hydration, an empty image skips restore and leaves a fresh computer.

The probe produces:

```text
COMPUTER_IMAGE before=66116 after=0
SHIP_STORE_IMAGE before=66116 after=0
```

**Refactor:** introduce a versioned `ComputerImageCodec` at the persistence boundary. Store bounded UTF-8 bytes or structured NBT rather than a large JSON `StringTag`. Read the current string format for migration. Validate an image round trip before replacing durable state, and preserve corrupt input for diagnosis instead of overwriting it with a fresh machine. Coordinate per-computer budgets with the store's 64 MiB read budget.

**Required tests:** actual compressed world-save round trips at the UTF limit, multi-byte text, maximum legal disks/jobs, old-format migration, corrupt images and interrupted saves. Include a fresh-JVM reload with computer files and detached jobs; current crash fixtures principally cover ship state and cargo.

### R02 — P1: ship NBT is an integration API with no single mutation owner

[ShipStore.java](../src/main/java/com/nullandkale/nullspace/ship/ShipStore.java), line 21, exposes `public CompoundTag data`. Ships, navigation, engineering, computer storage and scene services mutate nested compounds directly. [TravelService.java](../src/main/java/com/nullandkale/nullspace/ship/TravelService.java), lines 187 and 282, snapshots and replaces that root while runtime caches retain references to arrays/device compounds. The recent replay/device-loss bug demonstrates the cost of this coupling.

**Refactor:** add a server-owned `ShipRepository` exposing stable ship IDs and controlled mutation operations. Use typed records for identity, storage bounds, simulated pose and configuration; keep NBT conversion in codecs. Define mutation kinds such as player edit, damage, transfer and recovery. Publish explicit topology/power/air/scene invalidations after mutations. Runtime objects should resolve current state by ID and revision across transaction boundaries.

Retain the existing durable journal/checkpoint semantics. Extract a transfer plan, physical-world operations and journal/recovery codec around that working protocol. Avoid replacing the protocol and all save schemas in one change.

**Required tests:** rollback with warm runtime caches, exact device/air preservation, repeated recovery, damage versus replay invalidation and unchanged loading of existing saves/journals.

### R03 — P1: pad lookup mixes registry I/O with expensive synchronous world inspection

[PadRegistry.java](../src/main/java/com/nullandkale/nullspace/ship/PadRegistry.java), line 38, rereads and parses `nullspace-pads.json` for each query. `below`, `buildHeight` and `reconcileHeights` compose those reads. [ComputerTerminal.java](../src/main/java/com/nullandkale/nullspace/terminal/ComputerTerminal.java) inspects destination states while issuing a helm menu; `PadRegistry.state` can scan an entire 32 x 32 column to world height for each pad. A catalog can contain 128 destinations. This is substantial server-thread work even before drawing the UI.

**Refactor:** a server-owned `PadRepository` should load once, index by ID/controller/dimension and write only on registration changes. Separate cheap catalog lookup from bounded occupancy inspection. Queue/cache status work with terrain/registration revisions; report UNKNOWN while inspection is pending. Landing must retain its final authoritative revalidation. Define whether external JSON edits require an explicit reload.

**Required tests:** no disk reads for ordinary lookup, bounded work per tick for a large loaded catalog, correct UNKNOWN behavior, stacked-pad independence and invalidation after obstruction/controller changes.

### R04 — P2: normal saves rewrite the full store, while durability policy is scattered

[ShipStore.java](../src/main/java/com/nullandkale/nullspace/ship/ShipStore.java), line 32, compresses, rereads, forces and atomically replaces the complete root. Engineering edits, navigation close, camera channel changes, disconnect/death and periodic ticks all call it. [ComputerService.java](../src/main/java/com/nullandkale/nullspace/computer/ComputerService.java), line 81, serializes all computer images during flush even when unchanged. Idle engineering saves occur every 200 ticks.

**Refactor:** separate dirty-state tracking and coalesced ordinary saves from mandatory durable transfer checkpoints. Serialize unchanged computer images once per revision. A save coordinator should own ordinary scheduling and errors. Preserve checkpoint ordering and fsync guarantees; moving all writes to a background thread without immutable snapshots would introduce races.

**Required tests:** unchanged worlds do not rewrite every component, changed data survives shutdown, checkpoint durability is unchanged, and a failed save keeps the last good snapshot.

### R05 — P2: ShipSystems needs simulation components and a typed command boundary

[ShipSystems.java](../src/main/java/com/nullandkale/nullspace/systems/ShipSystems.java) contains discovery, device identity, power allocation, battery changes, spatial air, door queues, profiles, command parsing, authorization-related logic and text presentation. [ComputerService.java](../src/main/java/com/nullandkale/nullspace/computer/ComputerService.java), line 62, also dispatches ship actions and selects native capabilities. Controllers and scripts should meet at a clearer server API.

**Refactor:** retain `PowerBus` and `SpatialAir` as pure engines. Extract `DeviceInventory`, `PowerSystem`, `AirSystem` and `DoorSystem` behind one ship runtime. Parse UI/shell arguments into typed `ShipCommand`/`ShipQuery` objects. Derive read-only versus mutation behavior from command types, rather than maintaining a separate string classifier alongside execution. Keep transport session authentication at the boundary; keep world/gameplay checks in the command handlers. Format player text from query results.

**Required tests:** menu, shell and native-script routes produce equivalent changes; read-only queries never dirty/save state; stale edits reject; all paths observe blackout, topology and travel restrictions.

### R06 — P2: server lifetime and tick ordering are implicit

Runtime state is distributed across static maps: `ShipSystems.LIVE`, terminal sessions, navigation, computers, scene leases and terrain caches. Some are keyed by server, some by player UUID, and engineering uses a string containing `identityHashCode(server)`. Cleanup is registered independently. [NullSpace.java](../src/main/java/com/nullandkale/nullspace/NullSpace.java) also determines simulation ordering through the order in which services register tick callbacks.

**Refactor:** use a small `ServerContext` owning repositories, runtimes, sessions and a documented tick pipeline. Keep thin Fabric event adapters. Give different session kinds their own policies while sharing token ownership, expiry and lifecycle mechanics where appropriate. This does not require a dependency-injection framework.

**Required tests:** sequential server lifetimes in one JVM, context cleanup, connection/session expiry, stale callbacks after shutdown and documented power/computer/flight update ordering.

### R07 — P2: viewport rendering has high allocation pressure and two snapshot paths

[SceneViewport.java](../src/client/java/com/nullandkale/nullspace/client/scene/SceneViewport.java), line 146, transforms every cached quad into new point arrays, vectors and quad objects when a view renders. Moving cameras/ships repeatedly exercise this path; each viewport also owns its own mesh cache. This is an allocation finding, not a measured frame-time regression in this review.

Ship snapshots use 32-cube block/light volumes through [SceneSnapshots.java](../src/main/java/com/nullandkale/nullspace/scene/SceneSnapshots.java). Terrain uses section snapshots with halos, biome tints and block-entity visual data. [ScenePlayers.java](../src/client/java/com/nullandkale/nullspace/client/scene/ScenePlayers.java), line 59, submits block-entity visuals from terrain references; the ship snapshot path does not provide equivalent visual payloads. That asymmetry will keep producing different camera capabilities.

**Refactor:** define a bounded immutable scene snapshot model and shared geometry cache keyed by source/revision/model generation. Keep capture policies distinct, but share the representation consumed by rendering. Separate mesh building, actor visuals, view transforms and render-pass composition. Keep resident GPU meshes where useful and apply per-subject transforms during drawing. Preserve the tested physical/simulated coordinate split and camera-only non-ticking chunk tickets.

**Required tests:** ship and terrain cameras agree on representative block-entity visuals, two views share geometry without sharing temporal history, resource reload invalidates meshes, and moving large ships have bounded allocations/work.

### R08 — P2: render allocations and shader source loading need explicit ownership

[SpaceComputeRenderer.java](../src/client/java/com/nullandkale/nullspace/client/scene/compute/SpaceComputeRenderer.java), line 51, allocates three native buffers before constructor validation. Later texture/buffer/query creation is not protected by partial-construction cleanup, and registration in `LIVE` occurs only at the end. A rejected constructor or failed allocation can therefore bypass normal cleanup.

Shader ownership is also inconsistent. [ComputeProgram.java](../src/client/java/com/nullandkale/nullspace/client/scene/compute/ComputeProgram.java), line 31, reads reloadable resources. [NativeVolumeShader.java](../src/main/java/com/nullandkale/nullspace/scene/NativeVolumeShader.java), line 5, caches the shared optics source once from the classpath. A resource override/reload can update composition shaders without updating the injected native-volume copy of the same optics.

**Refactor:** validate descriptors before allocation, create resource groups through failure-safe factories and make cleanup idempotent. Split shared programs/catalog preparation from per-view targets/history and native-pass integration. Use one reload-generation-aware shader source loader for compute, includes and native patch input; preserve the current successful-compile-before-swap behavior.

**Required tests:** failure at each allocation stage releases earlier resources, repeated close is safe, resize/reload does not leak views, and an optics resource override reaches both native and custom composition paths.

### R09 — P2: the script runtime needs fair scheduling and compiled expression ownership

[VirtualComputer.java](../src/main/java/com/nullandkale/nullspace/computer/VirtualComputer.java), line 35, divides the remaining allowance by remaining jobs in fixed insertion order. With eight jobs and allowance one, the first seven receive zero every tick. The probe after 50 ticks reports `[null, null, null, null, null, null, null, 16.0]`. This is a confirmed edge of the public bounded scheduler API; the current ordinary server allocation is larger, so it does not establish starvation in the standard configuration.

[ScriptLanguage.java](../src/main/java/com/nullandkale/nullspace/computer/ScriptLanguage.java) correctly shares an instruction model between language styles, but instructions retain expression strings that are tokenized/evaluated again at execution. Saved jobs store source and instruction position, which also ties resumability to compiler behavior.

**Refactor:** extract an explicit scheduler with rotating/fair job selection, a compiler with separate Bash/Python frontends and bounded compiled expressions, a shell application, and a virtual disk/image codec. Version the compiler/resume contract before changing instruction layout. Keep explicit native capabilities and the absence of host filesystem/process access.

**Required tests:** progress under budgets below job count, bounded global native-call fairness, compiler compatibility/migration of saved jobs and unchanged sandbox restrictions.

### R10 — P2: content and planet definitions are repeated across code/assets/docs

Machine IDs and behavior appear in [ShipBlocks.java](../src/main/java/com/nullandkale/nullspace/ship/ShipBlocks.java), equipment menus, engineering dispatch, recipe indexes and fixtures. Planet IDs/atmosphere/layout appear in `LocalSpaces`, `PlanetSurfaces`, `SceneCatalog` and dimension JSON. Guide bootstrap parsing recognizes plank/stone tags by hand, while the live recipe path uses the real recipe manager.

**Refactor:** use small typed `EquipmentDefinition` and `PlanetDefinition` catalogs for shared identity/capability/metadata. Generate repetitive blockstates, item wrappers, self-drop loot, mining tags and recipe unlock advancements from authoritative definitions. Keep authored geometry, shaders and shaped recipes as readable assets. Build guide recipe snapshots from the loaded recipe manager after reload; keep any pre-server bootstrap fallback explicitly limited. Continue generating the vanilla block support catalog from the actual policy, which is already a useful pattern.

**Required tests:** registry-to-asset completeness, every defined planet has a dimension and atmosphere metadata, resource-pack/tag/recipe overrides and independently authored recipe expectations. Do not generate test expectations from the same production definition and call that independent coverage.

### R11 — P2: verification reports and launch harnesses need one artifact model

The test layers are valuable, but Gradle, `multiplayer-test.ps1`, `crash-test.ps1`, manually captured logs and multiple verification JSON documents describe them separately. Test configuration also recreates the client run directory, requiring manual screenshot preservation. Some fault tests are opt-in/no-op in ordinary native runs; a count alone does not identify which behavior was exercised.

**Refactor:** retain the existing test layers and expose one verification runner producing a run ID, selected suites, exit codes, per-stage markers, screenshots and JAR digest. Share launch argument handling between local harnesses. Derive manifests from executed reports/markers, and store run artifacts under one stable directory before another Gradle invocation. Preserve local-only multiplayer/crash execution and the current CI graphics distinction.

**Required tests:** a missing marker or skipped suite cannot be reported as passed, interrupted runs remain diagnosable, and evidence survives subsequent build configuration.

### R12 — P1 prerequisite / P2 cleanup: establish a baseline and make code readable

There is currently no Git HEAD to compare or roll back to. Establish an initial reviewed source baseline before structural work, excluding generated worlds, tools and logs with the existing ignore policy. No commit was made as part of this review.

There are 77 production Java lines longer than 500 characters across 25 files. Several large Java methods and shader functions occupy single lines. That makes ordinary exception paths, side effects and resource ownership harder to audit.

**Refactor:** adopt one Java formatter and readable GLSL formatting, with a mechanical formatting-only change separated from semantic refactors. Then move responsibilities at the boundaries above. Add a short architecture map and distinguish active feature documentation, proposals and historical verification records. Avoid splitting classes merely to hit a line-count target.

## Systems worth preserving

`PadScanner`, `PowerBus`, `Atmosphere`/`SpatialAir`, `FlightDynamics`, `ScenePose`, `VoxelVolume`, `SpaceDefinition`/`SpaceBvh`, and `TerminalBuffer` already provide useful bounded/pure seams. The common/client split, actual server authorization checks, dedicated native tests, explicit process-crash checkpoints, per-view temporal history and last-working shader fallback should survive the cleanup.

The authored assets are small, use vanilla textures coherently and passed the reference audit. Rebuilding all models/textures would not address the principal problems found here. The mod also does not need a generic ECS, plugin framework, external OS emulator or replacement build system for these refactors.

## Recommended order and acceptance gates

1. Establish the source baseline. Fix R01 with a native persistence regression and a migration-compatible image codec; keep that correctness change isolated.
2. Separate mechanical formatting. Introduce typed IDs/coordinate records and repository adapters without changing persisted identifiers or journal semantics.
3. Move ship/pad persistence behind repositories; add explicit mutation/invalidation and ordinary-save scheduling. Re-run the full crash/restart matrix before proceeding.
4. Extract engineering components and typed commands, then wire server lifetime/tick ownership. Validate equivalent UI/script behavior and real multiplayer sessions.
5. Unify scene input and geometry ownership, make render allocations failure-safe and unify shader reload. Validate moving-camera allocation/time measurements, image comparisons and GPU resource cleanup.
6. Extract compiler/scheduler boundaries with saved-job compatibility tests; consolidate content generation, launch artifacts and active documentation.

Each step should preserve survival saves, item/recipe IDs, player inventory, door/air behavior and multiplayer authority. Run targeted checks per step and the relevant existing integration/crash/GPU suites at the boundaries they protect. This is an incremental cleanup around functioning systems, with the demonstrated storage defect handled first.
