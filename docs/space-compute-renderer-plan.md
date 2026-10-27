# Space compute renderer

Implementation record for the plan approved on 2026-10-24. The renderer runs in Minecraft's OpenGL context; celestial meshes are no longer required in compute mode. [Ship systems and viewports](ship-systems-and-viewports.md) still define travel, authorization, damage, power, and physical ship storage.

Next proposed work: the [space rendering workshop](space-rendering-workshop.md) provides a persistent edit/view loop, source shader reloads, fixed shots and image comparisons. Its accepted visual target is stylized Minecraft space. [Space encounters and docking](space-encounters-and-docking.md) covers sensor discoveries, visitable hulls and short airlock boarding transfers. Both documents are proposals for review, not implemented features.

[Atmosphere and cloud research](atmosphere-and-cloud-research.md) recommends Hillaire's atmosphere technique and Nubis clouds for a consistent sky, sunrise/sunset, and weather replacement. It distinguishes SIGGRAPH course material from research papers and records the separate immediate daytime-star visibility fix.

The subsequent [visual-fix record](visual-polish-2026-10-25.md) covers implemented Moon spacing, fullscreen pilot framing and air-particle shutdown independently of those proposals.

The [October 26 pad and sky fixes](pad-and-sky-fixes-2026-10-26.md) add brighter clear-day sunlight, live rain/thunder atmosphere shading, and weather isolation for space/airless views. The [fuel and power design](ship-fuel-and-power-design.md) remains a proposal for critique.

## Startup and GPU selection

`GlContextRequestMixin` changes the SDL context major/minor attributes in `GlBackend.createWindow` to **4.3 core** before window/context creation. This covers utility, presentation, and graphical-test windows. Existing profile flags remain intact. `ComputeProgram.require()` checks the actual OpenGL backend, 4.3 capabilities, image bindings, SSBO bindings, and workgroup limit on the render thread. Unsupported compute startup reports the actual device and how to select the legacy path.

Compute is enabled by default. `-Dnullspace.compute=false` preserves the original context request and raster celestial renderer. Changing this property requires a fresh Minecraft process. Vulkan compute and automatic backend migration are outside this implementation.

Actual Minecraft runs have verified OpenGL 4.3 and an exact integer SSBO compute result on both the integrated AMD adapter and the NVIDIA RTX 3070 Laptop GPU. The NVIDIA run reports **OpenGL 4.3.0 NVIDIA 610.62**. Requesting 4.3 does not choose the physical adapter.

The Windows helper targets only this repo's `.tools/jdk-25*/bin/java.exe` and `javaw.exe`:

```powershell
.\gpu.ps1           # Inspect preferences
.\gpu.ps1 -Apply    # Prefer the high-performance adapter for new Java processes
.\gpu.ps1 -Restore  # Restore the saved previous preferences
```

`-Apply` has been applied on this machine and verified through a fresh Minecraft launch. It sets the per-user Windows GPU preference, preserves unrelated settings, and backs up the prior values under ignored `.tools/gpu-preference-backup.json` (preserved by Gradle clean). It does not restart existing clients. For an installed mod, select the launcher's actual Java executable in Windows Graphics settings; the repo helper does not configure other JREs.

