"""Writes the resources of the salvage turret and the repair bay: textures, models, blockstates, items, loot, recipes and lang entries.

The blocks are in src/main/java/com/nullandkale/nullspace/gameplay/defence/DefenceBlocks.java. Run from the repository root:
python scripts/art/build-defence-art.py
"""
from pathlib import Path
import json
import random
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
ASSETS = RES / 'assets/nullspace'
DATA = RES / 'data'

STEEL, STEEL_DARK, STEEL_LIGHT, PLATE = (112, 118, 128), (72, 77, 86), (156, 162, 172), (46, 50, 58)
GREEN, GREEN_LIGHT, GREEN_DARK = (74, 214, 44), (140, 255, 80), (36, 128, 28)


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def put(image, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        image.putpixel((x, y), tuple(color) + (255,))


def plated(seed):
    """Brushed steel with a bevelled edge and a rivet in each corner."""
    random.seed(seed)
    image = Image.new('RGBA', (16, 16), STEEL + (255,))
    for y in range(16):
        for x in range(16):
            if random.random() < 0.18:
                put(image, x, y, random.choice((STEEL_DARK, STEEL_LIGHT)))
    for i in range(16):
        put(image, i, 0, STEEL_LIGHT)
        put(image, 0, i, STEEL_LIGHT)
        put(image, i, 15, PLATE)
        put(image, 15, i, PLATE)
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        put(image, x, y, PLATE)
    return image


def turret():
    """A recessed round mount with a ring, a gun barrel pointing out of it and a green sight lamp."""
    image = plated(41)
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 8.5) ** 2) ** .5
            if d <= 5.2:
                put(image, x, y, (STEEL_LIGHT if x + y < 15 else PLATE) if d > 4.4 else STEEL_DARK if d > 3.2 else (30, 33, 40))
    for y in range(2, 9):
        put(image, 7, y, STEEL_LIGHT)
        put(image, 8, y, STEEL)
    for x in (6, 9):
        put(image, x, 7, STEEL_DARK)
        put(image, x, 8, PLATE)
    for x in (7, 8):
        put(image, x, 2, PLATE)
        put(image, x, 3, GREEN_LIGHT)
    for x, y in ((6, 10), (9, 10), (7, 11), (8, 11)):
        put(image, x, y, GREEN if y == 10 else GREEN_DARK)
    put(image, 7, 10, GREEN_LIGHT)
    return image


def bay():
    """A glass port lit green with a tool in it and scanlines, over a status strip and vents."""
    image = plated(77)
    for y in range(3, 10):
        for x in range(3, 13):
            put(image, x, y, PLATE)
    for y in range(4, 9):
        for x in range(4, 12):
            put(image, x, y, GREEN_LIGHT if y == 4 else GREEN if y % 2 else GREEN_DARK)
    for x, y in ((6, 8), (7, 7), (8, 6), (9, 5), (9, 6), (10, 5)):
        put(image, x, y, (214, 255, 170))
    for x in range(4, 12):
        put(image, x, 11, GREEN_LIGHT if x < 8 else GREEN_DARK)
        put(image, x, 12, PLATE)
    for x in range(4, 12, 2):
        put(image, x, 13, PLATE)
    return image


def metal(seed, base, dark, light, speckle):
    """A 16x16 tiling metal: a base colour with scattered darker and lighter flecks and faint horizontal brushing."""
    random.seed(seed)
    image = Image.new('RGBA', (16, 16), base + (255,))
    for y in range(16):
        for x in range(16):
            roll = random.random()
            if roll < speckle:
                put(image, x, y, dark)
            elif roll < speckle * 1.7:
                put(image, x, y, light)
    for y in (5, 11):
        for x in range(16):
            put(image, x, y, dark)
    return image


def lamp():
    image = Image.new('RGBA', (16, 16), GREEN + (255,))
    for y in range(16):
        for x in range(16):
            put(image, x, y, GREEN_LIGHT if (x + y) % 5 < 2 else GREEN)
    return image


def box(frm, to, texture):
    return {'from': frm, 'to': to, 'faces': {face: {'texture': '#' + texture} for face in ('down', 'up', 'north', 'south', 'west', 'east')}}


