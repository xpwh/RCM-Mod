"""1.8 textures: the wasteland blocks left behind by blasts, fireballs and fallout.

scorched earth, smoldering earth (animated embers), charred log, crater glass, molten rock
(animated), trinitite, fallout and ash. All 16x16 like vanilla blocks; animated ones are vertical
frame strips with an .mcmeta file.
"""
import json
import math
import os
import random

import numpy as np
from PIL import Image

random.seed(1808)
np.random.seed(1808)

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "block")
N = 16


def noise(scale=1.0, octaves=3, seed=None):
    """Tileable value noise in 0..1."""
    rng = np.random.default_rng(seed)
    acc = np.zeros((N, N))
    amp = 1.0
    total = 0.0
    for o in range(octaves):
        cells = max(2, int(4 * scale * (2 ** o)))
        grid = rng.random((cells, cells))
        ys = np.arange(N) * cells / N
        xs = np.arange(N) * cells / N
        y0 = np.floor(ys).astype(int)
        x0 = np.floor(xs).astype(int)
        fy = ys - y0
        fx = xs - x0
        fy = fy * fy * (3 - 2 * fy)
        fx = fx * fx * (3 - 2 * fx)
        y1 = (y0 + 1) % cells
        x1 = (x0 + 1) % cells
        a = grid[np.ix_(y0, x0)]
        b = grid[np.ix_(y0, x1)]
        c = grid[np.ix_(y1, x0)]
        d = grid[np.ix_(y1, x1)]
        top = a + (b - a) * fx[None, :]
        bot = c + (d - c) * fx[None, :]
        acc += amp * (top + (bot - top) * fy[:, None])
        total += amp
        amp *= 0.5
    return acc / total


def lerp(a, b, t):
    a = np.array(a, dtype=float)
    b = np.array(b, dtype=float)
    return a + (b - a) * np.asarray(t)[..., None]


def save(name, rgb, frames=None, frametime=None, interpolate=False):
    os.makedirs(OUT, exist_ok=True)
    if frames is None:
        img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")
    else:
        strip = np.concatenate([np.clip(f, 0, 255) for f in frames], axis=0)
        img = Image.fromarray(strip.astype(np.uint8), "RGB")
        with open(os.path.join(OUT, name + ".png.mcmeta"), "w") as f:
            json.dump({"animation": {"frametime": frametime, "interpolate": interpolate}}, f, indent=2)
    img.save(os.path.join(OUT, name + ".png"))


def specks(img, count, colors, rng):
    for _ in range(count):
        x, y = rng.integers(0, N, 2)
        img[y, x] = colors[rng.integers(0, len(colors))]


def cracks(count, rng, length=(4, 9)):
    """A mask of thin, wandering, tileable cracks."""
    mask = np.zeros((N, N))
    for _ in range(count):
        x, y = rng.random() * N, rng.random() * N
        a = rng.random() * math.tau
        for _ in range(rng.integers(*length)):
            mask[int(y) % N, int(x) % N] = 1.0
            a += rng.normal() * 0.6
            x += math.cos(a)
            y += math.sin(a)
    return mask


# ---------------------------------------------------------------------------------------------- blocks

def scorched_earth():
    rng = np.random.default_rng(11)
    n = noise(1.0, 3, 1)
    img = lerp((28, 22, 18), (62, 50, 40), n)
    img *= 0.85 + 0.3 * rng.random((N, N))[..., None]
    specks(img, 14, [(92, 88, 84), (110, 106, 100), (18, 14, 12)], rng)  # ash and charcoal crumbs
    specks(img, 3, [(70, 34, 20)], rng)                                     # baked red clay
    save("scorched_earth", img)
    return img


def smoldering_earth():
    rng = np.random.default_rng(12)
    base = scorched_earth() * 0.8
    veins = cracks(6, rng)
    glow = noise(1.5, 2, 3)
    frames = []
    for i in range(6):
        phase = math.sin(i / 6 * math.tau)
        f = base.copy()
        heat = np.clip(veins * (0.55 + 0.35 * glow + 0.15 * phase + 0.1 * rng.random((N, N))), 0, 1)
        ember = lerp((150, 30, 8), (255, 170, 40), heat)
        f = np.where(heat[..., None] > 0.05, ember, f)
        # a few hot coals winking in and out
        for _ in range(4):
            x, y = rng.integers(0, N, 2)
            if rng.random() < 0.6:
                f[y, x] = (255, 120 + rng.integers(0, 80), 30)
        frames.append(f)
    save("smoldering_earth", None, frames, frametime=6, interpolate=True)


