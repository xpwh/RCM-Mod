"""Volumetric smoke puff sprites for the SmokeField renderer: textures/effect/smoke_volume.png.

A 4 x 4 atlas of 64 px sprites: four puff shapes (columns) at four stages of dissolving (rows,
dense -> wispy). Each is raymarched from a 3D density field - a cluster of metaballs (the
cauliflower billows of real smoke) eroded by fractal noise - lit from above with self-shadowing,
so the top of every puff is bright and its underside darker, with soft, ragged edges. The renderer
turns each sprite so its lit side faces the sun and tints it with the smoke's colour.

The RGB is the lit brightness (greyscale, kept a little above black so shadows show the colour),
the alpha the puff's opacity.
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "client", "resources", "assets", "ballisticmissiles", "textures", "effect", "smoke_volume.png")
N = 72          # density grid resolution
S = 64          # sprite size
rng = np.random.default_rng(2025)


def value_noise(shape, cells, seed):
    """Smooth 3D value noise on an N^3 grid with `cells` lattice cells per axis."""
    r = np.random.default_rng(seed)
    lat = r.random((cells + 2, cells + 2, cells + 2))
    g = np.linspace(0, cells, shape, endpoint=False)
    i = np.floor(g).astype(int)
    f = g - i
    f = f * f * (3 - 2 * f)
    ix, iy, iz = np.meshgrid(i, i, i, indexing="ij")
    fx, fy, fz = np.meshgrid(f, f, f, indexing="ij")

    def at(dx, dy, dz):
        return lat[ix + dx, iy + dy, iz + dz]

    c00 = at(0, 0, 0) * (1 - fx) + at(1, 0, 0) * fx
    c10 = at(0, 1, 0) * (1 - fx) + at(1, 1, 0) * fx
    c01 = at(0, 0, 1) * (1 - fx) + at(1, 0, 1) * fx
    c11 = at(0, 1, 1) * (1 - fx) + at(1, 1, 1) * fx
    c0 = c00 * (1 - fy) + c10 * fy
    c1 = c01 * (1 - fy) + c11 * fy
    return c0 * (1 - fz) + c1 * fz


def fbm(seed):
    return (value_noise(N, 4, seed) * 0.45 + value_noise(N, 8, seed + 1) * 0.3 + value_noise(N, 16, seed + 2) * 0.17
            + value_noise(N, 28, seed + 3) * 0.08)


def density(shape_seed):
    r = np.random.default_rng(shape_seed)
    ax = np.linspace(-1, 1, N)
    x, y, z = np.meshgrid(ax, ax, ax, indexing="ij")   # x right, y up, z towards the viewer
    field = np.zeros((N, N, N))
    # a big central billow and smaller ones bulging out of it, more of them on top
    blobs = [(0.0, -0.08, 0.0, 0.62)]
    for _ in range(int(r.integers(10, 16))):
        a = r.random() * 2 * np.pi
        e = r.uniform(-0.4, 1.1)
        d = r.uniform(0.35, 0.6)
        blobs.append((np.cos(a) * np.cos(e) * d, np.sin(e) * d, np.sin(a) * np.cos(e) * d, r.uniform(0.2, 0.36)))
    for bx, by, bz, br in blobs:
        d2 = ((x - bx) ** 2 + (y - by) ** 2 + (z - bz) ** 2) / (br * br)
        field += np.exp(-d2 * 1.6)
    noise = fbm(int(shape_seed) * 7 + 3)
    return field, noise


def render(field, noise, stage):
    erosion = 0.3 + 0.2 * stage
    billow = np.abs(noise - 0.5) * 2.0   # rounded, cauliflower-like bulges
    dens = np.clip(field * 1.25 - erosion - (noise - 0.5) * (1.3 + 0.4 * stage) - billow * 0.25 * stage, 0, None) * (1.0 - 0.15 * stage)
    # light from above: optical depth from each voxel up to the top of the volume (y is axis 1)
    above = np.cumsum(dens[:, ::-1, :], axis=1)[:, ::-1, :] - dens
    dz = 2.0 / N
    sigma = 9.0
    light = 0.28 + 0.9 * np.exp(-above * dz * sigma * 0.55)
    # march front to back along z (axis 2, from +z towards -z: the viewer is at +z)
    trans = np.ones((N, N))
    col = np.zeros((N, N))
    for k in range(N - 1, -1, -1):
        a = 1.0 - np.exp(-dens[:, :, k] * sigma * dz)
        col += trans * a * light[:, :, k]
        trans *= 1.0 - a
    alpha = 1.0 - trans
    bright = np.where(alpha > 1e-4, col / np.maximum(alpha, 1e-4), 0.0)
    # to image space: x -> columns, y up -> rows from the top
    img_a = alpha.T[::-1, :]
    img_c = bright.T[::-1, :]
    im_a = Image.fromarray((np.clip(img_a, 0, 1) * 255).astype(np.uint8)).resize((S, S), Image.BICUBIC)
    im_c = Image.fromarray((np.clip(img_c, 0, 1) * 255).astype(np.uint8)).resize((S, S), Image.BICUBIC)
    a = np.asarray(im_a).astype(float) / 255.0
    c = np.asarray(im_c).astype(float) / 255.0
    # keep the sprite clear of its cell edges
    yy, xx = np.mgrid[0:S, 0:S]
    rr = np.sqrt((xx - S / 2 + 0.5) ** 2 + (yy - S / 2 + 0.5) ** 2) / (S / 2)
    a *= np.clip((1.0 - rr) / 0.12, 0, 1)
    out = np.zeros((S, S, 4), dtype=np.uint8)
    g = np.clip(c * 255, 0, 255).astype(np.uint8)
    out[..., 0] = g
    out[..., 1] = g
    out[..., 2] = g
    out[..., 3] = np.clip(a * 255, 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def main():
    atlas = Image.new("RGBA", (S * 4, S * 4), (0, 0, 0, 0))
    for shape in range(4):
        field, noise = density(100 + shape * 13)
        for stage in range(4):
            atlas.paste(render(field, noise, stage), (shape * S, stage * S))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    atlas.save(OUT)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
