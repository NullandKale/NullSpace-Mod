# Ship/space review decisions - 2026-10-23

The user resolved the review. The requested fixes and local verification are complete for the scenarios listed here; accepted limitations remain explicit. The [main plan](ship-and-space-plan.md) now includes the accepted behavior. "Remove" means implement the missing planned behavior, not remove the feature. No further approval is needed for these choices.

## Decisions

| ID | User decision | Result |
| --- | --- | --- |
| D01 | Keep synchronous bounded work | Scans, block mutation, and save boundaries can pause a tick. Incremental scheduling is deferred. |
| D02 | Keep pressure-based movement | Sealed cabins have gravity; exposed cabins/EVA activate complete-suit flight. This is the desired gameplay. |
| D03 | Keep current door pressure rules | Validated closed framed doors seal both whole cells. Unsupported frames remain leaks/rejections. |
| D04 | Keep all doors closed for travel | Interior and exterior captured doors must close before launch/landing. |
| D05 | Complete stairs/elevation | Collision-based walking checks handle half-block steps, stairs, one-block rises, body clearance, and headroom. Pressure remains a separate connectivity test. |
| D06 | Keep conservative entity boundary | Any entity intersecting the tight ship box is checked; an exterior entity in that box can veto travel. |
| D07 | Complete ship sensors/controls | Inspect shares cargo/occupant validation with transfer, reports engine/compartment/air-source information, and highlights failures/leak paths. |
| D08 | Add pad state information | READY, OCCUPIED, UNAVAILABLE, UNKNOWN are displayed without generating/loading remote terrain. Landing still revalidates. |
| D09 | Use stable destination IDs | Typed terminal selections bind a displayed pad name to its issued registry ID. Renaming cannot redirect that selection. |
| D10 | Add authenticated sessions and local multiplayer tests | Typed packets use server-owned player/block/dimension sessions. Opt-in tests use two graphical clients on loopback. |
| D11 | Keep client readiness heuristic | Ten consecutive ticks with the player chunk in the destination dimension; no full-footprint acknowledgment guarantee. |
| B01 | Accept rare logout facing reset | A non-pilot passenger disconnecting during preparation of the first launch can lose yaw/pitch. Ordinary online travel preserves facing. |
| B02 | Accept disconnected space debris | A disconnected block above the connected hull can remain in the space allocation on landing. It is omitted from capture; automatic cleanup is not promised. |
| G01 | Complete missing verification | Add real process crash/restart, dedicated two-client travel/reconnect, readiness timeout, and targeted regression checks. Exact evidence/limits are in [local verification](../development/local-verification.md). |

## D05: stairs and suffocation

`CabinPaths` uses actual collision shapes for a standing player and half-block elevation nodes. A usable stair route to an elevated door succeeds; an obstructed route without headroom fails. Stairs/slabs remain non-sealing fittings. Ladders, swimming, crouch-only passages, and a general movement simulator are outside this initial walking-path contract.

Suffocation, compartment isolation, and dangerous door operation are core gameplay. Current oxygen is binary and unlimited in sealed rooms. FTL-style oxygen levels, reserves, and replenishment are intended later work. Door state already changes connectivity, including ordinary redstone-controlled openings; active contraptions remain unsupported during transfer. No finite oxygen economy is claimed complete here.

Evidence: [CabinPaths](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/CabinPaths.java), [ShipScan](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/HullScan.java), [SpaceSurvival](../../src/main/java/com/nullandkale/nullspace/gameplay/crew/LifeSupport.java).

## D07-D08: sensors are gameplay

Ship inspection validates the structure and every intersecting occupant/cargo entity before reporting readiness. Transfer repeats that validation against current state. Success reports block/engine counts, sealed compartments/cells, and controller source status without disclosing contained hostile mobs. Failure messages include coordinates; a bounded five-second particle highlight marks the error or controller-room escape path.

Pad states are observations, not reservations. READY means loaded and geometrically valid with clear sky columns. OCCUPIED means a valid pad with a blocked column. UNAVAILABLE means a missing dimension/controller or changed build. UNKNOWN means the necessary terrain is unloaded. Listing must not load that terrain, including when a damaged border makes discovery search beyond the registered footprint. Actual landing validates the destination again.

Evidence: [ShipOccupants](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/Occupants.java), [ShipDiagnostics](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/ShipDiagnostics.java), [PadRegistry](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/PadRegistry.java).

## D09-D10: identity and multiplayer boundaries

A name is editable display text. If pad A is renamed and pad B later takes its old name, an old terminal selection must still refer to A's ID. A missing/changed A must fail validation rather than silently land on B. Session aliases retain the issued identity; closing/reopening obtains a fresh catalog. Registry UUIDs already existed; carrying that identity through the submitted action is the change.

Sessions expire after 1,200 server ticks of inactivity, bind player UUID/block position/dimension/controller type, and validate range, block presence, interaction permission, action type, and travel state. Actions and close packets are typed. A different player's token, stale token, controller replacement, out-of-range request, or dimension change cannot authorize interaction. Controls remain server-authoritative. Full computer/VM stream transport is future work; these packets serve controller applications.

The opt-in multiplayer harness runs two independent graphical client processes against a loopback-only dedicated server hosted by the Fabric harness in the pilot process. It exercises real packet actions, independent sessions, invalid controller actions, launch, offline landing, and rejoin with preserved cargo/inventories. It is not added to CI or normal build/check.

Evidence: [ComputerTerminal](../../src/main/java/com/nullandkale/nullspace/ui/terminal/ComputerTerminal.java), [LocalMultiplayerTest](../../src/gametest/java/com/nullandkale/nullspace/LocalMultiplayerTest.java), [scripts/test/multiplayer-test.ps1](../../scripts/test/multiplayer-test.ps1).

## B01: no seating or forced facing requirement

Online players retain yaw/pitch across normal transfer; mobs retain their serialized orientation. The engine translates the ship and its contents without rotating its blocks. Traversal does not require seats or a forced view direction.

The accepted defect concerns one narrow case: a non-pilot passenger disconnects during first-launch preparation before the ship has a persisted crew entry. That preparation snapshot currently retains position but not yaw/pitch. Rejoining can face 0/0. Already-recorded offline crew use a separate path that retains angles. A pilot disconnect cancels preparation. This exception is not a general ship orientation rule.

Evidence: [TravelService.Pending / transfer](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TravelService.java).

## G01: evidence and limits

The local crash harness halts the actual JVM at each durable journal stage and during temporary gzip-file writes, then launches a new JVM against the same disposable save. It verifies exactly one authoritative ship, supported entity UUIDs/cargo, and reloaded player inventory/facing. Recovery is also run twice to check idempotence. This extends the previous in-process exception injection.

Readiness regression checks cover a missing/wrong preparation acknowledgment leaving the original untouched and a missing/wrong arrival acknowledgment disconnecting the client while retaining committed ownership. Two real clients cover offline landing and reconnect. Stair/headroom, diagnostics, pad states, and session identity regressions accompany the fixes. A native underwater-to-vacuum test also exposed and fixed a stale air counter that could restore an old dry-cabin reserve after leaving water.

See [local-verification.md](../development/local-verification.md) for recorded runs. This matrix does not prove every hardware power-loss pattern, door geometry, network interruption timing, water configuration, or large-server stress case. Accepted D01 and D11 remain limitations. Orbit, finite oxygen economy, resource progression, computer VM/OS, ropes, and pad ownership remain deferred.
