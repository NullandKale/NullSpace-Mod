# Landing pads, ships, and space travel

**Status: the accepted review changes are implemented. Unit/server, graphical, local multiplayer, and seven real crash/restart checks passed.**

**Implemented extensions:** shared ship power, finite oxygen, equipment controls, native hull damage, ship-to-ship combat, remote monitors, and compute-rendered surroundings are described in [ship systems and viewports](ship-systems-and-viewports.md) and [the compute renderer record](space-compute-renderer-plan.md). [Ship navigation](ship-navigation.md) adds third-person local piloting and helm-only fast travel between planet/system regions. Baseline air and void-only descriptions below record the original loop; upgraded ships and the linked extension records define the expanded behavior.

[Automatic pad resizing](pad-and-sky-fixes-2026-10-26.md) now refreshes completed ring edits and the landed ship's build allocation without manual re-registration. [Fuel, engines, generation, and battery levels](ship-fuel-and-power-design.md) are the next design proposal, with a Q&A for review.

Ship damage and explosive ship-to-ship combat must also work with that frame separation. Local explosions damage actual blocks; inter-ship weapons need server-owned simulated hit detection mapped into the target's storage frame. The extension includes a combat gate covering breaches, equipment loss, persistent wrecks, blast isolation, and transfer ordering. Remote presentation alone does not provide collision or weapon hits.

The user confirmed the terminal fixes and resolved the 2026-10-23 review. Accepted simplifications now form part of this plan. Stairs, ship diagnostics, pad availability, stable destination selection, and authenticated terminal sessions are required gameplay. See [the decision record](plan-review-2026-10-23.md) and [local verification](local-verification.md) for the current evidence and remaining limits.

Pad registration, connected ship inspection, engines, framed-door pressure, void travel, named-pad return, cargo/entity preservation, durable recovery, loading-screen readiness, complete suits, controller-supplied air, and automatic tethers are implemented. Computers share an actual 80-column, 25-row cell-buffer terminal with optional mouse input, native text entry, cursor addressing, and streamed output. The prepared-world launcher adds ship equipment to existing development saves without rebuilding plot 1.

Original-loop defaults (retained by legacy ships): four distinct suit armor items (iron visuals), a 72-block tether, binary unlimited cabin air, no portable oxygen reserve, normal gravity in sealed cabins, automatic suit flight anywhere outside breathable cabins, and ordinary planetary respawn. Public named pads and unchanged block orientation are the initial landing rules.

Target: Minecraft Java 26.3, Fabric Loader, and Fabric API. All gameplay systems belong to NullSpace. The existing scaffold and tests remain the starting point.

## 1. Direction from the review

Use blocks and building as the main interaction. Players build a landing pad and use a computer-like pad control block to discover it, name it, and register it. A separate control block aboard the ship handles inspection and travel. No selection wand or other handheld setup tool is needed.

Build the smallest complete version of the desired effect. Start with grass/dirt pad surfaces, one border material, a 32 x 32 x 32 ship limit, airtight cabins, ordinary doors with explicit sealing rules, entity transfer, loading-screen travel, and an empty space dimension. Introduce shared infrastructure only where it directly serves these features. A larger system refactor should be a deliberate decision.

The ship stays ordinary, stationary Minecraft blocks in space. The implemented local-flight simulation moves the surrounding scene relative to it. The original first-loop scope was void-only; current scenery, flight controls, and region jumps are documented in the extensions.

### Decisions carried through this revision

| Area | Current direction |
| --- | --- |
| Pad discovery | The pad control block searches down for the surface, then sideways for a cobblestone border. Geometry comes from the build. |
| Surface | Accept grass blocks, dirt, or any mixture for the pad interior. Natural grass-to-dirt conversion does not invalidate a pad. Avoid a configurable material system for now. |
| Border | A complete, one-block-wide rectangular cobblestone ring around the grass/dirt interior. |
| Ship materials | Grass blocks and dirt cannot be ship blocks. The pad and border stay on the planet. Cobblestone above the pad plane can be ship material. |
| Size | Ship footprint up to 32 x 32, height up to 32 above the pad. The outside border is additional, so a maximum pad occupies 34 x 34 blocks. |
| Airtightness | Full sealing blocks and correctly framed, flush, closed doors. Glass blocks can seal; panes do not count unless their geometry is explicitly proven airtight later. |
| Depressurization | Opening a door to vacuum without an isolated airlock, or breaking the hull, exposes the connected room. No mandatory exit interlock. |
| Exposure | Lose breathable air and then health using drowning-style behavior. Hull breaches are dangerous. |
| Occupants | Include contained entities, including hidden hostile mobs, when transferring the ship. |
| EVA | Players remain tethered to their ship in space. Start without tank inventory, consumable tank oxygen, or rope physics. |
| Destinations | Named, registered pads can be landing destinations. Public/private access is later work. |
| Space | Static physical ship blocks, simulated local piloting, and helm-only planet/system jumps. Normal cabin gravity and automatic suit flight remain. Gravity/orbit integration is later work. |
| Controls | Block-based computer interfaces with a small shared interface API used by pad and ship controls. |

### Interpretations used in implementation

The comments leave a few details open. The rest of this plan uses these explicit assumptions:

- **Tethers provide oxygen:** a suited player receives breathable air through the tether from a sealed cabin associated with the ship controller. There are no portable air tanks initially. If that source room vents, tether oxygen also fails.
- **Cabin air is binary and unlimited initially:** a sealed room is breathable; an exposed room is not. No oxygen generator, power network, stored gas, or pressure quantities are required for the first version.
- **Entity support starts with contained mobs and items:** a hidden creeper is a required case. Special entity arrangements can fail preflight with a clear message until their transfer is supported.
- **Tethers are automatic in space:** arrival associates every passenger with the ship's anchor. Players do not have to carry or manually attach a tether item.
- **A tether is a bounded connection:** use a simple straight-line distance limit and server-enforced movement boundary. A rope renderer, sag, wrapping, and forces are deferred.
- **Pad shape is rectangular initially:** irregular cobblestone outlines are rejected rather than introducing a general polygon detector.

