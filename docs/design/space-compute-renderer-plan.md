# Space compute renderer

Space, planets, gas and distant ships are drawn by OpenGL compute shaders running inside Minecraft's own context. This document explains why it is built this way, how the data reaches the GPU, the order of the passes, how the result is composed with Minecraft's native drawing, and where its limits are. The server-side data and authority are in [ship systems and viewports](ship-systems-and-viewports.md), the sky is in [atmosphere and clouds](atmosphere-and-cloud-renderer.md), the settings and terrain cameras are in [planet computers and rendering](planet-computers-and-rendering.md), and the edit loop for the look is in [the rendering workshop](space-rendering-workshop.md). The code is the client [`render/compute`](../../src/client/java/com/nullandkale/nullspace/render/compute) package and [`model/space`](../../src/main/java/com/nullandkale/nullspace/model/space).

## Why compute shaders

Space is not made of blocks. It holds spheres of different sizes, rough asteroids, a nebula and a comet's tail, seen from a camera that may be thousands of blocks from the thing it is looking at, with stars at every scale. Rasterizing that as meshes means giant triangle counts at huge distances. A ray-traced or ray-marched approach handles scale naturally: a planet is an analytic sphere, a rock is a bounded displaced sphere, and gas is an integral along the ray. Compute shaders do that inside Minecraft's existing context, with no second window, no external shader mod, and no change to how the hull, players and block entities are drawn. **Physical hulls and players stay rasterized**; only the celestial scene is traced.

## Startup and GPU selection

The renderer needs **OpenGL 4.3**. [`GlContextRequestMixin`](../../src/client/java/com/nullandkale/nullspace/core/mixin/client/GlContextRequestMixin.java) raises the SDL context request to 4.3 core before any window is created, covering utility, presentation and test windows, and keeps other profile flags. [`ComputeProgram.require()`](../../src/client/java/com/nullandkale/nullspace/render/compute/ComputeProgram.java) checks the actual backend, the 4.3 capabilities, image and SSBO bindings and the workgroup limit on the render thread, and an unsupported setup names the real device and how to switch to the legacy path.

Compute is on by default. **`-Dnullspace.compute=false`** keeps the original context and the raster celestial renderer, and needs a fresh process. Vulkan compute and automatic backend migration are not part of this. Requesting 4.3 does not choose the physical adapter, and a laptop can still start on integrated graphics. For this repo, [`scripts/build/gpu.ps1`](../../scripts/build/gpu.ps1) (inspect), `-Apply` and `-Restore` set Windows' per-user GPU preference for the repo's own `.tools` Java executables, preserving other settings and backing up the old ones. Windows gives that preference precedence over the NVIDIA control panel, so it is a preference verified after startup and not an API. Ordinary Java cannot export `NvOptimusEnablement`, so for an installed mod the player selects their launcher's Java in Windows graphics settings.

Verified on an integrated AMD adapter and an NVIDIA RTX 3070 Laptop GPU, both reporting OpenGL 4.3 and an exact integer compute result.

## The server contract

[`SpaceDefinition`](../../src/main/java/com/nullandkale/nullspace/model/space/SpaceDefinition.java) is an immutable, graphics-independent description of a scene, and [`SpaceDefinitionCodec`](../../src/main/java/com/nullandkale/nullspace/model/space/SpaceDefinitionCodec.java) validates a whole definition before the client replaces its active scene or allocates anything. The schema is version 2 (generator version 1). One scene unit is one block of length. CPU transforms use doubles, while GPU buffers hold view-relative floats, and physical storage-slot coordinates never enter celestial transforms.

| Data | Parameters |
| --- | --- |
| Scene | Stable id, revision, seed, generator, simulation epoch |
| Body | Id, kind, centre, linear velocity, radius, colour, emission, roughness, seed, atmosphere fraction, rotation axis and angular speed |
| Bounded volume | Id, centre, extent, velocity, colour, density, extinction, emission, phase, seed, procedural kind |
| Asteroid field | Id, finite extent, cell size, occupancy density, radius range, seed, colour |
| Frame | Definition id and revision, authoritative simulation time, authorized ship snapshots, camera and observer data |

Limits keep one view bounded: 64 explicit bodies, 8 emissive lights, 16 volume contributors (including derived atmospheres and tails), 8 fields, 8,192 candidate cells per field, 2,048 solid instances in all, and a 64 KiB encoded definition. Unsupported versions, invalid or duplicate ids, non-finite values, invalid sizes and excess contributors fail validation. An instance overflow fails outright and never drops occluders, because a missing planet that others can see would be worse than a refused definition.

