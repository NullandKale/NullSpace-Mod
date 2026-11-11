# Public alpha preparation

The intended server is open to everyone, with authenticated Minecraft accounts. Hosting is provider independent. This is a release preparation record, not an announcement that the alpha is published or a server is running.

## Distribution

Use a **Modrinth mod project** for the Fabric JAR, then a matching client modpack so testers can install the exact versions together. Mark the first release **Alpha**, install on both client and server, and declare Fabric API as required. A GitHub Release is also useful for versioned artifacts and issue links once a remote repository is configured. Neither project has been created from this workspace.

The pinned dependencies were checked against the official catalogs on November 4, 2026:

| Component | Version |
| --- | --- |
| Minecraft | 26.3 |
| Java | 25 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.164.0+26.3 |
| Fabric API Modrinth version | LEklt8GT |

Fabric marks this loader version as not stable. It is the version used by this repository's tests; do not silently substitute another loader in the first package. Revalidate if changing it. [Fabric server installation](https://fabricmc.net/use/server/), [Modrinth version API](https://docs.modrinth.com/api/operations/createversion/).

Licensing remains pending. The requested policy allows monetized servers and videos, while selling the mod or a fork needs approval. Review [the license proposal](release-license-proposal.md) before adding a root `LICENSE` and license metadata. Do not label this policy MIT, GPL or an unmodified noncommercial license.

## Multiplayer requirements

Use a normal Fabric dedicated server, **not** [`scripts/run/server.ps1`](../../scripts/run/server.ps1), [`scripts/run/dev.ps1`](../../scripts/run/dev.ps1) or [`scripts/run/local-server.ps1`](../../scripts/run/local-server.ps1). Those are development tools. The local multiplayer test uses offline identities on loopback and is not a production configuration.

1. Install Java 25 and the pinned Fabric server launcher. Let Fabric obtain Minecraft and its libraries through the official installer; do not redistribute Minecraft binaries in our server pack.
2. Copy the release's non-sources NullSpace JAR and pinned Fabric API JAR into `mods/`. Do not include the `nullspace-test` mod, developer launch arguments, Gradle caches, saves or test worlds.
3. Start once to generate server files. Read Minecraft's EULA and accept it yourself if you agree. The release launcher must not accept it for you.
4. Set the properties below before allowing connections. Use a fresh world for the public alpha. These start settings apply to new arrivals, and do not replace existing players' ships.
5. Install the same Minecraft, Fabric API and NullSpace versions on every client. Open TCP 25565 on the host, configure any provider firewall, and leave RCON disabled unless you explicitly configure and secure it.
6. Start with `java -Xms2G -Xmx6G -jar fabric-server-launch.jar nogui`, adjusting memory to the rented machine and observed use. A service manager should save and stop gracefully instead of repeatedly force-killing the process.

```properties
online-mode=true
white-list=false
enforce-whitelist=false
server-ip=
server-port=25565
enable-rcon=false
enable-query=false
op-permission-level=2
gamemode=survival
force-gamemode=false
hardcore=false
pvp=false
view-distance=8
simulation-distance=6
level-name=world
motd=NullSpace public alpha
nullspace-space-start=true
nullspace-starting-ship=rivet_bolt
```

The small Rivet Bolt is the default public survival start. Players still choose their ship in single-player world creation; encounter variety must not override that choice. Change `nullspace-starting-ship` to a catalog preset if the server wants a different start. The server starts newcomers above a seeded planet, with a functioning ship, food, fuel, suit and guide. Terra's landing site is created at startup, independently of which planet a player starts above.

An open server still needs an operator able to moderate and restore backups. These settings do not add a claim or anti-grief system. Publicly accessible ships and salvage are not automatically protected property. Decide and publish the server's rules before launch.

## Backups and updates

