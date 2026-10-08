"""1.12: the AK-47 (milled receiver) instead of the AKM - patches for textures/entity/structures.png.

44 AK_MAG       the AK-47's slab-sided steel magazine: black phosphate, worn grey on the edges
46 AK_LAMINATE  solid birch / walnut furniture under amber shellac: straight grain, no plies
47 AK_GRIP      the wooden pistol grip, same wood, darker from the hand
"""
import numpy as np

from gen_textures_v11 import P, put, rgba, rim, rng, PATH
from PIL import Image


def wood(base, dark):
    img = np.zeros((P, P, 3))
    xs = np.arange(P)
    for y in range(P):
        # long straight grain running along the part, the odd darker late-wood line
        line = 0.9 + 0.12 * np.sin(y * 1.9 + rng.random() * 0.8) + (0.0 if rng.random() > 0.2 else -0.18)
        img[y] = np.array(base, float) * line
        img[y] *= (0.96 + 0.06 * np.sin(xs * 0.25 + y * 0.4))[..., None]
    img *= (0.95 + 0.08 * rng.random((P, P)))[..., None]
    img[2:4, :] *= 1.1  # the shellac catching the light
    return rgba(rim(img, dark, 0.3))


def slab_mag():
    img = np.full((P, P, 3), (44, 46, 50), dtype=float)
    img *= (0.9 + 0.16 * rng.random((P, P)))[..., None]
    return rgba(rim(img, (128, 130, 134), 0.5))


def main():
    atlas = Image.open(PATH).convert("RGBA")
    put(atlas, 44, slab_mag())
    put(atlas, 46, wood((134, 74, 34), (176, 112, 62)))
    put(atlas, 47, wood((112, 58, 28), (150, 92, 52)))
    atlas.save(PATH)


if __name__ == "__main__":
    main()
