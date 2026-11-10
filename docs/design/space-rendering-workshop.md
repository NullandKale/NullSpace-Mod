# Iterating on space rendering

How do you change how space looks and see the result quickly? This document describes the loop that works today and the **Space Workshop** that is proposed to replace the slow parts. The workshop is **not built**: the commands, screen, watcher and scripts below are a design, and are marked as such. The renderer itself is described in [the compute renderer](space-compute-renderer-plan.md) and the sky in [atmosphere and clouds](atmosphere-and-cloud-renderer.md).

The aim is to edit a shader or scene parameter, see the result in the running client, and compare it against a repeatable reference without rebuilding Java or flying to a place. A passing numerical test cannot decide whether a space scene looks good, so every change still gets visual review.

## The loop that exists

- **Shader reload.** The compute shaders live in `src/client/resources/assets/nullspace/shaders/compute`: the programs `surface`, `volume`, `resolve` and `volume_resolve` (plus the atmosphere programs), with `space.glsl`, `stars.glsl` and `atmosphere.glsl` as shared includes. Native composition also depends on `src/main/resources/assets/nullspace/shaders/include/space_optics.glsl`. After editing GLSL, copy it into the run's resources with `.\scripts\build\Invoke-Gradle.ps1 processClientResources processResources`, then press **F3+T** in the client. The reload swaps in a whole compiled shader set at once and **a failed compile keeps the previous working set**, so a typo cannot blank the screen. Java changes need a restart.
- **A development client** with a world to look at: `scripts/run/dev.ps1` opens the development world directly.
- **Settings** in Video Settings > Space Graphics let you change resolution, steps and quality while looking (see [planet computers and rendering](planet-computers-and-rendering.md#space-graphics-settings)).
- **GPU reference tests** (`ComputeClientGameTest`, `ComputeSceneClientGameTest`, `AtmosphereClientGameTest`) check the numerical behavior, and the graphical suite writes a reference atlas for the sky.

What is missing is a fixed set of shots, side-by-side comparison, automatic reload on file save, and a way to look at space without starting a world.

## The proposed workshop

A client-side screen built around the real `SceneViewport` and `SpaceComputeRenderer`, running inside Minecraft (an external renderer would miss Minecraft's composition and duplicate the integration work). It opens from the title screen on fixture data, with no server and no player save, and a separate disposable in-world fixture covers native sky replacement, hull depth, glass, terrain and entities.

**One session:** start the workshop client once, choose a named scene and camera shot, freeze time, edit GLSL or a scene file, let a watcher reload after the file settles, see compile or schema errors on the overlay with file and line while the last valid set stays on screen, toggle baseline and current at the same camera, clock, history age, resolution and settings, then save an approved image and parameter preset deliberately.

Proposed local commands: `/spaceworkshop open`, `scene <name>`, `shot <name>`, `time <seconds>`, `reload`, `baseline`, `compare`, `capture <label>`, `bench`. They are authoring tools and are separate from ship computer commands and from server authority.

### Inputs are kept apart

| Input | Examples | Owner |
| --- | --- | --- |
| Scene definition | Body positions, radii, volumes, lights, ids, seeds | The fixture author, or the authoritative server |
| Appearance profile | Star luminance and distribution, material palette, exposure, volume noise shaping | Client presentation, with validated bounds |
| Shot | Eye and rotation, FOV, aspect, fixed time, resolution, motion path | The workshop or a test fixture |

Fixture scenes would use the existing `SpaceDefinitionCodec`, with no second schema. Workshop knobs cannot alter collision, damage, discovery, gameplay air or authoritative body geometry. A shape change that affects collision needs the shared CPU and GPU contract and tests, and not a client-only override.

### Files, reload and captures

```text
dev/space-workshop/  scenes/ shots/ looks/
build/render-workshop/<run-id>/  captures/ comparisons/ report.json
```

The reload would read files off the render thread, debounce editor saves, compile on the render thread, publish a whole linked shader set atomically, and invalidate the histories it affects. A shared include invalidates every program that uses it, and a repeated identical error does not flood the log. The latency of a reload is measured and shown, since compilation can stall a frame. A native pipeline or material change would still need a full resource reload, and the interface labels the two paths separately. A reload never starts another Gradle or test task, and never deletes the running client's directory.

A capture records the presented image with the scene and its version, shader hashes, look, shot, clock, history state, quality settings, GPU and driver. Readback is explicit capture work and asynchronous where supported, never every frame. Review baselines live in an explicitly approved folder, so a shader change never silently replaces its expected images. For repeatable comparisons the same input state and the same number of convergence frames are used. Numerical optical and depth checks have tight tolerances, and image comparisons across GPUs have documented tolerances and are not bit-exact. Artistic approval stays with a person.

Proposed scripts: `render-lab.ps1` (start the workshop), `render-capture.ps1 -Scene ... -Shot ...` (one capture, then exit) and `render-check.ps1 -Suite stars|volumes|composition` (focused GPU cases). A control port for reloads is deliberately not proposed.

## The look: stylized Minecraft space

The accepted visual target is **stylized Minecraft space**: a restrained palette drawn from the game's blocks and environments, broad readable planet features, a clear light and terminator relationship, selected bright stars and coherent nebula shapes. Spherical planets and finite perspective stay, block-built stations and ships keep native materials, and edges and motion stay stable, since a deliberate style does not call for temporal artifacts. Judge it through windows and EVA and not only in a clean preview. Audit representative captures before adjusting anything (star density, brightness distribution, planet scale and lighting, sun exposure, atmosphere edges, noise frequency, volume stepping, ship readability), prefer one coherent look preset to unrelated sliders, and do not use a global exposure change to hide a broken depth or optical path.

### Reference shots

| Shot | What it reveals |
| --- | --- |
| Sparse stars, no bodies | Star density, footprint filtering, exposure |
| Slow pan across stars | Shimmer, popping, history trails |
| Sun and planet at several distances | Scale, phase, terminator, limb, exposure, perspective |
| Two overlapping gas volumes | Banding, stepping, noise, order independence |
| Lit cockpit looking through glass | Opaque and translucent depth, gas composition |
| EVA beside a hull | Native camera effects, silhouettes, depth |
| Ground day and night, and the horizon | Sky alignment, atmosphere, terrain composition |
| Station approach and a docked pair | Relative scale, port visibility, target stability |
| Powered and dark derelict interiors | Lighting, readability |

Camera bookmarks are data, with orbit and free cameras, pause and step, history reset, a fixed convergence age and a short repeatable path. Both stationary and moving shots are needed, because a single screenshot cannot show temporal instability.

## Measuring cost

Record warm GPU surface, volume and resolve times, the scope of native-composition timing, owned GPU memory, quality settings and reload latency. At 640 × 360 the volume work alone took about 6.1 ms on the tested RTX 3070 Laptop GPU, so higher resolution is a cost and not a free quality improvement. Compute-only timings never support a whole-game FPS claim.

## Order of work

First the persistent preview, fixed scenes and shots, source reload and capture metadata (the smallest useful loop). Then improve one agreed baseline with side-by-side evidence, add motion comparisons and performance reports, and add the station and encounter shots. Checks needed: reload retention on invalid input, include dependencies, history invalidation, frozen-clock reproduction, camera-path determinism, capture output and metadata, no player-save access in title-screen mode, and cleanup of targets and watchers on close.
