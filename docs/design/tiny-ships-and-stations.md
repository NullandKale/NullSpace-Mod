# Tiny ships and stations

Status: design specification for review, November 12. Tiny ships belong in the 0.5.2 release. This document covers station generation, operational and derelict stations, player construction, and the tiny starter. Batches 1 and 2 and the tiny-ship slice are implemented and verified locally: see the [work record](../development/station-generation-work.md). The earlier freeze deferral of tiny ships is superseded.

The manufacturer methods and treatment of floors were discussed together. Values and layouts marked **proposed defaults** still need review; they are not shipped behavior or measured results.

## Decisions and goals

| Status | Decision |
| --- | --- |
| Confirmed | Keep the existing manufacturers. Rivet uses spines and branches; Halcyon uses hubs, spokes and rings; Bastion uses connected clusters. |
| Confirmed | Randomness must produce meaningful variety between stations of the same purpose, maker and capacity. Purpose weights room choices; manufacturer constrains the architectural method. A seed reproduces one station, rather than selecting a single fixed layout for its whole type. |
| Confirmed | Floors are part of those methods, including stairs, landings, galleries and tall rooms. Floors need not extend across the entire station. |
| Confirmed | Every station type has an operational and a derelict version. Derelict is a condition applied to a station with an original purpose. |
| Confirmed | Dungeons combine combat, breaches and optional repairs. Ordinary mining and building remain allowed. |
| Confirmed | Keep claiming simple. Claim an unowned derelict at its core and preserve its damage, supplies and threats. The wider claiming system will have a separate feature design. |
| Confirmed | Stations occur rarely as journey encounters and rarely as fixed places. Preserve room for player construction. |
| Confirmed | The tiny starter has only a bridge and engineering. Tiny ships are available for starts and through beacons, and excluded from ordinary random population. |
| Existing design to complete | A useful enclosed first station section, construction previews/costs, and understandable visitor/builder core screens. |

The goals are recognizable purpose and architecture, useful space, readable routes, varied layouts, real hazards and repairs, compatible player construction, and deterministic persistent results. A larger station earns facilities, capacity and routes rather than giant dead interiors. A passing structural test does not establish that the place is enjoyable.

Use the existing materials, equipment, enemies, power, air, docking and journal systems. Planetary habs, a new economy, automatic restocking and a new ownership progression remain separate features. The larger manufacturer harbor and traffic-queue proposals are not prerequisites for this generator.

## Organizing the design

| Part | Owns |
| --- | --- |
| Lore and content identity | Existing manufacturer, original purpose, architectural character, history and former occupants |
| Gameplay | Activities, challenge, rewards, access, claiming, rarity and lifecycle |
| Engine requirements | Supported bounds, actual walking/collision/pressure/power, docking, rendering, durable construction and identity |
| Generation methods | Layout planning, room placement, construction, furnishing, condition, contents and validation |

Purpose determines activities and facilities. Manufacturer determines architecture. Capacity determines how much is needed. Condition and danger remain independent. Claiming changes ownership; repairing changes condition. Neither rerolls the building or changes who originally built it. Player-built stations do not need a catalog purpose to function.

## Station purposes and derelict counterparts

Choose a dominant activity and the supporting activities it needs. Small stations can combine support functions in one room; larger stations can separate them. Every supported type below also produces derelicts.

