# Automatic space population

The [saved star chart](star-systems.md) supplies both existing and generated systems. Entering a local space region discovers its orbital population automatically. Open **Destination → Ports**, choose a berth and press **Dock**. The list refreshes as places finish loading. Navigation and scanner controls never create a station.

Each region currently contains two public places:

| Place | Purpose | Layout |
| --- | --- | --- |
| Waypoint Station | Arrival, living space, workshop and engineering | Four connected room pieces, with seeded role and doorway variation; two exterior berths |
| Manufacturer Exchange | Visit the three manufacturer design styles | Rivet workshop, Halcyon observation and Bastion operations wings; two opposite berths |

This first pass reuses the existing compact templates. It does not add roaming ships, random events or the proposed full-size manufacturer harbors. Player-built station deployment and room kits remain ordinary construction controls.

## One saved region plan

World seed and region address determine site identities, layout seeds and candidate locations independently of the arriving player. Sites sit outside the orbital arrival area. Placement checks celestial bodies, asteroid fields and registered vessels, then tries a bounded set of alternate positions. Physical storage sections are separate from their simulated orbital positions and start in a reserved high range, leaving the normal launch allocator undisturbed.

The `spacePopulation` section of `nullspace/ships.nbt` saves a versioned site inventory. Existing Waypoint and Manufacturer Showcase identities are adopted before creating missing places. Their names, positions, construction, cargo, fuel and dock links stay intact.

Stations use the existing replayable construction plans. One ambient place is queued at a time; block placement is budgeted across ticks. Travel and active construction take priority over queuing another site. Completed stations expose real door nodes and docking ports through the normal discovery path. Construction elsewhere does not block an established boarding route.

Revisiting or restarting never repairs damage, resets supplies or creates another copy. Removing a registered generated station leaves a saved removal record. The normal generator does not respawn it. Explicit administrator regeneration remains a separate operation.

Legacy station creation commands remain development tools. They are absent from normal navigation menus, and old Find action packets only explain how to discover ports.

[Verification record](space-population-verification.json): 502 unit tests, 156 mod server checks and 22 graphical scenarios passed, including automatic discovery and a complete docking round trip.
