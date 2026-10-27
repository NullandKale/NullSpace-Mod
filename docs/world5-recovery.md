# World 5 character recovery — 2026-10-26

The reported symptom was a crash followed by spawning with an empty inventory. Inspection found the original survival character intact. Development launches were choosing random names, and therefore different offline UUIDs.

| Session | Offline UUID | Saved character |
| --- | --- | --- |
| `Player451`, last substantial play session | `efe2ab2d-072a-337a-9d65-10da4f48c969` | Survival, level 17, full health, 18 occupied inventory slots, aboard the ship in `nullspace:space` |
| `Player501`, latest reload | `c6d05243-c020-3d93-ad43-aa68ef2f4bcb` | Survival, level 0, full health, empty inventory, overworld spawn |

The original position is approximately `19.40, 97.00, 5.00` in space. Inventory includes the iron axe/pickaxe/shovel, 33 iron ingots, 12 coal, food, torches, cobblestone, dirt, sticks, and saplings. The original compressed player file parses successfully. Its ship exists in saved metadata, and all nine retained transfer journals are `COMMITTED`. At the first inspection the inventory was intact. The later login described below overwrote that character with the empty host state, requiring restoration from the verified backup. No transfer replay is needed.

A read-only region check successfully decompressed all **15,043 stored payloads across 47 region files**, with no detected allocation or compression errors. At the saved player position, the feet/head cells are air and the supporting floor is dark-oak planks. Two region files end immediately after their complete final payload without unused sector padding; those payloads also decode successfully. Evidence is `build/recovery/world5-inspection.json`. This checks the saved data, rather than claiming an interactive play session was run on the original world.

Before repair work, all 130 save files (105,515,728 bytes) were copied to `build/recovery/world5-20261026-182224`. Every copied file was verified against the source with SHA-256. The world session lock was checked for exclusive access before copying. The original save's player files, chunks, inventories, and journals were left untouched.

`build.gradle` now sets `--username Player451` for `runClient`, `runPlaytest`, and `runShipLab`. Launch normally with `./dev.ps1` or `./playtest.ps1` and open World 5. This stabilizes the launch UUID, but is insufficient on its own when the world still points to a different single-player owner; see the follow-up below. A deliberate alternative identity is available with `-PdevUsername=AnotherName`.

Loom's resolved argument providers were checked for all three launch tasks: each contains exactly one `--username Player451`. A second check confirms that `-PdevUsername=IndependentTest` deliberately overrides all three. Evidence is in `build/recovery/profile-default.log` and `build/recovery/profile-override.log`.

The retained logs establish the identity change. They do not establish the cause of the reported crash: the substantial session logs a normal shutdown at 16:31; the 17:07 attempt ends during world loading without a retained crash report; the 17:08 reload uses the new empty character. The initial name-only fix did not recover the character; the follow-up below corrects that diagnosis. It is not evidence that an unidentified crash has been reproduced or repaired.

During verification at 18:38, the system disk filled completely and Minecraft failed to save disposable test chunks/logs. This is a confirmed save-failure condition, but its role in the earlier crash is unproven. Removing 38 old generated server-test worlds freed about 5 GB. Windows GameTest launches now retain four previous generated sessions, skip actively locked sessions and redirected paths, and avoid player-save/backup directories. The retention checks cover recent/active/closed sessions and protected folders.

The backup remains available for a separate repair if another save problem appears. Do not roll back the whole world to recover this inventory; restore only the affected character files from the verified backup and ensure the world owner points to that character.

## Follow-up: single-player ownership and actual character restoration

The 20:31 login used the corrected name `Player451` but still appeared at spawn. Minecraft 26.3's `PlayerList.loadPlayerData` follows `level.dat` ? `Data.singleplayer_uuid` for the single-player host, even when the current launch UUID differs. The first backup's owner pointer was `Player501`. The next login therefore loaded that empty character, then saved it under `Player451`, replacing both its live `.dat` and `.dat_old` files with the empty state. The launch-name fix alone was incomplete.

The verified 18:22 backup retains the original `Player451` data: level 17, all 18 occupied inventory slots, original tool durability, and position `19.3969110053, 97, 4.9957393732` in `nullspace:space`. Before restoration, the current save was independently backed up and hash-verified at `build/recovery/world5-before-character-restore-20261026-203429`.

A disposable graphical test addon under `build/recovery/world5-character-probe` loads a repaired copy with the actual Minecraft login path. It checks the stored owner, actual player UUID, dimension, position, experience, every original item slot/count/tool damage, and membership in the saved ship. Both the first load and a subsequent save/close/fresh-process reload passed. Logs contain `RECOVERY_PROBE_VERIFIED` with the exact original UUID, position, and level; success markers are `first-load.passed` and `fresh-process-reload.passed`.

After both live checks passed, the original `.dat` and `.dat_old` were atomically restored from the verified backup while holding the world session lock. Both restored files match the original SHA-256 hashes. Comparing the entire live save with its pre-restoration backup confirms that **only those two player files changed**. `level.dat` already points to `Player451` after the latest login, so it needed no further edit. Terrain, ship data, world time, other players, and journals were not rolled back. The final receipt is `build/recovery/world5-character-restoration.json`.

Reopen World 5 using the normal fixed-identity development launch. The expected restored character is aboard the ship in `nullspace:space`, at approximately `19.40, 97.00, 5.00`, with level 17 and the original inventory.
