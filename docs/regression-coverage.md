# Regression coverage

This pass expands tests around observable behavior and failure boundaries. Seeded simulations are repeatable; they check conservation, bounded work and independent coordinate frames. Native GameTests use Minecraft's real blocks, registries, serializers and server services. Graphical tests use the actual OpenGL client. Local multiplayer uses two separate client JVMs and a loopback dedicated server.

The November 5 docking hotfix passes **534 unit scenarios**, **188 mod-native scenarios**, four worker smoke tests, all **25 graphical entrypoints**, both loopback client roles and a standalone package boot/save. Combined execution covers **88.20% of main-source lines** and **75.68% of branches**; client-only sources are outside that denominator. [Current receipt](playtest-hotfix-verification.json).

The earlier November 4 baseline separately passes all **14 travel** and **seven shipyard** physical crash/restart cases. Those matrices were not repeated after the docking hotfix. Current receipts and limitations are in [release-readiness.md](release-readiness.md). Older counts below describe their dated verification passes.

## Added coverage

| Area | Added checks |
| --- | --- |
| Virtual computers | Guest path rejection; full disk and file-count limits; failed move preserves its source; directories and working directory survive restore; detached sleep deadlines; foreground interruption preserves background jobs; editor cancel/save; zero CPU budget; repeated restore replaces jobs; invalid restore preserves the working disk; malformed saved version, PC, syntax and variables |
| Spatial air | Repeated bulkhead opening/closing along all six directions conserves mass; equal vacuum escape on each grid face; zero-time and solid-source behavior; overpressure retention; disconnected pump capacities; invalid source bounds |
| Shared power | Grants capped by actual hardware and requested allocation; duplicate subsystem rejection; invalid supplies and overflow rejection; existing randomized brownout/charge conservation remains enabled |
| Terminals | ANSI output split across arbitrary packet boundaries; late-viewer snapshots preserve all 16 colors including inverted defaults; malformed escape floods recover; reset clears parser/style/history; every cell of an RGB screen survives the actual network codec |
| Flight | Long seeded flights at every power level remain finite and speed-limited; integration and pose round trips agree; brakes dissipate motion at eight orientations; opposing controls cancel; non-finite ship controller angles reject |
| Landing pads | Rectangles from 1 x 1 through 32 x 32 at positive and negative chunk boundaries; each possible missing ring block; unloaded floor/ring handling; overlap symmetry and vertical independence |
| Space scene data | Full motion/volume/asteroid-field serialization; stale revision rejection; invalid updates preserve the current scene; new regions have independent revisions; accepted input tags are copied; non-finite parameters, malformed contributors and identity collisions reject |
| Network protocol | Every terminal action; maximum legal destination catalog and text sizes; forged action ordinals; invalid catalog lengths; oversized keyboard input; exact packet consumption |
| Server authorization | Cross-player use and close rejection; pad controllers cannot invoke ship controller, weapon or engineering actions; invalid arguments leave legitimate sessions usable; block replacement, moving away, closing and dimension changes revoke access |
| Graphical recipes | All 23 snapshots match every loaded server ingredient alternative and output; displayed grids really craft; persistent and inventory serialization; hand reader, ordinary-book isolation, ingredient click navigation, lectern rendering and batch counts |
| Journal replay | Replaying a full ship in place preserves exact device IDs, battery energy, allocations and stored air; rebuilding runtime caches preserves that state; real destruction still removes device records |
| Real multiplayer | Both clients receive graphical recipes from the dedicated server and navigate ingredient links. The existing scenario also verifies shared power revisions, independent viewers, camera captures, keyboard-driven guest files, launch, piloting, jumps, offline crew return and reconnect |

## Defects exposed and fixed

- Restoring a computer repeatedly appended duplicate jobs. A validated replacement now replaces the process table.
- A rejected computer image could partially replace the virtual disk. Restore now validates a separate candidate before committing live state.
- Terminal snapshots mapped an inverted default foreground/background to the wrong color. Snapshots now preserve RGB colors, and the parser accepts the corresponding ANSI RGB sequences.
- A densely colored terminal could exceed the old 16,384-character screen packet. Screen snapshots have a bounded 131,072-character allowance; keyboard input retains its separate 2,048-character limit.
- Crash recovery cleared the physical source before replay and treated that temporary clearing as equipment destruction, deleting saved device IDs and battery energy. Journal mutation now preserves authoritative device records; ordinary destruction still invalidates them.
- NaN/infinite ship controller yaw could enter flight calculations. Such input now rejects before updating the simulation.

