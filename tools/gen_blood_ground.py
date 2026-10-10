"""9.17 blood where it lands: textures/effect/blood_ground.png (1024 x 1024, a 4 x 4 grid of 256 x 256 tiles).

   0..3   a drop that fell straight: a round splat, its rim thrown out in a crown of points, satellite droplets
   4..7   a drop that struck at a slant: an elongated splat pointing the way it flew (+u), a tail, droplets ahead
   8..11  a pool: a thick, glossy body, darker where it is deep, a darker rim where it is drying, clots in it
  12..15  blood on a wall: the splat near the top (v ~ 0.175) and runs down from it, each ending in a bead

Blood on the ground is not flat paint: thick blood is almost black-red, thin films at the edge are brighter
and see-through, the surface is wet and catches the light, the rim dries first.
"""
import os

import numpy as np
from PIL import Image

T = 256
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "client", "resources", "assets", "ballisticmissiles", "textures", "effect",
                   "blood_ground.png")


def _hash(ix, iy, seed):
    h = (ix * 374761393 + iy * 668265263 + seed * 144665) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def vnoise(x, y, seed=0):
    xi, yi = np.floor(x).astype(np.int64), np.floor(y).astype(np.int64)
    xf, yf = x - xi, y - yi
    u, v = xf * xf * (3 - 2 * xf), yf * yf * (3 - 2 * yf)
    a = _hash(xi, yi, seed)
    b = _hash(xi + 1, yi, seed)
    c = _hash(xi, yi + 1, seed)
    d = _hash(xi + 1, yi + 1, seed)
    return a * (1 - u) * (1 - v) + b * u * (1 - v) + c * (1 - u) * v + d * u * v


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
X, Y = np.meshgrid(g, g)  # x right (+u), y down (+v), -1..1


def shade(depth, seed, gloss=1.0):
    """Colour and alpha of blood `depth` thick (0 at the very edge, 1 deep): thin films brighter and clearer,
    deep blood near black; a soft reflection of the sky across the wet top, and a darker drying rim."""
    deep = np.array([52, 0, 4], float)
    mid = np.array([118, 4, 10], float)
    thin = np.array([170, 22, 26], float)
    t = np.clip(depth, 0, 1)[..., None]
    col = np.where(t > 0.35, mid + (deep - mid) * ((t - 0.35) / 0.65), thin + (mid - thin) * (t / 0.35))
    col = col * (0.88 + 0.24 * fbm(X * 6, Y * 6, seed)[..., None])
    # the wet surface catching the light: a broad soft sheen and a few sharp glints
    sheen = smoothstep(0.55, 0.8, fbm(X * 1.6 + 3, Y * 1.6, seed + 1)) * smoothstep(0.15, 0.5, depth) * gloss
    glint = smoothstep(0.86, 0.95, fbm(X * 5, Y * 5, seed + 2)) * smoothstep(0.25, 0.6, depth) * gloss
    col = col + sheen[..., None] * np.array([70, 40, 42]) + glint[..., None] * np.array([150, 120, 120])
    # the rim, thinnest, dries first: darker, a little brown
    rim = smoothstep(0.12, 0.0, depth) * (depth > 0)
    col = col * (1 - 0.35 * rim[..., None]) + rim[..., None] * np.array([20, 4, 2])
    alpha = smoothstep(0.0, 0.08, depth) * (0.62 + 0.36 * np.clip(depth * 2, 0, 1))
    return col, alpha


def tile(col, alpha):
    return np.dstack([np.clip(col, 0, 255), np.clip(alpha, 0, 1) * 255]).astype(np.uint8)


def drops_around(rs, n, centre, spread, rmin, rmax, cone=None):
    """Satellite droplets: a distance field (depth-like) of little round drops."""
    d = np.zeros_like(X)
    for i in range(n):
        if cone is None:
            a = rs.uniform(0, 2 * np.pi)
        else:
            a = rs.normal(cone[0], cone[1])
        dist = rs.uniform(*spread)
        cx, cy = centre[0] + np.cos(a) * dist, centre[1] + np.sin(a) * dist
        r = rs.uniform(rmin, rmax)
        # a small drop flung at speed is a little egg-shaped, along its flight
        ex = (X - cx) * np.cos(a) + (Y - cy) * np.sin(a)
        ey = -(X - cx) * np.sin(a) + (Y - cy) * np.cos(a)
        q = np.sqrt((ex / 1.35) ** 2 + ey ** 2) / r
        d = np.maximum(d, np.clip(1 - q, 0, 1) * 0.8)
    return d


def splat(v):
    rs = np.random.default_rng(10 + v)
    r = np.sqrt(X * X + Y * Y)
    a = np.arctan2(Y, X)
    r0 = 0.42 + rs.uniform(-0.04, 0.05)
    # an uneven crown: here and there the rim thrown out in a short point
    spikes = np.clip(fbm(np.cos(a) * 9 + v * 3, np.sin(a) * 9, 20 + v, octaves=2) - 0.45, 0, 1) * 2.2
    crown = 1 + 0.16 * spikes ** 2
    edge = r0 * crown * (0.9 + 0.2 * fbm(np.cos(a) * 1.5 + 5, np.sin(a) * 1.5, 30 + v))
    depth = np.clip(1 - r / edge, 0, 1) ** 0.6
    depth = np.maximum(depth, drops_around(rs, rs.integers(10, 22), (0, 0), (r0 * 1.15, 0.92), 0.012, 0.045))
    col, alpha = shade(depth, 40 + v, gloss=0.8)
    return tile(col, alpha)


