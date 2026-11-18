# Hab rooms and layouts: design

Status: **decided November 17 and built** ([habs](habs.md#layout-forms)): all five layout forms, powered doors, the depth ramp, every room of the catalog, the logs, the new lobby and the cave. What is not built is the hab controller block, the power network it owns and placing habs in the world. Written November 17 after the owner's review of the first furnishing pass, which found three faults: a long hall with rooms off it is illogical and formulaic, the hab's rooms were not changed (only some floors were coloured), and the map table and the wing colours lead nowhere. This page redoes the design before any more code. **Decided** marks what the owner decided (on November 17 the owner accepted the lore, the build order, a toned-down dormitory, the chapel and apiary, and a mine head that digs a real cave); everything else is **Proposed** for correction.

Each part answers the four questions the station rooms were judged on ([room set](tiny-ships-and-stations.md#room-set-decided-november-13), [player stories](player-stories.md#why-a-station-would-matter)): how it fits the **lore**, how it plays for the **player**, how it fits the **code and engine**, how the **random generation** makes it vary, and how it fits **other features**. A room or layout stays only if it adds a distinct activity or story.

## What the review decided

- **Decided.** Layout needs variety. Hub clusters, rooms that open into each other and shorter corridors with many doors are all wanted as options, chosen per hab by the seed.
- **Decided.** Hab rooms need real design against the questions above, not station rooms with a new colour.
- **Proposed.** Remove the wing stripes and the map table; keep only the wing name signs ([what stops a player clearing it all](#what-stops-a-player-clearing-it-all)).

## Lore

**Proposed.** The question is why anyone lives on a planet with no air. The answer in this game's own terms is **the ship loop**: a ship cannot carry unlimited fuel, food and parts ([ship fuel and power](ship-fuel-and-power-design.md)), and the planets hold what ships need (copper, iron, the green gems, ancient debris on lava worlds). So a hab is a **ground outpost of the manufacturers' exploration**: a crew stays on a planet to mine it, grow food, refit and refuel the ships that pass, and hold the claim. People stay long term for the same reason people winter at a remote mine: the work is there and the ships are the only way out. They are not settlers who love the place; they are **a mining and supply crew who ended up living there**, which is why a hab has family quarters and a chapel next to a foundry.

**Why it goes down, and which end is oldest.** On an airless world the surface is the enemy: no air, cold or heat, radiation. Rock is shielding, and depth is steady temperature, water and the ore seam. So the crew **started at the bottom**: they dug a shaft to a warm, wet cavern, put in the **grove first** (the tree is what let them stay), and **built upward** as the outpost grew. The **deepest floor is the oldest**, and the **lobby at the surface is the newest part**, added last when ships began to call and a door and an airlock were needed. (If the lobby had been first and they dug down, the deepest floor would be the newest; the design takes the first reading because it makes the grove the heart and the depth ramp natural.)

**Why it is a dungeon now.** The outpost failed: the seam ran out, supply stopped, or what they dug into at the bottom broke through (the green fluid leaks of [layer 3](habs-and-rewards.md#how-a-hab-or-station-defends-itself) come from below, not from the ceiling). The sealed place held, and things moved in. Danger and reward rise with depth because the oldest rooms are the founders' own: the richest, the most worn and the nearest to what went wrong, which matches the [danger ramp](habs-and-rewards.md#how-a-hab-or-station-defends-itself) already decided.

**A station is a different story.** A station served ship traffic and was staffed in shifts; a hab is where the crew **lived**. Hab rooms show it: personal things, family-sized spaces, a school corner, a shrine, a vegetable plot, next to the working rooms.

## Player experience

The hab's loop for a player: arrive at the lobby, go down, pick a wing for what you need, fight, find the tree at the bottom, leave with a sapling (and what you could carry). Each wing must answer **what can I do here that makes the trip worthwhile?**

| Wing | What the player gets | The reason to go there |
| --- | --- | --- |
| Living | Beds that set a spawn, a kitchen with food, the infirmary, personal chests | A safe place to rest and eat mid-dungeon; the best place for loot that is personal and varied (books, maps, maps of the hab, food) |
| Works | Tools, building materials and spares, a foundry and a workshop, repair parts, EVA gear | Practical supplies and the machine rooms the player can learn from before building their own base |
| Gardens | Food, seeds, animals in a pen, and the grove with the tree at its end | Food and the dungeon's goal; a calm place with its own danger |
| Command | Labs, the archive (enchanting books and the colony's written logs), the armory, the camera wall and **the controller** | The objective: reach the controller and claim it, and the hab's air, power and turrets become the player's ([layers 4 to 6](habs-and-rewards.md#how-a-hab-or-station-defends-itself)) |

Players will clear every room, so the design makes clearing **cost** something ([what stops a player clearing it all](#what-stops-a-player-clearing-it-all)). The player should rarely walk a long empty corridor; a long route needs something in it (a window onto the grove, a breach to cross, a locked door, a camera).

### The rooms

Each room, with the four questions answered, is in the [room catalog](hab-room-catalog.md); the summary:

**Proposed** hab-only rooms or variants, with what each is for. Station rooms not listed stay as they are ([`StationRooms`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationRooms.java)). Sizes are larger than a station's; a hab room scales its furniture with the room.

**Living wing**

- **Family quarters** (new). A bed bay for two or three, a table, a shelf with books, a child's corner with a toy chest. Abandoned story: one bed made, one overturned. Replaces the bunk row as the common living room.
- **Bunk hall** (hab variant of bunk room). Rows of single bunks in bays with a chest each, as on ships, but long, with a notice board. The cheap, many-room option for a big colony.
- **Commons and kitchen.** A shared dining hall with long tables and a kitchen with furnaces, smokers and a cauldron of stew. Food is the loot here.
- **Infirmary.** Beds, cauldrons and a potion shelf; the player's place to rest. The function is a **spawn point bed in a safe room** until a healing mechanic exists.
- **Chapel or memorial** (new). A quiet room with a shrine and a wall of names, one item frame of something personal. It adds no loot and exists to tell the colony's story; at most one per hab.

**Works**

- **Workshop, foundry, stores, maintenance, cargo, engineering, EVA prep** as in the stations, with the stores variants by wing already built.
- **Machine hall** (new). Generators, batteries and an air supply in a row, the part of the hab the controller will show; its damage state (dead, leaking) tells the player what went wrong.
- **Mine head** (new). The shaft the colony dug for stone and ore: a lift-less ladder down, rails, a minecart, an ore chest. **Optional**, a danger-two-and-up room that opens onto a real cave below.

**Gardens**

- **Growing bays and gardens** as in the stations, with the custom floors and ponds.
- **Animal pen** with a pair of each animal and a gate (nothing inside but grass, since animals jump onto blocks).
- **Seed vault** (stores variant, seed).
- **Compost and bee room** (new). Composters, beehives and honey; honey and bone meal are the loot.
- **The grove**, as it is.

**Command**

- **Labs, archive, observation, operations, armory, brig, lookout** as in the stations.
- **Camera wall** (new). One giant wall screen cycling the hab's cameras, an operations desk and a log. This is where a player **sees** the rest of the hab before walking it, and it gives cameras a job.
- **Controller room** (new, **one per hab**, deep in the command wing). The hab controller block on a dais with a power line to the machine hall; it is the objective. Guarded in proportion to danger.

### What stops a player clearing it all

A directory or colour does not help a player who will clear every room anyway, and that player is the normal one. Guiding them is the wrong aim; the right aim is that **clearing everything costs something**, so the order and the choice of what to skip matter. The pressure is already in the game and the design only has to use it:

- **Air.** A suit and a canister last a fixed time ([air](habs-and-rewards.md#air-and-explosions)); a sealed wing that the colony let die has no air, so a wing is a **timed trip**. The player decides which wing is worth the canister.
- **Power.** Powered doors ([layer 5](habs-and-rewards.md#how-a-hab-or-station-defends-itself)) keep a wing shut until the machine hall is restored, so the machine hall is a **prerequisite**, not another room to loot.
- **Reinforcements.** They arrive while the player is aboard, up to a cap ([`SiteThreats`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/SiteThreats.java)); a long clear is a hard clear.
- **Depth.** The richest rooms are below the worst ones; a player turns back when the cost passes the reward.
- **Unknown contents.** Nothing tells the player what a room holds, so skipping is a gamble. The camera wall shows rooms (not their loot), which makes scouting a real option and gives cameras a job.

So **the directory and the stripes are dropped.** What stays is a **name sign by each wing's door** in the hall, which the gallery already has: it is cheap and tells a new player where the doors go, nothing more. The map table goes with them. The lobby instead holds what a crew would keep at an airlock: suit and canister lockers and a bench.

## Layout forms

A hab today is one form, the **arms**: four straight arms from the entry hall with rooms along each. The owner found it formulaic. **Proposed:** the seed picks one of five forms, and a form is a function the planner already has the shape of ([`StationMethods`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationMethods.java): given the program, the hall and the wings, place rooms and doors as a tree).

| Form | What it looks like | Why it plays differently |
| --- | --- | --- |
| **Arms** (today, shortened) | Short corridors, three to five rooms per side, doors every room | The familiar one; no corridor is longer than about 20 blocks |
| **Hub clusters** | Each wing is a big plaza with rooms opening straight off it, no corridor | Each wing is one arena; clear it in one fight, then the doors are choices |
| **Suites** (rooms into rooms) | A chain: lobby, antechamber, rooms that pass into rooms by doors, like a house | Rooms are the route; you cannot skip one. The most dungeon-like |
| **Stacked** | The wings are on different floors, one wing per level off the ladder shaft | The depth ramp is also a wing ramp: living near the top, command at the bottom |
| **Ring** (a bastion variant) | A loop of halls round a central grove with rooms on the outer side | The grove is the middle and seen through glass from everywhere; the loop is closed by one **locked** door so the graph stays a tree |

Rules that hold for every form, from the owner's earlier station reviews: the room graph is a tree of doors, rooms are flush along straight lines and mirrored, paths are straight, no hidden air, one task per room. Variety is in the **form and the room mix**, not in random placement.

The seed picks the form from weights by maker (Rivet leans arms and clusters, Halcyon rings and clusters, Bastion suites and stacked) and by size (a small hab never uses a ring). The wings of [`HabPlanner.Wing`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/HabPlanner.java) are unchanged: only where they sit changes.

## Code and engine

- **Layout.** [`HabPlanner`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/HabPlanner.java) gets a `Form` enum and calls one `StationMethods` method per form (arms and the bastion chain exist; clusters, suites, stacked and ring are new). Every form returns the same `StationPlan`, so the structure, lighting, cameras, ladder and tests do not change.
- **Rooms.** A new `HabRooms` class takes over from `StationRooms` for habs and picks a design from `(use, wing, depth, size, seed)`; where it has none it falls back to the station room. This replaces the existing `HabFurnishings.variant`. Designs use the existing [`RoomKit`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/RoomKit.java) pieces and its marked points (`frame`, `stand`, `painting`, `chest`, `spawn`), so entities and loot placement are the same as stations.
- **Depth.** A room's floor index (0 is deepest) is already in the plan. It picks the variant (older rooms are more worn) and feeds [`SiteDefences`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/SiteDefences.java) so danger and loot follow the floor. This is the depth ramp, and it needs `SiteDefences` to take a floor, which it does not yet.
- **Controller room.** The controller block is a new block and screen; until it exists the room holds the Station Core stand-in, as the entry hall does now.
- **Lobby.** `HabFurnishings.entryHall` changes from carpet and map table to suit and canister lockers (EVA gear, the one useful thing in a lobby), a bench, the door and the wing name signs. The stripe code is deleted.
- **Size.** Rooms get more variants, not more blocks per hab; the large hab's block budget does not change. Generation is the same stepped job in [`HabGallery`](../../src/main/java/com/nullandkale/nullspace/gameplay/shipyard/HabGallery.java).

## How random generation varies it

Three levels, so two habs of the same brief differ in more than placement:

1. **Form** (above): five layouts, weighted by maker and size.
2. **Room mix.** Each wing's program draws from a menu with weights, so a living wing may be family quarters and a chapel, or bunk halls and a commons. At least the required rooms of the purpose always appear.
3. **Design and history.** Each room picks one of three to five designs for its use and size, then a **history** from [`DerelictHistory`](../../src/main/java/com/nullandkale/nullspace/worldgen/condition/DerelictHistory.java): left in a hurry, held until the end, never finished. History sets what is knocked over, which doors are stuck and which log is on the lectern, so the same design reads differently. Lower floors are older and worse.

**Checks.** Unit tests count over many seeds that every form appears, that the five forms are sound plans (a tree of doors, every required room, the grove present), that no corridor is longer than 24 blocks except in the arms form, and that no two habs of one brief share a room list. Game tests build one hab per form and walk it with the ladder-aware search already used. Review by walking the galleries, one form at a time; no test says a layout is good.

## Fit with other features

| Feature | How it fits |
| --- | --- |
| [Golden apple tree](habs.md#trees) | The grove stays the deepest room in every form (the ring shows it from the loop). It is the reward and the air. |
| Air and power | The machine hall holds the real generators and the air system; the damage pass can kill power in a wing, and the controller shows it. |
| Security turrets and the controller | The controller room is the objective; turrets face the controller room's door and the grove. |
| Cameras and viewports | The camera wall gives them a job, and unset monitors show the nearest camera as built. |
| [Repair bay](habs-and-rewards.md#repair-bay-tier-2) | A rare find in the works at high danger, as in sites, now with a hab-specific machine hall around it. |
| Lost crew and threats | The existing threats spawn per room from the same system; the depth ramp scales them. |
| Field guide | Each hab-only room and the directory need a field guide page when they are built. |
| Station planner and galleries | One planner, one structure builder, one gallery, so stations and habs stay the same kind of place, which the [two loops](habs-and-rewards.md#two-loops) require. |

## Open points

1. **Which layout forms to build first.** Proposed order: hub clusters, suites, stacked; the ring last because of its locked door.
2. **How many hab-only rooms.** Proposed: family quarters, bunk hall, chapel, machine hall, mine head, compost and bee room, camera wall and controller room. Any can be cut.
3. **Does the mine head open a cave?** It adds a real cave under the hab; it complicates placing habs in the world.
4. **The infirmary's function.** A spawn point is proposed until healing exists.
5. **How hard clearing should be.** The air, power, reinforcement and depth costs above are proposed; their numbers need play. Whether a sealed, airless wing is common or rare is open.
6. **The lore's reading of depth.** The grove is the oldest part and the lobby the newest. If you want the lobby first and a descent into the newer parts, the grove would be the newest room and the ramp would reward reaching the colony's last project.