Back up the **whole world directory**, including all dimensions, `players`, and `nullspace`. The ship store (`ships-a.nbt` and `ships-b.nbt`) and the transfer journals must stay consistent with the physical world. Copying only the overworld or only the store files is not a valid backup. The store is saved with the world, so `/save-off` before a live copy keeps it consistent with the chunks, and `/save-on` afterwards. Stop cleanly for the first backup/restore rehearsal and restore into a separate directory to verify it boots and preserves ships and inventories.

Keep versioned server/mod files, properties and the approved license alongside that backup. Test updates on a restored copy first. Run no development world-reset scripts on the server, and do not regenerate players' builds to refresh encounters.

### When something is stuck

Do not delete files from `nullspace/transfers/` by hand. Journals are deleted by the server once both store copies include them, so a few recent ones are normal. Use the commands instead (operators only):

| Command | What it does |
| --- | --- |
| `/nullspace recovery list` | Journals that could not be recovered, ships whose controller disagrees with the store, and store copies that were damaged at start |
| `/nullspace recovery show <id>` | The operation, its ships, owner and crew, and what the journal holds |
| `/nullspace recovery retry <id>` | Run the rollback again, for example after a missing mod is installed again |
| `/nullspace recovery restore <id> <pad or space>` | Rescue a readable MOVE to a clear registered pad or free space slot, protected by a BUILD journal |
| `/nullspace recovery export <id>` | Write the hull as a structure file with the ship's records as text under `nullspace/rescue/<id>/`, to rebuild it by hand |
| `/nullspace recovery discard <id>` | Accept the world as it is and release the locks. For a controller check it clears the stale stamp |

Anything listed stays locked: its area cannot be built into, and its ships cannot travel, be recalled or be stored. Operators see a notice when they join. A journal set aside lives in `nullspace/transfers/quarantine/` with a `.reason.txt` file.

If a store copy was damaged, the server keeps the damaged file (`ships-a.nbt.damaged-<time>`), starts from the other copy, replays committed journals onto it and tells operators. Changes since the last world save can be missing, the same as chunks. If **both** copies are unreadable the server refuses to start and names the files: restore `ships-a.nbt` or `ships-b.nbt` from the world backup.

The pad registry `nullspace-pads.json` works the same way on a smaller scale: the registry it replaced is kept as `nullspace-pads.previous.json`, and an unreadable registry is kept as `nullspace-pads.json.damaged-<time>` while the previous copy is loaded. If you edit it by hand, a mistake that makes it unreadable on `/nullspace reload-pads` is handled the same way.

## Release checks

Record each check against the actual packaged JAR hash. A green unit run is not a substitute for a packaged server boot or a two-player playthrough.

| Gate | Evidence required |
| --- | --- |
| Build and assets | JAR builds; full cube/16px asset checks pass; no test entrypoints in release metadata |
| Unit tests and measured coverage | JUnit results plus JaCoCo HTML/XML; explicitly distinguish unit-only coverage from native/GPU execution |
| Native behavior | Exact server-test inventory completes, including Terra landing, loose-block confirmation, power, fuel, rooms, docking and encounters |
| Real client | Graphical entrypoint completion receipts, screenshots, world creation and actual UI actions |
| Two players | Independent starts/actions, session authorization, shared ship travel, docking/boarding, disconnect/reconnect and blackouts |
| Durability | Launch/landing and shipyard process-halt matrices; preserve inventories and authoritative identities |
| Packaging | Fresh standalone Fabric server starts from the release files, rather than Loom's development classpath |
| Operations | Authenticated joins, external connection, graceful restart, backup/restore rehearsal, moderation and server rules |
| Compatibility | Record OS/GPU and renderer backend actually tested; default compute renderer needs OpenGL 4.3 |
| Publication | Approved license, project owner/contact, issue reporting location, versioned client install instructions and known limitations |

The automated local multiplayer suite uses offline test identities. A successful local test cannot establish that online account authentication or an external hosting firewall works. Linux hosting, sustained public-player load and alternative GPUs require their own evidence; do not claim they were tested by the Windows runs.
