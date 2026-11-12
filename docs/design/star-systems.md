# The galaxy

Space in NullSpace is a chart of places connected by jumps. This document describes what is on the chart, how it is generated, and the rules (range, fuel, danger) that make a journey a decision. The code is `model/galaxy/GalaxyPlan` (pure data, no Minecraft), `gameplay/navigation/StarSystems` (saving and queries) and surface lookup in [`PlanetSurfaces`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetSurfaces.java).

Each world gets its own saved chart. Planet identities and existing terrain must survive loading, upgrades and restarts.

**November 11 correction implemented; final integration pending.** The finite surface pool and random landability were bugs, tracked as [PT11-16](../history/playtest-2026-11-11.md). Every nongas planet is landable with its own stable dimension. Existing surface assignments remain intact.

## What is on the chart

The chart is a 100×100 square of light-years.

- **Sol**, the home system, placed first. It has four planets: Terra (the Overworld, an ocean-type world), Ares (a desert world), Jove (a gas giant) and one more, plus Terra's moon Luna.
- **Four random systems** at least 18 light-years apart. Planet counts are spread on purpose: one system each draws from the bands 1–2, 3–4, 5–6 and 7–9, in random order, so there is always a sparse system and a crowded one. Gas giants have 1–3 moons, other planets sometimes have one.
- **Features** that are not stars: nebulae, asteroid fields and rogue planets act as stepping stones (see below), and exactly one oddity, a pulsar or a black hole, is placed within reach of the chart. You can jump to features, but nothing on them can be landed on.

Planets get hotter closer to their star: lava, desert and toxic worlds sit close in, ocean and jungle worlds in the middle, and gas giants, ice and barren worlds further out. All nongas planets are landable; gas giants remain orbit-only. Missing surface identities in saved charts are filled without rerolling their bodies or replacing existing terrain assignments.

### Zones

Every body and feature has a **zone**: the orbit where you fly and meet other players. Stations, whether generated or built by a player, sit in a zone. Few are generated (see [orbital population](space-world-generation.md)): Sol's home orbit has a Waypoint Station, a Manufacturer Exchange and a derelict; two or three other stations sit in orbit-only zones; one or two derelicts drift at features. Zones where nothing can land make good places to build your own station.

### Danger

Zones have a danger from 0 to 3 ([`GalaxyPlan.danger`](../../src/main/java/com/nullandkale/nullspace/model/galaxy/GalaxyPlan.java)). Sol is 0. Elsewhere it grows with distance from Sol: 1 within 35 light-years, 2 within 65, 3 beyond. Nebulae are at least 2, and pulsars and black holes are 3. Danger decides how many defenders a derelict has and whether its signal is announced as a distress call; see [FTL journeys](ftl-journeys.md). A zone made for a journey takes the danger of the place it started from.

## Jumps, range and fuel

| Jump | Fuel |
| --- | --- |
| Between zones of one system | 0.50 |
| Between chart places | 1.00 + 0.10 per light-year |

Each engine tier has a maximum jump distance: 25, 40, 60 or 100 light-years. A jump beyond range is refused and names the next hop.

**A ship with the starting engine can reach every place on the chart by hopping.** The generator guarantees this. After placing systems it builds the cheapest network connecting them, and puts stepping-stone features along every link that is longer than a starting-range hop, so no hop exceeds the starting range ([`GalaxyPlan.connected`](../../src/main/java/com/nullandkale/nullspace/model/galaxy/GalaxyPlan.java) is checked for thousands of seeds in the unit tests). Better engines skip hops. Fuel is the resource the player manages; there is no refuelling at features.

Time and cost are in [FTL journeys](ftl-journeys.md): a trip between chart places takes about 60 seconds plus 4 per light-year, and local trips 45–90 seconds, shortened by engine tier.

## The star map

Open the **Scanner** and go to the top level (**Stars**). The map draws every system and feature at its position:

`@` you, `*` star, `~` nebula, `:` asteroid field, `o` rogue planet, `+` pulsar, `#` black hole.

Green places are one jump away with the ship's engines; grey ones need hops. Selecting a place shows its distance, jump fuel and contents, and **Set destination** chooses it. Selecting a system again opens its planets. The map is sent to the client as a list of chart places alongside the Scanner view.

## Surfaces

Landable bodies use real terrain, with biomes fixed by the body's type:

| Type | Biomes | Air |
| --- | --- | --- |
| Desert | desert, badlands, eroded and wooded badlands | none |
| Ice | ice spikes, frozen peaks, snowy slopes, frozen ocean; seas are packed ice | none |
| Lava | basalt deltas, soul sand valley; seas are lava | none |
| Ocean | deep ocean, warm ocean, rare mushroom islands; high seas | breathable |
| Jungle | jungle, bamboo jungle, swamp | breathable |
| Barren | stony peaks, stony shore | none |
| Toxic | stony peaks, stony shore | none |

Villages and other structures appear only on Terra. A starter landing pad is levelled on the flattest dry spot near each surface's origin the first time a ship goes there ([`PlanetSurfaces.ensureLanding`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetSurfaces.java)).

## How it is built

- **A dedicated dimension for each landable planet.** Dimension identity belongs to the saved planet, independent of discovery order or a type's slot count. Each has separate terrain storage and a generation seed belonging to that planet. Terra retains its Overworld identity, and existing dedicated Sol surfaces remain stable. Any moon marked landable also needs its own dimension. Generated terrain types and biome settings can be shared as definitions; their world instances cannot be shared between planets.
- **Registration and migration are part of the fix.** Create and restore the required dimension definitions at the appropriate world-load stage. Preserve existing surface assignments and their terrain/builds, and add missing dedicated surfaces without rerolling the chart. Never assign another planet's existing terrain to a new body. A world reset is not a substitute for implementing persistence and migration.
- **Saved identity and terrain templates.** New surfaces use `nullspace:planet/<body-id>`. [`PlanetDimensions`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetDimensions.java) creates missing levels during world loading, before recovery and player login. Each uses its saved body's seed. The old `surface_<type>_<slot>` definitions remain terrain templates and retain existing assignments; they no longer limit landability. Toxic terrain currently uses the barren template with nonbreathable air.
- **Deterministic from the seed.** `GalaxyPlan.generate(worldSeed)` is a pure function. [`StarSystems`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/StarSystems.java) saves the result in the `galaxy` section of `nullspace/ships.nbt` once, so later versions of the generator cannot rewrite an existing world's map.
- **Zones are the unit of space.** Orbit views, stations, signals and ships all address a zone, which is how two players in the same region find each other.

## Where this is tested

[`GalaxyPlanTest`](../../src/test/java/com/nullandkale/nullspace/model/galaxy/GalaxyPlanTest.java) checks planet counts, complete nongas coverage, unique/stable identities, deterministic generation, connectivity and danger values. [`StarSystemsGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/StarSystemsGameTest.java) covers saving, migration, level isolation, atmosphere, range refusals and map payloads.

The native planet checks, dedicated-dimension graphical visits and [fresh-process restart check](../../scripts/test/planet-restart-test.ps1) pass. The restart check preserves separate builds at matching coordinates, identities, seeds, biomes and pad registrations. Receipts are recorded in [journal completion work](../development/journal-completion-work.md#chunk-2-implementation-and-verification); final integration remains pending.
