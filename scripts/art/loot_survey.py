#!/usr/bin/env python3
"""Turns the loot survey (LootSurveyGameTest) into distribution tables.

Usage: python scripts/art/loot_survey.py build/loot-survey.json [--by-table] [--items 25]

The survey lists, for many random encounters of every kind, what each chest rolled, which items sit in frames, which
armor stands wear suits and which blocks were placed. This script weights the kinds like the game does and prints:

  1. what one encounter gives, by danger (mean and percentiles), split by source: chests, frames, suits, blocks
  2. which categories of item make up the haul
  3. the most common items
  4. with --by-table, how much each loot table gives per chest, the quickest way to find an over-generous table

With --html FILE it instead writes a self-contained local report with one row per item: how many an encounter gives at
each danger, how often an encounter has any, and (click a row) which kinds, tables and sources give it. Nothing is
published; the page is the template next to this script, scripts/art/loot_survey_report.html.

"Worth" is a rough lens in iron-ingot equivalents, not a game rule: edit WORTH to change it. Items that are not in it
count 0.25 each and are listed at the end so they can be classified. Filler (FILLER) is left out unless --with-filler.
"""
import json
import statistics
import sys
from collections import Counter, defaultdict

KIND_WEIGHT = {0: 5, 1: 35, 2: 35, 3: 20, 4: 5}  # EncounterKind weights
DANGERS = ["0", "1", "2", "3", "S"]

