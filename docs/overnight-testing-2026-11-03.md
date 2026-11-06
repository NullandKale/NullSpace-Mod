# Overnight testing — November 3, 2026

Restart `dev.ps1` before playing to load the production fixes. Existing player saves and recovery backups were left unchanged. All test scenarios used disposable generated worlds.

The [verification receipt](overnight-testing-verification.json) records final run paths, counts, source fingerprint and the tested JAR digest. See [local verification](local-verification.md) for the commands and retention settings.

Passed: 500 unit tests with zero skips, 154 mod native tests plus two vanilla smoke fixtures, all 22 graphical entrypoints, two-client multiplayer, and all 21 physical crash/restart cases. The final main matrix took 4m 58s. Six graphical workers were faster than eight in the matching-source benchmark.

## Fixes found through testing

Repeated graphical boarding exposed a gap between the automatic boarding trigger and the collar's end stop. A passenger could move beyond the old one-block trigger between server ticks and stop at 1.2 blocks, safely supported but unable to transition, eventually running out of air. Boarding now covers the protected distance up to the saved end stop. The native regression failed before the fix and passed afterward, checking both ends of the longest collar with both ships unpowered; closed doors and positions beside the collar remain rejected. Evidence: `build/docking-regression/before/` and `build/docking-regression/after/`. The original real-client failure is retained under `build/parallel-verification/20261103-094237-9a4022/graphics-2/`.

The two-client shipyard scenario exposed a real `ConcurrentModificationException` in terminal session refresh when a peer disconnected. Session cleanup and refresh now iterate snapshots, remove only the expected session, and check session identity after callbacks before sending updates. The multiplayer regression keeps the remaining client alive for 40 ticks after its peer completes, and requires both client processes to exit successfully.

The three-world graphical lifecycle test also exposed overlapping metadata saves during shutdown, racing on `ships.nbt.tmp`. Saves are now serialized per `ShipStore`; different worlds retain separate locks. A controlled two-writer native regression pauses the first actual write and attempts a second save. It failed before serialization and passed afterward, preserving a complete final snapshot. Evidence: `build/docking-regression/save-before/` and `build/docking-regression/save-after/`. The lifecycle test still requires actual shutdown persistence of the computer disk and removal of the stopped server's context.

Twelve disabled guest-computer regressions are now enabled and passing. The fixes cover Python-style nested dedents and `else`, comparison precedence for `not`, long quoted expression tokens, writable filesystem boundaries, missing paths, same-file copies/moves, `head` argument validation, and typed saved-image validation. Malformed image restores leave the previous computer state intact. Three additional tests cover large tokens, path aliases and atomic rejection of malformed image fields. The unit inventory increased from 497 cases with 12 skips to 500 cases with zero skips.

Native sharding revealed that a cold-computer test shared its environment with fixtures that legitimately warmed the hull. It now uses a separate test environment; the original cold-state and resume assertions remain. Crash recovery fixtures now install the current scanner instead of a retired door-control block. The old fixture correctly lost that retired device during load migration, making its preservation assertion obsolete. Multiplayer controls now use the current controller destination picker and shipyard screen.

The last landing crash preparation also exposed a fixed-delay fixture: its preliminary launch had not moved the ship, but the fixture ignored the returned outcome and dereferenced a missing vessel. Code inspection showed that transfer can return `WAITING` until chunk entities load. The fixture now waits for actual entity readiness in both regions and explicitly requires the preliminary launch to complete. The old result was not logged, so its exact outcome cannot be recovered.

This final change touched only `CrashRecoveryGameTest.java`, after the full main matrix and multiplayer had passed. Recovery uses the newer fixture fingerprint; those earlier results retain their original fingerprint. `build/overnight-fixture-change-proof.json` and the receipt record the one-file difference and verify that the production JAR is unchanged. Each recovery matrix must still remain frozen throughout its own run.

## Runner improvements

- One compilation and package preparation feeds isolated native and graphical JVMs. Both worker counts support 1–8; defaults remain six graphical and one native worker.
- Graphical shards use per-case timing history and assign longer cases first. Native shards balance declared case counts. Every declared case must run exactly once; native XML IDs are checked individually, with vanilla smoke cases counted separately.
- Atomic graphical receipts report live cases, completion counts and durations. Final receipts include worker exits, elapsed times, unit/native evidence, a frozen source fingerprint, and a retained JAR snapshot and SHA-256.
- A repeated run caught Windows denying an evidence-file rename while a reader held the file. Publication now retries that specific error for at most two seconds, preserving the previous complete receipt throughout. A standalone regression compiles the actual Java writer and checks unlocked publication, a temporary lock that clears, and a persistent lock that must fail. Other I/O errors still fail immediately.
- Workers fail visibly and stop only sibling JVMs owned by their invocation. Transfer recovery now checks frozen inputs too. A test input change invalidated an intermediate recovery run, so it was rerun rather than accepted.
- Disk preflight defaults to 4 GiB free. Retention keeps worlds from the latest two passing parallel runs; older closed disposable worlds can be removed while their logs, screenshots, receipts and JARs remain. Active locks, failed/running runs and redirected paths are excluded. Harness fixtures exercise these boundaries.