These defaults implement the authorized loop and can be revised independently as gameplay evolves.

## 2. First playable loop

1. Build a flat grass/dirt rectangle with a cobblestone border.
2. Place a pad control block on the cobblestone ring in any facing, inspect the build, give the pad a name, and register it.
3. Build a ship above the pad surface with its own floor, a controller, an engine, and at least one usable door or framed trapdoor exit.
4. Inspect the ship and fix highlighted leaks, missing equipment, obstructions, or unsupported contents.
5. Board and close every captured door and hatch, including interior doors. Contained mobs and items come along, whether or not the crew noticed them.
6. Launch from the ship controller. A loading screen covers the transfer to the void dimension.
7. Arrive inside the same ship. Containers, entities, and normal block interactions still work.
8. Put on a spacesuit and go outside while tethered. Opening a direct exterior door vents its room; using a correctly operated two-door airlock protects the main cabin.
9. Return and select a named pad from the ship computer. Transfer back if the current ship fits and that pad is clear.

The departure pad is the default landing selection. Landing at another registered pad is part of the same destination lookup, not another planetary simulation feature.

## 3. Pad construction and discovery

### Build rules

The pad consists of a flat grass-block and/or dirt interior surrounded by a closed cobblestone ring at the same Y level. The initial interior must be rectangular and no larger than 32 x 32. Holes, steps, broken borders, and ambiguous nested borders fail inspection.

Place the pad computer on top of any border block, including a corner, in any facing. Its supporting cobblestone remains in the ring. The computer itself is outside the interior launch columns and is never captured as ship equipment.

The supporting ring fixes the floor elevation. Adjacent grass/dirt identifies the interior without requiring coordinates or an inward-facing screen.

### Discovery algorithm

1. Read the supporting block exactly one block below the computer. Require cobblestone; never search downward through another floor.
2. Check the eight horizontal neighbors on that same layer for grass/dirt candidate seeds. Diagonal seeds support ring corners.
3. Scan four bounded horizontal rays from each seed to cobblestone, propose a rectangle, and validate every floor and ring block. Accept mixed grass/dirt interiors and reject holes or spans over 32 x 32.
4. Require the computer to sit on the candidate perimeter. Collapse duplicate candidates; if multiple valid pads touch it, ask the player to move to an unambiguous outer edge. Facing is cosmetic.
5. Reject overlapping same-height registrations. Different floor heights may share a footprint; associate the ship with the nearest registered floor below its helm and cap prelaunch ownership below the next registered overlapping pad.
6. Report the footprint and available build height, and offer independent cyan pad / amber ship previews fading over 30 seconds. Register only after validation and naming succeed. A stacked pad overhead still obstructs vertical travel.

Use fixed constants for the first materials and limits. A small surface predicate can recognize grass/dirt during discovery without becoming a material-tag or configuration subsystem.

The search is always bounded. It must not follow a connected grass field, excavate toward bedrock, or repeatedly load chunks beyond the permitted search area. If it cannot find a valid pad, leave the world unchanged and report the failure in the computer.

### Pad registration and destinations

A registration stores a stable pad ID, display name, dimension, control-block location, detected bounds, and elevation. Use unique names within the server for the first destination list; identity still comes from the ID so renaming does not break references.

All registered pads are discoverable as destinations initially. Do not add public/private controls, ownership screens, or an access-policy framework yet. Retain ordinary server interaction checks and transfer locks.

The destination list reports READY (loaded, valid, empty columns), OCCUPIED (valid surface with blocked columns), UNAVAILABLE (missing dimension/controller or changed pad), or UNKNOWN (terrain not loaded). These are sensor observations, not reservations. Listing must not load remote terrain. Revalidate the actual destination before landing, loading only its bounded area when needed; registration does not permanently force-load terrain. An occupied home pad becomes ready after departure when its terrain remains loaded.

### Clearance to the sky

The grass/dirt interior defines the launch columns. The border and its computer are outside those columns.

- On an empty pad, every block above the interior through the build ceiling must be air.
- With a ship present, discover the ship first and ignore its exact captured block mask. Any remaining non-air block in those columns is an obstruction.
- Continue above the 32-block ship-height limit to the dimension ceiling. A high bridge or canopy must still prevent launch.
- Glass, leaves, signs, and fluids obstruct clearance even when they transmit light. Sunlight and lighting heightmaps are not the clearance rule.
- Require the entire interior to be clear apart from the captured ship. Overhang beyond the interior is unsupported initially.

## 4. Discovering the ship and its contents

The ship controller is the block scan seed. It must be above the pad interior and inside its 32-block-high build volume. Use six-neighbor connectivity for structural discovery; touching only at an edge or corner does not connect two blocks.

Capture a sparse set of ship blocks. A bounding box helps inspection and cabin analysis, but is not permission to capture every block inside it.

- Exclude the pad plane, border, and pad computer. Grass blocks and dirt above the pad are explicit errors, not silently omitted hull.
- Require the ship to provide its own floor. The pad surface cannot act as a pressure boundary for a cabin that will leave it behind.
- On the planet, reject every disconnected non-air block in the launch columns through the world ceiling. In space, check through the connected hull height. Disconnected blocks higher in the allocation may remain behind when landing; the user accepts this debris limitation (B02). Never silently include a nearby build.
- If the connected structure crosses the footprint or height limit, fail instead of truncating it. A wing touching a building may accidentally connect that building; the preview makes the captured result visible.
- Check complete doors, paired containers, beds, and other supported multi-block arrangements. Partial capture is a preflight failure.
- Support ordinary cargo storage and our own equipment explicitly. For unusual blocks, active fluids, unstable blocks, or block entities whose transfer is not supported, report the exact unsupported case before changing the world.

Ship inspection runs structural and occupant/cargo preflight, the same contents validation used at transfer. Its success report includes captured blocks, engines, sealed compartments/cells, and controller air-source status without listing hidden stowaways. Successful scans mark hull blocks. Failures show coordinates and bounded particles at the error or along a controller-room leak path for five seconds. Pad inspection marks its bounds. Sensors and controls are gameplay, not optional debugging.

