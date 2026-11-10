# Interactive block UI audit

This is the record of a screen-by-screen review of every ship, station and computer block, made by looking at the real screens with the player and writing down their critique. It is the evidence behind [the UI design](ui-refinement-design.md), and each block's current screen is described in [the block UI reference](ship-block-ui-design.md). Read it when changing a screen, to see what was wrong with the earlier version and why the current one is the way it is.

## How the audit was done

One block at a time: its purpose, a screenshot of the current screen, and the player's critique, recorded as written. The next block followed automatically. Screens were reviewed as they were, without changing code or running tests during the review, and wording was not chosen unilaterally: replacement labels were proposed for the player to approve at implementation time. Every screen also got a shared layout pass, including those that were otherwise considered fine.

"Fine" in the feedback means just barely good enough. It does not mean the player was satisfied or had approved the design, and that reading applies to earlier and later notes.

## What it found

Across the screens, a few themes recurred:

- **Hierarchy.** Screens felt "samey and mushy": the current location, the destination and the actions looked alike, so it was hard to see what mattered. Fix: separate regions with their own space and colour, with the controls and the message log never overlapping.
- **Duplication.** The same place appeared as both location and destination. Fix: say each fact once ("Current location" apart from "Destination").
- **Categories.** Destinations were a flat list. Fix: the Scanner groups by **All**, **Stations**, **Ships**, **Planets & moons** and **Landing pads**, with **Berths** as an optional detail.
- **Advanced on equipment screens.** Routine fuel and power faults sent players to Advanced. Fix: equipment screens group readings, controls and inventory, with ordinary power-allocation buttons, and Advanced is out of the everyday path.
- **Text instead of interface.** Equipment should look like inventory-style graphical screens, with visual indicators for power generated and consumed. Fix: the labels **Generating**, **Ship demand**, **Charging batteries** and **Power stored**, an upgrade column in its own panel, and a hint on the Engine reading **Uses fuel stored in generators**.
- **Unclear action names.** Fly ship, Look at ship, Close all doors, Review loose blocks, Check ship, Show problem and Build station were not clear in context. Fix: the player approved *Current location*, *Fly manually*, *View ship*, *Loose blocks* and *Locate problem*, kept *Destination*, *Trip*, *Engage*, *Disengage* and *Check ship*, and removed *Build station* from the controller (a station is started in the world by holding a Station Core).
- **Air.** The Air Supply read a single vent cell. Fix: it reads its whole room, shows the room size in blocks (levels only when there are several), this pump's output as a percentage, whether the room's pumps hold its leak, and the breathing threshold.
- **My ships** gained *Show blocks* and *Scrap ship*.

Open at the time of the audit and tracked elsewhere: tuning of the air and leak visualization, camera and fullscreen presentation, and station previews.

## Per-block records

Each entry below is the original audit of one block. The "Status" lines record where the audit left each item and are historical. The current behavior is in the block reference.

## 1. Ship Controller

Implementation update: player approved Current location, Fly manually, View ship, Loose blocks and Locate problem. Keep Destination, Trip, Engage, Disengage and Check ship. Instead of renaming Build station, remove it: start construction in space by holding a Station Core and Shift-right-clicking empty space or a non-interactive block. First layout pass is under focused verification; this does not complete the whole UI audit.

Purpose: operate the ship, understand its condition and destination, engage an automatic trip or fly manually.

Screenshot reviewed: `build/scanner-block-focus/20261105-151710/screenshots/0002_nullspace-scanner-destination-at-controller.png`.

### Player feedback

- Overall size and layout feel awkward and bad. It is hard to understand what is important.
- Current location ("Here") does not clearly read as where the ship is now. Its placement and color blend into everything else.
- Destination has the same hierarchy problem. The information feels samey and mushy rather than clearly separated.
- "Sol / Luna" and "Orbit" are duplicated. Remove redundant location/destination information.
- Keep the trip preview and Manual steps concept. A tab-based arrangement like this is welcome.
- Fly ship, Look at ship, Close all doors, Review loose blocks, Check ship, Show problem and Build station are unclear in meaning/context.
- The screen's Close button sometimes overlaps log text. Give controls and messages separate space.

