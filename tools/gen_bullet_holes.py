"""Bullet hole decals, textures/effect/bullet_hole.png (64 x 64, four 32 px cells):

0 rock / concrete: a black hole in a ring of pale crushed chips, a few radial cracks
1 wood: a dark hole with light, torn splinters standing out along the grain
2 metal: a punched hole with a bright, scraped rim of bare steel
3 earth: a dark crumbly crater with the soil thrown out round it
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "effect", "bullet_hole.png")
rng = np.random.default_rng(39)
S = 32


def grid():
    y, x = np.mgrid[0:S, 0:S].astype(float)
    x = (x - S / 2 + 0.5) / (S / 2)
    y = (y - S / 2 + 0.5) / (S / 2)
    return x, y, np.sqrt(x * x + y * y), np.arctan2(y, x)


def cell_rock():
    x, y, r, a = grid()
    rgba = np.zeros((S, S, 4))
    jag = 0.55 + 0.12 * np.sin(a * 7 + rng.random() * 6) + 0.08 * rng.random((S, S))
    chips = r < jag
    rgba[chips] = (150, 146, 138, 150)
    for _ in range(5):
        ang = rng.random() * 6.28
        d = np.abs(np.angle(np.exp(1j * (a - ang))))
        crack = (d < 0.05 + 0.02 * r) & (r < 0.95) & (r > 0.2)
        rgba[crack] = (40, 38, 36, 200)
    hole = r < 0.24 + 0.04 * np.sin(a * 5)
    rgba[hole] = (12, 11, 10, 255)
    soot = (r >= 0.24) & (r < 0.38)
    rgba[soot] = (60, 56, 52, 220)
    return rgba


def cell_wood():
    x, y, r, a = grid()
    rgba = np.zeros((S, S, 4))
    # splinters torn out along the grain (vertical), pale fresh wood
    torn = (np.abs(x) < 0.32 + 0.1 * rng.random((S, S))) & (np.abs(y) < 0.9) & (r < 0.95)
    rgba[torn] = (196, 160, 112, 170)
    for _ in range(6):
        cx = rng.normal(0, 0.15)
        width = 0.03 + 0.03 * rng.random()
        length = 0.5 + 0.4 * rng.random()
        s = (np.abs(x - cx) < width) & (np.abs(y) < length)
        rgba[s] = (222, 190, 140, 220)
    hole = r < 0.22
    rgba[hole] = (20, 14, 10, 255)
    ring = (r >= 0.22) & (r < 0.32)
    rgba[ring] = (70, 48, 30, 230)
    return rgba


def cell_metal():
    x, y, r, a = grid()
    rgba = np.zeros((S, S, 4))
    rim = (r < 0.5) & (r >= 0.26)
    shine = 0.6 + 0.4 * np.cos(a - 0.8)
    for i in range(3):
        rgba[..., i] = np.where(rim, 200 * shine, 0)
    rgba[..., 3] = np.where(rim, 230, 0)
    scuff = (r < 0.75) & (r >= 0.5) & (rng.random((S, S)) < 0.35)
    rgba[scuff] = (120, 122, 126, 120)
    hole = r < 0.26
    rgba[hole] = (8, 8, 9, 255)
    return rgba


def cell_earth():
    x, y, r, a = grid()
    rgba = np.zeros((S, S, 4))
    jag = 0.75 + 0.15 * np.sin(a * 5 + 1) + 0.15 * rng.random((S, S))
    thrown = (r < jag) & (rng.random((S, S)) < 0.55)
    rgba[thrown] = (70, 54, 38, 140)
    pit = r < 0.42 + 0.06 * np.sin(a * 6)
    rgba[pit] = (40, 30, 22, 230)
    hole = r < 0.2
    rgba[hole] = (14, 10, 8, 255)
    return rgba


def main():
    img = np.zeros((64, 64, 4))
    for i, f in enumerate((cell_rock, cell_wood, cell_metal, cell_earth)):
        cx, cy = (i % 2) * S, (i // 2) * S
        img[cy:cy + S, cx:cx + S] = f()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(OUT)


if __name__ == "__main__":
    main()