### Entities come with the ship

Include players and contained non-player entities. The first required non-player cases are a creeper, an animal, and dropped items, including their saved state and identity. Hostile mobs are not automatically removed, rejected, or revealed in the inspection UI; a stowaway should remain a possible hazard.

Validate every entity intersecting the tight ship bounding box against cabin pressure and complete bounding-box containment. A wholly exterior entity inside that rectangle can conservatively veto travel (accepted D06). Animals wholly outside the box or standing beside the pad stay behind. Unsupported decorations and attachments fail preflight; supporting-block capture is future work.

Validate passenger/rider graphs and attachments as a unit when supported. A leash to something outside the ship, an entity straddling the capture boundary, or an unsupported hanging/vehicle arrangement produces an actionable preflight error. Never delete or duplicate an entity to make validation succeed.

Freeze entity ticking only during the transfer transaction. Preserve relevant state, including a creeper's fuse state; do not let staging create an invulnerable or permanently paused hostile mob. Resume once the destination is authoritative and the affected crew can resume play.

## 5. Ship requirements, airtightness, and doors

### Minimum equipment

| Requirement | Initial validation |
| --- | --- |
| Ship controller | Exactly one active controller in the captured structure; its computer handles inspection and travel. |
| Engine | At least one connected, correctly oriented engine with an exterior, unobstructed exhaust. |
| Cabin | At least one airtight, standing-player-sized interior with its own floor and a usable path to the ship controls and door. |
| Door | At least one complete, usable door in a validated frame. Every captured door must be closed for launch and landing, including interior doors (D04). |
| Tether anchor | Use the ship controller as the initial anchor; derive its cabin-side air source from its facing/interaction position. No extra anchor block is required yet. |
| Contents | Players are aboard and entities/containers can be transferred without truncation or duplication. |

The initial engine is a functional construction requirement. Batteries, powered life support, and stored cabin oxygen are now implemented through the opt-in [systems extension](ship-systems-and-viewports.md). The [ship recipes](ship-recipes.md) implement shaped crafting from common vanilla materials, redstone components, machine casings, circuit boards, and computer bases. A development recipe-chest gallery is available through `/nullspace lab recipes`. Engine fuel and thrust budgets remain deferred. Adding resource requirements later should be a small explicit change unless a larger refactor is deliberately chosen.

### Cabin detection

Flood fill exterior air from a one-cell border around the ship bounds. Enclosed air not reachable from outside is a candidate cabin. Validate player-sized standing positions and usable doorways separately from air connectivity. Walking routes must handle stairs, half-block steps, and one-block elevation changes with body clearance and headroom. A stair is a traversable fitting, never a pressure seal. Ladders and swimming are not initial walking routes.

Pressure sealing is its own rule. Do not equate a block's light occlusion or any nonempty collision shape with airtightness. Start with full sealing cubes and full glass blocks. Panes, fences, stairs, and slabs are not pressure seals in this version. They can still be supported ship fittings when their transfer is safe.

The pad must not participate in this test: test the ship's captured geometry as if the ground had already been removed.

### Door special case

Support ordinary vanilla two-block doors as explicit pressure boundaries rather than requiring a new sealed-door block.

A door seals only when both halves exist, agree on orientation/state, are closed, sit flush with the hull opening, and have a continuous sealing frame at the sides, floor, and top. Check its facing and hinge geometry. Reject unsupported arrangements such as an unproven double-door seal rather than assuming they work.

A validated closed door seals both whole cells occupied by its halves (accepted D03). Opening it connects the adjacent air volumes. The solid frame is mandatory; an arbitrary closed door is not airtight. Invalid framing leaves a leak even when the door is closed. Directional door portals are not required for this version.

This does not require a general voxel-level gas solver. Validate the small set of supported door/frame arrangements explicitly, then use those results in room connectivity.

### Airlocks and depressurization

An airlock is built from a small chamber with two correctly framed doors. Operate them normally: close the inner door before opening the outer door, and close the outer door before reopening the inner door.

Doors are not forced shut by a suit check, and the game does not forbid a dangerous opening. If both doors open and connect the cabin to vacuum, the cabin depressurizes. Opening a single exterior door without an airlock does the same. Creeper explosions and other hull damage create leaks through the same mechanism.

Initially pressure is binary: sealed rooms are breathable and rooms connected to space are exposed. Closing the leak makes the enclosed room breathable again immediately. Real gas loss, finite reserves, pumping time, and pressure forces are deferred. FTL-style oxygen levels and replenishment are an intended later gameplay system. Keep compartment connectivity, doors, and redstone-driven door state meaningful now; this does not extend the supported transfer set to active contraptions.

Opening a door or breaking a seal must immediately invalidate any cached safe-room result. Recompute affected connectivity with bounded work; use an exposed/unknown state until a fresh result proves it safe.

## 6. Spacesuits, tethers, and drowning-style exposure

Every player in space remains associated with a ship tether, including while inside the cabin. The tether is a ship ID and a connection to that ship's controller, not a simulated rope or a carried item. Leaving or changing the ship connection requires a defined travel/landing operation; free untethered EVA is outside the initial scope.

Use a small fixed maximum distance from the anchor initially, with the exact distance set during the prototype. Choose a limit that reaches the whole permitted ship and leaves a useful exterior margin. The server prevents motion beyond that distance and reports why. If an exceptional teleport or falling state puts the player outside it, return them to a valid nearby tether position or their last valid position. Do not make that recovery contingent on the cabin still being breathable.

The distance limit is separate from oxygen protection. An unsuited player can open a door and step into vacuum within the tether radius; they then lose air and health. There is no invisible pressure boundary or automatic safe-room teleport preventing exposure.

### Initial oxygen rule

**Working assumption:** the tether is an oxygen connection. A complete spacesuit plus a live tether to a breathable controller-side cabin protects a player in vacuum. A tether alone is not a spacesuit, and a suit alone has no independent oxygen reserve in the first version.

