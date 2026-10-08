"""1.8 structure patches for textures/entity/structures.png:

30 SUB_TILES  anechoic coating of the submarine's pressure hull: rubber tiles in a grid, some
              of them a shade off where they were replaced, a few missing
31 SUB_DECK   the missile deck: dark non-skid surface with a faint walkway line
32 BEAM_HAZE  the laser beam's scattered glow: faint, translucent cyan-white
33 HOT        white-hot glowing metal for the laser's burn spot
34 FLASH      bright orange-yellow muzzle/launch flash
"""
import os
import random

import numpy as np
from PIL import Image

random.seed(3031)
np.random.seed(3031)
PATH = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "entity", "structures.png")
P = 16


def put(atlas, i, img):
    atlas.paste(img, ((i % 8) * P, (i // 8) * P))


def tiles():
    img = np.zeros((P, P, 3))
    for ty in range(0, P, 4):
        for tx in range(0, P, 4):
            shade = 34 + random.choice([0, 0, 0, 3, -3, 6])
            if random.random() < 0.05:
                shade = 52  # missing tile: bare steel showing
            img[ty:ty + 4, tx:tx + 4] = (shade, shade + 2, shade + 5)
            img[ty, tx:tx + 4] *= 0.7  # grout lines
            img[ty:ty + 4, tx] *= 0.7
    img *= (0.94 + 0.12 * np.random.rand(P, P))[..., None]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def deck():
    img = np.full((P, P, 3), (40, 42, 44), dtype=float)
    img *= (0.85 + 0.3 * np.random.rand(P, P))[..., None]  # grit
    img[7:9, :] = (58, 60, 60)  # walkway line
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


if __name__ == "__main__":
    atlas = Image.open(PATH).convert("RGBA")
    put(atlas, 30, tiles())
    put(atlas, 31, deck())
    put(atlas, 32, Image.new("RGBA", (P, P), (170, 235, 255, 60)))
    put(atlas, 33, Image.new("RGBA", (P, P), (255, 236, 200, 255)))
    put(atlas, 34, Image.new("RGBA", (P, P), (255, 170, 60, 200)))
    atlas.save(PATH)
    print("ok")
