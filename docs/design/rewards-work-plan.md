# Work plan: rewards, defences and habs

Status: track A is built and track B (habs) is started: the hab shells exist. Written November 16, updated November 17. The decisions this plan builds are in [habs and what space is for](habs-and-rewards.md); this page splits and orders the work and, for track A, defines each chunk closely enough to build and test. A **Default** is my choice where nothing was decided; it is written down so it can be corrected before building.

## Status

Built November 16, tested by unit tests and server tests (the integration sweep is recorded in the release notes). **Built:** A1, A2, A3, A4, A5, A7 and A8. **Not built:** the warp core (A6, waiting on its design) and track B (habs). Where the build differs from the definitions below:

- **A1.** The ore scaling is the placement modifier `nullspace:richness` ([`OreRichness`](../../src/main/java/com/nullandkale/nullspace/worldgen/OreRichness.java)) on the planets' copies of the vanilla ore features, with the band stored per planet when its level is created ([`PlanetDimensions.band`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetDimensions.java)). The lava biomes already listed the ancient debris features; no test generates a real lava planet to count debris.
- **A3.** A turret works while its vessel's controls have power and spends 256 internal fuel units a shot, not a share of the weapons power bus (the bus has no default allocation for weapons, so a turret would never have worked unconfigured). Vessel systems only run while a player is within 80 blocks of the vessel, so turrets, repair bays and the air an explosion takes are paused when nobody is near. **No Station Core status screen was built:** a station still has none.
- **A4.** The bay opens as a five-slot hopper screen and holds up to five tools; its status shows as a message. The turret and the bay are not in the field guide yet.
- **A5.** Securing a site is using its Station Core with the generators running ([`SiteDefences.secure`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/SiteDefences.java)); claiming derelicts is a separate feature that is still off in the game, and securing does not claim.
- **A7.** Sneak-using a Scanner on a surface prints the report and flashes the nearest vein of each kind ([`OreSurvey`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/OreSurvey.java)); there is no button yet. The radii grow with the tier: 32, 48 and 64 blocks.
- **A3, later.** The turret is a 3D model, not a cube: a fixed base, and a head and gun that turn toward the target. The base is the block's own model; the head and the gun are two items nobody holds, drawn by [`TurretRenderer`](../../src/client/java/com/nullandkale/nullspace/render/block/TurretRenderer.java) from the block entity's aim, which the server works out and sends ([`TurretBlockEntity`](../../src/main/java/com/nullandkale/nullspace/gameplay/defence/TurretBlockEntity.java)). It aims at a target whenever it has one, not only when it fires.
- **A4, later.** A generated ship or station at danger 2 has a repair bay one time in five and at danger 3 a little under half the time, standing against a wall of one of its rooms ([`SiteMachines`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/SiteMachines.java)); at danger 0 and 1 never.
- **A8.** There is no nest structure: [`SpaceNests`](../../src/main/java/com/nullandkale/nullspace/gameplay/boss/SpaceNests.java) spawns the boss 70 blocks off the hull of a ship with a player aboard in the oddity's zone.

**Verification, November 17.** 733 unit tests pass. Server tests: shards 1 and 2 passed; shard 3 failed one launch test, because entity-travel tests left item frames in an orbit slot and the measured-time balance had put them in the same shard as the launch tests. The tests now sweep entities out of the slots they use, and shard 3's classes pass together. Planet restart, the 16 crash cases and the shipyard crash passed. The four graphical workers exited 0, but the full receipt was never completed (the run stopped at the shard 3 inventory check), and one full `release-check.ps1` has not yet passed end to end. Running it with three crash workers beside the parallel check timed out three optional scripts (the servers were too slow to start), so its default is now two.

Not checked by anything yet: how the new art, the fluid and the white wither look in the game, the real ore counts and geode rates in generated planets, and the boss's daily return in real play.

## The split

Most of the work does not need habs. Habs are a second track that comes after, and reuses what the first track builds.

- **Track A: no habs needed.** Space materials and ores, air, turrets on ships and stations, the repair bay, defences for generated stations, ore finding, the space wither and the beacon helmet. Each piece is useful and testable on its own, and each adds a reason to go somewhere.
- **The warp core waits.** Its build rules (the beam, the shape, how a built core is validated) are still open in [warp core](warp-core.md), so it is not in this build.
- **Track B: habs.** The hab itself, its controller, the golden apple trees, the dungeon and building one. Everything in track B leans on track A.

