# Space Graphics settings: what each one costs

Measured November 14 on an NVIDIA GeForce RTX 3070 Laptop GPU (Ryzen 9 5900HS, OpenGL), to choose the preset levels in [`SpaceSettings`](../../src/client/java/com/nullandkale/nullspace/render/SpaceSettings.java). Timing is evidence for choosing levels, never a pass or fail threshold, the same rule as the [save and journal performance test](performance-test.md) (which stays deferred; this is a separate, GPU-only measurement the user asked for).

## How it is measured

[`GraphicsSettingsBenchmarkClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/render/compute/GraphicsSettingsBenchmarkClientGameTest.java) is opt-in: it does nothing unless `-Dnullspace.perf.graphics=true` is set, so it costs nothing in a sweep.

```powershell
.\scripts\test\Run-FocusedTests.ps1 -Client -TimeoutMinutes 30 -Classes com.nullandkale.nullspace.render.compute.GraphicsSettingsBenchmarkClientGameTest -JvmProperties '-Dnullspace.perf.graphics=true'
```

`-Dnullspace.perf.graphics.scene=sky|space` limits it to one scene and `-Dnullspace.perf.graphics.repeats=<n>` sets the repeats (3 by default). The report, `graphics-settings-benchmark.tsv` and `.json`, is written to the run's game directory.

Each setting is changed alone from the defaults, on two scenes, and the GPU time is the sum of the renderer's own timestamp queries for the surface, atmosphere, volume and resolve passes ([`SpaceComputeRenderer.stats`](../../src/client/java/com/nullandkale/nullspace/render/compute/SpaceComputeRenderer.java)):

- **Space:** a sun, planet with atmosphere, moon, a nebula and two four-deck hulls, with a moving camera. This is the heaviest view: a nebula in sight.
- **Planet sky:** a clear sky with clouds over flat ground, the camera moving a little each frame so the sky is rebuilt.

A laptop GPU changes its clock speed as it warms, so one reading of a setting means little (the same defaults read 15, 19 and 22 ms). Every setting is therefore run three times, each time between two runs of the defaults, and the **ratio to those defaults** is the number to read.

## Space scene (clean results)

The defaults at the time cost about 21 ms. Ratios to them:

| Setting | Ratio |
| --- | --- |
| Surface 640 / 960 / 1280 wide | 0.42 / 1.0 / 2.4 |
| Camera feed 640 / 960 / 1280 / 1920 / 2048 wide | 0.4 / 1.0 / 2.3 / 4.9 / 5.6 |
| Volume resolution 13% / 25% / 50% / 100% | 0.16 / 0.29 / 1.0 / 4.4 |
| Volume quality 8 / 16 / 24 / 32 steps | 0.60 / 0.93 / 1.4 / 1.7 |
| Antialiasing off, temporal reconstruction off, stochastic sampling on | 1.06 / 1.02 / 1.08 (no real cost) |

The nebula's resolution and sample count dominate; resolution scales with pixel count. A viewport is limited to 2048 pixels wide, so the 2560 camera size was measured at 2048.

## Planet sky (rough)

The sky is rebuilt (about 4.7 ms at the defaults) only when the camera or the time moves, and reused otherwise (about 1.3 ms), so the median of a run flips between the two and these numbers are not clean. What they do show: with clouds off, or cloud shadows off, the rebuilt sky costs about as little as the reused one, so clouds and their shadows are most of that cost. The cloud quality steps, coverage, density and block size showed no effect larger than the noise. Cloud costs are not worth tuning further without a steadier method (hold the camera and time still and time the rebuild alone).

## Preset levels

Chosen for the nebula-in-sight view, with a budget of a few milliseconds for Fast, under 8 for Balanced and about 20 for High on this GPU:

| Preset | Surface | Camera | Volumes | Volume steps | Antialiasing | Cloud steps | Cloud shadows | Nebula view |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Fast | 640 | 640 | 25% | 8 | off | 24 | off | about 2 ms |
| Balanced (the default) | 960 | 960 | 25% | 16 | 4 samples | 48 | on | about 6 ms |
| High quality | 960 | 1280 | 50% | 16 | 4 samples | 64 | on | about 22 ms |

The old Balanced (volumes at 50%) cost about 21 ms, and the old High quality (1280 wide, volumes at 100%, 24 steps) about 238 ms, which is not playable in a nebula view. Surface and camera sizes of 1280 and above, and volumes at 100%, remain available as sliders for machines that can afford them. These ratios will differ on other GPUs: re-run the benchmark there.