The drive initially had about 1.4 GiB free. Cleanup reclaimed 4.38 GiB from 24 closed disposable world roots after checking their locations and locks. `build/overnight-world-cleanup.json` records those exact paths; later automatic cleanup is listed in each parallel receipt. Player saves, backups and recovery files were preserved.

Two failed parallel runs also needed their closed disposable worlds removed after the disk guard rejected another launch below 4 GiB free. Their diagnostic logs, receipts, screenshots and private resources remain. `build/overnight-failed-world-cleanup.json` records the explicitly scoped candidate paths; that cleanup's byte-summary expression failed after deletion, so reclaimed byte counts for those two runs are unavailable.

Later cleanup reclaimed a further 983,301,602 bytes from closed worlds in the docking-trigger and shutdown-save failure runs. The relevant player and ship metadata were copied beside the retained logs/screenshots first. `build/overnight-explicit-cleanup-20261103-102114.json` records planned paths and completed removals, with its receipt written before deletion.

## Concurrency measurements

The comparable six/eight runs used the same source fingerprint, 497 unit cases (12 then skipped), 155 native cases including one vanilla smoke fixture, and 22 graphical entrypoints. Each passed. Times below include the runner's preparation and verification work.

| Graphical workers | Native workers | Elapsed | Evidence directory under `build/parallel-verification/` |
| --- | --- | --- | --- |
| 6 | 1 | 5m 30s | `20261103-001014-36f8d5` |
| 8 | 1 | 6m 03s | `20261103-001754-e6e6af` |

An earlier two-worker run took about 5m 47s, but used an earlier source snapshot and is not a controlled comparison. The six/eight comparison favors six on this machine: Ryzen 9 5900HS, 8 cores/16 threads, about 39.4 GiB RAM, and an RTX 3070 laptop GPU with 8 GiB VRAM. Recorded GPU samples reached 100% utilization with eight workers; these samples are not peak or average measurements. More JVMs share the same CPU and GPU.

The final matrix uses four graphical and two native workers to exercise native splitting and timing-based scheduling. It verifies correctness of that arrangement; it does not establish a universal fastest worker count. Some intermediate runs overlapped multiplayer/recovery workloads, so their durations should not be compared as controlled benchmarks.

The final invocation used `-KeepSuccessfulWorldRuns 1` to recover space while preserving one previous passing world set and all retained evidence. The default remains two.

That final four-graphical/two-native matrix passed in 4m 58s, including preparation and verification, with 500 unit cases without skips, 154 mod native cases plus two vanilla smoke fixtures, and all 22 graphical entrypoints. It includes the actual client boarding round trip and three sequential integrated-server lifetimes with shutdown disk persistence and GPU resource cleanup.

## Evidence and limits

The final receipt contains only completed passing results: 500 unit cases without skips, 154 mod native cases plus two vanilla smoke fixtures, 22 graphical entrypoints, two-client multiplayer, 14 launch/landing abrupt-crash cases, and seven shipyard abrupt-crash cases. Each physical recovery case halted preparation with exit 86, then passed verification in a fresh JVM with exit zero and its success marker. The receipt collector independently checked those markers, inventories, graphical timings and retained JAR hash. The harness also passed partitioning, timing-history, identity-inventory, disk-threshold and world-retention checks. Run `.\scripts\Test-LocalVerification.ps1` for the 38 harness checks and retention fixtures, and `.\scripts\Test-ClientEvidence.ps1` after preparing a local server launch for the three actual Java evidence-writer lock cases.

One repeated graphical JVM stalled before entering any test. Its render thread was waiting on a semaphore and its test thread on Fabric's phase synchronization. The other five workers exited successfully. The stalled invocation remains failed, with `build/overnight-graphics3-threads.log` retained; it was explicitly stopped and the unchanged build rerun. The cause of that intermittent startup stall remains unresolved, bounded by the runner's global timeout.

These are repeated bounded scenarios, not proof of an uninterrupted overnight soak or every possible crash timing. Recovery tests exercise explicit durable checkpoints and interrupted temporary journal writes; they do not emulate storage corruption or hardware power loss. Graphical runs require a working desktop/GPU.
