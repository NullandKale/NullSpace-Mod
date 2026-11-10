# Procedural ships and manufacturer stations

Status: nine manufacturer ships are implemented: a scout, freighter and explorer for Rivet, Halcyon and Bastion, plus one compact Manufacturer Showcase station. Stations now support a 128 x 32 x 128 build area and a generic eight-berth ring. Seeded orbital population now places a Waypoint outpost and Manufacturer Exchange automatically per visited region. Procedural ship synthesis and the three manufacturer-specific harbor designs below remain proposed.

Generate ships and space stations that have a recognizable manufacturer, a clear job, useful rooms and a distinctive outline. Keep them buildable from ordinary blocks and editable after placement. Include one reference station for each manufacturer alongside its ship range.

## Manufacturer personas

Manufacturer and role are separate choices. The manufacturer defines how a ship is designed; its role defines what it must do. A freighter and scout from one manufacturer should look related without sharing the same floor plan.

These names are working names:

| Manufacturer | Personality | Hull habits | Interior habits |
| --- | --- | --- | --- |
| Rivet Works | Practical, repairable industrial equipment | Exposed engine pods, broad cargo sections, blunt or stepped nose, strong structural spine | Direct service corridors, equipment access, workshop beside engineering, cargo near the dock |
| Halcyon Aerospace | Quiet, efficient exploration ships | Tapered nose, swept shoulders, narrow tail, integrated engines and grouped windows | Forward bridge, brighter crew areas, observation spaces, machinery separated from living space |
| Bastion Shipbuilding | Durable, disciplined fleet hardware | Angular bow, recessed windows, compact bridge, heavy shoulders and protected engines | Short routes to controls and machinery, compartment bulkheads, compact crew rooms, an alternate route on larger ships |

Each persona needs rules for shape, proportions, windows, materials, lighting, room arrangement and external equipment. Colors reinforce the identity. Changing the paint alone does not produce a different manufacturer.

Start with one consistent construction language per manufacturer, shared across its ships and stations. Later, individual product lines can introduce controlled exceptions.

**Notes:**

## Ship roles

| Role | Space priorities | Shape priorities |
| --- | --- | --- |
| Scout | Bridge, sensors, small engineering area and compact crew space | Small forward section and a clear propulsion arrangement |
| Freighter | Accessible hold, workshop and engineering; modest bridge and crew area | Cargo volume visibly dominates the ship |
| Explorer | Crew space, workshop, stores, observation area and engineering | Longer inhabited section; a second deck when size permits |
| Patrol | Bridge, supported cannon placement, engineering and compact stores | Clear firing direction, machinery protection and a compact outline |

Small ships combine functions in one room. Larger ships earn separate rooms and routes. Do not force a tiny scout to contain a kitchen, bedroom, cargo room and engineering room as four unusable closets.

For example, a Rivet freighter can have a broad hold, offset bridge and external engine pods. A Halcyon freighter can carry the same usable cargo space inside a tapered hull with integrated engines. Both still need a practical route from the dock to cargo.

**Notes:**

## One station per manufacturer

Each manufacturer gets a complete station showing the same design habits at a different scale. These are distinct destinations with useful interiors, not enlarged ships or recolored copies of Waypoint. Names are provisional.

| Manufacturer | Reference station | Silhouette | Rooms and routes |
| --- | --- | --- | --- |
| Rivet Works | Rivet Exchange — cargo and maintenance depot | An asymmetric freight spine with branching docking piers, broad storage pods and an offset operations room | Dock beside receiving/storage, workshop beside engineering, crew rooms away from the freight route, and a second service route around the hold |
| Halcyon Aerospace | Halcyon Observatory — exploration and survey station | A central hub surrounded by an open polygonal docking ring, observation wings and recessed utilities | Arrival lounge, observation/sensor room, furnished research/work space, crew quarters and engineering; a short circulation loop keeps visitors out of machinery access |
| Bastion Shipbuilding | Bastion Watchpost — patrol support station | A stepped command section with separated angular docking arms, recessed berth faces and protected utility pods | Dock reception, operations, equipment stores, compact crew rooms and engineering; framed bulkheads divide compartments, with an alternate maintenance route |

