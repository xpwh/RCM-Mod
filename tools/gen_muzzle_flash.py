"""Muzzle flash sprites for the AK (rendered additively: black is transparent).

textures/effect/muzzle_flash.png, 128 x 128:
  rows 0-3 (32 px each, left 96 px): side views of the flash, muzzle at the left - the white-hot
          primary flash at the brake, the cone of burning gas widening and the orange ball of the
          secondary flash a little way out, ragged at the edges; four different shots
  right 32 px column, rows 0-3: the flash seen head-on - a hot core with uneven spikes
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "effect", "muzzle_flash.png")
rng = np.random.default_rng(762)


def value_noise(h, w, cells):
    g = rng.random((cells + 2, cells + 2))
    ys = np.linspace(0, cells, h, endpoint=False)
    xs = np.linspace(0, cells, w, endpoint=False)
    y0 = ys.astype(int)
    x0 = xs.astype(int)
    fy = (ys - y0)[:, None]
    fx = (xs - x0)[None, :]
    fy = fy * fy * (3 - 2 * fy)
    fx = fx * fx * (3 - 2 * fx)
    a = g[y0][:, x0]
    b = g[y0][:, x0 + 1]
    c = g[y0 + 1][:, x0]
    d = g[y0 + 1][:, x0 + 1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def fbm(h, w):
    return sum(value_noise(h, w, c) * a for c, a in ((3, 0.5), (6, 0.27), (12, 0.15), (24, 0.08)))


def fire_color(i):
    """Intensity 0..1+ to additive colour: deep red, orange, yellow, white-hot."""
    i = np.clip(i, 0, 1.6)
    r = np.clip(i * 2.2, 0, 1)
    g = np.clip(i * 1.6 - 0.35, 0, 1)
    b = np.clip(i * 1.4 - 0.95, 0, 1)
    return np.stack([r, g * 0.92, b * 0.85], -1)


def side(seed_shift):
    h, w = 32, 96
    y = np.linspace(-1, 1, h)[:, None]
    x = np.linspace(0, 1, w)[None, :]
    n = fbm(h, w)
    # primary flash: tight and white at the muzzle
    primary = np.exp(-x / 0.06) * np.exp(-(y / (0.18 + x * 2.0)) ** 2) * 1.5
    # the cone of gas, widening and wavering
    width = 0.1 + 0.55 * x
    cone = np.exp(-(y / width) ** 2) * np.exp(-((x - 0.15) / 0.25) ** 2) * (0.3 + 1.0 * (n - 0.3))
    # secondary flash: a ball of burning gas out ahead
    cx = 0.42 + 0.12 * rng.random()
    r = 0.22 + 0.08 * rng.random()
    ball = np.exp(-(((x - cx) / r) ** 2 + (y / (r * 2.4)) ** 2)) * np.clip(n * 1.6 - 0.35, 0, 1)
    # ragged tongues licking forward
    tongues = np.zeros((h, w))
    for _ in range(5):
        ty = rng.normal(0, 0.35)
        length = 0.5 + 0.4 * rng.random()
        tongues += np.exp(-((y - ty * x * 2) / (0.05 + 0.15 * x)) ** 2) * np.clip(1 - x / length, 0, 1) * 0.5 * rng.random()
    i = (primary + cone * 0.7 + ball * (0.6 + 0.4 * rng.random()) + tongues * n * 1.4) * 0.75
    i *= np.clip((1 - x) * 3, 0, 1)            # nothing reaches the far end
    i *= np.clip(1 - np.abs(y) ** 6, 0, 1)
    i = np.where(i < 0.06, 0, i)
    return fire_color(i)


def front():
    s = 32
    y, x = np.mgrid[-1:1:s * 1j, -1:1:s * 1j]
    r = np.sqrt(x * x + y * y)
    a = np.arctan2(y, x)
    spikes = np.zeros_like(r)
    k = rng.integers(4, 7)
    off = rng.random() * 6.28
    for j in range(k):
        ang = off + j * 2 * np.pi / k + rng.normal(0, 0.25)
        length = 0.7 + 0.3 * rng.random()
        d = np.angle(np.exp(1j * (a - ang)))
        spikes += np.exp(-(d / (0.12 + 0.1 * r)) ** 2) * np.clip(1 - r / length, 0, 1)
    core = np.exp(-(r / 0.22) ** 2) * 1.5
    glow = np.exp(-(r / 0.55) ** 2) * 0.35
    n = fbm(s, s)
    i = core + spikes * (0.6 + 0.6 * n) + glow * n
    i *= np.clip((1 - r) * 4, 0, 1)
    i = np.where(i < 0.06, 0, i)
    return fire_color(i)


def main():
    img = np.zeros((128, 128, 3))
    for row in range(4):
        img[row * 32:(row + 1) * 32, 0:96] = side(row)
        img[row * 32:(row + 1) * 32, 96:128] = front()
    alpha = np.clip(img.max(-1) * 1.5, 0, 1)
    rgba = np.concatenate([img, alpha[..., None]], -1)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    Image.fromarray((rgba * 255).astype(np.uint8), "RGBA").save(OUT)


if __name__ == "__main__":
    main()