## Track A

Chunks are built whole and tested once together (see AGENTS.md). Dependencies are named.

### A1. Space materials and ores

**What is true today.** Vanilla's `minecraft:amethyst_geode` is listed in 16 of the 18 planet biomes and in neither lava biome. The ordinary overworld ore features are in the barren, desert, ice, jungle and ocean biomes, and the two lava biomes have only Nether features. Those lava biomes already list `minecraft:ore_ancient_debris_large` and `minecraft:ore_debris_small`. A planet is a dimension built from one of a few prototypes per type ([`PlanetDimensions`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetDimensions.java)), and the biome files are written by [`generate_planet_surfaces.py`](../../scripts/art/generate_planet_surfaces.py).

**Decided.**
- **Green gem:** an item, with a **green gem block** that crafts from nine gems and back to nine.
- **Green geode:** small, on the **surface** (exposed), on **every airless world** (barren, desert, ice and lava). The amethyst geode is placed on every airless world the same way, which adds it to the lava worlds. Each green geode holds at least nine gems, enough for one repair bay.
- **Green fluid** inside the geode: like water, but it hurts.
- **Ordinary ores on all worlds,** lava worlds included. **The farther from Terra, the more ore spawns and the higher it spawns.** Ancient debris is on lava worlds only.

**Defaults.**
- **Geode shape:** a cavity two blocks across, filled with the fluid, lined with green gem cluster blocks. At least five clusters, each dropping two gems (Fortune adds more), so every geode holds ten or more. One of each geode kind about every four chunks to start; the rate is a tunable.
- **Fluid:** `nullspace:green_fluid` flows and spreads like water and does not make infinite sources. Standing in it deals 2 damage every 10 ticks (one heart) and does not set fire. It can be taken in a bucket.
- **Richness is the zone's danger band** ([danger](star-systems.md#danger): 0 at Sol, 1 within 35 light-years, 2 within 65, 3 beyond). The ore count is multiplied by 1, 1.5, 2 and 3 for bands 0 to 3, and each band raises the top of the ore's height range by 0, 16, 32 and 48 blocks, so veins appear nearer the surface. "Higher" is read as higher in the ground, closer to the surface; say if you meant larger veins.
- **How richness reaches a planet:** biomes are shared by every planet of a type, so the richness cannot sit in one biome file. Either one prototype per type per danger band, or a placement modifier that reads the planet's danger when its chunks generate. The second is less data and is the plan; the first is the fallback.
- **Ancient debris check:** the lava biomes name the features already, so the first job is to count debris in sample chunks of a lava planet at the terrain's real heights, and move the features if none appear.

**Tests.** Ore counts in sampled chunks rise from band 0 to 3 and ore height rises with them, on a lava planet as well as on the others. The geode shells contain at least nine gems. The fluid damages an entity in it and does not make a source. Lava worlds have amethyst and debris, and other worlds have no debris. A graphical pass for the geode and fluid art.

**Needs:** nothing. **Unblocks:** A4, A5, A7, A8.

### A2. Air and explosions

**What is true today.** A ship's air is finite cells in a 32 × 32 × 32 grid ([`SpatialAir`](../../src/main/java/com/nullandkale/nullspace/model/air/SpatialAir.java)), a room is breathable above 25 percent, and [`ShipExplosionMixin`](../../src/main/java/com/nullandkale/nullspace/core/mixin/ShipExplosionMixin.java) already turns a blast near a ship into block damage ([`ShipCombat.localExplosion`](../../src/main/java/com/nullandkale/nullspace/gameplay/combat/ShipCombat.java)).

**Decided.** An explosion uses up a large amount of air, enough to make a player flee.

**Default.** A blast of power P removes `min(95%, 20% × P)` of the air in every cell within twice its radius, and ordinary flow refills from neighbours afterwards. TNT (power 4) removes 80 percent, so an ordinary room drops below 25 percent; a creeper (power 3) removes 60 percent, a dent. An explosion in vacuum or in the open on a breathable planet removes nothing. It applies to ships, stations and, later, habs.

