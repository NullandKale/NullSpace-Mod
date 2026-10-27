# Fast iteration on space rendering

Status: proposal for review. The workshop commands, screen and file watcher described here are not implemented yet. Existing compute reloads, graphics settings, GPU tests and scene rendering are the starting point.

The goal is to edit a shader or scene parameter, see the result in the running Minecraft client, and compare it against a repeatable reference without rebuilding Java or performing a launch-and-flight sequence. Rendering changes still need visual review; a passing numerical test cannot decide whether the space scene looks good.

## Use the real renderer

Build a client-side Space Workshop screen around `SceneViewport` and `SpaceComputeRenderer`. It uses the current OpenGL context and production shader/material code. It can open from the title screen using fixture data, without starting a server or opening a player save. A separate disposable in-world fixture exercises native sky replacement, hull depth, glass, terrain and entities.

An external renderer would miss Minecraft composition and duplicate integration work. The initial workshop therefore runs inside Minecraft. A future standalone tool can share definitions and shaders if useful, but is not a prerequisite for a fast edit/view loop.

Existing code already supports replacing a complete compute shader set and retaining the previous set on compilation failure. The missing pieces are focused authoring controls, source-file reloads, fixed shots, a small capture runner and saved comparisons. Full resource reload remains useful for native materials; it should not be required for every compute edit.

## One working session

1. Start a dedicated development client once with the proposed `render-lab.ps1` launcher.
2. Choose a named scene and camera shot in the workshop; freeze time.
3. Edit GLSL or a scene JSON file in the repo. A development watcher requests a reload after the file settles.
4. See compilation/schema errors on the preview overlay, with file and line information. Keep displaying the last valid shader set and scene.
5. Toggle baseline/current or capture both at the same camera, clock, history age, resolution and settings.
6. Save an approved image/parameter preset explicitly. Run only the relevant GPU/composition checks before broader validation.

Proposed local-only client commands are `/spaceworkshop open`, `scene <name>`, `shot <name>`, `time <seconds>`, `reload`, `baseline`, `compare`, `capture <label>` and `bench`. The title-screen entry and launcher provide equivalent access when no world is open. These are authoring tools, separate from ship computer commands and server navigation authority.

Java changes still require compilation/restart unless separately supported; GLSL, appearance parameters and fixture changes should not. Do not promise Java hot replacement as part of the shader loop.

## Files and reload behavior

Proposed layout:

```text
dev/space-workshop/
  scenes/          # small definitions validated by the production schema
  shots/           # camera pose, projection, clock and quality settings
  looks/           # versioned appearance-only parameters
build/render-workshop/<run-id>/
  captures/
  comparisons/
  report.json
```

The shader override points at the existing compute resource directory in `src/client/resources/assets/nullspace/shaders/compute`. Its four programs are `surface`, `volume`, `resolve` and `volume_resolve`; `space.glsl` is their shared compute include. Extend the current source-override loader to cover all four programs and preserve resource-pack/include behavior. Native composition shaders also depend on `src/main/resources/assets/nullspace/shaders/include/space_optics.glsl` and use the labeled full-resource-reload path. Use an explicit development flag and configured repo root. Disable filesystem watching and overrides in ordinary packaged play. These developer file reads are host-side tooling and grant no additional capability to NullOS guest scripts.

Read changed files off the render thread, debounce atomic editor saves and coalesce revisions. Queue GL compilation/linking on the render thread in the existing context. Publish a complete successfully linked shader set atomically, then invalidate affected histories. Shared includes must invalidate every dependent compute program. A malformed shader or scene keeps the last valid set; repeated identical errors do not flood chat or logs.

Compilation can still stall a frame. Measure and show reload latency instead of promising zero stalls. Native pipeline/material changes may need the full resource reload path, which the UI must label separately. Never launch another Gradle/test task as the reload mechanism or delete the running client's directory while iterating.

## Scene, appearance and gameplay data

Keep three inputs distinct:

| Input | Examples | Owner |
| --- | --- | --- |
| Scene definition | Body positions, radii, volumes, lights, IDs, seeds | Fixture author or authoritative server |
| Appearance profile | Star luminance/distribution, material palette, exposure, volume noise shaping | Client presentation, with validated bounds |
| Shot | Eye/rotation, FOV, aspect, fixed time, resolution, motion path | Workshop/test fixture |

Fixture scenes use the existing `SpaceDefinitionCodec`; do not create a second celestial schema. Workshop-only knobs cannot alter live collision, damage, discovery, atmosphere gameplay or authoritative body geometry. A shape change that affects collision needs the shared CPU/GPU contract and corresponding tests, not a client-only artistic override.

Begin by auditing representative captures before adjusting parameters. Assess star density, brightness distribution, planet scale and lighting, sun exposure, atmosphere edges, noise frequency, volume stepping and ship readability. A global exposure adjustment must not merely hide a broken optical/depth path. Prefer a coherent look preset to unrelated sliders spread through multiple menus.

