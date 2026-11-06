# Stock ship power and encounter docking

The reported Horizon had two stock generators, six air supplies, a camera and a scanner. Its saved requests were controls 1, air 12, doors 1, engines 3 and sensors 2. Generation was 16. Idle doors reserved a unit, and engines consumed their allocation even without thrust, leaving the scanner without power after the batteries ran down.

Power now follows actual demand. Idle doors and engines can report available capacity without withholding that capacity from working systems. Engines draw electrical power when thrust is requested. Default priority is controls, air, doors, cameras/scanners, engines, then weapons. Explicit custom priority orders remain authoritative. Energy conservation, generator fuel consumption and battery charge/discharge limits still apply.

For this Horizon, controls, air and sensors need 15 units. The remaining output supports propulsion; batteries cover additional loads. Increasing generator output allows more thrust. The controller names the control system's power status rather than implying that every machine is powered. Equipment diagnostics distinguish an allocation set to zero from power failing to reach the system.

The player's log also showed successful investigations followed by selecting a planet landing pad. Investigate now selects an available docking port on the encountered vessel. The next action is **Dock**, then open the door and walk through. The paid journey's original destination remains saved separately and **Resume journey** restores it.

Opening the controller on an older paused encounter selects its dock once. Subsequent deliberate destination changes are respected. This is applied through normal gameplay; no save edits or world reset are required.

Restart with ordinary `dev.ps1`, open the controller and press **Dock** when the selected encounter berth is ready. `--restart` creates a new world and is unnecessary for this fix.

The [verification receipt](power-and-encounter-verification.json) records 529 unit tests, all 177 unique mod server cases and 25 graphical entrypoints. Two test assertions were corrected during verification: battery discharge is measured with actual thrust demand, and preserved modules are counted in inactive entity sections as well as tracked sections. The checked production JAR was identical across the graphical, server and focused follow-up checks. Disposable test worlds were cleaned; the player's save was read for diagnosis and retained.