# Worth in iron-ingot equivalents.
WORTH = {
    "coal": .25, "charcoal": .2, "copper_ingot": .4, "iron_ingot": 1, "gold_ingot": 2, "diamond": 8, "emerald": 4, "lapis_lazuli": .5,
    "redstone": .25, "quartz": .5, "glass": .2, "iron_nugget": .11, "gold_nugget": .22, "netherite_scrap": 12, "netherite_ingot": 40,
    "amethyst_shard": .5, "glowstone_dust": .3, "ender_pearl": 3, "blaze_rod": 2, "slime_ball": .5, "string": .2, "leather": .4,
    "iron_block": 9, "copper_block": 3.6, "gold_block": 18, "diamond_block": 72, "emerald_block": 36, "redstone_block": 2.25, "lapis_block": 4.5,
    "iron_chain": 1.1, "iron_bars": .3, "hopper": 5, "piston": 4, "observer": 3, "dropper": 1.5, "dispenser": 1.5, "repeater": 1, "comparator": 3,
    "golden_apple": 12, "enchanted_golden_apple": 100, "experience_bottle": 3,
}
FOOD = {"bread", "apple", "carrot", "potato", "baked_potato", "beetroot", "melon_slice", "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_cod", "cooked_salmon", "beef", "porkchop", "chicken", "mutton", "cod", "salmon", "cookie", "pumpkin_pie", "mushroom_stew", "rabbit_stew", "beetroot_soup", "wheat", "sweet_berries", "glow_berries", "dried_kelp", "honey_bottle", "egg", "milk_bucket", "cake", "sugar", "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "bone_meal"}
CATEGORIES = [
    ("fuel", {"coal", "charcoal", "coal_block", "blaze_rod"}),
    ("metal/gems", {"copper_ingot", "iron_ingot", "gold_ingot", "diamond", "emerald", "lapis_lazuli", "quartz", "iron_nugget", "gold_nugget", "raw_iron", "raw_copper", "raw_gold", "netherite_scrap", "netherite_ingot", "amethyst_shard"}),
    ("food", FOOD),
    ("redstone/parts", {"redstone", "repeater", "comparator", "piston", "sticky_piston", "observer", "hopper", "dropper", "dispenser", "lever", "redstone_torch", "redstone_lamp", "glowstone_dust", "glowstone", "iron_chain", "iron_bars"}),
    ("building", {"glass", "glass_pane", "stone", "cobblestone", "stone_bricks", "polished_andesite", "smooth_stone", "terracotta", "oak_planks", "spruce_planks", "birch_planks", "oak_log", "sand", "bricks", "quartz_block", "torch", "lantern", "iron_door", "ladder", "chest", "barrel", "paper", "book", "string", "slime_ball", "leather"}),
]


# Filler fills chests and rooms so they look stocked; it is not meant to be useful, so the survey leaves it out unless
# asked (--with-filler). It stays in the loot tables on purpose.
FILLER = {
    "oak_planks", "oak_log", "oak_leaves", "oak_sapling", "birch_sapling", "jungle_sapling", "stone_bricks", "polished_andesite", "polished_blackstone", "light_gray_concrete", "white_concrete",
    "white_wool", "white_carpet", "glass", "glass_pane", "paper", "string", "dirt", "moss_block", "hay_block", "candle", "rotten_flesh", "poisonous_potato", "glass_bottle",
    "wheat_seeds", "melon_seeds", "pumpkin_seeds", "beetroot_seeds", "cocoa_beans", "bone_meal", "stone", "cobblestone", "sand", "gravel", "terracotta", "bricks", "ladder", "iron_bars",
}


def strip_filler(sites):
    """Removes filler items from every site's chests and frames, and filler blocks from its placed blocks."""
    for site in sites:
        site["frames"] = {k: v for k, v in site["frames"].items() if short(k) not in FILLER}
        site["blocks"] = {k: v for k, v in site["blocks"].items() if short(k) not in FILLER}
        for chests in site["loot"].values():
            for chest in chests:
                chest["items"] = {k: v for k, v in chest["items"].items() if short(k) not in FILLER}


def short(item):
    return item.split(":", 1)[-1]


def category(item):
    name = short(item)
    if name in FILLER:
        return "filler"
    for label, names in CATEGORIES:
        if name in names:
            return label
    if any(k in name for k in ("pickaxe", "axe", "shovel", "hoe", "sword", "helmet", "chestplate", "leggings", "boots", "bow", "arrow", "shears", "bucket", "flint_and_steel", "compass", "clock", "spyglass")):
        return "tools/gear"
    if name.startswith("nullspace") or "suit" in name:
        return "nullspace"
    if "potion" in name or "enchanted_book" in name or name.endswith("_bottle"):
        return "potions/books"
    return "other"


def worth(item, count):
    return WORTH.get(short(item), 0.25) * count


# Blocks the player can take apart for something of value; hull and trim do not count as loot.
BLOCK_ITEMS = {
    "minecraft:iron_block": {"minecraft:iron_block": 1}, "minecraft:copper_block": {"minecraft:copper_block": 1},
    "minecraft:furnace": {"minecraft:furnace": 1}, "minecraft:blast_furnace": {"minecraft:blast_furnace": 1}, "minecraft:smoker": {"minecraft:smoker": 1},
    "minecraft:barrel": {"minecraft:barrel": 1}, "minecraft:chest": {"minecraft:chest": 1}, "minecraft:crafting_table": {"minecraft:crafting_table": 1},
    "minecraft:redstone_lamp": {"minecraft:redstone_lamp": 1}, "minecraft:hopper": {"minecraft:hopper": 1}, "minecraft:piston": {"minecraft:piston": 1},
    "minecraft:iron_door": {"minecraft:iron_door": 1}, "minecraft:iron_bars": {"minecraft:iron_bars": 1}, "minecraft:anvil": {"minecraft:anvil": 1},
    "minecraft:smithing_table": {"minecraft:smithing_table": 1}, "minecraft:glass": {"minecraft:glass": 1}, "minecraft:bookshelf": {"minecraft:bookshelf": 1},
    "minecraft:lantern": {"minecraft:lantern": 1}, "minecraft:cauldron": {"minecraft:cauldron": 1}, "minecraft:brewing_stand": {"minecraft:brewing_stand": 1},
    "minecraft:wheat": {"minecraft:wheat": 1},
}
for ore in ("diamond_block", "emerald_block", "gold_block", "lapis_block", "redstone_block"):
    BLOCK_ITEMS["minecraft:" + ore] = {"minecraft:" + ore: 1}
WORTH_EXTRA = {"furnace": 1.5, "blast_furnace": 5, "smoker": 3, "barrel": .5, "chest": .5, "crafting_table": .3, "redstone_lamp": 1.5, "iron_door": 2, "anvil": 31, "smithing_table": 2.5, "bookshelf": 2, "lantern": .5, "cauldron": 7, "brewing_stand": 3, "glass": .2, "wheat": .25}


def block_worth(block, count):
    name = short(block)
    return (WORTH_EXTRA.get(name) or WORTH.get(name, 0.25)) * count


def percentile(values, p):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(p / 100 * len(ordered)))]


