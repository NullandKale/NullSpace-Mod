# Two-player development sandbox

Run from PowerShell:

```powershell
.\testt-server.ps1
```

The launcher compiles once, opens a dedicated server in its own terminal, waits for startup, and opens two normal Minecraft clients connected automatically. Their windows occupy the left and right halves of the active monitor. You can move them afterward. These are interactive players, without scripted GameTests.

The server binds to `127.0.0.1`, uses an available local port, and allows the two fixed offline players `NullSpace_One` and `NullSpace_Two`. Their identities, inventories and locations remain the same across launches. The launcher accepts the EULA for this local sandbox. Keep using `server.ps1` for the separate ordinary development server.

By default, new players receive a Sparrow in orbit above a random planet. Options:

```powershell
.\testt-server.ps1 -Instance mule-demo -StartingShip mule
.\testt-server.ps1 -Instance vanilla -VanillaStart
.\testt-server.ps1 -Instance fresh-space
.\testt-server.ps1 -Port 25565
```

`-Instance` chooses a separate named world and profiles. It creates new data rather than deleting the previous world. Changing the starting ship or vanilla option affects new players; use a new instance to try a different opening with the two fixed players.

World data is kept at `run-multiplayer/<instance>/server/world`. Client profiles are beside it in `client-1` and `client-2`. Launch arguments, process IDs, window bounds and client logs are in the instance's `launches/<session>/` directory; native server logs remain in `server/logs`. These interactive saves are outside automated world cleanup. Your `dev.ps1` saves are separate.

Close each client when done. The server terminal stays available; type **stop** there for a saved shutdown. Starting the same active instance again is rejected. The clients use windowed mode so they can be arranged beside each other; other existing graphics preferences are retained.

For launcher validation, `scripts/Test-LocalPlay.ps1` checks arguments, profiles, configuration and window geometry. `testt-server.ps1 -SmokeTest` creates a temporary sandbox under `build`, checks both connections and visible windows, then requests a saved server shutdown and removes its generated world. Logs remain for diagnosis.

[Verification](local-multiplayer-sandbox-verification.json) records real two-client runs for space and vanilla starts. Both players connected automatically, all four observed windows matched their requested left/right positions, both server processes exited cleanly after `stop`, and the temporary worlds were removed.