def charred_log():
    rng = np.random.default_rng(13)
    # side: deep alligator cracking, black char with silvery highlights on the ridges
    side = np.zeros((N, N, 3))
    col_shift = rng.integers(0, 4, N)
    for x in range(N):
        for y in range(N):
            block_x = (x + col_shift[(y // 3) % N]) // 3
            block_y = (y + (block_x % 2) * 2) // 4
            ridge = 1.0 if (x + col_shift[(y // 3) % N]) % 3 == 0 or (y + (block_x % 2) * 2) % 4 == 0 else 0.0
            v = 0.2 + 0.8 * ((block_x * 7 + block_y * 13) % 5) / 5 * (0.8 + 0.4 * rng.random())
            c = lerp((20, 18, 18), (74, 70, 68), v)
            if ridge:
                c = np.array((6, 5, 5), dtype=float)
            side[y, x] = c
    specks(side, 8, [(96, 96, 98), (120, 118, 116)], rng)  # silvery ash glints
    specks(side, 2, [(120, 40, 16)], rng)                  # last embers deep in a crack
    save("charred_log", side)
    # end grain: charred rim, rings still visible inside
    top = np.zeros((N, N, 3))
    for y in range(N):
        for x in range(N):
            r = math.hypot(x - 7.5, y - 7.5)
            if r > 7.0:
                top[y, x] = (10, 9, 9)
            else:
                ring = 1.0 if math.sin(r * 2.6) > 0.55 else 0.0
                burn = min(1.0, max(0.0, (r - 2.5) / 3.5))
                inner = lerp((88, 64, 40), (46, 32, 22), ring)
                top[y, x] = lerp(inner, (24, 20, 18), burn)
    save("charred_log_top", top)


def crater_glass():
    rng = np.random.default_rng(14)
    n = noise(0.8, 3, 5)
    img = lerp((10, 8, 12), (40, 34, 40), n)
    # flow streaks of the melt, glossy highlights
    for i in range(5):
        y0 = rng.random() * N
        for x in range(N):
            y = int(y0 + 2.0 * math.sin(x / N * math.tau + i)) % N
            img[y, x] = img[y, x] * 0.6 + np.array((70, 62, 74)) * 0.4
    specks(img, 6, [(150, 140, 160), (110, 100, 120)], rng)
    specks(img, 4, [(60, 30, 22)], rng)  # rusty iron inclusions
    save("crater_glass", img)


def molten_rock():
    rng = np.random.default_rng(15)
    crust = noise(1.2, 3, 6)
    veins = np.clip(cracks(9, rng, (5, 12)) + (noise(2.0, 2, 7) > 0.76), 0, 1)
    frames = []
    for i in range(8):
        flow = noise(1.6, 2, 20 + i)
        f = lerp((40, 22, 16), (80, 44, 30), crust)
        heat = np.clip(veins * (0.6 + 0.4 * flow), 0, 1)
        f = np.where(heat[..., None] > 0.05, lerp((210, 60, 10), (255, 220, 90), heat), f)
        frames.append(f)
    save("molten_rock", None, frames, frametime=10, interpolate=True)


def trinitite():
    rng = np.random.default_rng(16)
    n = noise(1.0, 3, 8)
    img = lerp((40, 92, 44), (118, 170, 92), n)
    # vesicles: gas bubbles frozen in the glass, dark rim with a bright glint
    for _ in range(7):
        x, y = rng.integers(0, N, 2)
        img[y, x] = (22, 54, 28)
        img[(y - 1) % N, (x - 1) % N] = (178, 220, 150)
    specks(img, 5, [(60, 50, 40), (30, 30, 30)], rng)  # unmelted sand and iron grains
    save("trinitite", img)


def dust(name, low, high, fleck_colors, seed):
    rng = np.random.default_rng(seed)
    n = noise(1.4, 3, seed)
    img = lerp(low, high, n)
    img *= 0.92 + 0.16 * rng.random((N, N))[..., None]
    specks(img, 16, fleck_colors, rng)
    save(name, img)


if __name__ == "__main__":
    smoldering_earth()  # also writes scorched_earth
    charred_log()
    crater_glass()
    molten_rock()
    trinitite()
    dust("fallout", (150, 150, 140), (196, 194, 182), [(176, 186, 120), (120, 120, 112), (210, 208, 196)], 31)
    dust("ash", (86, 84, 82), (132, 130, 128), [(50, 48, 46), (160, 158, 156), (30, 28, 28)], 32)
    print("ok")
