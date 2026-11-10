# Station construction and docking reference

The short [station guide](stations.md) covers everyday play. This reference preserves the detailed instructions for the current implementation.

For the implemented hardware and UI simplification, see the short [ship systems redesign](ship-systems-redesign.md). The instructions below describe the currently implemented system.

Stations, the completed Waypoint example, room kits, doorway graphs and automatic docking are implemented. Stations begin with a 32 x 32 x 32 build area and can expand at their core to 128 x 32 x 128. Four room tiles remain visible at a time; a section selector exposes all 64 tiles. The full-size ring uses 28 connected rooms and eight widely spaced exterior berths. Orbital regions populate automatically with persistent Waypoint and Manufacturer Exchange stations; random encounter scheduling remains future work.

## Visit Waypoint Station

1. Launch a powered ship with a valid docking airlock. Newly delivered Ship Beacon presets include one; existing saved ships retain their construction.
2. At the controller open **Destination > Ports**. Entering space automatically populates the region with its seeded stations; no menu action creates them.
3. Select a Waypoint port as **Destination**, return to the controller and press **Dock**. Autopilot navigates, aligns, approaches and clamps. Closing the terminal does not cancel it. The destination list updates automatically as stations finish loading and berths become available.
4. After capture, open the ordinary dock door and walk through. A short loading transition boards the station with your inventory.
5. Return through that same station port. Once all crew are back, select **Undock automatically** at the ship's ship controller or port. It closes and checks the airlocks, backs away and releases the berth.

