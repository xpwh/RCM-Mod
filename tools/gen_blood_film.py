"""The surface of blood on the ground (BloodPools): textures/effect/blood_film.png, 128 x 128, repeating every two blocks.

Almost white, so the colour comes from the simulation (how deep it stands, how dry it is): only the faint unevenness of
a liquid surface - slightly darker swirls where it is thicker, a few small dark clots - that wraps round seamlessly.
blood_film_gloss.png is plain white for the shine pass.
"""
import os

import numpy as np
from PIL import Image

T = 128
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "client", "resources", "assets", "ballisticmissiles", "textures", "effect")


def _hash(ix, iy, seed):
    h = (ix * 374761393 + iy * 668265263 + seed * 144665) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def pnoise(period, seed):
    g = np.arange(T) / T * period
    x, y = np.meshgrid(g, g)
    xi, yi = np.floor(x).astype(np.int64), np.floor(y).astype(np.int64)
    xf, yf = x - xi, y - yi
    u, v = xf * xf * (3 - 2 * xf), yf * yf * (3 - 2 * yf)
    p = period
    a, b = _hash(xi % p, yi % p, seed), _hash((xi + 1) % p, yi % p, seed)
    c, d = _hash(xi % p, (yi + 1) % p, seed), _hash((xi + 1) % p, (yi + 1) % p, seed)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v


def main():
    swirl = 0.5 * pnoise(4, 1) + 0.3 * pnoise(8, 2) + 0.2 * pnoise(16, 3)
    grain = pnoise(32, 4)
    clots = np.clip((pnoise(16, 5) - 0.78) * 6, 0, 1) * np.clip((pnoise(4, 6) - 0.4) * 3, 0, 1)
    v = 0.86 + 0.14 * swirl + 0.04 * (grain - 0.5) - 0.35 * clots
    v = np.clip(v, 0, 1)
    img = np.dstack([v * 255, v * 252, v * 252, np.full_like(v, 255)]).astype(np.uint8)
    Image.fromarray(img, "RGBA").save(os.path.join(OUT, "blood_film.png"), optimize=True)
    Image.fromarray(np.full((16, 16, 4), 255, np.uint8), "RGBA").save(os.path.join(OUT, "blood_film_gloss.png"))


if __name__ == "__main__":
    main()
