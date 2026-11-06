"""Tileable 3D noise for the volumetric clouds, textures/environment/cloud_noise.png.

64 x 64 x 64 texels, stored as 64 slices of 66 x 66 (each slice carries a one-texel border wrapped
from the opposite side, so the shader's bilinear fetches tile seamlessly) in an 8 x 8 grid: 528 x 528.

R  Perlin-Worley: billowing base shape (fbm noise inverted Worley-weighted, as in production cloud shaders)
G  Worley fbm at three frequencies: the cauliflower detail that erodes the edges
B  plain smooth fbm (low frequency): large-scale coverage variation

Each channel is equalized (an even spread of values over 0..1).
"""
import os

import numpy as np
from PIL import Image

N = 64
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "client", "resources", "assets", "ballisticmissiles", "textures", "environment", "cloud_noise.png")
rng = np.random.default_rng(1945)
g = np.stack(np.meshgrid(np.arange(N), np.arange(N), np.arange(N), indexing="ij"), -1).astype(np.float32) / N  # x, y, z in [0,1)


def value_noise(freq):
    """Smooth tileable value noise with `freq` cells per period."""
    lat = rng.random((freq, freq, freq)).astype(np.float32)
    p = g * freq
    i = np.floor(p).astype(int)
    f = p - i
    f = f * f * (3 - 2 * f)
    out = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = (f[..., 0] if dx else 1 - f[..., 0]) * (f[..., 1] if dy else 1 - f[..., 1]) * (f[..., 2] if dz else 1 - f[..., 2])
                out = out + w * lat[(i[..., 0] + dx) % freq, (i[..., 1] + dy) % freq, (i[..., 2] + dz) % freq]
    return out


def worley(cells):
    """Tileable Worley (cellular) noise, inverted: 1 at feature points, falling off between them."""
    pts = rng.random((cells, cells, cells, 3)).astype(np.float32)
    p = g * cells
    i = np.floor(p).astype(int)
    best = np.full(p.shape[:3], 9.0, np.float32)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                c = i + np.array([dx, dy, dz])
                fp = pts[c[..., 0] % cells, c[..., 1] % cells, c[..., 2] % cells] + c
                d = np.sqrt(((fp - p) ** 2).sum(-1))
                best = np.minimum(best, d)
    return 1.0 - np.clip(best, 0, 1)


def fbm(freqs, amps):
    out = sum(a * value_noise(f) for f, a in zip(freqs, amps))
    return out / sum(amps)


def norm(x):
    return (x - x.min()) / (x.max() - x.min())


def equalize(x):
    """Rank transform to an even spread over 0..1, so a coverage of c clouds over about c of the sky."""
    flat = x.ravel()
    ranks = np.empty(flat.size, np.float32)
    ranks[np.argsort(flat, kind="stable")] = np.arange(flat.size, dtype=np.float32)
    return (ranks / (flat.size - 1)).reshape(x.shape)


def main():
    perlin = norm(fbm((4, 8, 16), (1.0, 0.5, 0.25)))
    w1 = worley(4)
    shape = norm(np.clip(perlin * 0.55 + w1 * 0.45, 0, 1) ** 1.2)
    detail = norm(worley(8) * 0.625 + worley(16) * 0.25 + worley(32) * 0.125)
    coverage = norm(fbm((2, 4), (1.0, 0.4)))
    vol = np.stack([equalize(shape), equalize(detail), equalize(coverage)], -1)
    S = N + 2
    atlas = np.zeros((8 * S, 8 * S, 3), np.float32)
    for z in range(N):
        sl = vol[:, :, z]  # [x, y]
        padded = np.pad(sl, ((1, 1), (1, 1), (0, 0)), mode="wrap")
        tx, ty = z % 8, z // 8
        atlas[ty * S:(ty + 1) * S, tx * S:(tx + 1) * S] = padded.transpose(1, 0, 2)  # rows = y, columns = x
    img = Image.fromarray((np.clip(atlas, 0, 1) * 255 + 0.5).astype(np.uint8), "RGB").convert("RGBA")
    img.save(OUT)


if __name__ == "__main__":
    main()
