# FTL journeys and encounters

Implemented first pass. Jump now starts a timed journey aboard the existing ship. The short loading screen only synchronizes a region change; the countdown runs with normal movement, crafting, inventories and ship controls available.

Controller captures: [Investigate signal](images/ftl-investigate.png) and [Resume journey](images/ftl-resume.png).

[Flight motion and transitions](flight-motion-and-transitions.md) describes engine-defined forward direction, directional FTL streaks, docking parallax and arrival fades. Region changes preserve the ship's orientation.

## Player walkthrough

1. Choose a destination at the Ship Controller and press **Jump**. The same destination still covers its region, landing pad or docking port.
2. Walk around the cabin while travelling. The HUD shows the destination and remaining time. The controller shows the same journey; Details includes the fuel cost and estimated duration before departure.
3. A signal appears partway through the route. Keep travelling to ignore it, or press **Investigate signal** at the controller. An unresolved signal explains that it is still resolving; it becomes available when its physical vessel finishes construction.
4. Investigation pauses the trip, enters the encounter's space and selects its available docking port. Open the controller and press **Dock**. The destination picker still allows choosing another berth.
5. Open the ordinary docking door and walk through. Collect real supplies, repair access with ordinary blocks/tools, or salvage equipment. Return through the docking connection.
6. **Undock**, then press **Resume journey**. The original destination selection is restored. Completed travel time and the paid jump fee are retained.
7. Arrival happens automatically after the remaining travel time. Use **Land** or **Dock** for the final pad or port, as before.

## First places

| Signal | What is there | Useful supplies |
| --- | --- | --- |
| Abandoned growing station | Sealed arrival room and a lit growing room with 48 mature wheat plants, hydrated farmland and contained water. Three hull palettes follow the manufacturers. | 12 bread, 16 seeds, iron hoe and 8 coal in a barrel; ordinary harvestable crops. |
| Damaged freighter | One of the three manufacturer freighters. Its exterior docking entrance works; two cobblestone blocks obstruct the interior cargo doorway. Break these to reach its working and living rooms. | Engineering fuel, galley food, repair supplies and cargo materials in [separate room containers](ship-rooms-and-resources.md). |
| Salvage ship | One of the three manufacturer scouts, parked and uncrewed. Its machinery, furnishings and hull can be recovered. | Two System Upgrades, 16 iron ingots and 12 redstone at the workbench; 8 coal beside engineering; bread and carrots in the galley nook. |

These places permit visitors to open storage and alter blocks in Survival. Supplies exist in actual inventories and transfer as normal Minecraft items. Machinery has real operating fuel. Crops follow normal Minecraft growth; containers do not refill. The vessels are anchored exploration sites in this pass, without NPC crews, trading or combat AI.

## Timing, fuel and saves

- Unupgraded travel takes **45–90 seconds within a star system**, or **120–180 seconds between stars**, at 20 server ticks per second. Engine upgrades shorten the saved duration using `4 / (4 + tier)`, for tiers 0–3. These are initial balance values.
- The existing 2,000/8,000 burn-unit jump charge, adjusted by engine upgrades, is paid once on departure. Investigation, resuming and automatic arrival add no jump charge. Generators continue consuming fuel for electricity; local docking maneuvers use their ordinary fuel.
- Each ship owns a saved journey: departure, final destination, original destination selection, corridor, encounter identity, duration, completed ticks, paid amount and visited state. Save/reload retains that record. Empty ships pause while their crew is offline; another crew can keep travelling.
- A route has one seeded optional encounter in this pass. World seed and the unordered pair of endpoints determine its identity, role and manufacturer variant. Reversing the route, changing engines or reconnecting does not reroll it. Later journeys can revisit the same physical site.
- Encounter construction uses the existing saved, incremental station plans. Two crews on the same route reach the same object. Removed objects are not rebuilt by normal travel; damaged blocks and depleted inventories remain changed.
- Ordinary orbit population is excluded from FTL corridors and encounter regions. Ships still occupy the existing physical space dimension; encounters do not create additional native dimensions or planetary terrain.
- FTL steers the ship during cruise. Manual piloting, scripted thrust, landing and docking become available after investigation or arrival. Crew must be in safe cabins before a region transition. Automatic arrival uses the paid route even if engine power is lost; unsafe crew delay arrival until they return.
- An authorized surface recall can recover an empty ship after death. Landing through that recovery clears the journey rather than leaving a surface ship travelling in FTL.

## Verification

`JourneyPlanTest` covers stable route identity, reverse routes, speed-independent contacts, role coverage and timing bounds. `JourneyGameTest` exercises all three real templates, safe entrances, accessible rewards, offline progress, shared signals, disk reload, docking, boarding, collecting coal, return, undocking, resume and arrival. The existing multiplayer concurrency test now expects independent cruise records after entry.

`NavigationClientGameTest` uses the real controller clicks for Jump, Investigate and Resume, checks that the cabin is usable during cruise, and continues through the existing generated-planet landing/launch checks. Its timer acceleration changes only the test fixture's saved progress. Production timing has no test-speed setting.

Current run evidence is recorded in [journey verification](ftl-journeys-verification.json).
