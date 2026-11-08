"""1.9 structure patches for textures/entity/structures.png:

40 BOMB_GRAY  B61 case: matte light-grey paint, faint panel seams and a stencilled data block
41 TUNGSTEN   the orbital rod: dark, dense metal with fine machining lines
42 ABLATIVE   charred carbon-composite heat shield of the rod's nose
43 SHELTER    white corrugated equipment shelter (ground station)
44 AK_MAG     bakelite AK magazine
"""
import os
import random

import numpy as np
from PIL import Image

random.seed(4242)
np.random.seed(4242)
PATH = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "entity", "structures.png")
P = 16


def put(atlas, i, img):
    atlas.paste(img, ((i % 8) * P, (i // 8) * P))


def rgba(img):
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def bomb_gray():
    img = np.full((P, P, 3), (150, 154, 152), dtype=float)
    img *= (0.94 + 0.08 * np.random.rand(P, P))[..., None]
    img[:, 0] *= 0.82  # panel seam
    img[5:8, 9:14] = (70, 72, 72)  # stencilled data block
    img[6, 10:13] = (150, 150, 146)
    return rgba(img)


def tungsten():
    img = np.full((P, P, 3), (78, 80, 86), dtype=float)
    for y in range(P):
        img[y] *= 0.9 + 0.12 * ((y * 7) % 5) / 4.0  # machining lines around the rod
    img *= (0.95 + 0.08 * np.random.rand(P, P))[..., None]
    return rgba(img)


def ablative():
    img = np.full((P, P, 3), (40, 34, 30), dtype=float)
    img *= (0.7 + 0.5 * np.random.rand(P, P))[..., None]
    for _ in range(10):
        x, y = random.randrange(P), random.randrange(P)
        img[y, x] = (92, 70, 52)  # scorched flecks
    return rgba(img)


def shelter():
    img = np.full((P, P, 3), (222, 224, 220), dtype=float)
    for x in range(0, P, 3):
        img[:, x] *= 0.86  # corrugations
    img *= (0.96 + 0.05 * np.random.rand(P, P))[..., None]
    img[P - 2:, :] = (150, 146, 136)  # grime at the bottom
    return rgba(img)


def ak_mag():
    """44: the AK's bakelite magazine: orange-brown, a little mottled, with dark ribs."""
    img = np.full((P, P, 3), (150, 62, 30), dtype=float)
    img *= (0.85 + 0.25 * np.random.rand(P, P))[..., None]
    for y in range(2, P, 5):
        img[y, :] *= 0.7
    return rgba(img)


def main():
    atlas = Image.open(PATH).convert("RGBA")
    put(atlas, 44, ak_mag())
    put(atlas, 40, bomb_gray())
    put(atlas, 41, tungsten())
    put(atlas, 42, ablative())
    put(atlas, 43, shelter())
    atlas.save(PATH)


if __name__ == "__main__":
    main()
