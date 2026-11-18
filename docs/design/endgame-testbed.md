# Endgame testbed ship

Status: built, **not yet compiled or run** (written November 17 while the owner was playtesting). A ship to pick in the beacon's catalog (and the starting-ship list) for testing the late game: small, one deck, and able to feed and fuel itself. Its design is a catalog entry, `endgame_testbed`, in [`catalog/ships.json`](../../src/main/resources/data/nullspace/catalog/ships.json), the last one.

## What it is

A one-deck Rivet spine ship, 32 blocks at most each way like every ship: the bridge at the nose with the docking door, a hall, four rooms in two mirrored pairs and engineering at the stern. It is planned like the other ships ([`ShipPlanner`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/ShipPlanner.java)), except that its four side rooms are named in the catalog (`rooms`) and are deeper (13 blocks) than the ship rooms it draws from a role.

| Room | What is in it |
| --- | --- |
| Farm | Wheat and carrots in beds with water, cocoa pods ripe on a wall of jungle logs, sugar cane on sand beside water, composters and a chest of seeds ([`RoomsTestbed.farm`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/RoomsTestbed.java)) |
| Pen | One fenced pen with a gate, hay and a trough: six chickens, two cows, two sheep and two pigs, and a chest of food |
| Lava farm | Three columns, each a source of lava over dripstone with a pointed dripstone hanging over a cauldron, walled in stone. The dripstone fills the cauldron with lava, and a bucket of lava burns in a generator for a hundred items' worth. A chest of buckets, lava and coal blocks |
| Vault | An enchanting table with bookshelves, smithing, anvil, grindstone, stonecutter, two brewing stands, and chests of gear, magic, materials, food, this mod's space items and treasure from the loot tables |
| Engineering | The usual generators (a stack of coal in each), air supplies and engines |

## The stock

The rooms mark each chest as a `stock` point with a kind, and [`RoomEntities`](../../src/main/java/com/nullandkale/nullspace/gameplay/shipyard/RoomEntities.java) fills it when the ship is delivered ([`TestbedStock`](../../src/main/java/com/nullandkale/nullspace/gameplay/shipyard/TestbedStock.java)): a full set of netherite tools and armor, an elytra, totems, a trident and a mace; ender pearls and eyes, nether stars, enchanted golden apples, experience bottles, lapis and anvils; diamond, emerald, netherite and stacks of blocks; food; the mod's upgrades of every tier, armored suits, machines, salvage turrets, repair bays, green gems and blocks, a black hole, the beacon helmet and golden apple saplings; seeds, cocoa beans, bone meal and nether wart; and lava buckets and coal. The treasure chest takes the highest vessel treasure table.

## Not done

Nothing checks yet that the dripstone fills a cauldron in a delivered ship (it is vanilla's mechanism and needs random ticks), that the ship delivers on a 32 by 32 pad with its ramp, or that all the stock fits and works in play. [`EndgameTestbedGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/ships/EndgameTestbedGameTest.java) checks the plan, the crops, the lava farm, the animals and the stock chests, and has not been run. If the testbed's rooms do not fit, the catalog logs an error and leaves it out rather than failing to start.
