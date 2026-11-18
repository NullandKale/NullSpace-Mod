"""Writes the resources of the space wither, the black hole and the beacon helmet.

The boss is in src/main/java/com/nullandkale/nullspace/gameplay/boss/. Its texture is the vanilla wither's, whitened, so this reads the
Minecraft client jar. Run from the repository root:
python scripts/art/build-boss-art.py <minecraft-clientOnly jar>
"""
from pathlib import Path
import io
import json
import sys
import zipfile
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
ASSETS = RES / 'assets/nullspace'
DATA = RES / 'data'

GREEN, GREEN_LIGHT, GREEN_DARK, SHADE = (74, 214, 44), (140, 255, 80), (36, 128, 28), (20, 72, 22)


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def whiten(source):
    """The wither's dark greys become bone white and its light parts stay bright, keeping every shade apart."""
    image = Image.open(io.BytesIO(source)).convert('RGBA')
    px = image.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            shade = (r + g + b) / 3 / 255
            v = int(150 + 105 * shade)
            px[x, y] = (v, v, min(255, v + 6), a)
    return image


def black_hole():
    """A void with a bright thin ring, a violet glow and a tilted accretion disc that crosses it."""
    image = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = image.load()
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** .5
            if d <= 3.4:
                px[x, y] = (3, 1, 8, 255)
            elif d <= 4.4:
                px[x, y] = (236, 226, 255, 255)
            elif d <= 5.6:
                px[x, y] = (150, 90, 230, 255)
            elif d <= 6.8:
                px[x, y] = (84, 44, 150, 190)
    for x in range(16):
        y = round(8 + (x - 7.5) * -0.28)
        for dy in (-1, 0, 1):
            if abs(x - 7.5) > 3.3 or dy >= 0:
                px[x, y + dy] = (250, 244, 255, 255) if dy == 0 else (190, 140, 255, 255) if abs(x - 7.5) < 6 else (130, 80, 210, 230)
    return image


def beacon_helmet():
    """A slim helmet like an iron one: a rounded dome, a brow band, side guards and an open face, with a small black hole on the brow."""
    image = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = image.load()
    rows = {2: (6, 9), 3: (4, 11), 4: (3, 12), 5: (3, 12), 6: (3, 12), 7: (3, 12), 8: (3, 5, 10, 12), 9: (3, 5, 10, 12), 10: (3, 5, 10, 12), 11: (3, 4, 11, 12)}
    for y, spans in rows.items():
        pairs = [(spans[0], spans[1])] if len(spans) == 2 else [(spans[0], spans[1]), (spans[2], spans[3])]
        for a, b in pairs:
            for x in range(a, b + 1):
                t = (x - 3) / 9
                color = GREEN_LIGHT if t < .3 else GREEN if t < .72 else GREEN_DARK
                if y == 7:
                    color = GREEN_DARK if t < .72 else SHADE
                px[x, y] = color + (255,)
    # The black hole, ringed in violet, set in the crown.
    for x, y in ((7, 4), (8, 4)):
        px[x, y] = (6, 3, 14, 255)
    for x, y in ((6, 4), (9, 4), (7, 3), (8, 3), (7, 5), (8, 5)):
        px[x, y] = (200, 160, 255, 255)
    edge = [(x, y) for y in range(16) for x in range(16) if px[x, y][3] == 0 and any(0 <= x + dx < 16 and 0 <= y + dy < 16 and px[x + dx, y + dy][3] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for x, y in edge:
        px[x, y] = (12, 40, 16, 255)
    return image


def main(jar):
    z = zipfile.ZipFile(jar)
    save = ASSETS / 'textures/entity/space_wither.png'
    save.parent.mkdir(parents=True, exist_ok=True)
    whiten(z.read('assets/minecraft/textures/entity/wither/wither.png')).save(save)
    for name, make in (('black_hole', black_hole), ('beacon_helmet', beacon_helmet)):
        path = ASSETS / f'textures/item/{name}.png'
        path.parent.mkdir(parents=True, exist_ok=True)
        make().save(path)
        write_json(ASSETS / f'models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'nullspace:item/{name}'}})
        write_json(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'nullspace:item/{name}'}})

    lang_path = ASSETS / 'lang/en_us.json'
    lang = json.loads(lang_path.read_text(encoding='utf-8'))
    lang.update({'entity.nullspace.space_wither': 'Space Wither', 'item.nullspace.black_hole': 'Black Hole', 'item.nullspace.beacon_helmet': 'Beacon Helmet'})
    lang_path.write_text(json.dumps(lang, indent=2) + '\n', encoding='utf-8')

    write_json(DATA / 'nullspace/loot_table/entities/space_wither.json', {
        'type': 'minecraft:entity',
        'pools': []})  # the black hole is dropped by SpaceWither itself, with the long life of the vanilla nether star
    # The suit helmet's shape (five pieces) in green gems, with the black hole at its crown.
    write_json(DATA / 'nullspace/recipe/beacon_helmet.json', {
        'type': 'minecraft:crafting_shaped', 'category': 'equipment', 'pattern': ['GGG', 'GKG'],
        'key': {'G': 'nullspace:green_gem', 'K': 'nullspace:black_hole'},
        'result': {'id': 'nullspace:beacon_helmet', 'count': 1}})
    print('boss resources written')


main(sys.argv[1])
