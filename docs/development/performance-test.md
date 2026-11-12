# Save and journal performance test

**Status: further performance work deferred by user request.** Preserve the implementation, harness and recorded results. Do not run new benchmarks or tune toward the one-second target in the current batch. The full-size 100/300-ship measurements do not block release. Only demonstrated correctness failures justify changes to this code; save/recovery correctness checks remain in scope. Recorded evidence does not establish a reliable before/after speedup. Historical measurement plans below remain available for a later explicitly resumed effort.

This test measures what saving and journaling cost on a server that looks like a real one: how many ships per second it can move, build and record, and how long the worst tick is while it does. It exists to make the open cost decision in [journals and save recovery](../design/journals-and-save-recovery.md#performance-test) with numbers. Matching runs establish a baseline for subsequent changes. The earlier simple-fixture results cannot establish a before/after comparison for this implementation.

Timing is evidence, never a pass/fail threshold, the same rule as `AtmosphereBenchmark` ([`AtmosphereBenchmark`](../../src/test/java/com/nullandkale/nullspace/model/air/AtmosphereBenchmark.java)). It is opt-in and never part of `check`, `verify.ps1` or CI.

## Questions it answers

1. How long does a store save take, and a flushed world save, as ship count grows?
2. How many MOVE, BUILD and RECORD operations per second does the server sustain, and how long is the longest tick while it does?
3. What does the server cost per tick with nothing happening but ships existing (air, life support, crew lookups)? That is the baseline for review finding 7.
4. How does startup time grow with the number of journals on disk? That is the cost of review finding 1 today.

## The world

A generated, disposable world. It is never a copy of a player save.

| Part | At the 40-ship size | How it is made |
| --- | --- | --- |
| Ships in space slots | 24 | [`ShipGenerator`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/ShipGenerator.java) and the shipyard presets in [`ShipBlueprints`](../../src/main/java/com/nullandkale/nullspace/worldgen/ships/ShipBlueprints.java), fixed seed |
| Ships on pads | 10, split between the Overworld and one planet | Same, landed on registered pads |
| Stations | 6, with rooms and docking berths | [`StationDeployment`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationDeployment.java) |
| Crew | 8 mock players aboard different ships | Fabric's mock server players, as the native tests use |
| Cargo | Every hold full: chests, shulker boxes with contents, a computer with files, two animals aboard each ship | Fixed item lists, so runs compare |

The 100- and 300-ship sizes keep the same proportions. The fixture is built inside each run, before the warm-up starts, so building it is never measured. The first ships are launched into space through the real transfer code; a launch waits for the chunks' saved entities to load, so setup retries it each tick.

The result records how close the world is to a real one: ship count, store size raw and on disk, bytes per ship, loaded chunks. For reference, World 5 has 41 ships, about 140 KB of raw store data per ship (almost all of it air readings) and 116 KB on disk.

## How it runs

```powershell
.\scripts\test\performance-test.ps1 -Ships 40,100,300 -Repeat 3 -Label baseline
```

- The runner follows [`crash-test.ps1`](../../scripts/test/crash-test.ps1): it exports the dedicated-server launch (`exportLocalServerLaunch` in `build.gradle`) and shares Java argument handling through [`LocalVerification.psm1`](../../scripts/lib/LocalVerification.psm1).
- The scenario is chosen with `-Dnullspace.perf.scenario=<name>` and `-Dnullspace.perf.ships=<n>`. Without them the entrypoint returns at once, like the crash entrypoints.
- Each scenario at each size runs in its own server JVM with a fixed heap (`-Xms4G -Xmx4G`), view distance 10 and no client.
- Each scenario runs in three phases, and the run is repeated 3 times with the median reported:
  1. **Warm-up, one elapsed minute, not counted.** The world behaves like a quiet live server: the space ships cruise slowly through local flight, mock crew remain aboard, ship systems produce real air and device state, and one ship launches or lands about every 15 seconds. The JIT, the loaded chunks and the store's caches reach a steady state before anything is measured.
  2. **Ramp.** The scenario's operations start slowly and the rate steps up: 0.1, 0.25, 0.5, 1 and 2 operations per second, 30 seconds per step, with the warm-up's background movement still running. Each step records its own longest tick, ticks over 50 ms, and whether the server fell behind its intended rate.
  3. **Flat out.** The scenario's operations run as fast as the server takes them, to find the ceiling.

  The ramp shows the rate at which ticks start to stretch, which matters more for a real server than the ceiling alone.
- Nothing else should run at the same time; the result records the CPU, disk and Java version.

Timing comes from seams that already exist, so the baseline needs no production changes:
- the stage hook in [`TravelService`](../../src/main/java/com/nullandkale/nullspace/gameplay/travel/TravelService.java) (`failureInjector`) timestamps each journal stage;
- the write hook in [`WorldStore`](../../src/main/java/com/nullandkale/nullspace/core/save/WorldStore.java) (`writeObserver`) times store and journal writes;
- the server's own tick times give the longest tick.

## Scenarios

| Scenario | What runs | Measured |
| --- | --- | --- |
| `idle` | Two more minutes of the warm-up's background movement, with no ramp | Median and longest tick; time spent in life support and ship lookups |
| `store-save` | Store saves of a changed store, ramped | ms per save, bytes written |
| `world-save` | Flushed `saveEverything` calls, ramped from one per 10 seconds to one per second | ms per save, chunks saved |
| `move` | Launch and landing on registered pads, by different ships in turn, ramped | Ships per second, ms per operation split by stage, longest tick per step |
| `move-burst` | After the ramp, 4 ships launching in the same second | Longest tick, and how the moves queue |
| `build` | Station room builds, docking tunnels and shipyard deliveries, ramped | Operations per second, ms per operation, longest tick per step |
| `record` | Title issuance, signing, ownership transfer and recall authorization, ramped | Operations per second, longest tick per step |
| `startup` | Server start with 0, 100 and 1,000 finished journals on disk (no warm-up) | Seconds to ready, and time spent in recovery |

BUILD uses complete station-room jobs, delivery and scrap cycles, and collar retraction/rebuild cycles. RECORD uses the production title and authorization services. A burst is one logical operation containing four moves; throughput is labelled with that unit. Setup and workshop provisioning are outside measurement.

## Accounting and compatibility

Minecraft's GameTest loop runs without ordinary tick pacing. The opt-in phase driver restores the 50 ms budget; it measures work before sleeping. Phase lengths and throughput use monotonic elapsed time. An asynchronous room or transfer counts once, when it finishes. Attempts, completed operations, refusals and scheduling backlog are recorded separately. Pending operations may extend a phase until completion, with a three-minute operation timeout.

Startup uses three separate JVMs per case: construct and save the fixture, seed exactly 0/100/1,000 finished journals and halt, then measure a fresh process. Readiness includes JVM and world startup. Startup schema 3 reports `storeLoadMs`, `journalRecoveryMs` and their sum, `recoveryMs`. Historical schema-2 `recoveryMs` omitted initialization and is not directly comparable. The measured process checks the actual journal population.

Schema 2 / fixture 2 results cannot be compared with the earlier harness: it divided attempts by simulated ticks and had smaller empty hulls. `-Compare` requires completed runs with matching schema, fixture and phase durations, and matches rows by step name. Shortened smoke phases check behavior; they do not establish steady-state performance.

## Results

Each run writes `build/performance/<run>/`:

- Each case's `result.json`: per scenario, size and ramp step, the operation count, operations per second, median, 95th percentile and longest operation time, the longest tick, ticks over 50 ms, bytes written, heap, and the world statistics above. It also records the machine, Java version, git commit, label and whether the tree had uncommitted changes.
- Root `result.json`: a completion receipt, source fingerprint, selected cases, durations and machine details.
- `summary.md`: tables of the median across repeats, one per scenario.

`.\scripts\test\performance-test.ps1 -Compare <runA> <runB>` prints the two side by side with the change per row.

## Deciding the save cost

Once the baseline exists, a bound on the longest tick for BUILD operations at the 100-ship size is agreed, at a ramp step that represents a busy real server. If the journaled BUILD operations stay within it at that step, each keeps its own flushed save. If not, BUILD saves are batched, as described in the design.

### November 11 baseline

The first complete full-duration 100-ship BUILD run is retained as [raw results](../performance/journal-build-100-baseline.json) and its [completion receipt](../performance/journal-build-100-baseline-receipt.json). This is one repetition, not the default three-run median, and establishes no before/after improvement. It includes a 60-second warm-up and 30-second ramp and flat phases.

| Offered rate | Completed | Actual operations/s | Longest tick, ms | Refused |
| --- | --- | --- | --- | --- |
| 0.1/s | 2 | 0.067 | 1,427.0 | 0 |
| 0.25/s | 7 | 0.233 | 1,914.0 | 0 |
| 0.5/s | 14 | 0.465 | 2,682.4 | 0 |
| 1/s | 17 | 0.551 | 2,016.6 | 0 |
| 2/s | 18 | 0.569 | 2,040.2 | 0 |
| Flat out | 18 | 0.562 | 1,872.3 | 0 |

The fixture has 85 mobile ships, 15 fixture stations, eight mock crew and 170 cargo animals. Workload and ambient builds bring the final store to 105 records: 16,934,231 raw bytes and 347,390 bytes on disk, with 18,231 loaded chunks. The measured machine uses a Ryzen 9 5900HS, SSD and Java 25.0.2 with a fixed 4 GiB heap.

One operation every four seconds still produces a 1.91-second pause; increasing the offered rate beyond 0.5/s produces a scheduling backlog. The November 11 decision is to batch BUILD saves, targeting pauses under one second at the representative 100-ship workload. Keep each job's locks and journal until the shared flushed save completes, then repeat matching measurements. Batching reduces repeated save barriers; it does not by itself guarantee that an individual flushed save fits the target.

The complete eight-scenario smoke receipt is `build/performance/20261111-084637-scenarios-complete/result.json` (ten cases, including the three startup populations). The 300-ship idle attempt stopped during setup because valid metadata exceeded the current 64 MiB NBT accounting quota. It supplies no idle timing result; the bounded read-quota fix has passed large-slot validation/reopening and that size still needs retrying.

## Limits

- Mock players do not send network packets, so client arrival acknowledgements are simulated as in the native tests. Network cost is not measured.
- One machine's numbers. Comparisons between runs on the same machine are meaningful; absolute values are not a guarantee for a hosted server.
- Disk speed dominates flushed saves. The result records the disk so a slow-disk run is not mistaken for a regression.
