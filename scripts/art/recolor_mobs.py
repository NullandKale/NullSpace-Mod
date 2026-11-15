"""Makes the stowaway textures (see Stowaways.java) from the vanilla mob textures in the Minecraft client jar.

Usage: python scripts/art/recolor_mobs.py <minecraft-client.jar> [contact-sheet.png]

Writes src/main/resources/assets/nullspace/textures/entity/<name>.png, which only the mod's own
entity types use (vanilla creatures keep their textures), and, when a path is given, a before/after sheet for review. Colors are shifted, never repainted, so every pixel of the
original art (and its alpha) stays where it was.
"""
import colorsys
import io
import os
import sys
import zipfile

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "nullspace", "textures", "entity")


def shift(image, hue=0.0, saturation=1.0, value=1.0, lift=0.0, tint=None, eyes=None, solid=None, grey=False, alpha_cap=None):
    """Rotate hue (degrees), scale saturation and value, add lift to value; tint=(hue, saturation) forces both.
    grey desaturates fully. alpha_cap limits every pixel's opacity (the creeper's ghostly body). Pixels more saturated
    than 0.35 and red become the color eyes, when given: the spider's red eyes, blended into its skin. solid paints every
    visible pixel that color (the spider's glowing eye layer)."""
    image = image.convert("RGBA")
    out = Image.new("RGBA", image.size)
    src, dst = image.load(), out.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = src[x, y]
            if solid and a:
                dst[x, y] = (*solid, a)
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if eyes and s > 0.35 and (h < 0.06 or h > 0.94):
                dst[x, y] = (*eyes, a)
                continue
            if grey:
                s = 0.0
            elif tint:
                h, s = tint[0] / 360, max(s, tint[1])
            else:
                h = (h + hue / 360) % 1.0
            s = min(1.0, s * saturation)
            v = min(1.0, v * value + lift)
            r, g, b = colorsys.hsv_to_rgb(h, s, v)
            dst[x, y] = (round(r * 255), round(g * 255), round(b * 255), min(a, alpha_cap) if alpha_cap is not None else a)
    return out


# path inside the jar -> recolor. The story: everything that is not crew came aboard as a planetside animal and adapted.
# Creeper: a grey ghost, half transparent (its renderer draws it translucent). Spiders: white from the dark
# ducts, their eyes blended into the skin. Silverfish: the same white, the first tier of the spiders. Slime and blaze: the same purple.
SPIDER = dict(grey=True, value=0.35, lift=0.64, eyes=(222, 216, 228))
JOBS = {
    "creeper/creeper.png": ("ghost_creeper", dict(grey=True, value=0.9, lift=0.1, alpha_cap=128)),
    "spider/spider.png": ("pale_spider", SPIDER),
    "spider/cave_spider.png": ("pale_cave_spider", SPIDER),
    "spider/spider_eyes.png": ("pale_spider_eyes", dict(solid=(226, 222, 232))),
    "silverfish/silverfish.png": ("pale_silverfish", SPIDER),
    "slime/slime.png": ("violet_slime", dict(hue=170, saturation=1.0, value=1.0)),
    "blaze/blaze.png": ("violet_blaze", dict(hue=240, saturation=0.62, value=1.0)),
}


def main():
    jar = zipfile.ZipFile(sys.argv[1])
    sheet = []
    for name, (output, job) in JOBS.items():
        before = Image.open(io.BytesIO(jar.read("assets/minecraft/textures/entity/" + name)))
        after = shift(before, **job)
        target = os.path.join(OUT, output + ".png")
        os.makedirs(os.path.dirname(target), exist_ok=True)
        after.save(target)
        sheet.append((before.convert("RGBA"), after))
        print("wrote", os.path.normpath(target))
    if len(sys.argv) > 2:
        scale = 4
        width = max(a.width for a, _ in sheet) * scale
        height = sum(a.height * scale + 8 for a, _ in sheet)
        image = Image.new("RGBA", (width * 2 + 16, height), (40, 40, 48, 255))
        top = 0
        for before, after in sheet:
            for column, picture in enumerate((before, after)):
                big = picture.resize((picture.width * scale, picture.height * scale), Image.NEAREST)
                image.alpha_composite(big, (column * (width + 16), top))
            top += before.height * scale + 8
        image.save(sys.argv[2])


main()
