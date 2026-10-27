# Atmosphere and cloud renderer

Implementation record, October 26, 2026. This replaces the planetary blue-gradient/gray-rain sky described in the [research proposal](atmosphere-and-cloud-research.md). The implementation is original GLSL/Java informed by Hillaire's atmosphere factorization and Nubis's cloud modeling and lighting ideas. It does not import either renderer or claim to reproduce all their features.

## What changes in game

Planet skies have wavelength-dependent atmospheric extinction, Rayleigh and Mie scattering, absorption, a multiple-scattering approximation, a planetary shadow, and altitude-dependent views. Low sunlight travels through more air and reddens naturally. The solar disk remains bright through clear air. Stars fade through atmospheric twilight and return above the atmosphere; airless views retain their stars.

Our volumetric clouds replace Minecraft's clouds while the custom compute sky is ready. Clouds use bounded spherical altitude layers, quantized density cells for the Minecraft style, coherent coverage noise, wind, sunlight attenuation, self-shadowing, and diffuse illumination from the atmosphere. Rain increases coverage and optical thickness; thunder darkens cloud interiors. The sun and stars are transmitted through the cloud density, rather than painted over a gray weather overlay. Inside and above the layer use the same density field as ground views.

Settings are under **Video Settings > Space Graphics**. **Clear-weather clouds** toggles dry-weather clouds; rain and thunder always enable an overcast layer. **Cloud quality** sets 16–64 integration steps in cloud segments; the Fast/Balanced/High presets choose 24/48/64. The atmosphere remains enabled when clouds are disabled. **Planet space skies** still restores the native planetary sky path when disabled. Settings are local to the client.

## Data and units

`AtmosphereProfile` stores planet radius, atmosphere height, RGB Rayleigh scattering, Mie scattering, RGB absorption, two density scale heights, ground albedo, and cloud coverage. Geometry uses simulation scene units, not the far-away storage coordinates where ship chunks reside. Optical coefficients have inverse scene units. The default maps one atmosphere height to 100 km of optical distance: coefficients scale by `100 / height`; Rayleigh and Mie scale heights are 8% and 1.2% of the shell height. This deliberately compresses a recognizable atmosphere around our existing small celestial bodies; it is not an assertion that a Minecraft block equals a physical kilometer.

Cloud base/top are art-directed at 24%/52% of the shell height. Their cells are 4.5% of that height. These exaggerated layers preserve a visible, traversable Minecraft cloudscape on compressed planets.

Celestial bodies can carry an optional `optics` compound in the existing version-2 definition. `SpaceDefinitionCodec` preserves and validates it, including agreement with the body's radius and atmosphere height. Old definitions use the temperate defaults without a save migration. `PlanetSky` sends the profile and planet center separately from the sky's opaque-body list, so removing the standing planet's mesh does not discard its optical geometry. An optical atmosphere is separate from whether that world's air is breathable.

## Flat terrain, altitude and rainy weather

World 5 exposed a coordinate error: a camera at X=886, Y=65, Z=-82 was treated as a point on a tangent plane extending from a planet of radius 2,200. Its horizontal displacement put it roughly 173 scene units above the spherical surface, outside the 88-unit atmosphere. The black daylight sky, visible daytime stars and missing rain clouds were three consequences of that same error. The earlier native fixture at X=0 did not expose it.

Surface views now reconstruct the camera's native Minecraft coordinates from its observer frame. Celestial position and atmospheric altitude depend on native Y only; walking across a flat dimension does not climb off the planet. On an atmospheric surface, 512 Minecraft blocks above Y=65 span one atmosphere height. Native aerial-perspective distances use this same conversion. The default cloud layer is consequently around Y=188–331. Space views retain their existing scene-unit geometry. Airless surface skies also use the flat celestial frame.

Cloud coverage remains anchored to native horizontal coordinates through the inverse surface rotation, with the same scale conversion and slow wind. Rotating the planetary reference through the Minecraft day does not rotate the cloud field across the ground. Changing horizontal position still changes the nearby cloud pattern.

The **Clear-weather clouds** setting controls optional dry-weather clouds. Any rain or thunder enables the cloud layer independently of that preference; rising rain increases coverage toward a complete overcast. Once rain stops, the dry-weather preference applies again. The setting tooltip explains this behavior. This does not change Minecraft's weather timers or precipitation.

