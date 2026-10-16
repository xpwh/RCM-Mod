"""1.8 bunker blocks: the bunker kit, the NBC air filter (off/on) and the emergency generator (off/on)."""
import os

import numpy as np
from PIL import Image

np.random.seed(909)
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "block")
N = 16


def base(rgb, grain=0.12):
    img = np.full((N, N, 3), rgb, dtype=float)
    img *= (1.0 - grain / 2 + grain * np.random.rand(N, N))[..., None]
    return img


def frame(img, rgb):
    img[0, :] = img[-1, :] = rgb
    img[:, 0] = img[:, -1] = rgb


def save(name, img):
    os.makedirs(OUT, exist_ok=True)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").save(os.path.join(OUT, name + ".png"))


# bunker kit: a crate of concrete with a steel hatch and hazard stripes
kit = base((128, 128, 124))
frame(kit, (96, 96, 92))
kit[4:12, 4:12] = (78, 82, 84)
kit[5:11, 5:11] = (96, 100, 102)
kit[7:9, 5:11] = (60, 62, 64)
for i in range(N):
    if (i // 2) % 2 == 0:
        kit[14, i] = (230, 180, 30)
        kit[1, i] = (230, 180, 30)
save("bunker_top", kit)
side = base((128, 128, 124))
frame(side, (96, 96, 92))
for i in range(N):
    side[13:15, i] = (230, 180, 30) if (i // 2) % 2 == 0 else (30, 30, 30)
side[4:9, 5:11] = (180, 30, 26)  # trefoil-ish warning plate
side[6, 7:9] = (250, 220, 40)
save("bunker_side", side)

# air filter: steel cabinet, filter grille, pressure gauge, status lamp
steel = base((116, 122, 118))
frame(steel, (80, 84, 82))
for y in range(3, 13, 2):
    steel[y, 2:14] = (70, 74, 72)  # louvres
save("air_filter_side", steel)
top = base((116, 122, 118))
frame(top, (80, 84, 82))
top[5:11, 5:11] = (60, 64, 62)
top[6:10, 6:10] = (90, 94, 92)  # intake
save("air_filter_top", top)
for lit in (False, True):
    f = base((116, 122, 118))
    frame(f, (80, 84, 82))
    for y in range(6, 14):
        for x in range(2, 14):
            f[y, x] = (52, 56, 54) if (x + y) % 2 == 0 else (88, 92, 90)  # filter grille
    f[2:5, 2:5] = (230, 230, 220)  # gauge
    f[3, 3] = (40, 40, 40)
    f[2:4, 11:13] = (60, 230, 90) if lit else (40, 70, 44)  # status lamp
    save("air_filter_front" + ("_on" if lit else ""), f)

# emergency generator: yellow diesel set with louvres, control panel, exhaust
yel = base((206, 168, 40))
frame(yel, (150, 120, 30))
for y in range(3, 13, 2):
    yel[y, 2:14] = (120, 96, 24)
save("emergency_generator_side", yel)
gt = base((206, 168, 40))
frame(gt, (150, 120, 30))
gt[6:10, 6:10] = (40, 40, 40)
gt[7:9, 7:9] = (20, 20, 20)  # exhaust stub
save("emergency_generator_top", gt)
for lit in (False, True):
    g = base((206, 168, 40))
    frame(g, (150, 120, 30))
    g[3:9, 3:13] = (50, 54, 56)  # control panel
    g[4:6, 4:8] = (120, 200, 110) if lit else (40, 60, 40)  # display
    g[4:6, 10:12] = (240, 60, 40) if lit else (90, 40, 30)  # running lamp
    g[11:14, 3:13] = (60, 60, 60)  # grille
    save("emergency_generator_front" + ("_on" if lit else ""), g)
print("ok")
