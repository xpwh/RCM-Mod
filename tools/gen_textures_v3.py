"""Textures for the content added in 1.3: kamikaze drone, aerial bomb, strike jet, CIWS, airstrike radio.

Reuses the helpers of gen_textures.py / gen_textures_v2.py and only writes the new files.
"""
import random

import numpy as np
from PIL import Image, ImageDraw

import gen_textures as g
import gen_textures_v2 as g2
from gen_textures import item_missile, missile_texture, out, outline, small_print, stencil_decal, stripe

random.seed(1313)
np.random.seed(1313)

PATCH = 16


def atlas(patches):
    """8x8 grid of 16 px patches (BoxMesh layout); patches maps index -> PIL image."""
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 255))
    for i, p in patches.items():
        img.paste(p, ((i % 8) * PATCH, (i // 8) * PATCH))
    return img


def flat(rgb):
    return Image.new("RGBA", (PATCH, PATCH), tuple(rgb) + (255,))


# ---------------------------------------------------------------------------------------------- missiles

def kamikaze_drone():
    def cross(pil, d, row):
        cy = (row(2.4) + row(1.6)) // 2
        for cx in (64, 192):
            d.rectangle([cx - 2, cy - 9, cx + 2, cy + 9], fill=(30, 30, 30, 255))
            d.rectangle([cx - 9, cy - 2, cx + 9, cy + 2], fill=(30, 30, 30, 255))

    missile_texture("kamikaze_drone", 3.5, [
        (0.0, 0.35, (52, 54, 56)),
        (2.85, 3.5, (150, 150, 146)),  # glass-fibre nose with the warhead
        (2.75, 2.85, (200, 40, 30)),
    ], (128, 130, 126), (118, 120, 116), [
        cross,
        stencil_decal("GERAN-2", 1.3, (40, 168), (40, 42, 44), 9),
        small_print(["NO STEP", "FUEL"], 2.5, 100, (60, 60, 60)),
    ], soot=0.3, streaks=0.8)


def aerial_bomb():
    missile_texture("aerial_bomb", 2.2, [
        (0.0, 0.62, (78, 84, 62)),
        (1.55, 1.65, (240, 200, 40)),  # yellow band = live high-explosive filler
        (1.72, 1.8, (240, 200, 40)),
        (2.1, 2.2, (60, 60, 64)),
    ], (92, 100, 70), (80, 88, 60), [
        stencil_decal("MK 82", 1.3, (40, 168), (230, 225, 200), 9),
        small_print(["500 LB", "GP"], 1.0, 100, (220, 215, 190)),
    ], soot=0.0, streaks=0.4)


# ---------------------------------------------------------------------------------------------- jet

def strike_jet():
    n = g.noise(PATCH, PATCH, 2.0, 3)
    noisy = g2.noisy
    grey = (128, 134, 140)
    p = {
        0: noisy(grey, n, 0.1),
        1: noisy((96, 102, 108), n, 0.1),
        3: noisy((112, 106, 98), n, 0.25),
        4: noisy((26, 26, 28), n),
        5: noisy((84, 88, 92), n),
        6: noisy((12, 12, 14), n),
        7: flat((230, 30, 30)),
        8: flat((40, 220, 60)),
        9: noisy((80, 88, 60), n),
        10: noisy((226, 226, 222), n, 0.08),
        13: flat((236, 196, 40)),
    }
    glass = noisy((150, 120, 60), n, 0.2)  # gold-tinted canopy coating
    d = ImageDraw.Draw(glass)
    d.line([(2, 13), (12, 3)], fill=(230, 210, 160, 255))
    d.line([(4, 14), (14, 4)], fill=(200, 180, 130, 255))
    p[2] = glass
    mark = noisy(grey, n, 0.1)  # low-visibility roundel
    d = ImageDraw.Draw(mark)
    d.ellipse([2, 2, 13, 13], outline=(70, 74, 80, 255), width=2)
    d.ellipse([6, 6, 9, 9], fill=(70, 74, 80, 255))
    p[11] = mark
    panel = noisy(grey, n, 0.1)
    d = ImageDraw.Draw(panel)
    d.rectangle([0, 0, 15, 15], outline=(100, 106, 112, 255))
    for x in (3, 12):
        for y in (3, 12):
            d.point((x, y), fill=(160, 166, 170, 255))
    p[12] = panel
    atlas(p).save(out("entity/strike_jet.png"))


# ---------------------------------------------------------------------------------------------- CIWS

def ciws():
    n = g.noise(PATCH, PATCH, 2.0, 3)
    noisy = g2.noisy
    haze = (150, 156, 160)  # navy haze grey
    p = {
        0: noisy(haze, n, 0.1),
        1: noisy((236, 236, 232), n, 0.06),
        2: noisy((92, 96, 100), n),
        3: noisy((120, 122, 126), n, 0.2),
        4: noisy((24, 24, 26), n),
        5: flat((255, 220, 120)),
        6: flat((255, 190, 90)),
    }
    hz = Image.new("RGBA", (PATCH, PATCH), (230, 180, 20, 255))
    d = ImageDraw.Draw(hz)
    for x in range(-16, 16, 8):
        d.polygon([(x, 15), (x + 4, 15), (x + 19, 0), (x + 15, 0)], fill=(20, 20, 20, 255))
    p[7] = hz
    atlas(p).save(out("entity/ciws.png"))

    # pedestal block textures
    base = g.tile((120, 124, 128), 61, 6)
    d = ImageDraw.Draw(base)
    d.rectangle([0, 0, 15, 15], outline=(84, 88, 92, 255))
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        d.point((x, y), fill=(190, 190, 186, 255))
    base.save(out("block/ciws_base.png"))
    side = g.tile((150, 156, 160), 62, 5)
    d = ImageDraw.Draw(side)
    d.rectangle([0, 0, 15, 15], outline=(110, 116, 120, 255))
    d.rectangle([4, 4, 11, 9], outline=(110, 116, 120, 255))
    d.rectangle([5, 11, 10, 12], fill=(230, 180, 20, 255))
    side.save(out("block/ciws_side.png"))
    top = g.tile((92, 96, 100), 63, 5)
    d = ImageDraw.Draw(top)
    d.ellipse([1, 1, 14, 14], outline=(60, 62, 66, 255))
    top.save(out("block/ciws_top.png"))
    white = g.tile((236, 236, 232), 64, 3)
    ImageDraw.Draw(white).line([(0, 12), (15, 12)], fill=(200, 200, 196, 255))
    white.save(out("block/ciws_radome.png"))
    barrel = g.tile((30, 30, 32), 65, 4)
    barrel.save(out("block/ciws_barrel.png"))


# ---------------------------------------------------------------------------------------------- items

def item_radio():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([4, 5, 11, 14], fill=(74, 82, 54, 255))  # body
    d.rectangle([5, 6, 10, 8], fill=(40, 46, 30, 255))  # speaker grille
    d.point((6, 7), fill=(90, 98, 70, 255))
    d.point((8, 7), fill=(90, 98, 70, 255))
    d.rectangle([5, 10, 7, 11], fill=(30, 30, 30, 255))  # keypad
    d.point((9, 10), fill=(230, 40, 30, 255))  # PTT lamp
    d.rectangle([9, 12, 10, 13], fill=(200, 160, 40, 255))
    d.line([(10, 4), (12, 0)], fill=(30, 30, 30, 255))  # antenna
    d.line([(5, 4), (5, 3)], fill=(50, 50, 50, 255))
    outline(img)
    # a tiny jet silhouette in the corner
    for (x, y) in ((1, 2), (2, 2), (3, 2), (2, 1), (2, 3), (0, 2)):
        img.putpixel((x, y), (200, 205, 210, 255))
    img.save(out("item/airstrike_radio.png"))

# ---------------------------------------------------------------------------------------------- launch site structures

def structures():
    """Patch atlas for the launch pad complex, silo headworks and radar station (StructureKit)."""
    n = g.noise(PATCH, PATCH, 2.0, 3)
    noisy = g2.noisy

    def concrete(base, seed, stains=0.0):
        img = noisy(base, g.noise(PATCH, PATCH, 3.0, 3), 0.18)
        d = ImageDraw.Draw(img)
        rng = random.Random(seed)
        for _ in range(6):
            x, y = rng.randrange(16), rng.randrange(16)
            d.point((x, y), fill=tuple(max(0, c - 25) for c in base) + (255,))
        if stains:
            arr = np.asarray(img).astype(np.float64)
            st = g.noise(PATCH, PATCH, 2.0, 2)
            arr[..., :3] *= (1.0 - stains * np.clip(st - 0.45, 0, 1)[..., None] * 2)
            img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")
        return img

    p = {
        0: concrete((168, 166, 158), 1),
        1: concrete((120, 118, 112), 2, 0.4),
        2: noisy((132, 136, 140), n, 0.15),
        3: noisy((196, 52, 40), n, 0.1),
        4: noisy((226, 226, 220), n, 0.06),
        6: noisy((26, 26, 28), n),
        8: flat((230, 186, 30)),
        9: noisy((90, 98, 64), n, 0.12),
        11: flat((255, 244, 200)),
        12: flat((255, 40, 30)),
        13: noisy((110, 72, 50), n, 0.3),
        14: noisy((40, 38, 36), g.noise(PATCH, PATCH, 3.0, 3), 0.35),
        15: noisy((34, 34, 36), n),
        17: noisy((52, 52, 54), n, 0.25),
        19: flat((60, 255, 90)),
    }
    hz = Image.new("RGBA", (PATCH, PATCH), (230, 180, 20, 255))
    d = ImageDraw.Draw(hz)
    for x in range(-16, 16, 8):
        d.polygon([(x, 15), (x + 4, 15), (x + 19, 0), (x + 15, 0)], fill=(20, 20, 20, 255))
    p[5] = hz
    grating = Image.new("RGBA", (PATCH, PATCH), (70, 72, 74, 255))
    d = ImageDraw.Draw(grating)
    for i in range(0, 16, 3):
        d.line([(i, 0), (i, 15)], fill=(150, 152, 150, 255))
        d.line([(0, i), (15, i)], fill=(120, 122, 120, 255))
    p[7] = grating
    glass = noisy((70, 100, 120), n, 0.2)
    ImageDraw.Draw(glass).line([(2, 13), (13, 2)], fill=(170, 200, 210, 255))
    p[10] = glass
    dish = noisy((214, 216, 212), n, 0.08)
    d = ImageDraw.Draw(dish)
    for i in range(0, 16, 4):
        d.line([(i, 0), (i, 15)], fill=(170, 172, 168, 255))
        d.line([(0, i), (15, i)], fill=(170, 172, 168, 255))
    p[16] = dish
    panel = noisy((120, 126, 112), n, 0.1)
    d = ImageDraw.Draw(panel)
    d.rectangle([0, 0, 15, 15], outline=(80, 84, 74, 255))
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        d.point((x, y), fill=(180, 182, 176, 255))
    d.rectangle([4, 5, 11, 7], fill=(40, 42, 40, 255))
    d.point((5, 10), fill=(60, 220, 80, 255))
    d.point((7, 10), fill=(230, 60, 40, 255))
    p[18] = panel
    door = noisy((96, 100, 96), n, 0.1)
    d = ImageDraw.Draw(door)
    d.rectangle([0, 0, 15, 15], outline=(60, 62, 60, 255))
    d.line([(8, 1), (8, 14)], fill=(70, 72, 70, 255))
    d.rectangle([10, 7, 12, 8], fill=(200, 200, 196, 255))
    p[20] = door
    vent = noisy((90, 92, 94), n)
    d = ImageDraw.Draw(vent)
    for y in range(1, 16, 3):
        d.line([(1, y), (14, y)], fill=(30, 30, 32, 255))
    p[21] = vent
    atlas(p).save(out("entity/structures.png"))


def vapor():
    """Soft white condensation texture for the Mach cone (alpha comes from the vertex colour)."""
    S = 64
    nz = g.noise(S, S, 2.5, 4)
    arr = np.zeros((S, S, 4), dtype=np.float64)
    arr[..., :3] = 255
    arr[..., 3] = np.clip(120 + 135 * nz, 0, 255)
    Image.fromarray(arr.astype(np.uint8), "RGBA").save(out("entity/vapor.png"))


if __name__ == "__main__":
    kamikaze_drone()
    aerial_bomb()
    strike_jet()
    ciws()
    item_missile("kamikaze_drone", (132, 134, 130), (150, 150, 146), (110, 112, 108), [(0.70, 0.74)], False, wings=True)
    item_radio()
    structures()
    vapor()
    print("v3 textures ok")
