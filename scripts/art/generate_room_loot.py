"""Generate the room loot tables for found vessels (tiny ships and stations design, "Loot").

Usage: python scripts/art/generate_room_loot.py

Writes data/nullspace/loot_table/site/:
  normal/<room>.json             What a working room of that use holds.
  derelict/<room>_<tier>.json    The same stock worn, plus finds, tier 0-3 by danger.
  derelict/<room>_dead_<tier>.json  Power failure: electronics swapped for raw materials.

Only found vessels hold loot. Rooms with variants have one key per variant (stores_0..2,
growing_bay_0..1, garden_0..1). Fuel stays the main reward, but is rationed to a few rooms so a
whole vessel never holds much more than a stack.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_site_loot import OUT, item, pool, table  # noqa: E402

DAMAGE = {"type": "minecraft:set_damage", "damage": {"type": "minecraft:uniform", "min": 0.3, "max": 0.8}}


def damaged(name, weight=1, low=1, high=1):
    return item(name, weight, low, high, [DAMAGE])


def potion(name, weight, kind):
    return item(name, weight, extra=[{"type": "minecraft:set_potion", "id": "minecraft:" + kind}])


def enchanted(weight, low, high):
    return item("book", weight, extra=[{"type": "minecraft:enchant_with_levels",
                                        "levels": {"type": "minecraft:uniform", "min": low, "max": high},
                                        "options": "#minecraft:on_random_loot"}])


def tool(name, weight, low=1, high=1):
    return ("damaged", name, weight, low, high)


# An entry is (item, weight, low, high); ("damaged", ...) is a worn tool or piece of armor.
FUEL = [item("coal", 6), item("charcoal", 2)]
SPOILED = {"bread": "rotten_flesh", "cooked_beef": "rotten_flesh", "cooked_porkchop": "rotten_flesh",
           "cooked_chicken": "rotten_flesh", "apple": "rotten_flesh", "carrot": "poisonous_potato",
           "potato": "poisonous_potato", "cake": "rotten_flesh", "honey_bottle": "glass_bottle",
           "golden_carrot": "carrot", "sweet_berries": "rotten_flesh"}
ELECTRONICS = {"nullspace:circuit_board", "nullspace:machine_casing", "nullspace:battery",
               "nullspace:local_scanner"}
# What a power failure leaves instead of the electronics.
DEAD = [("iron_ingot", 3, 1, 4), ("copper_ingot", 3, 1, 4), ("redstone", 3, 2, 8), ("iron_nugget", 2, 3, 9)]


def make(entry, scale=1.0, tier=0):
    """Builds a loot entry from a spec tuple, its counts grown a little with the tier."""
    damaged_item = entry[0] == "damaged"
    name, weight, low, high = entry[1:5] if damaged_item else entry[:4]
    low = max(1, round(low * scale))
    high = max(low, round(high * scale))
    return damaged(name, weight, low, high) if damaged_item else item(name, weight, low, high)


def worn(entry):
    """The state a working room's stock is in after the wreck: food spoiled, tools damaged, fewer of them."""
    damaged_item = entry[0] == "damaged"
    name, weight, low, high = entry[1:5] if damaged_item else entry[:4]
    if not damaged_item and name in SPOILED:
        return (SPOILED[name], max(1, weight), 1, max(1, high // 2))
    if damaged_item or name.endswith(("_pickaxe", "_axe", "_shovel", "_hoe", "_helmet", "_chestplate", "_leggings",
                                      "_boots", "bow", "crossbow", "shield")):
        return ("damaged", name, weight, 1, 1)
    return (name, weight, max(1, low // 2), max(1, high // 2))


def block(name, weight, low, high, tier=0):
    return (name, weight, low, high, tier)


# Rooms: normal stock, building blocks (with the tier they start at), fuel (rolls, count), finds
# (entry, tier it starts at), and for some rooms a guaranteed pool (food in the galleys).
ROOMS = {
    "bridge": dict(
        normal=[("map", 3, 1, 2), ("compass", 2, 1, 1), ("clock", 2, 1, 1), ("spyglass", 1, 1, 1), ("paper", 4, 2, 6), ("book", 2, 1, 2)],
        blocks=[block("glass", 4, 4, 12), block("iron_bars", 2, 4, 10), block("quartz_block", 2, 4, 10, 1)],
        finds=[(("nullspace:circuit_board", 4, 1, 2), 0), (("nullspace:local_scanner", 2, 1, 1), 1), (("nullspace:system_upgrade", 3, 1, 1), 2), (("nullspace:advanced_system_upgrade", 2, 1, 1), 3)],
        dead=True),
    "engineering": dict(
        normal=[("redstone", 4, 2, 8), ("nullspace:circuit_board", 2, 1, 2), ("nullspace:machine_casing", 2, 1, 2), ("copper_ingot", 2, 2, 6), ("nullspace:battery", 1, 1, 1)],
        blocks=[block("copper_block", 2, 1, 3), block("iron_block", 1, 1, 2, 1), block("polished_andesite", 3, 8, 24)],
        fuel=(1, 2, 2, 6),
        finds=[(("nullspace:circuit_board", 4, 1, 3), 0), (("nullspace:system_upgrade", 3, 1, 1), 2), (("nullspace:advanced_system_upgrade", 2, 1, 1), 3), (("nullspace:air_canister", 2, 1, 2), 0)],
        dead=True),
    "stores_0": dict(
        normal=[("paper", 3, 2, 6), ("string", 3, 2, 6), ("white_wool", 2, 2, 6), ("candle", 1, 1, 3), ("iron_nugget", 2, 3, 9)],
        blocks=[block("oak_planks", 3, 8, 24), block("stone_bricks", 3, 8, 24), block("glass", 2, 4, 12)],
        finds=[(("iron_ingot", 3, 2, 6), 0), (("copper_ingot", 3, 2, 6), 0), (("gold_nugget", 2, 3, 9), 1), (("gold_ingot", 1, 1, 3), 2)]),
    "stores_1": dict(
        normal=[("wheat_seeds", 4, 4, 12), ("beetroot_seeds", 2, 2, 8), ("bone_meal", 3, 2, 8), ("iron_hoe", 1, 1, 1), ("carrot", 2, 2, 5)],
        blocks=[block("oak_planks", 3, 8, 24), block("hay_block", 2, 2, 6), block("moss_block", 1, 2, 6, 1)],
        finds=[(("pumpkin_seeds", 3, 2, 6), 0), (("melon_seeds", 3, 2, 6), 0), (("cocoa_beans", 2, 2, 6), 1), (("golden_carrot", 2, 1, 3), 2)]),
    "stores_2": dict(
        normal=[("nullspace:machine_casing", 2, 1, 2), ("nullspace:circuit_board", 2, 1, 2), ("iron_nugget", 3, 3, 9), ("iron_pickaxe", 1, 1, 1), ("redstone", 3, 2, 6)],
        blocks=[block("polished_andesite", 3, 8, 24), block("iron_block", 1, 1, 2, 1), block("copper_block", 1, 1, 3, 1)],
        fuel=(1, 1, 2, 4),
        finds=[(("nullspace:machine_casing", 3, 1, 3), 0), (("nullspace:circuit_board", 3, 1, 3), 1), (("nullspace:system_upgrade", 2, 1, 1), 2), ("damaged", "diamond_pickaxe", 1, 1, 1)]),
    "maintenance": dict(
        normal=[("iron_ingot", 3, 2, 6), ("redstone", 3, 2, 8), ("nullspace:circuit_board", 2, 1, 2), ("iron_pickaxe", 1, 1, 1), ("nullspace:air_canister", 1, 1, 1), ("iron_nugget", 3, 3, 9)],
        blocks=[block("polished_andesite", 3, 8, 24), block("iron_bars", 2, 4, 10), block("iron_block", 1, 1, 2, 1)],
        fuel=(1, 1, 2, 4),
        finds=[(("nullspace:machine_casing", 3, 1, 3), 0), (("copper_ingot", 2, 3, 8), 0), (("nullspace:system_upgrade", 3, 1, 1), 2), (("nullspace:advanced_system_upgrade", 1, 1, 1), 3)],
        dead=True),
    "commons": dict(
        normal=[("book", 3, 1, 2), ("paper", 3, 2, 6), ("cake", 1, 1, 1), ("poppy", 2, 1, 3), ("glass_bottle", 2, 1, 3)],
        blocks=[block("lantern", 3, 2, 6), block("iron_chain", 2, 2, 6), block("white_carpet", 3, 4, 12), block("bookshelf", 2, 2, 6, 1)],
        finds=[(("gold_nugget", 3, 3, 9), 0), (("golden_apple", 2, 1, 1), 2), (("music_disc_cat", 1, 1, 1), 2), (("music_disc_mall", 1, 1, 1), 3), (("emerald", 3, 1, 3), 1), (("gold_ingot", 2, 1, 3), 1)]),
    "kitchen": dict(
        normal=[("bread", 4, 2, 6), ("cooked_beef", 2, 1, 3), ("carrot", 3, 2, 5), ("potato", 3, 2, 5), ("apple", 2, 1, 3), ("honey_bottle", 1, 1, 2)],
        blocks=[block("smoker", 1, 1, 1, 1), block("barrel", 2, 1, 2), block("oak_planks", 2, 8, 16), block("white_concrete", 2, 8, 24)],
        finds=[(("honey_bottle", 2, 1, 3), 0), (("golden_carrot", 2, 1, 3), 1), (("cooked_porkchop", 3, 3, 8), 0), (("cake", 1, 1, 1), 2)],
        food=["bread", "carrot", "potato"]),
    "observation": dict(
        normal=[("spyglass", 1, 1, 1), ("map", 3, 1, 2), ("amethyst_shard", 2, 2, 6), ("book", 2, 1, 2)],
        blocks=[block("glass", 4, 8, 24), block("quartz_block", 2, 4, 12), block("lantern", 2, 2, 6), block("sea_lantern", 1, 1, 3, 2)],
        finds=[(("amethyst_cluster", 3, 1, 2), 1), (("spyglass", 2, 1, 1), 0), (("echo_shard", 2, 1, 1), 3), (("nullspace:local_scanner", 1, 1, 1), 2)]),
    "archive": dict(
        normal=[("book", 4, 2, 5), ("paper", 4, 3, 8), ("ink_sac", 2, 1, 4), ("writable_book", 1, 1, 1)],
        blocks=[block("bookshelf", 4, 3, 9), block("oak_planks", 2, 8, 16), block("lantern", 1, 1, 3)],
        finds=[(("__enchanted__", 4, 5, 15), 0), (("lapis_lazuli", 3, 3, 9), 1), (("__enchanted__", 3, 15, 30), 2), (("__enchanted__", 3, 25, 39), 3)]),
    "workshop": dict(
        normal=[("iron_ingot", 3, 2, 6), ("copper_ingot", 2, 2, 6), ("redstone", 3, 2, 10), ("nullspace:circuit_board", 2, 1, 2), ("iron_axe", 1, 1, 1), ("nullspace:air_canister", 1, 1, 1)],
        blocks=[block("oak_planks", 3, 8, 24), block("stone_bricks", 3, 8, 24), block("copper_block", 1, 1, 3, 1), block("iron_block", 1, 1, 2, 1)],
        finds=[(("iron_ingot", 3, 2, 6), 0), ("damaged", "diamond_pickaxe", 2, 1, 1), (("sentry_armor_trim_smithing_template", 1, 1, 1), 2), (("nullspace:system_upgrade", 3, 1, 1), 2), (("nullspace:circuit_board", 3, 1, 3), 1)]),
    "foundry": dict(
        normal=[("iron_ingot", 4, 2, 8), ("copper_ingot", 3, 2, 8), ("gold_ingot", 2, 1, 3), ("raw_iron", 2, 2, 6)],
        blocks=[block("stone_bricks", 3, 8, 24), block("polished_blackstone", 2, 8, 24), block("iron_block", 1, 1, 3, 1), block("gold_block", 1, 1, 2, 2)],
        fuel=(1, 1, 2, 6),
        finds=[(("diamond", 3, 1, 2), 2), (("gold_ingot", 3, 2, 6), 1), (("raw_gold", 2, 2, 6), 0), (("netherite_scrap", 2, 1, 2), 3)]),
    "cargo_hold": dict(
        normal=[("copper_ingot", 3, 2, 8), ("iron_ingot", 2, 1, 4), ("glass", 2, 2, 6), ("string", 1, 2, 6), ("paper", 1, 2, 6)],
        blocks=[block("stone_bricks", 3, 8, 32), block("oak_planks", 3, 8, 32), block("polished_deepslate", 2, 8, 24), block("copper_block", 1, 2, 4, 1), block("iron_block", 1, 1, 3, 1)],
        fuel=(1, 1, 2, 6),
        finds=[(("gold_nugget", 3, 3, 9), 0), (("emerald", 3, 1, 4), 1), (("diamond", 2, 1, 2), 1), (("gold_block", 2, 1, 2), 2), (("diamond", 2, 2, 4), 3)]),
    "receiving_bay": dict(
        normal=[("copper_ingot", 2, 2, 6), ("iron_ingot", 2, 2, 6), ("string", 2, 2, 6), ("paper", 2, 2, 6), ("glass", 2, 2, 6)],
        blocks=[block("oak_planks", 3, 8, 24), block("stone_bricks", 3, 8, 24), block("light_gray_concrete", 2, 8, 24)],
        fuel=(1, 1, 2, 4),
        finds=[(("emerald", 3, 1, 4), 0), (("shulker_box", 1, 1, 1), 2), (("diamond", 2, 1, 2), 2), (("gold_ingot", 3, 2, 5), 1)]),
    "growing_bay_0": dict(
        normal=[("wheat_seeds", 4, 4, 12), ("bone_meal", 3, 2, 8), ("wheat", 3, 3, 8), ("carrot", 3, 2, 5), ("beetroot_seeds", 2, 2, 6)],
        blocks=[block("moss_block", 2, 2, 8), block("oak_planks", 2, 8, 16), block("glass", 3, 8, 24), block("dirt", 2, 8, 24)],
        finds=[(("golden_carrot", 3, 1, 3), 1), (("pumpkin_seeds", 3, 2, 6), 0), (("melon_seeds", 3, 2, 6), 0), (("sweet_berries", 2, 3, 8), 0), (("cocoa_beans", 2, 2, 6), 1)],
        food=["carrot", "sweet_berries", "beetroot"]),
    "growing_bay_1": dict(
        normal=[("oak_sapling", 3, 1, 3), ("apple", 4, 2, 5), ("cocoa_beans", 2, 2, 6), ("bone_meal", 3, 2, 8), ("jungle_sapling", 1, 1, 2)],
        blocks=[block("oak_log", 3, 8, 24), block("oak_leaves", 2, 4, 12), block("glass", 3, 8, 24), block("moss_block", 1, 2, 6)],
        finds=[(("golden_apple", 2, 1, 1), 2), (("birch_sapling", 3, 1, 3), 0), (("sweet_berries", 2, 3, 8), 0), (("glow_berries", 2, 3, 8), 1)],
        food=["apple", "sweet_berries"]),
    "garden_0": dict(
        normal=[("wheat_seeds", 3, 4, 10), ("shears", 1, 1, 1), ("white_wool", 3, 2, 6), ("lead", 2, 1, 2), ("apple", 2, 1, 3)],
        blocks=[block("oak_fence", 3, 8, 16), block("grass_block", 2, 4, 12), block("oak_planks", 2, 8, 16), block("hay_block", 2, 2, 6)],
        finds=[(("honeycomb", 3, 2, 5), 0), (("name_tag", 2, 1, 2), 1), (("lead", 3, 2, 4), 0), (("golden_apple", 1, 1, 1), 3)],
        food=["apple", "sweet_berries", "carrot"]),
    "garden_1": dict(
        normal=[("oak_sapling", 3, 1, 3), ("poppy", 2, 2, 6), ("dandelion", 2, 2, 6), ("moss_block", 2, 2, 6), ("apple", 2, 1, 3)],
        blocks=[block("oak_log", 3, 8, 24), block("birch_log", 2, 8, 16), block("moss_block", 2, 4, 12), block("flowering_azalea", 1, 1, 3, 1)],
        finds=[(("glow_berries", 3, 3, 8), 0), (("spore_blossom", 2, 1, 2), 1), (("honeycomb", 2, 2, 5), 0), (("golden_apple", 1, 1, 1), 3)],
        food=["apple", "glow_berries", "sweet_berries"]),
    "lab": dict(
        normal=[("glass_bottle", 3, 2, 6), ("nether_wart", 2, 1, 4), ("spider_eye", 2, 1, 3), ("blaze_powder", 1, 1, 2), ("redstone", 2, 2, 6)],
        blocks=[block("glass", 4, 8, 24), block("quartz_block", 2, 4, 12), block("white_concrete", 2, 8, 24), block("brewing_stand", 1, 1, 1, 1)],
        finds=[("__potion__", "healing", 3), ("__potion__", "regeneration", 3), (("glistering_melon_slice", 2, 1, 3), 0), ("__potion__", "strong_healing", 3, 3), ("__potion__", "strength", 2, 3)]),
    "cabin": dict(
        normal=[("leather_chestplate", 1, 1, 1), ("book", 2, 1, 2), ("clock", 1, 1, 1), ("compass", 1, 1, 1), ("gold_nugget", 3, 2, 8)],
        blocks=[block("white_carpet", 3, 4, 12), block("white_wool", 3, 4, 12), block("oak_planks", 2, 8, 16), block("lantern", 2, 2, 4)],
        finds=[(("emerald", 3, 1, 3), 0), (("gold_ingot", 3, 2, 5), 1), (("sentry_armor_trim_smithing_template", 1, 1, 1), 2), (("diamond", 2, 1, 2), 2)]),
    "bunk_room": dict(
        normal=[("white_wool", 4, 2, 6), ("paper", 3, 2, 6), ("leather", 3, 1, 3), ("apple", 2, 1, 3), ("bread", 2, 1, 3)],
        blocks=[block("white_wool", 3, 8, 24), block("white_carpet", 3, 4, 12), block("oak_planks", 2, 8, 16), block("lantern", 1, 2, 4)],
        finds=[(("gold_nugget", 3, 3, 9), 0), (("iron_ingot", 2, 2, 5), 0), (("emerald", 2, 1, 2), 1), (("music_disc_stal", 1, 1, 1), 3)]),
    "operations": dict(
        normal=[("map", 3, 1, 2), ("book", 2, 1, 2), ("redstone", 3, 2, 8), ("comparator", 1, 1, 2), ("repeater", 1, 1, 2)],
        blocks=[block("polished_andesite", 3, 8, 24), block("glass", 3, 8, 16), block("iron_bars", 2, 4, 10), block("quartz_block", 2, 4, 10, 1)],
        finds=[(("nullspace:circuit_board", 4, 1, 3), 0), (("nullspace:local_scanner", 2, 1, 1), 1), (("nullspace:system_upgrade", 3, 1, 1), 2), (("nullspace:advanced_system_upgrade", 2, 1, 1), 3)],
        dead=True),
    "armory": dict(
        normal=[("arrow", 4, 4, 16), ("bow", 1, 1, 1), ("iron_chestplate", 1, 1, 1), ("shield", 1, 1, 1), ("chainmail_helmet", 1, 1, 1)],
        blocks=[block("polished_blackstone", 3, 8, 24), block("iron_bars", 2, 4, 10), block("iron_block", 1, 1, 2, 1)],
        finds=[(("nullspace:armored_space_helmet", 2, 1, 1), 2), (("nullspace:armored_space_chestplate", 1, 1, 1), 2), (("arrow", 3, 8, 24), 0), ("damaged", "diamond_chestplate", 1, 1, 1)]),
    "lookout": dict(
        normal=[("spyglass", 1, 1, 1), ("arrow", 3, 4, 16), ("firework_rocket", 3, 2, 6), ("map", 2, 1, 2), ("bow", 1, 1, 1)],
        blocks=[block("glass", 3, 8, 24), block("oak_planks", 2, 8, 16), block("lantern", 2, 2, 4)],
        finds=[(("firework_rocket", 3, 4, 12), 0), (("bow", 1, 1, 1), 1), (("spyglass", 2, 1, 1), 0), (("echo_shard", 1, 1, 1), 3)]),
    "eva_prep": dict(
        normal=[("nullspace:space_helmet", 1, 1, 1), ("nullspace:space_chestplate", 1, 1, 1), ("nullspace:air_canister", 3, 1, 3), ("string", 2, 2, 6)],
        blocks=[block("iron_bars", 2, 4, 10), block("polished_andesite", 2, 8, 24), block("white_concrete", 2, 8, 16)],
        finds=[(("nullspace:air_canister", 4, 1, 3), 0), (("nullspace:armored_space_leggings", 2, 1, 1), 2), (("nullspace:armored_space_boots", 2, 1, 1), 2), (("nullspace:space_leggings", 2, 1, 1), 1)]),
    "infirmary": dict(
        normal=[("paper", 3, 2, 6), ("string", 2, 2, 6), ("honey_bottle", 2, 1, 2), ("apple", 2, 1, 3), ("glass_bottle", 2, 1, 3)],
        blocks=[block("white_concrete", 3, 8, 24), block("white_carpet", 3, 4, 12), block("glass", 2, 8, 16), block("lantern", 1, 2, 4)],
        finds=[("__potion__", "healing", 3), ("__potion__", "regeneration", 2, 1), (("golden_apple", 2, 1, 1), 2), (("honey_bottle", 3, 1, 3), 0)]),
    "brig": dict(
        normal=[("iron_bars", 3, 4, 10), ("lead", 2, 1, 3), ("name_tag", 1, 1, 1), ("iron_nugget", 3, 3, 9)],
        blocks=[block("polished_blackstone", 3, 8, 24), block("iron_bars", 3, 4, 12), block("stone_bricks", 2, 8, 24), block("iron_chain", 1, 2, 6)],
        finds=[(("iron_ingot", 3, 2, 6), 0), (("diamond", 2, 1, 2), 2), (("emerald", 3, 1, 3), 1), (("gold_ingot", 3, 2, 5), 1), (("__enchanted__", 2, 10, 25), 2), (("netherite_scrap", 1, 1, 1), 3)]),
}

DEAD_ROOMS = {key for key, spec in ROOMS.items() if spec.get("dead")}
FIND_ROLLS = {0: (0, 1), 1: (1, 1), 2: (1, 2), 3: (2, 3)}


def find_entry(spec, tier):
    """A find as a loot entry; potions and enchanted books are spelled specially."""
    if spec[0] == "__potion__":
        return potion("potion", spec[2], spec[1])
    if spec[0] == "damaged":
        return damaged(spec[1], spec[2], spec[3], spec[4])
    name, weight, low, high = spec
    if name == "__enchanted__":
        return enchanted(weight, low, high)
    return item(name, weight, low, high)


def finds(spec, tier, dead):
    """The finds a room can give at this tier. Potions are ("__potion__", kind, weight[, first tier]), worn tools
    ("damaged", item, weight, low, high) start at tier 2, and the rest are ((item, weight, low, high), first tier)."""
    out = []
    for find in spec["finds"]:
        if find[0] == "__potion__":
            if tier >= (find[3] if len(find) > 3 else 0):
                out.append(find_entry(find, tier))
        elif find[0] == "damaged":
            if tier >= 2:
                out.append(find_entry(find, tier))
        else:
            entry, start = find
            if tier >= start and not (dead and entry[0] in ELECTRONICS):
                out.append(find_entry(entry, tier))
    return out


def scale(tier):
    return 1.0 + 0.25 * tier


def normal_table(key, spec):
    entries = [make(e) for e in spec["normal"]]
    return table(pool(entries, 2, 4))


def derelict_table(key, spec, tier, dead):
    pools = []
    if "fuel" in spec:
        rolls_lo, rolls_hi, low, high = spec["fuel"]
        pools.append(pool([item("coal", 6, low, high), item("charcoal", 2, low, high)], rolls_lo, rolls_hi))
    if "food" in spec:
        foods = {"bread": "bread", "carrot": "carrot", "potato": "potato", "apple": "apple", "sweet_berries": "sweet_berries",
                 "beetroot": "beetroot", "glow_berries": "glow_berries"}
        pools.append(pool([item(foods[f], 1, 1, 3) if tier >= 1 else item("rotten_flesh", 1, 1, 3)
                           for f in spec["food"]], 1, 1))
    salvage = []
    for entry in spec["normal"]:
        w = worn(entry)
        if dead and w[0] in ELECTRONICS:
            continue
        salvage.append(make(w, scale(tier), tier))
    if dead:
        salvage.extend(make(d, scale(tier), tier) for d in DEAD)
    pools.append(pool(salvage, 1, 2 if tier < 2 else 3))
    blocks = [make(block_entry[:4], scale(tier), tier) for block_entry in spec["blocks"] if block_entry[4] <= tier]
    pools.append(pool(blocks, 1, 2))
    options = finds(spec, tier, dead)
    low, high = FIND_ROLLS[tier]
    if options and high > 0:
        pools.append(pool(options, low, high))
    return table(*pools)


def write(path, data):
    full = os.path.join(OUT, path + ".json")
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main():
    count = 0
    for key, spec in ROOMS.items():
        write(f"normal/{key}", normal_table(key, spec))
        count += 1
        for tier in range(4):
            write(f"derelict/{key}_{tier}", derelict_table(key, spec, tier, False))
            count += 1
            if key in DEAD_ROOMS:
                write(f"derelict/{key}_dead_{tier}", derelict_table(key, spec, tier, True))
                count += 1
    print(f"{count} room loot tables written")


if __name__ == "__main__":
    main()