There are no air tanks to fill, trade, consume, or persist yet. A disconnected or out-of-range tether cannot supply air. If the anchor's source room vents, the suited player loses that supply too. A separately sealed source room can continue supplying a tether when another compartment vents.

| Condition | Behavior |
| --- | --- |
| Player in breathable cabin | Recover breathable air normally; suit not required. |
| Player in vacuum, wearing complete suit, with a valid tether and breathable source | Maintain breathable air. |
| Player in vacuum without one of those protections | Drain air, then take repeated drowning-style health damage. |
| Suit is removed or tether supply fails | Reevaluate immediately and begin/continue exposure; no fresh air or grace reset from swapping equipment. |
| Hull breach or door opening vents a room | Occupants are exposed unless protected by a suit with a source that remains breathable. |
| Player closes the leak or enters a supplied safe space | Air recovers under the same consistent breathing rule. |

Share the breathing/exposure state with vanilla underwater behavior carefully. Being in space must not suppress actual drowning underwater, and Water Breathing must not make vacuum survivable. Prefer reuse of vanilla air and damage behavior where it fits, with a small dedicated vacuum check; keep the mod-specific difference explicit.

Check that modern food regeneration cannot indefinitely cancel vacuum damage. Start with drowning-style pacing and adjust only if needed to preserve the intended hazard. Creative/spectator exceptions remain explicit developer behavior.

Movement follows pressure (accepted D02): a complete suit automatically activates flight outside any breathable cabin, including inside a breached hull. Sealing the room restores gravity. Creative players also auto-fly outside breathable cabins; spectator behavior is unchanged. EVA uses bounded movement without inertia or tether forces. Local ship piloting is now implemented in [ship navigation](ship-navigation.md). It stops while crew are outside breathable cabins. Free drifting, orbit integration, and EVA between reference frames remain later work.

## 7. Block computers and the shared interface API

Both pad and ship controls use computer-like block interfaces. Shared presentation and action handling should be designed for reuse across the mod, but implemented first for these two real consumers.

Keep the common API small:

- An 80-column, 25-row character grid with a title, status/results area, and CLI-style actions. Arrow keys select actions and move the text cursor; Enter executes, typing focuses/edits the name or destination, and Escape exits controller screens. Ctrl+A, Ctrl+V, Home/End, Backspace/Delete, and Tab are supported. Mouse input is optional: action rows can be clicked, and fields can be clicked to place the cursor. Missing names/destinations and stale or out-of-range actions report visible errors.
- A server-owned terminal session tied to a real block, dimension, and interacting player.
- Typed actions and responses for inspect, name/register, launch, list destinations, and land.
- Reusable validation messages that can point to a block or region in the world.
- Updates while a scan or transfer runs, with disabled conflicting actions and clear completion/failure results.

### Accepted terminal foundation (user-requested extension)

The user requested a real terminal foundation for a future computer and subsequently confirmed the result works. This extension is accepted scope, not a deviation awaiting approval:

- `TerminalBuffer` owns exactly 80 x 25 cells, per-cell foreground/background and bold attributes, cursor state, wrapping, erase operations, and up to 200 lines of retained scrollback.
- Output accepts chunks with incremental ANSI parsing, including split escape sequences. The supported subset includes cursor addressing, movement, erase, colors, saved cursor positions, and cursor visibility. Output is single-cell printable ASCII; unsupported glyphs become `?`. The subset and limitations are documented in [terminal.md](terminal.md).
- `TerminalInput` separates committed text, keys, control codes, and cell-coordinate pointer events. `TerminalDevice` provides the application-independent input/display boundary.
- `ControllerTerminal` implements the pad/ship menus and name editor. `StreamTerminal` provides persistent output and a UTF-8/ANSI input callback for a future computer. It does not choose or implement a CPU, VM, OS, or remote-computer transport.
- `TerminalScreen` renders cells at fixed character spacing independently of window/GUI scale. It explicitly activates Minecraft's native SDL text-input service on initialization and releases it on removal. The earlier omission caused real typing to fail despite direct `charTyped` tests passing.
- Controller actions use typed packets and an expiring server-owned session bound to player, block, controller type, and dimension. Leaving range, changing dimension, replacing the block, disconnecting, or beginning travel invalidates it. The session catalog binds selected destination names to stable pad IDs; a rename cannot redirect a submitted selection. Development commands remain separately validated. See [terminal.md](terminal.md) for transport limits.
- In controller screens Escape closes the screen. In a generic computer terminal, Escape is input to the device and Shift+Escape closes the screen. Physical-key input does not synthesize text.

Pad- and ship-specific behavior stays in its controller application behind the shared terminal device. The terminal foundation is authorized; a scripting language, virtual computer OS, plugin registry, general widget framework, and access-control system remain future work unless requested separately.

No setup wand is introduced. Normal block placement and the computer screens provide the initial workflow. Suit equipment is separate from this preference for block-based construction controls.

## 8. Launch, landing, and transfer reliability

Travel is server-owned. Before launch or landing, inspect current world state again; a previous successful scan is only a preview.

### Transfer sequence

1. Resolve the source ship and selected destination. For landing, resolve a registered pad ID and verify its controller, surface, border, clearance, and current ship fit.
2. Reserve both locations and the ship's destination area. Lock edits, containers, machines, entity ticks, and competing travel requests for the transaction. Recheck the snapshot under that lock.
3. Write a durable transfer record containing a unique transfer ID, source/destination, supported block and entity snapshots, passengers, and recovery stage before removing anything.
4. Show the loading screen to participating players with progress such as Preparing ship, Loading space/destination, and Boarding. Handle disconnects and readiness timeouts without waiting forever or dropping players into incomplete terrain.
5. Stage the destination with blocks, containers, and non-player entities inactive and inaccessible. Restore state in a controlled order and validate it.
6. Remove only captured source blocks and transferred source entities, without drops or duplicate inventory access. Leave the pad, border, pad computer, and unrelated contents in place.
7. Move players, preserving local offsets, facing, health/air, identity, and existing inventories. Online passengers and transferred mobs retain orientation; seating and forced facing are unnecessary. Accepted B01 exception: a non-pilot passenger disconnecting during preparation of the first launch may rejoin with yaw/pitch zero. A disconnected pilot cancels travel. Restore pending arrivals for disconnected passengers; unsupported entity relationships still fail preflight.
8. Commit exactly one authoritative live ship. Move or recreate tether connections against that ship's current controller; do not refill air merely because a dimension changed.
9. Use the accepted D11 readiness heuristic: ten consecutive client ticks in the target dimension with the player chunk available. This does not prove every neighboring ship chunk has arrived. Release loading/locks after acknowledgments; cancel missing preparation acknowledgments after 200 ticks, and disconnect clients missing arrival acknowledgments after 200 ticks so reconnect can finish safely. Resume ordinary ticking and hazards.