| Purpose | Activities and contents | Layout priorities | Derelict expression |
| --- | --- | --- | --- |
| Harbor and ship support | Docking, meeting, expedition preparation, workshops and equipment stores | Clear arrival routes; short dock-to-service connections; supporting crew space | Abandoned berths, depleted stores, damaged workshops |
| Cargo and trade | Receiving, storing, sorting and exchanging goods | Cargo volume dominates; direct receiving/storage routes; living areas off goods routes | Interrupted shipments, scattered goods, damaged bays |
| Industry and maintenance | Smelting, crafting, machinery, materials and repairs | Work bays near materials and receiving; usable maintenance access | Offline machinery, damaged work areas, recoverable materials |
| Agriculture | Growing, food handling, seeds, water, kitchens and stores | Growing volume dominates; services reach bays; harvest routes reach processing/stores | Neglected greenhouses, breached glazing, damaged services |
| Research and surveying | Observation, scanners, brewing/enchanting, collections and archives | Related labs share support; observation has exterior exposure; stores near work | Abandoned collections, damaged observation, finite supplies |
| Habitation | Sleeping, shared living, cooking, personal storage and gardens | Clear arrival-to-home routes; useful communal space; machinery in service areas | Deserted cabins, belongings and interrupted daily life |
| Security and expedition support | Operations, equipment, staging, barracks and lookout positions | Short crew/equipment routes to docks; protected control and stores | Damaged defensive positions, abandoned equipment, isolated sections |

Power, air, circulation and maintenance support these uses. Offices, toilets, mess halls and other storytelling spaces can make a place believable, but should not displace its primary activity or become repeated filler. Research uses existing gameplay; cargo/trade can support players exchanging real goods without requiring NPC shops.

Capacity can require several holds, greenhouses, cabins or berths. Optional uses draw from suitable alternatives before repeating. Repeated control rooms or workshops need a functional reason. A player may add a greenhouse to a cargo station without changing its class.

## Generation brief and pipeline

| Input | Controls |
| --- | --- |
| Purpose and supporting activities | Required facilities, dominant space, contents and important adjacencies |
| Manufacturer | Layout method, architectural preferences, materials, trim, windows and furnishing style |
| Capacity and envelope | Berth sizes/counts, useful work/cargo/growing space, habitation and available dimensions |
| Condition | Operational or derelict, with selected damage/depletion patterns and severity |
| Danger | Combat/environmental challenge and a finite reward budget |
| Context | Fixed site, journey site, player starter or extension; eligible location and reservations |
| Seed and generation version | Reproducible choices within the brief |

Ownership/access are saved runtime state. Player extensions also take the real existing station, requested attachment and paid construction brief. They never rerun the whole-station generator.

```text
brief and budgets
    -> required facilities and space relationships
    -> manufacturer layout graph, levels and vertical connections
    -> actual room volumes, docks, circulation and structural connections
    -> shells, doors, stairs, utilities, exterior and furnishings
    -> validate the intended operational design
    -> operational provisioning OR derelict condition and remaining contents
    -> threats, finite rewards and exploration validation
    -> journaled placement, commissioning and publication
```

The result is a complete placement plan, room identities, connections/levels, equipment, content positions, initial resource state, dock/approach reservations, required bounds and validation results. Save purpose, maker, seed and version as provenance. Runtime condition comes from actual blocks and systems.

Keep layout, furnishing and condition randomness separate so changing a loot roll does not rearrange the station. Use bounded deterministic attempts. Failure identifies the unmet requirement and does not silently substitute another purpose or manufacturer.

## Randomness and variety

Generate a family of stations for each brief. Two agriculture stations from the same maker and size band should share recognizable activities and architecture while differing in their floor plan, growing bays, supporting rooms and routes. Changing only materials, loot or damage does not satisfy layout variety.

- **Architecture:** make seeded choices within each manufacturer's method: branch/spoke/cluster counts, lengths, proportions, elevations, occupied floor footprints, vertical connections and suitable alternate routes. Capacity and clearance bound the choices; do not reduce each maker to one fixed graph with shuffled decorations.
- **Room mix:** place required facilities and reserve their capacity/access first. Fill the remaining budget with weighted draws from purpose-appropriate room pools. Agriculture favors growing, handling and food facilities; industry favors production, maintenance and stores; habitation favors living and shared services. Supporting rooms remain possible across types. A preferred list always consumed in the same order is insufficient.
- **Repetition:** discourage repeating optional support rooms while other useful fitting types remain available. Purpose-required repetition is intentional: several growing bays, cargo holds or residential sections can establish a station's identity. Do not force the ship's one-of-each bag onto a station or give every large station the same complete room checklist. Vary repeated rooms' volumes and arrangements while meeting their function.
- **Details and condition:** vary compatible furnishing arrangements and authored feature choices within the allocated rooms. Derelicts vary coherent damage/history patterns over that generated working design. These passes preserve reserved routes, utilities and the arrival dock.

