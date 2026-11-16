# Choosing the starting ship and planet

**Status (November 15): built and tested, not yet played.** The *Start* tab has a planet button (Random, Terra and each type), a crash-mode button, a difficulty and warnings; a blank seed is replaced by one that has the chosen type; and the server resolves the choice from the same chart. The starts that use it are [the orbit start and the crash start](crash-start.md). Not built: the survey tool (it is opt-in and must never slow the tests), and showing the chart on the tab.

Decided with the owner on November 15: a random planet is **any landable planet in any star system**, moons included, and every type is offered (Terra and Ocean separately, Toxic too); the air rules below, with **one** Air Canister; the menu **warns about air and resources, especially fuel, and states the difficulty** before the player commits, as inline coloured text; the warning for Random says you may start in another star system; and the difficulty rules are the owner's: **a planet without trees is never easy, a planet without air is very hard, and distance from Terra makes a start harder** (the distance is not shown, only its effect).

## Organizing the design

The four parts of the [station work](tiny-ships-and-stations.md#organizing-the-design):

| Part | Owns |
| --- | --- |
| Lore and content identity | What each planet type is like to start on, and why the player is there |
| Gameplay | What the player chooses, what each choice costs them, and what the first hour is on each |
| Engine requirements | What the game must guarantee: the chart exists and matches, the planet is landable, the air is survivable |
| Generation methods | How a type becomes a planet, and what happens when the seed has none |

## What exists now

- **The ship is already picked.** The *Start* tab of *Create New World* has a *Starting ship* button that opens [`StartingShipScreen`](../../src/client/java/com/nullandkale/nullspace/ui/screen/StartingShipScreen.java); the choice is stored in [`WorldStartSettings`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/WorldStartSettings.java) and `nullspace/world-start.properties` ([starting in space](space-start.md)).
- **The planet is not picked.** [`SpaceStart.planet`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/SpaceStart.java) draws a landable planet of the home system from the world seed and the player's id, so today a new player starts above Terra or Ares (Sol's other landable planets; moons are excluded). The [crash start](crash-start.md) is always Terra.
- **The chart is a pure function of the seed.** [`GalaxyPlan.generate`](../../src/main/java/com/nullandkale/nullspace/model/galaxy/GalaxyPlan.java) takes the world seed and nothing else, and [`StarSystems`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/StarSystems.java) saves `GalaxyPlan.generate(server.overworld().getSeed())` the first time it is read. The class is in the common source set, so the creation screen can build the same chart the server will, from the seed typed on the *World* tab.
- **Seven landable types, two with air.** Desert, Ice, Lava, Ocean, Jungle, Barren and Toxic have a surface; Gas giants are orbit-only. Only Ocean and Jungle (and Terra, the Overworld) are breathable ([star systems, surfaces](star-systems.md#surfaces)).

## Lore and content identity

The player chooses where the story starts, so each type should promise a different first hour, in the planet's own voice:

| Type | Terrain | Air | What starting there is like |
| --- | --- | --- | --- |
| Terra | the Overworld, villages and all | breathable | The familiar start: everything vanilla works, villages are near. |
| Ocean | deep ocean, warm ocean, rare mushroom islands | breathable | Water everywhere; the pad is on one of few dry spots. |
| Jungle | jungle, bamboo, swamp | breathable | Dense and green; wood and food are close. |
| Desert | desert and badlands | none | Dry and open; the suit is on from the first step. |
| Ice | ice spikes, frozen peaks, packed-ice seas | none | Cold and white; no air outside. |
| Barren | stony peaks and shores | none | Bare rock; little to eat or burn. |
| Toxic | the same stony terrain, poisoned air | none | Like Barren by terrain; reserved for a different atmosphere later. |
| Lava | basalt deltas, soul sand valley, lava seas | none | Dangerous ground; the pad is on the flattest dry spot but lava is near. |

Two things to decide with the owner: whether **Terra and Ocean** are separate choices (Terra is the chart's "ocean-type" home world but its terrain is the ordinary Overworld, so a player who asks for an Ocean world and lands in a village may not be getting what they asked for); and whether **Toxic** is offered while it is only Barren with a different label.

## Gameplay

**The choice.** On the *Start* tab, next to the *Starting ship* button, a *Starting planet* button cycles: **Random** (today's behaviour, the default), **Terra**, then each type above. Random needs no pre-generation and keeps every existing world and server unchanged. The planet applies to the ship start (the ship orbits it) and to the crash start (the ship has crashed on it; the crash start is always Terra today, so this removes that limit).

**What the player sees.** The choice and its warning (next section). With an explicit seed on the *World* tab, the tab can show what the chart holds: for each type, which planets exist, in which system and how far from Sol. A type that the seed does not contain is greyed with the reason ("no Lava world in this seed"). With no seed typed, the world's seed is only chosen at creation, so the tab cannot know; see [Open](#open), question 1.

### Warnings and difficulty

The planet changes how hard the start is, and the player should know before they commit. The *Start* tab shows a **difficulty** (coloured: Easy green, Normal yellow, Hard orange, Very hard red) and warning lines under the buttons, from [`StartDifficulty`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/StartDifficulty.java). For a desert crash start it reads:

> **Difficulty: Very hard**
> No air outside. You have a suit, one air canister and your ship's tether (72 blocks).
> No trees: little wood or fuel nearby. You start with 16 coal.
> The ship is wrecked: repairs come first.

The rules, the owner's:

| Choice | Difficulty |
| --- | --- |
| Terra, Jungle (air and trees) | Easy |
| Ocean (air, no trees) | Normal: a planet without trees is never easy |
| Desert, Ice, Lava, Barren, Toxic (no air) | Very hard: a planet without air is very hard |
| Random | "Any planet in any star system: it may have no air, and you may start far from Terra", shown as Hard |

**Distance makes it harder.** When the chart is known (a typed seed), the jumps from Sol to the planet's star system at the starting range add to the difficulty: one or two jumps raise it one step, three or more raise it two, never past Very hard. The warning says "N jumps from Terra: fuel to get home is a real cost." The distance itself is not shown on the planet button.

Which lines appear: **no air** names what the player has (suit, one canister, the 72-block tether); **no trees** says wood and fuel are scarce and that they start with 16 coal; a **crash** adds that repairs come first. The planet's trees and air are by type: only Terra and Jungle have trees, and only Terra, Ocean and Jungle have air.

**A seed that has the type.** A planet type has to exist in the world's chart, and not every seed has every type. When a type is chosen and the seed is blank, or the typed seed lacks it, the tab picks a random seed that has it (a search of a few thousand seeds, each a cheap chart build) and writes it into the seed field, so the type is guaranteed and the player can reuse the seed. A seed typed afterwards is not re-checked; the server then falls back to a random planet and a start never fails for want of one. The generator itself is unchanged, so existing worlds keep their charts.

**The survey (not built).** A tool that would generate each type's landing area over several seeds and count the logs, coal ore, lava and water nearby, to confirm the "no trees" and fuel claims. It would be run by hand like the scripts in `scripts/art`, its results checked into a data file the menu reads, and no test or sweep would depend on it or wait for it. Until it exists the rules above use the planet's biomes: Terra and Jungle have forests; Desert, Ice, Lava, Barren and Toxic are bare; Ocean has water and little else.

**Where that puts the player.** A type that Sol does not have means starting in another star system, up to several light-years from Terra. That changes the first hour:

- No Terra Harbor, shipyard, villages or the Terra landing pad nearby; the player is on their own until they fly home or find something.
- The way home is real fast travel. Every hop on the chart fits the starting engine range, and the start's 16 coal buys a few; whether that is enough to get home from the farthest system is a number to measure, not assume.
- The system's danger value (encounter strength) applies from the first minute. A far system may be more dangerous than Sol.

Sol always holds Terra (ocean-type), Ares (desert) and Jove (gas giant, orbit-only), and one more planet whose type comes from the outer-orbit roll (Gas giant 55 percent, Ice 30, Barren 15). So Desert is always available at Sol (Ares), Ice and Barren often, and Lava, Jungle and Toxic only in the four random systems, where inner orbits run hot (Lava, Desert, Toxic), middle orbits are mild (Ocean, Jungle, Desert) and outer orbits are cold (Gas giant, Ice, Barren). Whether a given seed holds Lava, Jungle or Toxic at all is not guaranteed, and is the first thing to measure.

## Air

The air a player has depends on three things, and a start has to keep all three working.

- **The ship's air.** Air inside a ship comes only from its working systems: it needs a Generator and an Air Supply that are enabled, and the cabin sealed ([`LifeSupport`](../../src/main/java/com/nullandkale/nullspace/gameplay/crew/LifeSupport.java), [`ShipAir`](../../src/main/java/com/nullandkale/nullspace/gameplay/systems/ShipAir.java)). A ship whose systems are not set up has no air to give.
- **The planet's air.** Outside, a breathable planet needs nothing. On a planet with no air, a full suit breathes from a carried Air Canister (2 minutes each, refilled at a powered Air Supply), or, while the player is within 72 blocks of their ship, from the ship's tether, which holds the oxygen meter at 20 and no lower. Without a canister or tether the meter falls 10 points a second and the player drowns at 0; an unsuited player in vacuum lasts about 20 seconds.
- **What the damage does.** A hull breach vents the rooms it opens into through the real air system.

What that means for each start:

| Start | Breathable planet | Planet with no air |
| --- | --- | --- |
| Orbit (ship start) | The ship's air is its own and does not depend on the planet. | The same. The player is safe aboard; the planet matters only when they land and step out. |
| Crash (single or multi) | A broken Generator or Air Supply is only a repair job: the planet's air carries the player. | **Dangerous without a rule:** the crash breaks Generators and Air Supplies; with the ship's systems down, a suited player with no canister has no air at all and, with no tether, dies in about a minute. The air rule below is built and tested. |

So the crash start on a planet with no air has an **air rule** (built; [`CrashDamage`](../../src/main/java/com/nullandkale/nullspace/gameplay/start/CrashDamage.java)):

1. **The cabin lives.** The crash never breaks the last working Generator or the last working Air Supply that serve the helm cabin, and never opens a wound into the cabin's rooms. The breaks go to the Engines and to the other rooms' machines. The check is the real air system: after the crash, the cabin cell in front of the helm must read as breathable.
2. **The tether works.** Because the systems stay enabled, the 72-block tether holds, so the player can gather materials near the ship without a canister, which is the first-hour loop.
3. **A canister for further.** On a planet with no air the player also starts with **one** full Air Canister (2 minutes), so a short trip beyond the tether is possible before they have built a way to fill more. The warning says so.
4. **A breached room is a hazard, not a trap.** A wounded room vents. Doors between rooms are normally closed, so this stays inside that room, and no tear reaches a block that touches the cabin's air zone. A test checks the cabin is breathable after the crash on a planet with no air.

On a breathable planet none of this applies, and the start can break the machines as the [crash-start design](crash-start.md) says.

## Engine requirements

What the game must guarantee:

- **The chart matches.** What the creation screen shows must be exactly what the server saves. Both call `GalaxyPlan.generate` with the same seed, and the screen must parse the seed the way the world will (a number, or text hashed, or blank). A unit test pins that the chart built from a typed seed equals the chart a server builds for the same world.
- **The planet is landable and has a pad.** Every chosen body is landable by construction (only landable types are offered), and [`PlanetSurfaces.ensureLanding`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetSurfaces.java) levels a pad on the flattest dry spot the first time it is needed, as the orbit start already does for its planet. The crash start builds the ship on a pad-shaped site near that landing spot and never registers a pad.
- **Dimensions exist before the player does.** Planet surfaces are created at world load ([`PlanetDimensions`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetDimensions.java)), before login, so a start on any chart planet can land immediately.
- **The start is reachable.** The ship's local space is the chosen planet's system; its first fast-travel hop must be possible from the start's fuel.
- **Saved settings stay compatible.** A new property (for example `nullspace-starting-planet=random|terra|desert|ice|lava|ocean|jungle|barren|toxic`) defaults to `random`, so every existing world file and `server.properties` keeps today's behaviour. An unknown value stops the server at startup with a clear message, as an unknown ship does today.
- **Air is tested on a planet with no air.** The crash start is built and checked on Ares (the desert world): the cabin is breathable after the crash, a generator and air supply survive, and the player has a canister. The other types without air share the same code path; each is not separately tested.

## Generation methods

1. **Pre-generate.** From the world seed, build the chart (`GalaxyPlan.generate`). This is cheap and pure.
2. **Choose the planet.** Among the chart's landable bodies of the chosen type, take the one nearest Sol by chart distance (then by id, so it is stable); prefer planets to moons, and use a moon only if the type has no planet. Terra is chosen by name.
3. **Resolve at the right time.** At creation, the screen uses step 2 to show availability. At the player's first login, the server repeats step 2 on the saved chart and starts the ship (and, for a crash start, builds and wrecks it) there. Only the server's answer counts; the screen's is a preview.
4. **No planet of that type.** The seed does not hold the type. The screen avoids it by choosing a seed that has the type. If the server finds none anyway (a seed typed after the choice), it falls back to a random planet instead of failing. It does not yet tell the player in chat which planet they got.
5. **The crash and air rules** above apply to the chosen planet, not to Terra only.
6. **Modes.** In a single crash the world has one planet, resolved once (the chosen type, or one random draw from the seed). In a multi-crash with a type, the type is resolved to one planet when the world is created and saved; with `random`, each player's planet is drawn for them from the seed and their id. Where on the planet a ship comes down is the [crash start's site search](crash-start.md#generation-methods), which never creates a pad.

## Open

1. **Showing the chart on the tab.** With a typed seed the tab could list where each type is (which system, which planet). Not built; the difficulty and warnings use the chart only for distance.
2. **A seed typed after the planet is chosen** is not re-checked on the tab. The server falls back to a random planet and the start succeeds, but the player may not get the type they chose.
3. **Difficulty tuning.** The distance steps (one for one or two jumps, two for three or more) and Random shown as Hard are guesses; the survey, when it exists, may also change the trees and fuel claims.
4. **Hard and Very hard are warnings, not a block.** There is no confirmation when pressing *Create New World*; the owner chose inline text only.

## Where this is tested

- [`StartPlanetsTest`](../../src/test/java/com/nullandkale/nullspace/gameplay/start/StartPlanetsTest.java): Terra and the Ocean type are different bodies; a type resolves to the nearest landable body of that type and is stable; Desert always exists (Ares); Random draws from many bodies in more than one star system and is stable per salt; a missing type falls back to a random body; every type exists for some seeds (so the tab's seed search can always succeed).
- [`StartDifficultyTest`](../../src/test/java/com/nullandkale/nullspace/gameplay/start/StartDifficultyTest.java): the owner's rules (no air is very hard, no trees is never easy), the warnings' wording, and distance making a start harder.
- [`WorldStartSettingsTest`](../../src/test/java/com/nullandkale/nullspace/gameplay/start/WorldStartSettingsTest.java): the planet and crash mode parse, default to Random and single, reject an unknown planet and round-trip through the world file.
- [`CrashStartClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/start/CrashStartClientGameTest.java): the real tab, choosing Terra and the crash start; the photograph of the tab shows the difficulty and warning. [`SpaceStartClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/start/SpaceStartClientGameTest.java) still passes with the new buttons.
- Not tested in game: the other planet types' warnings on the tab, and the seed search on a blank seed.
