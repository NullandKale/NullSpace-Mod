# Journals and save recovery

**Status: implementation complete; final integration pending.** Production BUILD and RECORD journals, operator restore and batched BUILD saves are implemented. Focused correctness and crash verification is recorded in [the completion log](../development/journal-completion-work.md). Further performance measurement and tuning are deferred at the user's request; the one-second pause target does not gate this batch.

NullSpace keeps one global ship store beside the Minecraft world, and a transfer journal for every move. This document defines what a journal holds, when the store is saved, how a damaged store is recovered without disagreeing with the world, and what happens when a journal cannot be replayed. It answers repository review findings 1 (journals pile up), 2 (one bad journal stops the server), 3 (a waiting journal does not lock its area) and 9 (the store has no second copy).

It has three parts, which work together:

1. **The store saves when the world saves**, so after a crash the two come from the same moment.
2. **Two copies of the store, with the journals as the log of changes between them**, so a damaged copy is rebuilt to match the world instead of falling one save behind it.
3. **Records are checked against the world when chunks load**, so any disagreement that remains is caught and contained instead of doing silent damage.

The ship store stays one global file. Ship data does not move into chunks.

## What was built

| Part | Built | Code |
| --- | --- | --- |
| Store saved with the world | The store is written at the end of every world save and only marked dirty by ordinary edits. Save-off is honoured. | [`MinecraftServerSaveMixin`](../../src/main/java/com/nullandkale/nullspace/core/mixin/MinecraftServerSaveMixin.java), [`WorldStore`](../../src/main/java/com/nullandkale/nullspace/core/save/WorldStore.java) |
| Two copies | `ships-a.nbt` and `ships-b.nbt` with save numbers, damaged copy kept, refusal to start when none is readable, migration from `ships.nbt` | [`StoreSlots`](../../src/main/java/com/nullandkale/nullspace/core/save/StoreSlots.java) |
| Records and replay | Before and after values of the store entries an operation changed; replay onto an older copy | [`StoreRecords`](../../src/main/java/com/nullandkale/nullspace/core/save/StoreRecords.java), [`JournalReplay`](../../src/main/java/com/nullandkale/nullspace/core/save/JournalReplay.java) |
| Journal files | Sequence-numbered names, finished journals renamed with their save number, pruning by name | [`Journals`](../../src/main/java/com/nullandkale/nullspace/core/save/Journals.java) |
| MOVE journals | Hull-only template, records instead of two whole stores, rollback of the journal's own records, hull, entities and players | [`ShipTransfer`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/ShipTransfer.java), [`TransferRecovery`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferRecovery.java), [`TransferReplay`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferReplay.java) |
| Recovery that cannot finish | Retry for entities and missing dimensions, any other error sets the whole journal aside, locks, operator notices | [`TransferRecovery`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferRecovery.java), [`UnresolvedJournals`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/UnresolvedJournals.java), [`TransferLocks`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferLocks.java) |
| Operator commands | `/nullspace recovery list`, `show`, `retry`, `restore`, `export`, `discard` | [`JournalCommands`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/JournalCommands.java) |
| Chunk-load check | Ship id on every controller, checked in both directions when a chunk loads | [`ShipIntegrity`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/ShipIntegrity.java) |
| Build journals | Production physical operations, sparse and multiple footprints, per-step records, locks, entities and scheduled ticks | [`BuildJournal`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/BuildJournal.java) |
| Pad registry | Previous copy kept, fallback when unreadable | [`PadRepository`](../../src/main/java/com/nullandkale/nullspace/core/save/PadRepository.java) |

### Where the code differs from the design above