Weights are relative chances among currently eligible choices, constrained by fit, capacity, adjacency and repetition limits. Define them in purpose profiles rather than hiding fixed room sequences in each layout algorithm. Exact tuning remains reviewable; the current ship-role weights are an example of the draw method, not station balance values.

Give each newly generated site its own seed derived from stable world/site identity or an allocated seed saved before construction. Do not reuse one default seed for all sites of a type. Derive separate random streams for room selection, layout, furnishings, condition, threats and loot. The same brief, seed and generation version reproduces the same plan; different seeds should produce meaningful variations. Paired operational/derelict examples use the same base-plan seed when comparing condition.

Save accepted choices and retain them through construction, reload, claiming and revisits. A construction preview and its eventual build use the same reserved choices; inspecting a menu does not reroll a station. If a bounded attempt cannot meet the brief, report that failure rather than silently falling back to a universal fixed layout.

## Manufacturer generation methods

Each manufacturer has its own room-placement method while sharing room builders, equipment, connections and validation. Purpose/capacity constrain each method. Controlled variation preserves identity without producing the same floor plan repeatedly.

### Rivet Works: spine and branches

Place a main service spine and arrival/docking connections. Allocate dominant work/cargo spaces along it, with short receiving routes. Attach smaller support branches; put crew facilities away from heavy service traffic. Add selected secondary connections where useful, then finish with the existing industrial structure, attached equipment and materials.

The spine can bend, step or divide into a small number of service runs. Vary branch lengths, sides and room proportions. Avoid identical mirrored boxes down every straight corridor.

**Floors:** stack service spines on selected levels, joined by planned stair towers. Branch rooms from each level. Tall cargo/machinery bays can span several levels with useful galleries and service landings. Upper spines need not cover the lower footprint.

### Halcyon Aerospace: hub, spokes and rings

Place a central arrival/shared hub. Extend spokes toward functional wings and exterior observation opportunities. Allocate dominant facilities in the wings and support spaces along suitable routes. Connect selected wings with partial or complete outer promenades when capacity justifies them. Finish with the existing brighter materials, coherent views and integrated services.

Vary hub proportions, spoke directions/lengths, wing sizes and ring completeness. Small stations need not have a token ring; larger ones should not require every route through one central doorway.

**Floors:** stairs around the hub/atrium connect occupied levels. Wings and promenades can occupy different elevations with deliberate connections. Double-height spaces and views through the hub are useful volume. An upper ring can cover only part of the station.

### Bastion Shipbuilding: connected clusters

Group related activities into compact sections around local junctions. Give each section understandable internal routes and utility access. Connect sections through a limited set of passages and framed bulkheads. Add selected alternate connections according to scale and route length. Place operations, protected stores and staging areas according to purpose.

Vary cluster shape, arrangement, elevation and links. Compartmentalization must not turn every example into a long chain of locked doors or one fragile mandatory passage.

**Floors:** clusters can contain several levels around their own stairwells. Connect clusters at selected matching elevations, using upper bridges where useful. Floor plates stop at section boundaries; no global repeated slab is required.

## Capacity, rooms and floors

Capacity is a functional brief, not a room-size multiplier. Harbors name visiting ship sizes/counts; agriculture names growing area; habitation names useful living capacity. Required rooms and circulation get space before optional content.

**Proposed defaults**, inside supported station bounds:

| Band | Built footprint budget | Typical occupied levels | Initial berth target |
| --- | --- | --- | --- |
| Compact | Up to 32 by 32 | 1–2 | 1–2 |
| Standard | Up to 64 by 64 | 2–3 | 2–4 |
| Large | Up to 128 by 128 | 2–4 | 4–8 |

These are budgets, not filled rectangular boxes or guaranteed berth counts. Whole-ship clearance can reduce the mix. Non-harbor types need not maximize docks. Approaches may extend beyond the built footprint and must be reserved when placing the site. Larger harbor aspirations remain separate from these initial defaults.

A room definition supplies purpose, footprint/height ranges, equipment/access needs, doorway/floor interfaces, exterior/window preferences, furnishing zones and suitable content/damage positions. Use shared alignment conventions without requiring every room to fill one 16-by-16 tile. Full-block pressure shells remain the baseline; decorative trim must not substitute for an unsupported seal.

Allocate vertical volumes before furnishings. Reserve stairs, landings, headroom and floor connections; place floor plates where rooms/routes need them; preserve intentional atriums and tall bays; then fit equipment. Every occupied operational level needs an actual walking route from a dock.

**Proposed geometry defaults:** main routes aim for three clear blocks of width; secondary routes and stairs aim for two. Existing framed doors can narrow locally to their hardware width. Normal rooms target three to five clear blocks of height; tall bays span multiple level bands. Exact rises/landings follow actual block collision and normal player headroom throughout.

Floors can differ in footprint, elevation and room mix. Exterior gaps between rings, arms and piers are intentional silhouette. Inaccessible enclosed volumes inside occupied hulls need a reason.

## Shared room construction

| Responsibility | Method |
| --- | --- |
| Relationships, volumes, decks and docks | Manufacturer layout planner with purpose/capacity constraints |
| Rooms fitted to actual volumes | Reusable procedural room builders |
| Distinctive entrances, machinery and details | Optional authored features declaring interfaces and clearance |
| Doors, stairs, corridors and joins | Shared connection builders and geometry checks |
| Shell, structure, windows and exterior finish | Manufacturer-aware construction rules |
| Furnishing | Purpose-aware placement preserving walking and working zones |
| Damage and contents | Separate passes over the completed design |

This avoids separately authoring every complete purpose/maker/size/condition combination. Room builders do not own spawn frequency, ownership or loot entitlement. Layout graphs do not excuse invalid physical headroom.

Keep the architectural plan, walking graph and pressure compartments distinct. A closed bulkhead blocks walking/air without arbitrarily dividing the current shared electrical bus. Real edits update runtime geometry; the original plan never restores player changes.

## Equipment, supplies, threats and rewards

Operational stations have reachable equipment and working power/air for intended activities. Initial resources are explicit finite state, initialized once. Visiting/loading never refills them. Public access must permit advertised services; a protected locker alone does not establish a usable fuel stop.

Furnish first, then populate valid positions. Give each site an overall budget based on danger, scale and intended exploration, with modest rewards consistent with the existing space-start balance. Purpose allocates appropriate shares; extra barrels do not independently multiply loot. Keep reduced fuel quantities and cheap hull materials.

Use existing supported enemies in valid standing/flying spaces, with usable routes and intentional encounters. Never spawn them inside fittings, through floors, in the boarding transfer position or in untracked voids. Danger is independent of station purpose and derelict status.

Player construction places paid building/equipment. It does not seed enemies, dungeon treasure or complimentary stocked containers. Initial fuel/air is included in the paid preview. Reusing a laboratory builder must not make repeated construction a source of free rare loot.

## Operational and derelict condition

Generate the same coherent base design for both conditions. Apply a few related patterns: ruptured bay, depleted engineering, isolated intact habitation, abandoned work area, collapsed gallery or interrupted shipment. Use localized damage and remaining contents suited to those spaces. Preserve pockets of intact work/living space; avoid independent random holes throughout the building.

Breaches affect real air and bulkheads can contain them. Generator loss affects the actual power budget. The shared bus does not imply independently switchable electrical grids for every wing. Lighting damage uses supported block behavior, not fictional per-room power labels.

