# Atmosphere and cloud renderer

Planet skies are drawn by a physically-inspired atmosphere and a volumetric cloud layer, computed on the GPU, replacing Minecraft's sky and clouds wherever the custom planetary view is active. This document explains what the player sees, how the model is parameterized, how the passes fit together, and what is deliberately approximate. The code is [`render/compute`](../../src/client/java/com/nullandkale/nullspace/render/compute) (client), `model/optics/AtmosphereProfile`, `model/space/PlanetSky`, and the shaders under [`shaders/compute`](../../src/client/resources/assets/nullspace/shaders/compute) (`atmosphere_*.comp`, [`atmosphere.glsl`](../../src/client/resources/assets/nullspace/shaders/compute/atmosphere.glsl), [`space.glsl`](../../src/client/resources/assets/nullspace/shaders/compute/space.glsl)). The papers it draws on are listed in [the research notes](atmosphere-and-cloud-research.md), and the wider renderer is described in [planet computers and rendering](planet-computers-and-rendering.md) and the [compute renderer plan](space-compute-renderer-plan.md).

The implementation is original GLSL and Java, informed by Hillaire's atmosphere factorization and Nubis's cloud modeling and lighting. It does not import either renderer or reproduce all their features.

## What the player sees

- Skies have wavelength-dependent extinction, Rayleigh and Mie scattering, absorption, a multiple-scattering approximation, and the planet's own shadow. A low sun travels through more air and reddens naturally. The solar disk stays bright through clear air.
- Stars fade through atmospheric twilight and return above the atmosphere. In airless views (space, airless moons) stars are always visible.
- Clouds are bounded spherical layers of quantized cells, which keeps the blocky Minecraft look, with coherent coverage noise, wind, sunlight attenuation, self-shadowing and diffuse light from the sky. Rain increases coverage and thickness toward a full overcast, and thunder darkens the cloud interiors. The sun and stars are *transmitted through* the cloud density instead of being painted over a gray overlay, so an opaque cloud hides the sun.
- Above, inside and below a cloud layer use the same density field.

Settings are in **Video Settings > Space Graphics** and are local to the client. **Clear-weather clouds** toggles dry-weather clouds only: rain and thunder always bring clouds. **Cloud quality** sets 16 to 64 integration steps (Fast, Balanced and High use 24, 48 and 64). Turning clouds off keeps the atmosphere. **Planet space skies** restores Minecraft's native planet sky entirely.

## The model and its units

An [`AtmosphereProfile`](../../src/main/java/com/nullandkale/nullspace/model/optics/AtmosphereProfile.java) holds the planet radius, atmosphere height, RGB Rayleigh scattering, Mie scattering, RGB absorption, two density scale heights, ground albedo and cloud coverage. It rejects out-of-range values, so a corrupt definition cannot reach the GPU.

Everything is measured in *simulation scene units*, not in the far-off storage coordinates where ship chunks sit. The default profile (`AtmosphereProfile.temperate`) maps one atmosphere height to 100 km of optical distance, scaling the coefficients by `100 / height`, with Rayleigh and Mie scale heights at 8% and 1.2% of the shell height. That compresses a recognizable atmosphere around the game's small planets, and does not claim that a Minecraft block is a kilometre. Cloud base and top sit at 24% and 52% of the shell height, with cells 4.5% of it, exaggerated so the clouds are visible and traversable.

Celestial bodies can carry an optional `optics` compound in their space definition. The codec validates it, including agreement with the body's radius and atmosphere height, and old definitions use the temperate defaults, so no save migration is needed. The profile and planet center travel separately from the list of opaque bodies, so removing the standing planet's mesh from the sky does not discard its optics. An optical atmosphere is separate from whether the world's air is *breathable*.

## Flat terrain and altitude

Surface terrain is a flat Minecraft dimension, but the sky is the sky of a sphere. Getting the join wrong produced a real bug: a camera far from the world origin was treated as a point on a tangent plane, which put it hundreds of units above the sphere, outside the atmosphere. Daylight went black, stars appeared at noon and rain clouds vanished, all from one coordinate error.

The rule now is that on an atmospheric surface, **celestial position and altitude depend on the camera's native Minecraft Y only**. Walking across the dimension never climbs off the planet. 512 blocks above Y=65 span one atmosphere height, so the default cloud layer sits around Y=188 to 331, and aerial-perspective distances use the same conversion. Cloud coverage is anchored to native horizontal coordinates through the inverse surface rotation with the same scale and a slow wind. The planet's day rotation does not drag the cloud field across the ground, and changing position still changes the clouds nearby. Airless surfaces use the flat celestial frame too.

## GPU passes

| Pass | Size | Recomputed when |
| --- | --- | --- |
| Transmittance | 256 × 64 RGBA16F | The profile or shader changes; shared by all views |
| Multiple scattering | 32 × 32 RGBA16F | Same; includes isotropic higher orders and a ground-bounce approximation |
| Sky view (radiance and RGB transmission) | Two 256 × 128 RGBA16F images per view | Position, illumination, weather, cloud settings or cloud animation time changes |
| Native aerial perspective | The existing 24-slice volume-prefix atlas | The actual native opaque depth and current view |

The shared table cache keeps at most four profiles (about 544 KiB). Each viewport adds 512 KiB for its sky images. Camera rotation reuses the sky lookup, so a still camera costs almost nothing. Planet positions and eye offsets are subtracted in double precision before upload, because the coordinates involved are large.