- **Production BUILD and RECORD replace the earlier deferrals.** New physical deliveries and station builds belong to BUILD jobs, including their intermediate saves and nested collar work. Old delivery intents retain a migration path. Ownership, titles, crew, respawn bindings, registration and station access use RECORD journals. Continuous simulation still marks the store dirty.
- **Operator restore is implemented.** A readable MOVE can be restored to a clear named pad or free space slot. Its original is held while a rescue BUILD captures all affected footprints, entities and records; rollback returns the original to quarantine, and commit archives it as restored.
- **The controllers needed a block entity.** The ship controller and the station core were plain blocks. [`ControllerBlockEntity`](../../src/main/java/com/nullandkale/nullspace/content/machine/ControllerBlockEntity.java) is a minimal one for them (the ship id only, no inventory, no menu), so hoppers and players cannot put anything in it. Like any block entity it travels with the hull. Registering a ship, or claiming a new helm, stamps the controller ([`ShipTopology`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/ShipTopology.java)); ships from before this version are stamped the first time record and controller agree.
- **The chunk-load check runs from the server tick.** Chunk loading happens inside the chunk pipeline, where an exception is swallowed and leaves the chunk half loaded (its entities never load). The load callback only notes the chunk, and [`ShipIntegrity`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/ShipIntegrity.java) checks up to 32 noted chunks per tick, catching and logging any failure. A stale stamp, left by a ship the store no longer has, is replaced with the id of the record that stands at the controller instead of being flagged; only two existing ships claiming one controller, or a controller of a ship the store does not record, are flagged. One flag per controller position.
- **The chunk-load check never flags a missing controller.** A destroyed controller is an ordinary state of a damaged ship, and such a ship must stay recallable. Only a controller that carries the wrong ship's id, or that carries the id of a ship the store records elsewhere or not at all, is flagged. Accepting a flag clears the stale stamp (`discard` on a check entry).
- **The integrity index follows repository generation and collection size.** RECORD commits notify the repository after direct borrowed-record edits. Continuous simulation does not require an index rebuild.
- **`nullspace-pads.json` keeps its previous generation, not numbered slots.** The registry is a JSON file operators may edit by hand and reload, so it stays one readable file. Before a registration replaces it, the old file is copied to `nullspace-pads.previous.json`. If the registry cannot be read, or is missing while the previous copy exists, it is kept as `nullspace-pads.json.damaged-<time>`, the previous copy (one registration behind) is loaded, and it is written back as the registry. Players only re-register what the lost write held. [`PadRepository`](../../src/main/java/com/nullandkale/nullspace/core/save/PadRepository.java).
- **Performance accounting changed.** The opt-in harness restores 50 ms pacing and counts completed logical operations over monotonic elapsed time. Old simulated-time results are incompatible. BUILD cycle smoke checks and fresh-process startup cases pass. Larger remeasurement and tuning are deferred; see the linked performance design.

## Goals

- After a crash, the store and the world agree as closely as vanilla's own data (`level.dat`, player files) agrees with chunks.
- A damaged store file never stops the world from loading while a good copy exists.
- Recovery never crashes the server. Whatever it cannot finish is locked, visible to operators, and resolvable with a command.
- Journals do not accumulate. One lives exactly as long as some copy of the store might need it.
- A journal holds only what its operation needs, not the whole store.

Not goals: consistency across chunks (Minecraft does not provide it either; transfers already force a full save), and compression. The store is gzip NBT already: World 5's 41 ships are 5.7 MB raw and 116 KB on disk.

## Before this change

The store is [`WorldStore`](../../src/main/java/com/nullandkale/nullspace/core/save/WorldStore.java) over [`ShipRepository`](../../src/main/java/com/nullandkale/nullspace/core/save/ShipRepository.java), saved to `nullspace/ships.nbt`.

- **Timing.** Any change requests a save, and the SAVE phase writes it within 20 ticks. About 80 calls in 34 files also force an immediate save. Minecraft saves chunks every 300 seconds (`MinecraftServer.autoSave`), on shutdown, and when a chunk unloads. After a crash the store is therefore usually minutes *ahead* of the chunks. Only transfers line the two up, because [`ShipTransfer`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/ShipTransfer.java) forces `saveEverything` before COMMITTED.
- **Write paths.** Most code edits borrowed sections of the store directly. Only four callers use `ShipRepository.mutate`, and three root replacements use `replace` (transfer, transfer recovery and [`StationRegeneration`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationRegeneration.java)).
- **One copy.** A save is written to a temporary file, read back and compared, flushed and atomically renamed. That protects against a torn write but not against a file damaged later; if `ships.nbt` cannot be read, the world does not start.

A journal today, measured on a real launch:

| Field | Contents | Size (raw) | Problem |
| --- | --- | --- | --- |
| `id`, `version`, `stage` | Header | tiny | No kind, ship or time, so a stuck journal cannot be described |
| `source`, `target`, `min`, `max`, `delta` | Where the move went | tiny | |
| `template` | The whole bounding box, empty space included (32,768 entries) | 1.1 MB | Rollback clears and rewrites the whole box, not only the ship |
| `entities` | Each journaled entity and its riders | 2 KB | |
| `players` | Each player's return position | tiny | |
| `before` | The entire store | 285 KB, grows with the world | Rollback replaces the whole store, erasing unrelated changes made since |
| `after` | The entire store | 286 KB | Never read |

