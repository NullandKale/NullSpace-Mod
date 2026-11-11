# Planet surfaces, computers, cameras and air

This document covers five systems that grew together around the question "what can a ship do while it is parked on, or looking at, a planet?": the surface dimensions players land on, the in-game computer, scanners, terrain cameras that look at a place without loading it, and the finite-volume air model. It also covers the graphics settings for the space renderer. Related documents: [the galaxy](star-systems.md) (which bodies exist), [atmosphere and clouds](atmosphere-and-cloud-renderer.md) (what the sky looks like), the [compute renderer plan](space-compute-renderer-plan.md) and the [NullOS manual](../development/nullos.md).

## Planet surfaces

Every landable body on the [galaxy chart](star-systems.md) has a **surface dimension** drawn from a fixed pool (`surface_<type>_<slot>`), plus the Overworld for Terra. [`PlanetSurfaces`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetSurfaces.java) links a zone to its surface and back: `address(level)` says which zone a level is the surface of, `dimension(server, zone)` finds a zone's surface, and `surfaceOf` says whether a pad belongs to a zone. End pads belong to Terra's orbit, because ships reach the End only from Sol / Terra.

- **Landing needs the right planet.** A ship must jump to the body's region before it can land on one of its pads, and landing on a pad of another planet is refused before preparation. Launching from a surface restores that body's region.
- **Starter pads.** The first time a ship visits a surface's region, a 32 × 32 starter pad is levelled on the least uneven dry candidate near the origin, and registered (`ensureLanding`). The step is idempotent: it searches for a clear spot, never repairs over player construction, never respawns a pad a player deliberately removed, and rejects a name already used on another world before building anything.
- **Air.** Whether a surface's air is breathable is part of its planet definition (ocean and jungle are, the rest are not). Airless surfaces use the same oxygen ledger and cabin and suit rules as space. Gravity is normal everywhere, and the automatic space flight and tether work only in the space dimension.
- **Limits.** There is no seamless descent, curved terrain, ore progression or planetary map. `/nullspace surface <zone>` lets an operator visit a surface for inspection.

Physical travel to a surface uses the same journal, passenger and cargo checks and arrival handshake as every other transfer (see [independent ship actions](independent-ship-actions.md)).

## Computers

The **Computer Terminal** is a block with an 80-column console and a small virtual machine. The decisions behind it:

- **A script runtime, not a CPU.** Two language frontends, Bash-style and Python-style, call the same native Java ship API. There is no host shell, no real Python and no process. A guest program cannot reach host files, commands, sockets, reflection or native calls, so a script on a public server is as safe as any other game feature. These are documented subsets of both languages, and no claim is made to run arbitrary Bash or Python.
- **Everything a ship does is scriptable, with the same rules.** Native functions cover power, doors, equipment, cameras, scanners, weapon fire, bounded local flight and travel, and they obey the ordinary power, connectivity, collision and transit rules. A script cannot do something a player at the controls could not.
- **Bounded and cooperative.** Each computer and the whole server have instruction, call and memory budgets (see the manual for the numbers), and jobs yield instead of blocking a tick. Scheduling is fair across jobs.
- **Jobs pause without a viewer.** Normal jobs run while someone is watching and the hardware has power. A job started with `run --detach` is persisted with its position, variables and compiler version and keeps computing without a viewer or loaded chunks. World actions then wait until the whole hull is loaded and the equipment is valid. A computer never force-loads its ship, and a detached job cannot manufacture a player to satisfy travel's readiness handshake.
- **State travels with the ship.** Guest files and detached jobs are serialized in the save and in ship metadata, and so in the transfer journal. A standalone console is adopted when its ship is registered.

## Scanners

A Scanner has three System Upgrade slots and uses the cameras and sensors bus. Enabled cameras take the first power slots and enabled scanners follow in position order, one unit each, and disabling hardware releases its slot. An unpowered scanner says fresh readings are not available.

The local scan reports the current region, celestial bodies and bounded nearby ship contacts. With a System Upgrade it also lists other regions. `ship.contacts()` and `ship.targets()` give scripts structured data, and `ship.scan("local")` and `ship.scan("long")` give readable reports. A scanner never exposes another ship's private interior and never force-loads distant ships. Destinations the controller already knows remain available, and the scanner supplies the sensor interface without hiding them.

