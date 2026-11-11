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

## Hosted server

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

Back up the **whole world directory**, including all dimensions, `players`, and `nullspace`. Ship metadata and transfer journals must remain consistent with the physical world. Copying only the overworld or only `ships.nbt` is not a valid backup. Stop cleanly for the first backup/restore rehearsal and restore into a separate directory to verify it boots and preserves ships and inventories.

Keep versioned server/mod files, properties and the approved license alongside that backup. Test updates on a restored copy first. Never delete live transfer journals to work around a travel error, run development world-reset scripts on the server, or regenerate players' builds to refresh encounters.

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
