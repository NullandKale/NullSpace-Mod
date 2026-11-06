# UI click inventory

Companion to the [priority review](ui-usability-audit.md). This records the source baseline before refinement, covering distinct controls and interactions. Repeated result rows, inventory slots, room tiles and recipe ingredients are grouped by behavior. Targets below describe the desired changes; the linked fix design tracks implementation.

The [UI fix design](ui-refinement-design.md) maps every interaction range below to a screen design and implementation slice. Shared selection, feedback, return and input rules apply throughout.

Retired blocks are not a player equipment list. Reachable compatibility actions are included because current blocks can still expose them. Vanilla screens are covered where the mod adds or depends on an interaction.

## Shared controls

Sources: [TerminalScreen](../src/client/java/com/nullandkale/nullspace/client/TerminalScreen.java), [MachineScreen](../src/client/java/com/nullandkale/nullspace/client/MachineScreen.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| G01 | Right-click operating block | Opening should show its job, state and useful interaction; it must not mean turning it on. |
| G02 | Click field; type, erase, select, paste | Native machine fields and hand-built terminal fields differ. Use labeled, focused fields and ordinary mouse/caret behavior. Names have usable defaults. |
| G03 | Save | Machine Save refers to a name, not all settings. Say Save name and acknowledge success. Names matter most for cameras, walls and docks. |
| G04 | Enabled/Disabled | A state label also acts as a switch. Label the device On/Off and explain scope; a wall toggle affects all its tiles. |
| G05 | Advanced | Keep expert settings. Put an essential existing remedy on the main fault panel rather than requiring this detour. |
| G06 | Gray action | Controller explains blocked actions on focus/click; native disabled buttons differ. Show the short reason before activation. A blocked click cannot execute. |
| G07 | Escape, inventory key, Back | Return paths vary by screen. Give Back a real parent; label Close honestly. Preserve inventory-key behavior outside text editing. |
| G08 | Wait for a refresh | Preserve typed text, scroll and selected identity. Waiting for data is not a real zero reading. |
| G09 | Click row/blank space | Some terminal hit areas span most of a row. Hit targets must match the visible controls; blank space is inert. |
| G10 | Read/scroll an error | Keep cause and repair visible without Page Up/Down. Direct highlights help a player; detailed logs remain available for troubleshooting. |

## Pad Computer

Sources: [ControllerTerminal](../src/main/java/com/nullandkale/nullspace/terminal/ControllerTerminal.java), [PadComputers](../src/main/java/com/nullandkale/nullspace/pad/PadComputers.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| P01 | Inspect pad | Opening already scans. Show the current result; keep Check pad as an optional repeat, not a registration prerequisite. |
| P02 | Pad name | Replacing text can require Ctrl+A. Make naming optional with a usable default and familiar editing. |
| P03 | register / save name | Use Register pad before registration and Save name afterward. Confirm the saved name. |
| P04 | Pad preview | Temporary outline, not placement. Say Show pad area and show whether its outline is active. |
| P05 | Ship preview | Can sound like a finished model preview. Say Show ship area, with its purpose distinguished from the pad outline. |
| P06 | Show problem | Keep direct highlighting and the corresponding repair instruction. |
| P07 | Advanced | Footprint/registration detail remains optional. Do not require it to register a valid pad. |
| P08 | Recall ships | Bring back my ship, with a short explanation that an empty ship returns to this pad. |

## Native inventory and upgrades

Sources: [MachineMenu](../src/main/java/com/nullandkale/nullspace/systems/MachineMenu.java), [MachineEntity](../src/main/java/com/nullandkale/nullspace/systems/MachineEntity.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| I01 | Pick up/place/split/drag stack | Keep Minecraft behavior. Item pictures and labels identify valid slots. |
| I02 | Shift-click stack | Keep valid-slot quick move as a shortcut; ordinary dragging must work without it. |
| I03 | Six generator fuel slots | Make Fuel dominant; explain coal, wood and other furnace fuels when empty. Keep actual stacks visible. |
| I04 | Generator return slot | Label Empty containers. If a remainder blocks burning, point here and say what to take out. |
| I05 | Insert System Upgrade into three slots | Show the upgrade item, 0/3..3/3 and the actual benefit. No redundant Upgrade now button. |
| I06 | Remove an upgrade | Battery charge can block removal. Explain the threshold at the slot instead of silently refusing the pickup. |
| I07 | Invalid/full slot | Show accepted item/full state; retain standard rejection without a pop-up for every misdrop. |
| I08 | Close while moving an item | Preserve native container handling. Inventory changes and unsaved name edits have distinct behavior. |

## Generator, Engine, Battery and Air Supply

Sources: [MachineScreen](../src/client/java/com/nullandkale/nullspace/client/MachineScreen.java), [MachineMenu](../src/main/java/com/nullandkale/nullspace/systems/MachineMenu.java).

Each uses G03-G05 for name/Save, On/Off and Advanced. Generator, Engine and Battery additionally use I05-I06 for upgrades.

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| M01 | Generator common controls and fuel | Fuel competes with per-tick output and Burn reserve. Lead with Supplying power / Needs fuel / Switched off and the fuel slots. Preserve exact accounting in details. |
| M02 | Generator fault directs Restart supply under Advanced | Offer that existing remedy beside the fault. Restart must preserve fuel/charge accounting. |
| M03 | Engine common controls | Show Ready / Running / Needs fuel / No power / Exit blocked. Explain that fuel goes into a generator; there is no engine inventory to fill. |
| M04 | Show exhaust | Currently highlights the front cell even when healthy. Use Show blocked exhaust for a fault; otherwise offer Show engine exit as an orientation aid. |
| M05 | Battery common controls | Lead with charge and Charging / In use / Full. Exact capacity/rate stay accessible; an upgrade never fills it. |
| M06 | Air Supply common controls | Lead with Cabin air and Safe / Filling / Air escaping / No power. Turning a vent off is a real machine change, not a display preference. |
| M07 | Show outlet | Say Show air opening; put it beside a blocked-opening fault and retain the orientation aid. |
| M08 | Show leak | Trace actual escaping air; show No leak when healthy. A player need not click this to learn that the room is sealed. |

## Ship Controller

Sources: [ShipControlTerminal](../src/main/java/com/nullandkale/nullspace/terminal/ShipControlTerminal.java), [ShipControl](../src/main/java/com/nullandkale/nullspace/navigation/ShipControl.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| S01 | Destination / Choose | Opens a picker; does not move anything. Keep one saved destination with readable name, world and kind. |
| S02 | Launch | Keep explicit Launch, with Leave this planet and the immediate blocker. All server preflight checks remain. |
| S03 | Jump | Keep explicit Jump, with Go to the selected planet or moon. Explain this intermediate step for remote pads/docks. |
| S04 | Land | Keep Land, naming the selected pad. If Home remains selected, this is the entire normal return action. |
| S05 | Dock | Keep automatic approach/mating. Name the selected dock and show actual stages and blockers. |
| S06 | Undock | Say Leave the dock; name a passenger/tunnel obstruction when one prevents it. Preserve crew/clearance checks. |
| S07 | Pilot | Explain You steer the ship; make basic controls visible on entry. |
| S08 | Ship camera | Explain Look around without flying; distinguish its purpose and exit from piloting. |
| S09 | Close all doors | Explicit ship-wide scope and completed/blocked result, not just queued-command text. |
| S10 | Deploy station | Build a station, with required items and actual outcome visible. Keep separate from ordinary travel. |
| S11 | Advanced systems | Expert allocation/diagnostics stay here. First-flight operation must not need this menu. |
| S12 | Inspect hull | Check ship; short result and direct problem highlight. Launch already runs preflight, so do not require another inspection every trip. |
| S13 | Accept | Allow [ship name] to dock; show who and which port. |
| S14 | Decline | Decline [ship name] request, distinct from separating an existing connection. |
| S15 | Cancel | Name the cancellable maneuver, such as Cancel docking. Do not imply committed physical travel can be canceled. |
| S16 | PgUp/PgDn status | Main fault and next action must be readable without scrolling. Keep visible optional detail controls. |

## Destination picker

Sources: [ShipControlTerminal](../src/main/java/com/nullandkale/nullspace/terminal/ShipControlTerminal.java), [ShipDestination](../src/main/java/com/nullandkale/nullspace/navigation/ShipDestination.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| D01 | Search typing | Any text in the open picker filters; its line is not a native clickable field. Use an explicit focused search field with Clear. Browsing works without typing. |
| D02 | All | All places, with understandable kinds. |
| D03 | Regions | Planets & moons. The player need not learn internal region addresses. |
| D04 | Pads | Landing pads, showing world and readiness. |
| D05 | Ports | Docking ports, showing vessel/station and a distinguishable dock name. |
| D06 | Result row | Highlights a place; enum kinds and raw addresses crowd its name. Keep a clear Selected marker and friendly details. Selection alone never starts travel. |
| D07 | Previous/Next; PgUp/PgDn | Preserve selection by identity and disable impossible paging. Pin the known home choice rather than forcing a search. |
| D08 | Set destination | Commits selection; controller goes back, scanner stays. Acknowledge Destination set: [name] and show the appropriate next action. |
| D09 | Back to controller / Escape; scanner Advanced footer | Give the scanner a visible Close and separate Advanced. Maintain a predictable one-step return and explain empty/occupied lists. |

## Scanner

Sources: [MachineScreen](../src/client/java/com/nullandkale/nullspace/client/MachineScreen.java), [MachineMenu](../src/main/java/com/nullandkale/nullspace/systems/MachineMenu.java).

Common controls: G03-G05 and I05-I06.

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| R01 | Nearby | Filters current-region places, not a manual scan. Show the selected filter state. |
| R02 | Destinations | Other/All places, with an honest power/range/upgrade explanation when limited. |
| R03 | Result row | Selects an index; names truncate at 18 characters. Select stable IDs and show a full distinguishable name in details. |
| R04 | < / > | Page changes leave the old selected index. Fix off-page selection: Set destination must match a visibly identified choice after paging/refresh. |
| R05 | Set destination | Empty/unpowered paths can return silently. Disable unusable selection, explain the blocker and acknowledge the actual saved destination. |

## Camera and Monitor

Sources: [MachineScreen](../src/client/java/com/nullandkale/nullspace/client/MachineScreen.java), [ViewportScreen](../src/client/java/com/nullandkale/nullspace/client/scene/ViewportScreen.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| C01 | Camera name/Save | Keep visible: a useful camera name helps later feed selection. Say Camera name and Save name. |
| C02 | Camera On/Off; Advanced | Use G04-G05. Show the real mounted facing; display options must not imply rotating the block. |
| C03 | Camera View fullscreen | Closes inventory and opens viewport. The server lease already reopens the machine on close; preserve the configuration draft and validate unavailable-parent handling. |
| C04 | Monitor name/Save; On/Off | Applies to the matched whole wall. Show its size and make the scope explicit. |
| C05 | Monitor camera row | Immediately changes the wall feed. Keep direct application and highlight the actual selected camera. No redundant Apply dialog. |
| C06 | Monitor < / > | Page cameras; names truncate. Show valid paging/count and preserve the actual camera selection by identity. |
| C07 | Monitor fullscreen; Advanced | Give fullscreen a real return to this wall. Missing camera/power shows a specific cause; expert settings stay separate. |
| C08 | Viewport arrows; PgUp/PgDn | Keyboard aim/zoom. Give visible look/zoom help and mouse alternatives within the permitted camera behavior. |
| C09 | Viewport Tab | Cycles feeds when not a fixed camera. Visible Next camera plus name/count is discoverable; Tab remains a shortcut. |
| C10 | Viewport F2 | Saves a snapshot. Keep optional Minecraft-style confirmation, not a required ship operation. |
| C11 | Viewport Escape | The server lease handles machine returns; the client retains no configuration draft. Keep the validated return and distinguish Back from closing a free view. |

## Docking Base and ordinary doors

Sources: [MachineScreen](../src/client/java/com/nullandkale/nullspace/client/MachineScreen.java), [StationMenus](../src/main/java/com/nullandkale/nullspace/station/StationMenus.java), [DockingService](../src/main/java/com/nullandkale/nullspace/station/DockingService.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| K01 | Port name/Save | Dock name and Save name. Use that name in destination choices so naming helps identify the entrance. |
| K02 | Port On/Off | Explain device scope and owner restrictions. Do not imply a captured walkway disappears; established traversal stays power-independent. |
| K03 | Accept / Decline | Name the requesting ship and use Allow docking / Decline request. Keep consent/access checks. |
| K04 | Cancel | Cancel approach, with the actual maneuver identified. |
| K05 | Undock | Leave this dock, with the actual reason if unavailable. Keep normal door closure/clearance checks. |
| K06 | Advanced | Opens a docking terminal with compatibility/repair operations. Ordinary door traversal must not depend on it. |
| K07 | Open dock door and walk | Keep the physical Minecraft interaction. Show Docked: open this door only when captured; an unconditional hint must not suggest an undocked exterior door is safe. |
| K08 | Advanced Inspect airlock / Board connected vessel | Check dock and a clearly identified maintenance boarding action. The normal route is opening the door and walking. |
| K09 | Advanced Cycle inner / Cycle exterior door | Use actual door names and Open/Close according to state. Do not teach the compact dock as a required two-door airlock. |
| K10 | Advanced Accept / Enter construction site / Undock | Named target and action; keep a direct construction return route. |
| K11 | Advanced Emergency departure | State its actual consequence and blockers before activation; distinguish from ordinary Undock without changing its rules. |
| K12 | Contacts Refresh / Select next berth | A compatibility path cycles contacts. Ordinary selection uses the one named destination picker; no second normal destination field. |
| K13 | Contacts Dock automatically / Cancel / Undock automatically | Keep existing actions but use the same names and state language as the controller. |
| K14 | Contacts Start station / Return to helm / Accept incoming request | Build a station, Back to ship controls and named consent. Do not teach duplicated trip workflows. |

## Ship Cannon and flight view

Sources: [MachineScreen](../src/client/java/com/nullandkale/nullspace/client/MachineScreen.java), [ShipPilotScreen](../src/client/java/com/nullandkale/nullspace/client/scene/ShipPilotScreen.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| F01 | Cannon On/Off, name/Save, Advanced | Shared controls; lead with Ready / Recharging / No power / Barrel blocked. Inspection never fires. |
| F02 | Show muzzle | Highlights the front cell. Show blocked barrel for a fault; otherwise an optional Show barrel exit orientation aid. |
| F03 | Any click in flight screen | Currently consumed without a button action. Do not draw fake clickable text; any visible buttons need real hit targets. |
| F04 | Drag / wheel | Orbits and zooms the viewing camera, rather than steering the ship. Explain Look around ship / Zoom separately from steering. |
| F05 | H | Opens the full help overlay, initially hidden. Use a visible Controls action and basic hints on entry; H remains a shortcut. |
| F06 | W/S, A/D, Space/Ctrl | Thrust, yaw and lift under a pilot lease. Explain Forward/back, Turn, Up/down with actual keys. An observer must plainly say it cannot fly. |
| F07 | Arrows, Q/E, R | Pitch, roll and brake. Keep mechanics; make Brake prominent and advanced movements available in help. |
| F08 | F | Requests a server-validated shot. Show Fire/key and actual readiness; never move firing into a cannon inspection click. |
| F09 | Escape | Hint says Esc helm, but no explicit parent-helm return is implemented. Make it a real return or say Close view. Keep input release on close/focus loss. |

## Station Core

Sources: [StationCoreTerminal](../src/main/java/com/nullandkale/nullspace/terminal/StationCoreTerminal.java), [StationService](../src/main/java/com/nullandkale/nullspace/station/StationService.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| T01 | Name field / Save name | Hand-built editing; Ctrl+A clears rather than selects text. Familiar editing, usable default and clear Saved feedback. |
| T02 | Previous section / Next | Pages four room tiles. Show the selected part on a simple floor plan and retain section location. |
| T03 | Expand to 128 x 128 | Reserves larger clear space; does not create rooms. Say Make build area larger, show resulting size and distinguish area from finished construction. |
| T04 | Four room tiles | Selects coordinates/role. Use a visible tile map and Empty/room labels; selection alone never spends kits. |
| T05 | Next type | Cycles enum roles. Four visible choices: Entrance, Workshop, Bedroom, Power room, with familiar item icons. Keep server role identities. |
| T06 | Build room: 1 kit | Places selected empty adjoining room after validation. Say Build [room], show cost and selected location together. |
| T07 | Room already built | Looks like an action but only explains that another tile is needed. Occupied tiles can show information without pretending to build or overwrite. |
| T08 | Build full station: 4/28 kits | Requires an empty station; failure can misleadingly say empty section. Say Build whole station, show full footprint/cost and preserve the empty-station rule. |
| T09 | Toggle Public / Private docking | Does not state current mode on its button. Show Visitors: Allowed/Not allowed and distinguish docking access from building rights. |
| T10 | Player field / Invite builder | Requires typing an online name. Choose a named online player; optional text filters the list. Show who receives building access. |
| T11 | Builders Next | Cycles names. Use a visible builder list and stable selected identity. |
| T12 | Remove builder | Name the player and Remove build access. Disable without a selection; it does not mean ejecting a visitor. |
| T13 | Return to construction ship | Footer exposes it outside its useful context. Show Back to my ship for a valid construction return; otherwise explain or omit contextual action. |
| T14 | Show problem | Keep direct fault highlighting and a short repair instruction before graph/system details. |
| T15 | Advanced; PgUp/PgDn | Preserve engineering and scrollable detail; normal room/access work stays on the main screen. |

## Ship recovery

Sources: [ShipRecallTerminal](../src/main/java/com/nullandkale/nullspace/terminal/ShipRecallTerminal.java), [ShipRecall](../src/main/java/com/nullandkale/nullspace/ship/ShipRecall.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| Q01 | Ship row | Selects a recoverable ship and prints its state. Show name, location and the actual plain-language eligibility/blocker; selection never moves it. |
| Q02 | Previous / Next | Keep valid paging, count and visible selected ship. |
| Q03 | Recall selected ship | Brings an eligible empty ship here. Say Bring back [name], naming this pad and what is preserved; lost player items are not restored. |
| Q04 | Blocked recall | Generic select-AVAILABLE/crew/docking message. Show this selected ship's real blocker before clicking. |
| Q05 | Refresh list | Manual refresh. Update availability automatically; do not require guessing that data is stale. |
| Q06 | Cancel recall | Shown even without an active job. Offer only while valid and acknowledge stopped/already completed. |
| Q07 | Back to pad / Escape | Make both a consistent one-step return, with a visible Back button. |
| Q08 | Row/button whitespace; PgUp/PgDn | Hit regions span broad horizontal areas. Match controls to their hit targets; primary blocker/progress remains unscrolled. |

## Ship Beacon catalog

Source: [ShipyardScreen](../src/client/java/com/nullandkale/nullspace/client/ShipyardScreen.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| B01 | Ship design row | Selects and requests readiness, without delivering. Keep preview and included-equipment details together with a clear selected marker. |
| B02 | < / > | Pages six models. Show valid paging/count and retain selected design/readiness. |
| B03 | Deliver ship | Starts current free delivery after checks. Keep one explicit action, Free delivery and a clear footprint/clear-pad requirement. Economy changes are separate. |
| B04 | Resume delivery | Continues saved matching work. Preserve the model/progress and never imply a second copy or another purchase. |
| B05 | Blocked Deliver | Native disabled button. Put the specific current blocker beside it and provide obstruction/occupant highlighting where supported. |
| B06 | Close / Escape | Catalog can close while server work continues. Explain closing is not canceling; completion names ship and pad. |

## Guide, recipes and crafting entry

Sources: [GuideContent](../src/main/java/com/nullandkale/nullspace/guide/GuideContent.java), [GuideRecipeRenderer](../src/client/java/com/nullandkale/nullspace/client/guide/GuideRecipeRenderer.java), [GuideBookScreenMixin](../src/client/java/com/nullandkale/nullspace/mixin/GuideBookScreenMixin.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| H01 | Open guide | A native book; regular survival must obtain/craft it. Reach first instructions through normal play, not just a dev command. Do not require a book for an obvious fuel-slot action. |
| H02 | Contents / chapter link | Jumps to chapter. Put Start with a ship and Build my first ship first; use task names alongside the equipment reference. |
| H03 | Recipes / recipe link | Jumps to a recipe. Keep item pictures/output count and a visible way back to the instruction being followed. |
| H04 | Previous/Next; More contents/recipes | Keep native navigation. Component recipe chains need Back to previous recipe/instruction rather than repeatedly finding the parent in an index. |
| H05 | Left-click displayed ingredient | Normal left-click already follows the recipe: Minecraft 26.3 uses SDL left-button code 1. Use named constants so this stays explicit. |
| H06 | Right-click displayed ingredient | Add this optional shortcut for a craftable ingredient, while keeping the advertised normal left-click. |
| H07 | Hover cycling ingredient | Shows accepted choices, cycling every 1.5 seconds. Explain Any planks/Any wooden door; keep hovered reading stable enough to understand. |
| H08 | Hover/click output | Shows item and count; no item is granted. Do not imply clicking crafts or delivers the output. |
| H09 | Native recipe book / crafting slots | Keep standard Minecraft crafting and consistent names. The Computer Base is an intermediate, not the functioning Ship Controller. |
| H10 | Close book / interact with Computer Base | Normal book close; the base gives crafting-purpose guidance. Do not add an empty operating screen for the base. |

## Engineering, compatibility and programming

Sources: [ControllerTerminal](../src/main/java/com/nullandkale/nullspace/terminal/ControllerTerminal.java), [EquipmentMenus](../src/main/java/com/nullandkale/nullspace/terminal/EquipmentMenus.java), [StreamTerminal](../src/main/java/com/nullandkale/nullspace/terminal/StreamTerminal.java), [TerminalScreen](../src/client/java/com/nullandkale/nullspace/client/TerminalScreen.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| A01 | Systems / refresh / equipment refresh | Keep diagnostic repeat actions; ordinary readings update automatically. |
| A02 | Upgrade systems | Enables a finite systems record for applicable older records. Compatibility action, not another universal module tier. New complete ships initialize through controller interaction. |
| A03 | Close all doors / Open all doors | Preserve ship-wide actions and actual results. Opening exterior doors has a real air consequence; make that scope clear. |
| A04 | Air / engine / weapon power | Cycles requests. Show current value and next change; direct values or plus/minus avoid guessing cycle length. |
| A05 | Priority | Changes who receives power first. Show current choice and actual trade-off. |
| A06 | Command / Execute | Runs typed engineering commands. Keep optional expert help; no first-flight or ordinary repair task requires typing one. |
| A07 | Equipment enable / disable | Compatibility commands for the same state. Keep supported operations; ordinary panels use one clear On/Off switch. |
| A08 | Manual restart | Existing supply/control recovery. Say Restart supply and expose it beside the corresponding ordinary fault without changing fuel/charge rules. |
| A09 | Allocate power / next camera / view feed | Expert compatibility operations. Ordinary work uses named camera lists and visible allocation values. |
| A10 | Older combined ship/destination and quote/buy panels | Overlaps newer dedicated panels. A current block should open one consistent normal workflow; keep compatibility out of the first-player instructions. |
| A11 | Advanced route row / page / Return to helm | Preserve server route validation and use the same friendly names, paging and back behavior as ordinary navigation. |
| A12 | Program text, Enter, arrows/history, paste | Real computer input remains a shell. Focus/cursor and optional help should be obvious; do not add a program-launcher menu before it. |
| A13 | Program Escape / Shift+Escape | Escape can go to the program; closing has a separate chord. Provide a visible Close terminal with accurate help, preserving program Escape and saved work. |
| A14 | Click programming stream | Mouse reporting is not enabled. Do not show fake command buttons; keyboard focus is visible. |

## Travel overlay and Space Graphics

Sources: [TravelScreen](../src/client/java/com/nullandkale/nullspace/client/TravelScreen.java), [SpaceSettingsScreen](../src/client/java/com/nullandkale/nullspace/client/SpaceSettingsScreen.java).

| ID | Click/interaction now | Finding and target |
| --- | --- | --- |
| O01 | Click/Escape during travel | Input is held while authoritative transfer/readiness completes. Show actual stage and destination, using an honest waiting indicator. No unsafe Cancel or required Continue click. |
| O02 | Options: Space Graphics | Keep optional Minecraft-options access; operating a ship should not require changing renderer settings. |
| O03 | Fast / Balanced / High quality | Applies preset. Mark the current preset and explain performance versus picture detail; this changes graphics only. |
| O04 | Surface resolution | Cycles output dimensions. Picture size/detail, with precise dimensions in optional graphics detail. |
| O05 | Volume resolution | Cycles volume scale. Space fog detail with low-to-high choices and a performance hint. |
| O06 | Volume integration quality | Cycles sampling count. Fog quality; preserve precise samples in Advanced graphics. |
| O07 | Surface antialiasing | Cycles sample count. Smooth planet edges, with understandable existing choices. |
| O08 | Temporal reconstruction | Technical true/false toggle. Keep in Advanced graphics with plain explanation and On/Off wording. |
| O09 | Stochastic volume sampling | Animated-sampling toggle. Explain its visual effect in Advanced; do not imply a gameplay change. |
| O10 | Planet space skies | Space sky on planets: On/Off. |
| O11 | Air flow particles | Air particles: On/Off. This changes visible effects, not whether a room has air. |
| O12 | Clear-weather clouds | On/Off with the existing storm-overcast exception stated. |
| O13 | Cloud quality | Cloud detail with low-to-high labels; exact step counts remain accessible. |
| O14 | Inherited Done/Back | Keep Minecraft options return behavior. Closing options is not a ship/world save or restart action. |

## Coverage and limits

This covers distinct action groups across the operating panels, shared item slots, destination selection, station building, recovery, delivery, guide, flight/camera views, engineering/programming, travel overlay and mod graphics settings. Repeated instances use the same behavior audit.

This inventory records the source baseline. The linked fix design, block reference and verification record describe the implemented changes and graphical checks. Child observations and further playtesting remain separate.


