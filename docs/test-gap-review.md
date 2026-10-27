# Test review follow-up

This implements the gaps identified in the October 26 test review. Player saves are not used. All native, graphical, multiplayer and crash fixtures run locally in disposable worlds.

## Defects fixed

- The supposed Unicode fixtures contained literal question marks. They now use Java Unicode escapes for CJK characters and supplementary emoji. The small fixture asserts that UTF-8 byte length exceeds Java string length. Large computer disks cross the real compressed-NBT and fresh-JVM boundaries.
- `ShipStore.document` and changed computer records could alter metadata without requesting an ordinary save. Both now request a coalesced save directly; unchanged computer flushes remain passive. A standalone guest-edit regression verifies durability without an unrelated mutation or manual checkpoint.
- A malformed saved computer owner UUID escaped hydration's image fault handling. Opening that terminal could then create a fresh computer over its saved record. Invalid ownership metadata now opens read-only, and flushing a faulted computer preserves its entire record. Adoption into a ship also copies the original record before moving it.
- Twenty-tick retry delays for exhausted device budgets could synchronize with the machine scheduler's rotation, starving the same computers indefinitely. Budget exhaustion now retries after one tick. Missing-world and missing-power waits retain the twenty-tick backoff. WAIT status now stays visible during that backoff and clears when the instruction resumes.
- Resource cleanup stopped early if a cleanup action threw an `Error`. It now releases all remaining resources, retains the original constructor failure, avoids self-suppression and remains idempotent.
- Pad catalog validation now rejects duplicate controller addresses and blank identifiers/dimensions before publishing indexes or replacing the durable document. The large-catalog authorization fixture now uses distinct controller addresses.
- Computer image encoding now rejects unpaired UTF-16 surrogates rather than silently replacing them during UTF-8 encoding.

## Added checks

| Area | Coverage |
| --- | --- |
| Computer encoding | Exact 8 MiB UTF-8 boundary and one byte over; malformed sequences with valid SHA-256 digests; overlong encoding, truncated emoji and encoded surrogates; unpaired Java surrogates; legacy 65,535-byte string boundary versus large byte envelopes |
| Ordinary saves | Coalescing across an interval; unchanged state avoids I/O; injected write failure preserves the last durable file; pending work retries without another mutation; checkpoint failures propagate |
| Computer service | Corrupt checksum, future envelope, invalid guest JSON and malformed owner stay read-only through open, keyboard input, close, flush, durable save and rehydration; legacy string disks migrate through the service and remain writable |
| Warm recovery | Actual repository root replacement revokes an existing computer session, rehydrates the checkpoint's disk and retains exact device identities, battery energy and air; stale computer input and queued door work cannot alter the recovered ship |
| Scheduling | Twenty real connected terminals contend for native calls while doing guest computation; every machine progresses and aggregate CPU/device/inspection counters remain bounded; the workload must reach the 64-call server limit; separate seeded tests exercise tiny budgets and changing membership |
| Unloaded hulls | A detached script computes with a cold hull corner, waits at its world instruction without loading chunks, then resumes its continuation once the fixture explicitly loads the hull |
| Pad lifecycle | Distinct registered pads, a 32-square pad and an upper pad; obstruction invalidation through the real block-change hook; eventual `OCCUPIED` and `READY`; independent columns; cache expiry; registry reload; removed controller; cold remote pads do not generate terrain |
| Pad persistence | Duplicate IDs/controllers, malformed external JSON and a real temporary-file I/O failure preserve the old file, revision and lookup indexes; successful reload publishes a new generation |
| Shader packs | An actual temporary pack changes a shared optics include; an already allocated viewport must change pixels; the native adapter reads that same include and executes its generated code on the GPU; a broken compute shader preserves working programs; removing the pack restores normal pixels |
| Recipe datapacks | A real recipe override uses tag alternatives, a new shaped pattern and output count; it must craft, and an existing guide must refresh its graphical recipe snapshot |
| GPU ownership | Repeated pairs of differently sized viewports capture actual live GL texture, buffer, sampler and initialized query handles, close twice, and check deletion and ownership counters; repeated across three integrated worlds |
| World lifetime | Three distinct integrated servers reuse a ship ID and terminal position without inheriting runtime services; each old context disappears; world shutdown persists pending metadata and live guest files without an explicit test flush |
| Verification harness | Fifteen checks cover valid/missing/wrong markers, exact test inventories and input fingerprints. The runner rejects failed/skipped/empty JUnit reports, incomplete required native counts and source/tooling changes during a run |
| Physical crash recovery | Seven boundaries in both launch and landing directions: 14 halt/restart cases. Fixtures include edited hull, cargo, online player orientation/inventory, offline crew assignment, finite air, equipment state, a large Unicode disk and a sleeping job. Recovery runs twice and requires one authoritative ship/entity copy |

## Running

```powershell
.\verify.ps1 -Graphics -Multiplayer -Crash
```

The crash runner defaults to both directions. A focused run can use `-Directions landing` and an explicit `-Stages` list. Its result lists unique direction/stage cases; the combined verifier requires all fourteen.

The suite now declares 303 unit scenarios, 94 server scenarios (93 mod GameTests plus Minecraft's optional smoke test) and 14 graphical entrypoints. The physical crash entrypoint returns early during the ordinary native suite; the separate crash result is required to claim fresh-JVM coverage.

## Validation corrections

The first new pixel readback omitted OpenGL pixel-pack isolation and corrupted JVM memory. The test now shares the renderer's isolated readback path, checks texture dimensions before reading, deliberately exercises a non-default row stride and verifies state restoration. The failed graphical run and its JVM crash report are retained in `build/verification/20261026-143032-54e733/`. An older advisory-queue test also assumed no concurrent registry reloads and retained no chunk tickets; it now keeps its fixture loaded and polls/requeues work until all inspections finish within the test deadline.

## Limits

This is behavioral coverage, not a line-coverage percentage. The GPU soak is bounded, not an overnight leak or driver-compatibility certification. Native shader pixel checks execute the actual generated adapter in a GPU diagnostic harness; they do not replace the existing native-world graphical tests. Deliberate write/compile failure tests produce expected error logs. Real power loss, disk-full behavior on every filesystem, arbitrary third-party packs and arbitrary hardware still require separate runs.

The complete frozen-source matrix passed. [Verification receipt](test-gap-verification.json) and [local verification record](local-verification.md#test-review-follow-up---october-26-2026) contain the results, input fingerprint and release JAR digest.
