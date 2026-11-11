# Ship block UI reference

This is the block-by-block reference for every ship, station and computer block: what its screen shows, what each control does, and why. The [UI design](ui-refinement-design.md) explains the shared behavior and the reasons behind it, and the [audit](ui-audit-notes.md) records the problems that led to it. The [player stories](player-stories.md) and [first-ship walkthrough](../player/first-ship-walkthrough.md) explain the player tasks.

The [hardware decisions](ship-systems-redesign.md), [fuel/power design](ship-fuel-and-power-design.md) and [recovery rules](ship-recall.md) provide the underlying system reference.

The first [November 5 navigation chunk](../development/playtest-chunks.md#chunk-1-navigation) implements **Engage**. Right-click the Scanner block for its hierarchical destination browser; **Set destination** updates the course directly. The controller shows the destination and operates the trip. There is no marking step or controller Scanner feed. Basic local planet/pad navigation needs no Scanner; interstellar travel requires a powered stock Scanner. **Settings / upgrades** in the Scanner opens its equipment inventory.

Captured from the focused GUI pass: [generator fuel slots](../images/ui-generator-fuel.png) and [controller repair feedback](../images/ui-controller-repair.png).

## Block index

| Block | Interface | Main task |
| --- | --- | --- |
| [Ship Controller](#ship-controller) | Terminal | Select a destination and Engage; manual flight and diagnostic travel steps remain available. |
| [Generator](#generator) | Inventory GUI + Advanced terminal | Load ship fuel and supply electricity. |
| [Engine](#engine) | GUI + Advanced terminal | Check thrust, fuel, controls and the exhaust. |
| [Battery](#battery) | GUI + Advanced terminal | Store electricity and install capacity upgrades. |
| [Air Supply](#air-supply) | GUI + Advanced terminal | Check this vent's room and find air problems. |
| [Scanner](#scanner) | GUI + Advanced terminal | Browse places and upgrade sensing. |
| [Camera](#camera) | GUI + fullscreen view | Name and inspect a mounted camera. |
| [Monitor](#monitor) | In-world picture + GUI | Choose a camera for the whole wall. |
| [Ship Cannon](#ship-cannon) | GUI | Inspect readiness; fire from flight controls. |
| [Docking Base](#docking-base) | Door + local GUI | Connect, open the door and walk through. |
| [Station Core](#station-core) | Terminal | Place rooms and manage named builder access. |
| [Pad Computer](#pad-computer) | Terminal | Register a pad and recover an empty ship. |
| [Ship Beacon](#ship-beacon) | Catalog GUI | Preview and deliver a ship. |
| [Computer Terminal](#computer-terminal) | Programmable shell | Run programs and scripts. |
| [Computer Base](#computer-base) | No operating screen | Craft a functioning computer. |

## Shared controls

- Opening a screen does not start travel, delivery or construction. Existing automatic ship-systems initialization remains.
- Core actions stay visible with their known blockers. Movement still runs full server validation; a visible action is not a guarantee that the hull passed preflight.
- Switches name their scope: Device On/Off or Electricity On/Off. A matched monitor wall shares its setting.
- Machines keep normal item slots, drag, split and shift-click. A generator's fuel inventory belongs to that actual block; it is not copied around the ship.
- Generator, Engine, Battery and Scanner have three upgrade slots. A block's tier is the number installed, capped by the best grade (System, Advanced, Exotic), so zero is stock (see [fuel and power](ship-fuel-and-power-design.md#upgrades)). Hover the slots for current/next benefits. Upgrades never refill fuel or charge.
- Ordinary repairs are on the normal screen. Details shows exact numbers; Advanced opens scoped engineering controls.
- Camera, monitor and dock names remain visible. Other machines use Edit name to reveal the field inline. Save name saves that name only.
- A picker highlights a candidate without applying it. Set destination waits for the server. A page/filter change cannot silently pick a different target.
- Close returns to the world. Back from a child view uses a fresh server validation. Retained drafts are UI state, not permission to reopen an old inventory.
- Machines use a 320 x 240 panel to fit Minecraft's minimum GUI area. Inventory positions and hit targets match that layout.

## Ship Controller

One terminal with one saved Destination and one primary **Engage** action. The trip preview shows its departure, travel and arrival steps. **Disengage** stops after the current safe transfer/departure. Set distant destinations at the Scanner block; the controller retains basic local planet/pad choices.

The five individual travel actions below are now under **Manual steps**, for troubleshooting:

```text
SHIP CONTROLLER
Here: Sol / Terra / Surface
Ship name
Fuel: loaded   Power: online   Engines: ready   Air here: safe
Destination: Home                                  [Choose]
Sol / Terra / Landing pad
Next: Launch | Movement still runs full checks
[Launch] Leave this planet          <or blocker>
[Jump]   Go to another region       <or blocker>
[Land]   Land at destination        <or blocker>
[Dock]   Connect to dock            <or blocker>
[Undock] Leave connected dock       <or blocker>

[Fly ship] [Look at ship] [Close all doors]

[Advanced] [Check ship] [Show problem]
<actual approach / connection status>
<contextual consent/cancel controls>
                                          [Details]
<word-wrapped problem or action result>

                                                   [Close]
```

Here is the ship's current location; the destination is a separate value. Choose a landing pad, station/ship or planet's orbit, then Engage. The course performs the required phases automatically. A planet-only choice ends in orbit; it does not promise a landing pad. Station destinations choose a free berth on arrival.

Travel between regions starts a [timed FTL journey](ftl-journeys.md). The loading screen closes during cruise; the HUD and controller show the destination and remaining time. The Trip panel's **Visit signal (optional)** action pauses the paid journey, selects the encountered vessel and automatically begins docking after arrival. Disengage cancels the approach. **Continue journey**, in the same Trip panel, undocks if needed and restores the original paid journey and remaining progress without another jump charge; a selected vessel destination continues into docking automatically. Individual Land and Dock controls remain available under Manual steps.

The paid destination stays fixed during cruise. Encounter ports appear in the picker after reaching their region, so they do not become unreachable jump choices elsewhere in the chart. Resume explains when fuel or propulsion power is missing.

Station creation is no longer a Controller button. In space, hold a Station Core and Shift-right-click empty space or a non-interactive block at the intended site. Existing ship/construction readiness and core-consumption protections still apply.

Check ship is optional. Launch performs full preflight. Show problem marks a diagnosed location; for an insufficient-fuel action it points to a connected generator when one exists. A controller without a usable ship/pad opens a setup screen with a repair message rather than the older travel workflow.

Details contains exact fuel reserve/costs and electrical figures. It does not claim a guaranteed round trip. Air here describes the current room; ship inspection retains occupant/cabin checks. Approach, consent, capture and undocking follow actual server states. Only a captured connection gets the open-door boarding hint.

Fly ship opens manual controls; Look at ship opens observation without steering. Controls, Brake, Fire and Back are visible in the flight view. Keyboard controls remain available. Escape returns to validated ship controls, retaining local picker state.

**Notes:**

## Generator

Fuel slots are the main inventory: six inputs and one Empty containers output, plus the three upgrade slots. Normal operation shows Fuel loaded / No fuel stored, ship output, demand, charging and the actual working/idle/fault state. Exact reserve and unit output limits remain under Details and upgrade help.

Put any furnace fuel in the input slots. Top/side hoppers insert fuel; a bottom hopper extracts empty containers. Hoppers do not insert upgrades. Electricity generation and propulsion use the connected generators' shared fuel automatically.

Electricity Off stops this generator's electrical output; engines can still use its fuel. If the ship supply has been disabled, Restart ship supply appears directly beside the fault. It invokes the existing supply/control recovery and grants no resources. A blocked container return points to the output slot.

Action messages such as Name saved are separate from live machine status, so they cannot conceal an ongoing power/fuel fault. Edit name, Details, Advanced and Close are direct controls.

**Notes:**

## Engine

Shows thrust, readiness and a distinct fuel/control/connection/exhaust problem. Fuel goes into a generator. There is no engine refuel inventory or throttle submenu; flight/autopilot provides demand.

Device On/Off, three upgrade slots, Show exhaust, Edit name, Details, Advanced and Close are direct controls. The exit highlight also helps explain mounting direction when the exhaust is clear. Upgrade help states maximum thrust and explains that travel efficiency uses the ship's best engine tier.

**Notes:**

## Battery

Shows stored/capacity charge and actual Charging / Discharging / Idle state. Details gives the transfer limit. Device On/Off, upgrades and Advanced are direct controls.

Removing a capacity module is refused if the remaining capacity cannot hold the charge. The normal screen states the threshold. Installing a module never adds stored energy.

**Notes:**

## Air Supply

Shows the vent's room air, actual pumping/power status and distinct opening/leak problems. Device On/Off changes the machine, not just the display. Show air opening and Show leak highlight the corresponding location. A healthy room reports No leak; it does not require a manual leak check every visit. No upgrade slots are assumed for this block.

**Notes:**

## Scanner

Right-click the Scanner to open the destination browser, organized by star system and planet/moon. Select a station/ship, landing site or orbit and press **Set destination**. It updates the ship's destination without moving it; Engage remains at the controller.

**Settings / upgrades** opens the inventory screen with upgrade slots, device power and sensing details. **Open scanner** returns to the shared destination browser. Results refresh automatically; no Scan step is required.

Selection uses stable destination IDs. Paging or filtering clears an off-page candidate; a removed target never leaves a different place selected. Empty/unpowered selection is unavailable, with the actual scanner problem visible. Set destination names the accepted place in the result line and does not move the ship.

Stock sensing range is 1024; tiers I/II/III provide 2048/4096/8192. A powered stock Scanner can already list registered distant destinations and enable interstellar travel. Upgrades improve sensing rather than unlocking basic travel. The controller and Scanner share the ship's single saved destination.

**Notes:**

## Camera

Name, Save name, Device On/Off, mounted facing, embedded preview, View fullscreen and Advanced are on the setup screen. The physical mount determines direction.

Fullscreen has visible aim/zoom controls and keyboard equivalents. Back reopens a fresh valid source panel and retains an unsaved name draft. A missing or inaccessible source never restores its old inventory.

**Notes:**

## Monitor

Right-click any tile to configure the whole matched wall. Name, Save name, Device On/Off, camera rows, embedded preview, wall size and fullscreen are visible. Selecting a camera applies immediately, with the active feed identified from server state; no Apply dialog is required.

Up to 8 x 8 coplanar matching tiles form a complete rectangular wall. Irregular/oversized groups remain individual screens. Missing power/camera is a real status; feed loss does not silently pick another camera. Back from fullscreen validates the wall again.

**Notes:**

## Ship Cannon

Shows Ready / Recharging / No power / Barrel blocked, Device On/Off and Show muzzle. Advanced retains diagnostics. Inspection never fires. Use Fire (F) in manual flight; the server validates a shot. No ammunition system or upgrade slots are introduced here.

**Notes:**

## Docking Base

Build one base directly below any normal door. Dock from the Ship Controller, then open the ordinary door and walk through the connected tunnel. No everyday Board button is required. The base also handles iron-door operation without extra button blocks.

The local GUI shows name, partner and actual dock status. Accept/Decline appear for an incoming request; Cancel appears during an outgoing maneuver; Undock is available when captured. Station visitors have read-only berth settings. Captured traversal remains available without power.

Undocking needs the departing ship's controls, engine power and fuel; the target can remain unpowered. It retains passage/crew checks. Emergency departure stays under Advanced, with its tunnel/seal consequence explained before a separate confirmed release action. Existing server safety checks still apply.

**Notes:**

## Station Core

One terminal holds the section map, four room tiles, direct piece choices (entrance, workshop, bedroom, power room, tunnels and the garden and observation domes), kit cost and Build room. Selecting a tile/type does not spend kits. Occupied tiles cannot be overwritten.

Build whole station requires an empty station, with the 4- or 28-kit cost visible. Enlarge area reserves 128 x 32 x 128; it does not build rooms. Existing construction remains intact.

Visitors Allowed/Not allowed states docking access, separately from build rights. Choose player opens a named online-player value picker; Invite builder is the explicit permission change. Builders opens a named list; Remove build access uses that selected person's ID. Owner-only controls identify that restriction. Field edits and selection survive live readings.

Return to construction ship appears when there is a construction source. Advanced retains engineering/graph diagnostics.

**Notes:**

## Pad Computer

Shows detected pad dimensions and Registered / Not registered / Needs repair. A new valid pad has an unused suggested name and Register pad. Renaming is optional; Save name is useful only when the draft differs. Existing registrations retain their IDs and home references.

Show pad area and Show ship area are distinct outline controls. Check pad repeats the opening check; Show problem marks the fault. Registration does not certify a clear landing column or a flightworthy ship.

Bring back my ship opens the existing recovery list. It shows the destination pad, ship names, live eligibility and the selected ship's actual blocker. Bring back ship starts recovery; Cancel recall appears only for an active request. Back and Escape return to the pad. Recovery preserves cargo/damage and does not restore player inventory.

**Notes:**

## Ship Beacon

One catalog: select a design, inspect its footprint/specification and press Deliver ship or Resume delivery. Selection alone never starts delivery. The current free-delivery rule stays visible.

Fit, clearance and occupants are checked again by the server. Closing the catalog does not cancel active work; the screen says so. Resume continues the saved matching job rather than creating another copy.

**Notes:**

## Computer Terminal

Opens the programmable shell directly. No program-launcher menu is added. Ordinary ship operation does not require typing commands. A visible Close terminal and Shift+Escape close the screen; ordinary Escape still goes to the program. Saved work is preserved.

Advanced equipment terminals remain scoped to their source. Power requests have explicit 0..8 choices and Left/Right keyboard adjustment. Server state identifies the current value; old cycle operations remain compatible with existing commands.

**Notes:**

## Computer Base

Crafting intermediate, with a tooltip explaining the functioning computers it makes. It does not open an empty operating screen. Changing it into an inventory-only component remains a separate hardware decision.

**Notes:**

## Guide and graphics

The guide puts Already on a ship and Build my first ship in the contents. Left-click follows a craftable ingredient recipe; right-click remains supported. Back returns through component recipes/instructions, and hovered ingredient choices stay stable. Output pictures never grant free items. The book stays within the native 100-page limit; dev-lab instructions remain in repository documentation.

Space Graphics keeps quality presets and ordinary effects together. Current matching presets are marked. On/Off describes visual options; technical sampling controls live under Advanced graphics. Air-particle visibility does not change cabin air.

## Machine appearance

The [machine review sheet](../images/machine-designs-industrial.png) and [texture sheet](../images/machine-textures-16px.png) retain the shared Minecraft material style: iron, stone, copper, redstone red, warm furnace light and green displays. Thirteen regular machines are full cubes; Camera keeps its small mounted form and Monitor stays thin/tileable. Block textures and inventory icons use a 16 x 16 grid with the same pixel size on shaped models. Functional faces distinguish generator fire, engine exhaust, battery contacts, air grille, navigation map and docking clamps.

[`scripts/art/build-machine-art.py`](../../scripts/art/build-machine-art.py) regenerates authored resources; [`scripts/art/preview-machine-art.py`](../../scripts/art/preview-machine-art.py) renders the review sheets using Pillow. `verifyContentAssets` checks full-cube regular machines, texture resolution and matching texel density. The nine component/upgrade/guide/suit icons keep the same material palette. Those asset checks remain part of verification.
