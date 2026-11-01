"""Blood pools that join up: textures/effect/blood_pool.png and blood_pool_gloss.png (1536 x 1536, a 12 x 12 grid of
128 x 128 tiles), drawn over a whole block's top by BloodPools.

A tile is chosen by the 8 neighbours that are wet too (bits 1 N / -v, 2 E / +u, 4 S / +v, 8 W / -u, 16 NE, 32 SE,
64 SW, 128 NW; a corner counts only with both its sides - 47 patterns) and by how much stands on the block (level
0..2). Tile index = level * 47 + the pattern's number (in the order canonical() first meets it counting the mask
up from 0 - BloodPools does the same). Towards a wet side the pool runs out in a wide channel, into a wet corner it
fills the corner: both the same in every tile, so neighbours meet without a seam and a big pool is one sheet.
Blood that thick is nearly black-red; only the thin film at the edges is brighter and see-through. The gloss map is
the wet surface for the shine pass.
"""
import os

import numpy as np
from PIL import Image

T = 128
N = 12
HERE = os.path.dirname(__file__)
OUT = os.path.join(HERE, "..", "src", "client", "resources", "assets", "ballisticmissiles", "textures", "effect")


def _hash(ix, iy, seed):
    h = (ix * 374761393 + iy * 668265263 + seed * 144665) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def vnoise(x, y, seed=0):
    xi, yi = np.floor(x).astype(np.int64), np.floor(y).astype(np.int64)
    xf, yf = x - xi, y - yi
    u, v = xf * xf * (3 - 2 * xf), yf * yf * (3 - 2 * yf)
    a, b = _hash(xi, yi, seed), _hash(xi + 1, yi, seed)
    c, d = _hash(xi, yi + 1, seed), _hash(xi + 1, yi + 1, seed)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v


def pnoise(x, y, period, seed=0):
    """Value noise repeating every `period` cells: the same at a tile's left edge as at its right."""
    xi, yi = np.floor(x).astype(np.int64), np.floor(y).astype(np.int64)
    xf, yf = x - xi, y - yi
    u, v = xf * xf * (3 - 2 * xf), yf * yf * (3 - 2 * yf)
    p = period
    a, b = _hash(xi % p, yi % p, seed), _hash((xi + 1) % p, yi % p, seed)
    c, d = _hash(xi % p, (yi + 1) % p, seed), _hash((xi + 1) % p, (yi + 1) % p, seed)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v


def tiled(base, seed, octaves=4):
    """fbm over the tile that wraps round: every tile shares it, so it runs on across their seams."""
    s, a, f = 0.0, 0.5, 1
    for o in range(octaves):
        s = s + a * pnoise((X + 1) / 2 * base * f, (Y + 1) / 2 * base * f, base * f, seed + o * 31)
        a *= 0.5
        f *= 2
    return s


def fbm(x, y, seed=0, octaves=4):
    s, a, f = 0.0, 0.5, 1.0
    for o in range(octaves):
        s = s + a * vnoise(x * f, y * f, seed + o * 31)
        a *= 0.5
        f *= 2.0
    return s


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


g = (np.arange(T) + 0.5) / T * 2 - 1
X, Y = np.meshgrid(g, g)  # x = u (east +), y = v (south +), -1 .. 1
# how close to the tile's border: the shape is free inside, fixed along the edges so tiles meet
EDGE = np.clip(np.maximum(np.abs(X), np.abs(Y)), 0, 1)
FREE = 1 - smoothstep(0.55, 0.92, EDGE)

RADIUS = (0.46, 0.66, 0.86)
CHANNEL = 0.42  # the channel's half width along the edge it runs to


def canonical(mask):
    """A corner only counts when both its sides are wet."""
    m = mask & 15
    for bit, a, b in ((16, 1, 2), (32, 4, 2), (64, 4, 8), (128, 1, 8)):
        if mask & bit and m & a and m & b:
            m |= bit
    return m


