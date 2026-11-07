"""Generates the worn space suit textures from the vanilla iron armor layout.

Reads the iron armor sheets from the Loom cache (run a Gradle build first), recolours them into
the plain suit (white, orange trim) and the armored suit (gunmetal, teal trim), closes the helmet
into a shell with a tinted visor, and adds a chest panel, a back air tank and coloured bands.

    python scripts/generate_suit_textures.py
"""
import glob
import io
import os
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'src/main/resources/assets/nullspace/textures/entity/equipment')

SUITS = {
    # name: (ramp dark..light, trim, visor, visor highlight)
    'space_suit': ([(118, 124, 132), (176, 182, 188), (214, 218, 222), (240, 242, 244)],
                   (226, 120, 34), (34, 58, 92), (120, 170, 220)),
    'armored_space_suit': ([(34, 38, 44), (58, 64, 72), (84, 92, 102), (112, 120, 130)],
                           (40, 182, 170), (20, 30, 40), (90, 200, 190)),
}


def vanilla(path):
    jars = glob.glob(os.path.join(
        ROOT, '.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-*/*/'
        'minecraft-clientOnly-*.jar'))
    jars = [j for j in jars if not j.endswith('-sources.jar')]
    with zipfile.ZipFile(jars[0]) as jar:
        return Image.open(io.BytesIO(jar.read(path))).convert('RGBA')


def recolour(img, ramp):
    px = img.load()
    for x in range(img.width):
        for y in range(img.height):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            lum = (r * 3 + g * 6 + b) / 10 / 255
            px[x, y] = ramp[min(len(ramp) - 1, int(lum * len(ramp)))] + (255,)


def fill(img, x0, y0, x1, y1, colour):
    px = img.load()
    for x in range(x0, x1):
        for y in range(y0, y1):
            px[x, y] = colour + (255,)


def body(name, ramp, trim, visor, glint):
    img = vanilla('assets/minecraft/textures/entity/equipment/humanoid/iron.png')
    recolour(img, ramp)
    # Helmet: a closed shell over the whole head box, seams darker, visor on the front.
    fill(img, 8, 0, 24, 8, ramp[2])
    fill(img, 0, 8, 32, 16, ramp[2])
    for x in range(0, 32):
        img.putpixel((x, 15), ramp[1] + (255,))
    for x in range(8, 16):
        img.putpixel((x, 15), trim + (255,))
    fill(img, 9, 10, 15, 14, visor)
    img.putpixel((10, 10), glint + (255,))
    img.putpixel((11, 10), glint + (255,))
    img.putpixel((10, 11), glint + (255,))
    fill(img, 1, 11, 3, 13, ramp[0])     # side air fittings
    fill(img, 21, 11, 23, 13, ramp[0])
    fill(img, 26, 9, 30, 14, ramp[1])    # back of the helmet
    # Chest: a control panel and a trim band at the waist.
    fill(img, 22, 22, 26, 25, ramp[0])
    img.putpixel((23, 23), trim + (255,))
    img.putpixel((24, 23), (220, 60, 50, 255))
    for x in range(20, 28):
        img.putpixel((x, 30), trim + (255,))
    # Back: an air tank.
    fill(img, 33, 21, 39, 30, ramp[1])
    fill(img, 34, 22, 38, 29, ramp[0])
    fill(img, 35, 21, 37, 22, trim)
    # Gloves and boot cuffs in the trim colour.
    for x in range(40, 56):
        img.putpixel((x, 30), trim + (255,))
        img.putpixel((x, 31), ramp[0] + (255,))
    for x in range(0, 16):
        img.putpixel((x, 28), trim + (255,))
        img.putpixel((x, 31), ramp[0] + (255,))
    save(img, 'humanoid', name)


def legs(name, ramp, trim, visor, glint):
    img = vanilla('assets/minecraft/textures/entity/equipment/humanoid_leggings/iron.png')
    recolour(img, ramp)
    px = img.load()
    for x in range(0, 16):  # knee pads
        if px[x, 24][3]:
            px[x, 24] = trim + (255,)
    save(img, 'humanoid_leggings', name)


def save(img, layer, name):
    folder = os.path.join(OUT, layer)
    os.makedirs(folder, exist_ok=True)
    img.save(os.path.join(folder, name + '.png'))


for suit, colours in SUITS.items():
    body(suit, *colours)
    legs(suit, *colours)
print('Suit textures written to', OUT)