Each station starts with a core section and grows into a harbor assembled from multiple sections. Ships keep their existing 32 x 32 x 32 limit; a full station's docking ring or piers must not be squeezed into one ship-sized cube. The compact showcase proves each manufacturer's rooms; use the expanded station runtime for the full harbor layouts. Smaller or shaped room pieces express the outline within each section.

Each station has one station identity and managing Station Core, a multi-ship harbor, working generators, batteries, compartment air supplies and reachable controls. Berths use framed standard doors on Docking Bases. Stations use their core for control and have no propulsion requirement. Capacity means ships can actually remain docked together and still arrive, depart and board safely.

The research and patrol themes use existing equipment and furnishing. They do not require new research progression, trading systems or combat AI. A station's job should already be apparent from its layout, storage, sensors and work areas.

Generate these stations directly in space through the station placement/construction path, preserving ownership and access rules. Keep them out of surface ship delivery. Provide a development gallery with each manufacturer's scout and station together for comparison. Keep the existing Waypoint and player stations intact; opening an old save must not replace them with a new template.

**Notes:**

## Docking rings and multi-ship harbors

Stations need actual docking districts with circulation, receiving areas and space for many ships. A ring is a walkable structure made from ordinary blocks and framed doors, with connections back to the core. Rings can be polygonal and use horizontal doorway faces; they do not require angled doors or rotating station physics.

| Manufacturer | Harbor layout | Boarding and service routes |
| --- | --- | --- |
| Rivet Works | Branching cargo piers on a service spine; outward berths along separated fingers, with larger ships at the ends | Short dock-to-storage routes, receiving areas, workshops and a parallel crew/service corridor |
| Halcyon Aerospace | An open docking ring around the central hub, connected by spokes; berths face outward | A continuous passenger promenade, observation lounges and cross-routes through the hub; service rooms stay off the main route |
| Bastion Shipbuilding | Separated docking arms around a protected command section; angular bulkheads divide groups of berths | Reception and equipment stores near the docks, with an alternate maintenance route and independent compartment isolation |

Proposed capacity targets are **4 simultaneous ships for a small harbor, 8 for a standard station, and 12 or more for a large station**. These are design targets, not existing capabilities. Berths declare the ship dimensions and docking orientations they accept. A station must state its capacity for each supported size mix; twelve small berths do not mean twelve maximum-size ships fit.

Place berth positions, docked ship envelopes and approach/departure corridors before adding harbor decoration. Reserve space for the full ship, its orientation changes during approach, its engines and a separation margin. A door socket's size does not determine ship spacing. Cargo pods, observation wings, neighboring ships and the station itself must remain outside each accepted route.

Docking remains automatic. Selecting a station as Destination assigns a free compatible berth; an individual berth can also be a destination in the same list. Reserve that berth and its traffic corridor before approach. Serialize conflicting maneuvers; ships should queue at clear holding points when the harbor is full or a shared corridor is busy. Release reservations on cancellation, disconnect or failed approach, without releasing an existing clamp. Docked ships must retain their route home when new arrivals use other berths.

Every berth needs a safe boarding anchor and a walking route into the station's rooms. Validate departure with neighboring berths occupied, and validate arrival into the last available berth. A ship at a large berth must not trap smaller ships beside it. Include mixed ship sizes, offset ship ports, blocked approaches, damage, unloading and save/reload in the harbor checks.

The current station runtime supports one identity and pose across a 128 x 32 x 128 build area, sixteen 32-block render sections, shared finite power and a continuous air grid. A generic 28-room ring provides eight exterior berths. Section boundaries do not create air, block walking routes or clip collision geometry. The three manufacturer-specific harbors and traffic queues below remain proposed. Expansion preserves existing rooms and occupied berth links.

**Notes:**

## Shape and rooms are planned together

The original rectangular builder has been replaced: every design is now a template edited in game (see [generation infrastructure](generation-infrastructure.md)).

