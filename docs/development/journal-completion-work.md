# Journal completion work

This continuation uses the **original** scope of [journals and save recovery](../design/journals-and-save-recovery.md), including production BUILD/RECORD journals and recovery `restore`. It also covers the bug and usability queues from the [November 10](../history/playtest-2026-11-10.md) and [November 11](../history/playtest-2026-11-11.md) playtests. New features remain deferred during the freeze. Dedicated landable planet dimensions are an explicitly confirmed bug fix. Further performance measurement and tuning were stopped at the user's request.

## Current state

**November 12 checkpoint:** local batches 1-3 are complete: existing station journeys, shared brief/preview prerequisites and tiny ships. Manufacturer station structures/floors are next. New room content, remaining station gameplay integration, final verification and CI delivery remain open. The latest published build is rc2; its successful CI runs do not cover the current local changes. See the [marked task list and checkpoint](station-generation-work.md).

The subsequent local follow-up adds `/nullspace version` with packaged CI provenance, moves water-containing ship rooms to the first floor by swapping compatible programs, protects functional soil/flooring from shared-slab lights, and weights optional room draws by role with distinct fixed catalog seeds. Its 37 focused unit cases and 19 native mod cases pass. Existing physical ships are preserved; these generation fixes apply to new builds. Details and draw weights are in the [follow-up record](station-generation-work.md).

The next requested batch covers tiny ships, station dungeons and player-built stations. **Tiny ships are now explicitly required for the 0.5.2 release**, superseding their earlier freeze deferral. The [design specification](../design/tiny-ships-and-stations.md) records the agreed manufacturer layout methods and floors. Batches 1 and 2 are implemented locally: existing stations in journeys and shared brief/preview prerequisites. Ten focused unit cases and 27 native mod cases pass with retained results and affected corrections. The [station work record](station-generation-work.md) separates these unreleased changes from the completed rc2 release below. Tiny ships are also implemented locally: three two-room maker options, the new default starter, beacon selection and random-population exclusion. Eleven focused unit cases, 21 native mod cases and two graphical entrypoints pass with retained results; actual interiors/exteriors were reviewed. Manufacturer station layout generation is next; permanent station claiming/navigation and the other station batches remain unfinished. Full integration and delivery of these new changes remain pending.

