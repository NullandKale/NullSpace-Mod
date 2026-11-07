# First ship: materials, placement and buttons

This is a concrete walkthrough of the current game, so we can play it and identify what needs refinement. The goal is **build a small sealed ship, launch, look around and land back at Home**. It does not use free ship delivery or dev commands.

## What counts as a minimal ship?

On the ship: one Ship Controller, one engine, one generator, one Air Supply, a sealed full-block hull, a framed door and two blocks of headroom in front of the controller. Equipment connects through touching hull blocks; there are no power wires to place.

On the ground: a registered dirt/grass pad inside a cobblestone ring, with a Pad Computer on the ring.

| Checked build | Outside dimensions | Inside | Occupied ship blocks |
| --- | --- | --- | ---: |
| Tiny cabin | 3 wide x 3 long x 4 high | One standing column, two blocks high | 34 |
| Walkthrough cabin | 5 wide x 5 long x 4 high | 3 x 3 walking area, two blocks high | 82 |

These are checked sealed-cabin layouts, not a claim about every possible exotic build. Both launch and return without a battery, scanner, camera, Docking Base, upgrades or suit. They include one ordinary door and one full glass window. A suit is unnecessary for this specific trip because the player stays inside the sealed cabin.

Use the **5 x 5 walkthrough cabin** below. The 3 x 3 cabin proves a lower bound worth discussing, but gives the player almost no room to move or furnish it.

## 1. Gather the materials

First get ordinary Minecraft tools and access to an iron pickaxe for redstone. Gather and smelt the following. Counts include crafting the machines, their subcomponents, one crafting table, the door batch, hull, window and pad.

| Material | 5 x 5 walkthrough | 3 x 3 comparison |
| --- | ---: | ---: |
| Iron ingots | 28 | 28 |
| Copper ingots | 42 | 42 |
| Redstone dust | 15 | 15 |
| Full glass blocks | 9 | 9 |
| Cobblestone | 129 | 73 |
| Planks | 31 | 31 |
| Dirt/grass blocks for the pad | 25 | 9 |

The walkthrough's cobble is **30 for crafting + 75 hull blocks + 24 pad-ring blocks**. Its glass is **8 for machines + 1 window**. Eight logs produce 32 planks, enough for the listed 31; bring more wood for tools and other normal survival needs.

Existing grass or dirt can form the pad. The dirt count is its required floor area, not necessarily additional blocks to gather.

To obtain the ingots/glass directly, collect **28 raw iron, 42 raw copper and 9 sand**. Those are raw items, not ore-block counts. The 79 smelts take 10 coal in an ordinary furnace. Bring **another 8 coal for the ship** and a small food supply, such as 8 bread, for the walkthrough. Ship fuel and food quantities are test provisions, not a balanced opening-game decision.

Mining tools, their materials, extra smelting/cooking fuel and general survival supplies are additional. One of the two furnaces below can do the initial smelting before being used in a machine recipe.

**Design observation:** Even this small ship requires mining through redstone and collecting 42 copper ingots. That needs to be part of the first-session progression discussion.

## 2. Craft in this order

Use the actual patterns in [ship recipes](../design/ship-recipes.md), or the recipe book. This list makes the intermediate work explicit.

1. One crafting table.
2. Three batches of sticks: 12 sticks total.
3. Nine redstone torches.
4. Two batches of Circuit Boards: 8 boards total.
5. Five Machine Casings.
6. Two ordinary pistons.
7. Two furnaces.
8. Two Computer Bases.
9. Turn one Computer Base into a **Pad Computer** and the other into a **Ship Controller**.
10. Craft one **Generator**, one **Engine** and one **Air Supply**.
11. Craft one batch of wooden doors: 3 doors, of which this ship uses 1.

The result includes two spare Circuit Boards, three spare sticks and two spare doors. A Computer Base is a crafting intermediate; placing it instead of a Ship Controller will not operate the ship.

## 3. Build and register Home

Choose clear sky and a flat area. The coordinates below are relative to the front-left dirt block of the pad. North/front is negative Z; east/right is positive X. **Y=0 is the pad surface layer.**

1. Make a **5 x 5 dirt/grass square**, X=0..4 and Z=0..4 at Y=0.
2. Surround it with a complete, flush cobblestone ring, including corners. Its total footprint is **7 x 7**, X=-1..5 and Z=-1..5. The ring uses 24 cobble.
3. Put the **Pad Computer at (-1,1,-1)**, on the front-left ring corner. Its facing does not matter.
4. Right-click it. Confirm the status recognizes a valid 5 x 5 pad; **Inspect pad** checks it again if needed.
5. Keep the suggested **Home** name and click **Register pad**. Confirm the registration message. If Home is already used, the computer suggests an unused name; renaming remains optional.
6. Close the screen with Escape.

The dirt and ring stay behind. The ship needs its own floor above them. Keep the crafting table, temporary furnaces, scaffolding and other ground equipment separated from the ship by an air gap; connected blocks can be captured as part of the ship or obstruct launch.

## 4. Place the ship blocks

The ship occupies X=0..4, Z=0..4, **Y=1..4**. Its floor is one block above the dirt layer. Jump up onto the entry floor to enter; do not leave stairs attached outside the pad.

### Floor: Y=1

Place a complete 5 x 5 cobblestone floor: **25 blocks**. Do not replace the dirt pad with it.

