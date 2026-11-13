# Station generation work

This tracks implementation of [tiny ships and stations](../design/tiny-ships-and-stations.md). Existing station journeys, shared prerequisites and tiny ships are implemented and verified locally. Manufacturer layout generation and new room/feature content follow afterward.

- [x] Batch 1: existing station layouts in journey encounters, boarding/return, resume and lifecycle checks.
- [x] Batch 2: versioned station brief, stable saved IDs, reproducible preview selectors and compatibility checks.
- [x] Batch 3: tiny ships, start/beacon selection rules, compact-pad delivery/storage and actual walkthrough.
- [ ] Batch 4: varied seeded manufacturer station layouts, floors and a comparison gallery including multiple seeds for the same brief.
- [ ] Batch 5: room and feature generators.
- [ ] Batch 6: complete permanent station claims/navigation, condition/content, fixed population, player construction and reviewed core screens.
- [ ] Batch 7: final integration and CI release delivery.

## Progress checkpoint: November 12

Batches 1-3 are complete locally. The next implementation batch is manufacturer station structures and floors, followed by purpose-specific rooms. The journey adapter currently uses the existing compact harbor plan; it does not establish that the new station-generation or dungeon-content work is finished. Tiny ships have one shared two-room layout with three maker treatments.

| Scope | Current evidence | Delivery |
| --- | --- | --- |
| Existing station journeys and shared prerequisites | 10 focused unit cases and 27 native mod cases pass with retained results | Local changes |
| Tiny ships | 11 focused unit cases, 21 native mod cases and two graphical flows pass; actual builds reviewed | Local changes |
| Manufacturer station layouts, floors and new rooms | Not implemented | Pending |
| Full integration of the new feature batch | Not run; human playtest acceptance also remains | Pending |
| Latest published build | rc2 release pipeline passed; latest main-branch secret scan passed | Published rc2 excludes these local changes |