Status: audit record (see the block reference for the current screen).

## 2. Scanner

Purpose: find destinations and set the ship's destination; enable interstellar travel when powered.

Screenshot reviewed: `build/scanner-block-focus/20261105-151710/screenshots/0000_nullspace-scanner-block-browser.png`.

### Player feedback

- Needs better destination categories.
- Shares the Ship Controller's samey, mushy presentation. Improve visual hierarchy and separation so categories, destinations and actions are easier to distinguish.
- Specific replacement categories have not been decided.

Status: audit record (see the block reference for the current screen).

## 3. Generator

Purpose: burn furnace fuels to generate electricity; its shared fuel inventory also supplies the ship's engines.

Screenshot reviewed: `docs/images/ui-generator-fuel.png`.

### Player feedback

- Improve the wording and labels.
- Remove the Advanced UI from these equipment screens.
- Use more of this inventory-style graphical UI across the interfaces, with actual UI elements rather than mostly text/buttons.
- Make power generated and power consumed easier to understand through clearer presentation and visual indicators.
- Specific replacement labels and indicators have not been decided.

Status: audit record (see the block reference for the current screen).

## 4. Ship Engine

Purpose: provide thrust for flight and travel, using electricity and shared fuel stored in generators.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-3/screenshots/0042_nullspace-engine-upgrades.png`. An older screenshot was shown first; feedback refers to the newer screen.

### Player feedback

- Broadly "fine"; no major functional redesign requested.
- "Fuel goes in generator" is awkward wording. Propose alternatives for approval at implementation time.
- The layout is not arranged well and needs improvement; specific placement changes have not been decided.
- The earlier request to remove Advanced from equipment screens still applies.

Status: audit record (see the block reference for the current screen).

## 5. Air Supply

Purpose: keep its connected room breathable using electricity and help locate leaks or a blocked air outlet.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-4/screenshots/0004_nullspace-equipment-air.png`.

### Player feedback

- The room-air meter, air visualizations and leak feedback feel barely implemented and untuned.
- This is a representative example of a broader lack of refinement, not just a problem with this screen's layout or labels.
- Follow-up needs to consider the usefulness and tuning of the underlying feedback, not only its visual presentation.

Status: audit record (see the block reference for the current screen).

## 6. Camera

Purpose: provide a live view from its mounted position, available on monitors or fullscreen.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-3/screenshots/0043_nullspace-camera-setup.png`.

### Player feedback

- Make the video the central element of the screen.
- Put Previous and Next controls below the video, on the left and right respectively.
- Fullscreen viewing must actually fill the entire screen.
- Increase rendering resolution and expose it as a graphics setting in Space Settings.
- Improve the fullscreen overlay; specific overlay changes have not been decided.

Status: audit record (see the block reference for the current screen).

## 7. Monitor

Purpose: display a selected camera feed on a block or tiled monitor wall.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-4/screenshots/0005_nullspace-equipment-monitor.png`.

### Player feedback

- Shares the overall layout problem identified on the other equipment screens. Specific Monitor placement changes have not been decided.

Status: audit record (see the block reference for the current screen).

## 8. Docking Base

Purpose: turn the door above it into a docking connection for walking between vessels.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-2/screenshots/0002_nullspace-station-dock-controls.png`.

### Player feedback

- Fine functionally; no specific functional change requested.
- Could use a layout pass, as could all the screens.

Status: audit record (see the block reference for the current screen).

## 9. Station Controller / Station Core

Purpose: manage and expand a station, including naming, room construction, visitor docking and building permissions.

Screenshot reviewed: `build/course-integration/20261105-151156/screenshots/0008_nullspace-station-core.png`.

### Player feedback

- The underlying station system is fine.
- The UI needs a complete revamp.
- Provide better construction previews and a preview of the whole station.

Status: audit record (see the block reference for the current screen).

## 10. Pad Computer

Purpose: register/name a landing pad, show pad and ship-building boundaries, diagnose pad problems and recall a ship.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-4/screenshots/0018_nullspace-pad-computer.png`.

