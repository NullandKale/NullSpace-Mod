# Playtestable changes

This breaks [the recovery, station and ship design](recovery-stations-and-ship-design.md) into small releases. Finish and playtest one before starting the next. Use focused tests for each chunk; save the full integration sweep for the completed batch.

| Chunk | What you can playtest | Done when |
| --- | --- | --- |
| 1. Choose a destination and Engage | Scanner browsing, controller course selection, automatic departure/travel/arrival | You can leave a pad, reach a station and return to the pad with one Engage per trip |
| 1a. Navigation UI and shared design language | Refined Controller and Scanner, using the interactive audit as requirements | Current location, destination, trip and actions are clearly separated; categories are understandable; no duplicate information or overlapping controls |
| 1b. Equipment UI, one small group at a time | Generator/Engine/Battery, then Air Supply feedback, then remaining equipment | Clear power generation/consumption and status, useful air/leak feedback, consistent layouts and necessary controls without Advanced equipment screens |
| 1c. Camera and flight presentation | Central camera view, compact controls, actual fullscreen, configurable render resolution and improved overlays | Camera switching and aiming are clear, views fill the screen, controls fit the player's display and quality is adjustable in Space Settings |
| 2. Get unstuck | Free rescue to a safe harbor, keeping carried inventory and leaving ship/cargo behind | A player with no fuel or landing site can continue playing without admin help |
| 3. Die and respawn safely | Beds that follow vessels, safe fallback when a bed/ship is missing | Moving or losing a ship cannot strand a respawning player |
| 4. Store a ship and clear a pad | Physical Ship Title and server-backed storage preserving blocks, cargo and damage | Store a ship, use the cleared pad, then deploy the same ship |
| 5. Transfer and recover ships | Hand over titles, replace lost titles, recover stranded vessels | Two players can exchange the same vessel without duplication or old-title access |
| 6. Take over an encounter | Explicit unclaimed/damaged controller states and Claim | Claim an existing derelict without refilling supplies or regenerating it at its old site |
| 7. A useful ship and station | One redesigned workboat and two-berth service outpost; improve delivery previews | Exterior, rooms, signs, supplies and workstations make the visit understandable and worthwhile; delivery keeps its preferred catalog layout with a better-centered ship preview |
| 8. Build a station | Starter dock, doorway-based room placement and revamped Station Controller | Place, dock with and expand a station through a clear player flow, with useful construction previews and a whole-station preview |

## Playtest 2 batch (November 6): today's goal

From the player's first session on the hosted server. These come before chunks 7 and 8. Order matters: the oxygen model is the base for canisters and suits, and the power/fuel balance tunes all of them.