The accepted visual target is **stylized Minecraft space**. Start with a restrained palette drawn from the game's blocks and environments, broad readable planet features, a clear light/terminator relationship, selected bright stars and coherent nebula shapes. Preserve stable edges and motion; deliberate style does not require jagged temporal artifacts. Keep spherical planets and finite perspective, with block-built stations and ships retaining native materials. Judge the scene through windows and at monitor resolution as well as in a fullscreen beauty shot. Other looks can remain comparison presets; the first approval baseline is the Minecraft style.

## Reference shots

| Shot | What it reveals |
| --- | --- |
| Sparse stars, no bodies | Star density, footprint filtering and exposure |
| Slow pan across stars | Shimmer, popping and history trails |
| Sun and planet at several distances | Scale, phase, terminator, limb, exposure and finite perspective |
| Two overlapping gas volumes | Banding, stepping, noise, order independence and convergence |
| Lit cockpit looking through glass | Opaque/translucent depth and gas composition |
| EVA beside a hull | Native camera effects, silhouettes, readable structure and depth |
| Ground day/night and horizon | Planet sky alignment, atmosphere and native terrain composition |
| Station approach and docked pair | Relative scale, port visibility and selected-target stability |
| Powered and dark derelict interiors | Lighting, exposed rooms and encounter readability |

Save camera bookmarks as data. Offer orbit/free camera, pause/step, reset history, fixed convergence age, and a short repeatable camera path. Use both stationary and moving shots: a single flattering screenshot cannot reveal temporal instability.

The synthetic workshop covers celestial and remote-view rendering. The in-world fixture is mandatory for changes to sky replacement, local hull occlusion, native translucency, day/night or terrain lighting. Both consumers must use the same look profile and production rendering path.

## Comparison and captures

Start with an instant baseline/current toggle; add a split wipe when the two target images can be composed without exceeding the view budget. Captures record the final presented image and metadata: scene/version, shader hashes, look, shot, clock, history state, quality settings, GPU and driver. The same command can capture an explicitly requested intermediate diagnostic.

Suggested diagnostics are body IDs/hit distance, volume transmittance, integration steps and history rejection. Add diagnostics where the renderer actually provides them; do not label guessed or unmeasured values as counters. Keep developer overlays out of normal ship monitors and graphics menus.

Readback is explicit capture work, not part of every frame. Use asynchronous completion where supported and report pending captures. Keep review baselines in an explicitly approved folder; changing a shader must not silently replace its expected images.

For repeatable comparisons, restore identical input state and render the same number of convergence frames. Numerical optical/depth checks should have tight tolerances; image comparisons across GPUs need documented tolerances and should not require bit-identical driver output. Artistic approval remains a human decision.

## Small launch and test scripts

Proposed tooling:

| Script | Purpose |
| --- | --- |
| `render-lab.ps1` | Start the persistent client directly in the workshop |
| `render-capture.ps1 -Scene ... -Shot ...` | Run one capture case and exit, using the same renderer |
| `render-check.ps1 -Suite stars|volumes|composition` | Run focused GPU cases instead of the full gameplay suite |

Add a dedicated Loom run/entrypoint with its own runtime directory and no automatic test-world cleanup of other sessions. For unattended capture, rendering still requires the actual GPU/window context. A celestial-only capture should not need an integrated server; native composition cases use their own disposable fixture world. Do not expose an unauthenticated network control port merely to reload a file.

On an already running client, parameter and source reloads should be interactive, with a target below one second excluding driver compile time. Cold startup time is measured separately. Each capture/benchmark writes to a new run directory and includes failures in `report.json`.

## Verification and implementation order

First implement the persistent preview, fixed scenes/shots, source reload and capture metadata. This is the smallest useful edit/view loop. Then improve one agreed baseline using side-by-side evidence, add focused motion comparisons and performance reports, and integrate the station/encounter shots.

Required checks cover invalid reload retention, include dependencies, history invalidation, frozen-clock reproduction, camera-path determinism, capture output/metadata, no player-save access in the title-screen mode, and cleanup of targets/watchers on close. Retain the existing GPU reference/state-restoration checks. Run native composition fixtures only when their affected paths change, and the full gameplay/multiplayer suite when shared protocol or simulation changes warrant it.

Record warm GPU surface/volume/resolve costs, native-composition timing scope, owned GPU memory, quality settings and reload latency. The previous 640 x 360 measurement spends about 6.1 ms on volume work alone on the tested RTX 3070 Laptop GPU; higher resolution is therefore an explicit cost, not a free quality fix. Optimizing appearance and reducing temporal artifacts should be judged alongside measured cost. No whole-game FPS claim follows from compute-only timings.
