# Journal completion work

The November 10 continuation uses the **original** scope of [journals and save recovery](../design/journals-and-save-recovery.md) and [performance test](performance-test.md). The user explicitly selected production BUILD/RECORD journals and recovery `restore`, despite the previous implementation notes deferring them. The November 11 request adds all eight playtest bug fixes. The feature freeze defers the three new features; reports and focused evidence are tracked in [the playtest record](../history/playtest-2026-11-10.md).

## Remaining slices

- [x] Contain unreadable/unsupported journals during store startup, before transfer recovery runs.
- [x] Production RECORD operations and complete replay of saved metadata after a damaged newest copy.
- [x] Production BUILD operations, with crash-safe record capture, locks, and no competing intent replay.
- [x] Operator restore to a clear pad or space slot, journaled and with entity/player recovery.
- [ ] Performance fixture and BUILD/startup scenarios, elapsed-time throughput and completed-operation accounting, comparison runner and retained evidence.
- [x] Real crash/restart coverage for BUILD and damaged newest store copies.
- [ ] One final integration sweep with frozen sources; update the two design documents to match delivered behavior and evidence.
- [ ] Publish the verified candidate through GitHub release CI/CD and use that release asset in the Modrinth test pack, checking the JAR hash.

## Verification record

Prior-agent evidence: 596 unit cases pass; broader native run 148/149 followed by a focused 31/31 including the failed transfer case. No final receipt for those focused runs. The last full integration manifest predates the journal implementation. The `after` performance run is incomplete. None of these certifies the completed batch.

Do focused tests while implementing each slice. Record new passes and blockers here before the final integration sweep. The playtest queue was explicitly added before final verification; do not expand that scope during the sweep.

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

PT10-1 through PT10-8 are implemented with focused native, unit and graphical checks. The playtest record lists their behavior and receipts. Corner-connected hulls now move while actual air gaps remain rejected; door occupants use adjoining room air without opening the seal. Existing catalogs missing non-Sol landing assignments repair in place. No world reset is needed for that migration case.

`build/focused-tests/20261111-075442-ScannerBlockClientGameTest-24af/result.json` passes both graphical entrypoints. Screenshots were reviewed for the consolidated scanner and physical distant monitor. The final 80-tick distant-monitor wait and explicit distance assertion pass in `build/focused-tests/20261111-082632-NavigationClientGameTest-674a/result.json`. Its journey screenshots were also reviewed. `build/focused-tests/20261111-083501-NavigationClientGameTest-2ec6/result.json` also passes actual encounter capture and automatic departure through Continue journey. The fixture now has a real docking base and waits for the full approach and departure distances.

The first full-duration 100-ship BUILD attempt stopped during setup, before measurement: generated planet sand intruded into a fixed-elevation fixture pad. Fixture construction now loads and clears each disposable pad's full launch column before placing a ship. These failed setups supply no performance result. A subsequent setup exposed ordinary passenger-data loading before crew placement; setup now keeps the mover chunks loaded and retries that state before measurement. The corrected run is pending.

The corrected full-duration 100-ship BUILD run `build/performance/20261111-083740-build-100-berths-ready/result.json` passes with zero refusals at every rate. Raw results and the root receipt are retained under `docs/performance/journal-build-100-baseline*.json`. The first repetition reaches 1,914 ms at 0.25 operations/s and 2,682 ms at 0.5/s, with a flat-out ceiling of 0.562 operations/s. The requested pause-budget/save-policy decision is pending; no final integration sweep has started. The all-scenario smoke receipt and 300-ship idle baseline are being collected independently.
