# Test audit (November 14)

Goal: decide for every test whether the project needs it, and find the gaps that matter. No target of 100 percent coverage.

## What was measured

| Evidence | How | Covers |
| --- | --- | --- |
| Registration and assertions | Static pass over every test class: registered in [`fabric.mod.json`](../../src/gametest/resources/fabric.mod.json), has an assertion, disabled or commented out | All 220 test files |
| Per-test coverage | [`CoverageByTestListener`](../../src/test/java/com/nullandkale/nullspace/testing/CoverageByTestListener.java) writes the JaCoCo data of every unit test to its own file (`NULLSPACE_COVERAGE_DIR` plus `-Pcoverage=true`); each test's probes were compared with every other test's | 83 unit classes, 687 executions |
| Whole-suite coverage | One `parallel-test.ps1 -Graphics -GraphicsWorkers 2 -ServerWorkers 4 -Coverage` sweep, merged with `combinedCoverageReport` | 687 unit, 310 native, 36 graphical: all passed; 84.6 percent of main lines |
| Mutation sample | A small harness changed one operator at a time in nine pure classes and ran that class's tests (41 mutants). PIT would give a fuller picture, but running its downloaded jars was refused | 9 classes |

The listener resets the agent after each test, so a `test.exec` written during such a run is empty. Do not use that run for a normal coverage report.

## Verdicts

**Unit tests (83 classes, 578 methods): keep all.** Every class reaches main code that no other unit class reaches. 108 of the 687 executions reach nothing the others miss, and almost all of those are parameterized cases (one per seed in `FlightInvariantTest`, per packet in `TerminalTransportTest`, per missing ring block in `PadMutationTest`, per scene field in `SceneContractTest`). They are boundary checks, not duplicates, and were kept.

**Mutation sample: 28 of 41 changes caught (68 percent).** The 13 survivors:

| Class | Survivor | Verdict |
| --- | --- | --- |
| `DerelictLoot` | the hoard room takes the treasure (`spot == hoard`) | **Real gap, fixed:** `theHoardRoomTakesTheTreasure...` in `DerelictTiersTest`; the same change is now caught |
| `DerelictLoot` | two hash-mixing multiplications | Equivalent for any test: a hash only needs to scatter |
| `VirtualFiles` | a leading NUL or backslash in a path | Equivalent: the check is redundant with the character whitelist after it. Boundary cases added anyway |
| `LocalSpaces` | the journey-region branch of `get` | Not a gap in the suite: it needs a server, and the journey GameTests cover it. The unit class cannot |
| `PowerBus`, `SpatialAir`, `JourneyPlan` | one `<` against `<=` at a boundary each | Boundary values untested. Low value; left |
| `TerminalBuffer` (2), `FlightDynamics` (2) | an index offset, an RGB bounds check, and two tuning constants | The flight tests are invariant tests (finite, bounded, conserved) by design and do not pin tuning constants. The terminal ones are worth a boundary case if that parser changes again |

**Native GameTests (307 methods in 83 classes) and graphical tests (36): not proven one by one.** The evidence is that all pass, that together with the unit tests they cover 84.6 percent of main lines, and the assertion pass. Per-class coverage for them would need one Minecraft launch per class. By role:

- **Behaviour checks (the great majority): keep.** They exercise real registries, saves, journals and networking that no unit test can.
- **Review tools: keep, but they are not regression tests.** Screenshot galleries with no assertions of their own: [`StationGalleryClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/shipyard/StationGalleryClientGameTest.java), [`DerelictGalleryClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/shipyard/DerelictGalleryClientGameTest.java), [`CreativeScannerClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/CreativeScannerClientGameTest.java), `ManufacturerClientGameTest`, `ManufacturerRoleClientGameTest`, `DerelictThreatsClientGameTest`; the two survey tools [`LootSurveyGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/encounters/LootSurveyGameTest.java) and [`LiveStationSurveyGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/encounters/LiveStationSurveyGameTest.java) (empty passes unless `nullspace.survey.sites` is set); and the performance tests, which are deferred. They fail only when something crashes or a wait times out. Splitting them into an opt-in tier would shorten the sweep; not done.
- **Assertions made by a helper, not by the method:** the 19 methods with no direct assertion are one-line delegates to helpers that assert (`EntityTravelGameTest.finish`, `JourneyGameTest.journeyFlow`, the landing-rotation edge cases). Fine.

## Changes made

- **Deleted** `ShipGeneratorTest.dumpForReview` and its `dump` helper: a floor-plan dump tool disguised as a test, which did nothing unless an environment variable was set and asserted nothing.
- **Fixed** a defect I introduced earlier: the two survey GameTests were unregistered, which made the sweep's inventory check refuse to run. They are registered and empty unless a survey is asked for; the new Scanner screenshot test is registered too.
- **Added** the hoard-room test and leading-character path cases.
- **Kept** the "never throws" regressions in `CompiledExpressionTest` and `ScriptLanguageRegressionTest`: their assertion is that no host `Error` escapes, which is what the bug was.

## Gaps that matter

Whole-suite coverage is 84.6 percent of main lines. The weak spots, by missed lines:

| Class | Covered | Why it matters |
| --- | --- | --- |
| `ControllerTerminal` | 46 percent | The terminal's input handling and action routing; most paths are only reached by a person |
| `StationService`, `DockingService`, `ShipService` | 54 to 55 percent | Station, docking and ship actions: refusals and edge cases rather than the main flows |
| `GeneratedStations` | 16 percent | `/nullspace station orbital` listing and generation commands |
| `PadAllocation` | 19 percent | Pad sizing; the unit test covers only part of it |
| `VirtualFiles` | 49 percent | The guest file system's directory commands (`mkdir`, `list`) |
| `gameplay.combat` | 60 percent | Weapons: two native tests |
| `core.mixin` | 0 percent | JaCoCo cannot see mixins; they run in every launch |

**Code that nothing in the whole suite runs:** `Shapes.dome` (45 lines: no catalog ship uses a dome shape), `Furnishings.hub` (28 lines: no design uses a hub room), `TransferRecovery.handleLegacy` (a legacy migration), `SpaceBvh.intersect` and `box`, `StationLayout.loops`, `RoomsLiving.pen`, `StationFurnishings.stock`, `ShipSensors.contacts` (the computer's `contacts` command), `JournalCommands.retry`, `StationService.showProblem`. Four of these have no caller at all and look dead (`SpaceBvh.box`, `StationLayout.loops`, `RoomsLiving.pen`, `Furnishings.hub` is reachable only through a use no design has); the rest are reachable and untested. Deleting dead code was out of this audit's scope.

## Not proven

- Each native and graphical test's own value, as above.
- Mutation results beyond nine classes and 41 changes: a sample, not a score.
- That equivalent mutants are all equivalent; each one above was read, not proven.

## Feature-level view

The class-level audit above says whether each test earns its place. This section asks the other question: for each thing a player does, is there a test that does it the way a player does, from the action to the visible result?

| Feature (player journey) | Scenario test that does it end to end | Verdict |
| --- | --- | --- |
| Start a world, pick a ship | [`SpaceStartClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/start/SpaceStartClientGameTest.java): Create New World, ship picker, login, saved settings | Covered |
| Fuel, register, launch, return | [`FirstTripClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/ship/FirstTripClientGameTest.java) through the real GUI, with two real preflight failures; `crash-test.ps1` halts the JVM at the launch and landing boundaries | Covered |
| Choose a destination, Engage, dock, return | [`CourseClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/CourseClientGameTest.java) (real Engage buttons, berth, undock, landing); [`ScannerBlockClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/ScannerBlockClientGameTest.java) | Covered |
| Journey, signal, visit, resume | [`JourneyGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/JourneyGameTest.java) (server-side, real queue and handshake); no client test clicks Visit signal | Covered on the server; not through the UI |
| Die, respawn, recall a ship | [`ShipStorageClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/ownership/ShipStorageClientGameTest.java) | Covered |
| Store a ship, hand it over with a title | [`ShipTitleGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/ownership/ShipTitleGameTest.java), `ShipStorageGameTest`; no client test | Covered on the server |
| Two players aboard one ship | `multiplayer-test.ps1`: pilot and crew clients on a loopback server | Covered |
| Station: dock, board, walk, return | [`StationClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/station/StationClientGameTest.java) | Covered |
| Derelict: from the dock, reach loot | [`DerelictScenarioGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/condition/DerelictScenarioGameTest.java): a walker from the dock cell, over 36 vessels at every danger (and calibrated on each whole vessel first), reaches a loot chest, breaking only jammed doors | Covered (the loot is not opened) |
| Derelict: torn rooms vent, whole rooms keep their air | Same class: a breach wreck of each kind is placed in space and the game's own `LivePressure` checks every whole room keeps all its air and some damaged room has none | Covered |
| Loot budget | [`LootBudgetGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/encounters/LootBudgetGameTest.java): 72 vessels at four dangers, every chest rolled for real: one chest per room, no top-end item in a room chest, at most one per vessel, none below danger 3 but the basic upgrade, four or five high rooms at danger 3, and the advanced upgrade and totem shares | Covered |
| Creative Scanner: choose settings, click Start FTL, arrive | [`CreativeScannerClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/CreativeScannerClientGameTest.java) cycles the real buttons, types a seed, presses Start FTL, and waits for the ship to reach a signal made with exactly those settings | Covered |
| Build a station | `StationExpansionClientGameTest`; the design is not settled | Deferred |

The core loop and the new features are now covered end to end. The remaining server-only rows (journeys, titles) have no client click-through.

### Scenario tests built (November 14)

All four proposed scenarios were built. Each was run, and two were proved able to fail:

1. **A wreck is playable.** Its first run failed on the walker itself (it asked for three blocks of headroom, so it could not walk through a door); the whole-vessel calibration caught that. Its next failure was a real wreck whose route was sealed by a jammed door, which the design allows, so the walker may now break doors and nothing else. It passes for all 36 vessels at four dangers.
2. **Wrecks keep their bubbles.** Passes. Proved by mutation: letting a breach cut a wall the room shares with a neighbour makes it fail.
3. **The loot budget as assertions.** Passes. Proved by mutation: letting every room be high tier makes it fail.
4. **The creative builder by click.** Passes: the server log shows `Starting FTL to a salvage ship at danger 3, breach` from the buttons, and the ship reached a signal with seed 777.

Not done: opening the chest in a wreck at the end of a real docking walk in a graphical test.