| Chunk | Change | Done when |
| --- | --- | --- |
| P1. Beds on planets | A bed aboard a ship uses vanilla sleep wherever the world has a day/night cycle; **Respawn aboard this ship** is only used where you cannot sleep (space). Launching still converts the bed into a ship-relative respawn. | Sleeping on a pad works; in space the bed sets ship respawn and never explodes |
| P2. Block facing and connections | Audit everything the mod places (ship blueprints, rooms, encounters, stations, harbor): beds, furnaces, crafting stations, chests, doors and stairs face their walkway or use; panes, fences and walls connect. | Generated ships, stations and the harbor show no backwards beds/furnaces or pane spikes |
| P3. Terra Harbor | Site selection avoids registered pads and other reserved areas (it overlapped the first-arrival landing site); correct facing and connections; non-operators can use the workstations and garden; spawn-protection interaction checked. | The harbor never overlaps a pad, looks intentional and is usable by any player |
| P4. Oxygen model | 0–100 scale. Air is per block in the room volume and the player's meter drifts toward the air where they stand; 0 means losing air bubbles. A one-block leak drains a room from 100 to 0 in about 30 s, a large breach (5x5) almost instantly, with no refill until patched. Two Air Supplies at full power just hold a one-block leak (expensive). Suited and tethered never drops below 20. With no air and no suit you die in about 20 s. Visualization and the Air Supply screen show the same numbers. | Focused tests measure the drain times, refill, tether floor and suffocation time |
| P5. Air canisters | Fill at an Air Supply; the suit draws from a canister when no ship air is available (airless planets away from the ship). Shows remaining air. | A suited player can walk away from the ship on an airless planet for a canister's duration, then refill |
| P6. Two suits | A plain space suit (vacuum and air, little or no armor) is the start kit; an armored space suit is crafted later. Existing suits migrate to a defined tier. | New players start in the plain suit; armored suit is craftable with real armor |
| P7. Power and fuel balance | Rebalance power draw for every block and make fuel use more efficient; fuel should not be the dominant cost of running a ship. Tuned together with P4 (air cost) and P5. | Documented targets (coal per hour of normal operation, launch/jump cost) are met by the numbers in tests |
| P9. Galaxy and star map | Per-world random galaxy on a 2D chart with distance-based jumps, engine range limits, a guaranteed hop route everywhere, zones for stations, and planet types with biome-locked vanilla terrain. Needs a new world. See [P9 design](#p9-galaxy-design) below. | See the P9 "done when" list |
| P8. Village space ports | A space-port piece in some villages: registered landing pad, Pad Computer and service point, generated by the normal village generator. Shares the structure approach used for the harbor. | Some new villages contain a usable registered port |

Player decisions for these chunks: the oxygen meter drifts rather than jumps; leak drain scales with total hole area; **1 coal = 1.00 fuel**, shown with two decimals everywhere (internal units stay integers); the P9 galaxy design below (new world, random non-home systems, engine range limits with a guaranteed hop route, a fully visible map, stations only in zones, fuel as the main resource with no refuelling).

P7 proposal (pending confirmation): idle powered ship about 4 fuel/hour, launch + land about 0.5, local jump about 0.5; interstellar jumps scale with P9 distance; an Air Supply draws 4 power at full output so holding a leak is a noticeable cost. Balance targets for P7 still need the player's numbers; propose defaults and confirm.

## P9 galaxy design

Agreed with the player on November 6. The hosted server starts a **new world** for P9; there is no migration of old places. The current Sol/Vega/frontier layout and its flat surface dimensions are replaced.

**Chart.** Each world gets its own random chart, saved once and never rerolled. Sol is the home system, with Terra (the overworld), Luna, Ares and some new non-landable bodies. The **four other systems are fully random**. Planet counts are spread out, not clustered (for example 2, 4, 6 and 8, shuffled; Sol has about 4). Planets have 0–3 moons, with more around gas giants. About 40% of bodies are landable. Systems sit on a 2D chart about 100 ly across with a minimum spacing. Features that aren't stars are also on the chart: nebulae, asteroid fields, rogue planets, and one rare oddity such as a pulsar or black hole. You can jump to features but not land on them.

**Jumps and range.** A jump within a system costs 0.50 fuel. A jump between systems costs a base amount plus a per-light-year rate, for example 1.00 + 0.10/ly. Each engine tier has a maximum jump distance, about 25, 50 and 100 ly.

**Reachability guarantee.** A ship with the starting engine can reach every place by hopping. Better engines only skip hops. Generation connects the systems with the cheapest set of links, then places stepping-stone features (nebula, asteroid field, rogue planet) along any link longer than the starting range until every hop fits. A check runs on every generated chart, and a unit test covers thousands of seeds.

**Fuel.** Fuel is the challenge and the only resource you really manage. There is **no refuelling** at features or gas giants. Long routes need planning and fuel on board.

**Star map.** A Star Map page on the ship terminal shows **everything from the start**: every system, body and feature, labelled. Nothing is hidden. The Galaxy view shows distance and fuel from where you are, greys out links that are too long for your engine, and shows the hop route and total fuel for a far selection. The System view shows the star, planets in orbit order, type, whether you can land, air and moons. Selecting an item sets the destination.

**Zones and stations.** A zone is a region of space your ship can fly in and where you meet other players. Today that is the orbit around a body (the existing local spaces). P9 gives every body and feature a zone, including those you can't land on. Stations, generated or player-built, are always in some zone, as everything in space already is. No extra placement rule is needed. Zones around non-landable bodies (gas giants, toxic worlds, stepping-stone features) are the best station space, and busy stepping stones become natural meeting points. Generated stations are sparse: Sol has one Waypoint and one Manufacturer Exchange, two or three more are spread out in non-landable zones, and one or two derelicts sit at features.

**Landable surfaces.** Minecraft creates dimensions only when the world is made, so the mod ships a fixed pool of surface dimensions for each type. The chart assigns pool slots to planets, and unused slots stay empty. Each surface uses vanilla terrain generation limited to a fixed biome set:

| Type | Biome set | Air |
| --- | --- | --- |
| Desert | desert, badlands, eroded badlands, wooded badlands | none |
| Ice | ice spikes, frozen peaks, snowy slopes, frozen ocean | none |
| Lava | basalt deltas, soul sand valley (nether-style terrain) | none |
| Ocean | deep ocean, warm ocean, rare mushroom islands | some breathable |
| Jungle | jungle, bamboo jungle, swamp | breathable |
| Barren moon | stony peaks, stony shore | none |

Villages and structures are off on every surface except Terra; caves and ores stay. Real terrain design (custom blocks, resources by planet type, hazards) is a later chunk and needs its own planning.

**Done when:**
- Two seeds give visibly different charts, each with 5 systems, spread-out planet counts, moons, types and features.
- Over thousands of seeds, every place can be reached from every other place with the starting engine.
- Jump cost grows with distance, and jumps beyond the engine's range are refused with a clear reason.
- The star map shows the whole chart and plans a multi-hop route with its total fuel.
- Landing on each surface type shows only its biome set.
- Every body and feature, landable or not, has a zone you can jump to and fly in. Generated stations are sparse.

## Playtest 3 notes (November 6)

Taken after P1-P9. The first five rows are fixed for the next release: only the owner is issued a title and the owner deploys without one, stale copies are marked "Void Ship Title"; a closed doorway breathes the best adjoining room; fuel is drawn from every generator in proportion to what it holds; Engage closes every door and stops if one will not close; the Air Supply screen shows how many cells and levels its room spans. Encounters still need a design pass.

| Note | What was seen | Likely cause / direction |
| --- | --- | --- |
| Duplicate titles | It is easy to end up holding two Ship Titles for one ship. | Handle it gracefully: one title stays valid, extras are recognised as copies (or void) without breaking ownership or recall. |
| Losing a title | Losing a title must not break anything. | Ownership and recall need a recovery path that does not depend on the physical title surviving. |
| Standing in a door reads as vacuum | In a doorway the oxygen meter treats you as outside, not in the room. | A door cell belongs to no room; take the air of the adjoining room (best of the two sides). |
| Fuel drawn unevenly | Fuel comes out of the generators in an odd order. | Spread draw across all connected generators instead of draining one first. |
| Engage should close doors | Engaging a course leaves doors open. | Engage closes all doors (as Close all doors does) before departing. |
| Air upstairs | Question: does an Air Supply fill a floor up a staircase? | Yes if the stairwell is open: stairs do not seal, so both floors are one room. A closed hatch or solid ceiling makes a separate room. Worth showing in the Air Supply screen. |
| Encounters feel flat | Random encounters do not feel random or challenging. | Generated encounters need more variety and real threats (enemies, hazards, loot worth the risk). Needs its own design pass. |

## Next batches (agreed November 6)

1. **Batch A: cleanup (done, not released).** Removed the six retired blocks, the old camera/monitor variant, station adoption from old saves, the scene-layout migration, the old power-allocation format and old player-data folders; reworded "legacy ship" as systems not set up; updated the README; deleted the stray `v0.2.0` tag. Still present, for a later pass: computer disk and string-format migrations, admin recovery of unclaimed old ships, and the setup-incomplete air rule (a sealed cabin without an Air Supply counts as breathable), which is a gameplay decision.
2. **Procedural generation design.** One design for how planets, ships and stations are generated, covering variety, threats, difficulty by distance and rewards. Encounters, terrain resources, village ports, the useful ship/station and station building follow from it.

Separately, a cull of old-world compatibility code is pending now that P9 starts a new world: retired blocks, the `legacy_hull` camera/monitor state, legacy (pre-air) ship mode, station adoption from old saves, scene-layout migration, old power-allocation formats, and the legacy airlock path.

## UI refinement added after the navigation playtest

Camera/flight checkpoint: Camera and Monitor now center the video; Monitor has Previous camera and Next camera directly below it. Fullscreen fills the display at its actual aspect ratio, with keyboard aiming, wheel zoom and compact overlays. Space Graphics exposes Camera resolution independently of sky resolution. Flight controls use small H/R/F buttons with tooltips. [Focused verification and screenshot paths](camera-flight-ui-verification.json) cover real camera changes, drafts surviving fullscreen return, player/entity rendering and manual flight. This candidate is separate from the deployed navigation/recovery/title baseline.

The [interactive UI audit](ui-audit-notes.md) is part of this batch. The navigation mechanics have focused verification; their presentation is not considered finished or approved. Start with 1a, then hand back each small UI group for playtesting. Rescue, respawn, titles and station work remain in the plan.

- Establish the shared design language using the Delivery Beacon as a positive reference. Apply the audit's hierarchy, grouping, spacing, scale and overlap requirements to all screens, including those called "fine".
- At implementation time, propose specific replacement labels and ask the player to approve them before applying them. Do not settle wording during this planning handoff.
- For 1b, keep power displays, air/leak tuning and other equipment as separate small deliverables. Air feedback needs functional refinement, not just rearranged labels.
- Include Camera/Monitor preview placement, Previous/Next below the video, fullscreen overlays and compact directional controls in 1c. Camera rendering resolution belongs in Space Settings.
- Give the remaining screens the audit's standard review: Scanner upgrades, start/ship selection, guide/recipes, controller secondary views, and the pad/recall/docking screens. Keep the programmable OS lowest priority.
- Preserve the Station Controller's underlying system while revamping its presentation and previews in chunk 8. Real screenshots versus an in-space rendered delivery preview remain alternatives to review, not an approved implementation choice.

Use screenshots and focused interaction checks for the affected UI; run the full integration sweep at the end of the agreed batch. No gameplay code or tests changed during the audit/handoff.

## Chunk 6: claim a derelict

Ready for playtesting (focused checks). Salvage encounters are generated from real ship designs but used to carry a Station Core that only refused commands. Now, aboard an **unclaimed derelict**, its Station Core shows **Claim and take command**. Claiming makes you the owner, gives you its Ship Title, turns the core into an ordinary ship controller facing the same way, and opens the Ship Controller. The vessel keeps its identity, blocks, depleted supplies, fuel and damage; nothing is refilled. Because its identity is kept, the encounter site never generates a fresh copy, and if the ship is later scrapped the site stays removed.

Public service stations (exchanges, waypoints) are never claimable, even though they have no owner. Claiming requires being physically aboard; a claimed ship cannot be claimed again, and player-owned vessels keep their owner whatever happens to their controller.

Verification, November 5: focused native `EncounterClaimGameTest` (claimable only when unclaimed salvage; refused from outside; same record becomes the claimant's ship with a working controller and title; container contents unchanged; no second claim) plus `JourneyGameTest` and `StationGameTest`.

Follow-ups, not in this slice: a **Damaged** controller state and **Claim wreck** for broken derelicts; claiming while your own ship is still docked to the derelict needs a dedicated check that Engage separates the right ship.

### Try this

1. Investigate a salvage signal and board the derelict. Its Station Core should offer **Claim and take command**.
2. Claim it. You should get its title and land at its Ship Controller; supplies aboard are exactly what was left.
3. Store or fly it like any of your ships. Revisiting the original site should not produce a second copy.

## Chunks 4 and 5: ship titles, storage and handover

Ready for playtesting (focused checks only; the batch integration sweep is still pending). The Pad Computer's **My ships** screen (formerly Bring back my ship) lists your ships with their state: On this pad, Stored, In space, Docked or Crew aboard.

- **Store** a ship on this pad: it moves into private storage through the same uncrewed transfer journal as recall (no fuel, engines or working controller needed), and the pad is clear. A ship already in space is stored where it is. You keep or receive its **Ship Title**, a non-stackable paper with the ship's name and registration (NS-XXXXXX).
- **Deploy here** brings any of your ships in space to this pad, with cargo and damage. A stored ship needs its title in your inventory.
- **Get title** replaces a lost title for the owner. Issuing a title retires every older copy, so a copied or found paper cannot deploy the ship.
- **Handing a ship over:** the owner right-clicks the title to sign it over (right-click again cancels), then hands it to the other player, who right-clicks it to take ownership. The same paper is updated for the new owner; older copies stop working and the former owner loses deploy and title rights. Crew aboard keep their safe exit.
- Stored ships are hidden from the Scanner, docking contacts and the space scene. Respawning at a bed aboard a stored ship falls back to Terra Harbor.

Decision taken while the player was away (reversible): a found or stolen title that the owner has **not** signed over transfers nothing. The design doc's alternative, where any holder may take ownership, remains possible by removing the signing step.

Verification, November 5: focused native `ShipTitleGameTest` (store from a pad clears it and keeps every block; deploy without the title is refused, with it returns the same ship and cargo; replacement invalidates the old paper; owner-only storage in space; signed handover, refused unsigned handover, retired copies) plus `ShipRecallGameTest` and `RecoveryGameTest`; client `ShipRecallClientGameTest` and `PadPreviewClientGameTest`.

Follow-ups, not in this slice: highlight the exact blocks to be captured before storing; a pad owner clearing an abandoned visitor's ship; offline crew still block storage (as they block recall) rather than being reconnected at the harbor; **Scrap ship permanently**.

### Try this

1. Land on a registered pad, step off, open the Pad Computer and choose **My ships**. Select your ship (On this pad) and press **Store**. The pad should be empty and a Ship Title in your inventory.
2. Build something on the empty pad, then clear it again.
3. Back in **My ships**, select the ship (Stored) and press **Deploy here**. It should return with its cargo. Try it once without the title in your inventory: it should refuse.
4. With a second player: right-click your title (signed over), hand it over, and have them right-click it. They now own the ship; you no longer see it in My ships.

## Chunks 2 and 3: rescue and respawn

Ready for playtesting. **Rescue** is in the pause menu (and `/nullspace rescue`, `/nullspace rescue cancel`). It counts down for three seconds, can be cancelled, and moves only the player and their carried inventory to Terra Harbor; the ship, its cargo and other players stay put. It needs no ship power, fuel, pad or Scanner, waits out an in-progress transfer, and retries if the arrival floor is occupied. Arrival grants brief damage protection. Terra Harbor is built on first use beside spawn: a protected arrival floor, crafting table, furnace, composter, a harvestable wheat garden and a ship-service Pad Computer.

Right-clicking a bed aboard a ship sets **Respawn aboard this ship**. The binding is the vessel ID plus the bed's position in the ship, so it follows the ship. A bed carried up from the surface converts during launch. A broken or replaced bed, a stored or travelling ship, lost access, or an unsafe arrival falls back to Terra Harbor with the reason. Players without a ship bed keep vanilla respawn.

Verification, November 5: focused native `RecoveryGameTest` (6 cases: rescue keeps inventory and leaves the ship, cancel, no-ship rescue, harbor reservation/protection/safe floor, ship bed respawn with stored and replaced fallbacks, vanilla players). Integration: 538 unit and 200 native tests passed (`build/parallel-verification/20261105-182848-0fb83b`); all 28 graphical classes passed after migrating stale client tests to the Scanner/Engage controller (`build/parallel-verification/20261105-191912-049572`). JAR SHA-256 `df1ce01cad6fd25507a104d586bd8a93d11d095a1f18df6750de2ae035a1302f`.

Proposed wording awaiting approval: respawn fallback reasons ("Your ship is in storage", "Your ship is between locations", "You no longer have access to your ship", "Your ship is no longer available") and "No rescue is pending" when cancelling with nothing queued.

### Try this

1. Pause and choose **Rescue**, then **Call rescue**. You should arrive at Terra Harbor after three seconds with everything you carried; the screen closes by itself. Your ship stays where it was.
2. Call rescue again and press **Cancel** before the countdown ends. You should stay put.
3. Aboard a ship, right-click a bed ("Respawn aboard this ship"), travel somewhere, then die. You should respawn at that bed.
4. Break that bed and die again: you should respawn at Terra Harbor with an explanation.

## Chunk 1: navigation

Ready for playtesting. [Focused verification](navigation-chunk-verification.json) records the build and walkthroughs. Restart with ordinary `dev.ps1` to keep the current world; `--restart` is the explicit world-reset path.

Latest build: [Controller pass 1 verification](controller-pass-1-verification.json) records automatic encounter docking, the approved Controller labels/layout pass and the Station Core gesture. This is a small UI checkpoint; the remaining audit changes are still planned.

Departure follow-up: [Docked Engage verification](docked-engage-verification.json) records the newer build that undocks and resumes the original paid journey with one Engage click after an encounter visit.

Navigation/equipment UI follow-up: Scanner categories separate Stations from Ships, navigation rows align names and availability, and breadcrumbs avoid repeating the star-system name. Generator gauges distinguish actual generation, ship demand (including unmet demand) and battery charging. Equipment has direct power controls without Advanced; Engine and Battery use the approved wording. Air Supply now shows room-average air with a breathing threshold instead of the pressure at one cell. See [UI batch verification](equipment-ui-verification.json) for the focused results and screenshots. Camera/fullscreen work, fuller air/leak feedback and the remaining audit are still pending.

To review this batch, browse both Stations and Ships in the Scanner, set a destination, then check it in the Controller. Open Generator, Engine, Battery and Air Supply: check the gauges, fuel/upgrade slots and power buttons. Turning generators off should leave ship demand visible. These are playtest checkpoints, not final design approval.

The controller's main travel action is **Engage**. It carries out the needed steps toward the selected destination: undock, launch, travel, land or dock. **Manual steps** keeps the individual controls available for troubleshooting.

Right-click the **Scanner block** to browse the registered destination catalog by star system, planet/moon, then station, ship or landing site. Search is also available. Each station/ship appears once. **Set destination** updates the ship directly without starting movement. There is no marking step or Scanner feed in the controller. **Settings / upgrades** opens the Scanner's equipment inventory.

The controller shows that destination, the trip preview and **Engage**. It retains basic local planet/pad choices so local travel works without a Scanner. Interstellar travel requires an enabled, powered stock Scanner; upgrade items are optional. Engage picks a free berth on arrival. Exact berths remain under **Berths (detail)** in the Scanner browser for troubleshooting.

### Try this

1. Start aboard a fueled, powered ship on a registered pad. Close the doors.
2. Right-click the Scanner. Use the breadcrumb to choose a system and planet, find a station and press **Set destination**. Open the ship controller; the destination should already be selected.
3. Check the trip preview and fuel, then press **Engage** once. The ship should launch, travel and dock without more travel buttons. Open the dock door and visit.
4. Return aboard your own ship. Choose your home landing pad and **Engage** once. The ship should undock, travel and land there.
5. With the Scanner off, try another planet in the same system: it should remain available. A different star system should explain that it needs a powered Scanner.
6. Try **Investigate signal** during travel: after dropping out of FTL, the ship should automatically begin docking with the encounter. No second Engage/Dock click is required. **Disengage** cancels the approach and leaves the paid journey paused; cancellation must not restart docking.
7. Check arrival views: ships get varied pitch, roll, heading and height. Crew remain upright inside their physical cabin, and docking still aligns the doors.
8. Open a station controller with a long-named encounter ship nearby. It should open normally; generated vessel names no longer exceed the terminal packet's old 32-character limit.
9. Check the Controller's first layout pass: distinct Current location and Destination, no repeated planet line, separate Trip/Manual steps tabs, approved action labels, and a footer that leaves Close its own space.
10. To start a station, be in space at the intended site with a Station Core in hand. Hold Shift and right-click empty space or a non-interactive block. The Controller no longer has a station-build button. Existing construction, ship readiness, core consumption and receipt protections still apply.
11. After visiting an encounter, return aboard your ship and open its Controller. It should show the original saved journey destination and an Undock/travel/arrival preview. Press **Engage** once to undock and resume that paid trip; manual Undock and Resume clicks are not required. An explicitly selected different destination remains selected.

Report the destination, the last visible course message, and whether you were landed, travelling or docked. Course completion/refusal is also written to the server log.

This is the first navigation prototype. Rescue, bed recovery, titles, claiming and replacement ship/station builds belong to later chunks above. A stalled or unavailable destination pauses the course for review; it does not choose an unrelated destination.
