"""Writes the resources of the golden apple tree and the enchanted golden apple tree: textures, models, blockstates, items, loot tables, the
recipe that makes an enchanted sapling and the lang entries.

The trees are in src/main/java/com/nullandkale/nullspace/gameplay/trees/. Run from the repository root:
python scripts/art/build-tree-art.py
"""
from pathlib import Path
import json
import random
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
ASSETS = RES / 'assets/nullspace'
DATA = RES / 'data'

# Plain: green leaves and gold. Enchanted: blue-green leaves, violet glints and a pink-gold fruit.
KINDS = {
    'golden_apple': {
        'leaf': ((54, 124, 40), (38, 96, 30), (80, 152, 54)), 'fruit': ((255, 204, 48), (214, 150, 24), (255, 240, 160)),
        'bark': ((96, 70, 40), (66, 46, 28), (126, 96, 58)), 'vein': (226, 178, 50), 'sprout': (255, 204, 48),
        'name': 'Golden Apple'},
    'enchanted_golden_apple': {
        'leaf': ((36, 132, 112), (24, 100, 88), (66, 168, 140)), 'fruit': ((255, 168, 224), (206, 96, 200), (255, 236, 250)),
        'bark': ((88, 66, 72), (60, 42, 54), (122, 92, 110)), 'vein': (182, 116, 240), 'sprout': (232, 150, 255),
        'name': 'Enchanted Golden Apple'},
}


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def save(image, rel):
    path = ASSETS / 'textures' / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def put(image, x, y, color):
    if 0 <= x < image.width and 0 <= y < image.height:
        image.putpixel((x, y), tuple(color) + (255,))


def leaves(kind):
    """Opaque leaf texture: clustered blobs of three greens, so it reads as foliage (an apple is its own block, hanging under a leaf)."""
    c = KINDS[kind]
    random.seed(7)
    image = Image.new('RGBA', (16, 16), c['leaf'][0] + (255,))
    for _ in range(46):
        x, y = random.randrange(16), random.randrange(16)
        shade = random.choice((c['leaf'][1], c['leaf'][2], c['leaf'][1]))
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            put(image, (x + dx) % 16, (y + dy) % 16, shade)
    if kind == 'enchanted_golden_apple':
        for x, y in ((3, 2), (11, 5), (6, 12), (13, 13), (1, 9)):
            put(image, x, y, c['vein'])
    return image


def apple(kind):
    """The body of a hanging apple: a round, shaded fruit with a glint, in the tree's colours, filling the whole face."""
    c = KINDS[kind]
    image = Image.new('RGBA', (16, 16), c['fruit'][0] + (255,))
    for y in range(16):
        for x in range(16):
            lit = (x + y) / 30
            put(image, x, y, c['fruit'][2] if x < 4 and y < 5 else c['fruit'][1] if lit > 0.85 else c['fruit'][0])
    return image


def stem():
    image = Image.new('RGBA', (16, 16), (92, 66, 36, 255))
    for y in range(0, 16, 2):
        for x in range(16):
            put(image, x, y, (70, 50, 28))
    return image


def bark(kind):
    c = KINDS[kind]
    random.seed(11)
    image = Image.new('RGBA', (16, 16), c['bark'][0] + (255,))
    for x in range(16):
        streak = random.choice((c['bark'][1], c['bark'][0], c['bark'][2]))
        for y in range(16):
            if random.random() < 0.75:
                put(image, x, y, streak)
    for y in range(2, 14):
        put(image, 4, y, c['vein'])
        put(image, 11, y, c['vein'])
    return image


def top(kind):
    c = KINDS[kind]
    image = Image.new('RGBA', (16, 16), c['bark'][2] + (255,))
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            ring = int(d)
            color = c['bark'][1] if d >= 7 else c['vein'] if ring == 5 else c['bark'][0] if ring % 2 else c['bark'][2]
            put(image, x, y, color)
    return image


def sapling(kind):
    """A small sapling: a brown stem, two leaf clusters and a gold (or violet) bud."""
    c = KINDS[kind]
    image = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(8, 15):
        put(image, 7, y, c['bark'][0])
        put(image, 8, y, c['bark'][1])
    for cx, cy in ((5, 8), (10, 7), (7, 5)):
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            put(image, cx + dx, cy + dy, c['leaf'][2] if (dx + dy) % 2 else c['leaf'][0])
        put(image, cx, cy, c['leaf'][1])
    for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
        put(image, 7 + dx, 2 + dy, c['sprout'])
    put(image, 7, 2, c['fruit'][2])
    return image


