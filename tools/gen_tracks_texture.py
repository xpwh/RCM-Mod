"""12.0: the footprint and tyre-track atlas (2 x 2 cells of 64 px): boot sole, animal paw, tyre tread, combat boot.
White, with the depth of the imprint in the alpha (the game tints it per surface: snow, sand, mud).
python3 tools/gen_tracks_texture.py
"""
import math
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "effect", "tracks.png")
N = 64
rng = np.random.default_rng(1202)
y, x = np.mgrid[0:N, 0:N] / (N - 1.0)


def ellipse(cx, cy, rx, ry, soft=0.12):
    d = np.sqrt(((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2)
    return np.clip((1.0 - d) / soft, 0, 1)


def sole_mask():
    """A left sole seen from above, toe up: rounded toe, wide ball, the arch drawn in on the inner (right) side, the heel."""
    out = np.zeros((N, N))
    for j in range(N):
        v = j / (N - 1.0)
        if v < 0.05 or v > 0.95:
            continue
        # half-width along the sole
        if v < 0.32:
            w = 0.21 * math.sqrt(max(0.0, 1.0 - ((0.32 - v) / 0.27) ** 2)) + 0.02
        elif v < 0.62:
            w = 0.23 - 0.08 * math.sin((v - 0.32) / 0.3 * math.pi / 2)
        else:
            w = 0.15 + 0.03 * math.sin((v - 0.62) / 0.33 * math.pi) * (1 if v < 0.8 else 1)
            if v > 0.86:
                w *= math.sqrt(max(0.0, 1.0 - ((v - 0.86) / 0.09) ** 2))
        # the arch: the inner edge comes in through the middle
        arch = 0.06 * math.sin(max(0.0, min(1.0, (v - 0.38) / 0.32)) * math.pi)
        left, right = 0.5 - w, 0.5 + w - arch
        for i in range(N):
            u = i / (N - 1.0)
            d = min(u - left, right - u)
            out[j, i] = np.clip(d / 0.025, 0, 1)
    return out


def boot(lug_rows, lug_period):
    sole = sole_mask()
    # the heel is struck deeper than the waist; the tread's lugs deeper still
    lugs = (np.sin((y * lug_rows + np.abs(x - 0.5) * lug_period) * math.pi * 2) > 0.25).astype(float)
    weight = 0.75 + 0.25 * np.clip(np.abs(y - 0.55) / 0.35, 0, 1)
    depth = sole * weight * (0.55 + 0.45 * lugs)
    # the rim where snow or sand is pushed up and out: a faint halo round the sole
    halo = np.clip(ellipse(0.5, 0.5, 0.33, 0.5, 0.35) - sole, 0, 1) * 0.12
    return np.clip(depth + halo + rng.normal(0, 0.04, (N, N)) * sole, 0, 1)


def paw():
    a = ellipse(0.5, 0.64, 0.2, 0.17)
    for cx, cy in ((0.3, 0.36), (0.44, 0.28), (0.58, 0.28), (0.72, 0.36)):
        a = np.maximum(a, ellipse(cx, cy, 0.08, 0.09))
    return np.clip(a * 0.9, 0, 1)


def tyre():
    band = np.clip((0.42 - np.abs(x - 0.5)) / 0.04, 0, 1)
    # chevron lugs, tileable along y (8 per cell)
    lug = (np.sin((y * 8 + np.abs(x - 0.5) * 3.0) * math.pi * 2) > -0.1).astype(float)
    return np.clip(band * (0.45 + 0.55 * lug) + rng.normal(0, 0.03, (N, N)) * band, 0, 1)


cells = [boot(9, 2.0), paw(), tyre(), boot(12, 3.2)]
img = np.zeros((N * 2, N * 2, 4), np.uint8)
for i, c in enumerate(cells):
    r, k = divmod(i, 2)
    img[r * N:(r + 1) * N, k * N:(k + 1) * N, :3] = 255
    img[r * N:(r + 1) * N, k * N:(k + 1) * N, 3] = (c * 255).astype(np.uint8)
os.makedirs(os.path.dirname(OUT), exist_ok=True)
Image.fromarray(img, "RGBA").save(OUT)
