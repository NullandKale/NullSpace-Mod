# Docking, boarding and encounters

Ships and stations are real structures that are stored far apart, so putting two of them side by side, joining them, and letting a player walk from one to the other takes deliberate machinery. This document explains how automatic docking and boarding work and why they are built this way, and how encounters (places worth docking with) come about. The player's view is in [stations](stations.md), the access and construction rules are in [the station reference](station-reference.md), and the places themselves are described in [orbital population](space-world-generation.md) and [FTL journeys](ftl-journeys.md). The code is [`gameplay/docking`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking) ([`DockingService`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingService.java), [`DockingManeuver`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingManeuver.java), [`DockJoin`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockJoin.java), [`DockingPorts`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingPorts.java), [`DockingTunnel`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingTunnel.java), [`Boarding`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/Boarding.java), [`StationConstruction`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/StationConstruction.java)) and [`model/docking`](../../src/main/java/com/nullandkale/nullspace/model/docking).

## The play loop

Scan or browse the destination list, choose a port, press **Dock**, and the ship does the rest. The player goes through the open door and explores a real, editable interior with real inventories, air and damaged machinery. They come back, **Undock**, and carry on. Selecting a place exposes what is needed to decide: its name, its ports and their status. A station or derelict is a persistent object, so visiting it twice returns to the same place with the same changes.

**Docking is fully automatic.** The player never flies into position, matches speed or aligns ports. This was an early and firm decision. Hand docking would make a core activity depend on piloting skill and would be a frustrating way to reach a safe room.

## Two hulls, one walk

Every ship and station has its own area of the space dimension, 4,096 blocks from the next, and flies by its simulated pose while its blocks stay put (see [ship navigation](ship-navigation.md)). Docking brings the simulated poses together, port to port, and then **moves the visitor's blocks beside its berth**, so the crew walk from one hull into the other ([`DockJoin`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockJoin.java)).

- **A docking arm, not a bare collar.** The game finds which vessel a block belongs to by each vessel's box (a ship's 32-block area, a station's area rounded up to whole sections), and two boxes must never overlap: the host's air, rebuilds, spawns and scrapping all work over its box. A berth's door is often well inside the host's box (a hull rarely fills its area), so the visitor clamps just outside it and a lit, floored, glass-walled arm runs from door to door. Half of the generated stations' berths need no arm at all, and most of the rest need one to about fifteen blocks. A docked visitor's own box fits its hull exactly, so it starts at its hull.
- **The same gap in the simulation.** The approach ends at the arm's length, so what the windows and other ships show matches the blocks. The berth may face any side; the visitor is turned a quarter turn as needed so its door faces the berth's door. A docking pose already matches the berth's orientation up to that turn, so afterwards the two vessels share an orientation, and their blocks sit exactly as the simulation places them.
- **One move at capture, one at departure.** At capture the visitor's hull, with everyone in it, moves to the berth behind a short fade, as a journaled ship move like a launch. Before it backs away it moves to a free area of its own again, the same way. Nobody on the host notices either move. The clamp and release sounds wait for these moves, and a course to the station counts as arrived once the hull has joined the berth, when the dock door can be walked through.
- **Walking across makes you crew.** Stepping from one hull into the other moves the player's crew record, as boarding through a door always did: their air, tether and return journey follow the vessel they stand in, and the visitor cannot undock while its crew are aboard the host.
- **Air stays separate.** A clamped dock's doors seal the two vessels from each other even when open, and the arm carries its own air for the crossing. The vessels do **not** merge their air, power, inventories or redstone, and connecting does not refill cabin air.
- **Drawn as blocks.** A vessel joined to the player's own is drawn from its real blocks, not as a picture over them, so the station outside a docked ship's window is the station itself.

