# The galaxy

Each world gets its own galaxy chart the first time the server starts. The chart is saved once and never rerolled. Changing it needs a new world, because Minecraft creates dimensions only when a world is made.

## What is on the chart

- **Sol**, the home system: Terra (the overworld), Luna, Ares, Jove (a gas giant) and one more planet.
- **Four random systems.** Planet counts are spread out: one system each has 1-2, 3-4, 5-6 and 7-9 planets. Gas giants have 1-3 moons; other planets sometimes have one.
- **Features** that aren't stars: nebulae, asteroid fields, rogue planets, and one pulsar or black hole. You can jump to them but there is nothing to land on.

Planets get hotter closer to their star: lava, desert and toxic worlds sit close in; ocean and jungle worlds in the middle; gas giants, ice and barren worlds further out. About 40% of bodies can be landed on, and every system has at least one.

Every body and feature has a **zone**: the orbit you fly in and meet other players. Stations, generated or player-built, are in a zone. Few stations are generated: Sol's home orbit has a Waypoint Station, a Manufacturer Exchange and a salvage ship; two or three other stations sit in orbit-only zones; one or two derelicts drift at features. Zones you can't land in are good places to build your own station.

## Jumps and range

| Jump | Fuel |
| --- | --- |
| Between zones of one system | 0.50 |
| Between chart places | 1.00 + 0.10 per light-year |

Each engine has a maximum jump distance set by its upgrade tier: 25, 40, 60 or 100 light-years. A jump beyond your engine's range is refused and names the next hop. **A ship with the starting engine can reach every place on the chart by hopping**: the chart puts stepping-stone features along long links. Better engines skip hops. Fuel is the resource you manage; there is no refuelling at features.

Journeys between chart places take about 60 seconds plus 4 seconds per light-year, shorter with upgraded engines.

## The star map

Open the **Scanner** and go back to the top level (**Stars**). The map shows every system and feature at its position:

`@` you, `*` star, `~` nebula, `:` asteroid field, `o` rogue planet, `+` pulsar, `#` black hole.

Green places are one jump away with your engines; grey ones need hops. Select a place to see its distance, jump fuel and contents, then **Set destination**. Select a system again to open its planets.

## Surfaces

Landable bodies use real terrain, with biomes fixed by planet type:

| Type | Biomes | Air |
| --- | --- | --- |
| Desert | desert, badlands, eroded and wooded badlands | none |
| Ice | ice spikes, frozen peaks, snowy slopes, frozen ocean; seas are packed ice | none |
| Lava | basalt deltas, soul sand valley; seas are lava | none |
| Ocean | deep ocean, warm ocean, rare mushroom islands; high seas | breathable |
| Jungle | jungle, bamboo jungle, swamp | breathable |
| Barren | stony peaks, stony shore | none |

Villages and other structures appear only on Terra. A starter landing pad is levelled on the flattest dry spot near each surface's origin the first time a ship goes there.

## Implementation

- `GalaxyPlan` generates the chart from the world seed (pure data, checked over thousands of seeds). `StarSystems` saves it in the `galaxy` section of `nullspace/ships.nbt` and builds each zone's orbit view.
- Landable bodies draw from a fixed pool of surface dimensions (`surface_<type>_<slot>`), assigned by the chart. `scripts/generate_planet_surfaces.py` generates the pool's biomes, terrain settings and dimensions from the game data, and keeps the game-test world preset in sync.
- The star map is sent with the Scanner's view as a list of chart places.
