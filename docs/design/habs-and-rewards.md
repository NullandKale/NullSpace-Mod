# Habs, dungeons and what space is for

Status: design, not built. Written November 16 from a design talk. Decided points are marked **Decided**; my readings and first guesses are marked **Proposed**; the questions at the end are open. How the work is split and ordered is in [the work plan](rewards-work-plan.md).

## Why

Exploring space gives loot, and the vanilla world already has loot. A player who crash-lands, starts on the overworld or starts in orbit can fairly ask: so what? The drive to go needs three things:

1. **Something that cannot be had at home.** Far planets and derelicts already supply some (copper, upgrade materials, wrecks, [salvage from the space mobs](crash-start.md)).
2. **Somewhere to spend it.** Missing today. Ships and stations are tools, not a place the player invests in.
3. **A payoff the player can see and keep.** A garden with a golden apple tree, a beacon over the base.

The constraint is the project's: stay minimal and true to vanilla, and avoid power creep. Every reward below is something vanilla players already do, made automatic or portable, and priced in the ship's own fuel and power or in materials only space has.

## Two loops

**Decided.** There are two separate loops:

- **The ship loop**, which exists: explore, find fuel, food and materials, bring them back, improve the ship, go farther (see [player stories](player-stories.md)). The ship is the vehicle, and only a little more than that.
- **The dungeon and base loop**, which this document starts. **Habs** (on planets) and **stations** (in orbit) are dungeons: generated places with real danger and good loot inside. The player can also create either as a base. A generated one is a place to raid and then keep; a made one is a place to live.

Both are the same kind of place, so what a player learns raiding one carries over to building one, as it does between a ship and a station ([stations](stations.md), [station pieces and systems](station-modules-and-systems.md)).

## Habs

**Decided.**

