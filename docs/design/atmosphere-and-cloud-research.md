# Atmosphere and cloud references

The planet sky renderer is described in [atmosphere and cloud renderer](atmosphere-and-cloud-renderer.md). This document records the published work it was based on, what was taken from each, what was left out, and why. Read it when changing the model or when evaluating a different technique.

## Why a physical model at all

The first planetary sky was a blue gradient with an exponential fade and a gray blend for rain. That cannot give a consistent sunset: the sky color, the cloud lighting and the sun disk each follow their own rule, so recoloring one leaves the others wrong. NullSpace has several planets with different atmospheres, and players view them from the ground, from orbit, and through ship monitors. A single scattering model, shared by sky, clouds and distant views, gives all of those the same answer from the same parameters, and makes a different planet a matter of changing numbers.

## The references

| Work | Where | Used for |
| --- | --- | --- |
| Sébastien Hillaire, **A Scalable and Production Ready Sky and Atmosphere Rendering Technique** | EGSR 2020 paper, SIGGRAPH 2020 course. [Paper](https://diglib.eg.org/items/8a3e5350-18b3-46bd-9274-3add5af88c75), [slides](https://blog.selfshadow.com/publications/s2020-shading-course/), [implementation](https://github.com/sebh/UnrealEngineSkyAtmosphere) | The atmosphere foundation. Its factorization into small lookup tables, a multiple-scattering approximation and a per-view sky table works from the ground to space and with a changeable composition |
| Andrew Schneider, **Nubis: Authoring Real-Time Volumetric Cloudscapes with the Decima Engine** | SIGGRAPH 2017 Advances in Real-Time Rendering course. [Materials](https://advances.realtimerendering.com/s2017/index.html) | Cloud modeling and lighting: layered density, coverage and type fields, noise, and sunlight attenuation with self-shadowing |
| Andrew Schneider, **Nubis, Evolved** | SIGGRAPH 2022 Advances in Real-Time Rendering course. [Materials](https://advances.realtimerendering.com/s2022/index.html) | Guidance on close clouds, flying through them and fast motion, relevant to ship and remote cameras |
| Wilkie and colleagues, **A Fitted Radiance and Attenuation Model for Realistic Atmospheres** | SIGGRAPH 2021, ACM TOG 40(4), DOI 10.1145/3450626.3459758. [Paper and images](https://cgg.mff.cuni.cz/publications/skymodel-2021/) | An appearance reference for twilight and altitude. Not implemented: a fitted terrestrial model is a different tradeoff from a configurable planetary solver |
| Bruneton and Neyret, **Precomputed Atmospheric Scattering** | EGSR 2008. [Documented implementation](https://ebruneton.github.io/precomputed_atmospheric_scattering/) | A validation reference: its documented implementation provides density profiles, ozone and CPU reference images to compare against |
| Hillaire, **Physically Based Sky, Atmosphere & Cloud Rendering in Frostbite** | SIGGRAPH 2016 course. [Notes](https://media.contentapi.ea.com/content/dam/eacom/frostbite/files/s2016-pbs-frostbite-sky-clouds-new.pdf) | How a sky, clouds and celestial bodies share one system |

The adaptation to Minecraft (compressed units, quantized cloud cells, flat terrain under a spherical sky) is this project's own. The papers do not prescribe it.

## What was adopted, and what was left out

Adopted: Hillaire's lookup-table structure and sky view; Nubis-style bounded layers with coverage fields and lighting; Bruneton's approach as a numerical check (the GPU transmittance table is compared against an independent double-precision integral).

Left out on purpose:

- **Spectral integration, refraction and polarization.** RGB with isotropic multiple scattering is adequate for the look, and spectral work would cost frame time on every viewport.
- **Wilkie's fitted model.** It targets Earth. The game needs different planets.
- **Full multi-star scattering and overlapping atmospheres.** One dominant atmosphere per view keeps the lookup tables small and shareable.
- **Cloud shadows on terrain.** Minecraft's lighting is separate and tied to its own light engine.
- **Replacing weather.** Minecraft's rain and thunder state drives cloud coverage and thickness, but timers and precipitation stay vanilla.

The nebula volume integration that already existed was not reused for clouds. Its distance and sample assumptions differ from a planetary shell. Nebulae and clouds share math and resources where that is natural but keep their own sampling and bounds.

## Acceptance criteria that shaped the tests

These were written before the renderer and are still the questions to ask of a change:

- Stars vanish in bright atmospheric daylight, emerge smoothly at twilight, and remain in space.
- Clouds track the sun's lighting and occlusion, and an opaque cloud hides the sun.
- Sunrise and sunset vary continuously, with no jump at the horizon crossing.
- Moving cameras show no jitter, trails, or history from another viewport.
- The result holds through native terrain, glass and ship monitors, and not only in an isolated sky fixture.
- It holds from the ground, above the clouds and outside the atmosphere, in clear, overcast and storm weather, on Terra, an airless moon and a planet with different scattering.

Published timings from other engines are not a performance guarantee here. Budgets must be measured on the actual GPU for atmosphere updates, cloud integration and several simultaneous viewports.