The definition is sent on first authorized use or when its revision changes, and frames afterwards carry only the id, revision and time. Clients reject old definitions, and a frame that names an unavailable revision. On disconnect the caches are cleared (an atomic connection token carries cleanup to the next render-thread tick, because Fabric can report a TCP disconnect before Minecraft discards its task queue). Version 1 catalogs migrate once, keeping body ids, positions and parameters, adding a moving comet, a bounded nebula, an asteroid belt and planet atmospheres. Existing bodies are never regenerated on a visit, and viewing procedural content never creates terrain chunks.

The server also uses the same shapes: explicit bodies occlude weapon queries through [`SpaceShapes`](../../src/main/java/com/nullandkale/nullspace/model/space/SpaceShapes.java), including motion, rotation and the same bounded rough shape, and the GPU never decides damage. Field rocks are visual scenery for now.

## Acceleration and shapes

[`SpaceBvh`](../../src/main/java/com/nullandkale/nullspace/model/space/SpaceBvh.java) builds a balanced median hierarchy in Java. Each node is 32 bytes (std430) and each body record 80 bytes, with node bounds that cover roughness and the whole 50 ms motion interval shared by a frame. Views share one cached definition, its generated instances and its hierarchy, and each view packs its own camera-relative buffers.

Smooth bodies use analytic ray and sphere intervals. Irregular rocks use a bounded sinusoidal radial displacement with a conservative distance denominator, a conservative enclosing sphere and an iteration cap, and the CPU reference queries use the same shape. Very small projected rough rocks use a smooth silhouette. Materials add procedural land, polar caps, rock variation and an emissive sun. Direct light uses up to eight emitters with shadow rays through the hierarchy. Field cells have ids and seeds independent of the camera or storage slot, include rotation, and keep the whole rough shape inside edge cells. The finite local field is generated once per definition, as a bounded instance hierarchy and not a streamed infinite grid.

## Pass order

[`ComputeProgram`](../../src/client/java/com/nullandkale/nullspace/render/compute/ComputeProgram.java) loads GLSL 430 assets from resources. Surface and reconstruction programs use 8 × 8 workgroups and the volume integrator 8 × 4, with ceiling dispatch and edge checks. Only OpenGL 4.3 operations are used (no persistent mapping, bindless textures or subgroups). [`ComputeStateScope`](../../src/client/java/com/nullandkale/nullspace/render/compute/ComputeStateScope.java) restores the actual program and pipeline state, touched textures and samplers, SSBO bindings and ranges, and image bindings. Raw compute runs outside native render passes, and normal frames never read pixels back or call `glFinish`.

Each view keeps its images and history. Uploads replace buffers (`glBufferData`) so the driver keeps ordered in-flight work, and deleting a program or target keeps pending uses alive. A four-slot query ring polls availability before reading timings. Resource reload swaps in a whole successfully compiled shader set and invalidates history, and a failed set keeps the last working programs.

The world is drawn in this order:

1. Trace celestial surfaces into linear HDR radiance plus hit distance and object id.
2. Reconstruct them over time, then rasterize celestial depth and remote opaque ships and players, composed around the native local hull using native opaque depth.
3. End the native opaque pass through an owned delegating render pass. Copy opaque colour and depth into sampling resources.
4. Integrate every contributing medium together, stopping at the nearest opaque depth, and reconstruct the cumulative optical field.
5. Composite gas onto opaque colour in its own native pass, then resume native transparency.
6. Apply the same optical field at each glass or entity fragment's depth, in both of Minecraft's transparency modes, restore bindings, and draw the GUI.

A remote viewport follows the same solid, volume, transparent order into its own targets, and its block glass samples the shared field at the glass depth. Camera captures never capture monitor screens. [`WorldVolumeBridge`](../../src/client/java/com/nullandkale/nullspace/render/compute/WorldVolumeBridge.java), [`ScenePassBoundaryMixin`](../../src/client/java/com/nullandkale/nullspace/core/mixin/client/ScenePassBoundaryMixin.java) and the native shader adapters make up the boundary; compute never dispatches while the caller still owns a render pass, adapters run after Minecraft's SPIR-V conversion and keep native alpha conventions (including premultiplied order-independent accumulation), and extra samplers use raw units 14 and 15, restored afterwards.

## Gas and reconstruction

Nebulae use bounded procedural density, and atmosphere shells and comet tails derive from their parent bodies, with tails following the comet's velocity. The volume pass intersects bounds, skips empty intervals, merges overlapping extinction and emission, integrates front to back, and stops at negligible transmittance. Lighting is single scattering with a bounded phase function and coarse shadow checks (absorption, emission and single scattering, not multiple scattering).

