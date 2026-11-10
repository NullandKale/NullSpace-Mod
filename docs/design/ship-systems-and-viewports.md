# Ship systems, power and remote viewports

This is the architecture document for how a ship works as a system and how it is seen. It covers ship identity and devices, the shared power bus, life support, doors, how cameras and monitors show other places, how other ships and celestial bodies are drawn, and how ship-to-ship damage works. Each section says what is built and why, and what is still open. The narrower documents are [ship systems](ship-systems-redesign.md) (the hardware), [fuel and power](ship-fuel-and-power-design.md) (the numbers), [the block UI reference](ship-block-ui-design.md) (each screen), [ship navigation](ship-navigation.md) (flight) and the [compute renderer plan](space-compute-renderer-plan.md) (how space is drawn). The code is `gameplay/systems`, `gameplay/scene`, `gameplay/combat` and `model/power`, `model/air`, `model/space`.

## 1. Ship identity and devices

A ship has one stable id, a bounded build, equipment, rooms and its own simulation state. The Ship Controller is an interface to those systems, not their owner: destroying or depowering it never erases stored energy, resets cabin air or stops life support that is otherwise working.

- **Membership comes from structure.** Equipment joins the ship's bus by being part of its connected structure. There are no cables, voltage tiers or conductive hull materials. A disconnected device loses bus service, and keeps its own stored energy or fuel according to ordinary block and item rules. Detaching and replacing a device must never produce resources.
- **Discovery is separate from travel validation.** A ship with an open door, a damaged cabin or a missing engine still needs working controls and diagnostics, so systems are discovered independently of the stricter launch inspection.
- **Identity is established before first launch** and reused through launch, landing, controller replacement and recovery. A pad id is a destination identity and not a ship identity. Replacing the controller inside the same build never creates a fresh battery or fresh atmosphere.
- **Ambiguity is explicit.** If damage splits a ship, or two controllers compete, the result is a repair or claim step and never a silent merge of inventories or energy. A surviving controller anchors its bus. With no controller, the largest component containing supply and control hardware is serviced, and equal-size ambiguous components stay offline.
- **Every device has an identity**: a stable id, type, ship-local position, enabled state and condition. A copy placed as a new block gets a new id. Ids are reconciled against real blocks after edits and loads, so a cached membership is never permission to power a missing block.

After a controller is destroyed, validated surviving equipment inside that ship's bounded area keeps working, and detached equipment stays offline until membership is resolved. The search never follows hull connections into another ship or into terrain.

## 2. Power

`PowerBus` is the integer allocator, and the server owns it. Its terms:

| Term | Meaning |
| --- | --- |
| Generation | Sustained output of enabled, working generators |
| Battery | Stored energy, with capacity and charge and discharge limits |
| Requested allocation | The most power the player assigns to a subsystem |
| Delivered allocation | What is actually available after supply, damage and higher-priority subsystems |
| Actual draw | Energy used by the work done this tick. Never more than delivered |
| Hardware capacity | The most the installed working equipment can take |

One power unit supplies one energy unit per tick (20 per second). Each tick: membership and device conditions are reconciled; generation and usable discharge are pooled; subsystems are funded in priority order, capped by hardware; consumers tick against their budgets; storage discharges only for demand beyond generation, and charges from what generation is left; and one state revision is published with requests, delivery, draw, storage and the reasons for any unavailable service. The balance is always `generated + discharged = consumed + charged + curtailed`, and a battery never charges and discharges in the same tick.

**Brownouts shed deterministically and keep the player's settings.** The terminal can show `REQUESTED 3 / DELIVERED 1`, and delivery recovers when supply does, without anyone rebuilding the configuration. Default priorities are controls, life support, doors, cameras, then engines and weapons, and the player can reorder them or put air first.

**A blackout is recoverable without the powered computer.** Diagnostics stay readable (a passive local display), but powered commands need power. Generators and batteries have local **Manual restart** controls that re-enable supply and restore a control-power reservation, because needing the computer to restart the power that runs the computer would be a trap. A restart creates no energy. This is tested from an empty battery and from a player who deliberately allocated controls to zero.

Numbers (generator output, battery capacity, fuel costs) are in [fuel and power](ship-fuel-and-power-design.md).

## 3. Equipment