Block snapshots include IDs/states and supported block-entity data, including cargo. Entity snapshots preserve identity and required state. Do not duplicate a player's inventory in the ship snapshot. Do not carry live object references from one dimension to another.

Initially reject unsupported scheduled ticks, active contraptions, fluids, or entity relationships before travel. Extend support case by case when it serves a real build.

### Recovery

Keep a small explicit journal with stages such as PREPARED, DESTINATION_STAGED, SOURCE_CLEARED, and COMMITTED. Each has an idempotent recovery action. World saves across dimensions and player records are not assumed atomic; journal writes must be durable and support safe replacement.

Two stored copies may briefly exist during staging, but only one can ever be live, tick, produce drops, or expose inventory. Quarantine both locations after a crash until recovery establishes ownership.

A failure before source removal leaves the original ship in place. A failure afterward must complete arrival or restore the source from the record. Rejoining players resolve through the same record so they cannot appear beside an old duplicate. Crash tests must cover blocks, cargo, mobs, dropped items, and players.

### Named-pad landing

List registered destinations in the ship computer and default to the departure pad. Reject unavailable, obstructed, overlapping, or undersized destinations before moving the ship. Stay safely in space on failure and show the relevant error.

Use a fixed local origin based on the destination interior's minimum X/Z and its pad elevation. Validate the entire current footprint before placing anything. Keep block orientation unchanged initially, and reject a landing that would place ship blocks onto the border/computer or exceed available height.

Landing transfers the ship's current state, including edits and new cargo, not its original launch snapshot. Pad registrations do not grant a permanent right to overwrite other builds or keep the departure pad empty after the ship leaves. Reserve pad space only for active operations.

## 9. Space dimension and simulated local travel

Create a void `nullspace:space` dimension. No terrain, ores, structures, ambient spawns, weather, starfield, visible planet, or reference objects are required for the first loop. Suppress environment behavior inappropriate to the intended void/vacuum setting. Existing passengers still tick and behave normally after arrival; disabling ambient spawning does not remove the creeper brought aboard.

Keep the ship as normal static Minecraft blocks. Walking, doors, containers, hull damage, and local collision use the ordinary world. Use normal gravity in sealed cabins and automatic suit flight in exposed cabins/EVA. Do not transform the ship's block coordinates to simulate flight.

Allocate a bounded local area per ship ID so a second arrival cannot overwrite another ship. Prevent travel between areas through ordinary movement; players remain within their ship's tether range. Support basic concurrent allocation and isolation without rendering other ships or building a multiplayer universe simulation.

Keep chunks active while needed by occupants or a transfer. Pause unattended areas rather than permanently force-loading all ships. If players build in space, restrict placement to the registered ship's size bounds; recompute membership and cabin safety on changes.

### Implemented reference-frame extension

The implemented navigation extension keeps Minecraft block coordinates separate from simulated local-space position, orientation, and velocity. Render surrounding objects relative to the ship using a transform such as `inverse(shipOrientation) * (objectPosition - shipPosition)`.

Crew on the same ship share its frame. Different ships need their own state; one global background offset would not work. The scene renderer combines native block meshes with the approved OpenGL compute renderer.

The completed first-loop gates did not require solar-system scenery, planets, asteroids, orbital mechanics, docking, or collisions across reference frames. Gravitational dynamics and docking remain later mechanics. No placeholder simulation framework was needed just to enter an empty dimension.

[Ship navigation](ship-navigation.md) adds third-person piloting within a local encounter space and helm-only jumps between planet and system addresses. Jumping changes the scene definition while preserving physical blocks and crew. Existing surface pads remain associated with Terra.

The selected viewport extension defines the presentation layer: stars, planets, moons, ships, and other space objects share an observer-relative scene and source-provider API. Block-built subjects use live remote regions; celestial subjects can use procedural/asset representations without giant block builds or planetary chunk loads. V4 in [the extension plan](ship-systems-and-viewports.md) covers their appearance, occlusion, scale, and consistency between observers/monitors. Full orbital dynamics and surface transitions remain separate from rendering those objects.

## 10. Data and server boundaries

| Responsibility | Minimum state/behavior |
| --- | --- |
| Pad control | Bounded discovery, name/registration, validation results |
| Pad registry | Stable IDs, names, dimension/locations, bounds, availability |
| Ship control | Connected block set, equipment, contained occupants, current location |
| Cabin analysis | Supported seal/door rules and breathable/exposed room results |
| Travel | Snapshot, reservations, journal, recovery, pending passenger arrivals |
| Suit/tether | Ship association, anchor/range, suit validity, source breathability, exposure |
| Computer interface | Shared terminal layout/session/actions/status for pad and ship controls |
| Client presentation | Inspection overlay, computer screens, loading screen, breathing/tether status |

Use ordinary world persistence and block-entity/item state where appropriate. Persist IDs, pad/ship records, and recovery data with schema versions. Persist a tether's ship association and recover its range/source checks after load; do not create a tank-data model that has no current gameplay purpose.

Clients request actions; the server computes pads, ship snapshots, breathing, damage, and travel results. Validate interaction range, dimension, controller existence, membership, and current transaction state. Rate-limit scans and travel requests. Public/private pad policy is deferred, not silently supplied by these interaction checks.