## Running the checks

```powershell
# Unit tests, native server tests, graphical client tests and package:
.\test.ps1 runClientGameTest assemble --no-daemon --console=plain

# Two actual clients and a loopback dedicated server:
.\multiplayer-test.ps1

# Real JVM halt/restart at seven boundaries in both launch and landing directions:
.\crash-test.ps1
```

The local JDK wrapper supplies Java 25. Use `./parallel-test.ps1 -Graphics -GraphicsWorkers 2 -ServerWorkers 4 -Coverage` to compile once, run isolated Minecraft JVMs concurrently, and merge their coverage with the unit tests. Each worker owns its world, logs and coverage file. Do not edit sources while a verification run is active; the harness checks its input fingerprint. Separate complete harness invocations should run sequentially. Before another Gradle invocation, copy graphical evidence out of `build/run/clientGameTest`: the next test configuration recreates that directory. Reports live in `build/reports/tests/test`, `build/test-results/test` and the individual native test logs. All worlds used by these harnesses are disposable; player saves are untouched.

## Limits

Test counts describe scenarios, not a percentage of code coverage. These checks do not prove the absence of bugs. GPU results apply to the local driver/device; alternative GPUs, resource packs and arbitrary data packs need their own runs. The separate physical process-halt crash matrix remains available through `crash-test.ps1`. Docking, boarding, finite fuel/power, journeys, procedural systems, encounter resources and world starts now have native coverage; graphical and two-client checks exercise their actual interfaces. A scenario inventory still does not prove every branch, hostile-client behavior or public-server operation is covered.


## Previous coverage-pass result