[`TransferRecovery`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferRecovery.java) reads every journal on start and after any failed transfer, rolls back those that are not COMMITTED or ROLLED_BACK, and never deletes any. It processes them in file-name order, which is UUID order, not the order they were written. A journal that must wait for entities to load retries from a static tick hook, holds no lock, and replaces the whole store when it finally runs. Any exception stops recovery and, from the tick hook, the server.

## Part 1: the store saves with the world

The store is written as part of Minecraft's own save, not on its own schedule.

- **Hook.** A mixin at the end of `MinecraftServer.saveAllChunks` writes the store after the levels are saved. That one method is called by autosave, `/save-all`, `saveEverything` (which transfers use) and shutdown, so every world save carries the store with it. The store write is synchronous, as today.
- **`/save-off` is honoured.** When every level has saving disabled and the save is not forced, the store is not written either. Backup tools rely on save-off to freeze the world, and the store is part of the world.
- **Requests become a dirty flag.** `requestSave` only marks the store changed. The 20-tick SAVE phase goes away.
- **Forced saves are sorted into two kinds**, one call site at a time:
  - *State changes* (a course step, a docking state, a claim, a title, a bed binding) become a dirty flag. They are saved with the world, which also keeps them in step with the items and positions in player files that some of them change.
  - *Intent before a world edit* (clearing rescue anchors before a teleport, recording a boarding before moving a player, recording a docking tunnel before building it) becomes a journal (Part 2). That is what the forced save was standing in for.
- **Shutdown.** Handlers that flush into the store on `SERVER_STOPPING` ([`ComputerService`](../../src/main/java/com/nullandkale/nullspace/gameplay/computer/ComputerService.java), [`NavigationService`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/NavigationService.java)) keep flushing, but no longer write the file; the final `saveAllChunks` does. Implementation must confirm that Fabric fires `SERVER_STOPPING` before that save.

What a crash costs: up to one autosave interval of store changes, the same window the chunks and player files lose. That is the point: the three roll back together.

Known limits:

- A chunk that unloads between autosaves is saved at that moment, so it can be newer than the store. That only matters for store data that describes blocks (air readings, device energy). The ship runtime must treat that data as a cache it rebuilds when the blocks disagree; implementation must confirm [`ShipRuntime`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/ShipRuntime.java) already does.
- Autosave queues chunk writes without waiting (`flush = false`), so the store can reach the disk slightly before the last chunks. Operations that need exact agreement already force a flushed save.

## Part 2: two copies, and journals as the change log

### Two store copies

- The store lives in two files, `nullspace/ships-a.nbt` and `nullspace/ships-b.nbt`. Each holds a **save number** that increases by one with every save.
- A save writes the slot that does **not** hold the newest good copy, using the existing temporary-file, read-back, flush and atomic-rename sequence. The newest good copy is never touched during a save.
- On start, both files are read. The highest save number that reads and validates wins.
- If the newest copy cannot be read:
  1. it is renamed to `ships-a.nbt.damaged-<time>` (or `-b`) and kept;
  2. the other copy is loaded;
  3. committed journals are replayed onto it (see below), bringing it up to the world;
  4. the result is saved at once as a new save number;
  5. the log, operators on join and `/nullspace recovery` say what happened and which file was set aside.
- If neither copy can be read, the server refuses to start and names the files and the backup to restore. Starting with an empty store would orphan every ship.
- [`PadRepository`](../../src/main/java/com/nullandkale/nullspace/core/save/PadRepository.java) (`nullspace-pads.json`) uses the same two-copy scheme. Today its fallback rename is not atomic.

### What a journal is

A journal is the record of one **operation**: anything that creates, removes or moves a ship, or edits world blocks together with store records. There are three kinds:

| Kind | Operations | World part | Before COMMITTED |
| --- | --- | --- | --- |
| MOVE | Launch, landing, recall, storing, every other [`ShipTransfer`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/ShipTransfer.java) | Hull, entities, players | Forced, flushed world save (as today) |
| BUILD | Docking tunnel ([`DockingTunnel`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingTunnel.java)), station core and rooms ([`StationDeployment`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationDeployment.java), [`StationBuilds`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationBuilds.java)), station regeneration, shipyard delivery ([`ShipYardService`](../../src/main/java/com/nullandkale/nullspace/gameplay/shipyard/ShipYardService.java)), the starting ship ([`SpaceStart`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/SpaceStart.java)), Terra Harbor ([`TerraHarbor`](../../src/main/java/com/nullandkale/nullspace/worldgen/sites/TerraHarbor.java)) | The blocks placed or replaced | Shared flushed world save; urgent first arrivals and restores can force the barrier |
| RECORD | Ship registration ([`ShipTopology`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/ShipTopology.java)), pad reframing ([`PadAllocation`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/PadAllocation.java)), crew release ([`CrewRelease`](../../src/main/java/com/nullandkale/nullspace/gameplay/ownership/CrewRelease.java)), claims and title transfers | None | Nothing; written COMMITTED in the same server task |