The C/C++ symbol is `NvOptimusEnablement`. Ordinary Java cannot export that symbol from `java.exe`; a JNI library is not a dependable substitute for a native launcher export. Windows now gives its application GPU preference precedence over NVIDIA Control Panel's preference. This is a preference, with the selected adapter verified after startup, rather than an unconditional device-selection API. Sources: [NVIDIA's Windows preference guidance](https://www.nvidia.com/content/Control-Panel-Help/vLatest/en-us/mergedProjects/nv3d/Setting_the_Preferred_Graphics_Processor.htm), [Optimus native export and startup policy](https://developer.download.nvidia.com/devzone/devcenter/gamegraphics/files/OptimusRenderingPolicies.pdf).

## Server contract

`SpaceDefinition` is immutable and graphics-independent. `SpaceDefinitionCodec` validates an entire definition before replacing the active client scene or allocating graphics resources. The NBT schema is version **2**, generator version **1**. One simulated scene unit is one Minecraft block-equivalent length. Coordinates and CPU transforms use doubles; buffers contain view-relative floats. Physical storage-slot coordinates never enter celestial transforms.

| Data | Implemented parameters |
| --- | --- |
| Scene | Stable ID, revision, seed, generator, simulation epoch |
| Body | Stable ID, kind, double center, linear velocity, radius, color, emission, roughness, seed, atmosphere fraction, rotation axis, angular speed |
| Bounded volume | ID, center, extent, velocity, color, density, extinction, emission, phase, seed, procedural kind |
| Asteroid field | ID, finite extent, canonical cell size, occupancy density, radius range, seed, color |
| Frame | Definition ID/revision, authoritative simulation time, authorized ship snapshots and camera/observer metadata |

Limits are 64 explicit bodies, eight emissive lights, 16 total volume contributors including derived atmospheres/tails, eight fields, 8,192 candidate cells per field, 2,048 total generated/explicit solid instances, and a 64 KiB encoded definition. Unsupported versions, invalid IDs, duplicate identities, nonfinite values, invalid sizes, excess contributors, and malformed contributor types fail validation. Instance overflow fails explicitly rather than dropping occluders.

`SceneService.Definition` is sent on first authorized use or a definition revision change. Subsequent frames carry ID/revision/time instead of copying the catalog. Clients reject old definitions and frames referencing an unavailable revision. Disconnect clears definition and geometry caches. Fabric can report a TCP disconnect on Netty before Minecraft discards its task queue: an atomic connection token carries cleanup to the next render-thread client tick instead of relying on that discarded queue. JOIN clears stale ownership for a new physical connection while preserving a seamless play/configuration transition on the same connection; late old disconnect tokens cannot clear a new session. Authorization for another ship's voxel snapshot or a camera lease remains separate from knowing celestial seeds.

Saved version-1 catalogs migrate once, preserving existing body IDs, positions, and parameters. The migration adds a moving comet, a bounded nebula, a deterministic asteroid belt, and planet atmosphere parameters. Existing bodies are not regenerated on each visit. New procedural content is metadata; viewing it does not create terrain chunks.

Explicit celestial bodies occlude server weapon queries using `SpaceShapes`, including motion, rotation, and the same bounded procedural rough shape. GPU buffers never decide damage. Field instances are currently visual scenery; interactive mining/collision and persistent edits to individual field rocks need a separate authoritative gameplay contract.

## Acceleration and procedural shapes

`SpaceBvh` builds a balanced median hierarchy in Java. Each node is 32 bytes in `std430`: minimum XYZ plus left/leaf index, maximum XYZ plus right index. The body record is 80 bytes: five aligned vec4s. Node bounds cover roughness and the entire shared 50 ms motion interval. Views share a cached immutable definition, generated instances, and hierarchy; camera-relative packing and GPU upload buffers belong to each view.

Smooth bodies use analytic ray/sphere intervals. Irregular rocks use a bounded sinusoidal radial displacement with an explicit conservative distance denominator, conservative enclosing sphere, bounded tracing interval, and maximum iteration count. CPU reference queries use the matching shape. Very small projected rough rocks use a smooth silhouette approximation. Materials add procedural land, polar caps, rock variation, and emissive sun detail. Direct lighting uses up to eight explicit emitters and BVH shadow rays.

Field cells have stable IDs and seeds independent of camera pose or storage slot. Generation includes rotation and keeps the complete rough shape inside partial edge cells. The finite local field is generated once per definition and accelerated with the other bodies. This implementation uses a bounded instance hierarchy rather than streaming an infinite cell grid. TinyBVH and native mesh builders are unnecessary for this scene and remain optional if triangle assets are introduced.

## GPU ownership and pass order

`ComputeProgram` loads GLSL 430 assets through Minecraft resources. Surface/reconstruction programs use 8 x 8 workgroups; the volume integrator uses 8 x 4 to improve small-capture occupancy. All use ceiling dispatch dimensions, and edge bounds checks. The code uses OpenGL 4.3 operations, without persistent mapping, bindless textures, subgroups, a second context, or another shader mod.

`ComputeStateScope` restores actual program/pipeline state, touched textures and samplers, generic and indexed SSBO bindings/ranges, and image bindings/access/format metadata. Raw compute work runs outside native render passes. Native resource copies stay outside the raw state scope so their state-cache updates remain consistent. Normal rendering has no synchronous pixel readback or `glFinish`.

Per-view GPU images and history remain resident. Uploads use buffer-store replacement (`glBufferData`) so the driver preserves ordered in-flight work; this replaces the proposed explicit staging-fence ring. OpenGL deletion semantics retain pending uses when programs or targets are retired. A four-slot timestamp-query ring polls availability before reading results. Targets close idempotently on viewport removal and disconnect; resource reload swaps a complete successfully compiled shader set and invalidates history. A failed shader set retains the last working programs.

The world path is:

1. Trace celestial surfaces into linear HDR radiance plus hit distance/object ID.
2. Reconstruct surfaces temporally, then rasterize celestial depth and remote opaque ships/players. Compose that view around the native local hull using native opaque depth.
3. End the native opaque pass through an owned delegating render pass. Copy opaque color/depth to separate sampling resources.
4. Integrate all contributing media together, stopping at the nearest opaque depth. Reconstruct their cumulative optical field.
5. Composite gas onto opaque color in a separate native pass, then resume native transparency.
6. Apply the same optical field at each glass/entity fragment's depth, including Minecraft's classic and improved transparency paths. Restore reserved bindings after the native world draw. Draw GUI afterward.

The remote viewport follows the same solid/volume/transparent order in its own color/depth targets. Its block glass shader samples the shared optical field at the glass depth. Native player bodies remain part of remote captures. Camera captures do not recursively capture monitor screens.

`WorldVolumeBridge`, `ScenePassBoundaryMixin`, and the native shader/program adapters implement the opaque/transparent boundary. Compute does not dispatch while the caller still owns an active GL render pass. Native fragment adapters run after the OpenGL SPIR-V conversion and preserve native alpha conventions, including premultiplied OIT accumulation. Extra native samplers use raw units 14/15, restored afterward, outside Minecraft's 12 tracked ordinary sampler units.

## Gas and reconstruction

Nebulae use bounded procedural density; atmosphere shells and comet tails derive from their parent bodies. Tails follow the comet's velocity axis. The volume pass intersects bounds, skips empty intervals, combines overlapping extinction/source contributions, integrates front to back, and terminates at negligible transmittance. Direct single scattering uses a bounded phase function and coarse shadow checks. This is an absorption/emission and single-scattering approximation, without multiple scattering.

The output is a 24-slice logarithmic cumulative field containing radiance and transmittance. Arbitrary fragment depth samples that field; gas has no invented opaque surface depth. Outdoor gas is excluded from structurally sealed cabin cells. That visual mask reacts to hull breaches and doors, while gameplay oxygen remains server-owned finite room air. Empty masks are omitted.

Depth-aware spatial reconstruction rejects samples hidden by nearby opaque geometry. Surface history reprojects camera and moving/rotating bodies, rejects mismatched identities/depth, and clamps history to current neighbors. Volume history reprojects each cumulative depth layer, checks opaque stops, and clamps against nearby current optical values. Camera cuts, projection changes, scene revisions, geometry changes where relevant, reload, and large time gaps reject history. Each view owns its own history; animated gas has a bounded history weight.

The native Minecraft framebuffer remains RGBA8. Compute intermediates are linear HDR, but their output is tone-mapped at the native framebuffer boundary. Gas compositing linearizes native display color and uses a bounded radiance mapping; this is a hybrid composition policy, **not the proposed single HDR framebuffer/exposure for every native draw**. That larger backend change remains a documented limitation. Translucent remote player overlays also retain their native material path; block glass has the explicit depth-aware gas adapter.

## Consumers and quality

The same implementation serves the main space view/EVA, fullscreen cameras, placed monitors, and F2 capture. Main/fullscreen surfaces begin at 640 x 360; two placed captures begin at 320 x 180. Existing image reuse and tick-rate animation limit repeated work. After initial warmup, the two installed monitors alternate capture slots so at most one advances per native frame. Main/fullscreen preparation precedes monitor work; fullscreen viewing suppresses hidden placed captures. Invalid leases, lost power, stale sources, and disconnect retain the existing service rules.

| Property | Default | Purpose |
| --- | --- | --- |
| `nullspace.compute` | `true` | Request/require OpenGL 4.3 and use compute celestial rendering |
| `nullspace.compute.volumeScale` | `0.125` | Volume width/height relative to surface image; allowed range 0.125..1 |
| `nullspace.compute.noHistory` | `false` | Disable temporal reuse for comparisons |
| `nullspace.compute.noJitter` | `false` | Disable subpixel/stochastic jitter and volume temporal reuse for reference checks |
| `nullspace.compute.bruteForce` | `false` | Reference solid traversal for tests/diagnosis |

These are JVM system properties. For a temporary PowerShell launch, set `JAVA_TOOL_OPTIONS` before launching the client and restore its previous value afterward. Volume scale is selected when a viewport is created. There is no adaptive-resolution controller or quality UI in this implementation. The lower default volume resolution follows measured integration cost; opaque depth and the surface image retain their own resolution.

## Acceptance and evidence

`ComputeClientGameTest` verifies the real context and an exact integer compute result, checks state restoration, and resumes native world drawing. `ComputeSceneClientGameTest` checks actual GPU hit/optical images, not just dispatch counters: odd dimensions, BVH versus brute force across every pixel, an analytic planet hit, nonzero body record indices, large origins, overlapping-volume order independence, sealed-cabin exclusion and breach invalidation, camera/FOV history cuts, failed/successful shader replacement, a full Minecraft resource reload, and released view ownership.

Pure tests cover schema limits, immutable ownership, deterministic cells and edge bounds, explicit overflow, packed BVH layout, double-to-relative-float precision, inside/grazing rays, thousands of randomized accelerated/reference rays, motion interval bounds, rotating procedural shapes, optical accumulation, logarithmic depths, and history decisions. Native GameTests cover NBT round-trip/revision validation, migration preservation, malformed and oversized definitions, and the existing gameplay suite.

`SystemsClientGameTest` retains real equipment interaction, powered camera leases, player-pixel comparisons, image export, travel, EVA and vacuum-floor checks. It adds a native glass fixture and exercises both transparency options. The loopback harness checks that two real clients render their own views, receive identical definitions/revisions, observe a scene edit from distinct player poses, and release GPU/definition ownership on disconnect. Its existing remote-chunk TNT/furnace and stale-system-write checks remain relevant.

The populated timing fixture contains a sun, planet/atmosphere, moon, rough rock, nebula, two four-deck 32-cube hulls, a moving camera, and two independent 320 x 180 captures. It records asynchronous surface, surface reconstruction, and volume/integrated-reconstruction GPU timings, CPU surface submission, adapter/driver, sizes, and resident-byte estimates after warmup. Those GPU timings exclude native hull rasterization, final color composition/copies, GUI, and the rest of Minecraft; they are not overall FPS. Allocation estimates cover renderer images, staging, and a conservative buffer capacity, excluding driver/compiler caches.

Final run paths, measurements, screenshots, and gate status are recorded in [local verification](local-verification.md#space-compute-renderer). The original 4 ms main / 2 ms monitor / 128 MiB goals are tuning targets, not guarantees on arbitrary machines or dense scenes. Where measured scope differs from a gate's original target, the record states it explicitly.

## Remaining boundaries

A single HDR native framebuffer/exposure, sparse imported/edited density bricks, infinite streamed asteroid belts, explicit per-instance persistent asteroid edits, automated quality hysteresis, full ray/step profiling counters, and a cross-vendor visual-quality study are not supplied by this implementation. The initial procedural volume contract does not pretend that arbitrary imported density can be recovered from a seed. Storage transforms and authority remain independent of rendering quality.

Orbital dynamics, planet descent/terrain generation, mining progression, docking, ship rigid-body physics, Vulkan compute, hardware ray tracing, and path tracing remain separate systems as in the approved scope.

Implementation references are the pinned 26.3 sources in `.gradle/loom-cache/minecraftMaven`, the client `scene/compute` package, [OpenGL 4.3](https://registry.khronos.org/OpenGL/specs/gl/glspec43.core.pdf), [compute semantics](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_compute_shader.txt), and [memory barriers](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glMemoryBarrier.xhtml).

## Ship navigation integration

[Ship cameras and navigation](ship-navigation.md) reuse this renderer for the external piloting camera. Server snapshots carry simulated poses at 10 Hz during piloting; shared client interpolation supplies consistent transforms to native cockpit composition, independent cameras, and compute occlusion. Each planet destination has its own persisted definition. Helm fast travel changes that definition and waits for the destination scene to prepare; thrust never switches regions. No second OpenGL context or planetary terrain rendering is introduced.

## Native camera alignment correction

Walking in a launched ship exposed a camera-origin error: native view bobbing includes a translation, while compute rays normalized an unprojected near-plane point relative to the original eye. With a 0.05-block near plane, a small walking translation could swing the background by a large angle while the native hull remained stable.

`SceneCamera.compose` now extracts the effective eye from the native view effects before constructing the camera-relative scene matrix. The eye shift is applied in the ship's physical local frame and then transformed into simulated space. The remaining view matrix retains native bob/hurt rotations and portal distortion, with its translation removed. Native opaque-depth reconstruction uses that same shifted origin. The scene projection retains native FOV, aspect and lens offsets, changing only the reverse-Z depth range. Vanilla view bobbing remains enabled and the hull and background receive matching camera motion.

Regression coverage compares native and scene projections across walking phases, view directions, ship rotations, hurt/portal effects and both clip-depth conventions. A numerical GPU fixture checks a stationary planet against native-camera reference rays over twelve walking-bob phases; existing graphical scenarios still exercise native composition, glass, resizing, monitors and travel.

## Planet skies, terrain cameras, and user graphics controls

The [planet/computer/rendering implementation record](planet-computers-and-rendering.md) supersedes the earlier fixed-resolution and no-quality-UI limitations. Native world skies and ground feeds now use the local-space environment; camera terrain is streamed as bounded independent sections. Graphics controls are under Video Settings > Space Graphics and persist locally. Stable rays, spatial surface sampling, filtered integer-hash stars, higher volume resolution and adjustable integration quality replace the earlier animated-surface-jitter/one-eighth-volume defaults. The record states the surface atmosphere approximation, terrain capture limits, actual GPU evidence and remaining native-rendering boundaries.

## Atmospheric scattering and replacement clouds

The [atmosphere/cloud implementation record](atmosphere-and-cloud-renderer.md) supersedes the earlier gradient atmosphere and gray-weather blend. Planet optical profiles, shared transmission/multiple-scattering tables, camera-specific sky transport, depth-limited aerial perspective and volumetric Minecraft-style clouds now use the same solar illumination. Native clouds and environmental haze are replaced when the custom planetary compute view is ready. The record documents quality controls, backward-compatible data, GPU references, regression coverage and the remaining approximations.

The World 5 follow-up corrects flat-surface coordinates: native height controls altitude, horizontal travel no longer places the camera outside the spherical atmosphere, and cloud density follows the native ground frame. Rain and thunder always enable overcast clouds, including when optional clear-weather clouds are disabled. `dev.ps1` now opens World 5 directly. See [the flat-terrain diagnosis and iteration workflow](atmosphere-and-cloud-renderer.md#flat-terrain-altitude-and-rainy-weather).
