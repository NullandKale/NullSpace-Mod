# Local verification

All harnesses use development/test dependencies. None opens `run/saves/NullSpace-Dev` or `run-lab/saves/NullSpace-Lab`. The multiplayer and physical-crash scripts are opt-in and are not dependencies of build/check or CI.

The combined runner `.\scripts\test\verify.ps1 -Graphics -Multiplayer -Crash` runs the complete matrix sequentially and preserves receipts, reports, logs, screenshots and the built JAR digest under `build/verification/<run>/`. Omit optional switches to run only the ordinary unit/native/assets/build suites; omitted suites are explicitly marked not-selected.

For the ordinary unit/server/assets suites and graphical coverage, run `.\scripts\test\parallel-test.ps1 -Graphics`. It compiles and packages once, then launches the server tests alongside six graphical workers by default. Set `-GraphicsWorkers 8` for eight, or choose any count from one to eight. `-ServerWorkers 2` splits native tests across two JVMs; the default is one and the supported range is also one through eight. Each worker has its own world root, working directory, resource manifest and logs under `build/parallel-verification/<run>/`.

The scheduler assigns the longest graphical cases first using timings from recent passing runs, falling back to even class counts without history. Counts can be uneven because individual tests take different amounts of time. Native shards use declared case counts. No empty workers are started when the inventory is smaller than the requested worker count. Success requires every declared class exactly once and every native test ID exactly once, checked against Fabric's XML report. Unit skips remain explicit in the result; the current suite needs none.

On Windows, graphical worker windows automatically tile into a grid on the monitor active when the command starts, using its working area so the taskbar stays clear. Six workers normally use 3 columns and 2 rows; eight use a layout chosen for the monitor's aspect ratio. Each window is labeled `NullSpace tests | graphics-N` and placed as soon as SDL creates it. Only that invocation's graphical worker PIDs are moved, without taking focus. Slots stay fixed when workers finish, and you can reposition a window manually afterward. Grid bounds and arrangement state are recorded in the run receipt.

Server workers are additional: the defaults mean seven game JVMs, and eight graphical workers plus two server workers mean ten. Each JVM has a 2 GiB maximum Java heap and graphical workers share the GPU. Six graphical workers were faster than eight in the comparable November 3 runs; more concurrency is not automatically faster. Use `-GraphicsWorkers 2` for lower resource contention while playing, or `.\scripts\test\parallel-test.ps1 -Graphics -GraphicsWorkers 4 -ServerWorkers 2` to try splitting native work without increasing the total beyond six JVMs.

Test worlds are disposable by default. The parallel runner removes closed `world`/`saves` directories at startup and again after all owned workers exit, including failures and timeouts. Older failed runs are cleaned too. Logs, receipts, XML reports, screenshots, private resources and JAR snapshots remain. Cleanup records paths, byte counts and skipped active worlds in `result.json` and `world-cleanup.json`. `-MinimumFreeGiB 4` remains the default disk-space threshold after startup cleanup.

Use `-KeepWorlds` to preserve this parallel run for debugging; its receipt also excludes it from later automatic pruning. Alternatively, `-KeepSuccessfulWorldRuns 2 -KeepFailedWorldRuns 1` retains that many recent worlds of each outcome. Running receipts, live worker PIDs and locked sessions are skipped. Redirected paths are rejected. To clean ordinary completed parallel runs without launching tests, run `.\scripts\test\Remove-OldParallelWorlds.ps1`. Startup also runs `.\scripts\test\Remove-OldLocalTestWorlds.ps1` for completed crash, multiplayer and focused graphical runs; this script requires a final receipt and checks Java argument-file paths before removing their worlds. Unidentified or unfinished historical runs are left alone.

On Windows, Gradle's `runGameTest` and `runClientGameTest` tasks have cleanup finalizers, including when the JVM fails. Native startup also prunes abandoned closed UUID sessions. Multiplayer and both physical crash runners clean worlds in `finally` after their processes exit; crash worlds remain intact between the prepare and recovery JVMs. Those runners accept `-KeepWorlds` for debugging. Their cleanup uses the shared [world cleanup helper](../../scripts/lib/TestWorldCleanup.psm1), which only accepts `world` and `saves` inside this workspace's `build` directory. Custom artifact locations outside `build` retain their worlds and print a warning. Player saves, backups and recovery files outside disposable test worlds are never cleanup targets. Non-Windows Gradle launches currently retain worlds.

The parallel runner records live case progress, per-case graphical timings, worker elapsed times and exit codes, unit XML, native XML, source fingerprints and a snapshot of the built JAR with its SHA-256. Graphical evidence is written atomically; readers allow atomic replacement while a worker runs. On Windows, a temporary access-denied rename retries for at most two seconds; persistent locks and other I/O errors still fail. The final receipt clears live progress fields after checking completion. Source/build inputs must remain unchanged. If a worker fails, only sibling test processes started by that invocation are stopped. Multiplayer and physical-crash coverage still use their dedicated runners.