- A hab is a **station in the ground**. **Floor 1 is at the surface and the hab goes down from there**: habs are basically inverted stations. A station builds out into vacuum; a hab digs into the ground. A generated hab can have **harder or more mobs** than a station of the same size.
- A hab has **one golden apple tree**, whatever its size.
- Generated habs are dungeons with good loot. They generate in the world **about as often as villages**, so another is always a short trip farther.
- A hab is a sealed, lit, breathable place to live. A hab with a tree needs no Air Supply machine ([golden apple tree](#golden-apple-tree-hab)).
- **Habs can be built on Terra, in the Nether and in the End**, as well as on the other planets. That is what makes turret farms possible ([salvage turret](#salvage-turret-base)).
- The **hab controller** is basically a station controller for a hab: it controls the hab's **power, air and turrets**, and everything that belongs to the hab links to it. It is a new block ([what it shows](#the-hab-controller)).

**Proposed.** Habs also generate on Terra, so a player who never leaves home, or who has just crash-landed there, can do a hab dungeon and get the first sapling without going to space. On a planet with air a hab's free air matters less, but the tree, the apples and the dungeon do not.

### Air and explosions

**Decided.** An explosion uses up **a large amount of the air** in the sealed system it happens in, enough to make a player need to flee. Air loss is a big thing to worry about in ships, stations and habs alike. **Proposed.** The air removed scales with the explosion's power, and a TNT blast in a small room takes the room below the **25 percent breathable threshold** the air system already uses ([ship systems](ship-systems-and-viewports.md#air)), so the player has to leave or be in a suit. A creeper costs a little, a turret fight in a small room costs more. This changes the air system that exists today (`ShipAir`), and the numbers need tuning in play.

### How a hab or station defends itself

**What a generated station or derelict does today.** It defends itself only with what is in it; nothing in it acts as a defence.

- **Occupants.** Lost crew (suited zombies and skeletons) from danger 1, a skeleton and a repair bot from danger 2, and at danger 3 three zombies, two skeletons and two repair bots. Pests besides: pale spiders and silverfish, the ghost creeper from danger 2, and the violet slime and blaze at danger 3. They appear when a player first arrives and are persistent. While a player is aboard, reinforcements turn up in rooms away from them, up to two per danger level, and never more than six plus the danger alive at once ([`SiteThreats`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/SiteThreats.java)).
- **Hazards from the damage pass.** Each site has a history (evacuated or interrupted work at danger 0 and 1, breach, power failure or interrupted work at 2, breach or power failure at 3). A third to four fifths of the rooms are damaged, by danger. A breach tears the hull open, so a room loses its air for real; a power failure puts the lights out and takes the generators, batteries and air supplies of the damaged rooms. There are cobwebs and broken floors. Air is therefore the one hazard that is part of the rules: a visitor needs a suit and a canister, and a breached room drains ([`DerelictDamage`](../../src/main/java/com/nullandkale/nullspace/worldgen/condition/DerelictDamage.java), [`DerelictHistory`](../../src/main/java/com/nullandkale/nullspace/worldgen/condition/DerelictHistory.java)).
- **Not there.** No turrets, no locked doors, no traps and no guarded objective. The rooms are the dungeon, and the loot is in them.

**Proposed for habs (and generated stations that want to be harder).** Layers, from what exists to what is new:

1. **More and harder occupants,** from the same system, scaled by depth: floor 1 at the surface is danger 1, and each floor down is a higher danger. Deeper is worse and pays better.
2. **Air as a clock.** A hab's lower floors can be sealed, breached or dead, as a station's wings are, and explosions drain air ([above](#air-and-explosions)), so a creeper or a breach monster's blast is a threat to the room and not only to the player.
3. **Green fluid leaks** in the lower floors, the green gem's fluid ([green gems](#green-gems)), which hurts, so it is a hazard to cross and not only a drop.
4. **Security turrets,** the same turret as the player's own, hostile until captured. The hab's **controller is the objective**: reach it and claim it, and the turrets and the doors are yours. That gives the dungeon a goal, and it is how a generated hab becomes a base.
5. **Powered doors.** A door that needs power stays shut until the room's power is restored, which uses the power system the player already knows.
6. **The tree at the bottom.** The golden apple tree is on the deepest floor, so the sapling is the reward for going all the way down.

**Decided.** All six layers are built. Layers 1 to 5 (the occupants, air as a clock, the green fluid, security turrets with a controller to claim, and powered doors) also apply to generated stations, so they are built for stations first and habs reuse them ([work plan](rewards-work-plan.md)). Only the tree at the bottom and the depth ramp belong to habs alone.

## What each base asks of the player once

**Decided.** Building a base is gated by beating something once, so nobody gets one without having done the dungeon loop.

- **A hab with free air needs a golden apple tree, and the sapling comes only from a hab dungeon.** Each hab dungeon has a golden apple tree. Destroying it drops one golden apple sapling. Anyone who wants to build a hab with free oxygen has to clear a hab dungeon at least once.
- **A station needs a warp core, and the warp core needs a nether star, which means killing one wither.** Anyone who wants a station has to beat a wither at least once. This is vanilla's own fight, and the materials for it are in reach in space: the lava planets' soul sand valleys spawn wither skeletons ([AirlessCrews](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/AirlessCrews.java) lists the biomes), and soul sand is there too.
- **The other way to a warp core is to steal one.** A generated station has a working core, and a player can take it, but taking it breaks its containment and the **breach monsters** ([the violet slimes and blazes in the core's color](warp-core.md#stowaways-connection)) come out, **a ton of them, all over the station**. The wither is the way to build one; the theft is the way to raid one.

**Settled details.** A dungeon's tree is **broken like any other tree**. Destroying one tree gives one sapling, which makes one hab. A cleared dungeon **does not respawn**: habs generate about as often as villages, so a player who needs another sapling finds another dungeon by going farther. The enchanted sapling is nine golden apple saplings in a **3 by 3 craft**.

**There are two trees, and they work the same way.** A **golden apple tree** and an **enchanted golden apple tree**. Each grows its own apple (golden or enchanted golden), each gives a hab its free air, and each drops **one sapling of its own type** when destroyed. Only the golden apple tree is ever generated in the world, one per hab dungeon. **No enchanted golden apple tree is ever generated:** the only way to get one is to clear nine hab dungeons, destroy their trees, and craft the nine saplings into an enchanted golden apple tree sapling.

## The reward ladder

**Decided** unless marked otherwise.

| Tier | Reward | What it is |
| --- | --- | --- |
| 1 | **Warp core** | The entry fee for a station. Every station needs one. Build one with a nether star (one wither kill) or steal one from a generated station and fight the breach monsters. It makes building a station expensive. Details in [warp core](warp-core.md). |
| Hab | **Golden apple tree** | Gives a hab free air, where the warp core gives a station power. One stands in each hab dungeon; destroying it drops the one sapling. |
| Hab | **Enchanted golden apple tree** | Never generated. Nine saplings from nine hab dungeons craft one. Works the same as the plain tree. |
| 2 | **Repair bay** | Repairs tools with fuel, and keeps enchantments. Costs green gems to build, found only on airless planets. |
| Base | **Salvage turret** | Kills mobs and collects their drops and experience. Costs power and connects to its ship, station or hab controller. Habs can stand on Terra, in the Nether and in the End. |
| Final | **Portable beacon** | A beacon you carry. A helmet: the suit helmet's recipe with green gems for iron and a black hole, which the space wither, a white end-game boss, drops. |

Skipped: more hearts. Too large a change to every fight.

### Warp core (tier 1)

Not a prize but the price of a station. It is a custom beacon built from colored glass that burns a nether star, powers its station and gives it a beacon effect ([warp core](warp-core.md) has the beam and leak rules, and its open questions still stand). A generated station comes with one; a player-made station cannot be finished without one. That is what makes building a station a real project.

**Decided.** A core can be stolen from a generated station. Taking it breaks containment, so a ton of breach monsters spawn all over the station until the player gets out or seals the gap ([warp core](warp-core.md) has the leak rule). **Proposed.** A stolen core keeps its color and works in the player's own station, so a theft is a full alternative to the nether star.

### Golden apple tree (hab)

A hab needs **one** golden apple tree, and the tree gives the hab **air instead of power**. Plants breathing out is the fiction; the effect is a hab whose air supply costs no fuel. Station air and ship air today come from an Air Supply machine on power ([ship fuel and power](ship-fuel-and-power-design.md), `ShipAir`), so a hab's tree stands in for that machine.

**Proposed.** How the tree feeds the air system: the tree's cell counts as an Air Supply for the hab's sealed volume, the same way the machine does, with no power draw, so habs need no generator for air. Power is still needed for everything else.

### Enchanted golden apple tree

**Decided.** Nine golden apple saplings, one from each of nine different hab dungeons, craft one enchanted golden apple sapling. Nothing else makes it, and none is found in the world. Plant it and it grows enchanted golden apples by the same rules as the plain tree, and it gives a hab the same free air. Destroying an enchanted tree drops one enchanted sapling, so it can be moved but never multiplied. The same one-for-one rule holds for the plain tree.

### How both trees grow

**Decided.**

- A tree grows **one apple per Minecraft day**, up to **eight apples** on the tree.
- They **grow while the player is away.** Each tree remembers the last game time it was seen; when its chunk loads it works out how many days passed, adds that many apples up to eight, and records the new time. A base left for a week comes back with a full tree.
- Both trees follow the same rules, so the enchanted tree is better for what it grows and not for how it grows.

**Proposed.** The apple count and last-seen time live in the tree's block data. Harvesting takes the apples and leaves the tree. A tree in a hab that has lost its air or sunlight stops growing and keeps what it has.

### The hab controller

**What exists.** The **Ship Controller** opens to a Trip tab (one destination, Engage and Stop), a Manual steps tab, and a More tab (Loose blocks, Check ship, Locate problem, Details, Advanced), with Pilot, ship camera, Destination, Inspect hull, Close all doors and Deploy station. Power, fuel and air are not on it; they are on each machine's own screen (Generator, Engine, Battery, Air Supply, Scanner) and in Advanced systems ([ship block UI](ship-block-ui-design.md), [ship navigation](ship-navigation.md)). A station has no controller screen of its own: the **Station Core** deploys the platform, and the dock controls are on the docking base.

**Decided.** The hab controller is the one place that shows the hab's state: **the tree, the air system and the power system**, and it controls the turrets.

**Proposed layout.**

- **Tree.** Which tree, apples now of eight, time to the next apple, and whether it is growing or stopped and why.
- **Air.** Air in each room, the breathable threshold, leaks, with **Show leak** and **Show air opening** as on the Air Supply screen, and whether the tree is supplying it.
- **Power.** What is generating, what is stored, what each device draws.
- **Turrets.** Each turret, its state (on, off, no power), what it has collected, with a switch.
- **Repair bay fuel,** when one is linked.

### Repair bay (tier 2)

A machine that repairs tools and **keeps their enchantments**, which makes it the automatic mending the project wants without the vanilla mending exploit.

**Decided.** It is a tier 2 reward, so building one needs something found only on airless planets: **green gems** ([below](#green-gems)). **Decided.** It costs **one green gem block** (nine green gems) to build; the running cost is fuel, below.

**Decided.** The cost is **fuel, and a lot of it: a stack or more.** It does not take ingots, because ingot-for-durability is what makes mending so strong.

**Proposed (first guess, to be tuned in play).** About one stack of coal (or its fuel equivalent) to repair a heavily worn tool, scaling with the durability restored, drawn from the fuel the base holds, with power while it works. Fuel is the same unit the ship already spends ([what things cost](ship-fuel-and-power-design.md#what-things-cost)), so a hab or station without a generator cannot run one for long.

### Salvage turret (base)

A turret that kills hostile mobs near a base and gathers their drops and experience, as a player would. It is an automation of what the airless planets already give: their space mobs drop iron, copper, redstone and glass only when a player kills them ([AirlessCrews](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/AirlessCrews.java)), so a turret's kill counts as a player kill.

**Decided.** The turret kills things and costs power. **Turrets go on ships and stations as well as habs.** Each links to the controller of the place it stands in (the Ship Controller, a station's controller, the hab controller), which supplies its power and is where the player manages it. A hab can stand on Terra, in the Nether or in the End, so a player can build a turret farm in any of them. **Proposed.** It holds what it collects in a container. Range and fire rate are small at first. Whether a station's Station Core can also run one is open.

**Balance.** A turret farm is an experience and loot farm, which vanilla players already build, and its experience and fuel can feed the repair bay. Its costs are the power it draws, the hab it needs and the gems behind the machines. Watch how much experience a running farm makes in play.

### Portable beacon (final tier)

**Decided.** The last thing a player gets. It is not new: players already build beacons, and this makes that easier to carry. It gives the ordinary beacon effects and nothing beyond them.

**Decided.** The **space wither** is the end-game boss. It is the top tier of the spider family (the space mite and the small and large space spiders, the pale spiders), so it is **white**. For now it is basically a white wither. It drops a **black hole**, and the black hole is used in a **helmet recipe**.

**Decided.** The portable beacon *is* that helmet: you wear the beacon. Its recipe is the space suit helmet's with **green gems where the iron would go** and the black hole in it. The suit's plain helmet is `III / IHI` ([ship recipes](ship-recipes.md#space-suit)). It works as a suit helmet as well, so it also keeps its wearer breathing outside a room. The black hole is the item the earlier boss drop became; nothing else crafts it.

**Open.** The helmet's exact pattern, which beacon effects it gives (a choice of one, as a beacon has), and how it is activated.

### Green gems

**How geodes spawn today.** The only geode is vanilla's: the `minecraft:amethyst_geode` feature, listed in 16 of the 18 planet biomes, and in **neither of the two lava biomes**. So barren, desert, ice, jungle and ocean planets have amethyst geodes, as the airless ones among them (barren, desert, ice) do, and lava worlds have none, although the Scanner's tier II upgrade needs amethyst. Their placement is vanilla's (underground). Nothing yet is a "green geode".

**Decided.** A new gem in a new color: **nuclear green**, since amethyst is purple. It grows in a **small geode**, with enough gems in each for **at least one repair bay**: a repair bay costs one **green gem block**, which is **nine green gems**, so a geode holds nine or more. Inside the geode is a **fluid that is like water but hurts**: it flows like water, and an entity in it is damaged, so harvesting a gem is dangerous. **Both the green geode and the amethyst geode are placed in all airless worlds**, which adds amethyst to the lava biomes, so the Scanner's tier II upgrade (see [ship recipes](ship-recipes.md#upgrades)) and the repair bay both have their materials on every airless world. The name, color values, fluid and art are placeholders until they are made.

**Decided.** The geodes lie **exposed on the surface**, not underground. **Defaults** for the fluid, the geode and the rates are in [the work plan](rewards-work-plan.md#a1-space-materials-and-ores).

## Finding things

**Decided.** Ordinary ores generate on **all worlds**, lava worlds included, and **the farther a world is from Terra, the more ore spawns and the higher it spawns**. Ancient debris is on **lava worlds only**. **Scanner upgrades find ore**, so a player who has invested in the Scanner knows where to land and dig. The Scanner and its tiers are in [ship recipes](ship-recipes.md#upgrades) and [creative scanner tools](creative-scanner-tools.md). The richness follows the zone's danger band (0 at Sol, 1 within 35 light-years, 2 within 65, 3 beyond; see [star systems](star-systems.md#danger)).

**Decided.** **Lava worlds are where netherite comes from.** Their biomes already list the ancient debris features, but no overworld ores, so a lava world had no iron, copper or diamond (the salvage drops exist because of that). Ordinary ores now generate there too, and debris is the thing only a lava world has, which makes netherite a reason to go far. Whether the debris really generates at the planet's terrain heights is the first thing to check ([work plan](rewards-work-plan.md#a1-space-materials-and-ores)).

**Proposed.** Ore finding by Scanner tier: the stock Scanner reports nothing, tier I lists the kinds of ore a planet has, tier II adds its richness and a Survey of nearby generated chunks, and tier III widens the Survey and marks ancient debris on lava worlds. The tiers are a first guess ([work plan](rewards-work-plan.md#a7-ore-finding)).

## What is built and what is not

| Built | Not built |
| --- | --- |
| Ships, stations, the shipyard, docking, air, power and fuel; hab shells, rooms, layouts, powered doors, the depth ramp, loot, the hab gallery and the golden apple trees ([habs](habs.md)) | Placing habs in the world, player-made habs, the hab controller |
| Crash start, planets by type, derelicts with loot and threats | Warp core |
| Space mobs with salvage drops on airless planets | Golden apple tree and enchanted golden apple tree |
| Three upgrade tiers for the Scanner and other machines | Repair bay, salvage turret, portable beacon |
| | Scanner ore finding, ancient debris on lava worlds |
| | Player-built stations ([stations](stations.md)) |

## Order to build in

**Proposed.** Habs come first, since three of the rewards live in them.

1. A hab design: what it is, how it is generated on a planet, how a player builds one, how its air works, and the hab dungeon with its golden apple tree. A second document: [habs](habs.md) covers generation so far.
2. The golden apple tree and its offline growth, with the hab air rule, because that is the first payoff a player can see.
3. The repair bay, since it is a single machine on the existing fuel system.
4. The warp core, already designed.
5. The salvage turret.
6. Scanner ore finding and lava-world debris.
7. The enchanted tree and the portable beacon last.

## Open questions

1. **Green fluid and geode tuning.** How much the fluid hurts and how common the geodes are, which play will tune. The placement (the surface) is decided.
2. **The portable beacon helmet.** The exact pattern, which effects it gives and how it is activated.
3. **The space wither.** A white wither for now; where it spawns, how hard it is and whether more than one exists.
4. **Defence tuning.** How many security turrets a site gets, how hard each floor's occupants are, and how the green fluid leaks are placed.
5. **Station controller.** A station has no controller screen today. Turrets and the controller-as-objective both need one (what it shows is the hab controller's list without the tree), or the Station Core has to take that job.
6. **Explosions and air.** The exact air loss per explosion, and how the air system shows it.
7. **Repair bay fuel.** One stack of coal per full repair, or a flat price per use? Does a bigger tool cost more?
8. **What sets the warp core's price,** so station building is expensive and not out of reach? That is the open build question in [warp core](warp-core.md).
9. **A stolen core.** Whether it keeps its color (proposed) and works in any station.

Nothing here is a commitment until the hab design and the first tree are settled.