Scrapping a ship ([`ShipScrap`](../../src/main/java/com/nullandkale/nullspace/gameplay/ownership/ShipScrap.java)) is a BUILD too: it removes blocks and a record together.

**Scope: enough to rescue a ship by hand.** Any change an operator would need to put a ship back after a failure is journaled: where it is, its name and registration, its owner and Ship Title, its home pad, its crew, and its creation or removal. That is why claims, title transfers, renames, home-pad changes and crew joining or leaving are RECORD operations. Each journal holds the *whole* record of every ship it changed, not only the changed fields, so a single journal is enough to rebuild that ship's record. Continuous state (air, energy, positions in space, course progress, docking state) is never journaled on its own; a fallback copy loses at most one save interval of it, the same as the chunks.

The shipyard's saved delivery orders become BUILD journals, replacing their separate three-retry recovery.

### What a journal holds

| Part | Contents | Used for |
| --- | --- | --- |
| Header | Format version, sequence number, kind, operation (`launch`, `recall`, `station_room`, …), stage, ship ids, mod version, real time, and the **save number** of the store save that will include it | Ordering, replay, deletion, and describing a stuck journal |
| Where | Source and target dimension and bounds, and the offset between them | Locks and rollback |
| Hull (MOVE, BUILD) | The blocks that moved or were placed, with their block entities and scheduled ticks, relative to the minimum corner. Not the whole box. For BUILD, the blocks that were there before | Rollback |
| Entities (MOVE) | Each journaled entity with its riders, and a restore status per entity | Rollback |
| Players (MOVE) | Each player's return position | Rollback |
| Records | Every store entry the operation changed, as `before` and `after` values (absent meaning "did not exist") | `before` for rollback, `after` for replay, both for a rescue by hand |

The records are not listed by hand. The operation snapshots the store when it starts (as transfers do now), and at commit the journal stores the difference: the whole record of each ship that changed, keyed by ship id, and each changed entry in keyed sections such as `targets` and `entityAuthority`. A record the operation touched cannot be missed, and nothing it did not touch is included.

