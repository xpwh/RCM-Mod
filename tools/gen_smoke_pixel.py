"""Pixel-art smoke puffs for the "Voxel 2" smoke style: 8 puffs of 12x12 pixels side by side (96x12) - coarse, like Minecraft's own 8x8 particles, drawn with
nearest filtering and alpha cut-out, so a trail of them reads as one solid, flat white body with stair-stepped
pixel edges - the look of Minecraft's own particles, not of a soft cloud.

RGB: the puff's shading in a few flat tones (white where the light comes from, top left; light grey round the
bottom right), with pixel-art clusters rather than a smooth gradient.
Alpha: not transparency but how deep inside the puff each pixel lies (0.1 at the rim .. 1 in the core, ragged).
The game multiplies it by how thick the smoke still is and cuts away what falls under 0.1, so as smoke thins the
puff loses its outer pixels first and crumbles away pixel by pixel instead of fading.

usage: python3 tools/gen_smoke_pixel.py
"""
import numpy as np
from PIL import Image

OUT = "src/client/resources/assets/ballisticmissiles/textures/effect/smoke_pixel.png"
N = 12
C = N / 2.0
VARIANTS = 8


def puff(rng):
    yy, xx = np.mgrid[0:N, 0:N] + 0.5
    # a lumpy blob: a main ball and a few smaller ones overlapping its edge
    inside = np.full((N, N), -1e9)
    k = N / 16.0
    balls = [(C + rng.normal() * 0.3, C + rng.normal() * 0.3, (5.4 + rng.random() * 0.6) * k)]
    for _ in range(rng.integers(3, 6)):
        a = rng.random() * np.pi * 2
        d = (2.6 + rng.random() * 1.6) * k
        balls.append((C + np.cos(a) * d, C + np.sin(a) * d, (2.6 + rng.random() * 1.5) * k))
    for cx, cy, r in balls:
        inside = np.maximum(inside, r - np.hypot(xx - cx, yy - cy))
    inside[[0, -1], :] = -1
    inside[:, [0, -1]] = -1
    mask = inside > 0.15
    depth = np.clip(inside / max(inside.max(), 1e-6), 0, 1)
    # erosion order: the rim first, the core last, ragged pixel by pixel
    rank = 0.1 + 0.9 * depth ** 0.75 + rng.normal(0, 0.09, (N, N))
    rank = np.clip(rank, 0.11, 1.0)
    # a frayed edge: stray pixels just outside it, notches just inside it - the first to go as it thins
    ring = (inside > -1.3) & (inside <= 0.15) & (inside > -1e8)
    ring[[0, -1], :] = False
    ring[:, [0, -1]] = False
    stray = ring & (rng.random((N, N)) < 0.3)
    mask = mask | stray
    rank = np.where(stray, 0.12 + 0.12 * rng.random((N, N)), rank)
    notch = mask & (inside > 0.15) & (inside < 0.9) & (rng.random((N, N)) < 0.12)
    mask = mask & ~notch
    alpha = np.where(mask, rank, 0.0)
    # shading: a ball lit from the top left, in four flat tones with a little pixel noise
    nx = (xx - C) / (C - 1.0)
    ny = (yy - C) / (C - 1.0)
    nz = np.sqrt(np.clip(1 - nx * nx - ny * ny, 0, 1))
    lx, ly, lz = -0.45, -0.6, 0.66
    lit = np.clip(nx * lx + ny * ly + nz * lz, 0, 1) + rng.normal(0, 0.06, (N, N))
    tones = np.array([206, 222, 238, 252])
    level = np.clip((lit * 4.2).astype(int), 0, 3)
    shade = tones[level]
    rgb = np.where(mask, shade, 255).astype(np.uint8)
    return rgb, (alpha * 255).round().astype(np.uint8)


def main():
    rng = np.random.default_rng(1957)
    out = np.zeros((N, N * VARIANTS, 4), np.uint8)
    for v in range(VARIANTS):
        rgb, a = puff(rng)
        out[:, v * N:(v + 1) * N, 0] = rgb
        out[:, v * N:(v + 1) * N, 1] = rgb
        out[:, v * N:(v + 1) * N, 2] = rgb
        out[:, v * N:(v + 1) * N, 3] = a
    Image.fromarray(out, "RGBA").save(OUT, optimize=True)


if __name__ == "__main__":
    main()