A berth whose arm would be longer than 31 blocks (a door set very deep in a station's area), or whose spot beside it is taken by another vessel's area, is docked without joining, so a deep berth on an existing station stays usable. So is a dock whose hulls cannot be joined after capture (three tries, for example when the spot became blocked). Such a dock stays clamped apart, with short collars on each hull, and the crew board through the door as described under [Boarding](#boarding). Docks from older saves and station construction docks work the same way.

## Ports

A port is a framed standard door directly above a **Docking Base**, facing outward, with a solid frame, two blocks of walking room inside and a clear approach outside. Wooden and iron doors both work, and no second door, chamber or pump is needed. The base handles mating and door control, and a berth in a station is built the same way. Edits update readiness at once, and the controller's Inspect reports missing doors, broken frames, blocked anchors and missing power. Loading an older save replaces removed hardware with solid iron blocks (see [ship systems](ship-systems-redesign.md)).

Every port has an identity. A connection names both ships and both ports, so a clamped joint is saved exactly. A server reservation stops two ships claiming one berth.

## Requesting a dock

The player chooses a port as the destination and presses **Dock** ([`DockingManeuver.readiness`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingManeuver.java) decides whether that is allowed). The ship must not be cruising on an FTL journey, docked, building or travelling. It needs systems enabled, fuel, controls power and propulsion, and every crew member must be in a sealed cabin with enough air (25% oxygen) before the maneuver starts, with a message naming the person and the problem if not.

The server then validates the ships, ports, region, consent, equipment, power and route, and reserves the berth. Stations accept by their access setting. **Another player's ship must accept**: the request waits (`WAITING_CONSENT`) until its crew press Accept or Decline at their controller, or the request is cancelled.

## Approach and capture

The autopilot owns the whole maneuver: brake, travel to a clear stand-off point, rotate to match the port, approach slowly and clamp.

- **Route.** [`DockingMotion.route`](../../src/main/java/com/nullandkale/nullspace/model/docking/DockingMotion.java) searches in 64-block steps on a grid around the ship (A* with a budget of 4,096 expansions) for a path whose every segment keeps clear of bodies, asteroid fields and other ships by a margin (a clearance radius of 29 blocks). If no path exists, the dock is refused with "the berth is obstructed", before the ship moves.
- **Motion.** Each tick the pose steps toward the next waypoint at a bounded speed and turn rate (`DockingMotion.step`). Manual thrust and steering are suspended during docking, and **Cancel** brakes the ship, releases an unclamped reservation and returns control only once it has stopped.
- **Collision.** Normal collision avoidance applies until a valid port reserves a corridor. During the approach the real hull occupancy is checked through translation and rotation, and only the intended mating faces are exempt. (After a recovery from an overlap, [`DockingManeuver`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/DockingManeuver.java) lets an existing overlap shrink but never allows a voxel into a new obstruction.)
- **Capture.** On arrival the ship is `CLAMPED`: the doors seal and the joint is saved, the hull joins the berth (a crew member out on EVA is waited for first), and the arm is built. The mechanical clamp stays latched through a blackout.

The states a connection moves through are `REQUESTED` and `WAITING_CONSENT`, then `APPROACHING`, `CLAMPED`, `SEPARATING` (a joined hull moving back to an area of its own), `UNDOCKING`, and `EMERGENCY_BACKOFF` for an emergency release. A reservation expires before clamping, and a clamped connection persists across saves. Power loss, damage or an obstruction stops a maneuver safely with an explicit reason, and progress (Approaching, Aligning, Connecting) is shown while it runs.

## Boarding

At a joined dock the player opens the door, walks the arm, opens the far door and walks in; the host's door opens only for players its access allows. The rest of this section is the door transfer of a dock whose hulls stand apart (an older dock, a construction dock, or one that could not be joined).

To cross, the player stands in the source port and opens the door; the transfer then ([`Boarding`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking/Boarding.java)):

1. Checks again that neither vessel is in transit or under construction, that the destination allows the player, and that they are in the chamber.
2. Picks a destination standing position: the other port's anchor, or for a ship with no matching port the first clear standing cell, preferring one with breathable air.
3. Closes both ports' doors and saves a small boarding record keyed to the player and a transfer token.
4. Waits for the client to be ready (up to 600 ticks), moves the player once, and waits for arrival (up to 200 ticks).

The record is validated before commit and again immediately before movement, using the same readiness tokens and reconnect handling as other transfers, and never the whole-hull snapshot. The transfer moves the player through Minecraft's normal entity path and does not copy their inventory. It transforms the entrance offset and look direction into the destination ship's frame. A player who disconnects midway comes back at a saved target. Offline crew keep stable return anchors, so destroying a port never strands a login at an invalid block.

**An unsafe destination** needs a suit with usable oxygen. A damaged or unpowered chamber can never declare itself pressurized, and redstone-opened hatches and breaches invalidate readiness.

Ordinary undocking refuses while boarders or crew remain on the other vessel. There is no "leave crew behind" action.

## Undocking and emergencies

**Undock** needs controls, engine power and fuel on the *departing* ship, the doors close and the tunnel retracts first, and a passenger in the tunnel blocks it. A joined hull then moves to an area of its own, with whoever is aboard, before it backs away; if that move fails the dock is clamped again with the reason. The other ship may be dead. **Emergency departure** exists for a damaged airlock: it closes surviving doors and backs away, waits for propulsion and a clear corridor, and leaves real vacuum consequences where seals broke. If a port is destroyed, the surviving hatches are asked to close and the docking relation is cleared.

## Encounters

Encounters are the reasons to dock. Two sources make them, both described elsewhere:

- **Seeded regional sites**: planned orbital stations and derelicts, derived from the world seed and region, saved once and built incrementally ([orbital population](space-world-generation.md)).
- **Journey signals**: a signal during an FTL trip resolves into a vessel, a growing station, a freighter, a salvage ship or drifting cargo, which can be docked with and salvaged ([FTL journeys](ftl-journeys.md)).

Both follow the same rules. Repeating a scan reveals the same objects, and closing a screen, reconnecting or jumping away and back does not reroll loot or create another freighter. Once built, blocks and inventories are authoritative, and updating a template never repairs or refills an existing site. Different players find the same objects. Ships and stations are persistent objects with stable ids, their own storage slots and simulated poses. A station is anchored and needs no engines, pad or launch inspection, and launch and landing menus reject stationary objects explicitly. A wreck stays discoverable after losing its controller or seal, and a derelict that nobody visits can be claimed (see [ship ownership](ship-recall.md)).

Scanners show contacts: a *local* scanner shows those in the current region, and a *long-range* one (from a System Upgrade) shows routes. Contact packets carry public metadata, not interiors, and discovery belongs to the ship's shared crew record. A scanner that loses power leaves labeled last-known information and stops updating. Menus, scans and script polling never force-load a hull or advance any clock. Reaching a distant station is a jump to its region, and arrival puts the ship at a checked stand-off, from which **Dock** starts the local approach.

## Not built

- NPC traffic, trading, dialogue or hostile AI at encounters.
- Utility sharing and gas transfer between docked vessels.
- Building onto a docked visitor beyond its hull's box: its area fits the hull while it is docked.
- Placing an offline player who logged out in a docking arm somewhere safe once the arm retracts.
- Berth queues and holding points for crowded harbors (see [manufacturers and harbors](procedural-ships.md#harbors-not-built)).
- A server-configured scheduler of occasional signals. Today's signals come from journeys.

## Where this is tested

[`DockingPresetGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/docking/DockingPresetGameTest.java) docks every delivered hull for real: it joins the berth turned to face it and just outside the station's area, with matching simulated and physical places, an open arm, crew records that follow the player across, and a separation (or emergency departure) to an area of its own before it backs away. [`GeneratedStationGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/station/GeneratedStationGameTest.java) joins a generated station's berth, and the station graphical tests walk the arm with real input. [`DockingRecoveryGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/docking/DockingRecoveryGameTest.java), [`StationGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/station/StationGameTest.java) and [`HullScan`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/HullScan.java)-based native tests cover port construction (all facings, missing or misplaced doors), reservation races, replayed packets, cancel, blackout, damage, reconnect, swept clearance, and recovery from overlaps. Boarding is tested with two real clients on different hulls: inventory kept exactly once, orientation, and a disconnect at each stage with recovery around the commit. The graphical suite runs the complete round trip with real clicks. [`BoardingViewTest`](../../src/test/java/com/nullandkale/nullspace/model/docking/BoardingViewTest.java) covers the look conversion.
