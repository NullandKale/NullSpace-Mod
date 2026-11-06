# Release readiness review

Working record, November 5, 2026. The public alpha is not yet approved for publication. The desired server is open to anyone; it must use authenticated accounts rather than the local test launcher's offline identities. The [remote two-player playtest](playtest-2026-11-04.md) adds real external connectivity and a two-hour session, but also found a construction-dock trap. [Follow-up](playtest-follow-up-2026-11-05.md).

## Current blockers and verification

| Finding | Change or required evidence |
| --- | --- |
| Disconnected pieces block an otherwise usable ship | Added controller review and a separate confirmation. Travel captures and clears only the connected component. Space separation uses an independent debris frame, preserving original blocks and container contents. Stale reviews and unsafe cabins are refused. |
| Terra has no destination pad in a fresh world | Removed the special-case skip; starting above another planet also initializes its landing destination before the player boards. Startup creates a terrain-aware registered pad with a clear column, without clearing terrain. Repeated visits preserve edits. A native test lands a real ship and passenger on that pad. |
| Encounters repeat a narrow set of ships | Added all nine manufacturer ship templates to seeded salvage selection. Orbital regions gain one to three salvage ships with actual docking ports and finite room supplies. Existing site records and removal tombstones are retained during migration. |
| Offline crew can reconnect at an abandoned room's old offset | Detachment now redirects unsafe saved crew positions into the validated surviving cabin, including passengers disconnected during loading. |
| Transient Windows registry promotion fails under load | Replaced the 50 ms sharing-lock retry budget with bounded backoff. Real Windows lock tests verify eventual success, interruption and preservation of both files on persistent failure. |
| Existing coverage document understates the current suite and overstates proposed gaps | Completed combined coverage and exact-inventory checks; current and historical receipts below have distinct JAR hashes. Unregistered native test classes now fail verification before launching workers. |
| No license declared | The owner wants free mod/fork distribution, monetized servers and videos, and approval before selling copies or forks. [Draft permissions](release-license-proposal.md) await review; do not assign MIT/GPL. |
| No production server package | Prepared pinned private client/server files, checksums and launch instructions. The exact final package boots on loopback without the test mod and saves cleanly. Public distribution awaits the license. |
| Local multiplayer does not test online authentication | Keep local two-client tests, then perform an authenticated join check on the hosted server. An offline loopback pass is not evidence for this gate. |
| Public operations need final verification | The remote playtest establishes public-IP connectivity on the development server. Still rehearse whole-world restoration, authenticated joins and external access on the hosted production installation, and establish moderation/reporting. |
| Construction docks can trap recessed ships; demolished ports leave them clamped | Recompute the finished room's gap, protect visiting ships' departure corridors, and allow the normal Undock control to recover using a saved route or either surviving port. Four native regression methods and full graphical/multiplayer checks pass. |
| Multi-second join/travel stalls | Recorded full-world save barriers of up to 964 ms during station construction and 861 ms during transfer. Staging/coalescing those saves is a separate performance task that must preserve recovery ordering. |

## November 5 verified docking hotfix

The current JAR passes **534 unit tests**, **188 mod-native tests**, four worker smoke tests, all **25 graphical entrypoints**, both loopback client roles, and a standalone packaged-server boot and graceful save. Main-source coverage is **17,519 / 19,862 lines (88.20%)** and **10,611 / 14,021 branches (75.68%)**. Client-only sources are outside that denominator.

JAR SHA-256: `e365171d971a302019a266d369ec6429822ec8942bffc2ec34ea34730796389a`. [Exact receipts, package checksum and profiling scope](playtest-hotfix-verification.json). [Changes and steps for the existing Courier](playtest-follow-up-2026-11-05.md).

The full run is `build/parallel-verification/20261105-102144-229709`; two-client run `build/local-multiplayer/20261105-102759-98424c`; standalone smoke `build/alpha-server-smoke/20261105-102517`. Its private bundle is `build/alpha-packages/20261105-102508-e4837d.zip`. No public upload or license grant has been made. The process-halt matrices below certify the earlier baseline, rather than this later docking JAR.

## Completed baseline before the remote-playtest fixes

