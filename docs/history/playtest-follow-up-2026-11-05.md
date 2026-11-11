# November 4 playtest follow-up

The [original notes](playtest-2026-11-04.md) remain the record of what the players saw. The first priority is letting a visiting ship leave a station reliably.

## Docking trap

The saved world still contains the clamped Yard Halcyon Courier and the station's reservation for it. Its link came from station construction, has no recorded hull gap, and now points at the finished room's door. No saved blocks, ships, inventories or operator entries have been changed during this investigation.

A construction platform has no wall around its virtual port. The finished room does. Commissioning previously switched the port identity without recalculating the separation needed for a recessed ship door. The Courier's nose could overlap that new wall, and the departure collision check refused the first step. Breaking the door and Docking Base then made the port reader fail before undocking could start.

Changes:

- Commissioning computes the finished room's hull gap and aligns the visiting ship before publishing the real connection. Boarding in progress delays that change.
- The normal **Undock** control works with a missing door or Docking Base. It closes surviving doors, preserves real cabin pressure, and uses the departure route saved at capture. Older links without that route can derive it from either surviving port. Your ship still needs fuel and propulsion power; the station does not.
- Existing overlap from the old construction bug can shrink during retreat. A voxel cannot enter a new obstruction or deepen an existing overlap. A newly blocked corridor pauses departure and clearing it resumes the maneuver.
- Temporary boarding collars are excluded from hull and power-bus discovery. Passenger checks still prevent retracting a tunnel under someone.
- Room construction checks the current hull and saved departure corridor of clamped visiting ships before consuming a kit or changing blocks. The error names the ship and highlights the conflicting block.
- Dock requests and station diagnostics identify a blocked two-block-high passage in front of a door. The Docking Base also defines the graph's facing when a player places the ordinary door facing inward.
- Routine controller refusals are logged at INFO. Unexpected failures and interrupted docking maneuvers remain visible at WARN/ERROR.

Four native regressions exercise damaged ports on either vessel, live collars, construction commissioning with a recessed Courier, a pre-fix overlapping connection, obstruction/resumption, proposed-room clearance and exact doorway coordinates. They also cover older links recovering their departure direction from either surviving port.

The final candidate passes **534 unit tests**, **188 mod-native tests**, four worker smoke tests, all **25 graphical entrypoints**, both real loopback client roles, and a standalone packaged-server boot and save. Main-source coverage is **88.20% of lines** and **75.68% of branches**; client-only sources are excluded. Verification receipt.

JAR SHA-256: `e365171d971a302019a266d369ec6429822ec8942bffc2ec34ea34730796389a`. The older physical process-halt matrices are recorded separately against their baseline JAR; they were not repeated for this docking hotfix.

## Trying the repaired connection

1. Stop the server cleanly, replace `nullspace-0.1.0.jar` on the server and both clients, then reopen the existing world. No regeneration is needed.
2. Return everyone from the station or tunnel to the Courier's cabin. Load furnace fuel and enable the Courier's controls and engines.
3. Press **Undock** at the Courier's Ship Controller. Its saved route can be used even with the station door and Docking Base missing. A real obstruction pauses departure; clearing it resumes the maneuver automatically.

## Lag investigation

`scripts/test/multiplayer-test.ps1 -Profile` records bounded Java Flight Recorder files in each disposable client's work directory. Logs and recordings survive world cleanup. Use the server-thread samples and long I/O/wait events to distinguish simulation, synchronous saves, chunk loading and client rendering. These disposable fixtures do not establish network performance in every multiplayer world.

The profiled two-client run passes in `build/local-multiplayer/20261105-101154-4b01fa/result.json`. Its JAR SHA-256 is `07686fc0a95e21b816e46af26d222d9179dca19bb14acc1af60ec9a117f9c21b`, before the final older-link compatibility addition. The production release launchers do not enable profiling.

| Recorded server-thread work | Largest individual wait | Total recorded waits |
| --- | --- | --- |
| Station construction completing a world save | 963.99 ms | 1,839.34 ms across 12 waits |
| Ship transfer completing a world save | 861.45 ms | 1,450.09 ms across five waits |
| Shipyard purchase completion | 215.08 ms | 346.58 ms across two waits |

The two largest stacks both reach `MinecraftServer.saveEverything` and wait in `ChunkMap.saveAllChunks` on a `CompletableFuture.join`. These are observed save barriers, rather than an inference from the log's generic lag warning. Ship metadata read/write accounts for 86 of 804 server-thread CPU samples. The largest GC pause in the whole JVM is 26.31 ms; that measurement does not explain a multi-second stall on its own.

The recording includes startup, development fixtures and a client in the same JVM as the dedicated server thread. It is a targeted diagnosis, not a public-server throughput benchmark or a reproduction of the remote player's network. Summary: `pilot/profile-summary.json`; long-wait stacks: `pilot/profile-long-waits.txt`, both under that run directory. Compressed recordings are preserved; the large derived JSON export was removed.

The next performance pass should stage and coalesce durable saves while retaining the journal's required completion boundaries. Capture immutable metadata on the server thread, keep world access there, and await any background serialization or flush before advancing destructive travel stages. Validate that change with process-halt recovery tests and a standalone-server recording. Removing a save barrier without that ordering proof would risk ships or inventories.

## Paste follow-up

No schematic importer or item-frame paste implementation was found in the shipped main/client sources or scripts. The recorded errors came from command-based pastes. Check supporting blocks, spawn order and the attachment NBT expected by Minecraft 26.3 in that producer. The existing pasted builds remain available for checking whether frames are missing.
