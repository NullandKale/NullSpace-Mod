"""Authored vanilla-density pixel art: 16 texels per block, including shaped parts."""
from pathlib import Path
import json
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/nullspace'
TEXTURES = ASSETS / 'textures/block'
MODELS = ASSETS / 'models/block'
CATALOG = json.loads((ROOT / 'src/main/resources/data/nullspace/catalog/equipment.json').read_text())
ACTIVE = [entry for entry in CATALOG if 'retired' not in entry['capabilities']]
IDS = [entry['id'] for entry in ACTIVE if entry['kind'] == 'block']
ITEMS = [entry['id'] for entry in ACTIVE if entry['kind'] == 'item']
INK, DARK, EDGE = '#242321', '#484742', '#78766e'
PALE, LIGHT, REDSTONE, AMBER = '#b9b7af', '#e3e0d5', '#b6332b', '#e5ad42'
COPPER, GREEN = '#b47a4b', '#79a34b'
TEXTURES.mkdir(parents=True, exist_ok=True)


def save(name, image):
    image.save(TEXTURES / f'{name}.png')


def surface(fill, salt=0):
    image = Image.new('RGB', (16, 16), fill)
    d = ImageDraw.Draw(image)
    rgb = tuple(int(fill[i:i+2], 16) for i in (1, 3, 5))
    for y in range(1, 15):
        for x in range(1, 15):
            value = (x*29 + y*37 + salt*13) % 31
            if value < 4:
                offset = -10 if value < 2 else 8
                d.point((x, y), fill=tuple(max(0, min(255, c+offset)) for c in rgb))
    d.line((0, 15, 15, 15, 15, 0), fill=tuple(max(0, c-28) for c in rgb))
    d.line((0, 14, 0, 0, 14, 0), fill=tuple(min(255, c+24) for c in rgb))
    return image


def panel(fill, pale=False):
    image = surface(fill)
    d = ImageDraw.Draw(image)
    for at in [(2, 2), (13, 2), (2, 13), (13, 13)]:
        d.point(at, fill=EDGE)
    return image


for name, fill, pale in [('case', DARK, False), ('panel', PALE, True), ('dark', INK, False)]:
    save('machine_' + name, panel(fill, pale))
for name, color in [('redstone', REDSTONE), ('amber', AMBER), ('metal', EDGE)]:
    save('machine_' + name, Image.new('RGB', (16, 16), color))


def base_color(kind):
    if kind in ('engine', 'weapon'):
        return '#64625c'
    if kind == 'generator':
        return '#89877f'
    if kind == 'battery':
        return '#bba386'
    return PALE


def housing(kind, top=False):
    image = surface(base_color(kind), sum(map(ord, kind)))
    d = ImageDraw.Draw(image)
    if kind == 'generator':
        if top:
            d.rectangle((3, 3, 12, 12), fill=DARK)
            for x in (4, 7, 10):
                d.line((x, 4, x, 11), fill=INK)
        else:
            for y in (4, 7, 10):
                d.line((3, y, 12, y), fill=INK)
                d.line((3, y+1, 12, y+1), fill='#a5a299')
    elif kind in ('engine', 'weapon'):
        if top:
            d.rectangle((2, 1, 3, 14), fill=EDGE)
            d.rectangle((12, 1, 13, 14), fill=EDGE)
        elif kind == 'engine':
            d.rectangle((2, 4, 13, 11), fill=INK)
            for x in (4, 7, 10):
                d.line((x, 4, x, 11), fill=EDGE)
        else:
            for y in (4, 10):
                d.rectangle((2, y, 13, y+1), fill=INK)
            d.line((4, 13, 11, 13), fill=REDSTONE)
    elif kind == 'battery':
        if top:
            d.rectangle((2, 2, 5, 5), fill=REDSTONE)
            d.rectangle((10, 10, 13, 13), fill=INK)
        else:
            d.rectangle((5, 1, 10, 14), fill=COPPER)
            d.line((6, 1, 6, 14), fill=AMBER)
    elif kind == 'air_supply':
        for y in (3, 6, 9, 12):
            d.line((3, y, 12, y), fill=INK)
    elif kind == 'docking_base':
        for y in (3, 11):
            d.rectangle((1, y, 14, y+1), fill=DARK)
    elif kind == 'ship_beacon' and top:
        d.rectangle((3, 3, 12, 12), fill=EDGE)
        d.rectangle((4, 4, 11, 11), fill=AMBER)
        d.rectangle((6, 6, 9, 9), fill=LIGHT)
    elif kind == 'station_core' and top:
        d.rectangle((3, 6, 12, 9), fill=COPPER)
        d.rectangle((6, 3, 9, 12), fill=COPPER)
        d.rectangle((6, 6, 9, 9), fill=REDSTONE)
    else:
        d.line((2, 3, 13, 3), fill=EDGE)
        d.line((2, 12, 13, 12), fill=EDGE)
    return image