Read and mutate Minecraft world state on the server thread. Accepted D01 permits bounded synchronous scans, mutation, and full-save boundaries; these can pause server ticks. Destination loading yields between ticks. A cancellable per-tick scan scheduler is deferred. Off-thread pure calculations are optional only when profiling justifies the extra coordination. Keep screens/rendering in `src/client`; the gameplay must work on a dedicated server.

## 11. Implementation sequence and acceptance gates

Implement these phases in order, verifying their acceptance gates. The user has authorized implementation.

### Phase 1: Pad computer and discovery

Implement the smallest shared computer interface needed by a pad block. Discover the grass/dirt interior and cobblestone ring, show failures, and register a valid named pad.

**Gate:** a valid pad is detected from its build without a handheld tool; holes, steps, broken rings, ambiguity, overlaps, oversized pads, and missing controllers fail clearly. Empty terrain cannot trigger an unbounded scan. Renaming preserves the registration ID.

### Phase 2: Ship inspection, equipment, and pressure

Add the ship controller as the second computer-interface consumer and add the engine requirement. Discover blocks and contained entities within 32 x 32 x 32, validate the floor and equipment, and implement full-block seals plus the supported closed-door/frame cases.

**Gate:** the exact ship is captured with the pad excluded. A missing floor, leak, invalid door frame, open captured door, obstruction above the ship limit, or unsupported cargo prevents launch. A sealed cabin passes; a hidden creeper is eligible without being called out as a hazard by inspection.

### Phase 3: Void transfer and named-pad return in development

Create the void dimension and isolated ship areas. Implement the minimum snapshot, journal, locks, recovery, and registered-destination resolution. Use development commands until the survival rules are ready.

**Gate:** round trips preserve blocks, supported cargo, creeper/animal/item state, players, and edits. Different destination sizes and missing/blocked pads are handled safely. Crash injection at every journal stage yields one authoritative ship, cargo set, and entity set. Two ships cannot share an allocation.

### Phase 4: Breathing, suits, and automatic tethers

Implement the binary cabin model, ordinary dangerous door operation, drowning-style vacuum damage, complete-suit check, controller-sourced tether air, and the simple range boundary. Define survival respawn/rejoin behavior and recovery from falling before enabling normal launch.

**Gate:** a supplied suit protects a tethered EVA player; an unsuited player can make a dangerous exit and takes damage. A direct door opening or creeper hull breach exposes the right rooms. A correctly operated two-door airlock protects the main cabin. Closing a leak restores the agreed binary breathing state. No tanks, oxygen economy, rope physics, or exit interlocks are required.

### Phase 5: Complete the player-facing loop

Connect inspect, launch, destination selection, and landing to the computers and loading screen. Show cabin, breathing, and tether status without exposing transaction internals in normal play.

**Gate:** the full build/inspect/launch/EVA/land loop works in singleplayer and with two passengers on a dedicated server. Readiness failures and reconnects do not reveal an incomplete destination or duplicate a ship. The empty space dimension is sufficient to pass this gate.