Repairs can recover breathable space, equipment or a convenient route. Mining/building can bypass problems. Broken stairs can offer repair opportunities, but the condition plan must identify a feasible route or ordinary modification to intended spaces and back. It cannot silently assume flight or an unavailable special tool.

The primary dock, frame, approach and immediate boarding space remain usable. The rest need not be fully sealed, powered or breathable. Do not require players to repair inaccessible docking hardware before boarding. Validate the working design first, then the dungeon; reject/retry invalid damage within a bounded budget without replacing its purpose/maker.

## Site placement, claiming and persistence

Population chooses rare sites independently of building generation. Most regions remain empty. Context controls eligible locations and lifecycle; fixed/journey sites share supported purpose/manufacturer definitions and derelict counterparts.

**Proposed defaults:** one or two fixed dungeon stations across the initial galaxy; stations around one in ten encounter signals. The latter is a signal share, not a chance per jump, and does not raise overall encounter frequency. Fixed sites can include operational public places and derelicts; journey signals default to derelicts. Exact weights and size distribution remain reviewable tuning values.

Choose fitting places, save fixed identities/plans once, and reserve full approach/ship envelopes clear of arrival zones, other sites and player builds. Menus and revisits never roll new stations. Review the existing unclaimed fixed-site reset policy and its removal of edits within site bounds before applying it to new types; communicate it clearly. Claimed/player stations never enter cleanup lists.

### Simple claiming

Reach the core and choose **Claim station**. Add no combat-clear, restoration, timer or repair prerequisite. Preserve identity, blocks, condition, supplies, surviving enemies and the previous ship. Retain a Station Core/station behavior; do not use the salvage conversion into a movable ship.

Ownership/access and removal from cleanup become durable together before success is shown. Repairs happen through normal play. Existing property stays protected under current access rules. The wider ownership redesign remains separate.

### Claimed journey destinations

Proposed navigation contract: preserve the encounter local space and physical arrangement, register a permanent station destination/approach point, and retain the journey's chart/route anchor needed to revisit it. Docked ships/crew retain positions and connections. Ending the signal releases temporary event state, not the claimed station or its occupied local space.

Navigation exposes a normal route and fuel/range checks. Claiming must not grant free teleportation or leave an unreachable permanent build. The exact route-anchor representation needs a navigation-code review before implementation; it is a required integration item, not a new claiming progression.

Store original purpose/maker and seed/version as provenance. Load the actual world as authoritative. Repairs, additions, depleted inventories and surviving enemies persist through reload and claiming. Updating generation never rebuilds existing stations automatically.

## Player-built stations

### First section

Use the existing deployment entry point to preview a complete enclosed arrival/work section, Station Core, dock, generator/air equipment, basic storage and real utilities. Show the full footprint, approach, materials and initial fuel/air provision before commitment.

Reserve/pay through durable construction, build and commission, then dock after the section and save complete. The result is a usable place to enter and work. Failed/cancelled construction preserves materials/ship according to its transaction and never exposes a half-built ready station.

### Extensions and costs

1. Choose an attachment on the real station, including a level where relevant.
2. Choose a room, corridor, stairs or dock, with supported style/size options.
3. Inspect the complete preview, joins, clearances, equipment and cost.
4. Confirm construction; the reserved area becomes available after commit.

Obstructions explain what is in the way. Modify only joining walls/floors shown in the preview. New berths reserve visiting ships and approaches. Vertical additions include their stairs and cannot strand the new floor. Leave useful future attachment opportunities where practical.

Keep the current Module Kit item and existing materials. Proposed costing uses room-equivalent kit quantities for size/floors plus separately required fittings and initial supplies. Arbitrarily large additions must not cost the same solely because both are called a room. The concrete cost sheet and starter recipe remain review items; add no new currency.

