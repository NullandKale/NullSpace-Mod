# Atmosphere, sunrise/sunset, and clouds

Research and recommendation, October 26, 2026. The replacement is now implemented; see the [implementation, approximations and verification record](atmosphere-and-cloud-renderer.md). The proposal below records the research and intended direction. The earlier daytime-star visibility correction was a separate shader fix.

## Recommended references

| Work | Publication | Why it matters here |
| --- | --- | --- |
| Sébastien Hillaire, **A Scalable and Production Ready Sky and Atmosphere Rendering Technique** | EGSR 2020 research paper; related SIGGRAPH 2020 course presentation | Recommended atmosphere foundation. Ground-to-space viewpoints, multiple-scattering approximation, economical lookup tables, and dynamic atmosphere composition suit our different planets. [Paper page](https://diglib.eg.org/items/8a3e5350-18b3-46bd-9274-3add5af88c75), [SIGGRAPH course/slides](https://blog.selfshadow.com/publications/s2020-shading-course/), [author's implementation](https://github.com/sebh/UnrealEngineSkyAtmosphere). |
| Andrew Schneider, **Nubis: Authoring Real-Time Volumetric Cloudscapes with the Decima Engine** | SIGGRAPH 2017 Advances in Real-Time Rendering course | Cloud authoring, weather, Perlin-Worley noise, animation, lighting, and integration with an atmosphere system. A course presentation rather than a peer-reviewed technical paper. [Course and PDF link](https://advances.realtimerendering.com/s2017/index.html). |
| Andrew Schneider, **Nubis, Evolved: Real-Time Volumetric Clouds for Skies, Environments, and VFX** | SIGGRAPH 2022 Advances in Real-Time Rendering course | Follow-up addressing close clouds, flying through them, fast movement, and temporal artifacts; useful for ship and remote-camera views. [Course and materials](https://advances.realtimerendering.com/s2022/index.html). |
| Alexander Wilkie and colleagues, **A Fitted Radiance and Attenuation Model for Realistic Atmospheres** | SIGGRAPH 2021 / ACM TOG 40(4), DOI 10.1145/3450626.3459758 | Strong reference for twilight and post-sunset appearance, observer altitude, finite-distance attenuation, and downward views. Use its images/model as an appearance reference; its terrestrial fitted model is a different tradeoff from a configurable planetary scattering solver. [Paper, supplemental images, and source](https://cgg.mff.cuni.cz/publications/skymodel-2021/). |

Two additional references are useful:

- **Physically Based Sky, Atmosphere & Cloud Rendering in Frostbite**, Hillaire, SIGGRAPH 2016 course. Read for the combined sky/cloud/celestial system. The author's [publication page](https://sebh.github.io/publications/) identifies the talk and links materials; the old EA article URL currently returns 404. The [EA-hosted course notes PDF](https://media.contentapi.ea.com/content/dam/eacom/frostbite/files/s2016-pbs-frostbite-sky-clouds-new.pdf) is a large download.
- **Precomputed Atmospheric Scattering**, Bruneton and Neyret, EGSR 2008. Useful comparison and validation reference. The author's [documented implementation](https://ebruneton.github.io/precomputed_atmospheric_scattering/) supplies GLSL/OpenGL code, configurable density profiles, ozone, tests, and CPU reference images. Its publication venue is EGSR, not SIGGRAPH.

The recommendation is **Hillaire for atmosphere, Nubis for clouds, and Wilkie/Bruneton for reference comparisons**. The implementation choices below are our proposed adaptation, not claims that the papers prescribe our Minecraft integration.

## What the current renderer actually does

`space.glsl` currently uses a small blue/horizon gradient and exponential transmission in `surfaceAtmosphere`, then blends toward a gray sky during rain. It does not evaluate a complete atmospheric scattering model. `PlanetSkyMixin` suppresses vanilla sky rendering when the custom planetary viewport is ready, while vanilla clouds remain separately rendered.

This split cannot provide a consistent sunset across atmospheric color, cloud illumination, and the sun disk. Recoloring the sky alone leaves clouds with a different lighting model. My recommendation is to replace cloud rendering wherever our custom planetary atmosphere is active, while retaining Minecraft's weather state and precipitation initially.

The star visibility fix fades the procedural starfield across twilight in atmospheric surface views. Space and airless moons retain stars regardless of solar elevation. This is an interim appearance correction; it does not turn the existing haze into a physical atmosphere model.

The immediate fix passed all 14 graphical entrypoints, including numerical GPU checks for daylight suppression, gradual twilight fading, visible night stars, and unchanged starfields in airless views. Assembly and the pinned formatter check pass. [Verification receipt](daytime-stars-verification.json); retained evidence: `build/verification/daytime-stars-20261026-2130`.

## Proposed replacement

1. **Planet atmosphere definition.** Use the existing celestial identity/frame and add atmosphere radius, density profiles, Rayleigh/Mie scattering, absorption, ground albedo, and stellar illumination parameters. Use explicit units and planet-local height; do not treat the flat terrain dimension as an infinite atmosphere or confuse its block coordinates with the spherical celestial frame.
2. **Atmosphere lookup passes.** Add transmittance and multiple-scattering lookup tables, a per-view sky lookup, and finite-distance aerial perspective using Hillaire's approach. Share planet-dependent data across views and regenerate only what the atmosphere, sun, or camera change requires. Validate low-sun behavior and the planet's shadow, including the period after the sun passes below the horizon.
3. **Cloud volume pass.** Replace native clouds only once the custom cloud pass is ready. Begin with bounded altitude layers, world-anchored coverage/type fields, wind, and density noise. Borrow Nubis's modeling and lighting ideas; art-direct larger or voxel-shaped formations to keep the selected Minecraft style. Cloud shape stylization should preserve coherent sunlight attenuation and shadowing.
4. **Shared illumination and composition.** Clouds sample the same atmosphere's sunlight and ambient sky. Cloud transmission obscures stars and the sun; clouds can self-shadow and receive low-angle reddened light. Handle view ordering above, below, and inside cloud layers. Composite our atmosphere/cloud radiance in linear HDR before its display conversion. Match native terrain fog/light carefully and avoid applying atmospheric extinction twice.
5. **Weather and every camera.** Minecraft's rain/thunder state drives coverage and weather transitions instead of only a gray overlay. The main view, monitors, third-person ship views, and surface/space views use consistent definitions and time. A remote viewport needs its own camera-dependent lookup/history; it must not reuse the main camera's altitude or orientation accidentally. Airless worlds have no atmospheric clouds.

Existing compute dispatch, depth integration, view ownership, and resource reload infrastructure can be reused. The current bounded nebula-volume integration is not a complete planetary cloud renderer; do not force planetary clouds into its distance/sample assumptions without reviewing them. Nebulae and clouds may share math/resources while retaining appropriate sampling and bounds.

Star visibility in the full replacement should follow atmospheric transmission, sky radiance, and display exposure together. Keep the interim daylight fade until reference captures show that the new radiometric/exposure path produces the intended visibility. Preserve the distinction between standing in an atmosphere and viewing that planet from an airless ship location.

## Captures and acceptance checks

Use fixed cameras and sun elevations for clear noon, low sun, horizon crossing, early twilight, late twilight, and midnight. Capture each from ground, above clouds, and outside the atmosphere, with clear/overcast/storm variants. Include Terra, an airless moon, and a planet with different scattering parameters.

Check that stars disappear in bright atmospheric daylight, emerge smoothly at twilight, and remain in space; clouds track solar lighting and occlusion; the sun does not leak through opaque clouds; sunrise/sunset vary continuously; and moving cameras do not produce jitter, trails, or history from another viewport. Render through native terrain, glass, and ship monitors as well as the isolated sky fixture.

Numerical transport tests and reference images should accompany captures. Performance budgets need measured GPU timings for atmosphere updates, cloud integration, reconstruction, and multiple simultaneous viewports on the actual target GPU. Published timings from other games are not a performance guarantee for our mod.