All **280 unit cases, 73 required server tests, 12 graphical entrypoints, the two-client loopback scenario and seven physical crash/restart stages** pass. The initial process-crash failure was investigated and fixed, then the entire crash matrix and multiplayer scenario were rerun successfully. [Logs and artifact details](regression-verification.json) and the [verification record](local-verification.md#graphical-crafting-and-regression-expansion---october-26-2026) preserve the result.

## Repository-refactor regressions

The October 26 refactor adds real compressed-NBT round trips for maximum legal computer disks and eight detached jobs; old string-image decoding; corrupt/future image rejection; strict-write failure preserving the previous file; atomic repository edits; cached catalog I/O; a globally bounded 128-pad inspection queue; warm runtime replacement; and bounded ship block-entity snapshots. The dense-sign case proves the producer never emits a visual list rejected by its own decoder.

Pure tests cover one-instruction fairness, sleeping jobs, compiled-expression reuse, short-circuit behavior, nullable guest lists, compiler ABI compatibility, image reuse/invalidation, resource-group cleanup and catalog completeness. Graphical tests inject failures after each of 23 allocations, repeat close, reload resources and assert moving populated views stop allocating transformed quads after warm-up. Each graphical entrypoint writes a completion receipt. The seven original crash stages carry a large disk and a sleeping detached job through fresh-JVM recovery. The later test review discovered that the fixtures labeled Unicode actually contained question marks; the follow-up corrects those fixtures and validates real multibyte text.

Current ownership and remaining optimization boundaries are in [architecture.md](architecture.md). The implementation receipt is [repository-refactor-verification.json](repository-refactor-verification.json); older verification files retain their historical counts.

## Test-review follow-up

[Changes, regression checks and remaining limits](test-gap-review.md) document the expanded persistence, recovery, scheduling, pad, resource/datapack and lifecycle coverage. The new native workload exposed device-call starvation caused by synchronized retry delays; budget yields now retry on the next tick.

The final follow-up matrix passed all declared scenarios, both real clients and all 14 launch/landing crash cases. [Current verification receipt](test-gap-verification.json) records the frozen input fingerprint and packaged artifact.


## Code-review pass: added tests and known-bug register

Run on 2026-10-26 against the working tree (no commits yet). `gradlew test runGameTest --offline` passed: **462 unit tests (0 failures, 12 skipped) and all 100 required server tests**. The skipped tests are the known-bug tests below.

### Added

| Class | Tier | Covers |
| --- | --- | --- |
| `ScriptLanguageRegressionTest` | unit | Python/bash compilation, block and size limits, error line numbers, sleep clamping, unavailable-device retry, budget enforcement, `expand`/`words`/`checkValue` |
| `CompiledExpressionTest` | unit | Precedence, floor division/modulo, string coercion, lists, calls, overflow, nesting and token limits, malformed input, short-circuiting |
| `VirtualFilesTest` | unit | Path normalization and traversal, writable roots, size limits, revisions, `mkdir`/`remove` rules, listing, `replaceWith`/`clear` |
| `VirtualShellTest` | unit | Every shell command, pipes and redirects, quoting, variable cap, process table, Ctrl-C, image validation and round trips |
| `ComputerImageCodecTest` | unit | Checksum, legacy strings, invalid UTF-8/Unicode, byte budget both ways, compressed-NBT round trip |
| `ShipRepositoryTest` | unit | Edit semantics, field-level change events, no-op edits, identity protection, replace boundaries, bounds, targets |
| `ShipStoreDurabilityTest` | unit | Write/read round trip, no leftover temp file, previous snapshot survives a failed or mid-stream write, garbage input, `AtomicFiles.promote` |
| `LocalSpacesTest` | unit | Destination lookup, uniqueness, defaults, labels |
| `ComputerServiceBoundaryGameTest` | server | 256-computer limit, 8192-characters-per-second input budget, range, forged and cross-player sessions |

### Known-bug register

Each is a test that states the correct behavior. Unit tests carry `@Disabled("KNOWN BUG/QUIRK: ...")`; remove the line with the fix. The server test is unregistered (no `@GameTest`) so `runGameTest` stays green. Each was run once enabled and failed as described.

| Test | Defect | Location |
| --- | --- | --- |
| `ScriptLanguageRegressionTest.elseAfterANestedIfBelongsToTheOuterIf`, `elseAfterANestedLoopBelongsToTheOuterIf`, `innerAndOuterElseBranchesBothResolve` | In Python mode an `else:` after a nested block is rejected with "Unexpected else" because the `else` guard stops inner blocks closing | `ScriptLanguage.compile`, dedent loop |
| `longStringLiteralsNeverThrowAnError`, `longStringLiteralsInExpressionsNeverThrowAnError` | Quoted strings of about 4,000 characters or more throw `StackOverflowError`, which no guest-error handler catches and which is expected to reach the server loop | `CompiledExpression.Parser.TOKEN` |
| `VirtualShellTest.movingAFileOntoItselfKeepsIt` | `mv a a` writes then removes the same path, deleting the file | `VirtualComputer.command` (`cp`/`mv`) |
| `headWithAnUnsupportedFlagExplainsUsage` | `head -n 2` reports "For input string" | `VirtualComputer.command` (`head`) |
| `imagesMissingRequiredFieldsAreRejectedAsInvalid` | A missing JSON field gives a message-less `NullPointerException` | `VirtualComputer.restoreImage` |
| `CompiledExpressionTest.notAppliesToTheWholeComparison` | `not 1 == 2` parses as `(not 1) == 2`, unlike Python | `CompiledExpression.Parser.atom` |
| `VirtualFilesTest.theDefaultWorkingDirectoryCannotBeRemoved` | Empty `/home/crew` can be removed, leaving the shell in a missing directory | `VirtualFiles.remove` |
| `listingAMissingPathIsAnError` | `ls` of a nonexistent path prints the path instead of failing | `VirtualFiles.list` |
| `mkdirHonoursTheSameWritableRootsAsWrite` | `mkdir /proc/x` succeeds although nothing can be written there | `VirtualFiles.mkdir` |
| `ComputerServiceBoundaryGameTest.brokenTerminalsReleaseTheirComputerSlots` | Machines are never removed from the registry, so after 256 distinct terminals every new terminal is refused for the life of the server | `ComputerService` (`open`, `machines`) |

### Recommended further tests

Ordered by value per effort. "Unit" needs no Minecraft world; "server" is a GameTest; "client" needs the graphical harness.

#### 1. Fuzz and property tests (unit, cheap, highest value)

The long-string crash shows that example-based tests miss host-level failures. Use seeded `java.util.Random` so failures reproduce.

- **Compiler never throws anything but `IllegalArgumentException`.** Generate a few thousand random sources from a grammar-biased alphabet (quotes, escapes, brackets, indentation, keywords, long runs) for both syntaxes and assert `compile` either returns or throws `IllegalArgumentException`. Include literals of 1, 100, 1,000 and 16,000 characters.
- **`Job.step` never throws.** Run each compilable random program for a bounded number of ticks with a host that randomly throws `Unavailable` and `IllegalArgumentException` and returns odd values. Assert no exception escapes and the budget is never exceeded.
- **Shell never throws a non-runtime error.** Random command lines through `VirtualComputer.shell`, asserting only `IllegalArgumentException` and that disk invariants hold (file count, bytes, paths under `/home` or `/etc`).
- **Mutated images.** Take valid saved images, flip or delete random JSON fields and values, and assert `restore` either succeeds or throws and leaves the live disk byte-identical.
- **Interpreter versus reference.** Evaluate random arithmetic and comparison expressions in `CompiledExpression` against a small independent evaluator.
- **`words` and `expand`.** Quoting then splitting random strings returns the original; expansion output length never exceeds 32,768.
- **Terminal buffer.** Random byte streams through `TerminalBuffer` keep the cursor in range and the history bounded (the existing test covers only malformed parameters).

#### 2. Computer service (server tier)

Needs a powered ship fixture; reuse `ShipTestFixture.build` as in `manyComputersShareGlobalBudgetsWithoutStarvation`.

- **`ship.camera(n)` persists.** Select a channel from a script, save, reload, and assert the monitor channel survives. The current code edits the ship tag without `requestSave()` or `mutate(...)`. Also assert `ship.camera()` with no argument gives a readable error.
- **Owner and actor semantics (needs a product decision first).** Today `owner` is stored but never enforced, any nearby player can open another's computer, and `launch`/`land`/`jump` act as the first nearby player. Write the tests for whichever policy is chosen: non-owner cannot run or edit; the acting player for travel is the session's player; detached scripts stop or pause when the owner is offline.
- **Device budgets.** 32 calls per machine per tick, 64 per server per tick, 2 `inspect` per tick; exhaustion yields a one-tick retry, never a failure.
- **Session expiry.** A session with no input or keepalive for more than 100 ticks closes and sends the closing screen; the client's once-per-second NUL keepalive keeps it open.
- **Removed terminal.** Breaking the block powers the machine off and keeps its disk; a replacement terminal at the same position reconnects to it.
- **`adopt` and `rebase`.** A standalone computer inside a newly registered ship migrates to ship-relative coordinates with its disk intact; `rebase` after a ground-origin change keeps scripts running at the same physical terminal.
- **Duplicate ids.** Two saved records with the same `id` must not silently overwrite each other in the registry.
- **`ship.thrust` bounds.** Huge, negative, NaN and fractional tick counts; unknown flight words; empty argument.
- **Scheduler cost.** Opt-in benchmark (like `benchmarkSystems`) with 256 idle machines, asserting per-tick time and that idle machines do not run flood fills each tick. Add a second case where a detached script changes its image every tick and measure `ships.nbt` rewrite cost over 60 seconds.

#### 3. Persistence (unit and server)

- **`ShipStore.saveIfRequested` cadence.** Saves only when `tick % 20 == 0` and a save was requested; a failed save leaves `requested` set so it retries; an unchanged store skips the write.
- **Constructor.** Unsupported `version` throws; a corrupt `ships.nbt` fails loudly instead of starting an empty world.
- **`ShipStore.at`.** Overlapping bounds choose the ship with the highest base Y; dimension filtering; positions on the boundary.
- **`ShipStore.bounds`.** Width and depth are not clamped while height is; decide whether that is intended and pin it.
- **Rollback in the `NavigationService` jump commit.** Use `ShipStore.writeObserver` to fail the save mid-jump and assert the ship record returns to its prior value and the jump is cancelled with a message.
- **`ShipRepository` listeners.** A listener that calls `mutate` during `onChange`; listener exceptions do not leave a half-applied edit.

#### 4. Terminal and protocol (unit and server)

- **Catalog-to-action mapping.** Every `EquipmentMenus` verb in the catalog maps to a real `Action` (`Action.valueOf` throws otherwise). Pure unit test over `equipment.json`.
- **`ComputerTerminal.authorize` matrix.** For each mode (pad, ship, systems, navigation, each equipment mode) and each `Action`, assert exactly the allowed set passes; argument regex and 32-character cap; `FAST_TRAVEL` and `LAND` must come from the session catalog; revision mismatch on edits; two-tick action spacing; session lifetime renewal.
- **`ComputerClient` queue.** The 32,768-character cap, 256-character batching, session-id filtering when a new screen opens mid-queue, and clearing on close.
- **`SceneService` keepalive.** Selection bounds of plus or minus 10,000, wrong lease id ignored, close removes the lease, expiry at 100 ticks; `/nullspace viewport` and `/nullspace scene pose` require game-master permission and reject non-finite poses.
- **`cycleMonitor`** wraps at 10,000 and persists.

#### 5. Ship geometry and systems (server tier)

These are the largest untested files by size.

- **`CabinPaths`.** Build small cabins: flat floor, half-slab step, full-block rise needing jump headroom, stairs between decks, ladders, closed and open doors, floor and roof trapdoor hatches, low ceiling, an unbreathable cell on the route. Assert `reaches` and `reachesDoor` for each, including that swimming is not a route.
- **`ShipScan` and `ShipBlockSupport`.** Portable and non-portable block entities (`PORTABLE_ENTITIES` against the registry), bounds clipping, pressure sealing with each door state, a hull with a one-block gap.
- **`ShipTopology`, `ShipDoors`, `DoorSystem`, `AirSystem`, `PowerSystem`.** Resolution of overlapping or adjacent ships, door sealing with trapdoors, and the pure parts of the systems classes where a world is not required.
- **`ShipSensors`, `ShipCombat`, `SpaceSurvival`.** Target selection range and ordering, weapon cooldown and power gating, oxygen and decompression damage timing.
- **`ShipService` and `TravelService`.** Preflight rejection reasons, readiness timeout at 200 ticks, pilot disconnect cancels, crew that never arrives is disconnected with the documented message, `locked` and `busy` interplay.

#### 6. Pads and labs (server tier)

- `PadRegistry`, `PadRepository`, `PadStatusService`, `PadRefreshService`: duplicate pad names, case-insensitive lookup, removal of a broken pad, the 128-pad inspection queue under load, restart persistence.
- `PreviewService`: lifetime expiry and one active preview per player.
- `ShipGallery`, `ShipLab`, `SphereLab`, `SystemsLab`, `RecipeLab`, `PlaytestWorld`: smoke tests that building each lab is idempotent and never touches player data.

#### 7. Mixins (server tier)

`TransitBlocksMixin`, `TransitBlockEntityMixin`, `TransitPlayerMixin`, `TransitDamageMixin`, `ShipExplosionMixin`, `CreeperStateMixin`, `SceneLightMixin`: one test each that the mixin changes behavior only while a ship is in transit or in a scene, and is inert otherwise (vanilla damage, explosions, lighting and block updates unchanged).

#### 8. Client and rendering (client tier)

- `SceneViewport`, `MonitorRenderer`, `ScenePlayers`, `SpaceSceneRenderer`, `SpaceComputeRenderer`: resource lifetime under repeated open and close, resize, and failed allocation (extend the existing 23-allocation fault-injection scenario to the compute renderer).
- `TerminalScreen`: Shift-Esc closes and sends the close packet exactly once; paste larger than the queue cap is dropped.
- **GLSL parity.** Extend `NativeVolumeShaderTest` to compile `space.glsl` with `glslang` if available and compare CPU `SpaceRay` results with GPU output on a fixed set of rays.

#### 9. Build and tooling

- **CI does not run server tests.** `.github/workflows/build.yml` runs `./gradlew build`, which covers unit tests only. Add a second job running `runGameTest` (headless) so server tests are visible on pull requests.
- **Coverage measurement.** Add JaCoCo to the `test` task and publish the report as an artifact; the Limits section notes that counts are scenarios, not coverage, and the untested classes above were found by name search.
- **Repository hygiene check.** A script test asserting `.gitignore` covers `logs/`, `mods/`, `*.log.gz` and the local JDK.
- **Pin workflow actions by commit SHA** and add a check in `Test-LocalVerification.ps1` that fails on unpinned `uses:` lines.
- **`Remove-OldGameTestWorlds.ps1`.** Add cases to `Test-GameTestRetention.ps1` for a junction inside a candidate world and for a locked `session.lock`.

### Suggested order

1. Fix and re-enable the crash-class and data-loss items: long string literals, nested `else`, `mv a a`, machine-slot release.
2. Add the fuzz tests (section 1); they would have found the stack overflow without a human review.
3. Decide the owner/actor policy, then write section 2's authorization tests.
4. Add the CI `runGameTest` job and JaCoCo so later gaps are measured rather than guessed.
5. Work through sections 5 to 8 by file size and by how much player state each class can lose or corrupt.

Air balance findings and the plan for balance tests (leak rates, survival timing, particles) are in [air-balance-testing.md](air-balance-testing.md).

## World 5 sky follow-up

The sky regression now covers the coordinate frame used away from spawn: X=886, Z=-82, the world border, native height conversion, camera origins/rotations and the server's surface geometry metadata. GPU tests require a blue daytime sky, unchanged celestial rendering after horizontal travel, rain clouds even when optional dry-weather clouds are disabled, and restoration of clear skies after rain. Actual native terrain/glass captures run at World 5's coordinates and time 9502; the storm capture disables optional clouds and checks overhead extinction. The launcher check verifies World 5 as one quick-play argument, the actual `run` directory and Player451 identity.

This run passed 450 unit tests, all 100 discovered server tests and all 15 graphical entrypoints, plus packaging, assets and formatting. The same 12 known-bug unit tests remain skipped. They are listed by name in the [World 5 sky receipt](world5-sky-verification.json); its status is `passed-with-known-skips`, not an all-tests-enabled certification. Air balance targets/harness remain proposed, and the two-client and process-crash matrices were not rerun for this sky change.

## Ship beacons and the preset shipyard

The [shipyard implementation](shipyard.md) adds four unit cases, eleven native cases, an opt-in fresh-process entrypoint and a graphical entrypoint. Checks cover centered placements, fit failures, separate quote/purchase actions, all six complete blueprints, multi-deck preflight, exact-size pads with controllers on every side, free persistent delivery, occupied/obstructed/wet pads, entity readiness and collision, forged and cross-player sessions, saved partial orders, conflict-preserving resume and showroom revisit preservation. Storage failures before construction and during final order removal must leave retryable state and preserve committed ship identity. The client really right-clicks a beacon, purchases through typed packets, rejects a duplicate purchase, launches, lands and captures the showroom after its meshes become visible. The shared recipe/guide checks now include all 24 recipes.

`shipyard-crash-test.ps1` terminates a real JVM at seven delivery checkpoints and starts a second JVM on each disposable save. Recovery must complete automatically without clicking the beacon, preserve committed ship/device identities and saved cargo, leave outside blocks unchanged, and produce one complete launchable ship. `multiplayer-test.ps1` now ends with two authenticated graphical clients racing to purchase onto the same empty pad: one delivery, one occupied-pad response, one persisted ship, no leftover order and no charge to either player.

Final frozen-source result: 454 unit tests passed with the same 12 known-bug skips; all 112 discovered server tests, all 16 graphical entrypoints, all seven physical delivery crash/restart checkpoints and the two-client multiplayer scenario passed, plus assembly, assets and formatting. [Receipt, hashes and retained evidence](shipyard-verification.json). All evidence uses fingerprint `35cdfa7fafbfcb7973cfe2ddbf563cf2abcc74cb0da8aea05a1950c49c7c0e66`. The separate fourteen-case launch/landing crash matrix was not rerun. The strict no-skipped-tests gate remains unsatisfied.


## Measured coverage

`./scripts/Invoke-Gradle.ps1 test -Pcoverage=true` records JaCoCo coverage from JUnit and writes HTML/XML under `build/reports/jacoco/test`. Normal tests do not run the coverage agent. `./parallel-test.ps1 -Graphics -GraphicsWorkers 2 -ServerWorkers 4 -Coverage` also records each Minecraft JVM separately and writes a combined report inside that run's `coverage/` directory. The combined report measures main production sources; client-only source coverage is not included in that denominator. Graphical entrypoint receipts separately establish which real client scenarios completed.

Do not compare unit-only and combined percentages as if they covered the same execution. Keep the report, exact scenario inventories, source fingerprint and packaged artifact hash together. The initial unit baseline and final measured release results are recorded in [release-readiness.md](release-readiness.md).
