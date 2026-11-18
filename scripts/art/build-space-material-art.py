"""Writes the resources of the green gem and the green fluid: textures, models, blockstates, items, loot, recipes, lang entries and the geode.

The materials are in src/main/java/com/nullandkale/nullspace/content/SpaceMaterials.java and the fluid in GreenFluid.java. Run from the
repository root: python scripts/art/build-space-material-art.py
"""
from pathlib import Path
import json
import math
import random
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
ASSETS = RES / 'assets/nullspace'
DATA = RES / 'data'

# Nuclear green, from the glow of the heart to the dark of the facet.
CORE, LIGHT, MID, DARK, SHADE = (214, 255, 170), (140, 255, 80), (74, 214, 44), (36, 128, 28), (20, 72, 22)
BASALT = (52, 58, 68)
DEEP = (44, 168, 36)


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def save(image, rel):
    path = ASSETS / 'textures' / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def put(image, x, y, color, alpha=255):
    if 0 <= x < image.width and 0 <= y < image.height:
        image.putpixel((x, y), tuple(color) + (alpha,))


def gem_item():
    """A cut gem: a bright table on top, a crown of facets, a pointed pavilion, a dark outline and a glint."""
    image = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    shape = set()
    for y in range(2, 15):
        if y <= 5:
            a, b = 5 - (y - 2), 10 + (y - 2)
        else:
            t = (y - 6) / 8
            a, b = 2 + 5.5 * t, 13 - 5.5 * t
        for x in range(round(a), round(b) + 1):
            shape.add((x, y))
    for (x, y) in shape:
        if y <= 5:
            color = CORE if y == 2 and x <= 9 else LIGHT if x < 6 else MID if x < 9 else DARK
        else:
            color = LIGHT if x < 5 else MID if x < 8 else DARK if x < 10 else SHADE
        put(image, x, y, color)
    for (x, y) in list(shape):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in shape:
                put(image, x + dx, y + dy, SHADE)
    for x, y in ((5, 4), (6, 3), (5, 3), (4, 5)):
        put(image, x, y, CORE)
    return image


def cells(seed, count):
    """Random points on a torus, so a tile made of their cells joins at its edges."""
    random.seed(seed)
    return [(random.uniform(0, 16), random.uniform(0, 16)) for _ in range(count)]


def nearest(points, x, y):
    """The nearest point to (x, y) on the 16x16 torus, the gap to the second nearest, and that point's index."""
    best = []
    for i, (px, py) in enumerate(points):
        dx, dy = abs(x - px), abs(y - py)
        dx, dy = min(dx, 16 - dx), min(dy, 16 - dy)
        best.append((dx * dx + dy * dy, i))
    best.sort()
    return best[0][1], best[1][0] ** .5 - best[0][0] ** .5


def gem_block():
    """Four cut facets, each lit from the top left, with dark grooves between them and a bright speck on each."""
    image = Image.new('RGBA', (16, 16), SHADE + (255,))
    for cx in (0, 8):
        for cy in (0, 8):
            for y in range(7):
                for x in range(7):
                    color = DARK if x + y > 8 else LIGHT if x + y < 3 else MID
                    if x == 0 or y == 0:
                        color = LIGHT
                    put(image, cx + x + 1, cy + y + 1, color)
            for dx, dy in ((2, 2), (3, 2), (2, 3)):
                put(image, cx + dx, cy + dy, CORE)
    return image


def gem_cluster():
    """A whole block of crystal: a mass of tall, narrow facets packed edge to edge, each lit from the left with a dark seam around it,
    and bright glints at the top of the lit ones. There is no rock in it."""
    random.seed(31)
    points = [(random.uniform(0, 16), random.uniform(0, 16)) for _ in range(20)]
    tones = [random.choice((LIGHT, LIGHT, MID, MID, DARK)) for _ in points]
    image = Image.new('RGBA', (16, 16), MID + (255,))
    for y in range(16):
        for x in range(16):
            scored = []
            for i, (px_, py_) in enumerate(points):
                dx, dy = abs(x + .5 - px_), abs(y + .5 - py_)
                dx, dy = min(dx, 16 - dx), min(dy, 16 - dy)
                scored.append(((dx * 1.8) ** 2 + (dy * 0.55) ** 2, i))
            scored.sort()
            i = scored[0][1]
            gap = scored[1][0] ** .5 - scored[0][0] ** .5
            color = tones[i]
            if gap < 0.55:
                color = SHADE
            elif x + .5 < points[i][0] - 0.8 and color != DARK:
                color = LIGHT
            elif x + .5 > points[i][0] + 0.8 and color != LIGHT:
                color = DARK
            put(image, x, y, color)
    for i, (px_, py_) in enumerate(points):
        if tones[i] == LIGHT:
            put(image, int(px_) % 16, int(py_ - 2) % 16, CORE)
    return image


