# Runtime architecture

Who owns what at runtime, in what order the server simulates it, and how it is saved. This describes the code as it is. The October 26, 2026 review and its implementation log are kept as a [dated record](../history/architecture-2026-10-26.md); a new verification run's manifest, not that record, is the evidence for any current claim.

## Server lifetime and tick order

[`ServerContext`](../../src/main/java/com/nullandkale/nullspace/core/ServerContext.java) owns each server's services: repositories, computers, navigation, engineering runtimes, terminal sessions, survival state, previews, diagnostics, scene subscriptions and the list of unresolved journals. One end-of-tick dispatcher runs phases in this order:

`SESSIONS → COMPUTERS → TRAVEL → SYSTEMS → SCENES → TERRAIN → COMBAT → NAVIGATION → SURVIVAL → FIXTURES`

Computers therefore observe the previous completed simulation tick unless a physical edit has already invalidated a grant. Physical edits invalidate grants immediately. Stop handlers flush while the context is still available; the final stopped handler releases the context. Lighting workers access only concurrent chunk-watch counters, and never enter the server context or read the world.

There is no tick phase for saving. The ship store is written when Minecraft saves the world (below).

## The ship store

[`WorldStore`](../../src/main/java/com/nullandkale/nullspace/core/save/WorldStore.java) is the one global ship store of a world, over [`ShipRepository`](../../src/main/java/com/nullandkale/nullspace/core/save/ShipRepository.java). It holds ships, stations, arrival targets, entity authority, scenes, the galaxy and the other world-scoped records. Callers obtain deep snapshots for journals. Only TRANSFER and RECOVERY can replace the root or apply a journal's records, and either invalidates engineering records and recovery-bound computers. Computer and navigation services resolve current records by stable id, and the engineering runtime checks its source-record identity before using cached air and device arrays.

Most code still edits the borrowed sections and ship records of the store directly. Returned records and sections are server-thread-only views; do not retain nested compounds across a transaction. The atomic `mutate` operation validates a copy, publishes changed fields and emits an edit notification. The whole save model has not been converted to immutable domain objects.

### Saved with the world, in two copies

- **When.** [`MinecraftServerSaveMixin`](../../src/main/java/com/nullandkale/nullspace/core/mixin/MinecraftServerSaveMixin.java) saves the store at the end of every world save: autosave, `/save-all`, shutdown and the forced saves of transfers. A change only marks the store dirty (`requestSave`), so the store, the chunks and the player files come from the same moment, and a crash loses the same window of all three. With `/save-off` the store is not written either, so a backup of the world stays consistent; a forced save still writes.
- **Two copies.** [`StoreSlots`](../../src/main/java/com/nullandkale/nullspace/core/save/StoreSlots.java) keeps `ships-a.nbt` and `ships-b.nbt` under the world's `nullspace/` folder, each with a save number that rises with every save. A save writes the slot that does not hold the newest copy, with the temporary-file, read-back, flush and atomic-rename sequence of `WorldStore.write`. The newest copy is never touched while a new one is written.
- **Loading.** The highest readable save number wins. A damaged copy is renamed (`.damaged-<time>`), never deleted, the other copy is loaded, and committed journals newer than it are replayed onto it ([`JournalReplay`](../../src/main/java/com/nullandkale/nullspace/core/save/JournalReplay.java)) before it is saved at once as a new copy. Operators are told on join. If no copy can be read the server refuses to start and names the files to restore. An old single `ships.nbt` loads as save number 0 and is kept as `ships.nbt.pre-journals` after the first save.
- **Forced saves.** Operations that edit blocks after writing an intent keep an immediate save (`WorldStore.save`): shipyard delivery, station builds, docking tunnels, the starting ship, scrapping's neighbours and the site generators. Each of those writes its intent first and resumes from it. Everything else marks the store dirty.

## Journals

Every ship move writes a journal under `nullspace/transfers/`. The design and its reasons are in [journals and save recovery](../design/journals-and-save-recovery.md). In short:

- [`Journals`](../../src/main/java/com/nullandkale/nullspace/core/save/Journals.java) names files `<sequence>-<id>.nbt`; a finished journal is renamed `<sequence>-<id>.s<N>.nbt`, where `N` is the first store save that includes it. Pruning and recovery read only names.
- A journal holds a header (kind, operation, ships, mod version, time), where the move went, the **hull blocks only**, entities, players, and [`StoreRecords`](../../src/main/java/com/nullandkale/nullspace/core/save/StoreRecords.java): the entries of the store the move changed, as before and after values. It never holds the whole store.
- A finished journal is deleted once both store copies include it, normally one save after the move.
- [`TransferRecovery`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferRecovery.java) rolls back an unfinished journal by applying its `before` records and restoring only its own hull, entities and players. Each journal is handled alone. Errors that can clear up are retried (entities still loading for up to 600 ticks, a dimension that is not loaded); any other error sets the whole journal aside in `transfers/quarantine/` with a reason file. A journal that is waiting or set aside keeps its areas and ships locked through [`UnresolvedJournals`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/UnresolvedJournals.java) and [`TransferLocks`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferLocks.java).
- [`JournalCommands`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/JournalCommands.java) gives operators `/nullspace recovery list|show|retry|export|discard`.
- [`BuildJournal`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/BuildJournal.java) journals a build the same way, for operations that edit blocks and records together and cannot resume from a saved intent.
- [`ShipIntegrity`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/ShipIntegrity.java) checks the store against the world when a chunk loads, using the ship id each controller carries. A disagreement marks the ship as needing attention and locks it until an operator resolves it.

`ShipPlacement` owns physical snapshot placement and clearing and the replay guard. [`TravelService`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TravelService.java) retains checkpoint ordering, passenger readiness, entity authority and recovery decisions. Physical edits publish topology and visual invalidations through `ShipMutations`. Source geometry is keyed to physical world regions and is invalidated by real block and light edits; replacing unrelated metadata does not flush those snapshots.

`WorldStore.at` finds the ship around a position with a loop that allocates nothing. It is not indexed, because most code edits ship records without telling the repository, so a cached index could return a ship that no longer exists.

## Pads

[`PadRepository`](../../src/main/java/com/nullandkale/nullspace/core/save/PadRepository.java) loads `nullspace-pads.json` once per server and indexes ids, controllers and dimensions. Registration publishes its cached indexes only after file promotion, and keeps the registry it replaced as `nullspace-pads.previous.json`. A registry that cannot be read is kept as `nullspace-pads.json.damaged-<time>` and the previous copy is loaded in its place. Manual edits require the operator command `/nullspace reload-pads` or a server restart.

`PadStatusService` provides advisory destination status. Listing first reports UNKNOWN, queues work, and uses at most 2,048 controller, floor and heightmap reads per tick across all queued pads. It never loads chunks. Results expire after 40 ticks and affected edits invalidate them immediately. Final landing keeps the authoritative surface, hull and sky checks, so a cached READY reading cannot permit an obstructed landing.

## Engineering and computers

[`ShipSystems`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/ShipSystems.java) coordinates [`ShipRuntime`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/ShipRuntime.java), [`DeviceInventory`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/DeviceInventory.java), [`PowerSystem`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/PowerSystem.java), [`DoorSystem`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/DoorSystem.java) and [`AirSystem`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/AirSystem.java). [`PowerBus`](../../src/main/java/com/nullandkale/nullspace/model/power/PowerBus.java), [`Atmosphere`](../../src/main/java/com/nullandkale/nullspace/model/air/Atmosphere.java) and [`SpatialAir`](../../src/main/java/com/nullandkale/nullspace/model/air/SpatialAir.java) remain pure engines. Device identities and energy travel in the move's records.

[`ShipCommand`](../../src/main/java/com/nullandkale/nullspace/model/engineering/ShipCommand.java) classifies engineering queries and mutations. UI authorization and the engineering executor use that same parser; scripted engineering calls reach the same executor. Transport authentication, proximity, revisions and block permissions stay at the server boundary. Flight, weapons, travel and sensors keep their authoritative native services.

[`FairScheduler`](../../src/main/java/com/nullandkale/nullspace/model/computer/FairScheduler.java) rotates first claim on both job budgets and the server's computer budget. Expression syntax becomes a bounded immutable AST when a program is compiled. Images carry compiler ABI 1; old images without that field use the compatible original layout, and unknown ABIs reject before replacing a working machine. Guest paths never reach host filesystem, process or network APIs.

Computer images use a versioned UTF-8 byte envelope with SHA-256 integrity, capped at 8 MiB per envelope; an invalid envelope stays stored and the computer shows a read-only recovery message. Disk quotas, job quotas and sandbox capabilities are in [NullOS](nullos.md).

