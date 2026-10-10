"""9.3 cockpit patches for the fighter atlas (textures/entity/fighter_jets.png, 8x8 grid of 16 px patches).

  20 cockpit grey (FS 36231 dark gull grey, as US cockpits are painted)
  21 console panel: black with rows of switches and backlit legends
  22 display bezel: black frame with the button rows around a dark glass
  23 seat cushion: dark green-black with stitching
  24 ejection handle: yellow and black stripes
  25 plain white (tinted per vertex for the live displays)
  26 canopy frame / sill: near-black
  27 stick and throttle grips: black rubber
python3 gen_cockpit_patches.py
"""
import os

import numpy as np
from PIL import Image

PATH = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "entity",
                    "fighter_jets.png")
rng = np.random.default_rng(935)


def noise(base, amount):
    p = np.zeros((16, 16, 4))
    p[..., :3] = np.array(base) + rng.normal(0, amount, (16, 16, 1))
    p[..., 3] = 255
    return p


def put(atlas, index, patch):
    y, x = (index // 8) * 16, (index % 8) * 16
    atlas[y:y + 16, x:x + 16] = np.clip(patch, 0, 255)


atlas = np.array(Image.open(PATH).convert("RGBA")).astype(float)
put(atlas, 20, noise((78, 82, 86), 3))
panel = noise((24, 25, 27), 2)
for row in (3, 8, 13):
    for col in range(2, 15, 3):
        panel[row - 1:row + 1, col:col + 2, :3] = (150, 150, 150)  # toggle switch
        panel[row + 1, col - 1:col + 3, :3] = (190, 220, 140)  # backlit legend
put(atlas, 21, panel)
bezel = noise((20, 20, 22), 1.5)
bezel[3:13, 3:13, :3] = (8, 10, 12)
for i in range(4, 13, 2):
    for (r, c) in ((1, i), (14, i), (i, 1), (i, 14)):
        bezel[r, c, :3] = (110, 110, 110)
put(atlas, 22, bezel)
seat = noise((38, 44, 34), 4)
seat[:, 7, :3] -= 12
seat[7, :, :3] -= 12
put(atlas, 23, seat)
handle = noise((30, 30, 30), 2)
for x in range(16):
    for y in range(16):
        if ((x + y) // 3) % 2 == 0:
            handle[y, x, :3] = (235, 200, 30)
put(atlas, 24, handle)
white = np.full((16, 16, 4), 255.0)
put(atlas, 25, white)
put(atlas, 26, noise((34, 35, 37), 2))
put(atlas, 27, noise((18, 18, 19), 2.5))
Image.fromarray(atlas.astype(np.uint8), "RGBA").save(PATH)
print("ok")
