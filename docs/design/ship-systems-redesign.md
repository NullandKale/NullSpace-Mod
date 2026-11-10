# Ship systems: the hardware and its principles

A ship is machinery the player builds and operates. This document describes each piece of hardware, the principles that decided how it is controlled, and what the rules are for converting older builds. Fuel and power numbers are in [ship fuel, power and upgrades](ship-fuel-and-power-design.md), the screen for each block is in [the block UI reference](ship-block-ui-design.md), and the larger rendering and viewport story is in [ship systems and viewports](ship-systems-and-viewports.md).

The guiding idea: **build the ship, add fuel, install the upgrades you want, and fly it from one controller.** A first-time player should not need to understand buses, allocation or command syntax. Everything needed is on the everyday screen, and everything else is one step away under **Advanced**.

## The hardware

| Equipment | Function |
| --- | --- |
| Ship Controller | One block holding flight, navigation, docking and engineering |
| Scanner | One block with System Upgrade slots. Upgrades add range and distant destinations |
| Camera | A small mounted camera, not a full cube |
| Monitor | A thin wall-mounted screen. Adjacent tiles join into one larger picture showing one selected feed |
| Docking Base | One base directly below any standard door. Bases mate automatically, then the player opens the door and walks through |
| Computer Terminal | A programmable computer, kept distinct from the controller (see [computers](planet-computers-and-rendering.md#computers)) |
| Engine and generator | Burn real furnace fuel from shared slots in the generator. Both take System Upgrades |
| Battery | Stores electricity and takes the same upgrade item |
| Air Supply | Supplies room air |

Earlier versions had many more blocks: separate flight, docking and engineering consoles, an airlock with top controllers, connectors and a chamber pump, tier-specific machines and a fuel tank. They were consolidated because each one added a thing to learn without adding a decision. What a player chooses is *where to put things, how much to power, and where to go*, so the hardware is as small as that set of decisions allows.

Removed hardware is replaced with solid iron blocks as its chunks load, so a hull never springs a leak from an update. Ship identity, stored energy, inventories and edits survive the conversion. Cameras and monitors are mounted on backing walls; an older full-cube installation keeps its hull seal, and a newly placed unit needs a solid block behind it.

## Principles for the controls

1. **One screen for the everyday task.** The Ship Controller has one Destination selection, one primary **Engage** (with **Stop**) that runs a whole trip, the individual Launch, Jump, Land, Dock and Undock buttons under **Manual steps**, and Pilot and Ship camera, with ship status inline. Regions, landing pads and docking ports are all destinations, and the selection persists through a journey. See [ship navigation](ship-navigation.md).
2. **Show actions when they apply.** Tuning, allocation numbers and command syntax live under **Advanced**. Core actions stay discoverable, and a disabled control says what is blocking it when selected.
3. **Lists choose targets; they do not hide menus.** Choosing a contact, a pad or a room is a selection, followed by a verb.
4. **Say what is wrong and how to fix it.** A problem names the affected equipment or player and gives a concrete repair action. Expected failures return a result and not an exception.
5. **Graphical where it helps.** Engines and generators show fuel, work and output visually, with the terminal under Advanced. Scanners show upgrade slots and results. Monitors choose one feed for the whole screen.

The audit that drove this compared each old screen against the task a player was doing:

| Old screen | Problem | Now |
| --- | --- | --- |
| Ship controller, 11 actions | Vague categories hid the actual travel actions | One destination selection, direct buttons, inline status |
| Engineering, 10 actions | Repeated allocation clicks and command syntax | Power, air and fuel summaries, with tuning under Advanced |
| Docking, 10 actions | Cycling through contacts, with unrelated construction and emergency controls | Select a contact, then Dock or Undock |
| Airlock, 8 actions | Normal boarding mixed with exterior-door and emergency operations | Open the normal door and walk through, with maintenance under Advanced |
| Station core, 9 actions | One text field served as room coordinates, a name and a player | A room grid, named fields and access controls on one screen |

## Compact docking

```text
[ door upper half ]
[ door lower half ]
[   Docking Base  ]
```

One ordinary door and one base make a port. Wooden, iron and other standard doors work. No extra button block, top controller, chamber, airlock pump or inner connector is needed, and the base handles mating and door control. Leave normal walking room behind the door.

Autopilot aligns the bases and makes an airtight joint, and walking through the open doorway boards the other vessel. Cabin air stays finite: mating never refills it or exposes a connected cabin to vacuum. Undocking closes the doors before releasing the joint. The mechanics are in [space encounters and docking](space-encounters-and-docking.md), and the construction rules in [the station guide](stations.md#add-a-port-to-your-ship).

## Upgrades

Upgrade items (System, Advanced and Exotic grades) go in the engine, generator, battery or scanner, three slots each. See [ship fuel, power and upgrades](ship-fuel-and-power-design.md#upgrades) for the numbers and the removal rules.

## Fuel and power in one paragraph

Fuel is furnace fuel in the generator, and nothing else. Engines and the generator draw from it, electricity flows through one hull-connected bus, surplus charges batteries and batteries cover shortfalls. A generator with nothing to do stops burning. Loading fuel must be enough to start generation from an empty battery.

## What is still open

- Fuel endurance, output, storage and travel costs are working playtest values and need balancing against real play.
- Extra fuel grades, residues, maintenance and solar panels were proposed and are not built (see [the fuel document](ship-fuel-and-power-design.md#not-built)).
- The Air Supply has no redesign.
