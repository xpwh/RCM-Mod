"""9.1 runway textures: asphalt, painted markings, edge-light fixtures, the runway kit.

python3 gen_textures_v13.py   (writes into the mod's block textures)
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "block")
rng = np.random.default_rng(1991)


def save(name, rgb):
    Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB").save(os.path.join(OUT, name + ".png"))


def aggregate(base, spread, speckle):
    """Asphalt: dark binder with lighter stone chips."""
    n = rng.normal(0, spread, (16, 16))
    img = np.stack([base[0] + n, base[1] + n, base[2] + n * 1.05], -1)
    chips = rng.random((16, 16)) < speckle
    img[chips] += rng.uniform(18, 42, (chips.sum(), 1))
    pits = rng.random((16, 16)) < 0.05
    img[pits] -= 14
    return img


asphalt = aggregate((52, 53, 56), 4.0, 0.12)
save("runway_asphalt", asphalt)

# white paint on asphalt: worn, with the aggregate showing through here and there and rubber marks
paint = np.full((16, 16, 3), 228.0) + rng.normal(0, 5, (16, 16, 1))
worn = rng.random((16, 16)) < 0.07
paint[worn] = asphalt[worn] + 40
save("runway_marking", paint)

# edge light: a squat grey can with a coloured glass dome
for name, glass in [("white", (255, 246, 214)), ("green", (90, 255, 120)), ("red", (255, 70, 60))]:
    img = np.zeros((16, 16, 3))
    img[:] = (120, 122, 126)
    img += rng.normal(0, 4, (16, 16, 1))
    yy, xx = np.mgrid[0:16, 0:16]
    r = np.hypot(xx - 7.5, yy - 7.5)
    dome = r < 6.5
    shade = np.clip(1.15 - r / 9.0, 0.55, 1.15)[..., None]
    img[dome] = (np.array(glass) * shade)[dome]
    img[(r >= 6.5) & (r < 7.5)] = (70, 72, 76)
    save("runway_light_" + name, img)
    # the post/can side
    side = np.zeros((16, 16, 3))
    side[:] = (118, 120, 124)
    side += rng.normal(0, 4, (16, 16, 1))
    side[:5] = np.array(glass) * 0.95
    side[5] = (70, 72, 76)
    save("runway_light_side_" + name, side)

# runway kit: an asphalt slab with a white centreline and a threshold stripe on top, crates on the side
top = asphalt.copy()
top[:, 7:9] = 228
top[1:4, :] = 228
top[1:4, 7:9] = asphalt[1:4, 7:9]
save("runway_kit_top", top)
side = np.zeros((16, 16, 3))
side[:] = (112, 98, 64)
side += rng.normal(0, 6, (16, 16, 1))
side[[0, 7, 15], :] = (80, 68, 44)
side[:, [0, 15]] = (80, 68, 44)
side[10:13, 3:13] = (222, 222, 222)
save("runway_kit_side", side)
print("ok")
