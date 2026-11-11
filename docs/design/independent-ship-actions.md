# Ship transfers and independent ship actions

Launching, landing, jumping, docking and recalling all move a ship, and several players do them at once. This document explains how a ship physically moves between places, how a crash in the middle is survived, and how the locks are arranged so that one player's loading screen never stops anyone else. The code is [`gameplay/travel`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel) ([`TravelService`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TravelService.java), [`ShipTransfer`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/ShipTransfer.java), [`TransferLocks`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferLocks.java), [`TransferRecovery`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferRecovery.java), [`TransferReplay`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferReplay.java)).

## Why ships are physically copied

A ship is real blocks. The surface is the Overworld or a planet dimension, and space is one shared dimension (`nullspace:space`). Leaving a pad means the blocks, block entities, passengers and items inside the hull are copied to a place in the destination dimension and cleared from the source. There is no portal or fake instance, so every structural rule the player relies on (sealed rooms, machines, inventories) is automatically preserved. The cost is that a move is a world edit of thousands of blocks, and that must never be left half done.

## The shape of a move

A launch is a request followed by a transfer. They are deliberately separate.

**Request** ([`TravelService.request`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TravelService.java)) runs the cheap, reversible checks on the player's click and then reserves things:

- the ship is not cruising on an FTL journey, is not docked or building a station, and has working systems (when launching from the surface);
- the pilot and crew are not already travelling or boarding, and every player inside the hull is in a sealed, breathable cabin. Players tethered outside on EVA must be recalled first;
- the source area is free, and a destination is reserved: for launch, the first free space slot; for landing, the chosen registered pad, which must be on the planet the ship has jumped to.

It then adds a `Pending` record and tells each crew client to show the loading screen.

**Transfer** ([`ShipTransfer.run`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/ShipTransfer.java)) happens on a later tick, after every crew client has acknowledged that it is ready (or after 200 ticks, when travel is cancelled instead). It re-inspects the ship, because the player may have changed it while the screen loaded, then runs the phases in order:

1. Check the source, destination, passengers and fuel.
2. Capture the hull's blocks into a template and save every non-player passenger.
3. Write the journal and checkpoint it as `PREPARED`.
4. Place the ship at the destination: `DESTINATION_STAGED`.
5. Clear the source: `SOURCE_CLEARED`.
6. Move the players: `PASSENGERS_MOVED`.
7. Save the world and checkpoint `COMMITTED`.

Everything from the capture to the commit is one server task. Machines, mobs and menus cannot tick between journal stages.

After the move the clients report that they have arrived in the new dimension. A client that has not arrived after 200 ticks is disconnected with an instruction to reconnect. That is safer than leaving the player somewhere the server is unsure about, because the journal stores where they belong.

## The transfer journal

Before anything is cleared, the server writes a file under the world's `nullspace/transfers/` directory. It holds the template of the hull, the passengers, the ship metadata *before* the move, the metadata *after*, and a stage name. Every checkpoint rewrites the file.

On server start, and after any failure during a move, [`TransferRecovery.recover`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferRecovery.java) reads every journal that is not `COMMITTED` or `ROLLED_BACK` and **rolls it back**: it restores the saved ship metadata, clears the destination copy, puts the template back at the source, restores each passenger and entity, and records where each player should reappear. The ship is therefore either fully at the destination (`COMMITTED`) or fully where it started. A crash in between cannot duplicate or lose it. Rolling back rather than forward is deliberate: it needs only the data saved in the journal and no judgement about a half-finished world.

Players who were mid-move are recorded as arrival targets and teleported when they next join or when recovery finishes. Offline passengers are placed in a validated cabin position, never at an offset that has become empty space in the departing ship.

Tests halt the real server process at each stage (`launch/landing and shipyard process-halt matrices`, see [local verification](../development/local-verification.md)) and check the ship, its passengers and the player inventories after restart.

## Who is locked, and by what

The locks follow the objects involved, not the server:

| Lock | What it protects | Held by |
| --- | --- | --- |
| Ship and crew | A ship and its players do one travel action at a time | The pending request, boarding, or recall job |
| Space slot | Launches reserve distinct allocations in space (4096 blocks apart) before either ship moves | The first free slot at request time, counting pending targets and existing ships |
| Landing pad | Two ships cannot claim the same pad column | The pending request, or a recall job for that pad |
| Docking berth | A berth is reserved for the docking ship; another ship can use a different berth | [`DockingService`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingService.java) |
| Boarding | A vessel cannot move until its passenger transfer finishes | [`Boarding`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/Boarding.java) |
| Area | A hull's volume cannot be built into or moved while a transfer holds it | [`TransferLocks.areaBusy`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TransferLocks.java) |

`TransferLocks` is the single place that answers "is this ship, player or area busy". It combines the physical transfers, recall jobs, navigation requests and boarding. World edits made by the transfer itself bypass the area lock.

Preparation, arrival acknowledgement, cancellation and timeout belong to one request token. A token from a different ship cannot release a lock or complete another ship's journey, so a stale click or a late packet is harmless.

### What this makes possible

- Two players can launch at the same moment. They get different space slots and neither waits for the other.
- A pilot waiting on a loading screen does not stop a recall, a station build, or a landing somewhere else.
- Cancelling one recall does not cancel another player's.
- A failed transfer rolls back only its own ship. A ship that committed a moment earlier keeps its new location, because each commit captures the latest saved metadata.

The one aggregate query that remains is "is the server busy", used only by administrator regeneration of all stations, which affects the whole station population.

## Failure behavior the player sees

| Situation | Result |
| --- | --- |
| A block changed so the hull no longer validates | Travel is cancelled with the reason, and the offending block is highlighted |
| The pilot left the cabin or disconnected | Travel is cancelled |
| Client readiness does not arrive within 200 ticks | Travel is cancelled |
| An exception during the move | The journal is rolled back and the pilot is told the move failed |
| A crew client does not confirm arrival | That player is disconnected and rejoins at the saved arrival target |

Expected refusals return a result and never reach the exception path, so they do not write stack traces to the log.

## Related

- [Ship ownership, storage and recall](ship-recall.md) uses the same journal for uncrewed moves.
- [FTL journeys](ftl-journeys.md) cover movement within space, which does not use this mechanism.
- [Space encounters and docking](space-encounters-and-docking.md) cover the docking and boarding locks.
