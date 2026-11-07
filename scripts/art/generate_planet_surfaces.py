"""Generate the P9 planet surface pool from the vanilla game data.

Usage: python scripts/art/generate_planet_surfaces.py <minecraft-common jar>

Writes, under src/main/resources/data/nullspace:
  worldgen/biome/planet/<type>_<biome>.json   NullSpace copies of vanilla biomes. Vanilla
                                               structures only spawn in vanilla biomes, so
                                               planets get no villages or temples.
  worldgen/material_rule/planet/<type>.json   Surface blocks for each planet type.
  worldgen/noise_settings/planet_<type>.json  Vanilla overworld terrain with that type's sea
                                               level, fluid and surface blocks.
  dimension/surface_<type>_<slot>.json        The pool slots, plus Sol's Luna and Ares.

It also copies those dimensions into the game-test world preset
(src/gametest/resources/.../flat_all_dimensions.json), which lists every dimension explicitly.

The pool sizes must match GalaxyPlan.SURFACE_POOL; a unit test checks the files.
"""

import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "src", "main", "resources", "data", "nullspace")

# type: (pool size, breathable, sea level, fluid, biomes as (vanilla id, share of temperature range))
TYPES = {
    "desert": (4, False, -63, "minecraft:water",
               [("desert", 4), ("badlands", 2), ("eroded_badlands", 1), ("wooded_badlands", 1)]),
    # Frozen seas: packed ice fills everything below sea level and never melts.
    "ice": (4, False, 63, "minecraft:packed_ice",
            [("ice_spikes", 2), ("frozen_peaks", 2), ("snowy_slopes", 2), ("frozen_ocean", 1)]),
    "lava": (3, False, 40, "minecraft:lava", [("basalt_deltas", 1), ("soul_sand_valley", 1)]),
    "ocean": (3, True, 96, "minecraft:water",
              [("deep_ocean", 5), ("warm_ocean", 3), ("mushroom_fields", 1)]),
    # A low sea keeps the jungle world mostly land.
    "jungle": (2, True, 46, "minecraft:water", [("jungle", 2), ("bamboo_jungle", 1), ("swamp", 1)]),
    "barren": (6, False, -63, "minecraft:water", [("stony_peaks", 1), ("stony_shore", 1)]),
}

# Sol's fixed surfaces use the same terrain as their type.
FIXED = {"sol_luna": "barren", "sol_ares": "desert"}

AIRLESS_SKY = "#101420"
AIRLESS_FOG = "#2a2f3a"
OVERWORLD_CARVERS = ["minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon"]


def block(name):
    return {"type": "minecraft:block", "result_state": name}


def when(condition, then):
    return {"type": "minecraft:condition", "if_true": condition, "then_run": then}


def noise(low, high, then):
    return when({"type": "minecraft:noise_threshold", "noise": "minecraft:surface",
                 "min_threshold": low, "max_threshold": high}, then)


def biome_is(*names):
    return {"type": "minecraft:biome", "biome_is": [f"nullspace:planet/{n}" for n in names]}


def seq(*rules):
    return {"type": "minecraft:sequence", "sequence": list(rules)}


def surface_rule(kind):
    """Blocks above the preliminary surface for each planet type."""
    if kind == "desert":
        bad = biome_is("desert_badlands", "desert_eroded_badlands", "desert_wooded_badlands")
        return seq(
            when(bad, seq(
                when("minecraft:on_floor", block("minecraft:red_sand")),
                when("minecraft:under_floor", {"type": "minecraft:bandlands"}),
                {"type": "minecraft:bandlands"})),
            when("minecraft:on_floor", block("minecraft:sand")),
            when("minecraft:under_floor", block("minecraft:sandstone")),
            when("minecraft:deep_under_floor", block("minecraft:sandstone")))
    if kind == "ice":
        return seq(
            when("minecraft:on_floor", seq(
                when("minecraft:not_underwater", seq(
                    noise(-0.2, 0.2, block("minecraft:packed_ice")),
                    block("minecraft:snow_block"))),
                block("minecraft:gravel"))),
            when("minecraft:under_floor", block("minecraft:packed_ice")))
    if kind == "lava":
        return seq(
            when("minecraft:on_floor", seq(
                noise(-0.15, 0.15, block("minecraft:magma_block")),
                noise(0.45, 10, block("minecraft:blackstone")),
                block("minecraft:basalt"))),
            when("minecraft:under_floor", block("minecraft:blackstone")),
            when("minecraft:deep_under_floor", block("minecraft:basalt")))
    if kind == "ocean":
        return seq(
            when(biome_is("ocean_mushroom_fields"), seq(
                when("minecraft:on_floor", seq(
                    when("minecraft:not_underwater", block("minecraft:mycelium")),
                    block("minecraft:dirt"))),
                when("minecraft:under_floor", block("minecraft:dirt")))),
            when("minecraft:on_floor", seq(
                when("minecraft:not_underwater", block("minecraft:sand")),
                noise(0.3, 10, block("minecraft:gravel")),
                noise(-10, -0.6, block("minecraft:clay")),
                block("minecraft:sand"))),
            when("minecraft:under_floor", block("minecraft:sandstone")))
    if kind == "jungle":
        return seq(
            when("minecraft:on_floor", seq(
                when("minecraft:not_underwater", block("minecraft:grass_block")),
                block("minecraft:mud"))),
            when("minecraft:under_floor", block("minecraft:dirt")))
    if kind == "barren":
        return seq(
            when("minecraft:on_floor", seq(
                noise(-0.25, 0.1, block("minecraft:andesite")),
                noise(0.5, 10, block("minecraft:tuff")),
                block("minecraft:gravel"))),
            when("minecraft:under_floor", block("minecraft:gravel")))
    raise ValueError(kind)


