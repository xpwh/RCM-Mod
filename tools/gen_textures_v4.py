"""Textures for the content added in 1.4: Tsar Bomba, antimatter missile, moon rocket, Trident SLBM,
radiation suit, blast door, reinforced concrete, laser defense, jammer and submarine.

Reuses the helpers of gen_textures.py / gen_textures_v2.py / gen_textures_v3.py and only writes the new
files (plus the extra patches of the structure atlas, which gen_textures_v3.structures() draws).
"""
import random

import numpy as np
from PIL import Image, ImageDraw

import gen_textures as g
import gen_textures_v2 as g2
from gen_textures import (checker_band, hazard_band, item_missile, missile_texture, out, outline, small_print, stencil_decal, stripe,
                          tile, trefoil_decal)

random.seed(1414)
np.random.seed(1414)


# ---------------------------------------------------------------------------------------------- missiles

def tsar_bomba():
    missile_texture("tsar_bomba", 12.0, [
        (0.78, 0.95, (60, 60, 64)),
        (7.38, 7.92, (40, 42, 46)),
        (10.15, 10.45, (40, 42, 46)),
        (10.45, 12.0, (150, 30, 26)),  # red nose
    ], (214, 210, 196), (196, 192, 180), [
        checker_band(2.0, 2.9, 8, a=(150, 30, 26)),
        stencil_decal("AN602", 6.9, (8, 136), (150, 30, 26), 13),
        stencil_decal("TSAR BOMBA", 5.3, (40, 168), (40, 40, 40), 7),
        trefoil_decal((3.6, 4.6), (100, 228), ring=(150, 30, 26), fill=(245, 200, 20), r=18),
        hazard_band(1.3, 1.6),
        stripe(9.6, 9.75, (150, 30, 26)),
    ], soot=0.45)


def antimatter():
    def rings(pil, d, row):
        cy = (row(6.3) + row(5.2)) // 2
        for cx in (64, 192):
            for rr, col in ((15, (120, 60, 255)), (10, (40, 230, 255)), (5, (255, 255, 255))):
                d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=col + (255,))

    missile_texture("antimatter_missile", 9.0, [
        (0.0, 1.0, (30, 30, 36)),
        (5.62, 5.90, (20, 20, 26)),
        (5.90, 9.0, (236, 236, 244)),
        (5.90, 6.02, (120, 60, 255)),
        (8.7, 9.0, (40, 230, 255)),
    ], (52, 48, 70), (44, 40, 60), [
        rings,
        stencil_decal("AM-1  PENNING TRAP", 4.6, (40, 168), (200, 190, 255), 8),
        small_print(["CONTAINMENT", "FIELD ON"], 3.0, 100, (40, 230, 255)),
        stripe(2.4, 2.5, (120, 60, 255)),
    ], soot=0.2)


def moon_rocket():
    missile_texture("moon_rocket", 12.0, [
        (0.45, 0.92, (60, 60, 64)),
        (5.20, 5.50, (28, 28, 30)),
        (7.98, 8.28, (28, 28, 30)),
        (9.80, 12.0, (236, 236, 232)),
    ], (240, 240, 236), (30, 30, 32), [
        checker_band(2.0, 3.0, 4),
        checker_band(6.2, 7.2, 4),
        stencil_decal("SELENE", 9.0, (8, 136), (30, 30, 30), 13),
        stencil_decal("METEOR PAYLOAD", 4.5, (40, 168), (200, 40, 30), 7),
        stripe(10.4, 10.6, (30, 60, 150)),
    ], soot=0.4)


def trident():
    missile_texture("trident_missile", 12.0, [
        (0.45, 0.92, (40, 40, 44)),
        (5.20, 5.50, (30, 30, 32)),
        (7.98, 8.28, (30, 30, 32)),
        (9.80, 12.0, (30, 32, 36)),
    ], (36, 40, 46), (30, 34, 40), [
        stencil_decal("UGM-133", 6.3, (8, 136), (210, 210, 205), 11),
        stencil_decal("TRIDENT II", 3.3, (40, 168), (210, 210, 205), 8),
        trefoil_decal((8.4, 9.2), (100, 228), ring=(210, 210, 205), fill=(245, 200, 20), r=11),
        stripe(9.0, 9.1, (200, 160, 30)),
    ], soot=0.35)


# ---------------------------------------------------------------------------------------------- radiation suit

YELLOW = (226, 190, 40)
YELLOW_DARK = (176, 140, 26)
GREY = (70, 72, 74)


