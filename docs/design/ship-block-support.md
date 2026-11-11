# Ship block support

This guide describes **transport and use inside a ship**: which blocks and entities can travel with a ship, and what a ship must settle first. It matches the policy in `gameplay/ship/structure/BlockSupport`, which is what ship preflight runs. It is written for Minecraft Java 26.3. The [complete vanilla block catalog](../development/ship-block-catalog.md) lists every one of the pinned registry's **1,286 vanilla block IDs**; [JSON](../development/ship-block-catalog.json) is available for tools.

The current policy makes **1,282 block IDs eligible**: 1,176 have no extra block-specific conditions and 106 have conditions such as dry states, framing, support or settled mechanisms. The remaining 4 are excluded: the three air variants and the moving piston, which is never a stable block. These counts are eligibility, not a claim that every state, arrangement, or vanilla gameplay interaction has been tested.

## Common rules

Every captured block must connect to the ship by a face, stay inside its bounded build area, and pass preflight. The ship still needs exactly one ship controller/ship computer, an engine with clear exhaust, a usable exit, a sealed boarding cabin, and clear launch/landing columns. The pad's grass/dirt floor and cobblestone ring remain on the planet. Grass and dirt **above** that floor can now be part of the ship, including gardens, previews, and equipment topology.

Airtightness is separate from transport. Full collision cubes generally seal; leaves, plants, stairs, slabs, fences, panes, rails and other partial shapes do not. Doors retain their closed matching-half/frame rules. A closed framed trapdoor seals a floor/ceiling hatch; unframed trapdoors may travel as decorative panels without sealing or counting as the required exit. An open framed hatch still needs closing before launch.

Pending scheduled block and fluid ticks travel with the ship: a running redstone clock, a pressed button or a repeater mid-delay keeps going at the destination, as with Minecraft's `/clone`. Queued block events (a piston about to fire) and pistons mid-move must still settle first. Running **block-entity** machines such as furnaces, brewing stands, and hoppers travel with their saved state; their server ticks pause during arrival quarantine and resume afterward.

## Supported blocks and contents

| Group | Blocks | Preserved behavior/data and conditions |
| --- | --- | --- |
| Hull and construction | Stone/cobble, bricks, wood/logs/planks, metals, glass, concrete, terracotta, wool, copper variants, and other ordinary dry building blocks | Block state, orientation, color, oxidation/wax state, and connected geometry. Partial shapes are usable fittings but do not seal. |
| Storage | Normal, trapped, and copper chests; barrels; every shulker-box color | Inventory, item components, names, locks, and unopened loot-table data/seeds. Double chests must remain within the captured ship. |
| Personal storage | Ender chests | The block travels. Ender inventory remains player-owned and is not copied into the ship snapshot. |
| Workshop | Crafting/smithing tables, stonecutters, grindstones, looms, cartography/fletching tables, enchanting tables, anvils | Ordinary vanilla interactions. Enchanting still needs its bookshelves and vanilla resources. Anvils need support from below and must settle. |
| Cooking and brewing | Furnaces, blast furnaces, smokers, brewing stands, campfires and soul campfires | Inventory, fuel/cooking/brewing data that vanilla saves. Campfires may be lit; open fire blocks are excluded. Vanilla campfire behavior does not add a new oxygen-consumption mechanic. |
| Automation | Hoppers, dispensers, droppers, crafters | Contents and saved cooldown/configuration/disabled-slot state. Queued dispensing/crafting must finish before departure. |
| Redstone | Wire, torches, repeaters, comparators, observers, levers, buttons, pressure plates, lamps, daylight detectors, rails and ordinary circuit blocks | State and pending ticks travel; clocks keep running. Queued block events must finish first. |
| Pistons | Normal/sticky pistons and stationary heads | Retracted pistons work; extended pistons require the matching stationary head/base and facing/type inside the captured ship. Moving or detached parts are rejected. |
| Books and music | Lecterns, jukeboxes, chiseled bookshelves, shelves, ordinary bookshelves | Books/pages, music disc and saved song progress, slotted contents, and item components. Native playback and interactions remain vanilla. |
| Decoration | Signs/hanging signs, banners, skulls/player heads, decorated pots, flower pots, bells, beds, copper golem statues, and ordinary decorative blocks | Saved block-entity data/components travel. Paintings, item frames, armor stands and other decoration entities travel too (see below). |
| Gardens and animals | Dirt/grass/farmland, compatible dry plants/crops, bee nests and beehives | Growth state, honey and stored bees travel. A hive's flower memory follows a captured flower; memories of flowers left on the planet are cleared, including stored bees' flower memories. Water plants and waterlogged fittings travel. |
| Gravity blocks | Sand, gravel, concrete powder, anvils, dragon eggs, suspicious sand/gravel | Must have native support from below. A block that is already falling travels as an entity and lands at the destination. |
| Explosives | Unprimed TNT blocks | TNT stays a block during transfer. Primed TNT travels as an entity with its fuse running, so it explodes aboard. |
| Cauldrons | Empty, water, lava, and powder-snow cauldrons | Contents are block-state levels, so these are portable; they are not free fluid cells. |
| Special equipment | Beacons, dry conduits | Saved configuration travels; power/effects recalculate from destination conditions and native structure requirements. A dry conduit can travel but cannot operate without its vanilla water/frame arrangement. |
| Sculk | Idle sensors/calibrated sensors, inactive catalysts, player-placed non-summoning shriekers | No pending vibration or spreading cursors. All shriekers travel, including naturally summoning ones. |
| NullSpace equipment | Ship Controller, generator, battery, Air Supply, engine, Scanner, Docking Base, camera, monitor and cannon | Existing shared ship systems and device IDs remain supported. The pad computer normally stays on the uncaptured cobblestone ring. Travel equipment requirements still apply. |

