"""Writes the resources of the hab door panel: its texture, models, blockstate, item, loot table and lang entry.

The panel is src/main/java/com/nullandkale/nullspace/gameplay/habs/HabDoors.java. Run from the repository root:
python scripts/art/build-door-panel-art.py
"""
from pathlib import Path
import json
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
ASSETS = RES / 'assets/nullspace'
DATA = RES / 'data'


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def panel():
    """A dark steel plate with a brushed face, a bevel and an amber lamp in the middle."""
    steel = ((54, 60, 68), (72, 80, 90), (38, 42, 50))
    image = Image.new('RGBA', (16, 16), steel[0] + (255,))
    for y in range(16):
        for x in range(16):
            edge = min(x, y, 15 - x, 15 - y)
            color = steel[2] if edge == 0 else steel[1] if edge == 1 or (x + y) % 5 == 0 else steel[0]
            image.putpixel((x, y), color + (255,))
    for dx in range(-1, 1):
        for dy in range(-1, 1):
            image.putpixel((8 + dx, 8 + dy), (255, 176, 40, 255))
    for x, y in ((7, 6), (8, 6), (6, 7), (9, 7), (6, 8), (9, 8), (7, 9), (8, 9)):
        image.putpixel((x, y), (120, 78, 24, 255))
    return image


def main():
    path = ASSETS / 'textures/block/door_panel.png'
    path.parent.mkdir(parents=True, exist_ok=True)
    panel().save(path)
    textures = {'texture': 'nullspace:block/door_panel'}
    write_json(ASSETS / 'models/block/door_panel.json', {'parent': 'minecraft:block/pressure_plate_up', 'textures': textures})
    write_json(ASSETS / 'models/block/door_panel_down.json', {'parent': 'minecraft:block/pressure_plate_down', 'textures': textures})
    write_json(ASSETS / 'blockstates/door_panel.json', {'variants': {'powered=false': {'model': 'nullspace:block/door_panel'}, 'powered=true': {'model': 'nullspace:block/door_panel_down'}}})
    write_json(ASSETS / 'models/item/door_panel.json', {'parent': 'nullspace:block/door_panel'})
    write_json(ASSETS / 'items/door_panel.json', {'model': {'type': 'minecraft:model', 'model': 'nullspace:item/door_panel'}})
    write_json(DATA / 'nullspace/loot_table/blocks/door_panel.json', {
        'type': 'minecraft:block',
        'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'nullspace:door_panel'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    lang_path = ASSETS / 'lang/en_us.json'
    lang = json.loads(lang_path.read_text(encoding='utf-8'))
    lang['block.nullspace.door_panel'] = 'Door Panel'
    lang_path.write_text(json.dumps(lang, indent=2) + '\n', encoding='utf-8')
    print('door panel resources written')


main()
