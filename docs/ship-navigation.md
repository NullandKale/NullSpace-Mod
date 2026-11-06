# Ship cameras, local flight, and ship controller navigation

Ships have a simulated pose and velocity inside a named local space. Their hull blocks, rooms, cargo, and crew stay in their existing axis-aligned storage slot in the shared `nullspace:space` dimension. Moving or rotating the simulated ship changes its observer-relative surroundings, third-person image, and combat coordinates.

## Star systems

The world now has a saved star chart: Sol and Vega retain their existing locations, and four seeded systems add two planets and a moon each. They appear automatically under **Destination > Regions**. Jump to a body, choose its registered Landing pad, then Land. Launch returns to that body's orbit. Each new surface has its own dimension; orbit views share the system's actual star, planets and moon.

Stations populate each region automatically when you visit. Returning preserves the chart, stations, surface builds and resources. See [saved star systems](star-systems.md) for the current generation limits.

## Using the ship controller

Right-click a Ship Controller or Ship Computer to open the controller. It has one saved **Destination** and separate **Launch**, **Jump**, **Land**, **Dock** and **Undock** buttons. Disabled actions explain what is needed when selected.

- **Pilot** opens the external ship camera and acquires exclusive flight control.
- **Ship camera** opens an independent observer camera without acquiring control. Another crew member can use it while the pilot flies.
- **Destination** opens one searchable list of regions, registered landing pads and available docking ports. Filter by type, select an entry, then choose **Set destination**. Selection alone never starts travel.

To reach a remote pad or port, select it once, **Launch** if grounded, **Jump** to its region, then **Land** or **Dock**. Launch, jumps, landings and reopening the controller retain that final selection. Region-only destinations support Jump; landing and docking need an actual pad or port. An unavailable destination stays selected so the ship never silently switches targets.

Powered scanners use the same list and **Set destination** action, updating the ship controller's selection. The Scanner shows the current region; installing a System Upgrade includes other regions. Destination lists update automatically. Unloaded pads and registered ports remain subject to a full check when used; listing them does not load remote terrain.

**Inspect hull**, **Close all doors** and **Deploy station** are direct controls. **Advanced systems** opens engineering. Waypoint and Manufacturer Exchange stations populate automatically as players enter space, and their berths appear under **Destination > Ports**. Docking and walking through the normal door handle boarding. Propulsion and generators consume finite shared furnace fuel.

![One destination and separate travel actions](images/ship-controller-docking.png)

![Scanner sets the same ship destination](images/ship-scanner-destination.png)

| Input | Action |
| --- | --- |
| W / S | Forward / reverse thrust toward the bridge's forward direction (opposite the panel face) |
| A / D | Yaw left / right |
| Up / Down | Pitch nose up / down |
| Q / E | Roll left / right |
| Space / Ctrl | Ship-relative vertical thrust |
| R | Brake |
| F | Fire the mounted weapon using its existing power and cooldown rules |
| Mouse drag | Orbit the third-person camera independently of steering |
| Mouse wheel | Zoom |
| H | Toggle the controls overlay |
| Escape | Release controls and return to the cabin |

The chase camera starts behind the bridge's forward direction, aims at the connected hull's bounds, and chooses its distance from the hull size. It supplies a modest camera fill light so an unlit hull remains readable; this changes camera presentation only, without changing world light or equipment power. It renders ordinary textured ship blocks and crew through the existing independent scene renderer, with the same compute-rendered celestial surfaces and gas as cockpit and monitor views.

Pilot and observer cameras fill the screen with aspect-correct targets and compact status overlays. Press **H** for controls. See the [visual-fix record](visual-polish-2026-10-25.md) for the fullscreen capture.

## Local flight

The server integrates flight at 20 Hz. Inputs are bounded button masks tied to the owning player, ship controller, ship, and random session token. Sequence numbers reject replays. Only one pilot can control a ship; observer views have no thrust authority. Leaving ship controller range, changing dimension, losing the ship controller, dying, disconnecting, missing heartbeats, or closing the screen releases control and stops the ship. Window focus loss clears held controls.

Translation uses normalized combined thrust, damping, a brake, and a power-dependent speed cap. Steering operates in ship-local axes, including a ship controller mounted facing any horizontal direction. Rotation preserves the storage cube's simulated center. Powered ships require delivered control and engine power and enabled engines with clear exhaust. Legacy ships retain their compatibility engine output. Power loss stops movement; the camera remains available and shows the reason. Flight stops while online crew are outside breathable cabins, including after a breach.

