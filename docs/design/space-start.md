# Starting in space

A NullSpace world can open the ordinary Minecraft way, on the ground, or aboard a working ship in orbit. This document explains what the space start gives the player, how it is chosen, and why it is built to be resumable and to never grant a second ship. The code is `gameplay/start` (`SpaceStart`, `WorldStartSettings`), with the creation-screen options in the client.

## What the player gets

A new player begins inside the chosen ship, in its sealed cabin, in orbit above a planet. The ship already works: the cabin has air, the generators hold coal, and the ship's systems are enabled and set to sensible defaults. In the inventory are 16 bread, 16 coal, an iron pickaxe and axe, 32 cobblestone, a full spacesuit and the field guide. The helm is next to them, and everything else (fuel, travel, landing) is learned by playing.

The aim is that a first minute needs no setup: the player can breathe, see where they are, and has an obvious thing to do, which is to use the Ship Controller.

## Choosing the start

**Single-player.** On *Create New World*, the *Start* control switches between **On a planet** and **Aboard a ship**. The *Starting ship* button picks a design from the Ship Beacon catalog. Rivet Bolt is the default. Game mode, difficulty, seed and world generation remain the ordinary Minecraft choices. The choice is stored in the world as `nullspace/world-start.properties`.

![Space-start choices in the normal world creation menu](../images/world-start-options.png)

**Dedicated server.** Add to `server.properties` and restart:

```properties
nullspace-space-start=true
nullspace-starting-ship=rivet_bolt
```

Both paths use the same two property names (`WorldStartSettings`), so single-player and server behave identically. The ship id must be one of the catalog ships: `rivet_bolt`, `rivet_hauler`, `rivet_ranger`, `halcyon_swift`, `halcyon_courier`, `halcyon_horizon`, `bastion_ward`, `bastion_tender`, `bastion_pioneer`. These are the presets defined in `data/nullspace/catalog/ships.json`; see [the ship generator](ship-generator.md).

An unknown ship id **stops the server at startup** with a clear message. This includes the retired pre-0.5 ids such as `sparrow`: the shipyard maps those to their replacements, but the start setting checks the current catalog only, so a server that still names one must be changed. The alternative, quietly falling back to another ship, would hand every new player a ship the operator did not choose. The settings are read once, when the server starts. This also means that an update which renames or removes a preset needs the property changed before the server will start.

## Who counts as new

A player gets a start once. `SpaceStart.firstArrival` decides:

- if the player's saved start record says it completed, they never get another;
- if a record exists but is not complete, construction was interrupted and resumes;
- otherwise a player with an existing `playerdata` file, or its backup, has played this world before and is left where they are.

So switching the option on for an existing server does not move veterans, and rejoining, dying or changing the server's starting ship never grants a second ship or refills supplies. Death follows the normal respawn and [ship recovery](ship-recall.md) rules.

## How a start is built

New arrivals are queued at login and commissioned on a later server tick, never inside the join callback. Fabric's join event runs before the player has been fully added to the world, so teleporting or placing blocks there would leave things in the wrong dimension.

`SpaceStart.start` is written so that every step can be repeated safely:

1. **Record the intent.** A `spaceStarts` entry is saved with the player's new ship id, the chosen preset and the chosen planet. Everything after this can be retried and will produce the same ship.
2. **Pick the planet.** The planet is chosen from the home system's landable planets, using the world seed and the player's UUID, so each player's planet is stable, different players can start above different planets, and moons are excluded. The planet's landing site is ensured at the same time, so the player can actually land.
3. **Find space.** The first free, completely empty 32×32×32 region in the space dimension is used. Regions are 4096 blocks apart and the search checks both blocks and entities.
4. **Reserve identity before blocks.** The ship record is added to the saved metadata and saved *before* any block is placed. If the server stops partway, the next attempt finds the same ship and carries on.
5. **Place the blueprint.** Air is filled in; an existing block that already matches is accepted; a block that differs is an error, so a retry never overwrites something that a player or another system put there.
6. **Provision.** Generators get coal, systems are upgraded to the current version, and default requests are written for air, power and doors.
7. **Verify the cabin.** The cell in front of the helm and the one above it must be breathable. If not, the start fails instead of placing a player in vacuum.
8. **Place the player.** They are added to the crew, teleported to the helm, given supplies once (a tag stops a second grant), their air is filled, and the start is marked `completed`.

A failed attempt is logged, the player is told it will retry, and the next try is scheduled for one minute later (1,200 ticks). An interrupted start never rerolls the planet and never replaces conflicting blocks.

## Why this shape

- **Per-player ships.** In a shared server each player has their own ship in their own space region, so they cannot grief each other's first hour and the launch allocator treats them like any other ship.
- **Same property names everywhere.** One parser serves the creation screen, the world file and `server.properties`.
- **Fail loudly on configuration.** A wrong ship id is an operator error that should be seen immediately.
- **Idempotent construction.** Servers stop at inconvenient moments, and a half-built starting ship is the worst possible first impression.

## Where this is tested

`SpaceStartGameTest` covers independent players, old player backups, interrupted setup and resumption. `SpaceStartClientGameTest` creates a world through the real menu, waits for the visible cabin, quits and reopens the same ship.