## Scene rendering

The server sends block states, light and bounded visual update tags, not block meshes. [`VisualSnapshot`](../../src/main/java/com/nullandkale/nullspace/model/space/VisualSnapshot.java) supplies the same update-tag contract for 32-cube ships and 16-cube terrain sections: at most 64 block entities, 8 KiB per entity and 16 KiB for the whole list. Detached client visuals never register or tick world entities.

[`SceneViewport`](../../src/client/java/com/nullandkale/nullspace/render/scene/SceneViewport.java) shares immutable local geometry across views and retains bounded transformed-quad storage. Camera-relative transforms use doubles until final vertex output. Transparent surfaces keep their per-view ordering. The native, entity and celestial passes are independent of the main world renderer.

A remote ship is shaded by the light stored at its blocks, with a floor of 15% ([`SceneMeshes`](../../src/client/java/com/nullandkale/nullspace/render/scene/SceneMeshes.java)). A ship in space has no sky light and no lamps, so its outside is drawn nearly black unless it carries a light source.

[`ResourceGroup`](../../src/main/java/com/nullandkale/nullspace/core/ResourceGroup.java) owns reverse-order cleanup for native buffers, images, GL objects and staged targets. Constructor failures release already-created resources; repeated close is safe. Compute validates dimensions and scale before allocating view resources, and shared programs compile successfully before they replace the previous set.

## Content and maintenance

[`ContentCatalog`](../../src/main/java/com/nullandkale/nullspace/content/ContentCatalog.java) reads typed equipment capabilities and planet definitions from [`data/nullspace/catalog/`](../../src/main/resources/data/nullspace/catalog). Removed block ids stay registered so saved chunks decode, but have no recipes or guide entries; `LegacyHardware` replaces them with solid iron in bounded server-tick batches, preserving installed inventory as drops, waiting for transfer locks, and dropping pending work when chunks unload.

`generateContentAssets` produces repetitive blockstates, item wrappers, self-drops, mining tags, recipe-unlock advancements and flat planet dimensions. `verifyContentAssets` compares those checked-in wrappers with the definitions. Block models, textures, shaders and shaped recipes remain authored.

```powershell
.\scripts\build\Invoke-Gradle.ps1 generateContentAssets
.\scripts\build\Invoke-Gradle.ps1 verifyContentAssets
.\scripts\build\format.ps1 -Write     # Spotless + Eclipse formatter, gradle/java-format.xml
.\scripts\build\format.ps1            # Java formatting check
.\scripts\test\verify.ps1             # unit, native server, assets, JAR
.\scripts\test\verify.ps1 -Graphics -Multiplayer -Crash
```

Java is formatted in the Visual Studio C# style: Allman braces, four-space indents and one blank line between members. There is **no line-length limit and no warning for long lines on purpose**. A very long line is a sign that the code needs restructuring (named checks, enums, extracted methods), and wrapping it would hide that. GLSL uses [`.clang-format`](../../.clang-format).

The verification runner creates `build/verification/<run>/manifest.json` before starting work. Each suite has selected, status and exit evidence; unselected suites are marked not-selected. Multiplayer requires both real client exit codes and its completed scenario marker. Crash stages require exit 86 during prepare, exit zero during verify, and a fresh completion marker. Root harnesses share Java argument handling through [`scripts/lib/LocalVerification.psm1`](../../scripts/lib/LocalVerification.psm1). See [local verification](local-verification.md) and the [performance test](performance-test.md).

## Documentation map

- This file describes current ownership and maintenance boundaries.
- [journals and save recovery](../design/journals-and-save-recovery.md) is the design of the store, the journals and recovery.
- [`ship-and-space-plan.md`](../history/ship-and-space-plan.md), [`ship-systems-and-viewports.md`](../design/ship-systems-and-viewports.md), [`space-compute-renderer-plan.md`](../design/space-compute-renderer-plan.md) and [`ship-navigation.md`](../design/ship-navigation.md) hold the feature implementation records and accepted gameplay decisions.
- [`ship-recipes.md`](../design/ship-recipes.md), [`nullos.md`](nullos.md), [`in-game-guide.md`](../player/in-game-guide.md), [`test-gallery.md`](test-gallery.md) and [`ship-block-support.md`](../design/ship-block-support.md) describe user-facing workflows.
- Dated review documents under [`docs/history`](../history) are historical evidence, not a substitute for a new run's manifest.