Keep freehand building within supported bounds. Players can mix activities and architecture. Preserve existing blocks, resources, damage and edits. Removed walls update routes and air rather than being restored from catalog tiles.

## Station Core presentation

| User/place | First view and actions |
| --- | --- |
| Public visitor | Name/purpose, observed condition, floor/room map, actual accessible services, own ship/return berth |
| Explorer at an unowned derelict | Original purpose/maker, condition/map, ownership status and Claim station |
| Owner/builder | Real rooms, floors, doors, berths and utilities; Build/preview, docks, rename and people/access as authorized |

Proposed structure: compact identity/status header, role-appropriate pages, broad central map/work area, selected-room information and actions below. Show one floor at a time with marked stairs and tall volumes. Avoid compressing all levels into one unreadable map or permanently surrendering most width to a legend.

Use real geometry and door state, with saved room names as orientation aids. Show removed connections and damaged spaces, not the pristine blueprint as reality. Summaries use actual air/power. Repairs stay at equipment. Unowned dungeon interaction permits the agreed mining/building and container access; ordinary public visitors do not receive construction controls.

Responsibilities are specified; actual-dimension terminal/preview mocks still need joint review before UI implementation.

## Tiny ships

The explicit program is bridge plus engineering on one deck. The bridge combines controls, sleeping, crafting and small storage; engineering holds working power, air, battery and propulsion. No additional bedroom, workshop, cargo or airlock room. Do not let a larger-ship side-room bag or station-support list force extra rooms into the hull.

Implemented footprint: 9 by 15 including equipment/trim, 5 blocks high, fitting a 16-by-16 pad. The bridge interior is 7 by 6 and engineering is 7 by 5, with a central bulkhead door and clear aisle. The [earlier sketch](../images/tiny-ships-and-stations-draft.svg) remains a design reference. Dock enters the bridge; engines occupy the stern. Furniture preserves boarding and equipment access. Hardware counts must satisfy the actual electrical budget.

Use one shared two-room program with simple existing maker treatments. The local implementation adds Rivet Spark, Halcyon Wisp and Bastion Dart, preserving the same essential functions without three elaborate algorithms. Actual interiors and exteriors have been reviewed from graphical walkthrough screenshots; human playtest acceptance remains separate. New space starts default to Rivet Spark unless explicitly configured otherwise. Existing larger designs stay available.

Use tiny presets for new starts and beacon delivery; exclude them explicitly from random population and salvage selection. Preserve the current survival-opening supply budget and scanner capability. Never replace existing players' ships automatically.

Acceptance follows real survival actions: sleep, craft, fuel, equipment use, launch/jump, dock/board/return, entrance-facing landing, store/deploy, and preservation of cargo, air, energy and identity. Compare the actual build with existing small ships to establish that it is genuinely tiny.

## Engine constraints and compatibility

Current station support starts at 32 by 32 by 32 and expands to 128 by 32 by 128. Rendering uses 32-block sections; nearby-vessel/section budgets also constrain berth capacity. Audit runtime dependencies when integrating layouts. Render boundaries are never required room boundaries, walls or floors.

The 16-by-16 tile, two stories per piece and one-loop maximum are old generator policies. Replace their layout assumptions while preserving supported simulation bounds/current docking hardware. Existing blocks, room records and resources must keep loading.

Integrate station layout/pieces/blueprints, room builders, doorway discovery, construction selection, section rendering, population and cleanup together. New graphs are not ready if construction/UI still requires four identical tiles. Validate actual walking, structural connectivity, pressure and utilities separately. Stations need no ship engines, launch inspection or surface pad. Visiting ships retain their own air/power and identities.

Construction and claiming use production journals/recovery; readiness follows durable state. Interrupted work must not duplicate stations, inventories, enemies or payment. Generation metadata records provenance and never overrides player edits on load.

## Acceptance and implementation batches