def armor_layers():
    """Humanoid armour layers (64x32, vanilla UV layout): yellow rubberised suit with taped seams,
    a respirator mask and visor on the head, dark gloves and boots."""
    n = g.noise(64, 32, 3.0, 3)

    def base(color, strength=0.12):
        arr = np.zeros((32, 64, 4), dtype=np.float64)
        arr[..., :3] = color
        arr[..., 3] = 255
        g.shade(arr, n, strength)
        return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")

    # layer 1: helmet, chest, arms, boots
    img = base(YELLOW)
    d = ImageDraw.Draw(img)
    # head (front face at 8..16 x 8..16): visor + respirator
    d.rectangle([9, 9, 14, 11], fill=(60, 90, 100, 255))
    d.line([(9, 9), (14, 9)], fill=(150, 190, 200, 255))
    d.rectangle([10, 12, 13, 15], fill=GREY + (255,))
    d.ellipse([7, 12, 10, 15], fill=(40, 40, 42, 255))  # filter canisters (wrap onto the sides)
    d.ellipse([13, 12, 16, 15], fill=(40, 40, 42, 255))
    # hat layer (32..64 x 0..16) left transparent
    d.rectangle([32, 0, 63, 15], fill=(0, 0, 0, 0))
    # taped seams on the body (20..28 x 20..32) and the trefoil patch
    d.line([(24, 20), (24, 31)], fill=YELLOW_DARK + (255,))
    d.line([(20, 25), (27, 25)], fill=YELLOW_DARK + (255,))
    d.ellipse([21, 21, 23, 23], fill=(20, 20, 20, 255))
    # gloves: bottom of the arms (40..56 x 28..32)
    d.rectangle([40, 28, 55, 31], fill=GREY + (255,))
    # boots on layer 1 use the leg area (0..16 x 16..32): lower half dark
    d.rectangle([0, 26, 15, 31], fill=(40, 40, 42, 255))
    img.save(out("entity/equipment/humanoid/hazmat.png"))

    # layer 2: leggings
    img = base(YELLOW)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 16, 15, 17], fill=YELLOW_DARK + (255,))
    d.line([(2, 16), (2, 31)], fill=YELLOW_DARK + (255,))
    d.rectangle([16, 16, 39, 21], fill=YELLOW_DARK + (255,))  # belt area
    img.save(out("entity/equipment/humanoid_leggings/hazmat.png"))


def item_icons_suit():
    def icon(name, draw):
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        draw(d)
        outline(img)
        img.save(out(f"item/{name}.png"))

    y = YELLOW + (255,)
    k = YELLOW_DARK + (255,)
    gr = GREY + (255,)

    def helmet(d):
        d.rectangle([4, 3, 11, 11], fill=y)
        d.rectangle([5, 5, 10, 7], fill=(60, 90, 100, 255))
        d.rectangle([6, 9, 9, 12], fill=gr)
        d.ellipse([3, 9, 5, 11], fill=(40, 40, 42, 255))
        d.ellipse([10, 9, 12, 11], fill=(40, 40, 42, 255))

    def suit(d):
        d.rectangle([4, 3, 11, 13], fill=y)
        d.rectangle([1, 3, 3, 10], fill=y)
        d.rectangle([12, 3, 14, 10], fill=y)
        d.rectangle([1, 10, 3, 11], fill=gr)
        d.rectangle([12, 10, 14, 11], fill=gr)
        d.line([(7, 3), (7, 13)], fill=k)
        d.ellipse([5, 5, 7, 7], fill=(20, 20, 20, 255))

    def legs(d):
        d.rectangle([4, 2, 11, 4], fill=k)
        d.rectangle([4, 4, 7, 14], fill=y)
        d.rectangle([8, 4, 11, 14], fill=y)

    def boots(d):
        d.rectangle([2, 8, 6, 13], fill=(40, 40, 42, 255))
        d.rectangle([9, 8, 13, 13], fill=(40, 40, 42, 255))
        d.rectangle([2, 7, 6, 8], fill=y)
        d.rectangle([9, 7, 13, 8], fill=y)

    icon("hazmat_helmet", helmet)
    icon("hazmat_suit", suit)
    icon("hazmat_leggings", legs)
    icon("hazmat_boots", boots)


# ---------------------------------------------------------------------------------------------- bunker blocks

