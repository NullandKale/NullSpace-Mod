"""Generate loot tables for generated sites (procedural generation design, "Rewards").

Usage: python scripts/generate_site_loot.py

Writes data/nullspace/loot_table/site/:
  <room>.json            Everyday supplies for each room type (engineering, galley, cargo,
                         workshop, crew, garden). Fuel comes first.
  treasure_<danger>.json One container per site, danger 0-3: cargo supplies plus rare vanilla
                         items that get better with danger.
  treasure_singularity   Black holes and pulsars: danger 3 plus a very rare elytra or nether star.
"""

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "data", "nullspace", "loot_table", "site")


def item(name, weight=1, low=1, high=1, extra=None):
    entry = {"type": "minecraft:item", "name": name if ":" in name else "minecraft:" + name}
    if weight != 1:
        entry["weight"] = weight
    modifiers = []
    if high > 1 or low != 1:
        modifiers.append({"type": "minecraft:set_count",
                          "count": {"type": "minecraft:uniform", "min": low, "max": high}})
    if extra:
        modifiers.extend(extra)
    if modifiers:
        entry["modifier"] = modifiers if len(modifiers) > 1 else modifiers[0]
    return entry


def book(weight, low, high):
    return item("book", weight, extra=[{"type": "minecraft:enchant_with_levels",
                                       "levels": {"type": "minecraft:uniform", "min": low,
                                                  "max": high},
                                       "options": "#minecraft:on_random_loot"}])


def pool(entries, low, high):
    return {"rolls": {"type": "minecraft:uniform", "min": low, "max": high}, "entries": entries}


def table(*pools):
    return {"type": "minecraft:chest", "pools": list(pools)}


FUEL = [item("coal", 6, 4, 12), item("charcoal", 2, 2, 8)]

ROOMS = {
    "engineering": table(pool(FUEL, 2, 3), pool([item("redstone", 3, 2, 8),
                                                 item("nullspace:circuit_board", 1, 1, 2),
                                                 item("nullspace:machine_casing", 1)], 0, 2)),
    "galley": table(pool([item("bread", 4, 2, 6), item("carrot", 3, 2, 5),
                          item("potato", 3, 2, 5), item("apple", 2, 1, 3),
                          item("cooked_beef", 1, 1, 3)], 2, 4)),
    "cargo": table(pool(FUEL, 1, 2), pool([item("copper_ingot", 3, 2, 8),
                                            item("iron_ingot", 2, 1, 4), item("glass", 2, 2, 6),
                                            item("string", 1, 2, 6), item("paper", 1, 2, 6)],
                                           2, 3)),
    "workshop": table(pool([item("iron_ingot", 3, 2, 6), item("redstone", 3, 2, 10),
                            item("nullspace:circuit_board", 2, 1, 2),
                            item("nullspace:air_canister", 1),
                            item("nullspace:system_upgrade", 1)], 2, 3)),
    "crew": table(pool([item("paper", 3, 2, 6), item("white_wool", 2, 1, 3),
                        item("book", 2, 1, 2), item("leather", 2, 1, 3),
                        item("compass", 1), item("clock", 1)], 1, 3)),
    "garden": table(pool([item("bread", 3, 2, 8), item("wheat_seeds", 3, 4, 12),
                          item("bone_meal", 2, 2, 8), item("melon_seeds", 1, 1, 4)], 2, 3),
                    pool(FUEL, 1, 1)),
}

TRIMS = ["coast", "dune", "sentry", "wild", "vex", "tide", "wayfinder", "raiser", "shaper", "host"]

RARE = {
    1: [book(4, 5, 20), item("golden_apple", 3), item("diamond", 2, 1, 2),
        item("shulker_shell", 1), item("nullspace:system_upgrade", 2)]
       + [item(t + "_armor_trim_smithing_template", 1) for t in TRIMS[:5]],
    2: [book(4, 15, 30), item("golden_apple", 3, 1, 2), item("diamond", 3, 1, 3),
        item("shulker_shell", 2, 1, 2), item("nullspace:system_upgrade", 3, 1, 2),
        item("amethyst_shard", 2, 2, 8)]
       + [item(t + "_armor_trim_smithing_template", 1) for t in TRIMS],
    3: [book(3, 25, 39), item("echo_shard", 3, 1, 3), item("totem_of_undying", 2),
        item("netherite_upgrade_smithing_template", 2), item("enchanted_golden_apple", 1),
        item("music_disc_otherside", 1), item("music_disc_pigstep", 1),
        item("diamond", 3, 2, 4), item("netherite_scrap", 2, 1, 2)],
}


def treasure(danger):
    pools = [pool(FUEL, 2, 3), pool(ROOMS["cargo"]["pools"][1]["entries"], 1, 2)]
    if danger >= 1:
        entries = list(RARE[1])
        if danger >= 2:
            entries = list(RARE[2])
        if danger >= 3:
            entries = list(RARE[2]) + list(RARE[3])
        pools.append(pool(entries, 1, danger))
    return table(*pools)


def write(name, data):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main():
    for name, data in ROOMS.items():
        write(name, data)
    for danger in range(4):
        write(f"treasure_{danger}", treasure(danger))
    singularity = treasure(3)
    singularity["pools"].append(pool([item("elytra", 1), item("nether_star", 1),
                                      {"type": "minecraft:empty", "weight": 18}], 1, 1))
    write("treasure_singularity", singularity)
    print("site loot written")


if __name__ == "__main__":
    main()
