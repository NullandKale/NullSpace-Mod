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
