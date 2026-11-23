# Crash-landing start

A third way to open a world, next to the ordinary start on a planet and the [start aboard a ship in orbit](space-start.md): the player's ship has already crashed on a planet and is wrecked. It is the same starting ship they would have had in orbit, but part of its hull is torn open and some of the machines it needs to fly are burnt out. The player's first job is to rebuild them.

**Status (November 15): built and tested; the owner has played it ("pretty good") and asked for the changes under [After the first play](#after-the-first-play).** It passes unit tests, server game tests on a planet with air and one without, and a real *Create New World* run with photographs of the result. Nobody has played it, so the feel of the first hour, the wreck effects and the repair loop are unjudged. What is not built is listed under [Not built](#not-built).

## Organizing the design

The same four parts the [station work](tiny-ships-and-stations.md#organizing-the-design) uses:

| Part | Owns |
| --- | --- |
| Lore and content identity | What happened to the ship and what the wreck should say to the player |
| Gameplay | The first hour: what the player does, what it costs, how they know they are done, and how several players share a crash |
| Engine requirements | What the game must guarantee for a crashed, wrecked ship to be a valid ship, and for joining never to fail |
| Generation methods | How the wreck and its site are built, deterministically, from the finished starting ship |

**Decided with the owner.**

- The start crashes the ship on a planet, **damaged**, with a few of its required blocks replaced by **broken variants** (a broken block, not a missing one, so the player sees what to build and what to replace), and the **NullSpace recipes already unlocked**.
- Two multiplayer modes: **single crash** and **multi-crash**.
- **The ship is built at the pad and never transferred.** There is no orbit stage and no landing: the player starts in the ship, on the pad.
- **The Pad Computer is always broken**, and a crash never creates or registers a pad, so crashes cannot fill the world with pads.
- A **random planet** is any landable planet in any star system, moons included.
- On a planet with no air the player has **one** Air Canister, the cabin keeps its air, and the menu warns about air and resources ([starting planet](starting-planet.md#warnings-and-difficulty)).
- **Joining never fails.** Every step has a fallback; a start that cannot be set up properly puts the player on the surface with their supplies and logs the cause.
- A site is **always found**: if no good ground exists, a pad is made anyway, over water or a slope. On a very busy server the sites form a grid around the spawn.
- The pad ring is **laid and 35 percent of it is blown away**, with real explosions around the ship; a wreck **drops 40 percent of the materials its machine is made from**; the wreck gives off smoke and sparks; the player **wakes facing the damage**; and ships' engines show an exhaust in space.

## Lore and content identity

The ship came down hard and alive. It is the player's own starting ship, so the wreck reads as a ship that was hurt, not abandoned: a torn hull and scorch marks, a pad ring half blown away, and burnt-out machines the player recognises by shape and facing.

- The broken machines are the ones a ship cannot fly or launch without: the Engine, the Generator, the Air Supply, and the Pad Computer ([`BrokenMachines`](../../src/main/java/com/nullandkale/nullspace/content/BrokenMachines.java)). Each is a charred, cracked, dark copy of the machine it replaces, its lights and flame out.
- **A wreck is alive for a while:** smoke curls off it, with the odd spark and ember and an occasional crackle, so the damage reads from across the room.
- The ship computer is never replaced. It is the player's window onto the ship and tells them what is wrong.
- The hull damage uses the visual language of a [derelict](tiny-ships-and-stations.md#the-derelict-state-built-november-14) breach (blackstone and cracked-brick scorch around torn blocks). A crash is a different story from a derelict's: nothing is stripped and no one has left, so only what a landing would do is used (torn hull, fallen fittings), not cobwebs, empty stands or empty frames.
- The ground tells the story too: a crash site is pad-shaped levelled earth, its ring partly gone, with explosion craters around it.

## Gameplay

**The first hour.** The player wakes in the cabin facing the damage, with 16 bread, the spacesuit and the field guide and, on a planet with no air, one Air Canister. Nothing else: no tools, no coal and no stone. They look around, find that using a broken machine says what it was, and look in the recipe book, where the replacement is already listed. The loop is: gather iron and copper, craft a Machine Casing, craft the machine, mine the wreck (which gives back 40 percent of what the machine cost), place the machine in the same spot. When every kind is back the ship works again. To leave the planet they also need a pad: they must repair the ring and craft and place a Pad Computer.

| Rule | Why |
| --- | --- |
| Half of each machine kind is broken, rounded up (at least one), and at most two of a kind on a small ship, three on a medium one and four on a large one. | Every broken kind is a task, and the ship needs all of them. The cap keeps a large ship from being a mountain of crafting: a Machine Casing alone is 4 iron and 4 copper. This is `MOST_BROKEN` in [`CrashDamage`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/CrashDamage.java). |
| A wreck keeps the machine's position and facing, and is a solid block of the same size. | "Replace the existing block" is something the player can see and do. |
| A wreck drops 40 percent of the raw materials its machine is crafted from, each item rolled on its own. | Salvage that softens the cost but never skips crafting. The Engine, for example, is 6 iron, 6 copper, 2 cobblestone, a furnace and a piston; a wreck drops about 40 percent of each. |
| Using a wreck says what to craft. | No guide needed to learn what to do. |
| Every NullSpace recipe is in the recipe book from the start. | The player can see the repair is craftable without first discovering its ingredients. |
| The kit is food, the suit and the field guide. | The first hour is gathering; the wrecks and the ship's furnishings are the first materials, and a tool is the first thing to make. |
| A bigger ship loses part of its furnishings: none on a small ship, a quarter of its chests, furnaces and workstations on a medium one, half on a large one. | A large ship is full of furnished rooms; left whole it is a warehouse. |
| The pad ring is laid whole, then 35 percent of its blocks are blown away. | The player repairs a ring, not builds one from nothing; the 32 cobblestone they start with covers part of it. |

The default ship (Rivet Spark) breaks one of each kind. A larger ship breaks up to two, three or four of each by size.

### Crash modes

One world setting chooses how several players share the crash. A world with one player behaves the same in both.

**Single crash** (the default). One ship crashed, once.

- The **first new player to join** causes the crash: their starting ship is built at the planet's spawn landing spot and wrecked. The world's crash (planet, ship) is saved.
- **Every later new player wakes up at that ship, in the closest valid air zone to it.** A valid cell has two free blocks above it and breathable air from the ship's own air system. The search starts at the helm and works outward, and successive players are spread over the nearest few cells so they do not stack. If there is none (no air outside and none left in the ship), they stand at the ship's pad, outside; that is never an error. They get their own supplies (and the canister, where there is no air) and are authorised crew of the same ship. Ownership stays with the first player.
- One planet for the world: the chosen type's planet, or one planet drawn once from the seed if the choice was random.

**Multi-crash.** Every new player crashes separately.

- Each new player gets **their own starting ship** and their own crash.
- The planet is either **random** (each player's own, stable for that player and seed, any landable planet in any star system) or **one specific planet**, fixed when the world is first used so it never changes. A random draw can put two players in different star systems, light-years apart.
- **Where they come down.** Sites sit on a grid of 40-block cells around the planet's landing spot, nearest first. A cell is skipped if another crash or a registered pad holds it. The nearest few cells with gentle dry ground are preferred; if none has any, the nearest free cell is used anyway and made level, even over water or a slope. So later crashes land as close to the first as the grid allows, and a very busy server gets a grid of crash sites around its spawn.

### The Pad Computer is always broken

A ship stands on a pad only if the player registered one: the **Pad Computer** is what registers a pad, and a ship belongs to the nearest registered pad below its controller ([pads](pad-previews-and-hatches.md)). The crash start never registers a pad:

- At every crash site a **broken Pad Computer** stands on a ring block. It is the fourth broken kind.
- The game **never creates a pad** for a crash. Neither the crash sites nor the number of players grow the pad registry.
- On Terra, the pad that exists from server start is the first crash site: its computer is replaced by the broken one and its ring is blown away like any other. A pad whose computer is gone reads as unusable, so it cannot be used by mistake.
- To leave the planet the player does what any player does: repairs the pad's cobblestone ring around the ship (the floor inside is already levelled earth), crafts a Pad Computer, places it and registers the pad. The recipe is in the recipe book from the start.

## Engine requirements

What the game guarantees, and the tests that hold it:

- **The ship is built where it crashed.** The starting ship is built directly on the surface with the same blueprint placement, provisioning and cabin check as the orbit start (journalled), and the player starts in it. Nothing is transferred, so nothing waits on a pad, on journals or on chunk entities, and the journalled transfer that every ship's travel depends on is untouched. The ship's record is written as a landed ship's is (its dimension is the surface, `localSpace` is the planet's address, a 32 by 32 footprint 32 high), with no `home` pad and no orbital position.
- **Joining never fails.** The start is idempotent: every step can be repeated. A site is always found (a grid, forced level ground), nothing waits on a loaded chunk (it loads what it needs), and a failure is retried. After three failures the player is put on the surface at the planet's landing spot with their supplies, the failure is logged, and the start is marked degraded, so no one is kicked or left stuck.
- **A crashed ship can launch later.** A ship off any pad cannot launch (launch finds its pad with `PadRegistry.below`). That is intended: it is the crash. A pad registered later around the ship captures it, as for any ship.
- **The cabin keeps its air.** On every planet the Generator nearest the helm and the Air Supply that blows into the cabin (the nearest one if none does) are never broken, and no tear reaches a block that touches the cabin's air zone. Up to 0.9.0 only a planet without air spared them, so a tiny ship on Terra could wake with its only air machines broken (the air connected to the cell in front of the helm). The test checks the cabin is breathable after the crash.
- **The player can move and leave.** The cabin around the helm, every door and every Docking Base (2 blocks around it) are never cut; nothing with an inventory, a bed, a door or any two-block piece is carved; machines are never carved.
- **Resumable and never doubled.** Each crash's damage is recorded, so a retry never wrecks twice; a finished start is never awarded again, and rejoining never duplicates supplies.
- **No pads.** The pad registry's size is the same after any number of crashes.
- **Explosions spare the ship.** The explosions around the pad cannot damage the ship's own blocks or the air above its footprint; only [`CrashDamage`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/CrashDamage.java) hurts the ship.
- **A crash start is a ship start.** [`WorldStartSettings`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/WorldStartSettings.java) makes `space` true whenever `crash` is, so the existing "who counts as new" rules apply unchanged.

## Generation methods

[`SpaceStart`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/SpaceStart.java) sends a crash start to [`CrashStart`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/CrashStart.java). For each crash:

1. **Planet.** From the world's settings and chart ([`StartPlanets`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/StartPlanets.java)); fixed when the player's record is first made.
2. **Site.** [`CrashSites.allocate`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/CrashSites.java) takes the nearest free grid cell to the planet's landing spot and saves it (Terra's own pad is the first site on Terra). The ground is judged from the generator's height estimate, so rejected cells cost no chunks.
3. **Prepare.** The footprint is cut or filled level (stone down to solid ground, 40 blocks of air above, a dirt floor), the ring is laid, 35 percent of it removed (the block under the computer stays), and the broken Pad Computer placed. On Terra's existing pad only the ring damage and the computer are done.
4. **Explode.** Six explosions around the outside of the pad (power 3.5, centred 22 to 27 blocks from the middle) make craters and take more of the ring. The ship's footprint is shielded. The items they throw are swept up.
5. **Build.** The ship is built on the floor, centred on the pad, provisioned and registered; the player is the owner and crew.
6. **Wreck.** [`CrashDamage`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/CrashDamage.java), seeded from the world seed and the ship's id so a retry makes the same wreck:
   - Break half (rounded up, at most two) of each Engine, Generator and Air Supply by seeded shuffle; on every planet, spare the Generator nearest the helm and the Air Supply that feeds the cabin. The same damage is what an [emergency landing](ship-navigation.md#landing-flies-first) does to a ship that comes down on a pad without fuel.
   - Find the ship's outside by flood fill from the edge of its box. Outer hull blocks touching it are the candidates, preferring the lower 60 percent, which hit the ground first.
   - Choose 3 to 6 wound centres (more on a bigger hull), at least 6 blocks apart, and cut a ball of radius 1 (radius 2 on a hull over 700 blocks) around each, never touching a block that touches the cabin's air zone.
   - Scorch what the cut left standing (40 percent blackstone, 25 percent cracked stone bricks), only inside the ship's bounds.
   - Let go of whatever lost its support (torches, ladders, carpets), up to four passes.
7. **Place.** The first player stands in front of the helm, later single-crash players in the closest valid air zone. They face the nearest wreck or tear, so they wake looking at the damage rather than a wall. The player gets the supplies, the canister if the planet has no air, and every NullSpace recipe.

**Choosing it.** *Create New World*, *Start* tab: a new world starts here, crash-landed on Terra in the Rivet Spark, single crash. *Starting ship*, *Planet* and *Crash mode* (single or multi) change it, and *Start* switches to the other starts. A dedicated server keeps the ordinary start unless set. The Planet button and a warning with a difficulty are described in [starting planet](starting-planet.md). The choice is stored in `nullspace/world-start.properties`. On a dedicated server, in `server.properties`:

```properties
nullspace-crash-start=true
nullspace-crash-mode=single
nullspace-starting-planet=random
nullspace-starting-ship=rivet_bolt
```

`nullspace-crash-mode` is `single` or `multi`; `nullspace-starting-planet` is `random`, `terra`, or a type (`desert`, `ice`, `lava`, `ocean`, `jungle`, `barren`, `toxic`). Only new players are affected (see [who counts as new](space-start.md#who-counts-as-new)).

**The broken machines' art and loot.** [`scripts/art/build-broken-machine-art.py`](../../scripts/art/build-broken-machine-art.py) derives the wrecks from the working machines' textures and models (charred and cracked, flame and warm lights put out, a spark where wiring shows), and writes the blockstates, item definitions, language entries and the pickaxe tag. It also writes each wreck's loot table from the machine's recipe: 40 percent of the raw materials, with the mod's own intermediate parts (Machine Casing, Circuit Board, Computer Base) expanded into theirs.

**Engine effects in space.** [`EngineEffects`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/EngineEffects.java): while a ship in space is thrusting (its pilot, autopilot or a docking manoeuvre asks for thrust) and its engines have power, every connected engine throws a flame, a blue flame and a trail of smoke out of its exhaust, along the direction the engine faces. An idle ship, or one without engine power, shows nothing. The plume grows with thrust and speed, and each engine upgrade tier burns its own colour (amber, blue, violet, green); see [flight physics](flight-physics.md).

## After the first play

The owner played the crash start and said it is pretty good, with two problems:

- **The ship changes the difficulty a lot.** Any of the larger ships gives a ton of resources.
- **The starting items were too much.** Food stays.

What was done:

- **The kit is food, the suit, the field guide and (without air) the canister.** The coal, the iron pickaxe and axe and the 32 cobblestone are gone for a crash start ([`SpaceStart.giveCrashSupplies`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/SpaceStart.java)). The ordinary ship start keeps its kit.
- **Damage scales with the ship's size class** (by decks: one is small, two medium, three or four large; [`StartDifficulty.shipClass`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/StartDifficulty.java)). A larger ship breaks more machines of each kind (cap 2, 3, 4) and loses a share of its furnishings (0, a quarter, half), so the repair work and the loot are less lopsided.
- **The menu's difficulty knows the ship.** On a crash start a bigger ship is easier (one step for medium, two for large), never below Hard on a planet without air or below Normal on one without trees, and a warning says why ("A large ship: plenty to salvage...").

The owner also pointed out that on a bigger ship a player can rebuild a flyable ship quickly, and wants that kept. It is kept: a large ship still has intact machines (half of each kind at most are broken), and those can be mined and moved to repair the broken ones, so a large ship can be flying again soon once its pad exists. That is why the menu calls a large ship easier rather than the damage removing the option, and it is the lever to adjust if it proves too easy.

## After the second play

The owner asked for stone, wood and enough copper and iron to leave every planet, and that the items "just need to be in the bridge, not obvious".

- **Stone and wood on every planet.** A water bucket, a lava bucket, an oak sapling and a block of dirt hang in item frames in the cabin, at eye height on the wall nearest the helm, never beside a door or a docking base and only where the air is breathable ([`CrashStart.hangBuckets`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/CrashStart.java)). Water running onto lava makes cobblestone; the sapling in the dirt makes wood.
- **A wreck drops 40 percent for real.** The loot tables divide by what a recipe makes (a circuit board makes four) so the total is 40 percent of the raw materials, not more. A Pad Computer wreck gives about 10 copper, 4 iron, 3 glass, 4 planks, 3 torches, 2 cobblestone and a redstone.
- **Airless planets are inhabited by every space mob.** The twelve airless planet biomes (barren, desert, ice and lava) spawn the suited zombie, zombie villager, skeleton, stray and wither skeleton, the six stowaways and the repair bot, through the biomes' natural spawn list ([`generate_planet_surfaces.py`](../../scripts/art/generate_planet_surfaces.py)). The mod's own types register spawn rules ([`Stowaways.place`](../../src/main/java/com/nullandkale/nullspace/content/entity/Stowaways.java)). The planets with air keep their vanilla life.
- **They drop salvage.** [`AirlessCrews`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/AirlessCrews.java) dresses the humanoid ones in the suit as they appear and, when a player kills any of them, drops iron, copper, redstone and a little glass (a repair bot drops double metal). Lava worlds have no ore at all (basalt deltas and soul sand valleys generate none), and ice, barren and lava worlds have no sand, so this is how copper, redstone and glass come to every planet. Zombies provide the iron, as the owner said.

## Not built

- **The survey tool** for the menu's difficulty (counting trees, coal and lava near each planet type's landing site). The difficulty uses the owner's rules by type instead; the survey would confirm them. It must run by hand and never slow the tests.
- **Anything about how it plays.** The wreck's smoke and sparks, the engine exhaust and the explosion craters have been built but not watched; the tests check the logic and the screenshots show the still result.
- **No engine effects for other ships.** The exhaust is made by the server as ordinary particles, so players inside the ship's space see it; a distant ship drawn by the space renderer shows none.

## Open

1. **How easy a large ship should be.** The owner wants rebuilding one into a flyable ship quickly kept, and the difficulty to reflect it. The numbers (cap 4, half the furnishings lost, two steps easier) are a first guess; the levers are `MOST_BROKEN` and the share of furnishings lost in [`CrashDamage`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/CrashDamage.java).
2. **What "a ton of resources" is.** I read it as the large ships' furnished rooms (chests, furnaces, workstations) and their spare machines. If it is the hull's own blocks or something else, the fix is different.
3. **The spawn the search uses.** The built version searches around the planet's landing spot (on Terra, the pad the server builds at start near the world spawn). Whether to search around the vanilla spawn instead is not settled.
4. **The ring.** About 130 cobblestone are needed to repair a fully broken ring; 35 percent is broken, so about 46, and the player no longer starts with any stone.
5. **Multi-crash random planets far away.** Two players can start light-years apart. Whether a server should be able to forbid that is not decided.
6. **Salvage tuning.** 40 percent of the machine's cost is the owner's number; with the smaller kit it matters more.

## Where this is tested

- [`StartPlanetsTest`](../../src/test/java/com/nullandkale/nullspace/gameplay/start/StartPlanetsTest.java), [`StartDifficultyTest`](../../src/test/java/com/nullandkale/nullspace/gameplay/start/StartDifficultyTest.java) and [`WorldStartSettingsTest`](../../src/test/java/com/nullandkale/nullspace/gameplay/start/WorldStartSettingsTest.java): planet choice (terra and ocean are different, stable, any landable body, a missing type falls back to random), difficulty rules, and the settings round-trip.
- [`CrashStartGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/start/CrashStartGameTest.java), on Ares (a planet with no air), each in its own test environment: a single crash with two players (one ship, the broken Pad Computer, about a third of the ring gone, the cabin breathable, a generator and air supply surviving, one canister, the wreck's drops, no pad registered, the second player in the same ship with their own supplies) a large ship (more broken, furnishings lost, the cabin still breathable, the same small kit) and a multi-crash with three players (three ships, three non-overlapping sites close together, no pad registered, each site's computer broken).
- [`AirlessCrewsGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/encounters/AirlessCrewsGameTest.java) and [`PlanetBiomeSpawnTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/encounters/PlanetBiomeSpawnTest.java): every space mob on an airless planet drops salvage and the humanoid ones wear the suit, nothing drops on Terra or for a non-player kill, and the biome data lists every space mob on the airless biomes only.
- [`EngineEffectsGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/systems/EngineEffectsGameTest.java): a thrusting ship's exhausts fire into open space, an idle one has none.
- [`CrashStartClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/start/CrashStartClientGameTest.java): the real *Create New World* screen to a single crash on Terra, then photographs of the options, the cabin, the wreck from three sides and each broken machine up close. These were reviewed. A passing structural test does not say the start is good; playing it is still to do.
