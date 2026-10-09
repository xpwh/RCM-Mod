"""7.4 item icons: the FPV kamikaze drone (X frame, props, battery, warhead underneath) and the
Iron Dome's Tamir launcher pod (20 red-capped cells in an olive box)."""
import os

from PIL import ImageDraw, Image

import gen_rpg as base
from gen_icons_v12 import OUT, STEEL, poly

CARBON = (34, 35, 38)
CARBON_EDGE = (70, 72, 78)
PROP = (20, 20, 22)
OLIVE = (84, 96, 52)
OLIVE_DARK = (58, 67, 36)
OLIVE_EDGE = (120, 134, 78)
RED = (196, 40, 32)
YELLOW = (226, 188, 40)


def drone():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # X frame seen from above at an angle
    d.line([(6, 8), (26, 22)], fill=CARBON + (255,), width=3)
    d.line([(26, 8), (6, 22)], fill=CARBON + (255,), width=3)
    for (x, y) in [(6, 8), (26, 8), (6, 22), (26, 22)]:
        d.ellipse([x - 5, y - 2, x + 5, y + 2], outline=PROP + (170,))            # prop disc
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=CARBON_EDGE + (255,))      # motor
    d.rectangle([12, 12, 20, 18], fill=CARBON + (255,))                          # stack
    d.rectangle([13, 11, 19, 14], fill=YELLOW + (255,))                          # battery
    # PG-7 warhead strapped underneath, nose forward (to the right)
    poly(d, [(10, 20), (21, 20), (25, 22), (21, 24), (10, 24)], OLIVE)
    d.line([(25, 22), (28, 22)], fill=STEEL + (255,))
    d.line([(10, 20), (21, 20)], fill=OLIVE_EDGE + (255,))
    d.point((16, 13), fill=(220, 60, 50, 255))                                   # camera LED
    return base.outline(img)


def pod():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # the box in perspective: front face with the cells, top and side
    poly(d, [(4, 10), (20, 10), (20, 29), (4, 29)], OLIVE)
    poly(d, [(4, 10), (11, 3), (27, 3), (20, 10)], OLIVE_EDGE)
    poly(d, [(20, 10), (27, 3), (27, 22), (20, 29)], OLIVE_DARK)
    for row in range(5):
        for col in range(4):
            x = 6 + col * 4
            y = 12 + row * 3 + row // 2
            d.rectangle([x, y, x + 2, y + 1], fill=RED + (255,))
    d.line([(21, 14), (26, 9)], fill=(200, 170, 60, 255))                        # hazard stripe
    d.line([(21, 18), (26, 13)], fill=(200, 170, 60, 255))
    return base.outline(img)


def main():
    drone().save(os.path.join(OUT, "fpv_drone.png"))
    pod().save(os.path.join(OUT, "tamir_pod.png"))


if __name__ == "__main__":
    main()
