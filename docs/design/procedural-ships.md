# Manufacturers, roles and harbors

Ships and stations should have a recognizable manufacturer, a clear job, useful rooms and a distinctive outline, while staying buildable from ordinary blocks and editable after placement. This document records the design intent for the three manufacturers and the three ship roles, how much of it the generator does today, and the design for the manufacturer harbors, which are not built. How ships are built is in [the ship generator](ship-generator.md), how they are delivered in [the shipyard](shipyard.md), and how stations work in [station pieces](station-modules-and-systems.md).

## Manufacturers

Manufacturer and role are separate choices. The manufacturer defines *how* a ship is designed and the role defines *what it must do*, so a freighter and a scout from one maker should look related without sharing a floor plan. The names are working names.

| Manufacturer | Personality | Hull habits | Interior habits |
| --- | --- | --- | --- |
| Rivet Works | Practical, repairable industrial equipment | Exposed engine pods, broad cargo sections, a blunt or stepped nose, a strong structural spine | Direct service corridors, workshop beside engineering, cargo near the dock |
| Halcyon Aerospace | Quiet, efficient exploration ships | Tapered nose, swept shoulders, a narrow tail, integrated engines, grouped windows | A forward bridge, brighter crew areas, observation spaces, machinery apart from living space |
| Bastion Shipbuilding | Durable, disciplined fleet hardware | Angular bow, recessed windows, a compact bridge, heavy shoulders, protected engines | Short routes to controls and machinery, compartment bulkheads, compact crew rooms, an alternate route on larger ships |

Colors reinforce an identity, and changing paint alone does not make a different manufacturer.

### What the generator does today

In the generator (`Spec.Maker`) the maker chooses **materials and accent colour** and nothing else. The shape, size and rooms come from the other choices, so the manufacturer personality shows up through which shapes the catalog gives each maker and through the interior and palette:

| Maker | Scout | Freighter | Explorer |
| --- | --- | --- | --- |
| Rivet Works | Bolt (spine, small) | Hauler (spine, medium) | Ranger (spine, large) |
| Halcyon Aerospace | Swift (dome, small) | Courier (twin hull, medium) | Horizon (dome, large) |
| Bastion Shipbuilding | Ward (twin hull, small) | Tender (dome, medium) | Pioneer (twin hull, large) |