### Lower walls and machines: Y=2

Looking down, with the front at the top:

```text
       X=0 1 2 3 4
Z=0      # # D # #     front
Z=1      # . . . #
Z=2      A . . . G
Z=3      # . . . #
Z=4      # E C # #     rear
```

`#` = cobblestone; `.` = empty cabin; `D` = door; `A` = Air Supply; `G` = Generator; `E` = Engine; `C` = Ship Controller.

Place the **11 cobblestone wall blocks**, leaving the marked openings. Then place the four machines:

| Machine | Position | Face direction | How to place it |
| --- | --- | --- | --- |
| Ship Controller | (2,2,4) | North, into the cabin | Stand inside and place it in the rear-wall opening. |
| Air Supply | (0,2,2) | East, into the cabin | Place it from inside, looking toward the left wall. Its outlet opens into the cabin. |
| Generator | (4,2,2) | West, into the cabin | Place it from inside, looking toward the right wall. |
| Engine | (1,2,4) | South, out of the rear wall | Place it from outside behind the ship. The exhaust points toward you, into open air. |

These full blocks replace wall blocks. They do not need a second layer of hull behind them. Leave the block directly outside the engine exhaust empty.

### Upper walls and window: Y=3

```text
Z=0      # # d # #
Z=1      # . . . #
Z=2      # . . . #
Z=3      # . . . #
Z=4      # # W # #
```

Place **14 cobblestone blocks** and the full glass window `W` at **(2,3,4)**. Keep the front door opening empty. `d` will be the upper half of the ordinary door.

### Roof and door

1. Fill **Y=4** with another complete 5 x 5 cobblestone layer: **25 blocks**.
2. Place one wooden door at **(2,2,0)**. Minecraft supplies its upper half at (2,3,0). The surrounding walls, floor and roof form its frame.
3. Enter and close the door. Leave the nine interior cells and their headroom free for this first check.

The hull total is 25 floor + 11 lower wall + 14 upper wall + 25 roof = **75 cobblestone**, plus four machines, two door halves and one glass block = **82 occupied ship blocks**.

## 5. Start the ship

1. Right-click the **Generator** inside the cabin.
2. Put the 8 ship coal into its **fuel inventory slots**, not the three upgrade slots. Leave upgrades empty. The normal default is enabled; only change **Electricity: Off** if you previously turned generation off. Engine fuel remains accessible when electricity generation is off.
3. Press Escape to close its inventory.
4. Stand in front of the Ship Controller, around **(2,2,3)**, and right-click it.
5. Opening the controller discovers the connected generator and Air Supply and initializes normal finite air/power automatically. No separate Upgrade systems step is needed for this new complete build.
6. Check the fuel, power and **Air here** readings. **Check ship** is optional: Launch performs the full preflight itself. If a fault appears, read its repair instruction and use **Show problem** to find the affected block. Repair it and try again.

No fuel tank item, cables, batteries or allocation edits are required. The generator's inventory is the shared fuel source for electricity and propulsion. The Air Supply must face cabin air, and the controller must face two blocks of standing room over a solid floor.

## 6. Launch, look around and return

1. While standing inside with the door closed, press **Launch** at the Ship Controller.
2. Stay aboard during loading. Wait until the travel overlay finishes.
3. Right-click the controller again and choose **Look at ship** to see the ship and surroundings without piloting it. Escape returns to the ship controls when the controller is still reachable.
4. In the controller, read **Destination**. If it is already **Home**, keep it selected. Otherwise click **Destination**, choose **Landing pads**, select **Home**, then **Set destination**.
5. Press **Land**. Stay inside until the transfer completes.
6. Open the ordinary door and step back onto the pad.

This exercise does not use Jump, Dock or Undock. Do not open the exterior door in space. A suit and a usable resource destination belong in the next exploration story; they are not required to prove this enclosed launch-and-return loop.

## Tiny layout for comparison

For the checked 3 x 3 cabin, keep the same pad/floor convention with a 3 x 3 dirt interior, a 5 x 5 outer ring and full 3 x 3 floor/roof. Its lower and upper wall layers are:

```text
Y=2       Y=3
# D #     # d #
E . A     W . #
# C #     # G #
```

The controller and generator face north into the standing column. The Air Supply faces west into it; the engine faces west out of the hull. The window is above the engine. This uses 27 cobblestone hull blocks, four machines, one window and the two halves of one door. It is a useful comparison for requirements, not the recommended first build.

## What this exposes for refinement

- The player crafts five casings, eight boards and two Computer Bases before a first launch. Are the intermediate steps earning their complexity?
- A dirt pad, raised ship floor, ring and separate ground computer are easy to confuse. The pad interaction needs to teach those distinctions through the build.
- Machine orientation has real consequences. The player needs to recognize the controller face, air outlet and engine exhaust before inspection reports a mistake.
- A new player should see why fuel, power and air became ready. Automatic initialization is useful, but its result needs to be readable.
- This proves construction and travel. The space-start ship still needs food, tools, safe access to resources and a reason to make its first expedition.

During the walkthrough, record the first place the player hesitates or needs an explanation. Refine that moment before adding another system.

[Verification record UNLINK : both exact layouts passed the focused native launch-and-return checks. Material totals were expanded from the current recipes, including vanilla subrecipes and batch leftovers.