def material_rule(kind):
    return seq(
        "minecraft:bedrock_floor",
        "minecraft:overworld/copper_ore_vein",
        "minecraft:overworld/iron_ore_vein",
        when({"type": "minecraft:above_preliminary_surface"}, surface_rule(kind)),
        "minecraft:overworld/underground")


def full():
    return [-1.0, 1.0]


def write(path, data):
    path = os.path.join(OUT, *path.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def biome(vanilla, breathable):
    b = json.loads(vanilla)
    attributes = b.setdefault("attributes", {})
    # Nether biome carvers only suit the nether's shape.
    b["carvers"] = OVERWORLD_CARVERS
    if not breathable:
        # Nothing lives on an airless world.
        attributes.pop("minecraft:gameplay/natural_mob_spawns", None)
        attributes["minecraft:visual/sky_color"] = AIRLESS_SKY
        attributes["minecraft:visual/fog_color"] = AIRLESS_FOG
    attributes.pop("minecraft:gameplay/can_pillager_patrol_spawn", None)
    return b


def dimension(kind):
    _, _, _, _, biomes = TYPES[kind]
    total = sum(w for _, w in biomes)
    entries, start = [], -1.0
    for name, weight in biomes:
        end = start + 2.0 * weight / total
        entries.append({
            "biome": f"nullspace:planet/{kind}_{name}",
            "parameters": {"temperature": [round(start, 4), round(end, 4)], "humidity": full(),
                           "continentalness": full(), "erosion": full(), "depth": 0.0,
                           "weirdness": full(), "offset": 0.0}})
        start = end
    return {"type": "minecraft:overworld",
            "generator": {"type": "minecraft:noise", "settings": f"nullspace:planet_{kind}",
                          "biome_source": {"type": "minecraft:multi_noise", "biomes": entries}}}


def main(jar):
    z = zipfile.ZipFile(jar)
    read = lambda p: z.read(f"data/minecraft/{p}").decode("utf-8")
    overworld = json.loads(read("worldgen/noise_settings/overworld.json"))
    for kind, (pool, breathable, sea, fluid, biomes) in TYPES.items():
        for name, _ in biomes:
            write(f"worldgen/biome/planet/{kind}_{name}.json",
                  biome(read(f"worldgen/biome/{name}.json"), breathable))
        write(f"worldgen/material_rule/planet/{kind}.json", material_rule(kind))
        settings = dict(overworld)
        settings["material_rule"] = f"nullspace:planet/{kind}"
        settings["sea_level"] = sea
        settings["default_fluid"] = fluid
        write(f"worldgen/noise_settings/planet_{kind}.json", settings)
        for slot in range(pool):
            write(f"dimension/surface_{kind}_{chr(ord('a') + slot)}.json", dimension(kind))
    for address, kind in FIXED.items():
        write(f"dimension/{address}.json", dimension(kind))
    preset_path = os.path.join(ROOT, "src", "gametest", "resources", "data", "minecraft", "worldgen",
                               "world_preset", "flat_all_dimensions.json")
    with open(preset_path, encoding="utf-8") as f:
        preset = json.load(f)
    dims = {k: v for k, v in preset["dimensions"].items()
            if not k.startswith("nullspace:") or k == "nullspace:space"}
    folder = os.path.join(OUT, "dimension")
    for name in sorted(os.listdir(folder)):
        if name.endswith(".json") and name != "space.json":
            with open(os.path.join(folder, name), encoding="utf-8") as f:
                dims["nullspace:" + name[:-5]] = json.load(f)
    preset["dimensions"] = dims
    with open(preset_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(preset, f, indent=2)
        f.write("\n")
    print("planet surfaces written")


if __name__ == "__main__":
    main(sys.argv[1])