### Player feedback

- "Fine": just barely good enough, not satisfaction or design approval.
- No additional specific change requested here; include it in the shared layout pass.

Status: audit record (see the block reference for the current screen).

## 11. Ship Delivery Beacon

Purpose: choose a ship template and deliver it onto a clear landing pad.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-3/screenshots/0000_nullspace-ship-beacon-catalog.png`.

### Player feedback

- Favorite UI reviewed so far; use it as a positive reference for the other screens.
- Center the ship layout/preview better.
- Consider real ship screenshots or rendering the ship in space instead of the small layout graphic. These are alternatives to explore, not a selected implementation.

Status: audit record (see the block reference for the current screen).

## 12. Programmable Computer Terminal

Purpose: run player-written scripts and commands for ship automation.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-4/screenshots/0013_nullspace-computer-python.png`.

### Player feedback

- UI is "fine" under the audit's barely-good-enough definition.
- The operating system needs work, but this is the feature the player cares least about. Give it the lowest priority.

Status: audit record (see the block reference for the current screen).

## 13. Ship Recall — Pad Computer screen

Purpose: bring an uncrewed ship from space to the pad, preserving cargo and damage without requiring ship fuel.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-3/screenshots/0005_nullspace-ship-recall.png`.

### Player feedback

- "Fine" under the barely-good-enough definition.
- Layout needs work.
- Establish a consistent design language across the interfaces.

Status: audit record (see the block reference for the current screen).

## 14. Flight View — Ship Controller screen

Purpose: manually pilot the ship while viewing it from outside.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-3/screenshots/0011_nullspace-pilot-controls.png`.

### Player feedback

- Button placement needs work.
- Buttons feel very large on the player's screen; revisit sizing and scale.

Status: audit record (see the block reference for the current screen).

## 15. Fullscreen Camera View — Camera/Monitor screen

Purpose: inspect a camera feed, adjust aim/zoom and switch cameras.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-4/screenshots/0006_nullspace-camera-feed.png`.

### Player feedback

- Movement controls do not need dedicated buttons. If retained, use compact directional controls such as arrows rather than large labeled buttons.
- Earlier Camera feedback still applies: fill the screen, raise resolution with a Space Settings option, and improve the overlay.

Status: audit record (see the block reference for the current screen).

## 16. Travel Transition

Purpose: cover the brief world transfer during launch, landing or a jump.

Screenshot reviewed: `build/parallel-verification/20261105-102144-229709/graphics-4/screenshots/0020_nullspace-loading.png`. The saved frame is dark/blurred and shows no readable progress information; it does not capture every transition state.

### Player feedback

- "Fine" under the barely-good-enough definition.

Status: audit record (see the block reference for the current screen).

## Remaining screens: standard review

Apply the shared criteria without another screen-by-screen critique session:

- Battery and Ship Cannon, including obtaining their missing screenshots when implementation reaches them.
- Scanner settings/upgrades.
- Space Settings and graphics controls, including camera resolution.
- Space-start and starting-ship selection.
- Guide book and recipe navigation.
- Controller secondary views: Manual steps, Details and loose-block confirmation.

Standard review means consistent layout, clear purpose and visual hierarchy, readable grouping, sensible sizing, no overlap or clipped controls/text, clear primary versus secondary actions, and useful graphical feedback. Remove the Advanced equipment UI as requested while retaining necessary ordinary controls. Use the Ship Delivery Beacon as the strongest positive reference; do not assume its layout must fit every screen.

Suggest replacement labels/wording for player approval when implementing each affected chunk, before applying them. "Fine" remains barely good enough, not final design approval. Keep the programmable OS at the lowest priority.

## Missing screenshots

- Ship Cannon: no saved UI screenshot found; still needs review.

- Battery: no saved UI screenshot found during the audit. Return to it when a screenshot is available; no tests or game launches were run to obtain one.
