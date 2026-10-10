"""1.13 item icons: the AK-47's slab-sided steel magazines (ball, tracer). The rifle icon is rendered from the model."""
import os

from PIL import ImageDraw, Image

import gen_rpg as base
from gen_icons_v12 import OUT, GREEN, STEEL, poly

MAG = (46, 48, 53)
MAG_EDGE = (92, 94, 100)


def magazine(tracer):
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    poly(d, [(9, 3), (17, 3), (25, 24), (21, 29), (12, 13)], MAG)
    d.line([(17, 3), (25, 24)], fill=MAG_EDGE + (255,))                     # worn front edge
    d.rectangle([9, 2, 17, 3], fill=STEEL + (255,))                          # feed lips
    d.rectangle([11, 1, 15, 2], fill=(200, 160, 70, 255))                    # top round (brass)
    if tracer:
        d.rectangle([12, 0, 14, 0], fill=GREEN + (255,))                     # green tip
        poly(d, [(13, 9), (19, 9), (21, 13), (15, 13)], GREEN)               # painted band
    poly(d, [(21, 29), (25, 24), (26, 25), (22, 30)], MAG_EDGE)              # floor plate
    return base.outline(img)


def main():
    magazine(False).save(os.path.join(OUT, "ak_magazine.png"))
    magazine(True).save(os.path.join(OUT, "ak_magazine_tracer.png"))


if __name__ == "__main__":
    main()