1. **Choose a brief:** manufacturer, role, size, seed and generation version. Set room, equipment, fuel and block budgets.
2. **Choose a silhouette:** long spine, broad cargo body, wedge, stepped decks, connected side pods or an offset command section. Manufacturer rules control which combinations make sense.
3. **Plan the room graph:** required functions, doors, corridors and deck connections. Put the dock where boarding and loading make sense, then reserve routes to every required room.
4. **Fit actual room volumes:** vary width and length along the ship, reserve stairs and door swings, and keep enough usable floor space. If rooms do not fit the outline, revise the outline or reject that attempt.
5. **Build the sealed hull:** wrap the planned interior, add deck floors and bulkheads, then place windows, engines, landing supports and the compact dock.
6. **Furnish and validate:** install real hardware, storage, lights and work areas. Add restrained exterior detail, then check the final blocks again.

Use different cross-sections along the length: a narrower nose, wider working section, stepped shoulders and a tail arranged around engines. Changes in deck height and connected pods should change the actual silhouette and interior space.

Keep a full-block pressure shell. Stairs, slabs and other partial blocks can shape exterior trim around it. Every pod, engine and decorative assembly must remain face-connected to the captured ship.

**Notes:**

## Room pieces and door nodes

Use the same doorway idea as [station rooms](station-modules-and-systems.md), with smaller and more flexible pieces. The station's fixed 16-block tiles are too coarse for these ships.

Each room piece declares its role, usable floor, occupied blocks, reserved walking space, doorway sockets and equipment anchors. Offer small, medium and stretched variants. A bulkhead joint owns one framed doorway; ordinary doors become graph nodes without another equipment block.

Keep these views separate:

- **Design graph:** which rooms should connect and why.
- **Walking graph:** whether a player can actually use those routes, including doors, stairs, headroom and landings.
- **Pressure compartments:** what is sealed when doors are closed and which air outlets supply it.

On larger ships, add a useful loop when space allows. Avoid a long chain of rooms that forces every trip through sleeping quarters. A cargo route should not run through the bridge, and stairs should not empty into a machine or door swing.

Once delivered, the placed blocks are authoritative. Breaking walls or moving equipment updates the existing topology and atmosphere; generation metadata must not restore the original layout.

**Notes:**

## What a finished ship must pass

- Fit the selected pad and the existing 32 x 32 x 32 ship allocation, including trim and equipment. Keep launch, docking approach, engine exhaust and cannon clearances valid.
- Contain exactly one Ship Controller and the connected engines, generator and air equipment needed for its job. Use only current hardware and actual upgrade slots.
- Have a framed standard door on a Docking Base, with usable interior boarding space and a clear outward approach.
- Provide a checked walking route from the dock to every required room and equipment interaction position. Closed doors can be operated along the route; a graph edge alone does not prove clearance.
- Seal occupied compartments and supply their air. Separate rooms behind closed bulkheads need an actual air source or a validated gas path.
- Supply the real power demand. Fuel, upgrades and any starting resources initialize once and survive normal delivery, travel and recovery.
- Have a readable front, rear and propulsion arrangement, plus useful rooms rather than an empty outer shell. Judge this in-game from outside and by walking the interior.

Generation has a fixed attempt budget. An impossible brief returns a clear failure before world placement; it must not loop indefinitely or silently substitute a different manufacturer or role.

**Notes:**

## Implementation order

| Step | Deliverable | Review gate |
| --- | --- | --- |
| 1. Manufacturer examples | Three small ships with the same role and similar size, one per manufacturer | Their shape and room arrangement remain distinguishable with the same material palette |
| 2. Room kit and graph | Reusable rooms, doorway joints, corridors and stairs | All required spaces are reachable and pressure checks agree with the placed blocks |
| 3. Nine reference ships | Scout, freighter and explorer for each manufacturer | Each manufacturer stays recognizable across roles; each role changes usable space and silhouette |
| 4. Station section prototype | One core/room/berth example for each manufacturer | Each example fits the current runtime, has valid systems and belongs visually with its ships |
| 5. Multi-section stations | Shared station identity, section placement, doorway links, systems boundaries and harbor reservations | Save/reload, expansion, damage, ownership and boarding work across sections without duplicating resources |
| 6. Three reference harbors | Exchange piers, Observatory ring and Watchpost arms | Distinct silhouettes and useful rooms; the advertised capacity works with all berths occupied |
| 7. Seeded generation | Controlled choices of ship/station proportions, room variants, routes and details | A large repeatable seed set stays valid, terminates and produces meaningful variation |
| 8. Placement integration | Ships in the existing beacon catalog; stations through space construction; both in a development gallery | Preview and placement use the same plan; interruption resumes that exact build and preserves ownership |