def front(kind):
    image = surface(base_color(kind), sum(map(ord, kind))+3)
    d = ImageDraw.Draw(image)
    if kind in ('ship_computer', 'computer_terminal', 'pad_computer'):
        d.rectangle((1, 1, 14, 12), fill=EDGE)
        d.rectangle((2, 2, 13, 11), fill=INK)
        if kind == 'ship_computer':
            d.rectangle((3, 3, 12, 10), fill='#c6b583')
            d.polygon([(3, 4), (6, 3), (8, 5), (7, 8), (4, 9)], fill='#798649')
            d.line((5, 8, 9, 8, 9, 5, 11, 5), fill='#795d35')
            d.line((9, 4, 11, 6), fill=REDSTONE)
            d.line((11, 4, 9, 6), fill=REDSTONE)
        elif kind == 'computer_terminal':
            d.line((4, 4, 6, 6, 4, 8), fill=GREEN)
            d.line((8, 9, 11, 9), fill=GREEN)
        else:
            d.rectangle((4, 3, 5, 10), fill=LIGHT)
            d.rectangle((10, 3, 11, 10), fill=LIGHT)
            d.rectangle((5, 6, 10, 7), fill=LIGHT)
        d.line((3, 14, 12, 14), fill=INK)
        for x in (4, 7, 10):
            d.point((x, 14), fill=LIGHT)
    elif kind == 'computer_base':
        d.rectangle((4, 4, 11, 11), fill=DARK, outline=EDGE)
        d.rectangle((6, 6, 9, 9), fill=REDSTONE)
        for y in (5, 8, 10):
            d.line((2, y, 3, y), fill=COPPER)
            d.line((12, y, 13, y), fill=COPPER)
    elif kind == 'generator':
        for y in (3, 5):
            d.line((3, y, 12, y), fill=INK)
        d.rectangle((2, 8, 13, 14), fill=EDGE)
        d.rectangle((3, 9, 12, 13), fill=INK)
        d.polygon([(4, 13), (5, 10), (7, 12), (9, 9), (11, 12), (11, 13)], fill='#ca652e')
        d.polygon([(6, 13), (7, 11), (8, 12), (9, 11), (10, 13)], fill=AMBER)
    elif kind == 'engine':
        d.polygon([(5, 2), (10, 2), (13, 5), (13, 10), (10, 13), (5, 13), (2, 10), (2, 5)], fill=EDGE)
        d.ellipse((3, 3, 12, 12), fill=INK)
        d.ellipse((5, 5, 10, 10), fill='#845332', outline=COPPER)
        d.rectangle((6, 6, 9, 9), fill=AMBER)
        d.rectangle((7, 7, 8, 8), fill=LIGHT)
    elif kind == 'weapon':
        d.rectangle((2, 2, 13, 13), fill=INK, outline=EDGE)
        d.rectangle((4, 4, 11, 11), fill=DARK)
        d.rectangle((5, 5, 10, 10), fill=INK, outline=EDGE)
        for x in (3, 12):
            d.point((x, 3), fill=REDSTONE)
        d.line((5, 14, 10, 14), fill=REDSTONE)
    elif kind == 'battery':
        for x in (2, 9):
            d.rectangle((x, 4, x+4, 13), fill=COPPER, outline='#67513c')
            d.line((x+1, 5, x+1, 12), fill=AMBER)
            d.line((x+1, 7, x+3, 7), fill=LIGHT)
        d.line((4, 6, 4, 8), fill=LIGHT)
        d.rectangle((3, 1, 5, 2), fill=REDSTONE)
        d.rectangle((10, 1, 12, 2), fill=INK)
    elif kind == 'air_supply':
        d.rectangle((2, 2, 13, 13), fill=INK, outline=EDGE)
        for y in (4, 7, 10):
            d.line((3, y, 12, y), fill=EDGE)
        for shape in [[(7, 4), (9, 5), (8, 8)], [(8, 7), (11, 8), (10, 10)],
                      [(8, 8), (8, 11), (6, 10)], [(4, 6), (8, 8), (5, 9)]]:
            d.polygon(shape, fill=PALE)
        d.rectangle((7, 7, 8, 8), fill=EDGE)
    elif kind == 'local_scanner':
        d.rectangle((1, 1, 14, 12), fill=EDGE)
        d.rectangle((2, 2, 13, 11), fill=INK)
        d.ellipse((4, 3, 11, 10), outline=GREEN)
        d.line((8, 7, 11, 4), fill=GREEN)
        d.point((5, 8), fill=LIGHT)
        d.point((12, 14), fill=REDSTONE)
    elif kind == 'station_core':
        d.rectangle((2, 6, 13, 9), fill=DARK)
        d.rectangle((6, 2, 9, 13), fill=DARK)
        d.line((3, 7, 12, 7), fill=COPPER)
        d.line((7, 3, 7, 12), fill=COPPER)
        for x, y in [(2, 6), (11, 6), (6, 2), (6, 11)]:
            d.rectangle((x, y, x+2, y+2), fill=EDGE)
        d.rectangle((5, 5, 10, 10), fill=INK)
        d.rectangle((6, 6, 9, 9), fill=REDSTONE)
    elif kind == 'ship_beacon':
        d.rectangle((4, 2, 11, 11), fill=EDGE)
        d.rectangle((5, 3, 10, 10), fill=AMBER)
        d.rectangle((6, 4, 9, 9), fill=LIGHT)
        d.rectangle((3, 12, 12, 13), fill=DARK)
        d.point((7, 14), fill=REDSTONE)
    elif kind == 'docking_base':
        d.rectangle((5, 2, 10, 12), fill=INK)
        for x in (2, 11):
            d.rectangle((x, 3, x+2, 11), fill=EDGE)
            d.line((x, 4, x, 10), fill=LIGHT)
        for x in (2, 6, 10):
            d.line((x, 14, x+1, 14), fill=AMBER)
    elif kind == 'camera':
        d.rectangle((3, 3, 12, 12), fill=EDGE)
        d.ellipse((4, 4, 11, 11), fill=INK)
        d.rectangle((6, 6, 9, 9), fill='#5c6062')
        d.point((6, 6), fill=LIGHT)
        d.point((13, 3), fill=REDSTONE)
    elif kind == 'monitor':
        image = Image.new('RGB', (16, 16), INK)
        d = ImageDraw.Draw(image)
        d.rectangle((0, 0, 15, 15), outline=EDGE)
        d.line((2, 11, 5, 11, 7, 7, 9, 9, 13, 4), fill=GREEN)
        d.point((14, 14), fill=REDSTONE)
    else:
        raise ValueError('Missing block design: ' + kind)
    save('machine_' + kind, image)
    if kind not in ('camera', 'monitor'):
        save('machine_' + kind + '_side', housing(kind))
        save('machine_' + kind + '_top', housing(kind, top=True))