CI was checked after the tiny-ship slice: [latest main scan](https://github.com/NullandKale/NullSpace-Mod/actions/runs/43326604820) and [latest release pipeline](https://github.com/NullandKale/NullSpace-Mod/actions/runs/43324931805) both succeeded. Their success does not validate the unpushed station/tiny changes.

The main implementation finding was that existing delivery, flight, docking and storage paths could carry the new tiny layouts without production travel changes. Compatibility still needed attention: expanded catalogs invalidated a test's assumed blank row, and an existing saved showroom would not automatically acquire new designs. The new showroom reserves a separate location and preserves earlier edits.

The unexpected verification cost came from test fixtures, including registering tick callbacks while Minecraft was already iterating them. That could repeat delivery and produce a misleading pad-reservation failure. The fixture now registers its state machine before ticking. Future slices should keep that pattern and collect combined outcomes before corrections; no additional performance work is authorized.

## Current changes

- Added a new abandoned-station encounter with saved ID 4, keeping IDs 0–3 and their meanings intact. Existing saved journeys continue using their recorded kind.
- Reused the current compact Waypoint room generator and station construction path. The new encounter retains a station core, real docks and existing furnishings, with at most one existing supply container selected per room.
- Kept the signal-occurrence roll and cooldown unchanged. Current weights within signals are growing station 5%, new abandoned station 5%, damaged freighter 35%, salvage ship 35%, cargo pod 20%. These are tuning values; the two station kinds together occupy 10% of signals.
- Added a versioned `StationSpec`: existing maker, purpose, size, condition and seed. New station signals save the brief before construction and carry it into the placed station. Invalid selections and unsupported specification versions fail explicitly.
- Added reproducible shipyard preview selectors. The existing station-piece gallery remains available.
- Restricted salvage-to-ship claiming to the original ship/cargo encounter kinds. The new station must not become a movable ship through that path. Permanent station claiming/return navigation remains in the later gameplay-integration batch.

## Preview command

```text
/nullspace shipyard stations preview <maker> <purpose> <size> <condition> [seed]
/nullspace shipyard stations preview rivet harbor compact derelict 419
/nullspace shipyard stations preview halcyon harbor compact operational 419
```

The maker choices are `rivet`, `halcyon` and `bastion`. The brief definitions include all seven planned purposes and three size bands, but this adapter currently places only `harbor compact`. Other combinations are refused before placement. Omitting the seed chooses one and reports it; an explicit seed reproduces the result.

These are existing Waypoint-layout previews. Maker changes the existing materials; the three new manufacturer placement algorithms and the new damage pass are not implemented. Operational/derelict labels are preserved as brief inputs, not evidence of a completed condition pass. Previews add no ambient-site/ownership records and do not stock survival loot.

The command uses fresh clear space, preserves the existing `/nullspace shipyard stations` gallery, and saves the selected brief/seed in a preview receipt. It does not overwrite existing structures.

## Verification

Compilation passes. Ten focused unit cases cover journey selection, stable encounter IDs/names, station rarity, brief parsing/round trips and rejection of invalid/future specifications. Formatting and content checks pass in `build/station12-prepare.log`.

The combined native batch `build/focused-tests/20261112-181741-JourneyGameTest-5c19/` completed 28 cases including the vanilla fixture, with two failures. The preview queried height before loading its chunks and attempted placement below terrain; it now loads the region first. The station journey fixture assumed an engineering locker, whereas this legacy plan supplies other rooms; it now follows actual saved supply markers and the connected return berth.

The affected two-class rerun `build/focused-tests/20261112-182204-JourneyGameTest-cff9/result.json` passes all nine cases including the vanilla fixture. It completes the new station journey through saved-state reload, automatic docking, boarding, actual fuel collection, return, paid resume and cleanup. The registered command places a preview, preserves the old gallery command, records the exact brief/seed, and refuses unsupported layouts before placement. Other station, condition/content, orientation and palette passes are retained.

The combined receipt `build/verification/station12-complete/result.json` records all **ten focused unit cases and 27 native mod cases passing**, with the original failures and affected corrections. Formatting/content checks pass. No graphical redesign, broad integration sweep or performance measurement was run for these two batches. Visual review of the new manufacturer layouts belongs to batch 3.

## Tiny ships

Three new catalog designs share a compact two-compartment survival program: Rivet Spark, Halcyon Wisp and Bastion Dart. Each is 9 wide, 5 high and 15 long, fits a 16-by-16 pad, and has one deck containing only bridge and engineering. The bridge provides controls, scanner, terminal/monitor/camera, bed, crafting table, furnace and a barrel. Engineering provides generators, batteries, engines and fuel storage. Both closed compartments have an air outlet and a clear central route.

The bridge interior is 7 by 6; engineering is 7 by 5. Existing maker materials, stripe colors, window treatments and Halcyon's skylight distinguish the three variants. This is a shared small layout, separate from the larger ship room-placement algorithms. Its footprint is 135 blocks, compared with 570 for the existing 19-by-30 small design.

New space-start settings default to `rivet_spark`. Explicit saved/configured choices and existing players' ships remain intact. All three tiny designs are selectable in the starting-ship picker and beacon catalog. Random ship/salvage selection explicitly excludes tiny presets. The shipyard command reserves a new gallery with all twelve designs; existing gallery structures and edits are preserved.

### Tiny verification

The combined batch covered eleven focused unit cases, 21 native mod cases and two graphical entrypoints. Final results pass with unaffected results retained. Compilation, formatting and content checks pass. The combined receipt is `build/verification/tiny-complete/result.json`.

Native checks cover all catalog hulls, clear equipment outlets, working docks, both closed tiny compartments, first-login supplies/identity, compact beacon delivery, existing start recovery, and storage/deployment on a 16-by-16 pad. The storage case preserves cargo and identity, consumes the matching title and verifies that the entrance faces the west Pad Computer.

The graphical purchase flow completes launch, walking through the real bulkhead door into engineering, station docking/boarding/return, undocking and landing. The separate creation flow selects Halcyon Wisp through the actual picker, logs in aboard it and reloads the same ship. Actual bridge, engineering and all three exterior screenshots were reviewed in `build/focused-tests/20261112-190829-ShipYardClientGameTest-757e/screenshots/`.

Initial failures were in the fixtures: the expanded catalog filled the row assumed blank; the storage fixture omitted authorization and used a raw ship ID instead of its recall selection ID; the walkthrough read a pre-transfer record. Registering repeated tick tasks from inside another running task also mutated Minecraft's scheduler and repeated delivery. Those fixture corrections pass in the affected reruns. Passing new-world/start results were retained, and graphical checks were not repeated for the later native-only scheduling correction.

Retained start checks are in `build/focused-tests/20261112-190431-ShipYardGameTest-5635/native-tests.xml`; the final shipyard check is `build/focused-tests/20261112-191403-ShipYardGameTest-c6d3/result.json`. The new-world graphical receipt is `build/focused-tests/20261112-190536-SpaceStartClientGameTest-38fc/result.json`.

Manual playthrough should still judge comfort and usefulness: sleep in the bed, craft and smelt, refuel, make an FTL journey, and repeat storage/deployment with personal cargo. Those are human acceptance checks, not claims established by the structural tests. The full integration sweep belongs to the final agreed feature batch. No performance measurements were run.

## Follow-up fixes: version, wet rooms and room selection

- [x] Add `/nullspace version` for ordinary players and console use. It reports the loaded mod version and build label. Future CI builds embed their release tag and commit; local builds explicitly report `development build`. The base mod version remains unchanged, so existing release metadata checks still apply.
- [x] Swap upper-floor hydroponics with a compatible first-floor room before furnishing. Preserve the room bag, room geometry, doors and special bridge/entry/engineering assignments. The swapped programs also receive their appropriate window treatments. Hydroponics is currently the ship room that places free water.
- [x] Give functional flooring priority over lower-deck ceiling lights in a shared slab. Soil, water and moss can replace an earlier structural light; later lighting preserves those materials.
- [x] Replace the fixed preferred-room prefix with seeded weighted draws by ship role. Keep the no-repeat bag: draw among fitting types used the fewest times, then normalize the role weights over those candidates. Give the nine larger catalog designs distinct fixed seeds, preserving reproducible copies while varying ordering between designs. Tiny layouts remain the explicit two-room program.

| Role | Relative room weights; unlisted optional rooms have weight 1 |
| --- | --- |
| Scout | Crew, cabin and storage: 4; crafting, galley and lab: 2 |
| Freighter | Cargo and storage: 8; crafting, smelting and mining: 4; crew and galley: 3 |
| Explorer | Lab, hydroponics, viewing, cabin and enchanting: 4; crew, brewing and mining: 2 |

These are relative draw chances, not fixed percentages of a finished ship. For example, with all fourteen optional types eligible, a freighter's cargo draw has weight 8 of 41, about 19.5%. Room fit, fixed reserved uses and earlier draws change the denominator. No type is excluded solely by role, and repeat avoidance takes priority over weight.

The combined follow-up passes **37 focused unit cases and 19 native mod cases**, plus formatting, content and compilation checks. The native batch is `build/focused-tests/20261112-201501-NullSpaceGameTest-e859/result.json`; the combined receipt is `build/verification/version-rooms-complete/result.json`. It exercises the public command, actual fluid ticks and soil preservation in planted catalog rooms, all catalog hulls/docks, the six larger role designs' routes/power/cargo, and compact storage/deployment. The role-fleet fixture now excludes tiny starter designs from its six-freighter/explorer expectations.

CI metadata was checked through the real resource-processing task with a temporary test tag/commit, then regenerated with normal local metadata. No release was published. These generation corrections affect new builds; existing physical ships are not rewritten or automatically repaired. No additional graphical sweep or performance measurement was run.

## Next batches

1. Build the three manufacturer placement methods, real floors/connections/shells/docks and basic utilities; expose comparable seeded examples through shipyard. Require meaningful same-purpose/maker/capacity variation across seeds, not one fixed floor plan per type. Room allocation uses purpose-weighted choices and functional capacity requirements, with deliberate repetition for dominant facilities.
2. Build purpose-specific room and feature generators inside those reviewed structures.
3. Complete condition/content, permanent station claiming/return navigation, rare fixed-site population, player construction and reviewed core screens.

Tiny ships are implemented locally. These changes are not part of the published rc2 asset; the new station structure/content and integration batches remain unfinished.