def site_totals(site, danger):
    chests = site["loot"][danger]
    items = Counter()
    for chest in chests:
        for item, count in chest["items"].items():
            items[item] += count
    frames = Counter({k: v for k, v in site["frames"].items()})
    blocks = Counter({k: v for k, v in site["blocks"].items() if k in BLOCK_ITEMS})
    return chests, items, frames, blocks


def item_stats(sites):
    """Per item: weighted mean count per encounter and chance of any, by danger, source, kind and table."""
    by_kind = defaultdict(list)
    for site in sites:
        by_kind[site["kind"]].append(site)
    weights = sum(KIND_WEIGHT.values())
    stats = {}

    def row(item):
        return stats.setdefault(item, {"mean": defaultdict(float), "any": defaultdict(float), "source": defaultdict(float), "kind": defaultdict(float), "table": defaultdict(float)})

    for kind, group in by_kind.items():
        share = KIND_WEIGHT[kind] / weights / len(group)
        for site in group:
            for danger in DANGERS:
                chests, items, frames, blocks = site_totals(site, danger)
                seen = set()

                def add(item, count, source, table=None):
                    r = row(item)
                    r["mean"][danger] += share * count
                    r["source"][danger, source] += share * count
                    r["kind"][kind, danger] += share * count
                    if table and danger in ("0", "3"):
                        r["table"][table, danger] += share * count
                    seen.add(item)

                for chest in chests:
                    for item, count in chest["items"].items():
                        add(item, count, "chests", chest["table"])
                for item, count in frames.items():
                    add(item, count, "frames")
                for block, count in blocks.items():
                    for item, per in BLOCK_ITEMS[block].items():
                        add(item, count * per, "placed blocks")
                for item in seen:
                    row(item)["any"][danger] += share
    return stats


def icon_finder():
    """Looks up an item's texture: the mod's own, then the game's item textures, then its block textures."""
    import base64
    import glob
    import os
    import zipfile
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    jars = [j for j in glob.glob(os.path.join(root, ".gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-*/*/minecraft-clientOnly-*.jar")) if "sources" not in j and ".backup" not in j]
    jar = zipfile.ZipFile(jars[0]) if jars else None
    mod = os.path.join(root, "src/main/resources/assets/nullspace/textures")

    def find(item):
        space, name = item.split(":", 1) if ":" in item else ("minecraft", item)
        name = {"enchanted_golden_apple": "golden_apple"}.get(name, name)
        candidates = [f"item/{name}", f"item/{name}_00", f"block/{name}", f"block/{name}_front", f"block/{name}_top", f"block/{name}_side", f"block/{name}_0", f"item/{name}_empty"]
        for c in candidates:
            if space == "nullspace":
                path = os.path.join(mod, c + ".png")
                if os.path.exists(path):
                    return "data:image/png;base64," + base64.b64encode(open(path, "rb").read()).decode()
            elif jar:
                try:
                    return "data:image/png;base64," + base64.b64encode(jar.read(f"assets/minecraft/textures/{c}.png")).decode()
                except KeyError:
                    pass
        return None

    return find


