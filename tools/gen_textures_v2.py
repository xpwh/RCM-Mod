"""Textures for the content added in 1.2: new missiles, missile silo, mobile launcher (TEL truck).

Reuses the helpers of gen_textures.py so the new missiles match the existing look. Only writes the new
files; run gen_textures.py first if you regenerate everything.
"""
import random

import numpy as np
from PIL import Image, ImageDraw

import gen_textures as g
from gen_textures import (access_panel, checker_band, hazard_band, item_missile, missile_texture, out, outline, small_print,
                          stencil_decal, stripe, tile, trefoil_decal)

random.seed(4242)
np.random.seed(4242)


# ---------------------------------------------------------------------------------------------- missiles

def tactical_nuke():
    missile_texture("tactical_nuke", 9.0, [
        (0.0, 1.0, (60, 64, 58)),
        (5.62, 5.90, (40, 42, 38)),
        (5.90, 6.04, (240, 200, 40)),
    ], (92, 100, 70), (84, 92, 64), [
        stencil_decal("9M723-N", 5.4, (40, 168), (235, 230, 215), 11),
        trefoil_decal((6.6, 7.6), (104, 232), r=13),
        hazard_band(1.6, 1.85),
        access_panel(2.9, 3.5, 60, 84, (60, 66, 46)),
    ], soot=0.4)


def emp_missile():
    def bolts(pil, d, row):
        cy = (row(5.2) + row(4.0)) // 2
        for cx in (64, 192):
            d.polygon([(cx + 4, cy - 16), (cx - 6, cy + 2), (cx + 1, cy + 2), (cx - 4, cy + 16), (cx + 8, cy - 4), (cx + 1, cy - 4)],
                      fill=(90, 200, 255, 255))

    missile_texture("emp_missile", 9.0, [
        (0.0, 1.0, (40, 42, 46)),
        (5.62, 5.90, (24, 26, 30)),
        (5.90, 6.02, (80, 180, 240)),
        (8.6, 9.0, (24, 24, 26)),
    ], (58, 62, 70), (52, 56, 64), [
        bolts,
        stencil_decal("HEMP-1", 6.6, (40, 168), (170, 220, 250), 11),
        small_print(["SAFE/ARM", "ALT FUZE"], 3.2, 100, (180, 200, 220)),
        stripe(2.4, 2.5, (80, 180, 240)),
    ], soot=0.35)


def incendiary_missile():
    def flames(pil, d, row):
        cy = (row(5.3) + row(4.3)) // 2
        for cx in (40, 104, 168, 232):
            d.polygon([(cx, cy - 13), (cx + 9, cy + 10), (cx - 9, cy + 10)], fill=(240, 100, 20, 255))
            d.polygon([(cx, cy - 4), (cx + 5, cy + 10), (cx - 5, cy + 10)], fill=(255, 220, 70, 255))

    missile_texture("incendiary_missile", 9.0, [
        (0.0, 1.0, (60, 50, 44)),
        (5.90, 6.04, (220, 40, 20)),
        (6.04, 6.14, (240, 200, 40)),
        (8.7, 9.0, (40, 30, 26)),
    ], (128, 52, 36), (112, 46, 32), [
        flames,
        stencil_decal("INCENDIARY", 6.95, (40, 168), (245, 225, 190), 9),
        access_panel(2.8, 3.6, 60, 84, (90, 36, 26)),
    ], soot=0.5)


def anti_radar_missile():
    missile_texture("anti_radar_missile", 6.0, [
        (2.40, 2.48, (60, 62, 66)),
        (5.10, 6.0, (200, 196, 160)),  # broadband passive seeker radome
        (4.95, 5.10, (220, 140, 30)),
    ], (150, 156, 160), (136, 142, 146), [
        stencil_decal("AGM-88X  ARM", 4.6, (40, 168), (40, 42, 46), 9),
        stripe(4.75, 4.82, (220, 140, 30)),
        small_print(["PASSIVE RF", "SEEKER"], 3.6, 64, (50, 50, 50)),
    ], soot=0.15, steel=(120, 122, 126))