Each device has a small handler that reports identity, condition, supported commands, request, draw and output. There is deliberately no general plugin or automation framework. The computer calls the same command boundary that the terminal does, so it cannot bypass validation.

| Subsystem | Equipment | Loss of power |
| --- | --- | --- |
| Supply | Generator, battery | Generation stops. Storage keeps its energy |
| Controls | Ship Controller | Local diagnostics stay readable. Commands report no power. Other equipment continues |
| Life support | Air Supply | Refill stops. Air already in the room remains and is used or lost normally |
| Propulsion | Engines | Thrust and launch stop. A stale command produces nothing |
| Doors | Controller and framed doors or hatches | Remote movement stops. Doors keep their state and local operation |
| Cameras | Camera and monitor | Show no-power or stale. Never show the last image as live |
| Sensors | Scanner | Enhanced contact data stops. A hull you can see does not vanish |

Camera and monitor power follows enabled hardware and server ticks. It does not depend on client frame rate, screenshot size or the number of viewers. Sharing a feed does not duplicate a camera's draw, and disconnecting every viewer does not refund an enabled camera. An explicit disable reduces consumption.

An engine's local thrust direction is opposite its exhaust, and opposed engines produce no fictitious net acceleration. Per-engine on and off is a device control.

### Air

Rooms hold air quantity and volume (see [the air model](planet-computers-and-rendering.md#air)). The key rules:

- Life support output is a consumer of delivered power, and air use, leaks and door exchange are independent of power. A blackout stops *replenishment*, and never deletes a room's air.
- Splitting or merging rooms conserves air. Opening a door exchanges it, a breach drains it over time, a repaired seal stops the loss and does not refill the room, and filling an entire compartment with no recipient records the gas loss rather than hiding it.
- Breathability has a threshold (25%). Tether oxygen draws from a real supplied room, so the tether obeys finite life support.
- The pump refills at its rated output, and open planetary rooms refill from the planet's air.
- Unattended ships pause. No offline air, fuel or generation. Loading chunks for a camera does not by itself turn simulation on.
- Systems enforcement is **opt-in** for legacy ships through *Upgrade systems*, which needs a connected generator and Air Supply. A ship that has not been set up has no air and cannot take off, and a crew needs suits until it is. Nothing is silently inserted into an old ship.

## 4. Doors and engineering commands

The Ship Controller manages the ship's framed doors and trapdoor hatches (a door is one device identified by its lower half). It supports individual doors, named groups, **Close all doors** and **Open all doors**. These are server commands with a result for each door, not a client loop. Doors are queued one per tick under the delivered door budget, and a command completes only after the real world state is read back.

Results are `CLOSED`, `ALREADY CLOSED`, `NO POWER`, `REDSTONE OVERRIDE`, `MISSING/INVALID` or `CANCELLED`, with counts and failed locations for a batch. The game never reports the ship sealed because commands were sent. A door held open by redstone reports an override, and the game does not fight the signal. Close-all is a one-shot and not an interlock: it may close doors around crew, and open-all can expose an occupied room. Power loss, transfer, or losing the controller or session cancels unfinished work visibly, and it never resumes by itself. Compartment connectivity is recomputed after real door changes, including manual and redstone ones. Group membership belongs to stable door ids, so a replaced door does not inherit its predecessor's groups.

The engineering terminal (80 × 25) also takes typed commands. Requested power is 0 to 8 and actual delivery is capped by hardware and supply:

```text
power controls 1        power air 2          power doors 1
power engines 3         power cameras 2      power weapons 2
order 0 1 2 3 4 5       supply off           devices
device <8-char-id> off  doors list           group lock add <door-id>
doors lock close        doors <door-id> open doors all close
```

Priority numbers are controls 0, air 1, doors 2, engines 3, cameras 4, weapons 5, each used once. `rooms <page>`, `room here`, `air audit` and `profile cruise|combat|emergency` read rooms and set presets that respect the hardware and device switches, and a power preview refreshes without spending battery energy.

Every command carries a session and revision and a typed action. The server checks the player, ship membership, device, dimension, range, transfer state and power, and rejects stale configuration edits. Two crew get the same authoritative result, and packet ordering cannot double-spend energy or run a door command twice. Equipment menus cannot issue controller or general engineering commands.

## 5. Persistence and transfer

Ship id, device ids, allocations, priorities, enabled states, stored energy and fuel, atmosphere and simulated pose are saved. Block-local resources stay in the block, ship-wide state in ship metadata, and neither is duplicated, so every system has exactly one authoritative copy through journal recovery. Systems pause under the travel lock, and a committed boarding or travel assigns a passenger to one ship and removes stale crew entries elsewhere in the same journaled update. Transfer and recovery can never generate power, refill air, replay a command or move resources between duplicate copies, and camera leases and queued commands are invalidated when a source moves. The save format is versioned and existing builds survive.

## 6. One dimension, four coordinate spaces

All ships share the `nullspace:space` dimension, and each has a reserved region of ordinary axis-aligned blocks plus a separate simulated pose.

| Space | Controls |
| --- | --- |
| Storage coordinates | Real blocks, collision, entities, chunks, saves and transfer destinations |
| Ship-local coordinates | Equipment, rooms, crew offsets and stable references |
| Simulated coordinates | Where ships appear relative to one another, and how they move |
| Camera coordinates | One observer's position, orientation, projection and image |

**Chunks are never moved or rotated to make a ship turn.** The surroundings are drawn from the observer ship's simulated frame, so crew on different ships see different arrangements of the same physical dimension. Storage slots are 4,096 blocks apart at Y=96, with bounded reservations checked against occupied ships (older slots stay where they are). Unrelated sections and entities are filtered out of the main view. The reserved region covers the build and the permitted EVA envelope, so tether and building limits still follow physical ownership, and drawing a ship nearby does not make its blocks nearby: it has no local collision or hit targets at its apparent position.

Celestial bodies share simulated coordinates with ships. A planet can have a pose and a visible extent without occupying blocks, and its optional link to a landing world is separate from how it looks in space. Looking through a window never loads planetary terrain.

## 7. Viewports: seeing another place

A viewport renders a bounded scene from an independently specified camera pose, without a player entity and without disturbing the player's own view. The sources are a region of the same dimension, an isolated ship, a space object, or a composed scene.

```text
Request:  source id + revision + bounds, scene context (frame, time, allowed contributors),
          camera pose, projection, output size, refresh and priority
Frame:    GPU color + coverage, GPU depth + exact projection convention,
          source revision + capture pose + time, state (ready / loading / stale / denied / no power)
```

Both the camera pose and the *source extent* are explicit: a camera far outside a ship must load a region around the subject, not around itself. The output is a GPU texture. A screenshot is a deliberate, asynchronous readback, and live frames never go through the CPU.

### Getting distant data

The player's own chunk cache only holds a region around the player and cannot be used for a remote feed. A viewport acquires a **server-authorized lease** on a bounded remote-scene cache keyed by source and section, separate from the normal chunk cache, and consumers that match share one subscription. For a physical camera the server validates the real block, its power and the viewer's session. A client-supplied coordinate never authorizes streaming, and an operator-only `/nullspace viewport <x> <y> <z>` exists for development.

- Server snapshots hold block states and light, with no inventories. Data is palette and nibble encoded, and every frame is self-contained. Server source caches and client decoded-volume caches hold 64 entries.
- A late packet from an old revision, lease, transfer or world cannot update a viewport that reused its slot, and missing sections show loading and never an empty scene.
- Source chunks are loaded with expiring non-ticking tickets and never change the player's chunk center or request gameplay simulation. A camera does not create a fake player, move a real one or restart paused machinery.
- Leases, data and GPU resources are released on closure, loss of access or power, dimension change, disconnect, reload, or source removal.
- Each delivered camera unit services one enabled camera in stable order, and unfunded channels report no power.

Feeds refresh each second (20 ticks) with at most four subjects. A fullscreen feed is 640 × 360 and up to two nearby placed monitors render at 320 × 180. Monitors inside a captured scene stay ordinary block surfaces so two cameras facing each other's screens cannot recurse. Cameras also render players (up to 32 per source) through an independent native submission, using skins and worn gear, without creating fake entities. Non-player animated entities and special block-entity visuals (sign text, chest models) are not yet captured.

### Other ships and bodies

A ship is drawn from its cached geometry at its simulated position and orientation. With storage origin `O`, simulated position `P` and orientation `Q`, an observer camera at `C_A` aboard A with local orientation `R_A` has virtual position `V = P_A + Q_A·(C_A − O_A)` and orientation `R = Q_A·R_A`, and the camera for target B is `C_B = O_B + Q_B⁻¹·(V − P_B)` with orientation `Q_B⁻¹·R`. The blocks stay axis-aligned in storage and the simulated orientation gives them their apparent rotation. Depth must be converted between projections before it is compared, since near and far planes differ, and glass must never become an opaque rectangle. Calculations are camera-relative, subtracting the observer before GPU conversion.

Stars, planets, moons and the sun are separate scene objects with stable ids, poses, extents and revisions, owned by the server. They keep finite-body perspective, angular size, parallax and occlusion: a moon can cross a planet and a ship can pass behind one, and only the far star field is a direction-based approximation. Surfaces share a light direction and scene time, so phases agree between observers and monitors. A body has no block collision. Finite bodies have server collision proxies so a weapon cannot shoot through a visible planet.

## 8. Combat and hull damage

Blocks are the damage model: an impact destroys real hull or equipment, and the resulting geometry drives air and system membership. There is no single ship health bar, because that would hide breaches, broken doors, destroyed machines and isolated rooms. Local TNT and creeper blasts damage a ship like any structure, and a breach to vacuum drains the connected room over time. Equipment and compartments are reconciled after the blast's block changes.

The cannon is a powered ray resolved on the server, with a 40-tick cooldown. A hit is resolved in simulated coordinates with a broad phase over ship poses, then transformed into the target's local frame and tested against the real collision shapes. A hit at `H` maps to storage position `O_B + Q_B⁻¹·(H − P_B)`, and damage is applied through the normal block and entity path with attribution. An area blast fans out once to each simulated-nearby ship and is never forwarded recursively, and its block damage is confined to that ship's bounds. Each shot is limited to 128 geometry queries, and the arrival queue to 128 impacts. A full queue rejects fire before it happens, and an excessive blast fan-out reports rejection and never crashes the server. An impact on a ship under transfer quarantine is held against the ship's stable id and applied afterward, so it cannot damage whichever ship later uses that slot.

Open: accepted impacts are not yet crash-atomic across a process halt (do not claim exactly-once), burst overload needs dedicated coverage, and native bows and projectiles are not cross-frame weapons.

## 9. Rendering constraints

The secondary renderer uses Blaze3D render targets and the 26.3 pipeline, separating extraction from drawing. Minecraft's `GameRenderer` owns the main camera and state, so calling its render method again with a moved camera is not an isolated renderer. Each active view therefore has its own extraction state, culling, render target, projection and resource lifetime. Work is scheduled inside a GPU time and memory budget and refreshes only for visible or requested consumers. Rendering the whole dimension once per monitor is not acceptable, and neither is one offscreen render per star or body.

## 10. Status

| Area | State |
| --- | --- |
| Identity, shared bus, allocation, manual restart, door commands | Built and tested |
| Finite air, tether consumption, pumps, vacuum leaks | Built |
| Remote cameras, monitors, leases, ships and bodies in space | Built, with the capture gaps above |
| Cannon, mapped explosions, hull damage | Built, with the crash-atomicity gap above |
| Native transparency interleaving through several remote layers, adaptive detail | Needs acceptance coverage |
| Orbital movement, clouds on bodies, rings, terrain mapping | Not built (bodies have fixed poses) |
| Wiring, heat, reactors, powered gravity, portable oxygen tanks, hull collisions, stations spanning several simulation sections, cross-dimension terrain cameras | Separate designs |

Framed trapdoor hatches take part in pressure, discovery, groups and the open-all and close-all operations (see [pads and hatches](pad-previews-and-hatches.md)).

## Where this is tested

JUnit tests cover the power bus, allocation, room air transitions, frame transforms, projection and depth math, revision handling and protocol bounds. Server GameTests cover real devices, door operations, membership, leases and transfer persistence. Graphical tests cover offscreen content, monitor recursion, preservation of the player's own camera, alpha and depth composition, and image export. The development fixtures (`/nullspace lab systems`, `/nullspace lab plot 24`) provide two powered ships a fixed distance apart whose cannons face each other, and the physical crash and restart matrix runs against persisted system state ([local verification](../development/local-verification.md), [test gallery](../development/test-gallery.md)).
