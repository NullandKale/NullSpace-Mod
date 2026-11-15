# Warp core

Status: early design, not built, and not settled. Written November 14 from a design talk; the open questions are the point of this page.

## Why

Stations need a better power source, and player-made stations need something hard to get so that they are an end-game goal. Today a station's power comes from generators and batteries ([`PowerBus`](../../src/main/java/com/nullandkale/nullspace/model/power/PowerBus.java) shares it between the systems). The nether star already exists only at the top of the loot: it is in the singularity treasure at danger 3 (pulsar and black hole sites), so it is rare and tied to danger without any extra gate.

## The idea

- A **warp core** is a custom beacon, built, not crafted, from colored glass. It takes on the color of the glass. It uses a nether star, powers its station and gives a beacon effect to it, and a station needs exactly one.
- Generated warp cores (in derelicts) only ever use bright colors. A player can build any color.
- The core's beam is a thing to deal with: **the beam may touch vacuum but may never touch breathable air** in the station's air system. If it ever touches air, containment has failed, and the core spawns **violet slimes and violet blazes in the core's color** until the leak is sealed. Sealing a gap with glass or a solid block fixes it, which keeps it Minecraft-like.
- The beam may travel in more than one direction (all six, or four); how many is undecided, and so is exactly how it is blocked.
- A derelict's damage pass can punch holes in a core's shaft, so a wrecked station can come with a leaking core.

## Stowaways connection

The violet slime and violet blaze exist already ([`Stowaways`](../../src/main/java/com/nullandkale/nullspace/content/entity/Stowaways.java)), placed for now in danger 3 derelicts. Their texture is a fixed violet. A core of any color needs the mobs tinted per instance, which means a grey base texture and a color stored on the entity (like a dyed sheep), drawn by [`StowawayRenderers`](../../src/client/java/com/nullandkale/nullspace/render/entity/StowawayRenderers.java). The vanilla slime and blaze are untouched, so planets and the Nether stay vanilla.

## Open questions

1. Does the core replace generators for stations, or are generators kept as a weak fallback so a new station can run before anyone has a nether star? (Leaning to keep a fallback.)
2. Does the beam go four or six directions, and what ends it: a solid block, glass, or a length limit?
3. Is the leak a slow spawn up to a cap until sealed, or one burst when it opens? (Leaning to slow, with a cap.)
4. Does the color change anything besides the mobs and the look, or is it purely cosmetic? (Leaning to cosmetic until there is a reason for a tuning table.)
5. What is the build? The shape, size and glass rules, and how the game checks that a built core is valid.
6. Ships: stations only, or ships too?
7. How is the existing station air system asked "is this cell breathable?" for every cell on the beam path, and how often?

Nothing here is a commitment until the build and the beam rules are settled.