# ---------------------------------------------------------------------------------------------- truck

PATCH = 16


def noisy(base, n, strength=0.12):
    arr = np.zeros((PATCH, PATCH, 4), dtype=np.float64)
    arr[..., :3] = base
    arr[..., 3] = 255
    g.shade(arr, n, strength)
    g.shade(arr, np.random.rand(PATCH, PATCH), 0.06)
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")


def mobile_launcher():
    S = 128
    atlas = Image.new("RGBA", (S, S), (0, 0, 0, 255))
    n = g.noise(PATCH, PATCH, 2.0, 3)

    def put(i, img):
        atlas.paste(img, ((i % 8) * PATCH, (i // 8) * PATCH))

    olive = (88, 98, 62)
    put(0, noisy(olive, n))
    put(1, noisy((62, 70, 44), n))
    put(2, noisy((26, 26, 28), n, 0.2))
    rim = noisy((96, 104, 74), n)
    d = ImageDraw.Draw(rim)
    d.ellipse([3, 3, 12, 12], outline=(60, 64, 50, 255))
    d.ellipse([6, 6, 9, 9], fill=(40, 40, 40, 255))
    put(3, rim)
    glass = noisy((60, 86, 98), n, 0.2)
    d = ImageDraw.Draw(glass)
    d.line([(2, 13), (11, 2)], fill=(150, 180, 190, 255))
    put(4, glass)
    put(5, noisy((120, 122, 124), n))
    put(6, noisy((22, 22, 24), n))
    lamp = Image.new("RGBA", (PATCH, PATCH), (250, 245, 200, 255))
    put(7, lamp)
    hz = Image.new("RGBA", (PATCH, PATCH), (230, 180, 20, 255))
    d = ImageDraw.Draw(hz)
    for x in range(-16, 16, 8):
        d.polygon([(x, 15), (x + 4, 15), (x + 19, 0), (x + 15, 0)], fill=(20, 20, 20, 255))
    put(8, hz)
    tread = noisy((30, 30, 32), n)
    d = ImageDraw.Draw(tread)
    for y in range(0, 16, 3):
        d.line([(0, y), (15, y)], fill=(14, 14, 16, 255))
    put(9, tread)
    grille = noisy((34, 36, 30), n)
    d = ImageDraw.Draw(grille)
    for x in range(1, 16, 3):
        d.line([(x, 1), (x, 14)], fill=(70, 74, 60, 255))
    put(10, grille)
    camo = noisy(olive, n)
    cn = g.noise(PATCH, PATCH, 3.0, 2)
    ca = np.asarray(camo).copy().astype(np.float64)
    ca[cn > 0.58, :3] = (58, 66, 40)
    ca[cn < 0.36, :3] = (110, 104, 74)
    put(11, Image.fromarray(ca.astype(np.uint8), "RGBA"))
    put(12, Image.new("RGBA", (PATCH, PATCH), (220, 30, 20, 255)))
    put(13, Image.new("RGBA", (PATCH, PATCH), (250, 150, 20, 255)))
    put(14, noisy((54, 56, 58), n))
    put(15, noisy((96, 92, 70), n, 0.18))
    atlas.save(out("entity/mobile_launcher.png"))


def item_truck():
    S = 16
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([1, 8, 14, 10], fill=(70, 80, 50, 255))  # bed
    d.rectangle([11, 5, 14, 10], fill=(90, 100, 64, 255))  # cab
    d.rectangle([12, 6, 13, 7], fill=(120, 160, 180, 255))  # window
    d.rectangle([1, 6, 11, 7], fill=(220, 220, 214, 255))  # missile
    d.polygon([(11, 6), (13, 6), (11, 7)], fill=(40, 40, 40, 255))
    d.rectangle([0, 6, 1, 7], fill=(60, 60, 64, 255))
    for x in (2, 5, 9, 12):
        d.ellipse([x, 10, x + 2, 12], fill=(24, 24, 26, 255))
        d.point((x + 1, 11), fill=(100, 100, 90, 255))
    outline(img)
    img.save(out("item/mobile_launcher.png"))


# ---------------------------------------------------------------------------------------------- silo

def silo():
    side = tile((150, 150, 146), 51, 7)
    d = ImageDraw.Draw(side)
    d.line([(0, 5), (15, 5)], fill=(128, 128, 124, 255))
    d.line([(0, 11), (15, 11)], fill=(128, 128, 124, 255))
    d.point((4, 2), fill=(110, 110, 106, 255))
    d.point((11, 8), fill=(110, 110, 106, 255))
    d.rectangle([0, 0, 15, 1], fill=(170, 170, 166, 255))
    side.save(out("block/missile_silo_side.png"))

    bottom = tile((120, 120, 116), 52, 6)
    bottom.save(out("block/missile_silo_bottom.png"))

    top = tile((150, 150, 146), 53, 6)
    d = ImageDraw.Draw(top)
    for i in range(16):  # hazard border
        c = (230, 180, 20, 255) if (i // 2) % 2 == 0 else (24, 24, 24, 255)
        d.point((i, 0), fill=c)
        d.point((i, 15), fill=c)
        d.point((0, i), fill=c)
        d.point((15, i), fill=c)
    d.rectangle([2, 2, 13, 13], fill=(96, 102, 100, 255))  # two steel doors
    d.line([(8, 2), (8, 13)], fill=(40, 42, 42, 255))
    d.line([(7, 2), (7, 13)], fill=(124, 130, 128, 255))
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12), (5, 7), (10, 7)):
        d.point((x, y), fill=(150, 156, 152, 255))
    d.rectangle([4, 5, 5, 10], fill=(80, 86, 84, 255))
    d.rectangle([10, 5, 11, 10], fill=(80, 86, 84, 255))
    top.save(out("block/missile_silo_top.png"))

    hole = tile((150, 150, 146), 53, 6)
    d = ImageDraw.Draw(hole)
    for i in range(16):
        c = (230, 180, 20, 255) if (i // 2) % 2 == 0 else (24, 24, 24, 255)
        d.point((i, 0), fill=c)
        d.point((i, 15), fill=c)
        d.point((0, i), fill=c)
        d.point((15, i), fill=c)
    d.rectangle([2, 2, 13, 13], fill=(8, 8, 10, 255))
    d.rectangle([3, 3, 12, 12], fill=(16, 16, 18, 255))
    d.rectangle([5, 5, 10, 10], fill=(4, 4, 6, 255))
    for (x, y) in ((2, 7), (13, 7), (7, 2), (7, 13)):
        d.point((x, y), fill=(220, 40, 30, 255))
    hole.save(out("block/missile_silo_top_open.png"))

    door = tile((96, 102, 100), 54, 5)
    d = ImageDraw.Draw(door)
    d.rectangle([0, 0, 15, 15], outline=(64, 68, 66, 255))
    for x in range(0, 16, 4):
        d.line([(x, 13), (x + 2, 15)], fill=(230, 180, 20, 255))
    door.save(out("block/missile_silo_door.png"))


if __name__ == "__main__":
    tactical_nuke()
    emp_missile()
    incendiary_missile()
    anti_radar_missile()
    item_missile("tactical_nuke", (92, 100, 70), (40, 42, 38), (80, 88, 60), [(0.60, 0.66)], True)
    item_missile("emp_missile", (60, 64, 72), (30, 30, 34), (52, 56, 64), [(0.58, 0.64)], False)
    item_missile("incendiary_missile", (140, 56, 38), (44, 32, 28), (112, 46, 32), [(0.60, 0.66)], False)
    item_missile("anti_radar_missile", (160, 164, 168), (210, 204, 166), (130, 136, 140), [(0.70, 0.76)], False, wings=True)
    mobile_launcher()
    item_truck()
    silo()
    print("v2 textures ok")