The user has now selected the next extension: shared ship systems/power and reusable remote viewports for same-dimension cameras, ship presentation, and celestial surroundings. Follow [the extension's implementation sequence](ship-systems-and-viewports.md#11-implementation-sequence-and-gates). Finite oxygen consumes the shared system's power; it should not be implemented as a separate unlimited controller effect. These extensions do not retroactively change the acceptance gates of the completed first loop.

## 12. Verification plan

Use the existing JUnit, server GameTest, and client GameTest support as features are implemented. Test behavior and failure cases rather than mirroring implementation details.

| Area | Required evidence |
| --- | --- |
| Pad discovery | Grass-only, dirt-only, and mixed interiors; grass decay after registration; continuous rectangular ring/corners, controlled downward search, nested/ambiguous borders, size bounds, negative coordinates and chunk boundaries |
| Registration | Unique names, stable IDs after rename, missing/changed controller, overlapping pads, unavailable destinations, no unnecessary persistent chunk tickets |
| Capture | Own floor, excluded pad/border/computer, forbidden dirt/grass in ship, valid cobblestone hull above pad, disconnected builds, whole multi-block objects, 32-block height limit |
| Clearance | Transparent obstruction, fluid, bridge above the ship-height cap, correct ship-mask exclusion, no requirement to clear the border computer's column |
| Doors/pressure | Both door halves, frame, flush orientation/hinge, invalid double-door arrangement, closed/open transitions, panes not sealing, direct vent, two-door airlock, compartment isolation |
| Entities | Hidden creeper, animal and dropped item, saved state/fuse, bounding-box exterior veto retained, unsupported attachments rejected, passenger identity and no duplication |
| Transfer | Containers, edits, player offsets/health/air, no removal drops, changed/undersized landing pad, crash/restart at each journal stage, logout/rejoin and readiness timeout |
| Tether/breathing | Automatic association, complete suit, anchor room vent, isolated air source, range limit, exceptional teleport/fall recovery, suit removal, no fresh-air reset exploit, water/vacuum interaction |
| Hazards | Unsuited exit allowed and damaging, door vent and creeper breach dangerous, leak closure response, food healing and Water Breathing do not defeat vacuum |
| Multiplayer | Consistent terminal state, concurrent scans/travel, two passengers, one disconnecting client, two ship areas, unauthorized/stale action packets |
| Client | Useful computer failures and highlights, loading screen through readiness, void renders and cabin interaction works, breathing/tether status is understandable |
| Performance | Bounded synchronous scan/mutation accepted, maximum volume, bounded snapshot/packet sizes, chunk ticket cleanup, no unbounded terrain search |

Profile the bounded prototype before raising limits or introducing asynchronous scan infrastructure. Capture tick cost and transfer duration during those tests; do not invent performance guarantees in advance.

## 13. References

These are implementation references, not additional gameplay requirements. General Fabric docs may differ from the pinned 26.3 dependencies; inspect actual source signatures before coding.

- [Fabric networking](https://docs.fabricmc.net/develop/networking)
- [Fabric automated testing](https://docs.fabricmc.net/develop/automatic-testing)
- [Fabric 26.3 changes](https://fabricmc.net/2026/09/15/263.html)
- [Fabric rendering concepts](https://docs.fabricmc.net/develop/rendering/basic-concepts) and [world rendering](https://docs.fabricmc.net/develop/rendering/world), for the later surrounding-scene work

## Implementation and verification notes

The core implementations and requested review fixes are present. Accepted limitations are listed in the decision record. Automated evidence includes:

- 27 JUnit tests: 10 pad-scanner, 9 terminal-buffer, 6 controller-application, and 2 stream-device tests. Coverage includes dirt/mixed surfaces, bounded discovery, the 80th column, split ANSI sequences, scrolling, cursor/attributes, editing, and input encoding.
- Server GameTests cover hull/door/breach checks, compartment isolation with a manually operated airlock, actual survival vacuum/suit/tether behavior, stair elevation/headroom, authenticated sessions, pad availability, readiness timeouts, and underwater-to-vacuum air continuity. Exact current results are recorded in [local-verification.md](local-verification.md).
- A transfer test with two server players, chest cargo, an animal, a dropped item, and a creeper. It checks unique UUIDs, health, elapsed fuse ticks, player inventory, hull/cargo edits, blocked landing, return to a registered pad after its grass turns to dirt, and an offline passenger's pending destination.
- Failure injection at PREPARED, DESTINATION_STAGED, SOURCE_CLEARED, PASSENGERS_MOVED, and COMMITTED. Recovery runs twice to check idempotence; committed travel is retained. Inactive loaded entity sections are included in cleanup to avoid hidden duplicate UUIDs.
- A real graphical client test that checks native SDL text-input activation/release, text routed through Minecraft's keyboard handler, generic stream-device input and column-80/row-25 output, blank-name/destination feedback, registers a pad by mouse, edits its name using arrow keys, verifies its saved name after reopening, launches the generated reference ship from its terminal, acknowledges loading, arrives in the void, and lands at a named pad. Screenshots are produced under the client GameTest run directory.

Run `.\build.ps1` for JUnit/server checks and the JAR, followed by `.\scripts\Invoke-Gradle.ps1 runClientGameTest` for the graphical test. Run these sequentially: Loom's test tasks share generated run files. All test worlds are separate from `run/saves/NullSpace-Dev`.

The [block support guide](ship-block-support.md) and generated registry catalog now define the transfer set: ordinary building materials, onboard soil, storage/workshop/automation block entities, stored bees, settled redstone, and stationary pistons. Pending scheduled ticks/events, fluids, moving parts, and world-bound special block entities fail before mutation. Frame and cabin requirements remain. Recipes are implemented; see [ship recipes](ship-recipes.md).

Transfers run as one server task after client preparation and destination entity loading. Destination loading yields between ticks using expiring portal tickets; ship capture and block mutation are bounded synchronous work. Only one transfer runs per server. Per-tick breathing queries cache occupied room components. This is the accepted D01 implementation limit: maximum-size scan/mutation work is not split into a cancellable per-tick scheduler. Full-world saves at transaction boundaries can cause a visible server tick pause, covered by the loading screen. An earlier cold maximum-footprint/height scan measured 91 ms and the final review run measured 82 ms for a 5,644-block hull; that is a measurement on this machine, not a tick-time guarantee.

Opt-in local harnesses add two separate graphical client processes over loopback to a dedicated server and real JVM termination/restart against disposable saves. The dedicated server runs in the pilot test process using the Fabric test harness, not an integrated singleplayer server. These scripts are excluded from build/check and CI. See [local-verification.md](local-verification.md) for exact scenarios, commands, results, and limits. Large-server profiling and exhaustive combinations remain future verification; no single test matrix proves every hardware failure.

The first loop deferred orbit, rendered planets/stars, propulsion/reference-frame simulation, fuel/power/oxygen economies, rope physics, and pad access policies. Shared power, finite air, remote frames, and celestial presentation now have a selected next-milestone design in [the systems and viewport extension](ship-systems-and-viewports.md); they remain unimplemented. Orbital dynamics, rope physics, and pad access policies remain separate follow-up work.

### Registration fixes and development lab

Pad registration requires a nonempty destination name. The terminal now explains a missing name and focuses the input instead of silently ignoring Register. Actions support mouse clicks alongside arrow keys and Enter. Saved pad names are restored when reopening a computer, command validation failures report why the action failed, and request timeouts report missing responses. Registration creates the pad destination; ship registration happens on launch.

The development lab now includes a 20-plot walking gallery: eight launchable variants, four labeled repair examples, and eight registered 12 x 12 blank pads. Plots have a shared level walkway, entry stairs, labels, lighting, and supply barrels. Ready ships cover glass, cargo, airlocks, elevation/headroom, half-block fittings, multiple engines, and button-operated iron doors. A player's own test builds, renamed registrations, repairs, supplies, and departed ships persist.

`.\lab.ps1` creates/opens `NullSpace-Lab` under `run-lab/saves/`; fresh saves start at the gallery entrance. Existing saves receive the gallery on next launch without resetting their position. `.\playtest.ps1` also adds it without rebuilding plot 1. `/nullspace lab` visits the entrance; `/nullspace lab plot <1-22>` visits a numbered plot. The original generated reference ship remains available through `/nullspace lab reference`, and its original `ShipLab.ensure` fixture is unchanged for transfer tests.

The gallery loads a bounded candidate footprint, chooses a shared height above its highest world surface, and builds without clearing existing blocks. Registrations cannot overlap existing pads. A separate generation marker prevents repeat construction. See [test-gallery.md](test-gallery.md) for the map, expected inspection results, and user workflow. Gallery tests exercise real inspections, iron-door controls, airlock pressure, blank-pad states, and repeat-visit preservation; a graphical test exercises walking aboard and landing on a blank.

An additive annex supplies plot 21 Meridian, a spherical 32 x 32 x 32 ship with five decks, ten independently sealed side rooms, an entry stair airlock, four engines, and a bridge computer on deck 3. Alternating stair flights make every deck and room reachable without flight. The central stair hall shares air across decks; closed room doors isolate the side rooms when the hall is breached. Plot 22 is an equally large empty registered landing pad. `/nullspace lab sphere` visits the entrance. A separate persistent marker adds the annex to existing saves without rebuilding the original gallery or respawning an edited/departed sphere. Room names are labels for test spaces; finite oxygen and specialized room machinery remain deferred.

The sphere server scenario verifies exact maximum bounds, walking routes to every deck and room, pressure isolation and door venting, then transfers the whole ship to space and back to plot-size-matched ground with all ten cargo barrels and player inventory preserved. A separate graphical scenario walks through the airlock and stairs to the third-deck controller and runs inspection using the actual terminal.

Suit flight automatically activates while a fully suited survival player is outside a breathable cabin in space. Returning to a sealed cabin or removing a suit piece disables flight and restores gravity. Server regression tests check activation without input, suit removal/restoration, and cabin reentry.

Ground contact now allows stable walking in vacuum while retaining suit flight permission. Jumping or leaving a ledge activates flight automatically. This avoids a client/server loop where vanilla landing disabled flight and the server immediately forced it back on. The graphical systems scenario stands on a ship roof for 40 ticks without height/flight oscillation, then jumps and verifies automatic flight.

Creative players also activate flight automatically outside breathable cabins, without suit requirements. On cabin reentry, active flight turns off while creative flight permission remains available. Spectator flight is unchanged.

The computer screen now renders a reusable 80 x 25 terminal device rather than directly drawing a menu. Buffer, input events, controller application, generic byte-stream endpoint, and Minecraft renderer are separate. Native SDL text input is explicitly activated and tested. See [terminal.md](terminal.md) for the output protocol, editing behavior, and future-computer integration boundary.

## Review cycle: 2026-10-23

The user accepted D01, D02, D03, D04, D06, D11, B01, and B02. These are explicit requirements/limitations above. The D05, D07, D08, D09, and D10 fixes are implemented: stair/headroom traversal, shared contents preflight and diagnostics, pad states, stable destination identity, and authenticated typed terminal sessions. G01 now has additional local evidence from two real clients, seven physical JVM crash/restart points, readiness timeout paths, and native underwater-to-vacuum air continuity. The review baseline passed 27 unit tests and 17 server GameTests, followed by graphical and two-client scenarios. The gallery extension adds a server scenario that validates all 20 plots and a graphical walking/cross-pad landing scenario; the extension passed all 27 unit tests, 18 server GameTests, and both graphical scenarios. See [local verification](local-verification.md). The decision record and local-verification document record the evidence and remaining limits.

D09 distinguishes identity from display: a pad may change names, and another pad may reuse an old name. A terminal selection remains bound to the original pad ID. B01 concerns the narrow first-launch logout window described above; ordinary travel preserves player and entity facing and requires no seats.

Suffocation and compartment isolation remain core gameplay. Stairs must work without becoming airtight, inspection must check actual occupants/cargo, and controls must reject stale or unauthorized packets. Finite oxygen levels, replenishment, and more elaborate life support are a later FTL-style extension, not part of the current binary-air resource model.


## Powered systems and scene implementation

The [systems and viewport extension](ship-systems-and-viewports.md) now records the implemented shared bus, finite room air, separate helm/engineering terminals, door groups, GPU monitors, simulated space scene, and initial server-mapped damage, alongside remaining acceptance gaps. Legacy ships remain opt-in. Additive powered fixtures are plots 23-24 (`/nullspace lab systems`); earlier plots and player builds are preserved. The extension's acceptance gates remain authoritative; the scene renderer includes players while other animated entities and some block-entity visuals remain pending, and combat damage does not yet have a crash-atomic damage journal.

Equipment now opens local 80 x 25 diagnostic applications with live connection/power readings and explicit switches/actions. Generator and battery recovery uses Manual restart. Monitors offer Next camera and View feed, preserving the chosen channel in fullscreen video. Placed video uses the native world projection, including view bobbing and hurt/portal effects, to stay aligned with its block. See the extension's equipment table for controls and the local verification record for coverage.

Development-world preparation runs at the end of the server tick, after native player registration and pending travel-arrival correction. Fabric's JOIN callback only queues the player UUID. Teleporting from a saved space position into the lab during JOIN can register the same player against inconsistent worlds, causing repeated passenger-list rejection and a distance-manager exception on disconnect. This timing rule also applies to first-time planetary fixture placement; existing fixture and introduction markers still preserve player edits and subsequent login positions.


## Pad, hatch, and cargo extension ? October 25, 2026

See [pad previews and hatches](pad-previews-and-hatches.md) for facing-independent ring placement, stacked pad ownership, separate fading pad/ship previews, framed trapdoors and ladder exits, furnace transport, and actionable diagnostics. This supersedes earlier inward-facing-controller, door-only-exit, and container-only block-entity restrictions. Stacked pads still require a clear sky column for vertical launch.


## Expanded vanilla block compatibility ? October 25, 2026

The [support guide](ship-block-support.md) and [complete block list](ship-block-catalog.md) supersede earlier narrow block-entity and falling/redstone/soil/TNT exclusions. Decorative trapdoors may travel without sealing; usable airtight hatches retain their framing rules. The original journal source remains unchanged while hive flower memories are translated for the destination.


## Preset ships and ship beacons ? October 27, 2026

[Ship beacons and the shipyard](shipyard.md) add free preset purchases to registered landing pads. The ground beacon has its own terminal catalog; the ordinary pad computer remains responsible for registration. Six presets include core equipment, sealed cabins, engines and finite systems, with larger multi-deck room/stair layouts. Delivered ships use the ordinary launch, return, power, air and camera systems.

`/nullspace shipyard` is an operator-only, opt-in showroom with six registered example ships and three empty delivery pads. Visits preserve player edits. Delivery validates clearance, permissions and loaded entities, saves a resumable order before construction, and never replaces occupied terrain. Exact-size pads retain a clearance margin so their ground controllers cannot join the hull.