def fluid(frames, size, phase, alpha, stretch):
    """Slow rolling swells: sums of waves with whole wavelengths, so the tile joins at every edge and the loop is seamless."""
    image = Image.new('RGBA', (size, size * frames), (0, 0, 0, 0))
    px = image.load()
    for f in range(frames):
        t = 2 * math.pi * f / frames
        for y in range(size):
            for x in range(size):
                u, v = 2 * math.pi * x / size, 2 * math.pi * y / size
                w = math.sin(u + 2 * v * stretch + t) + math.sin(2 * u - v * stretch - t + phase) + 0.6 * math.sin(3 * u + 3 * v * stretch + 2 * t + phase)
                n = (w + 2.6) / 5.2
                color = DEEP if n < 0.3 else MID if n < 0.55 else LIGHT if n < 0.82 else CORE
                px[x, f * size + y] = color + (alpha,)
    return image


def bucket():
    """A steel pail with a rim and the green fluid showing at the top."""
    image = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    steel, light, edge = (186, 190, 198), (222, 226, 232), (84, 88, 98)
    for y in range(4, 15):
        inset = (y - 4) // 4
        for x in range(3 + inset, 13 - inset):
            put(image, x, y, light if x == 4 + inset else edge if x >= 11 - inset else steel)
        put(image, 3 + inset, y, edge)
        put(image, 12 - inset, y, edge)
    for x in range(3, 13):
        put(image, x, 4, edge)
        put(image, x, 3, light if x < 8 else steel)
    put(image, 3, 3, edge)
    put(image, 12, 3, edge)
    for y in range(5, 8):
        for x in range(4, 12):
            put(image, x, y, CORE if (y == 5 and x % 3 == 0) else LIGHT if (x + y) % 3 else MID)
    return image