Keep the current presets while reviewing the first examples. Patrol ships follow once the first three roles establish the generation rules.

Generation produces a plan containing its blocks, rooms, doorway connections, controller position, equipment placements and reserved clearances. This replaces the current assumption that a controller's position can be calculated from a rectangular preset's dimensions.

Plans explicitly identify ships versus stations. Share manufacturer rules and room/joint definitions, while validating station cores, berth reservations and station placement separately from ship propulsion and surface delivery.

Save the resolved plan with its seed and version before delivery. Recovery must use that plan, even after generator rules change. Reuse the existing delivery intent and transfer systems, and store initial fuel/upgrades as part of the order so retries cannot refill them.

For the first player-facing version, use curated generated designs in the existing catalog. Show manufacturer, role, size and the existing preview beside Deliver ship. Seed entry and reroll controls can be considered later; they are not required to prove the designs work.

**Notes:**

## Current review set

The Ship Beacon now lists nine designs, generated by the [ship generator](ship-generator.md): one scout, freighter and explorer for each manufacturer, in a mix of spine, dome and twin-hull shapes. `/nullspace shipyard` creates the gallery at a fresh reserved site; earlier galleries and player edits remain intact. The captures below were taken of the earlier hand-built versions of these nine, and are kept for the record.

| Manufacturer | Scout | Freighter | Explorer |
| --- | --- | --- | --- |
| Rivet Works | Bolt | Hauler | Ranger |
| Halcyon Aerospace | Swift | Courier | Horizon |
| Bastion Shipbuilding | Ward | Tender | Pioneer |

Freighters have cargo holds with real storage barrels; explorers have workshops and living space. The [ship generator](ship-generator.md) lists each room and what it holds.

Enter space and open **Destination > Ports**. Each region automatically contains a Waypoint Station and Manufacturer Exchange. Choose an exchange port and press **Dock**, then open the normal dock door and walk through. The compact exchange reuses the three-wing showcase template; it is not one of the proposed large manufacturer harbors. `/nullspace station showcase` remains an explicit development fixture command.

The showcase occupies one 32 x 32 x 32 allocation, with a Rivet workshop, Halcyon observation lounge, Bastion operations room and a central arrival/core area. Two opposite berths demonstrate the compact dock templates. It is a section prototype. The expanded runtime also provides a generic eight-berth ring; manufacturer-specific harbors remain later work.

Captured in-game: [Rivet Bolt](../images/rivet-bolt.png), [Halcyon Swift](../images/halcyon-swift.png), [Bastion Ward](../images/bastion-ward.png), [showcase exterior](../images/manufacturer-station.png), [Rivet workshop](../images/rivet-workshop.png), [Halcyon observation](../images/halcyon-observation.png), [Bastion operations](../images/bastion-operations.png).

Freighter captures: [Rivet Hauler](../images/rivet-hauler.png), [Halcyon Courier](../images/halcyon-courier.png), [Bastion Tender](../images/bastion-tender.png).

Explorer captures: [Rivet Ranger](../images/rivet-ranger.png), [Halcyon Horizon](../images/halcyon-horizon.png), [Bastion Pioneer](../images/bastion-pioneer.png). The Pioneer stairwell was walked up and down in survival mode: [upper crew deck](../images/explorer-crew-deck.png), [lower workshop deck](../images/explorer-engineering-deck.png).

The [three-page catalog](../images/manufacturer-beacon-catalog.png), [expanded showroom](../images/shipyard-manufacturer-fleet.png) and [delivered explorer in space](../images/manufacturer-explorer-in-space.png) were captured in the graphical test run. The role fleet verification records 477 passing unit tests, 12 existing skips, 148 passing server tests and all 21 graphical entrypoints.

**Notes:**

## Next review

Walk the nine generated ships and compare their exterior outlines and room use, then judge the silhouettes: the generator bevels the hull and adds engine pods and an accent stripe, but swept wings, fins and landing gear are still to come. Review the same manufacturer across all three roles as well.

**Notes:**
