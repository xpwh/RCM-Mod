"""12.8: the foam texture the tsunami waves are drawn with (tiles both ways; tinted by the wave itself).

python3 tools/gen_tsunami_tex.py
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "effect", "tsunami.png")
rng = np.random.default_rng(128)
N = 64


def tileable(fmax, power):
    # noise made of whole waves across the tile, so its edges meet
    y, x = np.mgrid[0:N, 0:N] / N
    out = np.zeros((N, N))
    for fx in range(-fmax, fmax + 1):
        for fy in range(0, fmax + 1):
            if fx == 0 and fy == 0:
                continue
            amp = rng.normal() / (fx * fx + fy * fy) ** (power / 2)
            out += amp * np.cos(2 * np.pi * (fx * x + fy * y) + rng.uniform(0, 2 * np.pi))
    out -= out.min()
    return out / out.max()


# streaks of foam drawn out across the wave (along v), with finer bubbly clots in them
streak = tileable(10, 1.4)
streak = np.asarray(Image.fromarray((streak * 255).astype(np.uint8)).resize((N, N)), np.float32) / 255
fine = tileable(16, 0.9)
v = 0.55 * streak + 0.45 * fine
lum = 0.72 + 0.28 * v
img = np.zeros((N, N, 4), np.uint8)
img[..., 0] = np.clip(lum * 255, 0, 255)
img[..., 1] = np.clip(lum * 255, 0, 255)
img[..., 2] = np.clip(lum * 255, 0, 255)
img[..., 3] = 255
Image.fromarray(img, "RGBA").save(OUT)