New rc1 feedback is tracked in the [November 12 playtest](../history/playtest-2026-11-12.md). The approved full-width map and claim-controller handoff pass their focused native and graphical checks. Physical landing rotation, terrain-reaching pad stairs and foundations, and the generated-room/deck corrections are implemented. All 625 unit cases and content checks pass. Local acceptance is complete: 625 unit cases, 276 native mod cases, 32 graphical entrypoints, multiplayer, sixteen MOVE and ten BUILD crash checkpoints pass with affected corrections and retained results. [v0.5.2_rc2](https://github.com/NullandKale/NullSpace-Mod/releases/tag/v0.5.2_rc2) is published through CI, and its checksum-verified asset is installed in the Modrinth testing profile. Use the [rc2 changelog and checklist](../release/0.5.2-rc2-playtest.md) for the next playthrough. The completed rc1 verification below applies before these changes.

The earlier rc1 implementation, verification and release delivery are complete. Checkpoints `49f9670`, `c940571` and `2cdd4b0` contain the journal, save, planet, ship, UI and verification corrections. The complete selected inventory passes with retained results and affected batch checks. The final local receipt is `build/verification/20261111-191029-5d626f/final-result.json`. [Release v0.5.2_rc1](https://github.com/NullandKale/NullSpace-Mod/releases/tag/v0.5.2_rc1) was built and published by CI; its verified asset is installed in the Modrinth testing profile.

Use the [0.5.2 rc1 changelog and playtest checklist](../release/0.5.2-rc1-playtest.md) for the manual playthrough, expected results, optional recovery checks and issue-report template.

**Performance work stopped at the user's request.** Keep the existing implementation, harness and results. Do not run further benchmarks or tune for the one-second target in this batch. The 100-ship remeasurement and 300-ship run are deferred and do not block release. Revisit this code only to address a demonstrated correctness failure; ordinary save/recovery correctness checks remain required. No reliable before/after speedup has been established.

- **Saves/recovery:** production BUILD/RECORD journals, operator restore and batched BUILD completion are implemented. Relevant save, crash, unit and graphical checks pass. Short repeated BUILD cycles and fresh startup cases at 0/100/1,000 journals pass. Further performance measurements are deferred by the decision above. The bounded quota is now 256 MiB, with large-slot unit coverage.
- **Planets/landing:** dedicated stable dimensions for every nongas body, saved individual terrain seeds, retained legacy assignments, atmosphere and arrival clearance are implemented. Native, fresh-process restart and graphical checks pass. Dropped loot is preserved; lava can permit arrival while remaining a takeoff hazard.
- **Ships/inventory:** deployment consumes carried matching titles after the move commits; generated supplies no longer add default custom container names; salvage generator stock and container fuel are reduced. Repeated title/storage cycles, container stacking, battery behavior and a face-connected carved fixture pass. Encounter investigation, boarding, paid resume and arrival also pass native, graphical and integration acceptance.
- **Carved-wall report:** the user explicitly dropped PT10-7. Do not investigate it further or claim it was reproduced/fixed. The incorrect diagonal-connectivity workaround has been removed; hull connections use full faces again.
- **UI/player flow:** scanner selection retry, supported living actors in vessel snapshots, interior-door travel, salvage/guide progression, quieter oxygen status and explicit FTL states are implemented and verified. All 611 units, 31 selected graphical entrypoints and multiplayer pass. Status results retain their original API value; presentation labels stay in the renderer.
- **Starter size:** the two-room tiny designs are implemented and verified locally. Their earlier freeze deferral is superseded. New space starts default to Rivet Spark; old explicit choices and existing ships are preserved. They are not delivered in rc2.
- **Delivery:** complete. Release CI passed, the published JAR and checksum were verified, and the exact asset is in the testing profile. New feature requests and further performance work remain deferred.

## Implementation chunks

The user replaced the earlier small-slice testing preference with larger chunks. Implement each coherent group before one combined relevant test run. Use compile checks while editing. Isolate individual failures only when the combined run demonstrates a problem. Keep one full integration sweep at the end.

| Chunk | Work | Combined verification |
| --- | --- | --- |
| 1. Save durability; performance work stopped | Retain batched BUILD completion, save-boundary changes, large-save quota and startup timing. Fix demonstrated correctness failures only. | Relevant save/recovery correctness checks and selected crash/restart cases. Further benchmarks are deferred. |
| 2. Planets and landing | Dedicated persistent planet dimensions, nongas landability and migration; blocked landing pads; lava arrival/takeoff behavior. | Galaxy unit cases plus native dimension isolation, landing, atmosphere and restart coverage; one representative graphical journey. |
| 3. Ships and inventory | Restore full-face connectivity; container stacking; title lifecycle; starter selection; random fuel supplies; battery behavior. | Related ship/storage/power/content cases together, plus an actual carved-hull transfer and relevant player flow. |
| 4. UI and player flow | Scanner invalid-selection recovery, enemy visibility investigation, interior-door travel restrictions, salvage/guide progression, oxygen presentation and the complete FTL-state design pass. | Relevant unit/native checks together and one combined graphical pass through the changed flows and states. |
| 5. Final integration and delivery | Freeze sources, finish design/status records, verify the complete batch and publish through release CI/CD. | One full unit/native/assets/graphics/multiplayer/MOVE-and-BUILD-crash sweep, then install and hash-check the exact CI release asset. |

## Remaining slices

- [x] Contain unreadable/unsupported journals during store startup, before transfer recovery runs.
- [x] Production RECORD operations and complete replay of saved metadata after a damaged newest copy.
- [x] Production BUILD operations, with crash-safe record capture, locks, and no competing intent replay.
- [x] Operator restore to a clear pad or space slot, journaled and with entity/player recovery.
- [x] Performance fixture and all BUILD/startup scenario smoke checks, elapsed-time throughput, comparison runner and a retained 100-ship baseline.
- [x] Implement batched BUILD saves, correct startup timing and raise the bounded large-save quota with unit coverage.
- Deferred by user: full-size performance remeasurement and the one-second target. These are not release requirements for this batch.
- [x] Implement the November 11 bug/usability queue, keeping new features deferred. The user dropped the original carved-wall report. Chunk checks pass; release acceptance remains below.
- [x] Real crash/restart coverage for BUILD and damaged newest store copies.
- [x] Complete integration with frozen collection batches, fix the complete failure list together, and retain unaffected passes; update the design documents to match behavior and evidence.
- [x] Publish the verified candidate through GitHub release CI/CD and use that release asset in the Modrinth test pack, checking the JAR hash.

## Verification record

Prior-agent evidence: 596 unit cases pass; broader native run 148/149 followed by a focused 31/31 including the failed transfer case. No final receipt for those focused runs. The last full integration manifest predates the journal implementation. The `after` performance run is incomplete. None of these certifies the completed batch.

Implement each complete chunk before its combined relevant checks. Use focused checks to resolve failures demonstrated by that run. Record passes and blockers here before the final integration sweep. The playtest queue was explicitly added before final verification; do not expand that scope during the sweep.

Continuation: fixed per-file containment of unreadable and newer-format journals during initial store replay. All seven `JournalReplayTest` cases pass. The sandbox could not read the local JDK security file; running the focused test with the required local access succeeded. No broad tests launched yet.

The focused journal lifecycle run `build/focused-tests/20261110-235432-JournalGameTest-8100/result.json` passes all five mod cases plus the vanilla fixture, with a final receipt. It covers RECORD replay without forcing a save, nested RECORD edits, intermediate-save BUILD rollback, journal pruning, MOVE rollback isolation and quarantine.

Current production wiring includes title issuance and transfer, crew release and boarding, ship registration and pad reframing, claims, scrapping, shipyard deliveries, Terra Harbor, station core/module construction and docking collar placement/removal. BUILD jobs capture records before intermediate saves, own their footprints between construction ticks and capture distant collars as separate regions. New deliveries use BUILD rollback; old saved delivery orders retain their migration path.

The first focused production run stopped in a delivery test that injected a failure against a fixed store slot. That test also expected the superseded intent-resume behavior. Failure injection now targets the actual alternating slot once, with expectations for durable BUILD rollback and a clean retry. The affected station/delivery/claim classes are being rerun. Sources have not entered final integration verification.

Subsequent focused receipts:

- `build/focused-tests/20261111-000536-ShipYardGameTest-71ff/result.json`: all 14 delivery cases pass, including a partial BUILD rollback, final-write failure/retry and an explicit pre-BUILD version-1 intent migration fixture. The preceding combined production run passed its station and claim cases; its only failures were the two superseded intent tests.
- `build/focused-tests/20261111-000908-SpaceStartGameTest-8a2c/result.json`: all 23 cases pass across starting ships, station regeneration, titles, player rescue and journal lifecycle. Starting ships and regeneration now use BUILD jobs; title, storage, crew, death and bed-binding changes use RECORD journals. Continuous crew position updates do not write journals.
- `build/focused-tests/20261111-001743-JournalGameTest-4938/result.json`: all seven cases pass, including the new operator restore regression: occupied destination rejection, interrupted rescue rollback with entities, export, and a successful command-driven retry with old-hull cleanup and one authoritative passenger.

`JournalRestore` archives an original MOVE as `.restoring` before editing the world. A rescue BUILD records its original journal name, all affected footprints, actual entity snapshots, affected UUIDs and new arrival targets. Rollback restores the pre-rescue world and returns the original to quarantine; commit archives it as `.restored`. Startup recognizes held originals and completes their disposition from the rescue journal. Mutating recovery commands reject an in-progress BUILD or its held original.

Still required: complete scenario smoke checks and representative performance measurements, finish the save-boundary audit, and run final integration with frozen sources. Release has not started. Focused receipts certify only their tested paths.


Latest focused evidence:

- `build/focused-tests/20261111-011618-JournalGameTest-5a4c/result.json`: eight cases pass, including pad and space rescue, occupied-slot preservation, cross-dimension passenger authority, offline arrivals, nested BUILD capture and a RECORD write failure. A failed uncommitted RECORD write restores its in-memory metadata.
- The combined station/journal run passed all station cases; its new space test had a misplaced obstruction and hid its first assertion behind repeated callbacks. The corrected journal-only run above passes.
- `build/shipyard-crash/20261111-010557-ec9035/`: partial saved construction and committed BUILD restart passed. Its damaged-copy case exposed a real defect: a provisional order added and removed in one step retained its intermediate `after` value. BUILD capture now refreshes every address touched during that step, including values that return to their original state, while leaving other steps' unrelated records alone.
- `build/shipyard-crash/20261111-011022-4be7c3/result.json`: the damaged-copy BUILD restart passes after that fix. The focused native BUILD case also replays from an intermediate snapshot containing the provisional order.
- `build/local-crash/20261111-011722-8e4c76/result.json`: both launch and landing pass real process termination, newest-copy corruption and fresh-process recovery. Final integration now selects these two additional MOVE cases and all nine BUILD checkpoints.

The performance harness now has representative templates, station construction, full container cargo, computer disks, animals and crew, BUILD cycles and three-process startup cases. Schema 2 counts completions over monotonic elapsed time and restores tick pacing. The historical simulated-time baseline is incompatible; the comparator rejects it. Short smoke phases establish behavior only. Full-duration measurements and the BUILD save-cost decision remain outstanding.

## November 11 playtest continuation

The initial PT10 changes have focused native, unit and graphical checks. The playtest record lists their behavior and receipts. PT10-7 was subsequently reopened: the corner-connected fixture did not match the user's face-connected carving. Door occupants use adjoining room air without opening the seal. Existing catalogs missing non-Sol landing assignments repair in place; that migration does not establish that physical landing pads are clear.

`build/focused-tests/20261111-075442-ScannerBlockClientGameTest-24af/result.json` passes both graphical entrypoints. Screenshots were reviewed for the consolidated scanner and physical distant monitor. The final 80-tick distant-monitor wait and explicit distance assertion pass in `build/focused-tests/20261111-082632-NavigationClientGameTest-674a/result.json`. Its journey screenshots were also reviewed. `build/focused-tests/20261111-083501-NavigationClientGameTest-2ec6/result.json` also passes actual encounter capture and automatic departure through Continue journey. The fixture now has a real docking base and waits for the full approach and departure distances.

The first full-duration 100-ship BUILD attempt stopped during setup, before measurement: generated planet sand intruded into a fixed-elevation fixture pad. Fixture construction now loads and clears each disposable pad's full launch column before placing a ship. These failed setups supply no performance result. A subsequent setup exposed ordinary passenger-data loading before crew placement; setup now keeps the mover chunks loaded and retries that state before measurement. The corrected run is pending.

The corrected full-duration 100-ship BUILD run `build/performance/20261111-083740-build-100-berths-ready/result.json` passes with zero refusals at every rate. Raw results and the root receipt are retained under `docs/performance/journal-build-100-baseline*.json`. The first repetition reaches 1,914 ms at 0.25 operations/s and 2,682 ms at 0.5/s, with a flat-out ceiling of 0.562 operations/s. This motivated the subsequent decision to batch BUILD saves. The current state above records the completed smoke receipt and the separate 300-ship setup failure.


## Chunk 1 implementation and verification

The production save changes are implemented together: ready BUILD jobs queue for a shared flush, retain their footprint/ship locks through that flush, and retry failed save/commit writes without dismantling live hulls. A metadata-only save cannot commit them. First-arrival ships, the rescue harbor and operator restores can request an immediate shared barrier. New deliveries no longer create legacy delivery orders or force their own intermediate world saves; old version-1 orders keep their migration path. MOVE/recovery rely on the world-save hook instead of writing an extra store copy first. Remaining explicit gallery/showroom barriers are bulk operator-fixture saves; planetary landing-site construction belongs to chunk 2.

Evidence so far:

- 34 relevant unit cases pass, including large-slot validation/reopening and rejecting an oversized declared allocation.
- `build/focused-tests/20261111-134151-SaveCoordinatorGameTest-6235/`: the 70-case combined native run passed 67 cases. It demonstrated three failures: harbor readiness during first arrival, overlapping delivery-fixture coordinates and an immediate restore retry reaching unloaded passenger data.
- `build/focused-tests/20261111-134946-PlayerRescueGameTest-e4db/result.json`: all 28 cases in the three affected classes pass after those fixes. Other passing results were retained.
- `build/focused-tests/20261111-135927-ShipYardClientGameTest-81c7/result.json`: the actual purchase, launch, station visit, boarding return and landing flow passes. The first client run caught a real race: Engage attempted a berth still locked by its pending construction save. The course now waits for that save and continues automatically.

Selected fresh-process crash checks pass: `build/shipyard-crash/20261111-140156-6c6fe0/result.json` covers QUEUED, WORLD_SAVED and CORRUPT_COMMITTED BUILD checkpoints; `build/local-crash/20261111-140352-efed6b/result.json` covers damaged-newest-copy recovery for both launch and landing. Performance remeasurement remains pending. The new queued-before-flush crash window expands final BUILD coverage to ten checkpoints; MOVE remains sixteen. No full integration sweep has started.

Continuation after the chunk-plan update: `build/focused-tests/20261111-141420-SaveCoordinatorGameTest-cb99/result.json` passes the affected save-coordinator cases, including retaining BUILD locks when a flushed save skips the build dimension through `noSave`. The first two batched 100-ship measurements failed in the workload driver: delivery immediately followed scrap on a still-reserved pad, then collar placement immediately followed collar retraction on a still-reserved footprint. Both sequences now await their shared save. These failed measurements establish no pause or throughput result. A shorter repeated-cycle run and fresh schema-3 startup measurements precede another representative measurement.

`build/performance/20261111-142822-batch-cycle-startup/result.json` passes repeated BUILD cycles with zero refusals and fresh schema-3 startup cases at 0, 100 and 1,000 journals. Its short phases establish behavior only. Retain the full-duration 100-ship pause measurement and 300-ship quota retry for the completed implementation batch, rather than rebuilding their fixtures between chunks. The one-second target remains unverified.

## Chunk 2 implementation and verification

Every nongas body, including toxic worlds, receives a stable surface. New surfaces use `nullspace:planet/<body-id>` and the saved body's generation seed. Levels are recreated before journal recovery and player login. Existing assigned dimensions retain their terrain generators, seeds, identities and files; migration fills missing assignments without rerolling bodies. Toxic worlds reuse barren terrain and require a suit. Atmosphere reads the saved planet type rather than assuming unknown dimension names are breathable.

Arrival permits water/lava blocks and moves dropped loot outside the arriving hull without deleting it. Living occupants and solid obstructions still block arrival. Lava above the hull remains a real hazard and can still prevent takeoff. MOVE journals capture the destination region and its scheduled ticks so rollback restores displaced fluids and cover. Planetary landing-site metadata now requests the next coordinated save instead of writing an extra store copy.

- Eleven galaxy unit cases pass, including all nongas landability across thousands of seeds and exact preservation of an existing legacy assignment during migration. Content-catalog checks also pass.
- `build/focused-tests/20261111-143927-StarSystemsGameTest-ad1b/`: 13 of 15 combined native cases passed, including planet identities/seeds/atmosphere, existing journal recovery and cargo travel. Both failures were new landing fixtures passing raw pad IDs instead of a name or `id:` destination.
- `build/focused-tests/20261111-144039-LandingClearanceGameTest-2366/result.json`: both corrected landing cases pass, covering preserved loot, lava arrival/takeoff and interrupted-arrival fluid rollback. Unaffected passes were retained.
- `build/planet-restart/20261111-144234-e1b74f/result.json`: a real process halt and fresh process preserve independent builds at identical coordinates, dimension identities, terrain seeds, biomes and landing registrations. An initial runner attempt did not discover the new entrypoint; registration was corrected before this passing run.
- `build/focused-tests/20261111-144314-GalaxySurfacesClientGameTest-442e/result.json`: graphical visits to dedicated dimensions for all seven nongas terrain types pass. Lava, ocean and toxic screenshots were reviewed. The final integration crash selection now includes the planet restart check.

The user subsequently dropped the exact carved-wall report. The generic full-face regression remains useful, without claiming it reproduces that report. Sources have not entered final integration, and no release has been published.


## Chunk 3 implementation and verification

Successful deployment consumes matching carried titles after the physical move commits. The MOVE journal now includes clearing the stored flag on arrival. The title regression exercises three store/deploy cycles without accumulating carried titles. Generated supply labels no longer create custom container names; stocking preserves an existing player-authored name. New salvage construction shares eight coal among its generators instead of giving every generator a stack. Room fuel rolls are smaller; starters and purchased ships keep their existing provisioning. Battery help distinguishes electrical reserves from propulsion fuel.

The incorrect 26-neighbor hull workaround was removed. The retained generic regression carves a full-face-connected bend and transfers it, while disconnected pieces remain rejected. PT10-7 itself is dropped at the user's request.

- Power, generator-geometry and world-start unit checks pass.
- `build/focused-tests/20261111-145142-HullCarveGameTest-dd97/`: the combined 28-case native run passed 23 cases and demonstrated five failures. Pending construction was used/removed before its shared save, and live BUILD jobs retained a stale store reference after test reloads.
- BUILD steps and commit completion now resolve the current store. Encounter fixtures wait for committed construction and collar saves.
- `build/focused-tests/20261111-145704-JourneyGameTest-bc22/`: 21 of 22 affected cases pass, including repeated title cycles, the carved transfer, batteries and save coordination. The remaining journey case failed while checking its engineering fuel locker.
- `build/focused-tests/20261111-145957-JourneyGameTest-f4df/`: the focused journey rerun failed around boarding after its visitor reached the encounter. Its diagnostic assertions now distinguish access from actual fuel contents. The regression remains open; there is no passing final receipt for it.

Chunk 4 verification, remaining correctness failures, final integration and release remain outstanding. Representative performance measurements are deferred by the user's later decision, not a remaining release blocker. Earlier measurement plans in this historical record are superseded by the current-state decision above.

## Chunk 4 implementation

Code review traced the current flows before another Minecraft launch. The Trip page now receives an explicit state from current journey, docking and course records, rather than inferring state from the last response text. The [FTL state matrix](../design/ftl-journeys.md#controller-states-and-actions) records the copy and actions. Continue journey and optional signals remain on Trip; manual steps remain available.

Engage seals exterior doors while leaving open interior airflow paths alone. Real pressure and crew checks still apply. The scanner releases an unconfirmed selection after its timeout, permitting retry; travel actions retain their pending guard. Vessel snapshots now include supported visible living actors and equipment, using the existing detached entity renderer. Normal healthy cabin air leaves the action bar free; vacuum, low air and recovery show actionable status. The guide follows first-trip prerequisites, orders recipes accordingly and explains both ships after salvage claims.

Journey fixtures that reload the global store now run in separate environments. Boarding waits for journaled collar completion and reports construction/travel reservations explicitly. The encounter flow regression still needs its combined-run result before it can be closed.

Compilation and the focused `ShipControlTerminalTest` / `OxygenStatusTest` checks pass (`build/chunk4-unit.log`). One combined native check is in progress; no new performance run was launched.

The combined native run `build/focused-tests/20261111-190048-JourneyGameTest-fa79/` passed 29 of 30 cases. Only the journey fixture failed: it checked readiness after block placement but before the shared BUILD save. The fixture now waits for that save. The affected `JourneyGameTest` rerun `build/focused-tests/20261111-190222-JourneyGameTest-65ee/result.json` passes all five mod cases, including saved progress, investigation, docking, boarding, actual fuel collection, return, automatic undocking, paid resume and arrival. Unaffected native passes are retained. One combined graphical acceptance pass is running.

The first graphical run stopped when its journey fixture clicked Continue before the collar save completed. That fixture now waits for the completed docking course. Screenshot review also removed repeated trip summaries from the connection row and last-action area. The Trip page explicitly shows collar saving and arrival waiting for safe crew; transition captions distinguish entering/resuming FTL from arrival. In the corrected combined run, scanner, guide and remote-vessel actor checks pass; a skeleton outside native entity tracking is visible through the remote ship's window. The remaining navigation flow has accepted Continue and automatically undocked/resumed; its final receipt is pending.

The corrected combined graphical run `build/focused-tests/20261111-190644-ScannerBlockClientGameTest-1525/result.json` passes all four entrypoints: scanner, guide, scene expansion and navigation. Guide contents/recipe pages, remote enemy visibility and the revised docked Trip page were reviewed from actual screenshots. Automatic undocking, paid resume, orbit arrival, planet landing and launch complete through the real controls. Sources are now frozen for one `verify.ps1 -Graphics -Multiplayer -Crash` integration sweep; performance measurements are excluded.

## Final integration continuation

The frozen native sweep `build/verification/20261111-191029-5d626f/baseline.log` completed all 270 cases with nine failures across five older fixture classes. Synchronous delivery/collar fixtures used builds before their shared save; the population fixture reloaded an uncommitted build; the showroom fixture compared an old record object rather than its saved identity. These were corrected in test code only. Production code and tooling hashes remain unchanged from the broad run.

The affected five-class check `build/focused-tests/20261111-191748-ManufacturerTemplateGameTest-3fbf/result.json` passes all 19 cases. Unaffected native passes are retained. The integration continues through the previously unrun checks, rather than repeating the entire sweep. All 610 unit cases, asset validation and packaging pass (`unit-assets-build.log` beside the original manifest). Full graphical, multiplayer and crash/restart checks remain in progress. The original failed manifest is retained honestly; final receipts will reference the continuation.

The user requires complete failure collection before another fix batch. The graphical framework stops at the first failed class, so the continuation now runs each remaining graphical class in an isolated process, with three workers and a combined outcome list. Completed checks are retained, failed checks do not stop pending ones, and no fixes are made until collection finishes. The performance grid demonstration is deferred along with other performance work. Collection state is `build/verification/20261111-191029-5d626f/collection/result.json`; multiplayer follows the graphical pool, and the existing MOVE/BUILD crash collection continues separately. Two graphical failures are currently recorded: the station expansion fixture and the station scanner paging assertion. Neither has been changed during this collection.

Complete collection finished with 24 of 31 selected graphical checks passing and seven failing; multiplayer also failed. All 16 MOVE and ten BUILD crash/restart checkpoints pass, as does independent planet persistence. The seven graphical failures and multiplayer reduce to four causes: presentation labels changed the raw controller status value, the lifecycle test read the retired store filename, the station test exercised the removed duplicate Scanner browser, and station expansion acted before construction's shared save.

One combined patch addresses the complete list. The action result remains raw in the controller protocol/API, while Last action is added only when painting the screen. The existing strict status assertions remain intact. Lifecycle verification loads the newest store slot. The station flow uses the real consolidated Scanner, and station expansion waits for committed construction. The added terminal regression protects the distinction between status data and presentation. Compilation and all 611 unit tests pass (`fix-batch-compile-unit.log`); one isolated batch is checking every affected graphical class and multiplayer. Unaffected physical/native, crash, asset and graphical passes are retained.

The completed affected batch `collection-fixed-20261111-195318/result.json` passes five affected graphical checks and multiplayer. It exposes two fixture errors later in the remaining flows: reopening an already-open Scanner and using an old numeric index after the recipe list was reordered. Both were corrected together. `collection-fixed-20261111-200226/result.json` passes the station and recipe flows; the other passes were retained.

Final local acceptance is complete: **611 unit cases, 270 native cases covered by the broad run and affected corrections, 31 selected graphical entrypoints, multiplayer, all 16 MOVE and ten BUILD crash/restart checkpoints, independent planet restart, asset validation and packaging**. `final-result.json` links the contributing receipts and preserves the original failures. The performance grid demonstration and further measurements remain deferred.

On November 12, [release CI](https://github.com/NullandKale/NullSpace-Mod/actions/runs/43263207272) passed for `2cdd4b0` and published `v0.5.2_rc1`. The downloaded `nullspace-0.5.2_rc1.jar` matches the published SHA-256, `55231ad6de84e6b4d9a9936962f877bc48df62716c4132f7999b95fb323e433a`, and is installed in the testing profile. No new local sweep or performance measurement was needed for delivery.

## November 12 follow-up integration

The frozen batch `build/verification/remaining-final-20261112-140609/result.json` collected the full native inventory, all 32 selected graphical entrypoints, multiplayer, sixteen MOVE and ten BUILD crash/restart checkpoints. All crash checkpoints pass. Twenty-nine graphical entrypoints pass, including the approved map, claim handoff, generated-ship journey and the raised-pad stair walk. The actual terrain screenshot was reviewed: stairs reach ground and the raised platform has a filled foundation.

The batch exposed seventeen native failures in ten classes, three graphical failures and a multiplayer cargo lookup failure. Old coordinate assertions assumed translation-only arrivals. Several rectangular recall fixtures placed their computer on the west edge while only fitting a north-facing hull. Two failure-injection fixtures also acted before passenger data finished loading. The correction batch keeps translation-only invariant fixtures aligned, checks rotated cargo relative to the moved controller, and waits for loaded passenger data before injecting failures. The four-edge and rectangular arrival tests retain their rotation and refusal assertions. Production sources are unchanged by these fixture corrections.

The affected batch `build/verification/remaining-fixed-20261112-144123/result.json` passes all three graphical reruns and multiplayer, and 56 of 57 native cases. The final failure put the abandoned-ship visitor inside the hull after moving its fixture computer to the north edge. The visitor now stands outside; the focused title class passes. The combined receipt `build/verification/remaining-final-20261112-140609/final-result.json` passes all 625 unit cases, 276 native mod cases, 32 graphical entrypoints, multiplayer and every MOVE/BUILD crash checkpoint. Unaffected results are retained; performance measurements remain deferred. [Release CI](https://github.com/NullandKale/NullSpace-Mod/actions/runs/43324931805) passed and published [v0.5.2_rc2](https://github.com/NullandKale/NullSpace-Mod/releases/tag/v0.5.2_rc2). The [rc2 changelog and manual checklist](../release/0.5.2-rc2-playtest.md) records the delivered behavior and the checks for a real playthrough.


The first rc2 CI attempt passed all 277 required native cases but stopped on formatting in two test helpers. Whitespace-only corrections pass `spotlessCheck`; no behavior changed and the local acceptance results remain applicable. [The successful release run](https://github.com/NullandKale/NullSpace-Mod/actions/runs/43324931805) built `bdc044a` and published `v0.5.2_rc2`. The downloaded asset matches SHA-256 `c020cf3fb2a53dd9256198a2f8a2c7ec77fe31f54aee40a206413f766ffe58ad` and is installed in the Modrinth testing profile. No further performance measurement was run.
