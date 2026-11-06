"""1.10 item icons (32x32): AKM rifle, AK magazine (ball), AK magazine (tracer)."""
import os

from PIL import Image, ImageDraw

import gen_rpg as base

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "item")
STEEL = (54, 56, 60)
STEEL_LIGHT = (96, 98, 104)
WOOD = (132, 60, 30)
WOOD_DARK = (98, 42, 20)
BAKELITE = (150, 62, 30)
BAKELITE_DARK = (110, 44, 22)
GRIP = (86, 34, 18)
GREEN = (90, 220, 70)


def poly(d, pts, col, outline=None):
    d.polygon(pts, fill=col + (255,), outline=(outline + (255,)) if outline else None)


def ak():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # side view, muzzle to the right, tilted slightly up
    poly(d, [(1, 13), (9, 12), (9, 17), (1, 21)], WOOD)                     # stock
    poly(d, [(1, 13), (9, 12), (9, 13), (1, 14)], WOOD_DARK)
    d.rectangle([0, 13, 1, 21], fill=STEEL + (255,))                         # butt plate
    poly(d, [(9, 11), (19, 10), (19, 15), (9, 16)], STEEL)                   # receiver
    d.line([(10, 11), (18, 10)], fill=STEEL_LIGHT + (255,))
    poly(d, [(19, 11), (26, 10), (26, 13), (19, 14)], WOOD)                  # lower handguard
    poly(d, [(19, 9), (25, 8), (25, 10), (19, 11)], WOOD_DARK)               # upper handguard
    d.line([(26, 11), (31, 10)], fill=STEEL + (255,), width=1)               # barrel
    d.line([(25, 9), (27, 9)], fill=STEEL + (255,))                          # gas block
    d.rectangle([28, 7, 29, 10], fill=STEEL + (255,))                        # front sight
    d.rectangle([30, 9, 31, 11], fill=STEEL_LIGHT + (255,))                  # brake
    poly(d, [(11, 16), (13, 16), (12, 21), (10, 21)], GRIP)                  # pistol grip
    poly(d, [(15, 15), (18, 15), (21, 22), (19, 26), (16, 22)], BAKELITE)    # curved magazine
    poly(d, [(17, 18), (18, 18), (20, 23), (19, 24)], BAKELITE_DARK)
    d.line([(13, 16), (15, 17)], fill=STEEL + (255,))                        # trigger guard
    return base.outline(img)


def magazine(tracer):
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    poly(d, [(9, 3), (17, 3), (25, 24), (21, 29), (12, 13)], BAKELITE)
    poly(d, [(13, 5), (15, 5), (22, 23), (20, 25)], BAKELITE_DARK)
    d.rectangle([9, 2, 17, 3], fill=STEEL + (255,))                          # feed lips
    d.rectangle([11, 1, 15, 2], fill=(200, 160, 70, 255))                    # top round (brass)
    if tracer:
        d.rectangle([12, 0, 14, 0], fill=GREEN + (255,))                     # green tip
        poly(d, [(13, 9), (19, 9), (21, 13), (15, 13)], GREEN)               # painted band
    poly(d, [(21, 29), (25, 24), (26, 25), (22, 30)], STEEL)                 # floor plate
    return base.outline(img)


def main():
    ak().save(os.path.join(OUT, "ak47.png"))
    magazine(False).save(os.path.join(OUT, "ak_magazine.png"))
    magazine(True).save(os.path.join(OUT, "ak_magazine_tracer.png"))


if __name__ == "__main__":
    main()