For iteration, `dev.ps1` opens the existing World 5 as Player451. Copy updated GLSL into the runtime resources with `.\scripts\Invoke-Gradle.ps1 processClientResources processResources`, then use **F3+T**. The shader reload remains atomic: a failed candidate leaves the working renderer active. Java changes require a client restart. The moon is positioned by the existing celestial scene; this change does not introduce orbital dynamics.

Regressions cover World 5's coordinates, the world border, native altitude conversion, camera-origin and rotation handling, server snapshot metadata, mandatory rain clouds with the dry-weather option disabled, and recovery after rain ends. The native terrain/glass fixture now runs at X=886, Z=-82 and Minecraft time 9502, rather than at the origin. It captures clear weather and an actual client thunderstorm with optional clouds disabled, using a disposable world.

The [World 5 follow-up receipt](world5-sky-verification.json) records 450 passing unit tests with 12 existing known-bug tests skipped, all 100 discovered server tests and all 15 graphical entrypoints passing, plus assets, packaging and formatting. The strict no-skipped-tests gate remains unsatisfied. The images linked below now show this follow-up's native fixture; the later sections retain the initial atmosphere implementation's historical counts and timings.

## GPU passes and ownership

| Pass | Resolution | Update conditions |
| --- | --- | --- |
| Transmittance | 256 × 64, RGBA16F | Atmosphere profile or shader change; shared across views |
| Multiple scattering | 32 × 32, RGBA16F | Same; includes isotropic higher orders and ground-bounce approximation |
| Sky-view radiance and RGB transmission | Two 256 × 128 RGBA16F images per view | Position, illumination/scene, weather, cloud settings, or cloud animation time changes |
| Native aerial perspective | Existing 24-slice volume-prefix atlas | Actual native opaque depth and current view |

The shared optical cache retains at most four profiles, approximately 544 KiB total for the two tables per profile. Each viewport adds 512 KiB for its two sky images. Camera rotation reuses the spherical sky lookup. Planet positions and eye offsets are subtracted in double precision before upload. Sky lookups concentrate angular resolution near the horizon, wrap azimuth correctly, and interpolate transmission and radiance separately.

Transmittance integrates exponential density with quadratic spacing near the observer. The multiple-scattering pass integrates unit solar illumination over 24 directions and estimates subsequent isotropic orders with a bounded geometric series. The sky pass integrates the shared atmosphere and clouds together; it splits rays at the cloud-layer boundaries so long horizon rays cannot step across the whole layer. Outside an atmosphere, shell-intersecting surface rays integrate directly to avoid losing a small planet limb in the angular sky lookup.

Sky/background, sun and cloud transport combine in linear HDR before the existing surface display conversion. Native terrain and glass use depth-limited aerial perspective through the existing optical-prefix interface. Sky pixels skip that additional atmospheric segment, preventing a second full atmosphere application. Celestial hits likewise avoid being processed twice. The prefix reconstruction's integration end includes atmospheric segments, including scenes without nebula volumes.

`PlanetFogMixin` suppresses native environmental/rain haze when the replacement is ready. Native chunk-distance fading, underwater/lava/powder-snow fog, blindness/darkness, and boss fog remain. Native clouds are suppressed through both normal and OIT cloud draw paths only while the custom planetary compute view is ready.

The same compute path serves the main sky, independent terrain cameras, monitors and external ship views. Live surface weather is sampled only when the viewed dimension matches the client's world; unrelated views do not inherit local rain. Each view owns its radiance/transmission images and history. Changes to profiles or cloud settings invalidate history. No normal frame reads GPU images back to the CPU.

All seven compute programs reload as one candidate set. A compile failure in any atmosphere pass leaves the working set and lookup images usable. Successful reload invalidates the profile cache and view lookups. Allocation rollback, ordinary close, dimension disconnect and shutdown release the new images; the existing OpenGL state scope also covers their texture/image bindings. GPU reports now include `atmosphere_ms` separately from celestial surfaces and volume integration.

## Tests and reference images

`AtmosphereProfileTest` checks density decay, horizon reddening, planetary shadow and high-altitude sunlight, optical-unit scaling, convergence, profile validation and serialization. `SpaceDefinitionTest` checks optional optics round trips, old definitions, airless bodies and mismatched profiles. A server GameTest checks the actual planet-frame/profile snapshot and preservation when removing its opaque representation.

