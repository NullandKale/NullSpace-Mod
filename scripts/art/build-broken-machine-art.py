"""Derives the broken Engine, Generator and Air Supply from the working machines' textures and models.

The wrecks are the same cubes in the same facing, charred and cracked, with their lights dead, so they read as the machine they
replace. Run from the repository root: python scripts/art/build-broken-machine-art.py
"""
from pathlib import Path
import json
import random
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/nullspace'
DATA = ROOT / 'src/main/resources/data'
TEXTURES = ASSETS / 'textures/block'
MACHINES = {'engine': 'Broken Ship Engine', 'generator': 'Broken Generator', 'air_supply': 'Broken Air Supply', 'pad_computer': 'Broken Pad Computer'}
SALVAGE = 0.4
SOOT, CRACK, SPARK = (24, 22, 20), (14, 13, 12), (229, 173, 66)


def damage(source, salt):
    image = Image.open(source).convert('RGBA')
    pixels = image.load()
    random.seed(salt)
    width, height = image.size
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            # The lights and the flame are out: warm bright pixels go to cold, dull ash.
            if r > 170 and b < 140 and r - b > 70:
                pixels[x, y] = (58, 50, 46, a)
                continue
            # Charred: darker and browner than the clean machine.
            pixels[x, y] = (int(r * .62 + 6), int(g * .58 + 5), int(b * .54 + 4), a)
    # Soot blooms spreading from a corner or two.
    for _ in range(2):
        cx, cy = random.randrange(width), random.randrange(height)
        radius = random.randint(2, 3)
        for y in range(height):
            for x in range(width):
                if (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2 and random.random() < .5:
                    r, g, b, a = pixels[x, y]
                    pixels[x, y] = (SOOT[0], SOOT[1], SOOT[2], a)
    # Cracks: a few jagged lines across the face.
    for _ in range(1):
        x, y = random.randrange(3, width - 3), 0
        for _ in range(random.randint(8, 13)):
            if 0 <= x < width and 0 <= y < height:
                pixels[x, y] = (CRACK[0], CRACK[1], CRACK[2], pixels[x, y][3])
            x += random.choice((-1, 0, 1))
            y += 1
    # A spark or two where wiring is exposed.
    for _ in range(1):
        pixels[random.randrange(2, width - 2), random.randrange(2, height - 2)] = (SPARK[0], SPARK[1], SPARK[2], 255)
    return image


RECIPES = DATA / 'nullspace/recipe'
# Intermediate parts are salvaged as their own materials, so a wreck gives iron and copper, not a free casing.
EXPAND = {'nullspace:machine_casing', 'nullspace:circuit_board', 'nullspace:computer_base'}
TAGS = {'#nullspace:rough_stone': 'minecraft:cobblestone', '#minecraft:planks': 'minecraft:oak_planks'}


def materials(item, counts=None, multiplier=1):
    """The raw items one of item costs, expanding the mod's own intermediate parts."""
    counts = {} if counts is None else counts
    recipe = json.loads((RECIPES / f"{item.split(':')[-1]}.json").read_text())
    used = {}
    for row in recipe['pattern']:
        for key in row:
            if key != ' ':
                used[key] = used.get(key, 0) + 1
    # One craft can make several (a circuit board craft makes four), so each item costs a share of a craft.
    made = recipe['result'].get('count', 1)
    for key, number in used.items():
        ingredient = recipe['key'][key]
        ingredient = ingredient if isinstance(ingredient, str) else ingredient['item']
        ingredient = TAGS.get(ingredient, ingredient)
        if ingredient in EXPAND:
            materials(ingredient, counts, multiplier * number / made)
        else:
            counts[ingredient] = counts.get(ingredient, 0) + multiplier * number / made
    return counts


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


for name, label in MACHINES.items():
    for suffix in ('', '_side', '_top'):
        damage(TEXTURES / f'machine_{name}{suffix}.png', sum(map(ord, name + suffix))).save(TEXTURES / f'broken_{name}{suffix}.png')
    model = json.loads((ASSETS / f'models/block/{name}.json').read_text())
    model['textures'].update({'front': f'nullspace:block/broken_{name}', 'particle': f'nullspace:block/broken_{name}_side', 'side': f'nullspace:block/broken_{name}_side', 'top': f'nullspace:block/broken_{name}_top', 'amber': 'nullspace:block/machine_dark', 'redstone': 'nullspace:block/machine_dark'})
    write(ASSETS / f'models/block/broken_{name}.json', model)
    write(ASSETS / f'models/item/broken_{name}.json', {'parent': f'nullspace:block/broken_{name}'})
    write(ASSETS / f'items/broken_{name}.json', {'model': {'type': 'minecraft:model', 'model': f'nullspace:block/broken_{name}'}})
    write(ASSETS / f'blockstates/broken_{name}.json', {'variants': {f'facing={d}': {'model': f'nullspace:block/broken_{name}', 'y': y} for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}})
    # What is salvaged from the wreck: 40 percent of the raw materials the machine is crafted from, each item rolled on its own.
    pools = [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': material, 'functions': [{'function': 'minecraft:set_count', 'count': {'type': 'minecraft:binomial', 'n': max(1, round(count)), 'p': SALVAGE}}]}]} for material, count in sorted(materials(name).items())]
    write(DATA / f'nullspace/loot_table/blocks/broken_{name}.json', {'type': 'minecraft:block', 'pools': pools})

lang_path = ASSETS / 'lang/en_us.json'
lang = json.loads(lang_path.read_text(encoding='utf-8'))
for name, label in MACHINES.items():
    lang[f'block.nullspace.broken_{name}'] = label
lang_path.write_text(json.dumps(lang, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

tag_path = DATA / 'minecraft/tags/block/mineable/pickaxe.json'
tag = json.loads(tag_path.read_text(encoding='utf-8'))
for name in MACHINES:
    if f'nullspace:broken_{name}' not in tag['values']:
        tag['values'].append(f'nullspace:broken_{name}')
tag_path.write_text(json.dumps(tag, indent=4) + '\n', encoding='utf-8')
print('built', ', '.join(MACHINES))
