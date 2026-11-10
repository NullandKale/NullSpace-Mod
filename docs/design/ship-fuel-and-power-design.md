# Ship fuel, power and upgrades

A ship's machinery is a small economy: fuel goes in, electricity and thrust come out, and the player's job is to keep them balanced. This document describes how fuel, generators, batteries, engines and upgrades work, and the reasons for the choices. The hardware overview and its UI rules are in [ship systems](ship-systems-redesign.md); the controls on each block are in [the block UI reference](ship-block-ui-design.md). The code is `gameplay/systems` (`FuelSystem`, `PowerSystem`, `ShipSystems`, `SystemsUpgrade`) and `model/power/PowerBus`.

## The loop

Build an engine and a generator, load fuel into the generator, and keep the engine's exhaust clear. The generator turns fuel into electricity, which powers the controls, air, doors, cameras and engines through one shared bus. Surplus charges batteries, and batteries cover a shortfall. Engines draw on the same fuel for thrust. Everything on this page is one ship's loop, and a ship can fail in two different ways: a full battery and no fuel, or fuel and a dead bus. The controller names which.

## Fuel

**Any item the server's furnace accepts as fuel works.** A stick, a log, coal, a lava bucket: each is valid, using its relative burn value, so a stick simply lasts less time than coal. Fuel goes in the generator's fuel slots like a furnace's, by hand or through a hopper. There is no tank block or pipe. A container return (an empty lava bucket) goes to an output slot.

- **Hoppers.** A hopper inserts fuel from the top or sides and extracts container returns from the bottom. Invalid fuel stays in the hopper. The upgrade slots are never reachable by hoppers. Output space is reserved *before* a container-returning fuel is consumed, so a full output cannot delete or duplicate a bucket. Fuel automation needs no open GUI.
- **One store.** Engines draw their fuel from the generator's store. Each item is consumed once, and its remaining burn energy persists between uses and through travel. Electrical systems keep drawing fuel while the ship is stationary, which is why an idle ship still burns about four fuel an hour.
- **Units.** Internally, fuel is furnace burn time × 8, so one coal is 12,800 units, and the player sees it as 1.00. Amounts are always integers, so rounding can never make fuel from nothing.

### What things cost

| Action | Cost |
| --- | --- |
| A jump within a system | 0.50 fuel |
| A jump between systems | 1.00 + 0.10 per light-year (see [the galaxy](star-systems.md)) |
| Launch or landing | `(400 + captured blocks) × 2` units, about 0.25 fuel for a typical ship |
| Hand flight | Spent each tick any flight input is held |
| Electricity | A steady draw of power, at 11 power per fuel unit |

Engine tiers reduce a travel cost to `(cost × 4 + 3 + tier) / (4 + tier)`, always at least 1. The controller shows available fuel and the cost before the player acts. A generator that has nothing to power or charge throttles down and stops burning.

## Power

Electricity is **integer power units per tick**, and one delivered unit consumes one unit of energy a tick (`PowerBus`). It has six subsystems: **controls, air, doors, engines, cameras and weapons**. Each tick the bus pools generation plus what the batteries can discharge, then serves the subsystems in priority order. The default order is controls, air, doors, cameras, engines, weapons, with an option to put air first. A subsystem receives the smaller of what it asked for, its equipment's capacity and what is left. Whatever generation is left over charges batteries, up to their rate and capacity, and anything beyond that is wasted.

- **Generators** deliver 16 power plus 8 per upgrade tier, limited by the fuel they hold. A generator the player turned off supplies nothing.
- **Batteries** hold 1,200 energy per tier step (`1200 × (1 + tier)`) and charge and discharge at `4 × (1 + tier)` per tick. They store electricity, never fuel. A battery is a short buffer, enough for roughly 15 seconds of a 4-power load at tier 0, and not a survival blackout reserve. A new battery starts empty.
- **Engines** need power for their support electronics, and their thrust budget comes from how many are switched on with a clear exhaust: each counts as one plus its tier, to a maximum of eight units of thrust, further limited by the power the engines bus delivers. Without fuel, controls power or a working engine the ship gets no thrust.
- **Air Supply** takes 4 power each for normal output and up to 8 for 150%, which is why the air request is per supply.

Loading enough fuel to start generation from an empty battery must always be possible. That is the rule that keeps a new player from stranding a ship on its first launch.

## Upgrades

Upgrade items are one family used the same way in the engine, generator, battery and scanner, and each block has three slots. A block's tier is **the number of upgrades installed, capped by the best grade installed**: no items gives stock operation, and a tier needs both enough items and a good enough one. There are no separate tier blocks.

| Item | Recipe | Lets a block reach |
| --- | --- | --- |
| System Upgrade | Iron, circuit boards and redstone | Tier I |
| Advanced System Upgrade | A System Upgrade ringed by amethyst shards | Tier II |
| Exotic System Upgrade | An Advanced one with netherite scrap, or between two echo shards | Tier III |

So three plain System Upgrades still only make Tier I, and Tier III needs an Exotic one plus two more. Because the grades need materials from further away, a ship's reach (engine tier sets jump range) follows how far its crew has gone.

| Block | What each tier improves |
| --- | --- |
| Engine | Thrust and travel efficiency (the cost formula above, and one more effective engine) |
| Generator | Output (+8 power per tier) |
| Battery | Capacity and transfer rate |
| Scanner | Range (1,024 blocks doubled per tier) and, from the first, distant destinations |

Installed modules are real items. Installing or removing one never refills fuel, creates charge, duplicates items or loses stored energy. A battery module cannot be removed while the charge would not fit the smaller capacity, and its screen shows the threshold.

One family of items for every block was a deliberate simplification of an earlier design with separate tier recipes: an upgrade found in a derelict can be used in any machine without checking which one it is for.

## Starting supplies and old ships

A player-placed generator starts empty. A delivered ship, a starting ship and a generated station each get a finite starter stack of coal once (32 for a starting ship, 64 for a station or a shipyard delivery). Existing builds keep what they have and need fuel loaded by hand. Inspection, upgrading or revisiting never refills anything.

## Not built

These were part of the original proposal and are kept as ideas, not as current behavior:

- **Residues.** Burning fuel producing items (mineral ash and carbon-rich residue) with farming and construction uses, and output reservations while fuel burned.
- **Clean fuels.** A refined pellet that burns with less waste, and fuel classifications that make dirty fuel's waste the cost of burning it.
- **Engine alternators.** An engine able to generate electricity at about a 10% fuel penalty, in a thrust mode and a stationary mode.
- **Solar panels** sampled from the server's celestial definition and ship pose, accounting for weather, occlusion and eclipses.
- **Hardware tiers as separate blocks**, with engines at 2, 4 and 8 thrust units and batteries at 1,200, 4,800 and 19,200 energy. The upgrade item replaced this.
- **Reserves and priorities**: battery modes (charge-only, discharge-only, emergency reserve) and a reserve percentage that protects life support first.
- **Separate engine fuel inventories** with hopper-fed residue slots, instead of the shared generator store.

If any of these are revived, the principles above hold: fuel and electricity are separate ledgers with integer arithmetic and no free energy, no resource is changed by inspecting it, and UI previews never consume anything.

## Where this is tested

Unit tests cover the power bus allocation (`PowerBusTest`). Native tests (`ShipSystemsGameTest`, `ShipHardwareGameTest`, `EngineeringGameTest`) cover fuel costs, upgrade effects, conservation, hopper automation, container returns, upgrade install and removal, and exact totals through travel rollback. See [air balance testing](../development/air-balance-testing.md) for the air side.
