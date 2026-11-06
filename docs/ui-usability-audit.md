# UI usability review

Goal: a young Minecraft player can understand what to click and what happened. Keep the same building, fuel, air, power, upgrades, ownership and travel rules. Make the decisions easier to see and act on.

This records the source audit before the UI refinement. It is not a playtest with children; the implementation status belongs in the linked fix design. The [complete click inventory](ui-click-inventory.md) covers each distinct control and repeated-slot interaction. Start with the priorities here rather than treating every inventory row as a request to add another control.

The [UI fix design](ui-refinement-design.md) defines the proposed shared behavior, concrete first-trip layouts, other screen contracts and completion checks. Use it for implementation planning; this document remains the findings record.

## What a normal screen should answer

1. What is this block for?
2. What is it doing now?
3. What can I do here?
4. If something is wrong, what can I do about it?

Use Minecraft's familiar item slots, item pictures, readable buttons and ordinary mouse behavior. Keep the terminal style where wanted, but give its controls clear hit areas, focus and text editing. The programmable computer remains a terminal.

Words, icons and actual state changes work together. Color and a sound are supporting feedback, not the only explanation. One short primary message should fit without scrolling. Technical numbers remain available and resource costs stay honest.

## Highest-priority findings

| Priority | Current issue | Required outcome |
| --- | --- | --- |
| First | Scanner page arrows leave the previous numeric selection active. Set destination can act on an off-page result. | The committed destination must match the visibly selected place, even after paging or live refresh. |
| First | Recipe navigation uses a literal mouse-button number, has no return trail, and hover choices keep cycling. In Minecraft 26.3, literal 1 already means left-click. | Use named button constants, preserve normal left-click, add optional right-click and Back, and stabilize hovered choices. |
| First | Some native machine actions can return without a visible result, including empty/unpowered scanner selection. | Every consequential click has success, a readable blocker or a visible waiting state. |
| First | Fullscreen camera leases already reopen the machine through server validation, but do not retain the configuration draft. The flight view says Esc helm without implementing a helm return. | Back/Close labels must match the real destination. Restore an actual parent when promising to return to it. |
| Next | Destination rows use REGION/PAD/PORT and internal addresses such as sol_terra. Dock names can be lost in truncation. | Familiar place names, distinguishable docks and readable location/type details. |
| Next | A blocked controller action generally needs focus/click to reveal its explanation. | The immediate blocker and repair are visible when the player needs them. |
| Next | Generator, engine and other machine panels give similar prominence to rename, Save, Enabled, Advanced and technical readings. | The job of that block dominates: fuel slots, battery charge, engine readiness or cabin air. |
| Next | Generator recovery can say Restart supply under Advanced. | A normal fault has its existing remedy beside it; no engineering detour required. |
| Next | Terminal editing can require Ctrl+A; the destination search is not a native clickable field. | Familiar mouse editing, a visible cursor/focus, optional default names and a clear search field. |
| Next | Station room types and builders require cycling; docking access does not state its current mode in the button. | Visible choices, named people and current access state. Keep all existing construction/permission rules. |
| Later | Ordinary and compatibility panels teach overlapping versions of navigation, boarding and power control. | A current block has one normal workflow, with compatibility and repair functions available under clearly named expert controls. |

Source references and the individual actions are in the inventory. These are baseline findings; see the fix design for the subsequent implementation and verification.

## Walk through both starts

### Space start: first useful trip

1. Open the controller. See the ship's place, destination, fuel/air condition and useful actions.
2. Open destination choices. Browse named places without needing to know their internal IDs or type a name.
3. Select a place. Read what it is and where it is. Set destination explicitly.
4. Back on the controller, understand whether to Jump, Land or Dock. The action and current blocker are named.
5. If fuel is needed, open the generator, put furnace fuel in the clearly marked slots and see that it is available.
6. Start the chosen action. The actual approach/transit/arrival states make it clear when control returns.
7. At a captured dock, open the ordinary door and walk through. The connection state, destination and way back are recognizable.

UI clarity cannot supply a missing food/fuel/resource opportunity. The world still needs the useful expedition described in the [player stories](player-stories.md).

### Vanilla start: first launch and return

| Task | Current friction | Target interaction |
| --- | --- | --- |
| Register a valid pad | Naming can become a typing exercise; Inspect repeats the opening scan. | Open Pad Computer, see the detected area, Register pad with a usable name. Renaming remains optional. |
| Add fuel | Slot purpose competes with upgrades, Save and power counters. | Open generator, move fuel into Fuel slots, see Fuel available, close. Keep native inventory handling. |
| Check the ship | Long diagnostics and technical readiness wording. | Open controller, see a short state; Check ship is available but Launch still performs its full preflight itself. |
| Launch | Five travel actions look similarly important; the relevant blocker can be hidden until clicked. | Launch is plainly relevant on the ground, with Leave this planet and the actual repair if blocked. Other travel actions remain identifiable. |
| Look around | Pilot versus Ship camera, then an inaccurate Escape hint. | Fly ship and Look at ship have distinct purposes; close/back does exactly what its label promises. |
| Return Home | Our walkthrough unnecessarily reselected Home even if it was already saved. | If Home remains selected, press Land. Otherwise use the same destination picker to select it. |