PATTERNS = []
for _m in range(256):
    _c = canonical(_m)
    if _c not in PATTERNS:
        PATTERNS.append(_c)
assert len(PATTERNS) == 47


def field(mask, level, seed):
    """Depth (0 dry .. 1 deep) of the blood on a tile."""
    a = np.arctan2(Y, X)
    lobes = 1 + 0.22 * (fbm(np.cos(a) * 1.3 + seed, np.sin(a) * 1.3, seed, 3) - 0.5) * 2
    r = np.sqrt(X * X + Y * Y) / (RADIUS[level] * lobes)
    d = np.clip(1 - r, 0, 1) ** 0.7 * (1 - smoothstep(0.8, 0.97, EDGE))
    # channels to the wet sides: as wide along the edge as in every other tile
    for bit, (ex, ey) in ((1, (0, -1)), (2, (1, 0)), (4, (0, 1)), (8, (-1, 0))):
        if mask & bit:
            along = X * ex + Y * ey
            across = np.abs(X * ey - Y * ex)
            ch = smoothstep(CHANNEL + 0.1, CHANNEL - 0.3, across) * smoothstep(-0.7, -0.15, along)
            d = np.maximum(d, ch)
    # wet corners: filled right into the corner
    for bit, (cx, cy) in ((16, (1, -1)), (32, (1, 1)), (64, (-1, 1)), (128, (-1, -1))):
        if mask & bit:
            q = np.minimum(X * cx, Y * cy)  # > 0 inside that quadrant
            d = np.maximum(d, smoothstep(-0.45, 0.1, q))
    # an uneven outline and a surface of shallows and deeps: noise that wraps round the tile, so it is the same
    # on both sides of every seam
    inside = d > 0.0
    d = np.where(inside, d + 0.55 * (tiled(2, 77) - 0.5) + 0.1 * (tiled(6, 91) - 0.5), 0.0)
    return np.clip(d, 0, 1)


def shade(depth, seed):
    deep = np.array([52, 2, 4], float)
    mid = np.array([86, 5, 8], float)
    thin = np.array([128, 14, 16], float)
    t = depth[..., None]
    col = np.where(t > 0.3, mid + (deep - mid) * np.clip((t - 0.3) / 0.7, 0, 1), thin + (mid - thin) * (t / 0.3))
    col = col * (0.92 + 0.16 * tiled(3, 303)[..., None])
    # clots settling where it is deep, matt and darker
    clots = smoothstep(0.74, 0.8, tiled(5, 411)) * smoothstep(0.45, 0.8, depth) * 0.5
    col = col * (1 - 0.4 * clots[..., None])
    alpha = smoothstep(0.0, 0.1, depth) * (0.58 + 0.38 * smoothstep(0.05, 0.45, depth))
    gloss = smoothstep(0.08, 0.35, depth) * (1 - 0.6 * clots) * (0.75 + 0.25 * tiled(4, 519))
    return col, alpha, gloss


def main():
    atlas = np.zeros((N * T, N * T, 4), np.uint8)
    shine = np.zeros((N * T, N * T, 4), np.uint8)
    for level in range(3):
        for k, mask in enumerate(PATTERNS):
            i = level * 47 + k
            d = field(mask, level, 300 + i)
            col, alpha, gloss = shade(d, 500 + i)
            ty, tx = divmod(i, N)
            sl = (slice(ty * T, ty * T + T), slice(tx * T, tx * T + T))
            atlas[sl] = np.dstack([np.clip(col, 0, 255), np.clip(alpha, 0, 1) * 255]).astype(np.uint8)
            shine[sl] = np.dstack([np.full((T, T, 3), 255), np.clip(gloss, 0, 1) * 255]).astype(np.uint8)
    Image.fromarray(atlas, "RGBA").save(os.path.join(OUT, "blood_pool.png"), optimize=True)
    Image.fromarray(shine, "RGBA").save(os.path.join(OUT, "blood_pool_gloss.png"), optimize=True)


if __name__ == "__main__":
    main()