| Area | Required evidence |
| --- | --- |
| Generation | Deterministic bounded plans across supported purposes, makers, capacities and both conditions; valid interfaces/dimensions/content budgets |
| Variety | Multiple seeds for the same purpose/maker/capacity produce different useful layouts, room mixes and floor arrangements; repeated seeds reproduce exactly. Review actual examples side by side, including purpose-appropriate repetition. |
| Geometry | Actual routes through rooms/floors/stairs, equipment access, declared dock mix and absence of accidental enclosed dead space |
| Systems | Real operational power/air and finite one-time provisioning |
| Dungeons | Usable arrival/return, real hazards, optional repairs, feasible mining/building and claims preserving damage/contents |
| Construction | Starter/extensions match preview/cost, preserve work, support floors and recover interrupted payment/building |
| Persistence/navigation | Correct reload/claim/cleanup behavior; reachable claimed sites; old stations load |
| Experience | Recognizable purpose/manufacturer, useful space, readable routes/screens and playable fresh tiny starts |

Review actual examples of every purpose and representative maker/capacity/condition combinations, not only graphs. During implementation use coherent combined checks, fix collected failures together and retain unaffected passes. Freeze sources for one final integration batch. Design/documentation needs no Minecraft launches. Performance measurements remain stopped.

Include same-brief, different-seed examples in the shipyard comparison gallery. Pure checks should detect constant-layout generation and unintended fixed room prefixes; the in-game review must establish that the variation changes exploration and useful space while retaining manufacturer identity. Testing different purposes alone does not demonstrate randomness within a type.

### Implementation order agreed in the follow-up

Reuse working stations in journeys first, then complete the independent tiny-ship slice. Then build the new manufacturer layout/structure generation and expose it through the shipyard command. Develop the room and feature generators after the structures and floors can be reviewed in game. Purpose, manufacturer and condition remain separate throughout; the first journey integration does not depend on finishing all room content.

| Batch | Deliverable | Verification and review |
| --- | --- | --- |
| 1. Existing stations in journeys | Adapt existing station blueprints to the existing signal/site/build path. Preserve saved encounter identities, ordinary docking, paid journey resume and cleanup. Add a reproducible way to select the station case for checks. | One combined existing-station journey flow: signal, build readiness, investigate, dock, board, return, resume and cleanup/reload. No new generation algorithm is needed for this proof. |
| 2. Shared definitions and other small prerequisites | A station brief for purpose, existing maker, capacity, condition and seed; stable saved kind/version handling; command selectors and reproducible seed. Keep this small and directly useful to the next batch. | Focused pure checks for selection, reproducibility and compatibility. Avoid a separate framework or migration project. |
| 3. Tiny ships | Three compact maker options with bridge and engineering only; start/beacon selection, random-population exclusion and compatibility. Implemented locally. | Combined focused units, native hull/air/power/delivery/storage checks and actual picker, launch, station visit, walking and landing flows; review the generated builds. |
| 4. Manufacturer layout and basic construction | Rivet spines, Halcyon hubs/rings and Bastion clusters, with purpose/capacity allocation, variable room volumes, floors, stairs, connections, shells, docks, core and minimum working utilities. Use plain placeholders or existing furnishings where appropriate. | Pure plan/geometry checks plus one combined in-game review of the actual three methods and floors through the shipyard command. Review walking space, silhouette, volume use and dock approaches before detailed furnishing. |
| 5. Room and feature generators | Fill those allocated spaces with purpose-specific equipment/furniture, growing/cargo/work/living/lab facilities and distinctive manufacturer details. Preserve reserved walking and stair space. Add content/damage markers to support the condition pass. | Combined room/feature checks and an in-game gallery/walkthrough. Judge rooms inside the generated structures, not only as isolated tiles. |
| 6. Complete gameplay integration | Operational/derelict provisioning and hazards, finite rewards, rare fixed/journey population, durable simple station claiming/return navigation, and player starter/extensions/core screens using the reviewed parts. | Relevant combined native/graphical checks covering real exploration, claiming/revisit, cleanup, construction/payment and saved edits. Finish and review screen mocks before UI implementation. |
| 7. Final acceptance and delivery | Tiny ships and selection rules completed, all agreed station behavior integrated, sources frozen and release delivered through CI. | One final integration batch with retained results and affected reruns, followed by the checksum-verified CI asset. Performance measurements remain deferred. |

