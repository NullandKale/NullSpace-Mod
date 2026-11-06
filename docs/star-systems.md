# Saved star systems

Star systems exist automatically when the server starts. Open **Destination > Regions**, search for a system or body, set the destination and **Jump**. No generation button is needed.

Sol and Vega keep their current planets, saves and stations. Four seeded systems add twelve regions: each has an inner rocky planet, an outer ocean planet and that planet's moon. Names, star color and size, planet sizes and orbital arrangement vary with the world seed. Each body has its own landable surface and starter landing site. **Launch** returns to the correct orbit.

Orbital views use the same star, planet and moon identities throughout a system, translated around the selected body. Surface skies reference that same celestial body. Visiting a new region also activates its normal Waypoint and Manufacturer Exchange population.

The star chart is saved once. Restarting, opening navigation or visiting again never rerolls it. Existing scene edits, stations, landing pads, terrain and resources are preserved.

This pass uses a bounded chart and twelve separately allocated flat surface dimensions. Terrain uses the current rocky, ocean and moon templates. It does not yet generate an unlimited galaxy, terrain biomes or evolving celestial orbits.

Implementation: `starSystems` in `nullspace/ships.nbt` holds the versioned system plan. `localScenes` holds each resolved orbital view. The `star_system_slots.json` content catalog reserves surface identities; their visible names and celestial details come from the saved world chart. Navigation clients receive the server's destination list rather than generating a chart locally.

[Verification record](star-systems-verification.json): 506 unit tests, 159 mod server checks and all 22 graphical scenarios passed, including a generated planet landing, relaunch, moon jump and return home.