**Tests.** A blast in a sealed small room, in a large room, beside a breach, and outside any ship, each with air conserved everywhere it should be; a creeper and a TNT blast leave different amounts.

**Needs:** nothing.

### A3. Turrets on ships and stations

**What is true today.** Devices belong to a ship or station by structure and need no setup. A ship's helm is its controller; a generated station has a **Station Core** and no status screen. The ship cannon is a manual weapon.

**Decided.** The turret kills things, costs power, and links to the controller of the place it is in; it goes on ships and stations (and habs later). Its kills count as player kills.

**Defaults.**
- **Block:** `nullspace:salvage_turret`. It becomes part of its ship or station with the other devices.
- **Targets:** only hostile mobs (`Enemy`) inside the same ship or station, in line of sight, within 16 blocks. Never players, pets or villagers.
- **Fire:** one shot every 20 ticks for 4 damage. It draws power only while firing (5 power per second) and does nothing without power.
- **Kills count as the owner's:** damage is attributed to the ship's owner, so the airless planets' salvage drops ([`AirlessCrews`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/AirlessCrews.java)) and experience come as if a player killed it.
- **Collection:** drops within 3 blocks go into the turret's own nine-slot inventory, which a hopper can empty. Experience is stored in the turret as points and given to the player who uses it.
- **A station status screen:** the Station Core gains one, showing air, power and the turrets with a switch for each. A ship shows turrets in its existing systems screens.

**Tests.** A turret kills a hostile mob and not a pet, a villager or a player; it needs power; the drops and the salvage appear and the experience is stored; it works on a ship and on a station; the Station Core screen lists its turrets.

**Needs:** A2 is not required, but a turret fight in a small room is where air loss shows.

### A4. Repair bay

**Decided.** A tier 2 machine that repairs tools and keeps their enchantments. It costs **one green gem block** to build and **fuel** to run: a stack or more, and no ingots.

