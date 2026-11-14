# Stations and docking

A station is a place in orbit where ships dock, players live and build, and a ship's crew can stop, trade supplies and rest. This is the guide to using one, with the reasons behind how it behaves. The design is in [station pieces and systems](station-modules-and-systems.md), and every rule and limit is in [the station reference](station-reference.md). The docking mechanics themselves are in [space encounters and docking](space-encounters-and-docking.md).

Stations are real structures made of ordinary blocks. Docking, doors, air and power are the same systems a ship uses, so what you learn on your ship carries over.

## Dock and visit

New ships come with a compact docking port. To visit a station:

1. Launch, then open **Destination > Ports** at the Ship Controller. Regions populate on their own as you arrive (see [orbital population](space-world-generation.md)), and their ports appear in the list as they finish loading. There is no "prepare" button, and browsing never creates anything.
2. Back at the controller, press **Dock**. You can also select a port directly in Destination. Autopilot aligns and mates the ports, leaving clearance for small exterior steps and cameras.
3. A lit, enclosed docking tunnel bridges the gap. Open the normal dock door and walk through. A short loading transition carries you and your inventory aboard. Once clamped, the connection works even if either ship loses power.
4. Return through the same port. When everyone is back aboard, press **Undock**. Doors close and the tunnel retracts before the ships separate. A passenger standing in the tunnel prevents the tunnel retracting.

Occupied ports stay listed, with their status. Only completed stations with valid dock doors add usable ports.

### If it does not work

Clicking a blocked travel control asks the server for the current reason without starting any movement, and shows it on the controller, in chat and in the log (for example `Ship control EXPLAIN: Dock blocked: Destination port is occupied`). A later failure reports `Docking stopped: <reason>`, even after the controller is closed.

## Who may do what at a station

A station has an owner and a **public or private** setting. Visitors can open safe internal doors, including iron doors, and use the dock controls, the Station Core and the crafting table. Visitors cannot touch the berth configuration, storage or construction, and cannot edit blocks. The owner and invited builders can do all of that. Visitors already aboard keep their way back to their ship even if public access is later switched off.

Another player's ship must accept the dock request at its own controller. **Cancel** stops an approach before capture. Starting or moving a ship needs power, fuel and the crew in breathable cabins, but an established joint stays clamped without power. Cabin air stays finite throughout: losing breathable air never locks the boarding route, and docking never creates oxygen.

## Add a port to your ship

```text
[ door upper half ]
[ door lower half ]
[   Docking Base  ]
```

Any standard door works, including iron. Put the Docking Base **directly under the door**, in the pressure hull, facing outward, with a full frame, two blocks of walking room inside and a clear approach outside. The base operates an iron door without extra buttons. No second door, chamber or dedicated air supply is needed. A docking joint is airtight but does not refill cabin air.

Older saves replace removed equipment with solid iron blocks as areas load. For a dock, put the current base below its door and keep a solid lintel above. If the main controls were replaced, install a Ship Controller in the same position. Identity, cargo and generator fuel are kept.

## Build your own station

1. Bring a **Station Core**, **Station Module Kits** and a full space suit.
2. Choose **Deploy station** at the controller. A small construction platform is placed in a clear site, and the ship docks to it.
3. Open the dock door and walk through, wearing the suit. The tether uses the ship's finite oxygen, so keep the ship's air and power running. The ship cannot leave while builders depend on its return route.
4. At the core choose a piece (entrance, workshop, bedroom, power room, tunnel, garden or observation dome), a tile, and press **Build room**. One kit builds one piece. Start at tile 0,0 and add neighbours: each new piece must meet an open doorway of an existing one. **Build full station** fills an empty starter for four kits.
5. Name the station, set Public or Private, and invite builders on the same screen.

Ordinary framed doors become route nodes automatically, and rooms, power and air come from the blocks you place. You can also build rooms by hand within your reserved area, and your hand-built room needs no catalog entry.

### Full-size stations

At the core, **Expand to 128 × 128** reserves a **128 × 32 × 128** area. It uses no kits, works on existing stations and keeps all their blocks, air, fuel and links. The new area must be clear. Use **Previous / Next section** to pick one of sixteen sections of four tiles each (tiles 0,0 to 7,7). On an empty expanded platform, **Build full station: 28 kits** builds a complete ring of 28 rooms with eight berths spaced around the perimeter.

New rooms get air from their own powered pumps, so allow time for them to fill. Generators use finite furnace fuel as always.

Screens: [expanded core](../images/expanded-station-core.png), [complete ring](../images/full-station-ring.png), [seen from another ship](../images/full-station-remote.png), [far berth](../images/full-station-far-berth.png), [Waypoint ports](../images/discovered-waypoint-ports.png), [visiting in a purchased ship](../images/purchased-ship-station-visit.png), [far-side port discovery](../images/far-station-port-discovery.png), [docked blackout arrival](../images/docked-blackout-arrival.png).

## The places you will find

- **Orbital stations**: planned, furnished stations by Rivet, Halcyon or Bastion, in Sol's home orbit and some other zones, with berths for visiting ships. The old Waypoint Station and Manufacturer Exchange are no longer built.
- **Abandoned stations**: the same planner, found through FTL journey contacts.

They are saved once and persist. Visiting never repairs, restocks or refills. If you break something, it stays broken until someone fixes it. See [orbital population](space-world-generation.md) for where they are.

For each block's screen see [equipment controls](ship-block-ui-design.md).