`AtmosphereClientGameTest` runs the real OpenGL passes and compares 25 transmittance-table locations against an independent 8,192-step double-precision integral. It also checks camera lookup reuse, shared tables between viewports, altitude invalidation, finite bounded transport, storm extinction, night darkness, airless invariance, native-depth aerial perspective, prevention of double sky scattering, and failed reloads of each new pass. Existing graphical cases cover real native rendering, transparency, multiple monitors, pilot views, movement, resource packs and repeated allocation failures/deletions.

The graphical suite writes `screenshots/nullspace-atmosphere-reference-atlas.png` and `screenshots/nullspace-atmosphere-metrics.json` beneath its isolated game directory. The 16 reference tiles cover clear noon, low sun, sunset, two twilight phases, midnight, clouds, rain, thunder, inside/above storm clouds, sunset clouds, an orbital limb, alternate scattering, airless daylight and high-altitude twilight. Additional in-game captures show actual terrain and glass under clear and storm skies. Reference captures are generated by GPU rendering; they are not externally generated art.

The frozen-source verification passed **316 unit tests, 96 mod GameTests plus one optional vanilla smoke test, and all 15 graphical entrypoints**, along with assembly, assets, formatting and the verification harness. Exact source/JAR hashes, retained artifacts and measurements are in the [verification receipt](atmosphere-renderer-verification.json). Local two-client and physical crash/restart matrices were not rerun for this renderer change.

The GPU transmittance comparison measured a maximum absolute error of **0.000489** on the RTX 3070 Laptop GPU. At 960 × 540 in the native terrain/glass scene, 32 samples measured atmosphere pass p95 **1.59 ms**, celestial surface p95 **0.63 ms**, and volume p95 **1.46 ms**. The atmosphere median was 0.001 ms because unchanged views reuse the lookup. These are separate asynchronous pass timings, not complete frame times; native terrain draws, final composition, GUI and other Minecraft work are excluded.

The separate populated 640 × 360 space fixture measured surface/volume medians of **1.80/7.31 ms**, with one scheduled monitor capture adding **3.38 ms** across its measured compute passes. Its nebula integration remains above the original 4 ms main-view tuning target. The three view allocations total about **75.8 MiB**, excluding the small shared optical cache and driver/compiler caches. This fixture is deliberately different from the native planetary sky scene; the measurements are not interchangeable.

Retained images: [16-scene reference sheet](images/atmosphere-cloud-reference.png), [native terrain and glass](images/atmosphere-native-terrain-glass.png), [native thunderstorm](images/atmosphere-native-thunderstorm.png).

## Deliberate approximations

- This is a compact Hillaire-style solver, not a complete port of the paper. Multiple scattering is isotropic; there is no fitted Wilkie twilight model, spectral integration, atmospheric refraction or polarization.
- One visually dominant planetary atmosphere receives the detailed solver per view. Other distant planet shells retain the existing bounded approximation. Overlapping detailed planetary atmospheres are not supported.
- Atmospheric and cloud illumination currently use the primary emissive star. Additional stellar disks and the existing multi-light celestial-surface shading remain, but secondary stars do not yet contribute atmospheric scattering.
- Cloud lighting and atmospheric in-scattering share solar attenuation, but clouds do not add a shadow map to Minecraft's terrain lighting. Native block/sky lighting and precipitation remain Minecraft systems.
- Native terrain/glass retain the existing display-color optics interface with scalar transmission and RGB in-scattering. Celestial/sky transport uses RGB transmission. Nearby nebula and aerial terms use the existing cumulative composition approximation; this is not a general spectral solver for arbitrarily interleaved media.
- The cloud coverage field is procedural and simulation-space anchored, with bounded wind animation, not a server-side weather simulation. Flat terrain dimensions remain tangent patches rather than generated spherical terrain.
- The existing artistic solar exposure and twilight star-visibility controls remain, now fading out with altitude. Stellar disk colors are adapted toward a neutral incident white point so Sol's orange artwork does not make midday skies sepia. These controls complement transport and display exposure; the starfield and disk colors are not photometrically calibrated.

These boundaries keep the replacement compatible with the current scene model and testable within the existing OpenGL 4.3 pipeline.