The completed integration run passes **534 unit tests**, **184 mod-native tests**, **four worker smoke tests** and all **25 graphical entrypoints**, with no unit skips. Receipt: `build/parallel-verification/20261104-191624-a7b3ba/result.json`. Packaged JAR SHA-256: `a021962904a8c4346a4b9cb83f61b545ebb3e0ed6b47244b8bb21aae5fc4b5c6`.

JaCoCo recorded **17,328 / 19,702 main-source lines (87.95%)** and **10,521 / 13,923 branches (75.57%)** across JUnit and the native and graphical Minecraft JVMs. Client-only source files are outside that denominator. HTML/XML are preserved in that run's `coverage/` directory. Completion receipts establish which UI scenarios actually ran.

The focused debris test also passes actual replay at `PREPARED`, `DESTINATION_STAGED`, `SOURCE_CLEARED` and `PASSENGERS_MOVED`, then finishes the passenger handshake. Other cases cover surface launch with a preserved container, an obstruction above the launch path, stale confirmation and ordinary cancellation after a replay changes loose blocks.

All **14 launch/landing process-halt cases** pass (`build/local-crash/20261104-192125-0c9d6c/result.json`), as do all **seven shipyard process-halt cases** (`build/shipyard-crash/20261104-192147-fef015/result.json`). Both real client roles pass the loopback multiplayer scenario (`build/local-multiplayer/20261104-192433-9db548/result.json`). The standalone package passes boot, Terra creation and graceful save on this same JAR (`build/alpha-server-smoke/20261104-192145/result.json`).

The focused real-client new-world check caught and fixed a terrain bug: height lookup on an unloaded chunk returned the minimum build height. Terra now loads candidate chunks before selecting a pad floor, including negative-Y flat worlds. Receipt: `build/flat-terra-client/20261104-183719/result.json`.

A prior graphical run exposed a loose test-helper wait: it clicked travel before the destination picker closed. The helper now requires the controller screen and acknowledged destination. A further delivery test found that the server's shared cooldown rejected an immediate Land click after selection. Destination changes and travel now have independent bounded limits; the complete baseline above includes that fix. Failed workers also allow the remaining isolated jobs to finish before reporting overall failure.

The November 5 docking hotfix is a later candidate, with separate receipts. The baseline does not certify that later JAR. Public publication still requires the license and operations gates below.

## Coverage findings

| Main-source area | Lines exercised by unit/native tests | Branches exercised |
| --- | --- | --- |
| Loose-block review/confirmation | 94.5% | 85.0% |
| Physical travel | 88.9% | 80.0% |
| Orbital population | 93.3% | 81.9% |
| Power allocation | 97.0% | 86.5% |
| Terra site selection | 85.7% | 81.5% |
| Ship controller | 85.0% | 77.0% |
| Docking | 73.3% | 58.8% |
| Station construction/service | 74.7% | 60.7% |
| Atomic promotion | 100% | 100% |

These identify where additional UI/multiplayer and failure-path work matters. They are not a reason to add tests that merely repeat implementation details or inflate a percentage. OS-specific file-lock tests are Windows-only; a Linux CI report should show those explicit skips.

## Audit boundaries

The review covers the creation/start flow, landing destinations, physical hull transfers and recovery, ship systems, generated encounters, docking/boarding, controller session authorization, release metadata, dependency availability, test isolation and server setup. It does not establish protection against every hostile client or third-party mod, cross-platform GPU compatibility, long-running public-server performance, or correctness of arbitrary custom worlds and datapacks.

The source includes development labs, but `ShipLab` and `PlaytestWorld` register their fixture entrypoints only in a Fabric development environment. Ordinary action commands still require player proximity, an existing accessible controller and server-side validation; administrative world-generation commands have permission checks. The release JAR must still be inspected for test entrypoints and development JVM flags before distribution.

The default compute renderer checks for an OpenGL backend with OpenGL 4.3. Its error message offers `-Dnullspace.compute=false` for legacy rendering. Record compatibility from actual test devices; the local default-renderer tests do not validate every fallback or operating system.

See [public server setup and release gates](public-alpha.md).