Together, the hull, entities and records make a journal self-contained: from one file an operator can see what the ship was, who owned it, who was aboard and what it carried, and put it back (see [Rescuing a ship by hand](#rescuing-a-ship-by-hand)).

Files are named by sequence number (`transfers/00001234-<id>.nbt`), so the directory lists in the order operations happened.

### Stages

MOVE keeps today's stages: PREPARED, DESTINATION_STAGED, SOURCE_CLEARED, PASSENGERS_MOVED, COMMITTED. BUILD uses PREPARED, PLACED, COMMITTED. RECORD is written once, COMMITTED. Rollback adds ROLLED_BACK.

Rollback of an unfinished MOVE or BUILD restores only that journal's `before` records, clears only the positions its hull occupied at the target, places the hull back at the source, restores entities and players, forces a flushed save and writes ROLLED_BACK. Nothing else in the store or the box is touched.

### Replay after a damaged copy

When the store is loaded from the older copy, every COMMITTED journal whose save number is greater than that copy's is applied in sequence order: its `after` records are written into the store. The world part is never touched, because the world already holds the result. ROLLED_BACK journals in that range apply their `before` records, which is what the rollback left in the world.

### When a journal is deleted

A finished journal (COMMITTED or ROLLED_BACK) is needed only while some kept copy of the store does not include it. Once the **older** copy's save number reaches the journal's save number, both copies include it and the file is deleted. In practice that is one successful save after the operation, so the directory holds the operations of roughly the last one or two autosave intervals.

Each deletion logs one line (sequence, kind, operation, ship, outcome), so the history is in the server log rather than on disk.

## Part 3: checking records against the world

Every ship's controller carries the ship's id in its block entity. It is a few bytes, written when the ship is registered or created and carried with the hull through every move. It is a cross-check, not ship data: the record in the store stays the only source of a ship's state.

When a chunk loads (Fabric `ServerChunkEvents.CHUNK_LOAD`), the check runs both ways. The ship index from review finding 7 makes both lookups cheap:

- every ship record whose controller lies in that chunk must find a controller at the recorded position, carrying its own id;
- every controller in that chunk that carries an id must have a record, and that record must place it here.

Existing ships get their id stamped on the first load after the upgrade, when record and controller agree.

A record or controller that fails the check is marked **needs attention**, with the reason and time. A controller with no record is listed on its own, with its position:

- the ship is busy for travel, recall, storing and scrapping (through [`TransferLocks`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferLocks.java));
- its owner and online operators are told;
- `/nullspace recovery` lists it, and an operator can **accept** it (clear the mark and keep the record) or **remove** the record (the blocks stay where they are).

Unloaded chunks are checked when they next load.

## Recovery that cannot finish

- **Any unrecoverable error sets aside the whole journal.** There is no partial recovery: if one entity will not restore, one block will not place or one record will not apply, the journal as a whole is set aside (below) with everything it covers locked, and the ship waits for an operator. A half-restored ship is never presented as recovered. Errors that can clear up on their own are retried instead; the journal stays pending and locked while it waits:

  | Recoverable: retried | Unrecoverable: the whole journal is set aside |
  | --- | --- |
  | The chunks' entities are still loading (up to the waiting limit) | Waiting for entities ran past the limit |
  | A dimension is missing, because a mod or datapack may come back | Unknown newer journal format, or a file that cannot be read |
  | A brief Windows file-sharing violation while writing | An entity whose saved data no longer loads, or that the level will not accept |
  | | A block or block entity that will not place |
  | | A record that will not apply, or a journal that contradicts itself |

  An error that is not known to be recoverable is treated as unrecoverable.
- **One failed journal does not stop the server.** Each journal is recovered in its own try block, so a failure contains that journal; the others are still recovered and the world runs.
- **Everything unresolved holds its locks.** Every journal that is pending, waiting or set aside registers its source and target areas and its ship ids with [`TransferLocks`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferLocks.java) until it is resolved. No one can build into, move or recall what a journal may still rewrite.
- **Waiting for entities.** A journal whose chunks' entities are still loading retries for up to 600 ticks (30 seconds), then is treated as stuck.
- **Missing dimension** (a mod or datapack removed): the journal stays pending and locked, and is retried on every start.
- **Unknown newer format, or unreadable:** the file moves to `transfers/quarantine/` with a reason file. If its header is readable, its areas stay locked.
- **Operators** get `/nullspace recovery`: `list`, `retry <journal>`, and `discard <journal>`, which accepts the world as it is, moves the file to quarantine and releases its locks. Operators are told about anything unresolved when they join.
- **Retries** run in the TRAVEL phase of [`ServerContext`](../../src/main/java/com/nullandkale/nullspace/core/ServerContext.java), not from a static tick hook.
- [Public alpha operations](../release/public-alpha.md) changes "never delete live transfer journals" to "use `/nullspace recovery`".

### Rescuing a ship by hand

A journal that failed or was quarantined still holds the ship: hull, entities and whole records. `/nullspace recovery` gives operators these actions on it:

| Action | Effect |
| --- | --- |
| `show <journal>` | The operation, stage and error; the ship's name, registration, owner, crew, home pad; source and target; what the hull and entities contain |
| `retry <journal>` | Run the rollback again, for example after the missing mod is back |
| `restore <journal> <pad or space>` | Place the hull at a registered, clear pad or a free space slot, restore its entities, write its `before` record at the new location and stamp the controller. Players who were aboard get arrival targets there |
| `export <journal>` | Write the hull as a vanilla structure file and the records as readable text under `nullspace/rescue/`, for building it back with a structure block |
| `discard <journal>` | Accept the world as it is, move the file to quarantine and release its locks |

`restore` follows the rules of an ordinary landing: the destination must be clear and is locked for the duration, and the restore is itself a journaled BUILD, so a crash during a rescue is recovered like any other operation.

## Migration

- On the first start with this version, `ships.nbt` is read as save number 0 and written as `ships-a.nbt`. The original is renamed `ships.nbt.pre-journals` and kept.
- Version 1 journals that are unfinished are still rolled back with today's rules (whole-store `before`, whole-box template), because that is the data they hold. Finished version 1 journals carry nothing replayable and are deleted after the first successful save.
- Older mod versions cannot read `ships-a.nbt`/`ships-b.nbt`, so downgrading past this version needs the kept `ships.nbt.pre-journals` and loses what changed since.

## Where the code changes

| Area | Change |
| --- | --- |
| [`WorldStore`](../../src/main/java/com/nullandkale/nullspace/core/save/WorldStore.java), [`AtomicFiles`](../../src/main/java/com/nullandkale/nullspace/core/save/AtomicFiles.java) | Two slots, save numbers, load selection, damaged-copy handling, save on world save |
| [`ShipRepository`](../../src/main/java/com/nullandkale/nullspace/core/save/ShipRepository.java) | Record-level difference between two snapshots; applying `before`/`after` records |
| New mixin in [`core/mixin`](../../src/main/java/com/nullandkale/nullspace/core/mixin) | Save the store at the end of `saveAllChunks` |
| [`ShipTransfer`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/ShipTransfer.java), [`TransferReplay`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferReplay.java) | New journal contents; hull of captured blocks only; rollback limited to the journal's records and positions |
| [`TransferRecovery`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferRecovery.java) | Per-journal containment, waiting limit, quarantine, replay, deletion, ServerContext phase |
| [`TransferLocks`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferLocks.java) | Locks for unresolved journals and ships needing attention |
| BUILD and RECORD call sites (table above) | Write journals |
| About 80 forced saves | Dirty flag or journal, decided per call site |
| New recovery command and chunk-load check | `/nullspace recovery`; needs-attention marks |
| [`PadRepository`](../../src/main/java/com/nullandkale/nullspace/core/save/PadRepository.java) | Previous copy and fallback at start |

## Testing

Tests that change global state or launch into the first free orbit slot run in their own environment, so their own batch (see the `test_environment` files under `src/gametest/resources`). The journal, integrity, pad registry and save tests do; so do the animals and the interrupted-transfer tests, which race for the first free slot with any other test launching at the same time.

- **Unit:** slot selection by save number, damaged newest copy, both copies damaged, record differences, replay order, the deletion rule, version 1 journals.
- **Native server:** a damaged newest copy is replayed back to the world on restart; a waiting journal locks its areas; a journal for a missing dimension stays locked and does not stop the server; quarantine and the recovery command; `/save-off` leaves the store untouched; a chunk-load mismatch marks the ship.
- **Crash matrix:** the existing launch and landing process-halt cases ([local verification](../development/local-verification.md)) on the new format, plus halts during a BUILD and between a COMMITTED journal and the next save, followed by a damaged-copy restart.
- **Rescue:** a quarantined journal is shown, retried, exported (hull as a structure file, records as text) and discarded through the commands. Restore tests cover destination rejection, interrupted rescue rollback, passenger uniqueness, offline arrival targets and a successful command-driven retry. Final integration is still required.

## Performance test

**Current scope decision:** the user stopped further performance measurements and tuning for this batch. Retain the implemented batching and existing evidence. The one-second pause target remains unverified and is not a release gate; the planned 100/300-ship measurements are deferred. Continue save/recovery correctness verification and fix demonstrated failures. The measurement plan below is retained for future work.

MOVE and BUILD force a flushed world save, and the store now saves with every world save. Whether that is affordable is measured, not assumed, by the [save and journal performance test](../development/performance-test.md). It runs on a generated world sized like a real server (about 40 ships and stations, scaled to 100 and 300), before Part 1 as a baseline and again after each part lands. It reports operations per second and the longest tick for MOVE, BUILD and RECORD operations.

The November 11 decision was to batch BUILD saves and target pauses under one second in the representative 100-ship workload. Ready jobs share a flushed save after a short collection window; another flushed world save can finish them sooner. Every job remains locked and keeps its rollback journal until the chunks and matching store copy are saved and its COMMITTED journal is durable. Failed barriers retry with those protections intact. First arrivals and operator restores may force the shared barrier immediately. This is implemented; further measurement is now deferred as stated above.

## Decisions

Agreed on November 10, 2026:

1. **Any unrecoverable error sets aside the whole journal.** No partial recovery; the journal is locked for an operator. Errors that can clear up on their own (entities still loading, a missing dimension, a brief file-sharing violation) are retried, and anything not known to be recoverable counts as unrecoverable.
2. **Controllers carry the ship id**, checked both ways on chunk load.
3. **Both copies unreadable: the server refuses to start**, naming the files and the backup to restore.
4. **RECORD scope:** everything needed to rescue a ship by hand, with whole ship records in every journal and the rescue actions above.
5. **BUILD save cost is measured** by the performance test before it is decided.