# The turret stands on a base (8, 10, 8 is the pivot of the head and gun, which the client renderer turns).
BASE = [box([2, 0, 2], [14, 2, 14], 'steel'), box([3, 2, 3], [13, 3, 13], 'dark'), box([6, 3, 6], [10, 8, 10], 'dark'), box([5, 7, 5], [11, 9, 11], 'steel')]
HEAD = [box([4.5, 9, 4.5], [11.5, 13, 11.5], 'steel'), box([5.5, 13, 5.5], [10.5, 14, 10.5], 'dark'), box([3, 9.5, 6], [4.5, 12.5, 10], 'dark'), box([11.5, 9.5, 6], [13, 12.5, 10], 'dark'), box([7, 11, 3.6], [9, 12.5, 4.5], 'lamp')]
GUN = [box([7, 9.2, 1], [9, 10.8, 8], 'dark'), box([6.5, 8.7, 0], [9.5, 11.3, 2], 'steel'), box([7.2, 9.4, -0.2], [8.8, 10.6, 0], 'lamp')]
TEXTURES = {'steel': 'nullspace:block/turret_steel', 'dark': 'nullspace:block/turret_dark', 'lamp': 'nullspace:block/turret_lamp', 'particle': 'nullspace:block/turret_steel'}
DISPLAY = {'gui': {'rotation': [30, 225, 0], 'scale': [0.62, 0.62, 0.62], 'translation': [0, -1, 0]},
           'ground': {'scale': [0.5, 0.5, 0.5], 'translation': [0, 3, 0]},
           'fixed': {'scale': [0.6, 0.6, 0.6]},
           'thirdperson_righthand': {'scale': [0.4, 0.4, 0.4], 'translation': [0, 3, 0]},
           'firstperson_righthand': {'rotation': [0, 45, 0], 'scale': [0.4, 0.4, 0.4]}}


def turret_models():
    textures = {k: v for k, v in TEXTURES.items()}
    write_json(ASSETS / 'models/block/salvage_turret.json', {'textures': textures, 'elements': BASE})
    write_json(ASSETS / 'models/item/turret_head.json', {'textures': textures, 'elements': HEAD})
    write_json(ASSETS / 'models/item/turret_gun.json', {'textures': textures, 'elements': GUN})
    write_json(ASSETS / 'models/item/salvage_turret.json', {'textures': textures, 'elements': BASE + HEAD + GUN, 'display': DISPLAY})
    write_json(ASSETS / 'blockstates/salvage_turret.json', {'variants': {'': {'model': 'nullspace:block/salvage_turret'}}})
    write_json(ASSETS / 'items/salvage_turret.json', {'model': {'type': 'minecraft:model', 'model': 'nullspace:item/salvage_turret'}})
    for part in ('turret_head', 'turret_gun'):
        write_json(ASSETS / f'items/{part}.json', {'model': {'type': 'minecraft:model', 'model': f'nullspace:item/{part}'}})
    for name, image in (('turret_steel', metal(41, STEEL, STEEL_DARK, STEEL_LIGHT, 0.12)), ('turret_dark', metal(43, (58, 63, 72), PLATE, STEEL_DARK, 0.14)), ('turret_lamp', lamp())):
        image.save(ASSETS / f'textures/block/{name}.png')


def main():
    turret_models()
    for name, make in (('salvage_turret', turret), ('repair_bay', bay)):
        path = ASSETS / f'textures/block/{name}.png'
        path.parent.mkdir(parents=True, exist_ok=True)
        make().save(path)
        if name == 'repair_bay':
            write_json(ASSETS / f'models/block/{name}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'nullspace:block/{name}'}})
            write_json(ASSETS / f'blockstates/{name}.json', {'variants': {'': {'model': f'nullspace:block/{name}'}}})
            write_json(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'nullspace:block/{name}'}})
        write_json(DATA / f'nullspace/loot_table/blocks/{name}.json', {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'nullspace:{name}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})

    lang_path = ASSETS / 'lang/en_us.json'
    lang = json.loads(lang_path.read_text(encoding='utf-8'))
    lang.update({'block.nullspace.salvage_turret': 'Salvage Turret', 'block.nullspace.repair_bay': 'Repair Bay', 'item.nullspace.turret_head': 'Turret Head', 'item.nullspace.turret_gun': 'Turret Gun'})
    lang_path.write_text(json.dumps(lang, indent=2) + '\n', encoding='utf-8')

    # Three rows of the turret: iron and circuits around a dispenser on a machine casing.
    write_json(DATA / 'nullspace/recipe/salvage_turret.json', {
        'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': ['ICI', 'CDC', 'IMI'],
        'key': {'I': 'minecraft:iron_ingot', 'C': 'nullspace:circuit_board', 'D': 'minecraft:dispenser', 'M': 'nullspace:machine_casing'},
        'result': {'id': 'nullspace:salvage_turret', 'count': 1}})
    # The repair bay costs one green gem block (nine gems).
    write_json(DATA / 'nullspace/recipe/repair_bay.json', {
        'type': 'minecraft:crafting_shapeless', 'category': 'misc',
        'ingredients': ['nullspace:green_gem_block', 'nullspace:machine_casing', 'nullspace:circuit_board', 'nullspace:circuit_board'],
        'result': {'id': 'nullspace:repair_bay', 'count': 1}})
    print('defence resources written')


main()