def main():
    save(gem_item(), 'item/green_gem.png')
    save(bucket(), 'item/green_fluid_bucket.png')
    save(gem_block(), 'block/green_gem_block.png')
    save(gem_cluster(), 'block/green_gem_cluster.png')
    save(fluid(16, 16, 0.0, 200, 1), 'block/green_fluid_still.png')
    save(fluid(16, 16, 1.7, 215, 2), 'block/green_fluid_flow.png')
    for name, ticks in (('green_fluid_still', 12), ('green_fluid_flow', 8)):
        write_json(ASSETS / f'textures/block/{name}.png.mcmeta', {'animation': {'frametime': ticks, 'interpolate': True}})

    for name in ('green_gem_block', 'green_gem_cluster'):
        write_json(ASSETS / f'models/block/{name}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'nullspace:block/{name}'}})
        write_json(ASSETS / f'blockstates/{name}.json', {'variants': {'': {'model': f'nullspace:block/{name}'}}})
        write_json(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'nullspace:block/{name}'}})
    write_json(ASSETS / 'models/block/green_fluid.json', {'textures': {'particle': 'nullspace:block/green_fluid_still'}})
    write_json(ASSETS / 'blockstates/green_fluid.json', {'variants': {'': {'model': 'nullspace:block/green_fluid'}}})
    for name in ('green_gem', 'green_fluid_bucket'):
        write_json(ASSETS / f'models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'nullspace:item/{name}'}})
        write_json(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'nullspace:item/{name}'}})

    lang_path = ASSETS / 'lang/en_us.json'
    lang = json.loads(lang_path.read_text(encoding='utf-8'))
    lang.update({
        'item.nullspace.green_gem': 'Green Gem',
        'block.nullspace.green_gem_block': 'Block of Green Gem',
        'block.nullspace.green_gem_cluster': 'Green Gem Cluster',
        'block.nullspace.green_fluid': 'Green Fluid',
        'item.nullspace.green_fluid_bucket': 'Green Fluid Bucket',
    })
    lang_path.write_text(json.dumps(lang, indent=2) + '\n', encoding='utf-8')

    write_json(DATA / 'nullspace/loot_table/blocks/green_gem_block.json', {
        'type': 'minecraft:block',
        'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'nullspace:green_gem_block'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    write_json(DATA / 'nullspace/loot_table/blocks/green_gem_cluster.json', {
        'type': 'minecraft:block',
        'pools': [{'rolls': 1, 'entries': [{
            'type': 'minecraft:alternatives',
            'children': [
                {'type': 'minecraft:item', 'condition': 'minecraft:tool/can_silk_touch', 'name': 'nullspace:green_gem_cluster'},
                {'type': 'minecraft:item', 'name': 'nullspace:green_gem', 'modifier': [
                    {'type': 'minecraft:set_count', 'count': 2},
                    {'type': 'minecraft:apply_bonus', 'enchantment': 'minecraft:fortune', 'formula': 'minecraft:ore_drops'},
                    {'type': 'minecraft:explosion_decay'}]}]}]}],
        'random_sequence': 'nullspace:blocks/green_gem_cluster'})

    write_json(DATA / 'nullspace/recipe/green_gem_block.json', {
        'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': ['###', '###', '###'],
        'key': {'#': 'nullspace:green_gem'}, 'result': {'id': 'nullspace:green_gem_block', 'count': 1}})
    write_json(DATA / 'nullspace/recipe/green_gem_from_block.json', {
        'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'group': 'green_gem',
        'ingredients': ['nullspace:green_gem_block'], 'result': {'id': 'nullspace:green_gem', 'count': 9}})

    # The fluid swims like water: it is in the water tag.
    write_json(DATA / 'minecraft/tags/fluid/water.json', {'replace': False, 'values': ['nullspace:green_fluid', 'nullspace:flowing_green_fluid']})

    # The geode: small, filled with the fluid, lined with clusters, never cracked open.
    def geode(wall_distance, points, offset, lining, alternate_chance):
        return {
            'type': 'minecraft:geode',
            'blocks': {
                'alternate_inner_layer_provider': {'id': lining},
                'cannot_replace': '#minecraft:features_cannot_replace',
                'filling_provider': {'id': 'nullspace:green_fluid', 'Properties': {'level': '0'}},
                'inner_layer_provider': {'id': 'minecraft:smooth_basalt'},
                'inner_placements': ['minecraft:air'],
                'invalid_blocks': '#minecraft:geode_invalid_blocks',
                'middle_layer_provider': {'id': 'minecraft:calcite'},
                'outer_layer_provider': {'id': 'minecraft:smooth_basalt'}},
            'crack': {'generate_crack_chance': 0.0},
            'invalid_blocks_threshold': 1,
            'layers': {},
            'min_gen_offset': -offset, 'max_gen_offset': offset,
            'outer_wall_distance': {'type': 'minecraft:uniform', 'min_inclusive': wall_distance[0], 'max_inclusive': wall_distance[1]},
            'distribution_points': {'type': 'minecraft:uniform', 'min_inclusive': points[0], 'max_inclusive': points[1]},
            'point_offset': {'type': 'minecraft:uniform', 'min_inclusive': 0, 'max_inclusive': 1},
            'use_alternate_layer0_chance': alternate_chance}

    write_json(DATA / 'nullspace/worldgen/feature/green_geode.json', geode((2, 3), (1, 2), 3, 'nullspace:green_gem_cluster', 0.6))

    # The amethyst geode of an airless world: vanilla's blocks, small, and on the surface like the green one.
    amethyst = geode((2, 3), (1, 2), 3, 'minecraft:budding_amethyst', 0.083)
    amethyst['blocks']['filling_provider'] = {'id': 'minecraft:air'}
    amethyst['blocks']['inner_layer_provider'] = {'id': 'minecraft:amethyst_block'}
    amethyst['blocks']['inner_placements'] = ['minecraft:small_amethyst_bud', 'minecraft:medium_amethyst_bud', 'minecraft:large_amethyst_bud', 'minecraft:amethyst_cluster']
    write_json(DATA / 'nullspace/worldgen/feature/amethyst_geode_surface.json', amethyst)

    for name, rarity in (('green_geode', 4), ('amethyst_geode_surface', 4)):
        write_json(DATA / f'nullspace/worldgen/placed_feature/{name}.json', {
            'feature': f'nullspace:{name}',
            'placement': [
                {'type': 'minecraft:rarity_filter', 'chance': rarity},
                {'type': 'minecraft:in_square'},
                {'type': 'minecraft:heightmap', 'heightmap': 'OCEAN_FLOOR_WG'},
                {'type': 'minecraft:offset', 'x': 0, 'y': -2, 'z': 0},
                {'type': 'minecraft:biome'}]})
    print('space material resources written')


main()