The result is a **24-slice logarithmic cumulative field** of radiance and transmittance, sampled at any fragment depth, so gas never needs an invented surface depth. Outdoor gas is excluded from structurally sealed cabin cells, a purely visual mask that follows breaches and doors, while gameplay oxygen stays server-owned. Spatial reconstruction rejects samples hidden by nearby opaque geometry. Surface history reprojects the camera and moving or rotating bodies, rejects mismatched ids or depths and clamps to current neighbours. Volume history reprojects every depth layer and clamps against nearby values. Camera cuts, projection changes, scene revisions, reloads and long time gaps drop history, and each view owns its history.

Minecraft's framebuffer stays RGBA8. Compute works in linear HDR but is tone-mapped at the native boundary, and gas compositing linearizes native colour with a bounded radiance mapping. That is a hybrid policy and not a single HDR framebuffer with exposure for every native draw, which would be a larger backend change and is an accepted limit. Translucent remote player overlays keep the native material path.

## Consumers, defaults and quality

One implementation serves the main space view and EVA, fullscreen cameras, placed monitors and F2 capture. Quality comes from the player's **Space Graphics** settings (see [planet computers and rendering](planet-computers-and-rendering.md#space-graphics-settings)): the defaults are a 960-wide surface image, 1280 for cameras, volumes at half resolution with 16 steps, four spatial surface samples and temporal reuse on. Two placed monitors start at 320 × 180 and alternate capture slots after warmup so at most one advances per native frame. Main and fullscreen preparation goes before monitor work, and fullscreen viewing suppresses hidden placed captures. Invalid leases, lost power and stale sources follow the usual service rules.

Some JVM properties exist for diagnosis and tests: `nullspace.compute` (default `true`), `nullspace.compute.volumeScale` (overrides the volume resolution, 0.125 to 1), `nullspace.compute.noHistory`, `nullspace.compute.noJitter` (disable jitter and volume temporal reuse for reference checks) and `nullspace.compute.bruteForce` (reference solid traversal). Set them through `JAVA_TOOL_OPTIONS` for a temporary launch. Volume scale is read when a viewport is created. There is no adaptive-resolution controller, and the volume default follows measured integration cost.

## Camera alignment

Native view bobbing contains a translation, and an early version normalized the unprojected near-plane point relative to the original eye, so a small walking motion could swing the background by a large angle while the hull stayed still (the near plane is 0.05 blocks). [`SceneCamera.compose`](../../src/main/java/com/nullandkale/nullspace/model/optics/SceneCamera.java) now extracts the effective eye from the native view effects first, applies the shift in the ship's local frame, and transforms it into simulated space. The remaining view matrix keeps native bob, hurt and portal rotations with the translation removed, native opaque-depth reconstruction uses the same shifted origin, and the projection keeps native FOV, aspect and lens offsets, changing only the reverse-Z depth range. Vanilla view bobbing stays on, and hull and background move together.

## Limits

A single HDR native framebuffer, sparse imported density bricks, streamed infinite belts, persistent edits to individual asteroid instances, automatic quality adaptation, full step-count profiling and a cross-vendor visual study are not provided. The procedural volume contract does not pretend arbitrary density could be recovered from a seed. Orbital dynamics, planet descent, mining, hull physics, Vulkan compute and hardware ray tracing are separate systems. The measured pass times quoted in [the atmosphere document](atmosphere-and-cloud-renderer.md#how-it-was-checked) are separate asynchronous GPU timings and are not frame rates.

## Where this is tested

[`ComputeClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/render/compute/ComputeClientGameTest.java) verifies the real context and an exact integer result, state restoration and resumed native drawing. [`ComputeSceneClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/render/compute/ComputeSceneClientGameTest.java) checks actual GPU hit and optical images: odd sizes, the hierarchy against brute force across every pixel, an analytic planet, large origins, order independence of overlapping volumes, sealed-cabin exclusion and breach invalidation, history cuts, failed and successful shader replacement, a full resource reload, and released ownership. Pure tests cover schema limits, deterministic cells, packed layout, double-to-float precision, randomized accelerated-versus-reference rays, motion bounds, optical accumulation and history decisions. Native tests cover the definition's round trip, migration and malformed input, and a regression compares native and scene projections across walking phases, view directions, ship rotations and hurt and portal effects. The two-client test checks that each client renders its own view and releases GPU resources on disconnect.