def main():
    lang_path = ASSETS / 'lang/en_us.json'
    lang = json.loads(lang_path.read_text(encoding='utf-8'))
    for kind, c in KINDS.items():
        save(leaves(kind), f'block/{kind}_leaves.png')
        save(apple(kind), f'block/{kind}_hanging.png')
        save(bark(kind), f'block/{kind}_trunk_side.png')
        save(top(kind), f'block/{kind}_trunk_top.png')
        save(sapling(kind), f'block/{kind}_sapling.png')

        write_json(ASSETS / f'models/block/{kind}_trunk.json', {'parent': 'minecraft:block/cube_column', 'textures': {'end': f'nullspace:block/{kind}_trunk_top', 'side': f'nullspace:block/{kind}_trunk_side'}})
        write_json(ASSETS / f'blockstates/{kind}_trunk.json', {'variants': {'': {'model': f'nullspace:block/{kind}_trunk'}}})
        write_json(ASSETS / f'models/block/{kind}_leaves.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'nullspace:block/{kind}_leaves'}})
        write_json(ASSETS / f'blockstates/{kind}_leaves.json', {'variants': {'': {'model': f'nullspace:block/{kind}_leaves'}}})
        # An apple: a big round body (eight across, a wide belly over a taller core) and a stem up to the leaf above it, in the cell under the leaf.
        faces = {face: {'texture': '#apple'} for face in ('down', 'up', 'north', 'south', 'west', 'east')}
        stem_faces = {face: {'texture': '#stem'} for face in ('down', 'up', 'north', 'south', 'west', 'east')}
        write_json(ASSETS / f'models/block/{kind}_hanging.json', {
            'textures': {'apple': f'nullspace:block/{kind}_hanging', 'stem': 'nullspace:block/apple_stem', 'particle': f'nullspace:block/{kind}_hanging'},
            'elements': [{'from': [4, 6, 4], 'to': [12, 10, 12], 'faces': faces}, {'from': [5, 4, 5], 'to': [11, 12, 11], 'faces': faces}, {'from': [7.5, 12, 7.5], 'to': [8.5, 16, 8.5], 'faces': stem_faces}]})
        write_json(ASSETS / f'blockstates/{kind}_hanging.json', {'variants': {'': {'model': f'nullspace:block/{kind}_hanging'}}})
        write_json(DATA / f'nullspace/loot_table/blocks/{kind}_hanging.json', {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'minecraft:{kind}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
        write_json(ASSETS / f'models/block/{kind}_sapling.json', {'parent': 'minecraft:block/cross', 'textures': {'cross': f'nullspace:block/{kind}_sapling'}})
        write_json(ASSETS / f'blockstates/{kind}_sapling.json', {'variants': {'': {'model': f'nullspace:block/{kind}_sapling'}}})
        write_json(ASSETS / f'models/item/{kind}_sapling.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'nullspace:block/{kind}_sapling'}})
        write_json(ASSETS / f'items/{kind}_sapling.json', {'model': {'type': 'minecraft:model', 'model': f'nullspace:item/{kind}_sapling'}})

        lang.update({
            f'block.nullspace.{kind}_trunk': f"{c['name']} Tree",
            f'block.nullspace.{kind}_leaves': f"{c['name']} Leaves",
            f'block.nullspace.{kind}_sapling': f"{c['name']} Sapling",
            f'block.nullspace.{kind}_hanging': c['name'],
        })

        # Breaking the trunk's core drops one sapling of its own kind; leaves drop nothing (apples are picked by hand); a sapling drops itself.
        write_json(DATA / f'nullspace/loot_table/blocks/{kind}_trunk.json', {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'nullspace:{kind}_sapling'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
        write_json(DATA / f'nullspace/loot_table/blocks/{kind}_leaves.json', {'type': 'minecraft:block', 'pools': []})
        write_json(DATA / f'nullspace/loot_table/blocks/{kind}_sapling.json', {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'nullspace:{kind}_sapling'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})

    # The only way to an enchanted tree: nine golden apple saplings, one from each of nine hab dungeons, in a 3 by 3 craft.
    write_json(DATA / 'nullspace/recipe/enchanted_golden_apple_sapling.json', {
        'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': ['###', '###', '###'],
        'key': {'#': 'nullspace:golden_apple_sapling'}, 'result': {'id': 'nullspace:enchanted_golden_apple_sapling', 'count': 1}})

    save(stem(), 'block/apple_stem.png')
    write_json(DATA / 'minecraft/tags/block/mineable/axe.json', {'replace': False, 'values': ['nullspace:golden_apple_trunk', 'nullspace:enchanted_golden_apple_trunk']})
    lang_path.write_text(json.dumps(lang, indent=2) + '\n', encoding='utf-8')
    print('tree resources written')


main()
