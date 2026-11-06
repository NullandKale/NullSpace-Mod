# Stations and docking

## Dock and visit

New Ship Beacon deliveries include a compact docking port.

1. Launch and open **Destination > Ports** at the Ship Controller. Local space automatically contains a seeded Waypoint Station and Manufacturer Exchange. Their ports appear as the region loads; no preparation button is needed.
2. Return to the controller and press **Dock**. You can also select an existing port directly from Destination. Autopilot aligns and mates the ports, allowing clearance for small exterior steps and cameras.
3. A lit, enclosed docking tunnel bridges the gap. Open the normal dock door and walk through. A short loading transition boards the other vessel with your inventory. Once clamped, traversal works with either vessel unpowered; passengers wait on a safe interior floor during the handoff.
4. Return through the same station port. Once everyone is back aboard, press **Undock**. Doors close and the tunnel retracts before separation. A passenger standing in the tunnel prevents retraction.

Stations follow their public/private access setting. Visitors can operate safe internal doors, including iron doors, directly. Berth configuration stays protected, and visitors already aboard keep their return route if public access changes. Another player's ship must accept the request. Cancel stops an approach. Starting or moving a ship needs power, fuel and crew inside breathable cabins. An established joint stays mechanically clamped without power. Cabin oxygen remains finite: loss of breathable air does not lock the boarding route or create free oxygen.

Occupied ports remain listed with their status. Only completed stations with valid dock doors contribute usable ports. The list updates automatically. [discovered Waypoint ports](images/discovered-waypoint-ports.png), [visiting in a purchased ship](images/purchased-ship-station-visit.png), [selecting a large station's far-side port](images/far-station-port-discovery.png).

Clicking a blocked travel control asks the server for its current reason without starting movement. The explanation appears on the controller, in chat and in `run/logs/latest.log` as, for example, `Ship control EXPLAIN: Dock blocked: Destination port is occupied`. Dock attempts and autopilot transitions also include ship, destination and port context in the log. A later failure reports `Docking stopped: <reason>`, even after closing the controller.

The [feedback verification](docking-feedback-verification.json) covers blocked clicks, later failures and ordinary doors facing into the cabin.

The [tunnel and blackout verification](docking-tunnel-and-machine-art-verification.json) covers physical collars, docked inspection, safe retraction and [arrival with both vessels unpowered](images/docked-blackout-arrival.png).

## Manufacturer Exchange

Each region includes a Manufacturer Exchange with connected Rivet workshop/storage, Halcyon observation/sensors and Bastion operations/bulkheads wings. Select an exchange port as Destination and press **Dock**. Both opposite berths and every room fit inside a 32-block allocation. The exchange and Waypoint use one persistent region plan: revisiting preserves edits, damage, finite fuel and cargo. Existing Waypoint/Manufacturer Showcase stations are adopted without rebuilding them.

The first population pass uses these two compact templates. Large manufacturer-specific harbors, roaming traffic and random encounters remain future work. See [space population](space-world-generation.md) for placement and save behavior.

## Add a port to your ship

```text
[ door upper half ]
[ door lower half ]
[   Docking Base  ]
```

Use any standard door, including wood or iron. Place its base in the pressure hull, facing outward, with a full frame, two blocks of walking room inside, and a clear approach outside. The base handles iron-door interaction without extra buttons. No second door, chamber, connector or dedicated air supply is required. Docking forms an airtight joint; it does not refill cabin air.

Older saves replace removed equipment with solid iron blocks as each area loads. For a dock, put the current base below its door and keep a solid lintel above. If the main controls were replaced, install a Ship Controller at that position. Ship identity, cargo and generator fuel are retained.

## Build directly in space

1. Bring a **Station Core**, **Station Module Kits** and a full space suit.
2. Choose **Deploy station** at the controller. The ship connects to a construction platform.
3. Open the dock door and walk through, keeping the suit on. The tether uses the ship's finite oxygen.
4. At the core, select a room tile and a room type, then press **Build room** (one kit). Begin at tile **0,0** and add neighboring rooms. **Build full station** fills an empty starter for four kits.
5. Name the station, set Public/Private docking and invite builders on that same terminal screen.

Keep the ship's fuel, power and air running. **Return to construction ship** remains available at the core. Ordinary bulkhead doors become graph nodes automatically. Rooms, power, oxygen and obstructions use their actual placed blocks.

## Full-size stations

At the Station Core, press **Expand to 128 x 128** to reserve a **128 x 32 x 128** build area. This works on existing stations and keeps their identity, blocks, cargo, fuel, air and docking links. The new area must be clear. Expansion itself uses no kits.

Use **Previous section / Next** to select one of sixteen sections. Each shows four room tiles; tile coordinates run from **0,0** to **7,7**. Build with individual kits and ordinary blocks, or expand an empty construction platform and choose **Build full station: 28 kits** for a complete ring.

The ring has 28 connected arrival, living, workshop and engineering rooms, plus eight outward-facing berths separated around its perimeter. New rooms receive air from their own powered pumps; allow time for unpressurized additions to fill. Generators use finite furnace fuel as usual. One core manages the station, with shared power and continuous air simulation across section boundaries.

Ships still use the 32-block limit. Waypoint and Manufacturer Exchange remain compact examples; expanding their build area does not replace their rooms. Full manufacturer station designs remain separate work.

Captured in game: [expanded core controls](images/expanded-station-core.png), [complete ring](images/full-station-ring.png), [view from another ship](images/full-station-remote.png), [boarding the far-side berth](images/full-station-far-berth.png).

The [expansion verification](station-expansion-verification.json) records 484 passing unit tests, 12 existing skips, 150 passing server tests and all 22 graphical scenarios.

See the [full reference](station-reference.md) for permissions, recovery and limits, or [equipment controls](ship-block-ui-design.md) for each block's screen.

The [docking and regeneration verification](docking-regeneration-verification.json) records the complete controller-driven round trips, all 15 deliverable ship designs, and World 5's backed-up rebuild request. Subsequent inspection confirmed that Waypoint regenerated with two Docking Bases.
