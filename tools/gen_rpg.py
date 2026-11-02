"""RPG-7 textures.

Adds structure atlas patches used by the RPG-7 and PG-7V meshes:
36 WOOD      laminated birch heat shield, red-brown lacquer with grain
37 GUNMETAL  dark blued/parkerized steel of the launch tube
38 BAKELITE  black-brown moulded grip with a fine stipple
39 LENS      the PGO-7 sight's objective: dark blue glass with a reflection

and draws the 32x32 inventory icons item/rocket_launcher.png and item/rpg_rocket.png
(the launcher with a round loaded, and a single PG-7V grenade), shaded as round bodies.
"""
import math
import os
import random

import numpy as np
from PIL import Image

random.seed(707)
np.random.seed(707)
ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures")
ATLAS = os.path.join(ROOT, "entity", "structures.png")
P = 16


def put(atlas, i, img):
    atlas.paste(img, ((i % 8) * P, (i // 8) * P))


def rgba(img):
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def wood():
    img = np.zeros((P, P, 3))
    base = np.array((128, 66, 34), dtype=float)
    for y in range(P):
        for x in range(P):
            # grain runs along x; growth rings wobble a little
            g = math.sin((y + 1.3 * math.sin(x * 0.45 + y * 0.2)) * 1.7)
            shade = 0.86 + 0.12 * g + 0.05 * random.random()
            img[y, x] = base * shade
    for _ in range(3):  # darker grain lines
        y = random.randrange(P)
        for x in range(P):
            yy = int(y + 0.8 * math.sin(x * 0.5)) % P
            img[yy, x] *= 0.75
    img += np.array((14, 6, 0))  # lacquer warmth
    return rgba(img)


def gunmetal():
    img = np.full((P, P, 3), (46, 48, 50), dtype=float)
    img *= (0.9 + 0.18 * np.random.rand(P, P))[..., None]
    for _ in range(5):  # worn edges where the bluing has rubbed off
        x, y = random.randrange(P), random.randrange(P)
        img[y, x] = (92, 94, 96)
    return rgba(img)


def bakelite():
    img = np.full((P, P, 3), (34, 26, 22), dtype=float)
    img *= (0.8 + 0.4 * np.random.rand(P, P))[..., None]
    return rgba(img)


def lens():
    img = np.zeros((P, P, 3))
    for y in range(P):
        for x in range(P):
            d = math.hypot(x - 7.5, y - 7.5) / 8.0
            img[y, x] = (22 + 30 * (1 - d), 34 + 40 * (1 - d), 58 + 60 * (1 - d))
    img[3:6, 4:7] = (170, 200, 220)  # reflection
    return rgba(img)


# ------------------------------------------------------------------ icons
GUN = (58, 60, 63)
GUN_DARK = (38, 40, 42)
WOOD = (140, 74, 38)
WOOD_DARK = (104, 52, 26)
GRIP = (40, 31, 26)
OLIVE = (92, 100, 62)
OLIVE_DARK = (66, 72, 44)
STEEL = (150, 152, 150)
BLACK = (26, 26, 26)
GLASS = (70, 110, 150)


def shade(col, t):
    """t in [-1, 1] across the body: light from the top-left, dark underneath."""
    k = 1.18 - 0.42 * (t + 1) / 2
    if t < -0.55:
        k += 0.15  # specular line
    return tuple(int(max(0, min(255, c * k))) for c in col)


def draw_body(img, start, end, segments):
    """Round body along start->end; segments: (s0, s1, r0, r1, colour)."""
    (x0, y0), (x1, y1) = start, end
    length = math.hypot(x1 - x0, y1 - y0)
    ax, ay = (x1 - x0) / length, (y1 - y0) / length
    nx, ny = ay, -ax  # perpendicular, pointing up-left on screen for an up-right axis
    w, h = img.size
    px = img.load()
    for y in range(h):
        for x in range(w):
            cx, cy = x + 0.5 - x0, y + 0.5 - y0
            s = cx * ax + cy * ay
            d = cx * nx + cy * ny
            for s0, s1, r0, r1, col in segments:
                if s0 <= s <= s1:
                    r = r0 + (r1 - r0) * (s - s0) / max(1e-6, s1 - s0)
                    if abs(d) <= r:
                        px[x, y] = shade(col, -d / max(r, 0.5)) + (255,)
    return (ax, ay), (nx, ny)


def draw_box(img, origin, axis, normal, s0, s1, d0, d1, col):
    """Flat part (grip, sight) in the axis frame: s along the axis, d along the normal (+ = up-left)."""
    w, h = img.size
    px = img.load()
    ax, ay = axis
    nx, ny = normal
    for y in range(h):
        for x in range(w):
            cx, cy = x + 0.5 - origin[0], y + 0.5 - origin[1]
            s = cx * ax + cy * ay
            d = cx * nx + cy * ny
            if s0 <= s <= s1 and d0 <= d <= d1:
                px[x, y] = col + (255,)


def outline(img):
    a = np.array(img)
    alpha = a[..., 3] > 0
    out = a.copy()
    h, w = alpha.shape
    for y in range(h):
        for x in range(w):
            if alpha[y, x]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                xx, yy = x + dx, y + dy
                if 0 <= xx < w and 0 <= yy < h and alpha[yy, xx]:
                    out[y, x] = (16, 16, 16, 255)
                    break
    return Image.fromarray(out, "RGBA")


def launcher_icon():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    start, end = (2.5, 28.5), (30.0, 2.0)
    # behind the body: pistol grip and trigger guard hang below (down-right), the sight sits above
    axis = ((end[0] - start[0]), (end[1] - start[1]))
    n = math.hypot(*axis)
    axis = (axis[0] / n, axis[1] / n)
    normal = (axis[1], -axis[0])
    draw_box(img, start, axis, normal, 17.6, 20.4, -6.0, 0.0, GRIP)
    draw_box(img, start, axis, normal, 20.4, 23.4, -4.2, -3.0, GUN_DARK)  # trigger guard
    draw_box(img, start, axis, normal, 22.4, 23.4, -4.2, 0.0, GUN_DARK)
    draw_box(img, start, axis, normal, 14.0, 20.5, 1.0, 3.8, GUN_DARK)  # PGO-7 sight body
    draw_box(img, start, axis, normal, 19.3, 20.5, 1.4, 3.4, GLASS)
    draw_body(img, start, end, [
        (0.0, 3.5, 2.6, 1.4, GUN_DARK),  # venturi bell
        (3.5, 10.5, 1.3, 1.3, GUN),
        (10.5, 18.0, 2.1, 2.1, WOOD),  # heat shield
        (12.0, 12.8, 2.2, 2.2, WOOD_DARK),
        (18.0, 24.5, 1.3, 1.3, GUN),
        (24.5, 25.5, 1.6, 1.6, GUN_DARK),  # muzzle ring
        (25.5, 27.5, 0.8, 0.8, OLIVE_DARK),  # sustainer stub
        (27.5, 29.0, 0.8, 2.6, OLIVE),
        (29.0, 33.5, 2.6, 2.6, OLIVE),
        (31.0, 31.6, 2.7, 2.7, BLACK),
        (33.5, 36.5, 2.6, 0.9, OLIVE),
        (36.5, 38.6, 0.6, 0.3, STEEL),
    ])
    return outline(img)


def grenade_icon():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    start, end = (4.0, 28.0), (29.5, 2.5)
    axis = ((end[0] - start[0]), (end[1] - start[1]))
    n = math.hypot(*axis)
    axis = (axis[0] / n, axis[1] / n)
    normal = (axis[1], -axis[0])
    for side in (-1, 1):  # folded-out tail fins
        draw_box(img, start, axis, normal, 0.3, 4.5, *(sorted((side * 0.8, side * 3.8))), (110, 112, 112))
    draw_body(img, start, end, [
        (0.0, 1.0, 1.1, 1.0, BLACK),
        (1.0, 15.0, 1.0, 1.0, OLIVE_DARK),
        (15.0, 18.0, 1.0, 3.0, OLIVE),
        (18.0, 25.0, 3.0, 3.0, OLIVE),
        (21.5, 22.3, 3.1, 3.1, BLACK),
        (25.0, 30.5, 3.0, 0.9, OLIVE),
        (30.5, 33.0, 0.6, 0.3, STEEL),
    ])
    return outline(img)


def main():
    atlas = Image.open(ATLAS).convert("RGBA")
    put(atlas, 36, wood())
    put(atlas, 37, gunmetal())
    put(atlas, 38, bakelite())
    put(atlas, 39, lens())
    atlas.save(ATLAS)
    launcher_icon().save(os.path.join(ROOT, "item", "rocket_launcher.png"))
    grenade_icon().save(os.path.join(ROOT, "item", "rpg_rocket.png"))


if __name__ == "__main__":
    main()