Rivet is always a spine, which is the "strong structural spine" habit, while Halcyon and Bastion mix domes and twin hulls. Exposed engine pods and an accent stripe are common to all. The tapered noses, swept shoulders, angular bows and protected engines of the table above are the design target for a future exterior pass, not current behavior (see [the ship generator](ship-generator.md#not-yet)).

## Roles

| Role | Space priorities | Shape priorities |
| --- | --- | --- |
| Scout | Bridge, sensors, small engineering and compact crew space | A small forward section and a clear propulsion arrangement |
| Freighter | An accessible hold, workshop and engineering, with a modest bridge and crew area | Cargo volume visibly dominates |
| Explorer | Crew space, workshop, stores, observation and engineering | A longer inhabited section, with extra decks when size allows |
| Patrol (not built) | Bridge, supported cannon placement, engineering and compact stores | A clear firing direction, protected machinery and a compact outline |

Small ships combine functions in one room, and larger ships earn separate rooms and routes. A tiny scout is not given a kitchen, bedroom, cargo room and engineering room as four unusable closets. The role sets the wish list of rooms (see [rooms and resources](ship-rooms-and-resources.md)).

## What a finished ship must pass

The generator is checked against these on every combination, and ship tests check them again in the real air system:

- It fits the pad and the 32 × 32 × 32 allocation, including trim and equipment, and keeps launch, docking approach and engine exhaust clear.
- It has exactly one Ship Controller and the engines, generator and air equipment its job needs, using current hardware and real upgrade slots.
- It has a framed standard door on a Docking Base with usable boarding space inside and a clear approach outside.
- A checked walking route runs from the dock to every room and machine. A graph edge alone does not prove clearance.
- Occupied compartments are sealed and supplied with air, and the power demand is met. Fuel, upgrades and starting resources initialize once and survive delivery, travel and recovery.
- It has a readable front, rear and propulsion arrangement and useful rooms, which is judged in game from the outside and by walking through it.

Generation has a fixed attempt budget. An impossible brief returns a clear failure before anything is placed and never loops or substitutes a different manufacturer or role.

## Keep three views of a ship separate

The *design graph* says which rooms should connect and why. The *walking graph* says whether a player can really use those routes, including doors, stairs, headroom and landings. The *pressure compartments* say what is sealed with the doors closed and which outlets supply it. Once a ship is delivered, the placed blocks are authoritative: breaking walls or moving equipment updates topology and air, and generation data never restores the original layout. Keep a full-block pressure shell, since stairs and slabs can shape the exterior trim around it, and every pod and engine must remain face-connected to the ship.

## Harbors (not built)

The design calls for one reference station per manufacturer, each showing its habits at station scale and each with a multi-ship harbor. These are destinations with useful interiors, not enlarged ships or recolored Waypoints. Today a region has a Waypoint Station, a compact three-wing Manufacturer Exchange, and a generic eight-berth ring is available to builders (see [stations](stations.md)). The three manufacturer harbors are a later project.

| Manufacturer | Station | Harbor layout | Routes |
| --- | --- | --- | --- |
| Rivet Works | Rivet Exchange: cargo and maintenance depot | Branching cargo piers on a service spine, with outward berths along separated fingers and larger ships at the ends | Short dock-to-storage routes, receiving areas, workshops and a parallel crew and service corridor |
| Halcyon Aerospace | Halcyon Observatory: exploration and survey station | An open docking ring around a central hub, joined by spokes, with berths facing outward | A continuous passenger promenade, observation lounges and cross-routes through the hub, with service rooms off the main route |
| Bastion Shipbuilding | Bastion Watchpost: patrol support station | Separated docking arms around a protected command section, with angular bulkheads dividing groups of berths | Reception and stores near the docks, an alternate maintenance route and independent compartment isolation |

Design targets: 4 ships docked at once for a small harbor, 8 for a standard station and 12 or more for a large one. Berths declare the ship sizes and orientations they accept, and a station states its capacity for each size mix (twelve small berths do not mean twelve large ships fit). Berth positions, docked-ship envelopes and approach corridors are laid out first and decoration comes after, with room for the full ship, its orientation changes, its engines and a separation margin. A door socket's size does not set ship spacing.

Docking stays automatic. Choosing a station as the destination would assign a free compatible berth, and a single berth stays selectable. The berth and its corridor would be reserved before the approach, conflicting maneuvers serialized, and ships queued at holding points when the harbor is full. Reservations release on cancel, disconnect or failure and never release an existing clamp. Departure would be validated with neighbouring berths full, and arrival into the last free berth, with mixed sizes, offset ports, blocked approaches, damage and save and reload in the checks.

The station runtime already supports what this needs: one identity across a 128 × 32 × 128 area, shared power, a continuous air grid and sixteen render sections, where section boundaries create no air, block no routes and clip no collision.

## Status

| Step | State |
| --- | --- |
| Nine reference ships (a scout, freighter and explorer for each maker) | Built, generated |
| Room kit and doorway graph | Built (ships and station tiles) |
| One compact station for the three makers | Built (the Manufacturer Exchange) |
| Station expansion to 128 × 128 with a generic eight-berth ring | Built |
| Three manufacturer harbors and berth traffic queues | Not built |
| Patrol ships | Not built |
| Seed entry and reroll in the beacon | Not built (curated designs only) |

Captures: [Rivet Bolt](../images/rivet-bolt.png), [Halcyon Swift](../images/halcyon-swift.png), [Bastion Ward](../images/bastion-ward.png), [Rivet Hauler](../images/rivet-hauler.png), [Halcyon Courier](../images/halcyon-courier.png), [Bastion Tender](../images/bastion-tender.png), [Rivet Ranger](../images/rivet-ranger.png), [Halcyon Horizon](../images/halcyon-horizon.png), [Bastion Pioneer](../images/bastion-pioneer.png), [showcase exterior](../images/manufacturer-station.png), [Rivet workshop](../images/rivet-workshop.png), [Halcyon observation](../images/halcyon-observation.png), [Bastion operations](../images/bastion-operations.png). The ship captures predate the generator and show the earlier hand-built versions of the same nine designs.
