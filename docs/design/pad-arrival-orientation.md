# Landing with the entrance toward the Pad Computer

The November 12 playtest found that every ship lands in its existing physical orientation. The agreed correction is for the main entrance to face the Pad Computer. This is a landing bug fix within the freeze. The player-facing rule is settled; this document records the implementation and recovery checks. The transform, saved-state changes and recovery integration are implemented; local integration acceptance is complete.

The MOVE path applies one saved transform to blocks and occupants, including rollback and operator restore. Changing a rendered heading alone would leave the real door on the wrong side.

## Arrival rule

Use the Pad Computer's position on the ring to determine the arrival side. A computer on the west edge requires the entrance to face west, regardless of which way the computer block itself faces. The docking base defines the entrance's outward direction; the door block's facing can change independently.

Generated designs already have one docking entrance. Keep a stable main entrance identity for custom ships with several ports; do not select a different entrance because a list or cache changed order. Use the existing primary docking-port choice initially, then preserve its identity through rotation. If it is destroyed, use the existing valid-port fallback. This does not require another player-facing design step.

Apply the required quarter turn to the physical ship. Check the rotated footprint against the pad before any mutation; a ship that no longer fits must refuse arrival with an explanation. Preserve the existing rules for dropped items, fluids, protected blocks and occupied arrival space. Recall and deployment must use the same arrival rule.

## One transform throughout MOVE

Use one transform for block cells and another application of the same transform for continuous positions. A block rotates about its centre, so using the block-coordinate formula directly on a player's position introduces a one-block error. Keep quarter turns exact rather than using floating-point trigonometry for block coordinates.

The transfer review must cover:

- The hull template's positions, dimensions and block states, including doors, stairs, beds, chests and directional machines. Keep inventories, unopened loot seeds and machine contents exact.
- Block entity memories that refer to moved world positions, including hive flower memories. Retain the existing rule for references to things that stay behind.
- Scheduled block and fluid ticks, with their delay, priority and order.
- Online and offline crew positions and headings, pending arrival targets, cargo, riding relationships, hanging decorations and leash posts.
- The controller location, local docking-port identities, device positions and stored battery energy. Review position-keyed fuel debt and other saved system settings.
- The saved air field. Rotate cell quantities into the new grid without refilling, venting or moving air into another room.
- Bed placement identities and player respawn bindings. Both the local bed coordinate and its saved heading must follow the hull.

## Recovery and compatibility

Record the transform and exact destination bounds before PREPARED. Old journals without a transform keep their translation-only meaning.

Recovery must load, reserve and clear the rotated destination, then restore the original source snapshot and records. Quarantine locks, passenger authority checks and operator `restore` cleanup must use those destination bounds too. Do not derive them by adding the old translation to source corners after a quarter turn.

The source snapshot remains unrotated for rollback. Record changes must contain the matching rotated air, devices, crew, ports and respawn state for committed replay. An interrupted landing must recover exactly one authoritative ship and one copy of its occupants and inventories.

## Verification

The combined local receipt is `build/verification/remaining-final-20261112-140609/final-result.json`. All 625 unit cases, 276 native mod cases, 32 selected graphical entrypoints, multiplayer, sixteen MOVE and ten BUILD crash/restart checkpoints pass with retained results and affected fixture corrections.

- [x] Four pad edges, with entrance direction different from bow and computer facing.
- [x] Rectangular footprint: a required turn fits swapped dimensions; an invalid orientation refuses before mutation.
- [x] Launch and repeated landing without orientation or placement drift, plus the retained store/deploy/title cycles.
- [x] Template/state rotation review and native comparisons of captured hull blocks and cargo. Flower memories use the same position transform; bed, device, computer and air records retain their identities and quantities.
- [x] Online/offline crew and yaw, mounts, hanging decorations and leashes, including real multiplayer return and reconnect.
- [x] Unit checks for battery energy, device coordinates, saved air and respawn bindings. Unknown air cells retain their sentinel value.
- [x] Scheduled work follows transformed positions. Destination fluid/tick rollback remains covered by landing-clearance and crash checks.
- [x] Rotated landing interruptions at all MOVE stages, damaged newest copies, quarantine bounds and operator restore. Existing journals retain translation-only behavior.

The actual graphical trip, claim, storage and multiplayer flows pass. Detailed manual checks for paired containers, beds, hive memories, cabin air and leaving through the entrance remain in the [rc2 playtest checklist](../release/0.5.2-rc2-playtest.md); automated acceptance does not establish how those flows feel. Performance measurements remain deferred.
