# Simpler ship systems

Status: implemented. See the [editable block UI reference](ship-block-ui-design.md) and [short station guide](stations.md) for operation. Recipes and deliveries use the consolidated design. Removed hardware becomes solid iron blocks when its chunks load.

Build the ship, add fuel, install the upgrades you want, and fly it from one controller.

Edit the screens and controls in the [block-by-block UI design](ship-block-ui-design.md).

| Equipment | Function |
| --- | --- |
| Ship Controller | One ship controller containing flight, navigation, docking and engineering. |
| Scanner | One scanner block with System Upgrade slots. Upgrades add long-range capability. |
| Camera | Keep a physical block, but use a small mounted camera instead of a full cube. |
| Monitor | Thin wall-mounted screen. Adjacent tiles form one larger picture with one selected feed. |
| Docking Base | One Docking Base directly below any standard door. Bases mate automatically; open the door and walk through. |
| Computer Terminal | Retain its distinct programmable-computer role. |
| Engines and generators | Consume real furnace fuel from shared inventory slots inside the generator. Both accept System Upgrades. |
| Battery | Keeps stored electricity and accepts the same System Upgrade item. |
| Air Supply | Supplies room air; no hardware change agreed yet. |

**Everyday controls**

- Ship controller: one terminal screen with one Destination selection, separate Pilot, Ship camera, Launch, Jump, Land, Dock and Undock controls, and inline ship status. Planets/regions, landing pads and docking ports are all destinations; the selection persists through the journey.
- Engine and generator: graphical controls with visible fuel/work/output information; terminal under Advanced.
- Scanner: graphical upgrade slots and scan results.
- Monitor: select the feed for the whole screen; tiles join automatically in a rectangle.
- Show temporary actions when relevant. Keep tuning, allocation numbers and command syntax under Advanced.
- Core actions remain discoverable; disabled controls explain their blocker. Selection lists choose targets rather than hiding additional action menus.
- Problems name the affected equipment or player and give a concrete repair action. Normal blocked actions return results rather than exceptions.

**UI audit: simplify by task**

| Current screen | Problem | Everyday view |
| --- | --- | --- |
| Ship Controller: 11 actions | Vague categories hide the actual travel actions. | One Destination selection, direct Pilot/Launch/Jump/Land/Dock/Undock buttons and inline status on one screen. |
| Engineering: 10 actions | Repeated power allocation clicks and command syntax. | Power, air and fuel summaries; tuning under Advanced. |
| Docking: 10 actions | Cycling through contacts and unrelated construction/emergency controls. | Select a contact, then Dock or Undock. |
| Airlock: 8 actions | Normal boarding mixed with exterior-door and emergency operations. | Open the normal door and walk through; maintenance under Advanced. |
| Station core: 9 actions | One text field doubles as room coordinates, a name and a player. | Room grid, named fields, Build buttons and access controls together on one terminal screen. |

**Fuel**

Load the generator's fuel slots like a furnace, by hand or through a hopper. Any item accepted as fuel by the server's furnace rules is accepted here, using its relative burn value. Container returns, such as an empty lava bucket, go to an output slot. No separate tank block or fuel pipes are required.

Standard hoppers insert furnace fuel through the top or sides and extract finished container returns from the bottom. Invalid fuel stays in the feeding hopper. Upgrade slots are excluded from hopper access. Output space must be reserved before consuming fuel that returns a container; a blocked output must never delete or duplicate buckets. Fuel automation needs no open GUI or manual action for each item.

Connected engines draw from this one store for propulsion. The generator draws from it to make electricity for equipment and to charge batteries. Each fuel item is consumed once; its remaining burn energy persists between uses and through travel. Engines consume fuel while doing work. Electrical systems can still consume generator fuel while the ship is stationary.

Electricity uses the shared hull-connected bus. Generation supplies current demand, surplus charges batteries, and batteries cover a shortfall. The generator throttles down when no electrical work or charging is needed. Batteries store electricity, not propulsion fuel. Loading fuel must be enough to start generation from an empty battery.

Burn rates, output numbers and launch/landing/jump costs remain to balance. Extra fuel grades, residues, maintenance and solar panels are not agreed requirements. The longer [fuel proposal](ship-fuel-and-power-design.md) retains those options.

**One upgrade item**

Use the same reusable **System Upgrade** item in the engine, generator, battery and scanner. Each block has three upgrade slots. Empty slots give stock operation; one installed item gives upgrade Tier I, two Tier II, and three Tier III. No separate tier-specific blocks or upgrade recipes are required.

| Block | What its upgrades improve |
| --- | --- |
| Engine | Available thrust and fuel efficiency. |
| Generator | Electrical output and fuel efficiency. |
| Battery | Energy capacity and charge/discharge rate. |
| Scanner | Scanning capability and range, including long-range scanning. |

Installed modules remain real items. Installing or removing them must not refill fuel, create charge, duplicate items or lose stored energy. Battery module removal is blocked until charge fits the reduced capacity. The main screen shows the required threshold.

## Compact docking

Put a door on a Docking Base. Choose a destination and dock. Open the door and walk through.

```text
[ door upper half ]
[ door lower half ]
[   Docking Base  ]
```

One ordinary door item and one base make the port. Wooden, iron and other standard doors are supported; no extra button block, top controller, dedicated chamber, airlock pump or inner connector is required. The base handles mating and door control. Leave normal walking room behind the door.

Autopilot aligns the bases and makes an airtight joint. Crossing the open doorway automatically boards the other vessel. Cabin air remains finite; mating must not refill it or expose a connected cabin to vacuum. Undocking closes the doors before releasing the joint. Extra chambers and inner doors are optional; each working dock uses a base below its outer door.

**Still to settle**

1. Fuel endurance, electrical output, storage capacity and travel costs.
2. Upgrade performance per tier and safe removal of battery upgrades when charged.

Existing ship identities, stored energy, inventories and edits must survive conversion. Thin cameras and monitors mount on backing walls; converting an old full-cube installation must not leave a hull leak.

## Initial tuning

These are working playtest values, not permanent balance:

| System | Stock | Each installed module |
| --- | --- | --- |
| Generator | 8 electricity/tick | +4 output/tick; better fuel efficiency |
| Battery | 1200 energy, 4 energy/tick charge/discharge | +1200 capacity and +4 transfer/tick |
| Engine | 1 effective engine, bus limit 3 | +1 effective engine, +2 bus limit; lower travel fuel cost |
| Scanner | 1024 local range | Doubles range; first module enables distant destinations |

Each furnace burn tick supplies 8 burn-energy units. Launch and landing cost `400 + captured block count` before engine efficiency. Local jumps cost 2000 and interstellar jumps 8000 before efficiency. Engine efficiency scales a cost by `4 / (4 + best active engine tier)`, rounded up. The controller shows available fuel and costs before travel. Docking and piloting spend fuel while maneuvering. Fuel spent doing electrical work is separate from propulsion work.

A new player-placed generator starts empty. New delivered ships and generated station generators receive a finite starter stack of coal once. Existing builds are preserved and need fuel loaded manually; inspection, upgrades and revisiting never refill them. Legacy full-cube cameras and monitors keep their old hull seals. Newly placed units are wall mounts and need a solid block behind them.