- *Transmittance* integrates exponential density with quadratic spacing near the observer.
- *Multiple scattering* integrates unit solar illumination over 24 directions and estimates the later orders with a bounded geometric series.
- *Sky view* integrates atmosphere and clouds together. It splits rays at the cloud-layer boundaries so a long horizon ray cannot step over the whole layer. Lookups concentrate angular resolution near the horizon and interpolate radiance and transmission separately. Outside an atmosphere, rays that hit the shell integrate directly, so a small planet's limb is not lost in the angular lookup.

Sky radiance, the sun and cloud transport are combined in linear HDR before the existing display conversion. Terrain and glass use depth-limited aerial perspective through the existing optical-prefix interface, and sky pixels skip that extra segment so the atmosphere is never applied twice. Celestial hits are likewise processed once.

### Cooperating with Minecraft

[`PlanetSkyMixin`](../../src/client/java/com/nullandkale/nullspace/core/mixin/client/PlanetSkyMixin.java) suppresses the native sky when the custom view is ready. [`PlanetFogMixin`](../../src/client/java/com/nullandkale/nullspace/core/mixin/client/PlanetFogMixin.java) suppresses native environmental and rain haze but keeps the fog that is gameplay: chunk-distance fading, underwater, lava and powder-snow fog, blindness, darkness and boss fog. [`PlanetCloudMixin`](../../src/client/java/com/nullandkale/nullspace/core/mixin/client/PlanetCloudMixin.java) suppresses native clouds through both the normal and order-independent-transparency draw paths, and only while the custom view is ready. Minecraft's weather state, timers and precipitation are untouched. The renderer reads the state and does not simulate weather.

### One path, every view

The same compute path draws the main sky, independent terrain cameras, monitors and external ship views. Live weather is applied only when the viewed dimension is the client's own dimension, so a monitor showing another planet does not inherit local rain. Each view owns its images and history, and a change to profiles or cloud settings invalidates the history. No normal frame reads GPU images back to the CPU.

All seven compute programs reload as one candidate set (**F3+T**), so a compile failure in any atmosphere pass leaves the previous working set in place. Allocation failures, closing, dimension disconnect and shutdown release the images, and the OpenGL state scope covers their bindings. GPU reports list `atmosphere_ms` separately from celestial surfaces and volumes.

## Deliberate approximations

- A compact Hillaire-style solver, not a port. Multiple scattering is isotropic. There is no fitted twilight model, spectral integration, refraction or polarization.
- One dominant planetary atmosphere gets the detailed solver per view. Other distant shells use a bounded approximation, and overlapping detailed atmospheres are not supported.
- Atmosphere and clouds are lit by the primary emissive star. Secondary stars are drawn but do not add scattering.
- Clouds do not cast shadows onto Minecraft's terrain lighting. Native block and sky light stay Minecraft's.
- Terrain and glass use scalar transmission with RGB in-scattering, and sky transport uses RGB transmission. This is not a general spectral solver for interleaved media.
- Cloud coverage is procedural and anchored in simulation space, with bounded wind. Flat terrain remains a tangent patch rather than spherical terrain.
- Artistic solar exposure and twilight star-visibility controls remain, now fading with altitude, and star-disk colors are adapted toward a neutral white point so Sol's orange artwork does not make noon skies sepia. Star fields and disk colors are not photometrically calibrated.

These limits keep the renderer compatible with the existing scene model and OpenGL 4.3 compute pipeline.

## How it was checked

- [`AtmosphereProfileTest`](../../src/test/java/com/nullandkale/nullspace/model/optics/AtmosphereProfileTest.java): density decay, horizon reddening, planetary shadow, high-altitude sunlight, unit scaling, convergence, validation and serialization. [`SpaceDefinitionTest`](../../src/test/java/com/nullandkale/nullspace/model/space/SpaceDefinitionTest.java): optional optics, old definitions, airless bodies and mismatches.
- [`AtmosphereClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/render/compute/AtmosphereClientGameTest.java) runs the real GL passes. It compares 25 transmittance-table locations against an independent 8,192-step double-precision integral (the measured maximum error was 0.0005), and checks lookup reuse, shared tables, altitude invalidation, bounded transport, storm extinction, night darkness, airless invariance, native-depth aerial perspective, no double scattering, and failed reloads of each pass.
- The graphical suite writes a 16-tile reference atlas (clear noon, low sun, sunset, two twilight phases, midnight, clouds, rain, thunder, inside and above storm clouds, sunset clouds, an orbital limb, alternate scattering, airless daylight, high-altitude twilight) and a metrics file. Native terrain and glass are captured under clear and thunderstorm skies at a position far from the origin, because the origin hid the coordinate bug above. These are GPU renders, not external art.

On the development laptop GPU (RTX 3070 Laptop), at 960 × 540 in the native terrain scene, the atmosphere pass measured a p95 of 1.6 ms on frames where it changed, celestial surfaces 0.6 ms and volumes 1.5 ms. A populated space scene at 640 × 360 had volume integration around 7 ms median, above the original 4 ms tuning target. The views together use about 76 MiB of GPU memory. These are separate asynchronous pass timings, not frame times.

Retained images: [16-scene reference sheet](../images/atmosphere-cloud-reference.png), [native terrain and glass](../images/atmosphere-native-terrain-glass.png), [native thunderstorm](../images/atmosphere-native-thunderstorm.png).
