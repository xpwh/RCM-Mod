"""1.11 AK patches for textures/entity/structures.png. Every face of a BoxMesh shows a whole patch,
so a lighter rim on a patch reads as worn edges on every part.

44 AK_MAG       bakelite magazine: orange-brown, mottled, worn rim
45 AK_BLUED     blued / phosphated steel: almost black, faint blue, silvered rim where the finish wore off
46 AK_LAMINATE  laminated birch stock and handguards, stained red-brown, grain lines and dark glue layers
47 AK_GRIP      the reddish polymer pistol grip, stippled
48 BRASS        cartridge brass
49 AK_BRIGHT    the bright machined bolt carrier
50 AK_WORN      steel worn grey on edges of the muzzle device and sights
"""
import os

import numpy as np
from PIL import Image

rng = np.random.default_rng(1947)
PATH = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "entity", "structures.png")
P = 16


def put(atlas, i, img):
    atlas.paste(img, ((i % 8) * P, (i // 8) * P))


def rgba(img):
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def rim(img, color, strength, width=1):
    """Blend a worn rim into the patch border."""
    out = img.copy()
    for k in range(width):
        f = strength * (1.0 - k / width)
        for sl in (np.s_[k, :], np.s_[P - 1 - k, :], np.s_[:, k], np.s_[:, P - 1 - k]):
            noise = 0.6 + 0.4 * rng.random(out[sl].shape[:-1])[..., None]
            out[sl] = out[sl] * (1 - f * noise) + np.array(color, float) * f * noise
    return out


def blued():
    img = np.full((P, P, 3), (34, 36, 42), dtype=float)
    img *= (0.88 + 0.2 * rng.random((P, P)))[..., None]
    # faint brushed streaks
    for y in range(P):
        img[y] *= 0.96 + 0.06 * np.sin(y * 1.7)
    return rgba(rim(img, (120, 122, 128), 0.55))


def laminate():
    img = np.zeros((P, P, 3))
    base = np.array((128, 52, 26), float)
    for y in range(P):
        layer = 0.82 + 0.25 * ((y * 5) % 7) / 6.0
        img[y] = base * layer
        if y % 4 == 3:
            img[y] = base * 0.55  # dark glue line between the plies
    # wavy grain
    xs = np.arange(P)
    for y in range(P):
        img[y] *= (0.93 + 0.1 * np.sin(xs * 0.6 + y * 0.9 + rng.random() * 2))[..., None]
    img *= (0.94 + 0.1 * rng.random((P, P)))[..., None]
    # varnish catching the light, worn lighter on the edges
    img[3:5, :] *= 1.12
    return rgba(rim(img, (170, 98, 60), 0.35))


def grip():
    img = np.full((P, P, 3), (118, 40, 26), dtype=float)
    img *= (0.75 + 0.4 * rng.random((P, P)))[..., None]  # stippling
    return rgba(rim(img, (150, 70, 50), 0.3))


def brass():
    img = np.full((P, P, 3), (196, 152, 72), dtype=float)
    for y in range(P):
        img[y] *= 0.85 + 0.25 * np.sin(y * 0.5) ** 2
    img *= (0.95 + 0.07 * rng.random((P, P)))[..., None]
    return rgba(rim(img, (240, 210, 140), 0.4))


def bright():
    img = np.full((P, P, 3), (150, 152, 156), dtype=float)
    for x in range(P):
        img[:, x] *= 0.9 + 0.15 * ((x * 3) % 4) / 3.0  # machining marks
    img *= (0.95 + 0.08 * rng.random((P, P)))[..., None]
    return rgba(rim(img, (210, 212, 216), 0.5))


def worn():
    img = np.full((P, P, 3), (52, 54, 58), dtype=float)
    img *= (0.8 + 0.35 * rng.random((P, P)))[..., None]
    return rgba(rim(img, (150, 150, 152), 0.7, width=2))


def ak_mag():
    img = np.full((P, P, 3), (156, 66, 30), dtype=float)
    blobs = rng.random((P // 2, P // 2))
    big = np.kron(blobs, np.ones((2, 2)))
    img *= (0.8 + 0.25 * big)[..., None]
    img *= (0.92 + 0.12 * rng.random((P, P)))[..., None]
    return rgba(rim(img, (196, 110, 60), 0.4))


def main():
    atlas = Image.open(PATH).convert("RGBA")
    put(atlas, 44, ak_mag())
    put(atlas, 45, blued())
    put(atlas, 46, laminate())
    put(atlas, 47, grip())
    put(atlas, 48, brass())
    put(atlas, 49, bright())
    put(atlas, 50, worn())
    atlas.save(PATH)


if __name__ == "__main__":
    main()