Local movement stays within an 8,192-block radius. Swept conservative bounds stop translation into explicit celestial bodies or another ship in the same local space. This is collision avoidance; generated asteroid-field instances are currently visual obstacles, and contact does not apply impact damage or rigid-body response. Weapon fire still traces actual hull geometry and explicit celestial shapes. [Automatic docking](stations.md) provides a separate checked approach and airlock boarding path. Gravity, orbit integration and free EVA between ship frames remain separate work.

Authoritative scene snapshots advance at 10 Hz during piloting. All client views share bounded position/quaternion interpolation. Geometry arrays remain shared; pose changes do not rebake hull models. The motion cache is limited to 64 ship/region entries and clears on disconnect. Poses save periodically while piloting and when controls are released; a process crash may lose the latest unsaved flight movement. Opening controls or an observer camera without an active pilot clears stale saved velocity.

## Planets and systems

| System | Local spaces |
| --- | --- |
| Sol | Terra, Luna, Ares |
| Vega | Pelagos, Cinder |

Each destination has a stable server address and a persisted celestial definition with its own ID, seed, bodies, gas, asteroid field, and epoch. Existing saves without an address default to Sol / Terra. Local thrust never changes this address, even if the ship reaches the edge of the encounter space. Planet-to-planet and system-to-system changes use **Jump** at the controller.

Ships in different regions can occupy identical simulated coordinates. Their storage slots remain distinct. Backgrounds, monitors, ship cameras, weapon targets, and native TNT blast fan-out filter by region. A bounded arrival search leaves clearance around ships and explicit bodies before choosing the new pose.

Fast travel validates the issued route, ship/cargo preflight, a sealed occupied cabin, powered propulsion, and recalled EVA crew. It rejects the current destination and unresolved queued combat impacts. It closes pilot leases, locks the hull and crew through the existing transit protections, and shows the loading screen to all online occupants.

After preparation acknowledgments, the server rechecks the ship and atomically saves its new region and arrival pose. It transfers no blocks or entities. A save failure restores the old metadata. The loading screen waits for the new definition and a prepared scene on each client before acknowledging arrival. Wrong-token and premature arrival acknowledgments cannot release the crew. Preparation timeout or pilot disconnect cancels before commit; a client that fails destination synchronization after commit must reconnect. Region jumps and physical launch/landing transactions serialize to keep whole-store recovery snapshots consistent.

The initial navigation feature used Terra landing pads only. [Planet surfaces](planet-computers-and-rendering.md#planet-surfaces-and-travel) now add four native flat terrain dimensions and starter landing sites. Jump to a pad's planet before landing there; existing overworld pads belong to **Sol / Terra**. Fuel costs, unlocks and route progression remain separate work. Jump arrival resets simulated orientation and velocity; player facing, block facing, and physical crew positions stay intact.

## Verification

`FlightDynamicsTest` covers every ship controller facing, steering direction, diagonal thrust, speed bounds, opposing inputs, braking, power loss, invalid inputs, and repeated rotations. Terminal tests cover the flight actions and stable route selection. Native navigation tests cover persisted region definitions, session catalog validation, arrival placement, exclusive ownership, stolen/replayed input, and stopping on release.

`NavigationClientGameTest` drives the real typed terminal and input packets, checks simulated movement with stationary physical crew, opens the observer camera, and performs Terra ? Luna ? Vega/Pelagos ? Terra with destination-scene readiness. The local two-client harness adds exclusive piloting, a simultaneous crew camera, stale-velocity recovery, and shared-region round trips before its existing offline landing and engineering scenarios.

Verified: 98 unit tests, 40 server tests, nine graphical test classes, and the two-client loopback scenario passed. See [local verification](local-verification.md) and the [verification manifest](navigation-verification.json).

![Third-person sphere camera](images/ship-camera-sphere.png)

The [flight view](images/ship-flight.png), [orbit view](images/ship-camera-orbit.png), and [ship controller navigation menu](images/helm-navigation.png) are captures from the graphical regression.

## Stations, docking and future encounters

[Stations and automatic docking](stations.md) implement Waypoint, player construction started directly in space, a room-module graph, automatic approach/alignment/capture and departure, and short airlock boarding transfers. Players select Dock at the ship controller; manual positioning or alignment is never required. A deployed core/platform provides the first construction berth. The broader [encounter design](space-encounters-and-docking.md) retains future random scheduling, additional exploration contacts and stations spanning several simulation sections.