`.\scripts\test\Test-LocalVerification.ps1` checks scheduling, inventory, fingerprint, disk and retention rules with disposable fixtures. `.\scripts\test\Test-ClientEvidence.ps1` compiles the actual Java evidence writer and tests unlocked, temporarily locked and persistently locked publication. Prepare `build/local-server-launch.json` through a normal local runner first; the evidence checks use its existing compile classpath and the project JDK.

Run independent Gradle-based commands sequentially because Loom tasks share generated launch files. The parallel runner avoids that conflict by preparing launches first and giving each JVM a private launch configuration. Scripts prefer the project JDK 25 and Gradle cache. The graphical tests need a working desktop/GPU.

```powershell
.\scripts\build\build.ps1 --no-daemon --console=plain
.\scripts\build\Invoke-Gradle.ps1 runClientGameTest --no-daemon --console=plain
.\scripts\test\multiplayer-test.ps1
.\scripts\test\crash-test.ps1
.\scripts\test\shipyard-crash-test.ps1
# Optional single crash point:
.\scripts\test\crash-test.ps1 -Stages PREPARED
```

## Unit and server tests

JUnit covers pad geometry and bounded discovery, grass/dirt mixtures, terminal cells/ANSI/scrollback, controller editing/actions, and generic stream input. Additional pure tests cover randomized energy conservation/brownouts, finite-air conservation through bulkheads and breaches (including compression above nominal pressure), and large-coordinate quaternion/ray transforms. Server GameTests cover hull geometry, framed doors, airlocks, breaches, suits/vacuum/tethers, generated-lab preservation, pad registration, transfer/edit/cargo/entity state, interrupted journals, and maximum ship dimensions.

The review adds stair elevation and headroom, shared sensor/cargo validation and leak tracing, all four pad states, no remote generation during listing, session ownership/range/replacement/dimension rejection, stable selection through rename and a home pad retained beyond the 128-entry catalog limit, preparation/arrival acknowledgment timeouts, and native underwater air loss carried into vacuum without a stale reserve refill. An opt-in crash test method immediately succeeds during ordinary server tests; physical-crash coverage requires the separate script below.

## Two-client multiplayer

[`scripts/test/multiplayer-test.ps1`](../../scripts/test/multiplayer-test.ps1) exports Loom's client launch specification, then starts two independent graphical JVMs with separate game directories, identities, logs, and a fresh world under `build/local-multiplayer/<run>/`. It allocates a loopback port, sets offline authentication only on that loopback-only server, and disables query/RCON. Nothing listens on an external interface.

The pilot process hosts a real `DedicatedServer` through Fabric's test harness. The server runs on its own thread inside that JVM; this is two client processes, not three standalone processes. Both clients communicate through actual TCP networking, not integrated-server shortcuts or embedded mock connections.

The scenario checks:

- Both clients join and open independent authenticated ship terminals.
- A forged controller session is rejected; each client can inspect through the actual controller screen successfully.
- Both passengers launch and acknowledge destination readiness.
- A creeper retains its identity and health, and chest/dropped cargo travels.
- One client disconnects in space; the pilot lands while that passenger is offline.
- The second client reconnects aboard the returned ship with its own inventory.
- Both player inventories, chest cargo, dropped-item accounting, and authoritative mob identity survive.
- After reconnecting, both clients move to the powered sphere and open independent engineering sessions.
- One allocation edit succeeds; the other client's stale revision is rejected without changing the shared bus.
- A deliberately late engineering reading cannot replace the camera screen or reopen its closed terminal.
- Both clients view powered camera feeds, then an operator source 4,096 blocks away. Captured GPU pixels must contain source geometry, while the source stays outside the normal native client chunk cache. A primed TNT fuse and furnace inventory must remain frozen in those camera-only chunks. Scene tickets load FULL chunks without enabling block/entity ticking.
- Native player registration/tracking remains consistent after deferred arrival correction, movement, and disconnect. Both clients disconnect cleanly and finish their assertions.
- Both clients race delivery through the actual shipyard screen; exactly one wins. The pilot remains connected for 40 ticks after completion so the peer's disconnect also exercises live terminal refresh and cleanup.

The script has a six-minute default deadline and terminates only the child processes it created. Run directories, screenshots, and logs remain available. It requires both client exit codes to be zero and a completed scenario marker. It is never invoked by the GitHub Actions workflow.

## Real crash/restart

[`scripts/test/crash-test.ps1`](../../scripts/test/crash-test.ps1) creates a new save for each launch/landing stage under `build/local-crash/<run>/<direction>/<stage>/`. A preparation JVM builds a ship, player inventory, chest cargo, creeper, and dropped item. It saves baseline state, starts a transfer, and terminates with `Runtime.halt(86)` at the selected point. No shutdown hook or graceful server save follows the halt.

