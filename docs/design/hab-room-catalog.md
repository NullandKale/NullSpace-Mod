# Hab room catalog

Status: **decided and built** ([habs](habs.md#rooms-and-furnishings)): the six new rooms (family quarters, chapel, machine hall, mine head, apiary and controller room) and the variants (camera wall, grove gallery, quarantine ward, ore store, and the dormitory's few occupants), with the written logs. The compost room is the apiary's composters. Written November 17, the room-by-room part of [hab rooms and layouts](hab-rooms.md). Everything here is **Proposed**. Every room is judged on the same questions as the station rooms ([room set](tiny-ships-and-stations.md#room-set-decided-november-13)) and must add a distinct activity or story, or it is cut.

For each room: **Story** (the lore: who used it and what it looks like abandoned, from [the hab lore](hab-rooms.md#lore)), **Player** (what the player does or finds), **Build** (how it fits the code, [`StationRooms`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationRooms.java) and [`RoomKit`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/RoomKit.java)), **Varies** (what the seed changes) and **Ties** (other features). **New** means a new `Use`; **Variant** means the station room with a hab design chosen by [`HabRooms`](hab-rooms.md#code-and-engine).

## Rules for every room

- **History.** Every room is told one of three stories by its depth and the hab's history ([`DerelictHistory`](../../src/main/java/com/nullandkale/nullspace/worldgen/condition/DerelictHistory.java)): *left in order* (tidy, things packed), *left in a hurry* (things overturned, doors stuck) or *held to the end* (barricades, bodies of lost crew). The shallow floors are mostly the first, the deepest the third. This is what makes a room read differently each time, so a design lists only its parts and not its mood.
- **One loot point.** Each room marks one chest for stocking (as now); nothing stocks until habs are placed in the world. Loot lists per room are therefore a **proposal for the stocking pass**.
- **Station rules still hold:** a free cell round tables, item frames at eye height, no stacked containers, costly blocks once per room, doors framed with solid posts, water and soil sunk into the floor ([room design rules](tiny-ships-and-stations.md#room-set-decided-november-13)).
- **Size.** Hab rooms are four blocks wider and deeper than station rooms. A design gets more **bays**, not bigger furniture: a larger room is a longer row or a second row.
- **Sealed wings.** Any room can be in a sealed wing (no air) or an unpowered one (doors shut); that is the [cost to clear](hab-rooms.md#what-stops-a-player-clearing-it-all) and not a room property.

## Living wing

### Family quarters (New: `FAMILY_QUARTERS`)
- **Story.** The crew's homes, the room that says the outpost was lived in. One unit per family: a bed, a table, a shelf, a toy chest. Left in order, beds are made and a bag is packed; held to the end, a barricaded door and an empty crib.
- **Player.** A bed that sets a spawn. Each unit has one **keepsake** (a book, a map, a food, a musical disc): the personal, varied loot of the hab. A lectern holds one family's **written log** that tells part of the colony's story.
- **Build.** A central aisle with units on both sides, each unit a bay behind a partition (as ships' cabins). Two to five units by room size.
- **Varies.** Unit count; unit kind (single, couple, family with a child's corner); whether the aisle has a window onto a garden (living wing beside gardens only); which unit holds the log.
- **Ties.** Lost crew spawn here; the log is an archive-style book; a spawn point is the only safe bed until the infirmary.

### Dormitory (Variant of the bunk room)
- **Story.** The single crew: rows of bunks, lockers, a notice board of shift lists. A working room for the many, not the homes of the few.
- **Player.** The mob room: sleeping lost crew lie in the bunks and wake when the door opens, so it is **an ambush with the loot (one locker each) as the lure**.
- **Build.** The ship bunk room's bays repeated in two long rows, a locker and partition each. A row of lockers at the foot.
- **Varies.** Bunk count; number of occupied bunks; row length. At danger 0 it is empty and only reads as a place.
- **Ties.** [`SiteThreats`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/SiteThreats.java) places the sleepers; the sealed-wing cost applies.

### Commons (Variant)
- **Story.** Where the crew ate and met: long tables, a stage for a fiddler, a school corner with a slate and small desks.
- **Player.** Food on the tables; the place where the largest group of lost crew gather, a fight to be planned.
- **Build.** The commons table ring made two rooms long with a stage and a school corner.
- **Varies.** Corner (school, game table, jukebox with a disc); table count; stage or none.
- **Ties.** Joins a kitchen by a door; the disc is a loot item that the jukebox plays.

### Kitchen (Variant)
- **Story.** A galley sized for a crew: furnaces, smokers, a pot, a larder.
- **Player.** Food, and a **cooking spot**: furnaces and smokers the player can use mid-dungeon, so the room is a utility and not just loot.
- **Build.** The existing kitchen with a second line of furnaces and a larder wall of barrels in rows.
- **Varies.** Galley or bakery; larder full, empty or looted.
- **Ties.** Opens onto the commons; fuel for the furnaces is why the machine hall matters.

### Infirmary (Variant)
- **Story.** A small clinic: beds behind curtains, a potion shelf, a cauldron. The **quarantine ward** variant has iron-barred beds and a sealed door, the first hint of what went wrong.
- **Player.** A **safe bed** (spawn point) and potions of healing. The quarantine ward holds the rare find and a strong mob.
- **Build.** The existing infirmary with beds in bays; the ward variant uses iron bars with solid posts (bars do not join doors).
- **Varies.** Clinic or quarantine; bed count; potions present.
- **Ties.** The rest function stands in for a healing mechanic until there is one.

### Chapel (New: `CHAPEL`)
- **Story.** A quiet room with a wall of names, one for every crew member. A colony that lost people kept this room clean.
- **Player.** No fight and no loot but one **rare book** on the lectern and the wall of names: the fewer names read, the later in the colony's story. It is a place to recover and understand.
- **Build.** Rows of benches facing a shrine; a wall of signs with names generated from the crew size; one item frame of a personal object.
- **Varies.** Names; the shrine (candles, a garden bed, a stained glass wall); at most **one per hab**.
- **Ties.** Reads with the archive's logs; its absence is also a story (a hab that never had one).

## Works

### Workshop (Variant: fabrication shop)
- **Story.** Where the crew made the parts they could not import.
- **Player.** Tools and a working set of every bench, so a player can **craft here**; the loot is made tools and a few spare parts.
- **Build.** The station workshop with a second row and a gantry along one wall.
- **Varies.** Which benches; a half-finished part in a vise (an item frame).
- **Ties.** Needs power to be worth using; sits beside stores and the foundry.

### Foundry (Variant)
- **Story.** Ore to ingots for the outpost's own needs.
- **Player.** Ingots in small amounts; a **lava channel** hazard in a deep foundry.
- **Build.** Furnaces and blast furnaces with a pit; a safe walkway with rails.
- **Varies.** One or two lines; lava or none.
- **Ties.** Fed from the ore store; fuel from the machine hall's stock.

### Ore store (Variant of the cargo hold)
- **Story.** Raw ore from the seam, in bins, waiting for the foundry or a ship that never came.
- **Player.** The **mining reward**: raw ore and some copper, scaled by the planet's richness ([finding things](habs-and-rewards.md#finding-things)). A reason for a miner to go down.
- **Build.** The cargo hold with bins of raw-ore blocks, rails and a cart.
- **Varies.** Ore kinds by planet; full, half or empty.
- **Ties.** Planet ore tables; the mine head feeds it.

### Stores (Variant, built)
- **Story.** A depot. **Player.** Spares in the works, seed in the gardens, general elsewhere. **Build.** Done ([`HabFurnishings.variant`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/HabFurnishings.java)). **Ties.** The repair bay is a rare stores find at high danger.

### Maintenance (Variant)
- **Story.** The home of the repair bots. **Player.** Bots that fix what you break, and a **bench for repair parts**. **Build.** The station room, larger. **Varies.** Bot count; some are broken. **Ties.** The repair bot system.

### Machine hall (New: `MACHINE_HALL`)
- **Story.** The outpost's heart: generators, batteries and the air system in a row, with a switch panel. It is the first room the crew built after the grove.
- **Player.** **The first objective.** Generators are out of fuel and powered doors stay shut; **put coal in the generators** and doors open, lights return and the hab's air runs. It is where the player learns what the hab was made of, with loot on the shelves of spare parts.
- **Build.** The engineering room's generators, batteries and an air supply, with a visible catwalk and a panel of levers (decorative until the hab controller). Reuses the ship systems, so each machine is the real block.
- **Varies.** One to three generators; each machine alive, dead or leaking.
- **Ties.** [Powered doors](habs-and-rewards.md#how-a-hab-or-station-defends-itself); the hab controller shows this room's machines; fuel is the same unit ships spend.

### Mine head (New: `MINE_HEAD`, optional)
- **Story.** The shaft the crew dug to the seam: a ladder down, rails, a cart, a lamp on a post.
- **Player.** An **optional deep trip** to a real cave: ore, a skeleton or two, a short air clock. Cost against the ore store's reward.
- **Build.** A square shaft with a ladder (as the hab's), a platform and rails; a landing at the bottom that the cave generator opens into. The room is buried; the cave is why it is optional.
- **Varies.** Shaft depth; cart present or lost; the cave's one hazard.
- **Ties.** Ore tables; open point on whether habs can fit a cave under them ([open points](hab-rooms.md#open-points)).

### EVA prep (Variant)
- **Story.** The airlock locker room. **Player.** Suits and canisters in lockers (the useful part). **Build.** The station room beside the lobby when the form allows. **Varies.** Suit count and condition. **Ties.** The lobby holds a smaller set.

## Gardens

### Growing bays (Variant, built)
- Crops or orchard on a custom floor. Food and seeds; the bay's soil needs water and light, which fails with the power. **Varies:** crop mix; orchard trees.

### Animal pen (Variant)
- **Story.** The colony's livestock in one big pen with a gate. **Player.** A pair of each animal in the pen, wild if left. **Build.** Nothing inside but grass; walls of a pen. **Varies.** Animal mix. **Ties.** Persistent animals spawned once from marked points.

### Seed vault (Variant of stores)
- Shelves of seeds, and one **sapling case** with the room's loot: a plain sapling of a tree that is not the golden one. **Build.** The stores room in its seed variant.

### Apiary (New: `APIARY`)
- **Story.** Bees for the crops and the honey. **Player.** Honey, honeycomb, a risk of bees if disturbed. **Build.** Beehives on a gallery with flower beds and composters. **Varies.** Hive count; one empty. **Ties.** Honey is food; the room is the gardens' calm, dangerous room.

### The grove (built)
- The central tree, the objective. See [habs](habs.md#how-it-is-generated).

## Command

### Lab (Variant)
- **Story.** Where the crew analysed the planet. **Player.** Brewing stands and potions; a **core sample** item frame as a story prop. **Build.** The station lab, larger. **Varies.** Brewing line or analysis line.

### Archive (Variant)
- **Story.** The crew's records. **Player.** Enchanting books and the colony's **written logs** that explain what happened; a filled map of the hab is one find. **Build.** The enchanting library with a log lectern. **Varies.** Which logs survive. **Ties.** The family logs and the chapel add to it.

### Observation (Variant: grove gallery)
- **Story.** A glass wall onto the grove, where the crew went to rest. **Player.** A view of the tree and a calm bench; camera of the grove. **Build.** A viewing room whose wall is glass onto the grove when it can be, otherwise a monitor wall. **Ties.** Cameras.

### Operations (Variant: camera wall, New role)
- **Story.** The crew's eyes on the hab: a bank of monitors and a desk. **Player.** **Scouting**: it shows the other rooms live, so the player picks a route. **Build.** The operations room with a giant wall screen cycling the hab's cameras ([`ViewportDefaults`](../../src/main/java/com/nullandkale/nullspace/gameplay/scene/ViewportDefaults.java)). **Ties.** Cameras are placed in more rooms for it; the hab controller replaces the Station Core stand-in nearby.

### Armory (Variant)
- Weapons on racks, armored suits on stands, a sword in a frame. The security turrets' ammunition and a **strong locked cabinet** at depth. **Varies.** What is on the rack.

### Brig (Variant)
- Cells with a single strong prisoner mob and loot behind bars. **Build.** Iron bars with solid posts. **Open:** a brig earns its place only with a function (as in the stations).

### Controller room (New: `CONTROLLER_ROOM`, exactly one)
- **Story.** The room the crew ran everything from; the last place anyone stood.
- **Player.** **The objective.** The controller block sits on a dais with a power line from the machine hall; reaching it and claiming it gives the hab's power, air and turrets, and is how a generated hab becomes a base ([layer 4](habs-and-rewards.md#how-a-hab-or-station-defends-itself)).
- **Build.** The operations desk, the Station Core stand-in until the controller exists; one door, lit, with a turret watching it at danger 2 and up.
- **Varies.** Guard strength by depth; whether the power line is cut (the machine hall must be fixed first).
- **Ties.** The hab controller screen; security turrets; the machine hall.

### Not in habs
- **Lookout** (it needs a window onto the outside), **bridge** and **receiving bay** (they serve ships), and the **cabin** (replaced by family quarters).

## Count and mix

A small hab (40 rooms) has about two or three of each wing's main rooms; a large one (225) repeats them with more variants and has several machine halls only if there are several wings sealed apart. The required rooms of a purpose always appear; **one controller room, one machine hall, one grove, at most one chapel and one mine head** in every hab.

## Open points

1. **Which new rooms to build first.** Proposed: machine hall and controller room (they make the objective), family quarters, camera wall, then the rest.
2. **Dormitory ambush.** Sleeping mobs that wake: is this the right pressure, or too many ambushes?
3. **The machine hall's objective.** Fueling generators with coal is the proposed first step; it could be a different job.
4. **Apiary and chapel.** Lore rooms; cut if they do not earn their cost.
5. **Loot lists** are for the later stocking pass and are not committed.
