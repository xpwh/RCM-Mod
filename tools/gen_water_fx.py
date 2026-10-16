"""12.6: a round into water - the drop and ripple particle textures.

python3 tools/gen_water_fx.py
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "particle")


def drop(i, stretch):
    # a small bead of water: clear body, a darker rim, a bright glint up top
    n = 8
    y, x = np.mgrid[0:n, 0:n] + 0.5
    cx, cy = n / 2, n / 2
    r = np.sqrt(((x - cx) / (n * 0.42)) ** 2 + ((y - cy) / (n * 0.42 * stretch)) ** 2)
    a = np.clip((1.0 - r) * 3.0, 0, 1)
    lum = 0.78 + 0.22 * r
    g = np.exp(-(((x - cx + 1.2) ** 2 + (y - cy + 1.2) ** 2) / 1.2))
    lum = np.clip(lum + g * 0.6, 0, 1)
    img = np.zeros((n, n, 4))
    img[..., 0] = lum * 235
    img[..., 1] = lum * 245
    img[..., 2] = 255
    img[..., 3] = a * (170 + 85 * g)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(os.path.join(OUT, f"water_drop_{i}.png"))


def ripple():
    # one bright ring with a faint second one inside, broken up a little like a real wave front
    n = 64
    y, x = np.mgrid[0:n, 0:n] + 0.5
    dx, dy = (x - n / 2) / (n / 2), (y - n / 2) / (n / 2)
    r = np.sqrt(dx * dx + dy * dy)
    ang = np.arctan2(dy, dx)
    rng = np.random.default_rng(126)
    wobble = sum(rng.normal(0, 0.004) * np.sin(k * ang + rng.uniform(0, 6.3)) for k in range(3, 9))
    ring = np.exp(-((r - 0.86 + wobble) / 0.045) ** 2)
    inner = 0.45 * np.exp(-((r - 0.66 + wobble) / 0.04) ** 2)
    shade = 0.35 * np.exp(-((r - 0.78 + wobble) / 0.05) ** 2)  # the trough just inside the crest
    a = np.clip(ring + inner, 0, 1)
    lum = np.clip(1.0 - shade, 0, 1)
    img = np.zeros((n, n, 4))
    img[..., 0] = lum * 235
    img[..., 1] = lum * 245
    img[..., 2] = 255
    img[..., 3] = a * 255 * (r < 1.0)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(os.path.join(OUT, "water_ripple.png"))


if __name__ == "__main__":
    for i, s in enumerate((1.0, 1.25, 0.85)):
        drop(i, s)
    ripple()
