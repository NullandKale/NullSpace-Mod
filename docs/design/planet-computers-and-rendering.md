# Planet surfaces, computers, cameras, and air

Implemented behavior and limits, October 25, 2026. Validation results are recorded in [local verification](../development/local-verification.md).

## Accepted decisions

- A script-focused abstract runtime, without a physical CPU architecture. Bash-style and Python-style programs invoke the same native Java ship APIs.
- All normal ship actions are exposed, with existing power, connectivity, collision, recharge and transit rules.
- Normal jobs pause without a viewer or available equipment. Explicitly detached jobs persist and keep computing without loading chunks; world actions wait for a fully loaded hull and valid equipment.
- Spatial gas movement over the ship's 32 x 32 x 32 grid, with local refilling and visible flow.
- The Scanner reports local contacts; a System Upgrade unlocks distant destinations.
- Remote cameras load terrain without simulating its machines or entities.
- Planetary day/night rotation is included.
- One starter pad per planet is the default for the unanswered landing preference. Additional pads are player-built and registered normally.

## Computers and scripts

Craft and place a Computer Terminal, then right-click it. It uses the existing 80-column character display and real byte-stream input/output adapter. The [NullOS manual](../development/nullos.md) documents commands, both script syntaxes, native functions, execution limits, persistence and controls. Existing ship controller and equipment interfaces retain their roles.

Guest files and detached jobs are serialized into the Minecraft save. Files travel in ship metadata and its before/after journal; standalone consoles are adopted when their ship is registered. Guests have no API for host files, commands, sockets, reflection, native calls or Java evaluation. This is a custom language/runtime, with explicitly documented subsets of Bash-style and Python-style syntax, not compatibility with every Bash/Python program.

The runtime is cooperative and bounded at both computer and server levels. Native functions cover power, doors, equipment, cameras, scanners, weapon fire, bounded local flight and travel. An online crew member must board the ship controller for the existing travel-readiness handshake. Detached jobs can wait for that condition; they do not manufacture a fake player or bypass arrival safeguards.

## Scanners

One Scanner has three System Upgrade slots and uses the cameras/sensors bus. Enabled cameras occupy the first power slots; enabled scanners follow in position order, with one unit per device. Disabling hardware releases its slot. Unpowered scanners explain that fresh readings are unavailable.

The local scanner reports the current region, celestial bodies and bounded nearby ship contacts. With a System Upgrade, the Scanner also reports the five implemented planetary jump addresses. `ship.contacts()` and `ship.targets()` return structured data for scripts; `ship.scan("local")` and `ship.scan("long")` provide readable reports. Scanners do not expose another ship's private interior or force-load distant ships. Existing known ship controller routes remain available; scanners provide the sensor interface rather than hiding those previously known destinations.

## Planet surfaces and travel

| Address | Surface | Exterior air |
| --- | --- | --- |
| `sol_terra` | Existing overworld | Breathable |
| `sol_luna` | Flat stone and gravel | Airless |
| `sol_ares` | Flat stone and red sandstone | Airless |
| `vega_pelagos` | Flat stone, dirt and grass | Breathable |
| `vega_cinder` | Flat basalt and blackstone | Airless |

The four new surfaces end at Y64 and use native flat generation and vanilla materials. Each has its own saved dimension. First visiting its orbital region creates a clear starter pad if no surface pad/site already exists. Initialization is idempotent, searches for a clear area, and does not repair over player construction. A deliberately removed starter pad is not silently respawned. A conflicting starter name is rejected before terrain construction.

Jump to a planet through the ship controller, then use its landing destinations. Landing rejects pads on another planet before preparation. Launching from a surface restores that planet's local-space address. Physical travel uses the existing journal, passenger/cargo checks, chunk/entity readiness and client arrival handshake. Terra pads and existing worlds remain the home surface.

Airless surfaces use the same oxygen ledger and cabin/suit supply rules. Their gravity remains normal; automatic space flight and the space tether are confined to the space dimension. There is no seamless planetary descent, curved terrain, ore progression, planetary geographic map, or full climate simulation in this implementation. The administrator-only `/nullspace surface <address>` command can visit an initial surface for inspection; normal play uses the ship controller and pads.

## Shared space skies

Planetary backgrounds and ground cameras now use the space definition for that surface's local region. The standing body's orbital representation is omitted. A tangent surface frame rotates with Minecraft's solar angle, keeping the native day/night sun direction aligned with the celestial sun. A pure geometric test checks the full rotation. The native terrain renderer remains responsible for the real world and its depth.

Airless skies show the space scene directly. Breathable worlds add an analytic daylight/horizon blend. This initial blend is not a full spectral atmospheric-scattering solver. Surface and orbital views share body definitions; their initial terrain reference is a flat tangent patch anchored at Y65, rather than global latitude/longitude geography. The existing effective-eye correction keeps walking/hurt effects aligned with the background.

## Terrain cameras