for kind in IDS:
    front(kind)

# Four model units wide means four lens texels, cropped from a 16px material.
lens = Image.new('RGB', (16, 16), INK)
ld = ImageDraw.Draw(lens)
ld.rectangle((6, 6, 9, 9), fill=INK, outline=EDGE)
ld.rectangle((7, 7, 8, 8), fill='#5c6062')
ld.point((6, 6), fill=LIGHT)
ld.point((8, 7), fill=REDSTONE)
save('machine_camera_lens', lens)


def interval(a, b):
    """Keep protruding parts inside the atlas tile without changing texel density."""
    length = b - a
    start = max(0, min(a, 16-length))
    return start, start+length


def box(lo, hi, texture='case', front_texture=None, rotation=None):
    x0, x1 = interval(lo[0], hi[0])
    y0, y1 = interval(16-hi[1], 16-lo[1])
    z0, z1 = interval(lo[2], hi[2])
    ez0, ez1 = interval(16-hi[2], 16-lo[2])
    uv = {'north': [x0, y0, x1, y1], 'south': [x0, y0, x1, y1],
          'east': [ez0, y0, ez1, y1], 'west': [z0, y0, z1, y1],
          'up': [x0, z0, x1, z1], 'down': [x0, ez0, x1, ez1]}
    result = {'from': lo, 'to': hi, 'faces': {
        side: {'texture': '#'+texture, 'uv': region} for side, region in uv.items()}}
    if front_texture:
        result['faces']['north']['texture'] = '#'+front_texture
    if rotation:
        result['rotation'] = rotation
    return result


