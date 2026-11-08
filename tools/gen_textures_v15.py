"""10.0 Glock patch for textures/entity/structures.png (see gen_textures_v11.py for the layout).

63 GL_POLY   the Glock's black polymer frame and magazine: matte, a faint grey stipple, edges a little lighter
"""
import os

import numpy as np
from PIL import Image

rng = np.random.default_rng(1919)
PATH = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "entity", "structures.png")
P = 16


def polymer():
    img = np.full((P, P, 3), (30, 30, 32), dtype=float)
    img *= (0.88 + 0.16 * rng.random((P, P)))[..., None]
    # the stipple: tiny raised dots catching a little light
    for _ in range(26):
        y, x = rng.integers(0, P, 2)
        img[y, x] = (52, 52, 55)
    for sl in (np.s_[0, :], np.s_[P - 1, :], np.s_[:, 0], np.s_[:, P - 1]):
        img[sl] = img[sl] * 0.7 + np.array((70, 70, 72)) * 0.3
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def main():
    atlas = Image.open(PATH).convert("RGBA")
    atlas.paste(polymer(), ((63 % 8) * P, (63 // 8) * P))
    atlas.save(PATH)


if __name__ == "__main__":
    main()
