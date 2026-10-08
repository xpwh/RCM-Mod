"""Bullet hole decals, textures/effect/bullet_hole.png (512 x 256: eight 128 px cells, two of each kind).

Cell (kind * 2 + variant) at column index % 4, row index // 4:

0 rock / concrete: a black core in a cone of freshly broken, pale stone, chips flaked off round it,
  hairline cracks running out and a faint ring of powdered stone
1 wood: a dark core torn open along the grain, pale fresh splinters standing out above and below
2 metal: a punched hole in a dished, bright ring of bare scraped steel, a grey lead smear round it
3 earth: a soft dark crater, damp soil thrown out round it in crumbs

The light parts are pale greys: the game tints them with the colour of the block that was struck,
so the broken surface looks like that block's own material, freshly exposed. The spall is thrown a
little further to the right (+u), the way the round was travelling: the game turns that side along
the round's path and stretches a hole made at a flat angle.
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "effect", "bullet_hole.png")
S = 128


def noise(rng, scale, octaves=4):
    """Smooth value noise in [0, 1] over an S x S cell, `scale` cells across at the coarsest octave."""
    out = np.zeros((S, S))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        n = int(scale * 2 ** o) + 2
        g = rng.random((n, n))
        y, x = np.mgrid[0:S, 0:S] / S * (n - 1)
        x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
        fx, fy = x - x0, y - y0
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        x1, y1 = np.minimum(x0 + 1, n - 1), np.minimum(y0 + 1, n - 1)
        v = (g[y0, x0] * (1 - fx) + g[y0, x1] * fx) * (1 - fy) + (g[y1, x0] * (1 - fx) + g[y1, x1] * fx) * fy
        out += v * amp
        total += amp
        amp *= 0.5
    return out / total


def grid():
    y, x = np.mgrid[0:S, 0:S].astype(float)
    x = (x - S / 2 + 0.5) / (S / 2)
    y = (y - S / 2 + 0.5) / (S / 2)
    return x, y, np.sqrt(x * x + y * y), np.arctan2(y, x)


def smooth(e0, e1, t):
    t = np.clip((t - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


class Layers:
    """Paints value (grey) and alpha front to back: each layer goes over what is beneath it."""

    def __init__(self):
        self.v = np.zeros((S, S))
        self.a = np.zeros((S, S))

    def over(self, value, alpha):
        alpha = np.clip(alpha, 0, 1)
        value = np.broadcast_to(value, (S, S))
        a = alpha + self.a * (1 - alpha)
        self.v = np.where(a > 1e-6, (value * alpha + self.v * self.a * (1 - alpha)) / np.maximum(a, 1e-6), 0)
        self.a = a

    def rgba(self):
        out = np.zeros((S, S, 4))
        out[..., 0] = out[..., 1] = out[..., 2] = np.clip(self.v, 0, 255)
        out[..., 3] = np.clip(self.a * 255, 0, 255)
        return out


def cracks(rng, x, y, r, a, count, reach, width):
    """Hairline cracks running out from the hole, wandering, thinning to nothing."""
    alpha = np.zeros((S, S))
    for _ in range(count):
        ang = rng.random() * 2 * np.pi
        length = reach * (0.55 + 0.45 * rng.random())
        wander = rng.normal(0, 0.15)
        bend = ang + wander * r  # the crack drifts off its line as it runs
        d = np.abs(np.angle(np.exp(1j * (a - bend)))) * r
        w = width * (1 - smooth(0.15, length, r)) + 0.004
        alpha = np.maximum(alpha, (1 - smooth(w * 0.4, w, d)) * smooth(0.12, 0.2, r) * (1 - smooth(length * 0.8, length, r)))
    return alpha


def cell_rock(rng):
    x, y, r, a = grid()
    L = Layers()
    n1, n2, n3 = noise(rng, 3), noise(rng, 6), noise(rng, 12, 3)
    forward = 1 + 0.18 * x  # more thrown out ahead
    # powdered stone dusted round it
    dust = (1 - smooth(0.45, 0.95, r / forward)) * (0.25 + 0.5 * n2) * 0.45
    L.over(235, dust)
    # flakes chipped off the face: fresh, pale, broken surface
    edge = 0.5 + 0.22 * (n1 - 0.5) * 2
    flake = (1 - smooth(edge * forward - 0.05, edge * forward + 0.02, r)) * smooth(0.35, 0.6, n2 + 0.25)
    L.over(205 + 40 * n3, flake * 0.9)
    # its broken rim, a thin dark line where the flakes left the face
    rim = np.abs(r - edge * forward) < 0.025
    L.over(90, rim * flake.clip(0.4, 1) * 0.5)
    # the cone of the crater: pale at the lip, into shadow at the bottom
    cone = 0.32 + 0.06 * (n1 - 0.5) * 2
    depth = smooth(0.16, cone, r)
    L.over(60 + 150 * depth + 30 * (n3 - 0.5), 1 - smooth(cone - 0.02, cone + 0.02, r))
    # hairline cracks
    L.over(70, cracks(rng, x, y, r, a, 5, 0.62, 0.011) * 0.6)
    # the core: black, a ragged edge, a lead smear of dark grey round it
    core = 0.15 + 0.012 * np.sin(a * 3 + rng.random() * 6) + 0.08 * (n3 - 0.5)
    L.over(45, (1 - smooth(core, core + 0.06, r)) * 0.8)
    L.over(8, 1 - smooth(core - 0.02, core + 0.01, r))
    return L.rgba()


def cell_wood(rng):
    x, y, r, a = grid()
    L = Layers()
    n1, n2 = noise(rng, 3), noise(rng, 8)
    # fibres torn out along the grain (up and down the texture), each its own length
    fibre = np.zeros((S, S))
    cols = np.linspace(-0.4, 0.4, 26) + rng.normal(0, 0.02, 26)
    for cx in cols:
        width = 0.012 + 0.02 * rng.random()
        up, down = 0.3 + 0.6 * rng.random() * (1 - abs(cx)), 0.3 + 0.6 * rng.random() * (1 - abs(cx))
        lit = 0.5 + 0.5 * rng.random()
        reach = np.where(y < 0, up, down)
        f = (1 - smooth(width * 0.5, width, np.abs(x - cx - 0.03 * np.sin(y * 9 + cx * 20)))) * (1 - smooth(reach * 0.75, reach, np.abs(y)))
        fibre = np.maximum(fibre, f * lit)
    torn = 1 - smooth(0.3, 0.45, np.abs(x) / (1 - 0.6 * np.abs(y)).clip(0.2, 1))
    L.over(240, fibre * torn * 0.95)
    # a split running on along the grain
    split = (1 - smooth(0.006, 0.02, np.abs(x - 0.02 * np.sin(y * 7)))) * (1 - smooth(0.4, 0.75 + 0.15 * rng.random(), np.abs(y)))
    L.over(40, split * 0.8)
    # torn, shadowed wood round the core
    pit = 0.27 + 0.04 * (n1 - 0.5)
    shade = smooth(0.12, pit, np.sqrt(x * x * 1.0 + y * y * 0.55))
    L.over(55 + 120 * shade + 40 * (n2 - 0.5), 1 - smooth(pit - 0.02, pit + 0.03, np.sqrt(x * x + y * y * 0.55)))
    # the core, a little taller than wide (split along the grain)
    core = np.sqrt(x * x + y * y * 0.6)
    L.over(10, 1 - smooth(0.13, 0.16, core + 0.02 * (n2 - 0.5)))
    return L.rgba()


def cell_metal(rng):
    x, y, r, a = grid()
    L = Layers()
    n1, n2 = noise(rng, 4), noise(rng, 16, 2)
    # a grey smear of lead and burnt paint round it
    L.over(95 + 30 * n1, (1 - smooth(0.32, 0.7, r)) * (0.3 + 0.4 * n1))
    # paint knocked off: bare steel scraped bright, polished nearest the hole
    bare = 0.42 + 0.08 * (n1 - 0.5) * 2 + 0.06 * x
    L.over(170 + 70 * (1 - smooth(0.18, bare, r)) + 25 * (n2 - 0.5), 1 - smooth(bare - 0.02, bare + 0.02, r))
    # the dished rim pushed in: a bright lip and a shadowed inside
    L.over(255, (1 - smooth(0.012, 0.03, np.abs(r - 0.25))) * 0.8)
    L.over(70, (1 - smooth(0.0, 0.05, r - 0.18)) * smooth(0.14, 0.2, r) * 0.7)
    # scratches out from the strike
    L.over(230, cracks(rng, x, y, r, a, 5, 0.6, 0.01) * 0.6)
    # the hole
    L.over(6, 1 - smooth(0.15, 0.17, r))
    return L.rgba()


def cell_earth(rng):
    x, y, r, a = grid()
    L = Layers()
    n1, n2 = noise(rng, 3), noise(rng, 10, 3)
    forward = 1 + 0.25 * x
    # crumbs of soil thrown out, damp and darker than the face
    crumbs = np.zeros((S, S))
    for _ in range(32):
        ang, dist = rng.random() * 2 * np.pi, 0.4 + 0.5 * rng.random() ** 1.5
        cx, cy = np.cos(ang) * dist * (1 + 0.25 * np.cos(ang)), np.sin(ang) * dist
        size = 0.012 + 0.02 * rng.random()
        crumbs = np.maximum(crumbs, 1 - smooth(size * 0.6, size, np.sqrt((x - cx) ** 2 + (y - cy) ** 2)))
    L.over(150, crumbs * 0.7)
    # the scuffed ground round it
    L.over(150 + 40 * n2, (1 - smooth(0.35, 0.75, r / forward)) * (0.35 + 0.4 * n1))
    # the crater: soft, shadowed toward the middle
    pit = 0.42 + 0.08 * (n1 - 0.5) * 2
    L.over(50 + 110 * smooth(0.1, pit, r) + 30 * (n2 - 0.5), 1 - smooth(pit - 0.05, pit + 0.03, r))
    L.over(12, 1 - smooth(0.1, 0.18, r))
    return L.rgba()


def main():
    rng = np.random.default_rng(39)
    img = np.zeros((2 * S, 4 * S, 4))
    for kind, f in enumerate((cell_rock, cell_wood, cell_metal, cell_earth)):
        for variant in range(2):
            i = kind * 2 + variant
            cx, cy = (i % 4) * S, (i // 4) * S
            img[cy:cy + S, cx:cx + S] = f(rng)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(OUT)


if __name__ == "__main__":
    main()