for kind in IDS:
    textures = {key: 'nullspace:block/machine_'+value for key, value in
                {'case': 'case', 'panel': 'panel', 'dark': 'dark', 'metal': 'metal',
                 'redstone': 'redstone', 'amber': 'amber', 'front': kind}.items()}
    textures['particle'] = textures['case']
    if kind == 'camera':
        textures['front'] = 'nullspace:block/machine_camera_lens'
        elements = [box([6,6,15], [10,10,16], 'metal'), box([5,6,11], [11,10,15.5], 'panel'),
                    box([6,5,11], [10,11,15], 'panel'), box([6,6,9.5], [10,10,12], 'dark'),
                    box([6.25,6.25,10], [9.75,9.75,11], 'metal', rotation={
                        'origin': [8,8,10.5], 'axis': 'z', 'angle': 45}),
                    box([6,6,9], [10,10,9.5], 'dark', 'front'),
                    box([10,8,12], [10.6,8.8,13], 'redstone'),
                    box([5.4,7,12.5], [5.8,9,14], 'metal'),
                    box([10.2,7,12.5], [10.6,9,14], 'metal')]
    elif kind == 'monitor':
        elements = [box([0,0,14], [16,16,16], 'case', 'front')]
    else:
        textures['side'] = f'nullspace:block/machine_{kind}_side'
        textures['top'] = f'nullspace:block/machine_{kind}_top'
        textures['particle'] = textures['side']
        elements = [box([0,0,0], [16,16,16], 'side', 'front')]
        elements[0]['faces']['up']['texture'] = '#top'
        elements[0]['faces']['down']['texture'] = '#case'
        for side, face in elements[0]['faces'].items():
            face['cullface'] = side
    model = {'textures': textures, 'elements': elements, 'display': {'gui': {
        'rotation': [30,225,0], 'translation': [0,0,0], 'scale': [.625,.625,.625]}}}
    if kind not in ('camera', 'monitor'):
        model['parent'] = 'minecraft:block/block'
    (MODELS / f'{kind}.json').write_text(json.dumps(model, indent=2)+'\n')
    if kind in ('camera', 'monitor'):
        legacy = {'parent': 'minecraft:block/orientable', 'textures': {
            'top': textures['panel'], 'side': textures['case'], 'front': 'nullspace:block/machine_'+kind}}
        (MODELS / f'{kind}_legacy.json').write_text(json.dumps(legacy, indent=2)+'\n')