The [build walkthrough](first-ship-walkthrough.md) remains the concrete test case. Do not require Inspect or reselecting an unchanged destination every trip merely because the tutorial demonstrated those actions once.

## Language and presentation decisions

| Current wording | Prefer on ordinary screens | Preserve in details |
| --- | --- | --- |
| Regions | Planets & moons | Region addresses and scene IDs |
| Pads / Ports | Landing pads / Docking ports | Coordinates and node IDs |
| Enabled / Disabled | A labeled On / Off switch | Device enabled state |
| Shared fuel | Ship fuel; Loaded in a generator | Exact shared burn-unit accounting |
| Burn reserve; output per tick | Needs fuel / Supplying power, with meaningful bars and item slots | Burn reserve, rates and allocation values |
| Inspect hull | Check ship | Full preflight/occupant diagnostics |
| Show outlet / Show muzzle | Show air opening / Show barrel exit | Exact affected coordinates |
| Toggle Public / Private docking | Visitors: Allowed / Not allowed | Docking permissions and builder rights |
| HABITATION / ENGINEERING | Bedroom / Power room | Stable room-role identifiers |
| Recall selected ship | Bring back [ship name] | Recovery eligibility and durable records |

These are candidate labels to test, not a ban on teaching new terms. Thrust can stay when its bar explains how hard the engine pushes. Power should not be renamed in a way that confuses electricity with thrust. Avoid baby talk and unexplained abbreviations.

Keep exact costs accessible, but do not claim Enough for the trip unless the supported calculation actually covers that trip. Enough to launch is a narrower, honest statement. Docking fuel use and future manual flying are not guaranteed by a launch-ready indicator.

## Per-screen direction

- **Generator:** fuel slots and current supply state first. Rename is optional. Upgrade slots show the module item and 0/3 through 3/3. Empty containers point to the actual blocked return slot.
- **Engine:** Ready/Running, fuel/power and a clear exit problem. Fuel is loaded at a generator; do not invent an engine refuel screen.
- **Battery:** charge and Charging/In use/Full first. Explain blocked upgrade removal at the slot.
- **Air Supply:** cabin air, leak/power state and direct problem highlighting. A healthy vent does not require a manual leak check.
- **Scanner:** named places and stable selection. Nearby/other-place filters have obvious selected states. Empty, unpowered and upgrade-limited results explain themselves.
- **Camera/Monitor:** names are useful here, so keep them readily editable. Named camera rows, real selection, an immediate preview and an honest return from fullscreen.
- **Docking Base:** connection and partner first. Incoming requests name the ship. Ordinary boarding uses the door. Emergency release has a distinct consequence, not a vague technical label.
- **Station Core:** a visible floor-plan selection and direct room choices. Kit costs stay visible. Invitations use named people; docking access states its current setting. No change to building costs or permissions.
- **Ship Beacon:** keep the current one-screen list/preview/delivery workflow. Clearly distinguish selecting a design, starting delivery and closing a screen while work continues. Free delivery remains the current rule pending the separate progression decision.
- **Ship recovery:** name the ship and return pad, show its exact blocker and update automatically. A canceled or unavailable recovery never looks successful.
- **Guide:** task-first contents for both starts, normal recipe clicks and a way back from nested component recipes. Pictures should support doing something, not require reading every chapter before acting.
- **Flight:** visible basic controls, a clear brake, camera-versus-steering distinction and real Fire/key feedback. Keep the existing flight mechanics.
- **Programming/engineering:** keep expert functions. Names, scope, current values, results and exits still need to be clear.
- **Graphics settings:** familiar On/Off and quality labels, with technical sampling settings in Advanced graphics. No ordinary ship task should depend on opening them.

## One refinement pass at a time

1. Fix the source-proven click/selection/back-label mismatches. Check the scanner and guide before changing presentation broadly.
2. Walk Pad Computer -> Generator -> Ship Controller -> Launch -> Land. Implement and test that complete route, including one fuel problem and one hull problem.
3. Refine destination selection and docking, then upgrades and recovery.
4. Refine station construction, cameras/monitors, delivery and optional expert panels using the same rules.

For a child-facing playtest, watch someone attempt a task without coaching: can they find the next action, predict its result, recognize completion and recover from one ordinary mistake? Record hesitation and wrong clicks. Passing automated behavior checks alone does not establish ease of use.

This document remains the audit record. Subsequent runtime changes and restart requirements are tracked in the UI fix design.