Waypoint is a ring of four generated tiles (an entrance, a workshop, a bedroom and a power room, each two stories) joined by offset doors, two on each story of each shared edge; see the [station generator](ship-generator.md#stations). It has two opposing exterior berths, colored room borders, ceiling lights, finite room air, generators and batteries. Starter contents and resources initialize once; visiting never repairs or refills it. Public visitors may use its door controls and crafting table; protected storage and construction require builder access.

The graphical integration test captures the [station interior](../images/waypoint-station-interior.png) and [dock controls](../images/station-dock-controls.png) during a complete boarding round trip. The verification receipt records test counts, coverage limits and the packaged artifact hash.

**Cancel** stops an approach before capture. Another player's ship must stop and explicitly accept the incoming request at its controller or selected port. The controller shows **Accept** and **Decline** when a request is waiting. Stations accept according to their access setting. Power loss or an obstructed route stops the maneuver safely. Ships cannot thrust, jump or land while connected.

## Build an airlock into an existing ship

Place one **Docking Base directly below any standard door**, facing outward. The door needs its full frame, normal walking room inside, and a clear exterior approach. Wood and iron both work; the base operates an iron door without extra buttons. The base connects through the hull to the controller and power bus.

The Docking Base determines which side faces space. The door itself may face either way along that same axis; its placement direction does not change the approach or the return route. A base turned sideways relative to the doorway is invalid.

After automatic docking, the mated joint seals the open doorway. Open the door normally and walk through to board; the transition preserves your inventory. Undocking closes both ports first. Cabin air remains finite. An undocked exterior door opens into vacuum, so wear a suit when using it outside a sealed connection.

A captured compact joint extends a lit tunnel with a floor and glass walls across the gap. Its collars have a saved recovery plan and retract before departure, preserving existing hull blocks. They are excluded from ship inspection and cargo capture. A passenger standing in a collar prevents retraction. Boarding and returning across the established joint do not require power from either vessel; the transfer holds passengers on an interior floor, and low cabin oxygen does not lock the route. Oxygen simulation and the power needed to move the ship remain unchanged.

Existing two-door airlocks with top controllers and connectors remain supported. They keep their saved construction and advanced door/boarding controls; installing the update never rebuilds a player's ship.

## Start a station directly in space

Carry a crafted **Station Core**, a complete space suit and **Station Module Kits** aboard the ship. Choose **Deploy station** at the controller. The core deploys a small platform and reserves a nearby clear site; autopilot connects to its temporary construction berth. Open the dock door and walk through, wearing the suit.

The construction tether consumes real oxygen from the connected ship. It supplies the suit rather than filling the station. Keep the ship's air and power systems operating. The ship cannot leave while builders depend on its return route.

At the station core, select a room tile (`0,0`, `1,0`, `0,1`, `1,1`) and room type, then choose **Build room**. Naming and builder invitation have distinct fields; construction does not require command syntax.

Each placed piece consumes one kit in Survival. **Build full station** requires an empty station: four kits for the starter, or 28 kits for the ring after expanding the build area. It uses a deterministic connected layout. Creative mode does not consume kits. Joining neighboring pieces creates one shared, framed bulkhead with an ordinary door node. Existing conflicting blocks or occupants stop placement; room kits do not overwrite a player's construction.

You can also build rooms and equipment by hand within the reserved build area. Ordinary framed doors participate in the graph without a prefab identity. The station core's **Return to construction ship** action provides a return route before permanent ports work. Once the first powered port is ready, the temporary connection becomes a normal dock.

## Station systems and access

- Stations use the existing shared power bus, finite atmosphere, equipment diagnostics, computers and camera feeds. They need no engines, ship controller, surface pad or launch inspection.
- Expansion conserves existing gas and fuel. New rooms fill from their installed pumps; keep the station active and powered while they pressurize. The station tether measures distance outside its build area, so distant interior rooms do not trigger a return to the core.
- Rendering supports up to twelve nearby vessels within a total budget of sixty-four 32-block render sections. One full station and eight visiting ships fit that budget. Station section boundaries do not clip collision, boarding or gas flow.
- Closing a bulkhead blocks gas exchange without disconnecting the surrounding power bus. Actual blocks and room pressure determine safety; the doorway graph provides connections and routes.
- The owner can rename the station, invite an online builder by name, and switch docking access between private (owner/invited builders) and public. Public docking grants no construction or protected storage rights.
- A station keeps its identity, hull and contents if its core breaks. An authorized builder can place and use a replacement core. Existing visitors retain the door access needed to return even if public access is revoked.
- If an airlock is damaged, return crew before using **Emergency departure** from the visiting ship. It attempts to close surviving doors, then automatically backs away. It waits if propulsion lacks power or the corridor is obstructed. Damaged seals retain their actual vacuum consequences.

Placement is saved before construction starts and completed with a world save. Restarting an interrupted job replays its reserved plan while the section is unavailable. Kit receipts prevent duplicate deployment. Player stations are limited to 32 per owner; each supports up to 64 room-kit tiles in its expanded build area. Legacy station commands remain available for explicit development fixtures. Normal navigation only discovers existing stations.

The implementation follows the [room and graph design](station-modules-and-systems.md) and [docking design](space-encounters-and-docking.md). Vertical/rotated kit placement, utility sharing between docked vessels and continuous rendering across vessel frames (boarding uses a short transition) are outside this first version.

## Explicit regeneration

Normal visits preserve station edits. An explicitly requested regeneration uses a world-local `nullspace/station-regeneration-request.json`, identifying the stations, an unchanged source metadata hash and a verified backup. On the next launch, it queues ordinary saved construction jobs and archives the request. Replaying a completed request does not rebuild the station again.

Regeneration replaces each selected station's blocks, containers and equipment with its current template. It preserves station identity, name, access, ownership, region and position, and preserves other ship records. Crew and visiting ships must be returned before queuing the job. A player entering the rebuild area pauses clearing until they leave. Interrupted construction resumes through the existing build journal.

The compact Waypoint template regenerates with two current Docking Bases under ordinary doors. Existing expanded ring stations retain their larger envelope and regenerate with eight berths. The Manufacturer Showcase uses its own template.

The verification receipt records the tests and World 5's request, including its verified backup, original source metadata hash and subsequent rebuild completion.