item_dir = ASSETS / 'textures/item'
item_dir.mkdir(parents=True, exist_ok=True)
for kind in ITEMS:
    image = Image.new('RGBA', (16, 16))
    d = ImageDraw.Draw(image)
    if kind == 'machine_casing':
        d.polygon([(2,4),(9,2),(13,5),(13,11),(5,14),(2,11)], fill=DARK, outline=EDGE)
        d.polygon([(3,4),(9,3),(12,5),(6,7)], fill=PALE)
        d.polygon([(6,8),(12,6),(12,11),(6,13)], fill=PALE)
        d.line((7,9,10,9), fill=REDSTONE)
    elif kind in ('circuit_board', 'system_upgrade', 'station_module'):
        d.rectangle((3,2,12,13), fill=DARK, outline=PALE)
        for y in (4,8,11):
            d.line((1,y,3,y), fill=AMBER)
            d.line((12,y,14,y), fill=AMBER)
        d.rectangle((5,5,10,11), fill=EDGE, outline=REDSTONE)
        if kind == 'circuit_board':
            d.rectangle((6,6,9,9), fill=INK)
            d.line((4,8,11,8), fill=REDSTONE)
        elif kind == 'system_upgrade':
            d.line((6,7,8,5,10,7), fill=REDSTONE)
            d.line((6,10,8,8,10,10), fill=REDSTONE)
        else:
            for x, y in [(6,6),(9,6),(6,9),(9,9)]:
                d.point((x,y), fill=PALE)
    elif kind == 'field_guide':
        d.polygon([(3,2),(10,1),(13,3),(13,12),(5,14),(3,12)], fill=DARK, outline=PALE)
        d.line((5,3,5,12), fill=REDSTONE)
        d.line((7,5,11,5), fill=REDSTONE)
        d.line((7,8,10,8), fill=PALE)
    elif kind == 'space_helmet':
        d.rounded_rectangle((2,2,13,13), radius=3, fill=PALE, outline=EDGE)
        d.rounded_rectangle((4,4,11,9), radius=1, fill=INK, outline=REDSTONE)
        d.line((5,5,7,5), fill=LIGHT)
        d.rectangle((6,11,9,13), fill=DARK)
    elif kind == 'space_chestplate':
        d.polygon([(5,2),(6,4),(9,4),(11,2),(14,4),(13,9),(11,8),(11,13),
                   (4,13),(4,8),(2,9),(1,4)], fill=PALE, outline=EDGE)
        d.rectangle((5,5,10,10), fill=DARK)
        d.line((6,7,9,7), fill=REDSTONE)
        d.line((6,12,8,12), fill=REDSTONE)
    elif kind == 'space_leggings':
        d.polygon([(3,2),(12,2),(12,13),(9,13),(8,8),(7,8),(6,13),(3,13)], fill=PALE, outline=EDGE)
        d.rectangle((4,3,11,5), fill=DARK)
        d.line((6,4,8,4), fill=REDSTONE)
        d.rectangle((4,9,5,11), fill=EDGE)
        d.rectangle((10,9,11,11), fill=EDGE)
    elif kind == 'space_boots':
        for x in (2,9):
            d.polygon([(x+1,4),(x+4,4),(x+4,11),(x+5,13),(x,13),(x,11),(x+1,10)], fill=PALE, outline=EDGE)
            d.line((x+1,6,x+3,6), fill=REDSTONE)
            d.line((x,13,x+5,13), fill=INK)
    else:
        raise ValueError('Missing item design: ' + kind)
    image.save(item_dir / f'{kind}.png')
    model = {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'nullspace:item/{kind}'}}
    (ASSETS / f'models/item/{kind}.json').write_text(json.dumps(model, indent=2)+'\n')

# Retired palette tile; no generated model uses this material anymore.
(TEXTURES / 'machine_cyan.png').unlink(missing_ok=True)
print(f'Authored {len(IDS)-2} full cubes, camera/monitor shapes and {len(ITEMS)} icons at vanilla 16px density.')