def write_html(path, sites, labels):
    """The loot report: sampled loot sets and one row per item, in a self-contained page. Nothing is published."""
    stats = item_stats(sites)
    find = icon_finder()
    full = {}
    for site in sites:
        for chests in site["loot"].values():
            for chest in chests:
                for item in chest["items"]:
                    full[short(item)] = item
        for item in site["frames"]:
            full[short(item)] = item
    rows = []
    for item, r in stats.items():
        full.setdefault(short(item), item)
        rows.append({
            "item": short(item), "category": category(item), "worth": WORTH_EXTRA.get(short(item), WORTH.get(short(item), 0.25)),
            "any": {d: round(r["any"][d], 3) for d in DANGERS},
            "source": {f"{d}|{k}": round(v, 3) for (d, k), v in r["source"].items()},
            "kind": {f"{labels[k]}|{d}": round(v, 3) for (k, d), v in r["kind"].items()},
            "table": {f"{t}|{d}": round(v, 3) for (t, d), v in r["table"].items()},
        })
    icons = {}
    for name, item in full.items():
        found = find(item)
        if found:
            icons[name] = found
    compact = []
    for site in sites:
        compact.append({
            "label": site["label"], "design": site["design"], "stationSize": site.get("stationSize"), "rooms": site.get("rooms"),
            "frames": {short(k): v for k, v in site["frames"].items()}, "suits": site["suits"], "armoredSuits": site["armoredSuits"],
            "loot": {d: [{"table": c["table"], "items": {short(k): v for k, v in c["items"].items()}} for c in chests] for d, chests in site["loot"].items()},
        })
    worth_map = {short(i): worth(i, 1) for i in full.values()}
    data = json.dumps({"rows": rows, "dangers": DANGERS, "kindList": [labels[k] for k in sorted(labels)], "siteCount": len(sites), "sites": compact, "icons": icons, "worth": worth_map}, separators=(",", ":"))
    template = open(__file__.replace("loot_survey.py", "loot_survey_report.html"), encoding="utf-8").read()
    with open(path, "w", encoding="utf-8") as out:
        out.write(template.replace("__DATA__", data))
    print(f"wrote {path}: {len(rows)} items, {len(icons)} icons, {len(sites)} sites")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--") and sys.argv[sys.argv.index(a) - 1] not in ("--html", "--items")]
    path = args[0] if args else "build/loot-survey.json"
    top = int(sys.argv[sys.argv.index("--items") + 1]) if "--items" in sys.argv else 25
    data = json.load(open(path))
    sites = data["sites"]
    if "--with-filler" not in sys.argv:
        strip_filler(sites)
    by_kind = defaultdict(list)
    for site in sites:
        by_kind[site["kind"]].append(site)
    labels = {s["kind"]: s["label"] for s in sites}
    if "--html" in sys.argv:
        write_html(sys.argv[sys.argv.index("--html") + 1], sites, labels)
        return
    print(("filler included; " if "--with-filler" in sys.argv else "filler left out; ") + f"{len(sites)} encounters ({data['sitesPerKind']} per kind); weighted {KIND_WEIGHT}")

    print("\n== 1. One encounter, by kind and danger ==")
    print("worth = iron-ingot equivalents; chest/frame/suit/block columns are the mean worth from each source")
    print(f"{'kind':26} {'dng':>3} {'chests':>6} {'stacks':>6} {'items':>6} | {'worth':>6} {'p10':>5} {'p50':>5} {'p90':>5} | {'chest':>6} {'frame':>6} {'suit':>6} {'block':>6}")
    overall = {d: defaultdict(float) for d in DANGERS}
    overall_samples = {d: [] for d in DANGERS}
    weights = sum(KIND_WEIGHT.values())
    for kind in sorted(by_kind):
        for danger in DANGERS:
            rows = []
            for site in by_kind[kind]:
                chests, items, frames, blocks = site_totals(site, danger)
                chest_worth = sum(worth(i, c) for i, c in items.items())
                frame_worth = sum(worth(i, c) for i, c in frames.items())
                suit_worth = site["suits"] * 6 + site["armoredSuits"] * 12  # a set of space armor, rough
                block_w = sum(block_worth(b, c) for b, c in blocks.items())
                rows.append((len(chests), sum(1 for c in chests for _ in c["items"]), sum(items.values()), chest_worth, frame_worth, suit_worth, block_w))
            total = [r[3] + r[4] + r[5] + r[6] for r in rows]
            mean = lambda i: statistics.mean(r[i] for r in rows)
            print(f"{labels[kind]:26} {danger:>3} {mean(0):6.1f} {mean(1):6.1f} {mean(2):6.0f} | {statistics.mean(total):6.0f} {percentile(total, 10):5.0f} {percentile(total, 50):5.0f} {percentile(total, 90):5.0f} | {mean(3):6.0f} {mean(4):6.0f} {mean(5):6.0f} {mean(6):6.0f}")
            share = KIND_WEIGHT[kind] / weights
            for name, i in (("chest", 3), ("frame", 4), ("suit", 5), ("block", 6)):
                overall[danger][name] += share * mean(i)
            overall[danger]["stacks"] += share * mean(1)
            overall[danger]["items"] += share * mean(2)
            overall_samples[danger].extend((t, share / len(rows)) for t in total)
    print("\nWeighted by how often each kind appears:")
    print(f"{'dng':>3} {'stacks':>7} {'items':>7} | {'worth':>7} {'p10':>6} {'p50':>6} {'p90':>6} | {'chest':>6} {'frame':>6} {'suit':>6} {'block':>6}")
    for danger in DANGERS:
        samples = sorted(overall_samples[danger])
        cumulative, marks = 0.0, {}
        for value, weight in samples:
            cumulative += weight
            for p in (10, 50, 90):
                marks.setdefault(p, value) if cumulative >= p / 100 else None
        o = overall[danger]
        print(f"{danger:>3} {o['stacks']:7.1f} {o['items']:7.0f} | {sum(o[k] for k in ('chest', 'frame', 'suit', 'block')):7.0f} {marks.get(10, 0):6.0f} {marks.get(50, 0):6.0f} {marks.get(90, 0):6.0f} | {o['chest']:6.0f} {o['frame']:6.0f} {o['suit']:6.0f} {o['block']:6.0f}")

    for danger in ("0", "3"):
        print(f"\n== 2. Haul by category, danger {danger} (mean items per encounter, weighted) ==")
        cats = defaultdict(float)
        counts = defaultdict(float)
        unknown = Counter()
        for kind, group in by_kind.items():
            share = KIND_WEIGHT[kind] / weights / len(group)
            for site in group:
                chests, items, frames, blocks = site_totals(site, danger)
                for item, count in list(items.items()) + list(frames.items()):
                    cats[category(item)] += share * count
                    counts[item] += share * count
                    if category(item) == "other":
                        unknown[item] += share * count
        total = sum(cats.values())
        for label, value in sorted(cats.items(), key=lambda kv: -kv[1]):
            print(f"  {label:16} {value:8.1f}  {100 * value / total:4.0f}%")
        print(f"  most common items at danger {danger}:")
        for item, value in sorted(counts.items(), key=lambda kv: -kv[1])[:top]:
            print(f"    {short(item):28} {value:7.2f}  ({category(item)})")
        if unknown and danger == "3":
            print("  unclassified items (worth 0.25 each): " + ", ".join(f"{short(i)}={v:.1f}" for i, v in unknown.most_common(15)))

    if "--by-table" in sys.argv:
        print("\n== 4. Per loot table: chests rolled, mean stacks, mean items, mean worth per chest ==")
        rolled = defaultdict(list)
        for site in sites:
            for danger in ("0", "1", "2", "3"):
                for chest in site["loot"][danger]:
                    rolled[chest["table"]].append((len(chest["items"]), sum(chest["items"].values()), sum(worth(i, c) for i, c in chest["items"].items())))
        print(f"{'table':32} {'n':>6} {'stacks':>7} {'items':>7} {'worth':>7} {'p90 worth':>9}")
        for table, rows in sorted(rolled.items(), key=lambda kv: -statistics.mean(r[2] for r in kv[1])):
            print(f"{table:32} {len(rows):6} {statistics.mean(r[0] for r in rows):7.1f} {statistics.mean(r[1] for r in rows):7.0f} {statistics.mean(r[2] for r in rows):7.1f} {percentile([r[2] for r in rows], 90):9.1f}")


if __name__ == "__main__":
    main()
