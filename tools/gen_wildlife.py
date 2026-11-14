"""The wildlife atlas for AmbientLife: a row of 8 tiles of 32 px.

  0 monarch wing, 1 cabbage white wing, 2 brimstone wing, 3 blue wing   (a fore- and hindwing, hinged on the left edge)
  4 bird wing (dark, feathered tip, hinged on the left)   5 firefly glow (soft, white: tinted by the vertex colour)
  6 white (for the boxes of the deer and the fish)        7 fish tail fin

    python3 tools/gen_wildlife.py
"""
import math
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "client", "resources", "assets", "ballisticmissiles", "textures", "effect",
                   "wildlife.png")
T = 32
img = Image.new("RGBA", (T * 8, T), (0, 0, 0, 0))
px = img.load()


def wing_shape(u, v):
    """Inside a butterfly's wing (u from the hinge 0..1 outwards, v 0..1 front to back): fore- and hindwing lobes."""
    fore = ((u - 0.05) / 0.95) ** 2 + ((v - 0.32) / 0.32) ** 2 <= 1.0 and u >= 0.0
    hind = ((u - 0.0) / 0.68) ** 2 + ((v - 0.72) / 0.28) ** 2 <= 1.0
    return fore, hind


def butterfly(tile, base, edge, spots, veins):
    ox = tile * T
    for y in range(T):
        for x in range(T):
            u, v = x / (T - 1), y / (T - 1)
            fore, hind = wing_shape(u, v)
            if not (fore or hind):
                continue
            c = base
            # dark margin with pale spots, veins
            r = (((u - 0.05) / 0.95) ** 2 + ((v - 0.32) / 0.32) ** 2) if fore else ((u / 0.68) ** 2 + ((v - 0.72) / 0.28) ** 2)
            if r > 0.62:
                c = edge
                if spots and (x + y * 3) % 7 == 0:
                    c = (245, 240, 230)
            elif veins and (abs((v - 0.5) * 3 - u * 1.2) % 0.5 < 0.06 or x % 9 == 0):
                c = edge
            shade = 0.85 + 0.15 * u
            px[ox + x, y] = tuple(int(ch * shade) for ch in c) + (255,)


butterfly(0, (238, 128, 28), (28, 18, 12), True, True)       # monarch
butterfly(1, (244, 242, 232), (60, 58, 56), False, False)    # cabbage white
butterfly(2, (236, 226, 70), (190, 180, 60), False, False)   # brimstone
butterfly(3, (70, 140, 240), (20, 30, 70), True, False)      # blue

# a bird's wing: a long tapering shape, the primaries spread at the tip
ox = 4 * T
for y in range(T):
    for x in range(T):
        u, v = x / (T - 1), y / (T - 1)
        width = 0.42 * (1.0 - u * 0.65)
        inside = abs(v - 0.45 + u * 0.12) < width
        if u > 0.75 and inside:
            inside = (y + x // 3) % 4 != 0  # the spread primaries
        if inside:
            px[ox + x, y] = (36, 34, 38, 255)

# a firefly's glow: a soft round falloff
ox = 5 * T
for y in range(T):
    for x in range(T):
        d = math.hypot(x - 15.5, y - 15.5) / 15.5
        a = max(0.0, 1.0 - d) ** 2.2
        core = max(0.0, 1.0 - d * 4.0)
        px[ox + x, y] = (255, 255, int(255 * (0.7 + 0.3 * core)), int(255 * min(1.0, a + core)))

# white
for y in range(T):
    for x in range(6 * T, 7 * T):
        px[x, y] = (255, 255, 255, 255)

# a fish's tail fin: a forked fan
ox = 7 * T
for y in range(T):
    for x in range(T):
        u, v = x / (T - 1), y / (T - 1)
        spread = 0.08 + 0.42 * u
        if abs(v - 0.5) < spread and not (u > 0.7 and abs(v - 0.5) < (u - 0.7) * 0.9):
            px[ox + x, y] = (170 - int(40 * u), 180 - int(40 * u), 190 - int(30 * u), 255)

img.save(OUT)
print("wrote", OUT)