def spatter(v):
    rs = np.random.default_rng(50 + v)
    # an elongated body pointing +x, its back end round, the front tapering into a tail
    ax, ay = 0.45, 0.2 + rs.uniform(-0.03, 0.03)
    cx = -0.25
    ex = (X - cx) / ax
    ey = Y / (ay * (1 - 0.55 * np.clip((X - cx) / (ax * 1.6), 0, 1)))
    q = np.sqrt(ex * ex + ey * ey) * (0.92 + 0.15 * fbm(X * 3, Y * 3, 60 + v))
    depth = np.clip(1 - q, 0, 1) ** 0.65
    # the tail: a thin streak on ahead, ending in a bead
    tail_len = rs.uniform(0.35, 0.6)
    tx = np.clip((X - (cx + ax * 0.8)) / tail_len, 0, 1)
    tail = np.clip(1 - np.abs(Y) / (0.03 * (1 - tx) + 0.008), 0, 1) * (X > cx + ax * 0.6) * (tx < 1)
    bead = np.clip(1 - np.sqrt((X - (cx + ax * 0.8 + tail_len)) ** 2 + Y ** 2) / 0.045, 0, 1)
    depth = np.maximum(depth, np.maximum(tail * 0.5, bead * 0.8))
    depth = np.maximum(depth, drops_around(rs, rs.integers(8, 16), (cx + ax, 0), (0.15, 0.85), 0.01, 0.035, cone=(0, 0.35)))
    col, alpha = shade(depth, 70 + v, gloss=0.7)
    return tile(col, alpha)


def pool(v):
    rs = np.random.default_rng(90 + v)
    r = np.sqrt(X * X + Y * Y)
    a = np.arctan2(Y, X)
    # a lobed outline: blood runs where the ground lets it
    lobes = 0.7 + 0.5 * fbm(np.cos(a) * 0.9 + 7 + v, np.sin(a) * 0.9, 100 + v, octaves=3)
    lobes = lobes + 0.1 * np.sin(a * rs.integers(2, 4) + rs.uniform(0, 6)) + 0.03 * fbm(np.cos(a) * 6, np.sin(a) * 6, 105 + v)
    edge = 0.93 * np.clip(lobes, 0.5, 1.07)
    depth = np.clip(1 - r / edge, 0, 1) ** 0.45
    depth = depth * (0.85 + 0.25 * fbm(X * 2.5, Y * 2.5, 110 + v))
    depth = np.maximum(depth, drops_around(rs, rs.integers(4, 9), (0, 0), (0.8, 0.97), 0.015, 0.04))
    col, alpha = shade(np.clip(depth, 0, 1), 120 + v, gloss=1.2)
    # clots: darker, matt lumps settling in the deep middle
    clots = smoothstep(0.62, 0.72, fbm(X * 4 + v, Y * 4, 130 + v)) * smoothstep(0.4, 0.7, depth)
    col = col * (1 - 0.45 * clots[..., None])
    return tile(col, alpha)


def runs(v):
    rs = np.random.default_rng(140 + v)
    cy = -1 + 2 * 0.175
    r = np.sqrt(X * X + (Y - cy) ** 2)
    a = np.arctan2(Y - cy, X)
    edge = 0.24 * (0.85 + 0.3 * fbm(np.cos(a) * 2 + v, np.sin(a) * 2, 150 + v))
    depth = np.clip(1 - r / edge, 0, 1) ** 0.6
    depth = np.maximum(depth, drops_around(rs, rs.integers(5, 10), (0, cy), (0.28, 0.5), 0.01, 0.03))
    for k in range(rs.integers(2, 5)):
        x0 = rs.uniform(-0.17, 0.17)
        y1 = rs.uniform(0.0, 0.92)
        w = rs.uniform(0.02, 0.045)
        wob = x0 + 0.02 * np.sin(Y * rs.uniform(4, 9) + rs.uniform(0, 6))
        along = np.clip((Y - cy) / (y1 - cy), 0, 1)
        width = w * (1 - 0.5 * along)
        run = np.clip(1 - np.abs(X - wob) / width, 0, 1) * (Y > cy) * (Y < y1)
        bead = np.clip(1 - np.sqrt((X - x0) ** 2 + ((Y - y1) / 1.3) ** 2) / (w * 1.15), 0, 1)
        depth = np.maximum(depth, np.maximum(run * 0.55, bead * 0.85))
    col, alpha = shade(depth, 160 + v, gloss=0.9)
    return tile(col, alpha)


def main():
    atlas = np.zeros((4 * T, 4 * T, 4), np.uint8)
    makers = [splat, spatter, pool, runs]
    for row, make in enumerate(makers):
        for v in range(4):
            atlas[row * T:(row + 1) * T, v * T:(v + 1) * T] = make(v)
    Image.fromarray(atlas, "RGBA").save(OUT, optimize=True)


if __name__ == "__main__":
    main()
