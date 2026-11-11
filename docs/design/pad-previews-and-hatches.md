# Landing pads, previews and hatches

A ship on a planet's surface stands on a **landing pad**: a registered, player-built area that the game can find, measure and reserve. This document explains how a pad is defined and discovered, how the Pad Computer shows which blocks would travel, and how doors and trapdoor hatches take part in sealing a hull. The code is [`gameplay/pads`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads) ([`PadScanner`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/PadScanner.java), [`PadRegistry`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/PadRegistry.java), [`PadComputers`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/PadComputers.java), [`PadStatus`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/PadStatus.java), [`BuildPreviews`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/BuildPreviews.java)) and [`gameplay/ship/structure`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure) ([`ShipDoors`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/ShipDoors.java), [`CabinPaths`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/CabinPaths.java)).

The surrounding systems are described in [ship systems](ship-systems-and-viewports.md) and [ship block support](ship-block-support.md).

## What a pad is

A pad is a rectangle of grass or dirt, between 1×1 and 32×32, enclosed by a one-block ring of cobblestone at the same height. The ring and floor are deliberately cheap and ordinary: anyone can build one with a pickaxe and a few stacks.

The size cap and the ring define the pad's **allocation**. A ship launched from the pad is captured within the pad's footprint and up to 32 blocks above it. Everything in that column that is connected to the ship controller travels. Terrain beside the pad never does. Pads that stack at different heights can share a footprint, and a lower pad's allocation ends below the next registered pad above it ([`PadRegistry.buildHeight`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/PadRegistry.java)). Vertical launch still needs a clear column to the sky, so a stacked pad overhead is an obstruction and not an elevator.

## Placing the Pad Computer

The player places the **Pad Computer** on top of a cobblestone ring block. A corner works. Its facing only decides which way the screen faces. [`PadScanner`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/PadScanner.java) then validates:

1. The computer must stand on a ring block (the scanner reads only the layer directly beneath it, so it can never fall through to a lower pad).
2. From each neighbouring grass or dirt block it walks outward in four directions up to 32 blocks to find the ring, then checks the whole rectangle: every ring block is cobblestone, every interior block is grass or dirt.
3. If it touches two valid pads, it asks the player to move it to an unambiguous outer edge.
4. If any cell is unloaded, it asks the player to wait. It never loads terrain on its own, and distinguishes "still loading" from "invalid".

Failures name the exact block and coordinates ("Fill this hole with grass or dirt", "Repair the cobblestone ring here"). Pads on the same height may not overlap (rings included), and a computer may be placed from outside the pad.

A ship belongs to the nearest registered pad below its controller ([`PadRegistry.below`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/PadRegistry.java)), never to the first one in the file. When a pad is added or removed, existing ships' allocations are resized and rebased (`reconcileHeights`), unless a transfer is currently moving them.

## Knowing whether a pad is free

Other pads appear in landing and recall menus with a status: ready, occupied, unavailable or unknown. Listing must never be expensive or force-load remote chunks, so [`PadStatus`](../../src/main/java/com/nullandkale/nullspace/gameplay/pads/PadStatus.java) inspects pads incrementally. It has a global budget of 2,048 block reads per tick, caches each result for two seconds, and clears the cache whenever the pad registry changes. A pad whose chunks are not loaded is reported as *unknown*, which is different from a pad that was inspected and found unusable.

## Previews

Opening the Pad Computer offers **Pad preview** and **Ship preview**, toggled with arrows and Enter or by click. Both can be on at once, including before the pad is registered. Closing the terminal leaves them on so the player can look around.

- **Cyan** highlights the pad interior and its cobblestone ring.
- **Amber** outlines the blocks connected to a ship controller or ship computer in the pad's allocation. Disconnected blocks are excluded. Invalid machinery stays visible so it can be repaired. A preview is *not* approval to travel, and several controllers can be shown even though a launch needs exactly one.
- The highlights show through the hull and fade over 30 seconds (600 ticks). Geometry refreshes after edits at most twice a second without restarting the fade. After expiry each mode needs to be turned on again.
- They belong to the requesting player. They end on disconnect, death, a dimension change, removing the computer, invalidating the pad, or moving more than 160 blocks away.

The server sends a bounded list of block positions in a typed, session-authorized payload (at most the 32³ allocation, and fully enclosed blocks are filtered out). Clients draw it with Minecraft's batched gizmos, so no extra framebuffer or terrain simulation is needed. With no preview active, no block scans run.

![Cyan pad and amber connected ship previews](../images/pad-and-ship-preview.png)

## Doors and trapdoor hatches

Wooden and iron trapdoors count as doors for pressure sealing, ship discovery, stable door device ids, engineering groups, single operations, and open/close-all.

- A closed, dry trapdoor seals a **floor or ceiling opening that has a full solid block on each of its four horizontal sides**, in either half. An open or unframed hatch leaks. (Leaves are not a seal.) Opening a hatch into vacuum vents the compartment connected to it, because room air uses the real block state.
- A trapdoor used as an upright decorative panel does not seal. That is deliberate: panels may travel with the ship, but they are not walls.
- A hatch only counts as an exit if there is a usable route to it ([`CabinPaths`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/CabinPaths.java)). The route search is player-sized: it checks clearance, half-block steps, stairs, and ladders between decks, so an unreachable ceiling hatch does not satisfy the "usable exit" requirement. A floor hatch can be a downward exit.
- Native redstone keeps working, and a remote close cannot override an active redstone signal. Framed normal doors behave as before.

## What travels, and what it says when it cannot

Block entities that carry state are captured in the journal and restored on arrival: chests, barrels, shulker boxes, signs, furnaces, blast furnaces and smokers (with inventory, fuel and cooking progress), and the rest of [`BlockSupport.PORTABLE_ENTITIES`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/BlockSupport.java). See [ship block support](ship-block-support.md) for the full set. While a ship is arriving, edits to its blocks are quarantined ([`TransitBlocksMixin`](../../src/main/java/com/nullandkale/nullspace/core/mixin/TransitBlocksMixin.java)), including explosions from outside, and live block-entity ticks pause. A slow client therefore cannot make a furnace burn fuel or cook past a snapshot.

Anything not supported is refused before the move starts. The message names the block, its block-entity type and its location, and says what to remove or repair. An unsupported passenger gets its entity type and a remedy. A validation failure keeps its highlight in the world, and an unexpected internal exception is logged and shown as a readable retry message, not Java text.

## Where this is tested

[`PadScannerTest`](../../src/test/java/com/nullandkale/nullspace/gameplay/pads/PadScannerTest.java) covers every ring edge and corner, exact-layer scanning and ambiguity. Native tests cover stacked pads and allocation boundaries, preview toggles, invalid machines, hatch sealing, missing frames, ladder-only exits, redstone override, and furnaces surviving rollback at every pre-commit stage. The graphical suite places the computer from outside in Survival, drives both previews through the real terminal, checks highlight pixels, edits geometry, and checks fade and expiry. The two-client test checks that one player's highlights and actions never reach the other client.