def bunker_blocks():
    concrete = tile((132, 132, 128), 81, 7)
    d = ImageDraw.Draw(concrete)
    for x in (3, 12):
        for yy in (3, 12):
            d.point((x, yy), fill=(96, 96, 92, 255))  # form-tie holes
    d.line([(0, 8), (15, 8)], fill=(118, 118, 114, 255))
    concrete.save(out("block/reinforced_concrete.png"))

    for half in ("top", "bottom"):
        door = tile((92, 98, 96), 82 if half == "top" else 83, 4)
        d = ImageDraw.Draw(door)
        d.rectangle([0, 0, 15, 15], outline=(60, 64, 62, 255))
        d.rectangle([2, 2, 13, 13], outline=(70, 74, 72, 255))
        for x in (3, 12):
            for yy in (3, 12):
                d.point((x, yy), fill=(170, 172, 168, 255))
        if half == "bottom":
            for x in range(0, 16, 4):
                d.line([(x, 15), (x + 3, 12)], fill=(230, 180, 20, 255))
            d.rectangle([11, 2, 13, 5], fill=(40, 40, 42, 255))  # wheel lock housing
        else:
            d.ellipse([4, 4, 11, 11], outline=(40, 40, 42, 255))  # locking wheel
            d.line([(7, 4), (8, 11)], fill=(40, 40, 42, 255))
            d.line([(4, 7), (11, 8)], fill=(40, 40, 42, 255))
            d.rectangle([6, 1, 9, 2], fill=(60, 90, 100, 255))  # peephole
        door.save(out(f"block/blast_door_{half}.png"))

    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([4, 1, 11, 14], fill=(92, 98, 96, 255))
    d.rectangle([5, 2, 10, 13], outline=(60, 64, 62, 255))
    d.ellipse([6, 4, 9, 7], outline=(40, 40, 42, 255))
    for x in range(4, 12, 3):
        d.line([(x, 14), (x + 2, 12)], fill=(230, 180, 20, 255))
    outline(img)
    img.save(out("item/blast_door.png"))


def plinth(name, base, seed, marking):
    img = tile(base, seed, 5)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], outline=tuple(int(c * 0.7) for c in base) + (255,))
    d.rectangle([4, 5, 11, 10], fill=marking + (255,))
    img.save(out(f"block/{name}.png"))


def structure_patches():
    """Extra patches for textures/entity/structures.png (26..29), painted onto the existing atlas."""
    path = out("entity/structures.png")
    atlas = Image.open(path).convert("RGBA")
    patch = 16

    def put(i, img):
        atlas.paste(img, ((i % 8) * patch, (i // 8) * patch))

    beam = Image.new("RGBA", (patch, patch), (140, 230, 255, 255))
    put(26, beam)
    put(27, Image.new("RGBA", (patch, patch), (190, 80, 255, 255)))
    n = g.noise(patch, patch, 2.0, 3)
    hull = g2.noisy((30, 32, 36), n, 0.25)
    d = ImageDraw.Draw(hull)
    for x in range(0, 16, 4):
        for yy in range(0, 16, 4):
            d.point((x, yy), fill=(22, 24, 26, 255))  # anechoic tiles
    put(28, hull)
    rock = g2.noisy((70, 52, 40), g.noise(patch, patch, 3.0, 3), 0.5)
    d = ImageDraw.Draw(rock)
    for _ in range(10):
        x, yy = random.randrange(16), random.randrange(16)
        d.point((x, yy), fill=(255, 150, 40, 255))  # glowing cracks
    put(29, rock)
    atlas.save(path)


if __name__ == "__main__":
    tsar_bomba()
    antimatter()
    moon_rocket()
    trident()
    item_missile("tsar_bomba", (214, 210, 196), (150, 30, 26), (196, 192, 180), [(0.36, 0.41), (0.62, 0.67)], True)
    item_missile("antimatter_missile", (40, 38, 52), (236, 236, 244), (52, 48, 70), [(0.62, 0.67)], False)
    item_missile("moon_rocket", (240, 240, 236), (236, 236, 232), (40, 40, 42), [(0.30, 0.36), (0.56, 0.62)], False)
    item_missile("trident_missile", (40, 44, 50), (30, 32, 36), (34, 38, 44), [(0.60, 0.65)], True)
    armor_layers()
    item_icons_suit()
    bunker_blocks()
    plinth("laser_defense", (96, 104, 70), 91, (140, 230, 255))
    plinth("jammer", (90, 98, 64), 92, (190, 80, 255))
    plinth("submarine", (34, 36, 40), 93, (60, 64, 70))
    structure_patches()
    print("v4 textures ok")
