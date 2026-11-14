# Station construction and docking reference

The [station guide](stations.md) covers everyday play and the [station pieces document](station-modules-and-systems.md) covers the design. This reference lists the rules, limits and failure behavior of the current implementation, for builders, server operators and anyone changing the code. It covers [`gameplay/station`](../../src/main/java/com/nullandkale/nullspace/gameplay/station) and the parts of [`gameplay/docking`](../../src/main/java/com/nullandkale/nullspace/gameplay/docking) that stations rely on.

## Access

Access is decided in [`StationAccess`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationAccess.java). Three roles apply to a station:

| Role | Who | May |
| --- | --- | --- |
| **Owner** | The player who started it, or any server operator | Everything, plus rename, invite and remove builders, change public or private |
| **Builder** | Invited by the owner (the invited player must be online at the time) | Build, edit blocks, use storage and the core |
| **Visitor** | Anyone, if the station is public. Everyone, if it is a derelict | Operate internal doors, use dock controls, the Station Core, a crafting table and a stonecutter. Cannot edit blocks, open protected storage or change berths |

A **returning visitor** (a player aboard whose record says which ship they came from) keeps the door access needed to get back, even if public access is switched off. A station under construction cannot be edited or used except through the build itself.

Derelicts from [encounters](ftl-journeys.md) are the exception to protection: visitors may open storage and change blocks, because salvage is the point. A derelict becomes protected property when a player claims it (see [ship ownership](ship-recall.md)).

Doors operated by docking cannot be toggled by hand. A door under a redstone signal cannot be overridden remotely, and the player is told so.

## Visiting a station

1. At the controller open **Destination > Ports**. Select a port as the destination. The list updates as stations finish loading and berths become free.
2. Return to the controller and press **Dock**. Autopilot navigates, aligns, approaches and clamps. Closing the terminal does not cancel it.
3. Open the dock door and walk through. A short transition boards the station with your inventory.
4. Return through the same port. When all crew are back, **Undock automatically** at the ship's controller or port closes and checks the doors, backs away and releases the berth.

**Cancel** stops an approach before capture. A port belonging to another player's ship needs that ship to accept the request at its controller or port. The controller then shows **Accept** and **Decline**. Stations accept according to their access setting. Power loss or an obstructed route stops a maneuver safely. A ship cannot thrust, jump or land while it is connected.

**Emergency departure** is available from the visiting ship when an airlock is damaged. Return the crew first. It tries to close the surviving doors and then backs away. It waits if propulsion has no power or the corridor is obstructed, and a damaged seal keeps its real vacuum consequences.

### The tunnel and the joint

After a compact dock captures, a lit tunnel with a floor and glass walls bridges the gap between the hulls. Its collars have a saved recovery plan and retract before departure, preserving the hulls' own blocks. They are excluded from ship inspection and from cargo capture. A passenger standing in a collar prevents retraction.

Boarding and returning across an established joint need power from neither vessel: the transfer holds passengers on an interior floor, and low cabin oxygen never locks the route. Oxygen simulation, and the power needed to move a ship, are unchanged. Docking does not merge the vessels' air or power.

An exterior door that is not part of a mated joint opens into vacuum, so wear a suit when using it outside a sealed connection.

## Ports on ships

The **Docking Base directly below any standard door**, facing outward, makes a port. The door needs its full frame, normal walking room inside and a clear exterior approach. The Docking Base decides which side faces space, the door itself may face either way along that axis, and a base turned sideways relative to the doorway is invalid. The base connects through the hull to the controller and the power bus.

Older two-door airlocks with top controllers and connectors remain supported and keep their saved construction. Updating never rebuilds a player's ship.

## How construction runs

Station building is a saved, replayable block plan, never a live paste:

1. **Reserve and pay.** The build reserves a clear region, then takes the kit from the player's inventory with a *receipt*: a token stored together with the saved inventory. A redeemed kit cannot create a second station, and if the server stops between taking the kit and the save, startup settles the receipt (a build that was not paid for is removed or rolled back).
2. **Queue.** The plan is saved on the station as `stationBuild`. The station is unavailable to docking and editing while it exists.
3. **Place.** [`StationBuilds.tickBuilds`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationBuilds.java) places up to 384 blocks per tick for the first station with work, and player work goes first, ahead of world-generated places. It keeps the plan's chunks loaded, and restarting an interrupted job replays the same plan.
4. **Commission.** When placement is complete, fuel is provisioned in each generator, battery charge is set once, supplies are stocked once, air and power requests are set, and the world is saved *before* the station is published as ready, so a crash cannot expose a half-built station.

Existing blocks, containers and occupants stop a placement. A piece never overwrites a player's construction: the message names the obstructing block and coordinates, and the player is asked to move any occupant first.

### Kits and limits

- One piece costs one **Station Module Kit** in Survival. **Build full station** needs an empty station and consumes four kits for the starter or 28 for the ring. Creative mode consumes none.
- A piece must adjoin an existing piece and meet an open doorway. The first goes on tile 0,0. A tile beyond the current envelope needs the area expanded first.
- Each owner may have **32 stations**. Generated places do not count. Each station holds up to 64 piece tiles.
- Each Station Core item starts exactly one station: its receipt is its station id, so redeeming the same core twice is refused.

### Expansion

**Expand to 128 × 128** needs the station ready and not travelling, no other registered vessel overlapping the new area, all of that area free of blocks, and no entities in the new part. It uses no kits and changes no existing block. It conserves existing gas and fuel. New rooms fill from their own pumps. The station tether measures distance outside the build area, so distant interior rooms do not trigger a return to the core.

## Station systems

- Stations use the shared power bus, finite atmosphere, equipment diagnostics, computers and camera feeds. They need no engines, ship controller, surface pad or launch inspection.
- Closing a bulkhead blocks gas without disconnecting the surrounding power bus. Real blocks and room pressure determine safety, and the doorway graph only provides routes.
- Rendering supports up to **twelve nearby vessels** within a total budget of **sixty-four 32-block sections**, so one full station and eight visiting ships fit. Section boundaries never clip collision, boarding or gas flow.
- The owner can rename the station, invite an online builder by name and switch docking access. Public docking grants no building or storage rights.
- A station keeps its identity, hull and contents if its core breaks. An authorized builder can place and use a replacement core.
- The first powered port turns the temporary construction connection into an ordinary dock. Until then, **Return to construction ship** is available at the core.

Legacy station commands remain for development fixtures. Normal navigation only discovers existing stations.

## Explicit regeneration

Normal visits never rebuild a station. A regeneration is requested by writing a world-local file, `nullspace/station-regeneration-request.json`, with these fields:

- a version (currently 1) and a request id;
- the ids of 1 to 32 stations;
- the metadata hash of each station as it was when the request was made, so a station that changed since is refused;
- a verified backup.

On the next server start ([`StationRegeneration.applyPending`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationRegeneration.java)), the request is validated and turned into ordinary saved construction jobs, and the request is archived. Replaying a completed request does not rebuild anything again. If the request is invalid, the server logs it and keeps both the request and the backup.

Regeneration replaces the selected stations' blocks, containers and equipment with the *current* template. It keeps the station's identity, name, access, ownership, region and position, and keeps every other ship record. Crew and visiting ships must be returned before it is queued. A player who enters the rebuild area pauses the clearing until they leave, and an interrupted rebuild resumes through the same build journal.

Compact Waypoints regenerate with two current Docking Bases under ordinary doors. Expanded ring stations keep their larger envelope and regenerate with eight berths.

## Where this is tested

[`StationGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/station/StationGameTest.java), [`StationExpansionGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/station/StationExpansionGameTest.java), [`StationRegenerationGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/station/StationRegenerationGameTest.java) and [`DockingRecoveryGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/docking/DockingRecoveryGameTest.java) cover building, receipts, expansion, regeneration and complete docking round trips (both on a live server and in the graphical suite, which captures the station interior and dock controls during a full boarding round trip). [`StationExpansionTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/stations/StationExpansionTest.java) checks the envelope arithmetic.