Vanilla dimension/environment rules still apply. Beds and respawn anchors do not retarget an existing player's saved respawn location when moved. Existing lodestone compasses keep their original dimension and position. This feature does not make beds safe in dimensions where vanilla makes them explode.

## Liquids, fire, portals and special blocks

Water, lava, bubble columns, waterlogged blocks and water plants travel as blocks, with their pending fluid ticks. Fire travels. Portal blocks (Nether portals, End portals, End gateways) travel as blocks; a Nether portal needs its frame to survive, and an End gateway keeps its saved exit. Spawners, trial spawners, vaults, creaking hearts, potent sulfur, command blocks, structure blocks, jigsaws and test blocks travel with their saved data. Block entities from other mods travel with their saved data as well.

## Entities

Every entity fully inside the ship travels with its UUID and saved state: mobs, villagers and wandering traders, animals, vehicles (boats, minecarts with their contents), armor stands, item frames, paintings, display entities, projectiles, experience orbs, dropped items, falling blocks and primed TNT. Living occupants must be inside the sealed cabin; decorations and vehicles may sit anywhere in the ship.

- **Riders** travel on their mount. A player riding a mob aboard is dismounted for the move and put back on afterwards.
- **Leashes** are kept when the holder also travels: another mob (a wandering trader's llamas), a fence knot aboard, or a player aboard. The leash is detached for the move without dropping a lead and reattached afterwards. A leash tied to something that stays behind breaks and drops its lead aboard.

## What still has to wait

| State | What to do |
| --- | --- |
| Moving piston blocks, short moving heads, broken stationary head/base pairs | Let the motion finish or repair/retract the piston. |
| An unsupported gravity block (sand with air below) | Support it or let it fall first. |
| Queued block events, pending vibrations, catalysts still spreading | Wait a moment for the device to settle. |
| Air, cave air, void air | Empty space is not captured building material. |

## How transfer preserves data

The policy is centralized in [`BlockSupport`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/BlockSupport.java) and called by the real ship scan. Vanilla structure snapshots retain block states and block-entity data. Placement and removal suppress neighbor, placement, drop and block-entity removal side effects, so transporting a circuit does not count as placing fresh TNT, firing a dispenser, extending a piston, or breaking a full container. Scheduled block and fluid ticks inside the ship are copied to the destination with the same delays.

Vanilla structure placement randomizes container loot seeds. The transfer path reloads each block entity from the exact saved snapshot afterward, preserving unopened loot instead of rerolling it. Replacing an existing snapshot first removes its block entities without emptying their inventories: emptying a jukebox can notify neighbors and pop a flower above it, which the workshop rollback test caught. Hive position changes apply to a copy of the destination snapshot; the original journal retains the source coordinates for exact rollback.

This guide concerns normal native gameplay inside the physical ship. Distant camera/third-person snapshots still have the existing limits on special block-entity visuals and animations; transport support is not a promise of complete remote visual parity.

## How this is tested and how to update the list

[`BlockSupportGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/ship/structure/BlockSupportGameTest.java) exercises portable block variants across the block-entity types with named contents and components, written books, jukebox discs and stored bees, and checks exact saved data and no dropped cargo. Further cases cover loot seeds, stationary and malformed pistons, pending ticks and events, dry and wet states, supported falling blocks, garden membership and hive flower translation. A populated workshop ship is tested through journal interruption and recovery and a real space and planet round trip. Procedures are in [local verification](../development/local-verification.md).

The complete catalog is generated by the native registry test using the production policy. Refresh with:

```powershell
.\scripts\test\test.ps1
.\scripts\build\Update-ShipBlockCatalog.ps1
```

The source report lives in that test run's disposable world. The script writes the Markdown and JSON catalog under [`docs`](..); it does not inspect or edit player saves.