Ground cameras subscribe to separate 16-cube terrain sections around the camera. This is independent of the player's ordinary chunk cache. A view requests at most 75 sections in a 5 x 3 x 5 neighborhood: 80 x 48 x 80 blocks, subject to vertical world limits. The server has a bounded 192-section cache, captures at most four sections per tick, and sends bounded section packets separately from the scene frame. Viewers receive up to four section updates and an estimated 128 KiB per tick. Source loading and packet preparation are incremental. Missing sections fill in as they arrive.

Section snapshots include block states, light, neighboring border states for face culling, sampled biome tint colors, and bounded visual block-entity update tags. Inventories are not copied as a general camera capability. Oversized visual tags are omitted. Meshes use vanilla block models and tint sources; fluid surfaces use Minecraft's fluid tessellator. Independent native feature submissions render supported visual block entities and generic entity poses. Players retain their existing full-body rendering path. A standalone Camera block can open a world feed; installed ship cameras retain bus and power validation.

Terrain tickets request loading only and expire after viewing stops. They do not request machine/entity simulation. Remote source data does not populate the player's normal chunk cache. Terrain, mesh, actor, packet, and view caches have bounds; unchanged section data keeps its revision. Dirty block borders invalidate neighboring section faces. Lighting is refreshed periodically.

Current limits: camera terrain is a bounded neighborhood, not infinite native render distance. Biome colors are sampled per section rather than blended per block. General entity snapshots currently carry type and pose, not every variant, equipment stack or animation state. Visual block-entity renderers that depend on uncaptured world neighbors may differ from native rendering; large update tags are deliberately skipped. Remote particles and every special native render layer are not fully reproduced. These are explicit rendering limits rather than claims of exact native-world capture.

## Space graphics and stable sampling

Open **Options > Video Settings > Space Graphics**. Fast, Balanced and High presets and individual controls are saved in `config/nullspace-space.json`. Surface and volume resolution, volume integration steps, spatial surface antialiasing, temporal reconstruction, optional stochastic integration, planetary skies and air particles can be changed without restarting. Main targets resize; monitor targets and compute histories are recreated when their settings change.

Surface rays are stable by default. Four spatial samples smooth celestial silhouettes; stars use pixel-footprint filtering and integer lattice hashing instead of animated binary subpixel samples. A numerical GPU test renders stationary stars repeatedly under default sampling and requires identical radiance. Volume resolution defaults to half the surface width/height, replacing the former one-eighth setting. Integration quality is adjustable, and centered sampling is the default. History retains its camera/scene/depth rejection rules.

Higher presets spend more GPU time and image memory. Tests record compute timing and resident image estimates; these are not whole-game FPS guarantees. The renderer still uses Minecraft's native framebuffer/composition path and the documented HDR limitations in the [compute renderer plan](space-compute-renderer-plan.md).

## Spatial air and particles

Upgraded ships now exchange gas across neighboring grid-cell faces. Pumps add gas at their outlet cells, crew consumes it locally, pressure differences move it through the room, and open exterior edges exchange gas with the planet atmosphere or vacuum. Updates conserve mass between cells and separately account for supply, use, escape and entry. They do not instantly average the whole connected room. Existing topology history preserves stored air after a breach and displaces/compresses air during construction.

Particles derive from the solver's actual flow records. Filling, internal equalization and decompression remain visible while flow is significant; effects stop at equilibrium or exhaustion. Small internal flows caused by crew breathing stay invisible even in underfilled rooms, so clouds do not follow players. Exterior leaks retain a lower visual threshold. Flow samples and particles are capped. A pump supplying an open breach can produce a continuing stream. The client air-particle preference affects visuals only.

The [visual-fix pass UNLINK  suppresses routine maintenance haze in full sealed rooms and actively clears owned particles when flow ends. A continuing vacuum leak remains visible. The same pass corrects default companion spacing and removes the pilot view's large letterbox.

This is a finite-volume pressure/density diffusion model over air cells, with exterior reservoirs at openings. It does not solve momentum, turbulence, temperature, or variable gas mixtures. The pressure and oxygen ledgers are currently one breathable-gas quantity. Legacy ships retain their existing opt-in upgrade behavior; upgrade at the engineering access to enable finite air, powered equipment and these flow effects.

## Verification

Unit tests cover script syntax, short-circuit side effects, isolation, budgets, fairness, persistence, local gas movement and conservation, plus celestial frame alignment. Native tests cover flat dimensions, starter-pad preservation, actual scanner power, computer session ownership/disk state, native device validation and the longer scanner menu packet. Graphical tests type and run both languages, exercise the file editor, render camera terrain/water/chests/entities, show the planetary sky/settings, and retain the existing gameplay suite. The local two-client harness additionally creates a guest file through the real console before travel and checks it after the ship returns.

Final counts, logs, screenshots and measured limits belong in [local verification](../development/local-verification.md). Player survival saves are not used as test worlds.

## Screenshots

The disposable graphical test drives real keyboard input and captures these views:

- [Python-style program and console](../images/computer-python.png)
- [Bash-style program output](../images/computer-bash.png)
- [Planet terrain camera, including water and native actors](../images/planet-terrain-camera.png)
- [Luna surface sky](../images/luna-surface-sky.png)
- [Space graphics settings](../images/space-graphics-settings.png)