## Terrain cameras

A camera on a ship can look at a place the player is not standing in, such as a pad on the planet below. The player's ordinary chunk cache is not used for this, because it would force-load and simulate the remote area.

- A view subscribes to separate 16-cube **terrain sections**, at most 75 in a 5 × 3 × 5 neighbourhood (80 × 48 × 80 blocks, clipped by world height). The server keeps a bounded cache of 192 sections, captures at most four per tick, and sends bounded section packets separately from the scene frame, at most four sections and about 128 KiB per tick per viewer. Missing sections fill in as they arrive.
- A section snapshot has block states, light, neighbouring border states for face culling, sampled biome tint colours, and bounded visual block-entity update tags. Inventories are not copied, and oversized visual tags are dropped. Meshes use vanilla block models and tint sources, and fluids use Minecraft's own tessellator. Supported block entities and generic entity poses are drawn by independent native feature submissions.
- Terrain tickets request loading only, and expire after viewing stops. They never ask for machine or entity simulation. Unchanged data keeps its revision, and a changed border invalidates neighbouring faces. Lighting refreshes periodically.
- A standalone Camera block can open a world feed. An installed ship camera also needs bus power.

Limits, stated plainly: a camera sees a bounded neighbourhood and not native render distance. Biome colour is sampled per section and not blended per block. Entity snapshots carry type and pose only, not every variant, equipment stack or animation. Block-entity renderers that depend on uncaptured neighbours may differ from native rendering, and remote particles and special native render layers are not reproduced.

## Space graphics settings

**Options > Video Settings > Space Graphics** has Fast, Balanced and High presets and individual controls, saved in `config/nullspace-space.json` and applied without a restart. They cover surface and volume resolution, volume integration steps, spatial surface antialiasing, temporal reconstruction, optional stochastic integration, cloud settings, planetary skies and air particles. Targets resize and compute histories are recreated when a setting changes.

Stability is a default, not an option. Four spatial samples smooth celestial silhouettes, stars use pixel-footprint filtering and integer lattice hashing and not animated binary subpixel samples, and a numerical GPU test renders stationary stars repeatedly and requires identical radiance. Volumes default to half the surface resolution. Higher presets spend more GPU time and image memory. The tests record compute time and image sizes, which are not frame-rate guarantees, and the renderer still uses Minecraft's framebuffer and composition path.

## Air

A ship's air is a finite-volume model over its 32 × 32 × 32 grid ([`SpatialAir`](../../src/main/java/com/nullandkale/nullspace/model/air/SpatialAir.java)), not a room-average:

- Pumps add gas at their outlet cells, crew consume it locally, and pressure differences move it across cell faces. Open edges exchange gas with the planet's atmosphere or with vacuum. Gas is conserved between cells, and supply, use, escape and entry are accounted separately.
- A breach keeps the history of the air already stored, and construction displaces or compresses it.
- The model is pressure and density diffusion only. It has no momentum, turbulence, temperature or mixture of gases, and oxygen and pressure are one breathable quantity.
- Legacy ships opt in through the engineering access upgrade.

**Particles come from the solver's real flow records.** Filling, equalizing and decompression show while flow is significant and stop at equilibrium. Small flows from crew breathing stay invisible so clouds do not follow players, exterior leaks have a lower threshold so a continuing vacuum leak stays visible, and samples and particles are capped. The client particle preference changes visuals only.

More on the supply and demand model is in [fuel and power](ship-fuel-and-power-design.md).

## Where this is tested

Unit tests cover script syntax, short-circuit effects, isolation, budgets, fairness, persistence, local gas movement and conservation, and celestial frame alignment. Native tests cover surfaces, starter-pad preservation, scanner power, computer session ownership and disk state, and device validation. The graphical suite types and runs both languages, exercises the file editor, renders camera terrain, water, chests and entities, and shows the planetary sky and settings. The two-client test creates a guest file through the real console before travel and checks it after the ship returns.

Captures: [Python-style program](../images/computer-python.png), [Bash-style program](../images/computer-bash.png), [terrain camera with water and actors](../images/planet-terrain-camera.png), [Luna surface sky](../images/luna-surface-sky.png), [space graphics settings](../images/space-graphics-settings.png).