**Defaults.**
- **Recipe:** one green gem block, one Machine Casing and two Circuit Boards, shapeless.
- **Use:** one damageable item in a slot; it repairs while the bay has power and fuel and does not touch enchantments or any other component.
- **Cost:** `64 fuel × (durability restored ÷ 1,561)`, never under 8. One coal is 1.00 fuel ([units](ship-fuel-and-power-design.md#fuel)), so repairing a worn diamond pickaxe costs a stack of coal and a netherite one costs more. The fuel comes from the connected generator's store, and the bay draws 20 power per second while it works.
- **Rate:** 10 durability per second.
- **Where:** on a ship, a station or, later, a hab, wherever a generator is connected; and a rare find in generated sites at danger 2 and 3 ([`SiteMachines`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/SiteMachines.java)).

**Tests.** A tool is repaired with its enchantments intact; the generator's fuel falls by the cost; with no fuel or no power it stops and says why; the cost scales with the durability restored; the recipe needs the block.

**Needs:** A1 (the gem block).

### A5. Defences of generated stations

**What is true today.** A generated station or derelict defends itself only with its occupants and its damage ([how](habs-and-rewards.md#how-a-hab-or-station-defends-itself)).

**Decided.** All six layers are built; layers 1 to 5 also apply to generated stations, and habs reuse them.

**Defaults, for generated stations and derelicts at danger 1 and up.**
- **Occupants:** the existing system, unchanged (harder occupants come with habs).
- **Air as a clock:** nothing new; A2 makes every blast count.
- **Green fluid leaks:** at danger 2 and 3, a small pool (at most three by three, bucketable) in about one damaged room in four, away from the dock room and the path to it.
- **Security turrets:** the turret from A3, hostile to players until claimed, one at danger 1, two at danger 2, four at danger 3, set to cover doorways. Armed only while the site has power, so a power-failure history leaves them dark.
- **Powered doors:** the doors between the station's wings stay shut until the site has power (see `DoorSystem`).
- **Claiming:** with power restored, using the Station Core (or the ship's helm) makes the player the owner (the existing ship title), turns the turrets friendly and unlocks the doors. The controller is the objective.

**Tests.** The turrets shoot an unclaimed site's visitor and not the occupants; they are dark in a power failure; the doors open once power is restored; claiming changes the owner and the turrets stop firing at the owner; leaks stay out of the dock room and its path; an existing derelict still loads and plays.

**Needs:** A1, A2, A3.

### A7. Ore finding

**Decided.** Ores generate everywhere, and Scanner upgrades find them.

**Defaults.**
- **Stock Scanner:** nothing about ore.
- **Tier I:** the planet page lists the kinds of ore the planet has.
- **Tier II:** also its richness band, and a **Survey** button that reveals ore within 32 blocks of the ship in chunks that are already generated, as a glow on the blocks for 30 seconds. It costs power.
- **Tier III:** the Survey radius is 64 blocks and also marks ancient debris on lava worlds.
- **Screens:** one new panel on the Scanner's existing screen. The redesign in [creative Scanner tools](creative-scanner-tools.md) is still deferred, and this should be built so it moves into that redesign.

**Tests.** The kinds list matches the planet's real features; a Survey marks the ore that is there and not ore that is not; the radius and the debris mark follow the tier; it needs power.

**Needs:** A1.

### A8. The space wither and the beacon helmet

**Decided.** The space wither is the white end-game boss of the spider family, basically a white wither for now, found at a **boss site in a black hole or pulsar system**. It drops a **black hole**, which goes into the beacon helmet (green gems where iron would go).

**Defaults.**
- **Boss:** `nullspace:space_wither`, the vanilla wither's stats and skulls, recolored white; it needs air no more than the crews do.
- **Site:** a generated nest at the chart's oddity ([the chart has exactly one pulsar or black hole](star-systems.md#what-is-on-the-chart)) with the boss in it. One oddity means one nest; the boss returns once a Minecraft day after it is killed ([below](#still-open)).
- **Black hole:** one item, dropped only by the boss.
- **Helmet recipe:** the suit helmet's shape ([ship recipes](ship-recipes.md#space-suit)) in green gems with the black hole in the middle: `GGG / GKG` (G green gem, K black hole). It is a suit helmet too.
- **Effects:** the vanilla beacon's, from the full pyramid: the wearer picks one of Speed, Haste, Resistance, Jump Boost or Strength at level II, or at level I with Regeneration, and it is renewed every four seconds while worn. No fuel.

**Tests.** The boss spawns at the nest, drops the black hole when killed, and not elsewhere; the recipe makes the helmet only with the black hole; the effects apply and renew and can be changed; the helmet still lets its wearer breathe outside.

**Needs:** A1.

## Track B: habs

The generation design is [habs](habs.md). **B1's planner, structure, rooms, tree, Station Core registration and natural placement on Terra and planet surfaces are built.** Natural placement was missing from 0.8.0 and is added in 0.8.1. The `/nullspace shipyard habs` gallery remains a separate review tool. The dedicated controller screen and player construction workflow still follow below:

- **B1. The hab itself:** generation like a station but down from a surface entrance, on every planet and buildable on Terra, in the Nether and in the End, about as common as villages; sealed, lit and breathable.
- **B2. The hab controller:** the new block for power, air and turrets, with the tree on its screen.
- **B3. The golden apple tree and the enchanted tree (built November 17, see [habs](habs.md#trees)):** one per hab, the free air, one apple per Minecraft day up to eight, growth while away, one sapling dropped when destroyed; nine saplings in a 3 by 3 craft the enchanted sapling.
- **B4. The hab dungeon:** depth that raises the danger, the tree on the deepest floor, and A5's defences. Clearing it once is how a player gets a sapling.
- **B5. Player-built habs and turret farms:** the rules for a valid hab, and turrets in a hab in any dimension.

Needs: A1, A2, A3 and A5 before B4, and A3's controller link before B2.

## Order

1. **A1**, then **A2** and **A3** together (air and turrets).
2. **A4**, the repair bay: the first thing a player can feel.
3. **A5**, then **A7**.
4. **A8**, the boss and the helmet.
5. The warp core, once its design is settled; then track B once its document is written.

## Still open

- **Decided: the boss returns once a Minecraft day.** The chart has one pulsar or black hole, so one nest. After the boss is killed the nest spawns a new one a day later (24,000 ticks of game time, counted from the kill, whether or not anyone is there), and each kill drops a black hole.
- **The meaning of "higher"** for ore (closer to the surface, above) is a reading.
- **The warp core's rules** ([warp core](warp-core.md)), and **the hab design**, are for later.
- Every **Default** above is a first guess for play to tune.
