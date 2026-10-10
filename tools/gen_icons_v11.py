"""1.9 item icons (32x32): B61-11, Poseidon, tungsten rod, orbital uplink ground station."""
import math
import os

from PIL import Image

import gen_rpg as base

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "item")

GRAY = (160, 164, 162)
GRAY_DARK = (96, 100, 100)
STEEL = (190, 194, 196)
BLACK = (28, 28, 30)
HULL = (40, 42, 46)
HULL_LIGHT = (62, 64, 70)
TUNG = (86, 88, 96)
CHAR = (52, 40, 32)
WHITE = (232, 234, 230)
CONCRETE = (150, 146, 138)


def frame(start, end):
    axis = (end[0] - start[0], end[1] - start[1])
    n = math.hypot(*axis)
    axis = (axis[0] / n, axis[1] / n)
    return axis, (axis[1], -axis[0])


def b61():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    start, end = (3.5, 28.5), (29.5, 2.5)
    axis, normal = frame(start, end)
    for side in (-1, 1):  # tail fins
        base.draw_box(img, start, axis, normal, 0.3, 5.0, *(sorted((side * 2.0, side * 4.6))), GRAY_DARK)
    base.draw_body(img, start, end, [
        (0.0, 5.5, 1.6, 2.3, GRAY_DARK),
        (5.5, 6.2, 2.4, 2.4, BLACK),
        (6.2, 25.0, 2.3, 2.3, GRAY),
        (16.0, 16.6, 2.35, 2.35, BLACK),
        (25.0, 25.6, 2.35, 2.35, BLACK),
        (25.6, 36.8, 2.3, 0.2, STEEL),
    ])
    return base.outline(img)


def poseidon():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    start, end = (3.0, 29.0), (29.5, 2.5)
    axis, normal = frame(start, end)
    for side in (-1, 1):  # X fins
        base.draw_box(img, start, axis, normal, 4.0, 8.0, *(sorted((side * 2.8, side * 5.0))), HULL_LIGHT)
    base.draw_body(img, start, end, [
        (0.0, 4.5, 3.6, 3.6, HULL_LIGHT),  # pump-jet duct
        (0.0, 1.0, 2.2, 2.2, BLACK),
        (4.5, 8.0, 2.6, 3.4, HULL),
        (8.0, 31.0, 3.4, 3.4, HULL),
        (15.0, 15.6, 3.45, 3.45, BLACK),
        (23.0, 23.6, 3.45, 3.45, BLACK),
        (31.0, 37.3, 3.4, 0.4, HULL_LIGHT),
    ])
    return base.outline(img)


def tungsten_rod():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    start, end = (4.0, 28.0), (29.0, 3.0)
    axis, normal = frame(start, end)
    for side in (-1, 1):
        base.draw_box(img, start, axis, normal, 0.2, 3.0, *(sorted((side * 1.2, side * 3.4))), STEEL)
    base.draw_body(img, start, end, [
        (0.0, 4.5, 1.6, 1.6, GRAY_DARK),
        (4.5, 27.0, 1.35, 1.35, TUNG),
        (15.0, 15.6, 1.4, 1.4, BLACK),
        (27.0, 35.4, 1.35, 0.15, CHAR),
    ])
    return base.outline(img)


def uplink():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    px = img.load()

    def fill(x0, y0, x1, y1, col):
        for y in range(max(0, y0), min(32, y1)):
            for x in range(max(0, x0), min(32, x1)):
                px[x, y] = col + (255,)

    # pad, shelter and pedestal
    fill(2, 25, 30, 30, CONCRETE)
    fill(2, 29, 30, 30, (110, 106, 100))
    fill(18, 18, 29, 25, WHITE)
    fill(24, 20, 26, 25, (90, 96, 104))
    fill(19, 17, 29, 18, (170, 174, 176))
    fill(9, 16, 12, 25, (170, 174, 176))
    # tilted dish: an ellipse seen from the front and side, with the feed boom
    cx, cy = 11.0, 10.0
    for y in range(32):
        for x in range(32):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            u = (dx * math.cos(0.6) + dy * math.sin(0.6)) / 9.0
            v = (-dx * math.sin(0.6) + dy * math.cos(0.6)) / 5.0
            d = u * u + v * v
            if d <= 1.0:
                shade = int(200 + 40 * (1 - d) - 30 * v)
                px[x, y] = (shade, shade, min(255, shade + 4), 255)
    for t in range(9):
        x = int(cx + t * 0.8)
        y = int(cy - t * 0.9)
        px[x, y] = (60, 60, 64, 255)
    fill(17, 1, 20, 3, (40, 40, 44))
    fill(28, 15, 29, 17, (230, 40, 40))  # warning light
    return base.outline(img)


def main():
    b61().save(os.path.join(OUT, "b61.png"))
    poseidon().save(os.path.join(OUT, "poseidon.png"))
    tungsten_rod().save(os.path.join(OUT, "tungsten_rod.png"))
    uplink().save(os.path.join(OUT, "orbital_uplink.png"))


if __name__ == "__main__":
    main()