A second JVM opens that same save, runs automatic recovery, invokes recovery twice more, and checks exactly one authoritative ship, one copy of each cargo entity UUID, chest contents, battery energy, allocations, device IDs, finite room air, a large Unicode computer disk, a sleeping detached program counter, and the player's inventory/dimension/facing after loading native player data and resolving pending arrival.

Default crash points:

| Point | Expected recovery |
| --- | --- |
| PREPARED | Original ship on planet |
| DESTINATION_STAGED | Original ship restored; staged copy removed |
| SOURCE_CLEARED | Original ship restored; staged copy removed |
| PASSENGERS_MOVED | Original ship/passenger arrival restored |
| COMMITTED | Destination in space remains authoritative |
| TEMP_PREPARED | Baseline survives interrupted initial temporary-file write |
| TEMP_SOURCE_CLEARED | Last durable journal stage recovers an interrupted replacement write |

Temporary-file cases halt after bytes are actually written to the compressed temporary journal, before validation, force, and atomic promotion. Durable-stage cases explicitly flush the world before halting to exercise recovery of persisted world changes. Exit 86 proves the hook was reached; verify must exit zero and write its success marker. Each subprocess has a three-minute deadline.

This covers abrupt process death and torn/unpromoted temporary files. It does not emulate every storage-controller cache failure, power loss, filesystem corruption, or arbitrary instruction-level crash timing.

### Ship delivery crash/restart

[`scripts/test/shipyard-crash-test.ps1`](../../scripts/test/shipyard-crash-test.ps1) uses its own disposable saves under `build/shipyard-crash/`, with the same prepare-exit-86 / verify-exit-0 contract. `-Stages WORLD_SAVED` selects one checkpoint. The full default matrix is:

| Checkpoint | State at termination |
| --- | --- |
| INTENT_SAVED | Durable order; construction has not started |
| PARTIAL_UNSAVED | Seven hull blocks placed without an explicit chunk save |
| PARTIAL_SAVED | Seven hull blocks explicitly saved |
| HULL_PLACED | Entire hull and test cargo saved before registering ship identity |
| SHIP_SAVED | Ship identity and systems saved; world flush still pending |
| WORLD_SAVED | Hull and metadata flushed; order still pending |
| ORDER_CLEARED | Final order removal saved |

Startup must finish any pending order automatically. The second process checks exactly one ship, the complete planned hull, preserved committed ship/device identities, saved barrel contents where a flush occurred, outside-block preservation, a successful launch preflight and repeated-recovery idempotence. A frozen-source fingerprint and per-case success markers prevent stale or mixed-build results from being accepted. Normal `runGameTest` registers this entrypoint but does not halt its JVM; the physical matrix is always opt-in.

The two-client script also requires `shipyard-race-passed`: each actual client buys from its own shipyard screen on the same pad. Exactly one wins; both retain their currency items. That case runs after the existing travel, engineering and remote-camera scenarios. Both crash runners check a frozen input fingerprint and retain per-case logs and success markers.

## Save and journal performance test

[`scripts/test/performance-test.ps1`](../../scripts/test/performance-test.ps1) measures what saving and journaling cost on a generated world of real ships: one dedicated-server JVM per scenario, a one-minute warm-up, a stepped ramp, then flat out. It is opt-in and never part of `check`, `verify.ps1` or CI. The design and the scenarios are in the [performance test](performance-test.md). [`scripts/test/compare-performance.py`](../../scripts/test/compare-performance.py) prints two runs side by side:

```powershell
.\scripts	est\performance-test.ps1 -Ships 40 -Scenarios idle,store-save,world-save,move -Label before
.\scripts	est\performance-test.ps1 -Ships 40 -Scenarios idle,store-save,world-save,move -Label after
python scripts	est\compare-performance.py build\performance\<run before> build\performance\<run after>
```

## Videos of graphical tests

A client test that takes a screenshot every few ticks can be joined into a video: `python scripts/test/make-video.py <screenshots folder> <output.mp4>` (needs `ffmpeg` on the path; see [`make-video.py`](../../scripts/test/make-video.py)). [`SpaceGridClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/perf/SpaceGridClientGameTest.java) records ships launching into a grid in space and landing again this way.

## Running one native test class

`.\scripts	est\Run-FocusedTests.ps1 -SkipBuild -Classes <full.Class.Name>` needs `rg` (ripgrep) on the path for its inventory check and ends with an error without it; the test itself still runs and writes `native-tests.xml` in its `build/focused-tests` folder. Tests that change global state, or launch to the first free orbit slot, run in their own environment (their own batch) so they cannot disturb a test running at the same time.