Tiny ships are independent of the station content pipeline and are implemented locally as batch 3. The work record lists their verification and remaining manual acceptance. They remain required in the next release asset.

### First journey adapter: existing code and boundary

`JourneyPlan` already selects four signal kinds, including the growing station. `JourneyService` saves/registers the signal and selects its dock, and `EncounterSites` builds a `StationBlueprint.Plan` through `StationBuilds`. Reuse this machinery for the existing station plans instead of creating a second event system. Keep existing saved kind meanings stable, and introduce explicit selection/weights for additional station variants without increasing overall signal frequency.

The small proof is placement, boarding/return, paid resume and the normal site lifecycle. Claiming has a separate required integration: current `Claims` converts salvage into a ship, while special journey regions are excluded from ordinary catalog/return travel. Preserve station behavior and provide a durable reachable claimed destination before publishing the completed feature. Do not conceal this gap by only checking that the station blocks appeared.

### Shipyard generation review command

The existing `/nullspace shipyard stations` gallery remains available for old pieces. Extend the command with a generated-station preview accepting maker, purpose, capacity, condition and optional seed. Proposed form: `/nullspace shipyard stations preview <maker> <purpose> <size> <condition> [seed]`. Report the seed and brief so an unwanted result can be reproduced.

The first new preview must place the real walkable structure: shells, floors, stairs, doors, core, docks and minimum utilities. Empty boxes or graph diagrams alone are not sufficient. Detailed room content can use placeholders or the current builders until batch 4. A gallery option should arrange comparable examples of the three manufacturer methods using the same purpose/capacity and labeled seeds.

Reserve clear space and use existing construction/placement safety; the command must not overwrite player work. Preview placement does not create normal world-population entries or award survival loot. The same generator entry point later supplies journeys, fixed sites and player construction, avoiding a separate showcase-only implementation.

### Remaining review items

- Capacity/berth bands and room/stair geometry defaults.
- Functional room definitions and exterior examples using the agreed methods.
- Population weights, size distribution and unclaimed fixed-site reset policy.
- Construction cost sheet and starter recipe/provisioning.
- Persistent journey-destination route-anchor representation.
- Human playtest acceptance of the implemented tiny furnishing/exteriors and three compact maker options.
- Actual-dimension core and construction-preview mocks.

These are bounded review items. The manufacturer methods and multi-floor model are established; choosing one dungeon theme is not a substitute for implementing the shared generator.

## Earlier designs and implementation gaps

| Source | Existing intent / current implementation |
| --- | --- |
| [Recovery and station design](recovery-stations-and-ship-design.md#the-station-core) | Room map/complete visitor-builder flow unfinished; rc2 salvage claiming covers only a small part |
| [Useful first section](recovery-stations-and-ship-design.md#building-a-station-that-produces-a-useful-first-place) | Enclosed first place, preview and cost already intended; deployment currently starts with an exposed platform and tile/kit choices |
| [Station pieces and systems](station-modules-and-systems.md) | Shared parts/systems exist, with uniform tile/two-story generation |
| [Procedural generation](procedural-generation.md#site-catalog) | Existing growing encounter and derelict ships do not complete this station-purpose/derelict catalog |
| [Manufacturers and harbors](procedural-ships.md#harbors-not-built) | Existing identities underpin the three methods; full reference harbors/traffic queues unbuilt |
| [Station reference](station-reference.md) | Current access, construction, rendering and system rules, not completion of this design |

The growing-station drawing is only a route illustration. Purpose, manufacturer, capacity and condition stay independent throughout generation and play.
