"""9.10 shotgun and gore patches for textures/entity/structures.png (see gen_textures_v11.py for the layout).

51 SG_WALNUT   oiled American walnut: dark brown, long straight grain with darker streaks, satin sheen
52 SG_PARK     matte black-grey parkerized steel of the receiver and barrel, fine speckle, edges worn grey
53 SG_PAD      black rubber recoil pad: horizontal ribs
54 SG_HULL     red ribbed plastic shotshell hull
55 SG_CHECKER  checkered walnut (diamond pattern) on the grip and the forend
56 SG_BORE     the dark, slightly glossy bore and recesses
57 GORE_FLESH  torn muscle: deep red with darker fibres and lighter fat and fascia flecks
58 GORE_BONE   bone: off-white cortex, a little yellow, with a pinkish blush
59 GORE_CLOTH  torn cloth soaked dark with blood
60 GORE_BLOOD  fresh blood: dark glossy red with a highlight
"""
import os

import numpy as np
from PIL import Image

rng = np.random.default_rng(870)
PATH = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "entity", "structures.png")
P = 16


def put(atlas, i, img):
    atlas.paste(img, ((i % 8) * P, (i // 8) * P))


def rgba(img):
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def rim(img, color, strength, width=1):
    out = img.copy()
    for k in range(width):
        f = strength * (1.0 - k / width)
        for sl in (np.s_[k, :], np.s_[P - 1 - k, :], np.s_[:, k], np.s_[:, P - 1 - k]):
            noise = 0.6 + 0.4 * rng.random(out[sl].shape[:-1])[..., None]
            out[sl] = out[sl] * (1 - f * noise) + np.array(color, float) * f * noise
    return out


def walnut():
    base = np.array((92, 54, 32), float)
    img = np.zeros((P, P, 3))
    ys = np.arange(P)
    for x in range(P):
        # long grain running across the patch, a few dark streaks
        img[:, x] = base * (0.86 + 0.18 * np.sin(ys * 0.9 + x * 0.12 + np.sin(x * 0.35) * 2.0))[..., None]
    for _ in range(3):
        y = rng.integers(0, P)
        img[y] *= 0.72
    img *= (0.95 + 0.08 * rng.random((P, P)))[..., None]
    img[5:7] *= 1.1  # oil sheen
    return rgba(rim(img, (130, 84, 52), 0.3))


def parkerized():
    img = np.full((P, P, 3), (44, 45, 47), dtype=float)
    img *= (0.82 + 0.3 * rng.random((P, P)))[..., None]
    return rgba(rim(img, (112, 112, 110), 0.45))


def pad():
    img = np.full((P, P, 3), (24, 24, 25), dtype=float)
    for y in range(P):
        if y % 3 == 0:
            img[y] *= 0.55
        elif y % 3 == 1:
            img[y] *= 1.35
    img *= (0.92 + 0.12 * rng.random((P, P)))[..., None]
    return rgba(img)


def hull():
    img = np.full((P, P, 3), (168, 26, 22), dtype=float)
    for x in range(P):
        img[:, x] *= 0.85 if x % 2 == 0 else 1.08
    img *= (0.94 + 0.08 * rng.random((P, P)))[..., None]
    return rgba(rim(img, (200, 70, 60), 0.25))


def checker():
    base = np.array((84, 50, 30), float)
    img = np.zeros((P, P, 3))
    for y in range(P):
        for x in range(P):
            d = ((x + y) % 4 == 0) or ((x - y) % 4 == 0)
            img[y, x] = base * (0.58 if d else 1.0)
    img *= (0.94 + 0.1 * rng.random((P, P)))[..., None]
    return rgba(rim(img, (110, 70, 44), 0.25))


def bore():
    img = np.full((P, P, 3), (10, 10, 12), dtype=float)
    img[6:9] = (34, 34, 38)
    img *= (0.9 + 0.2 * rng.random((P, P)))[..., None]
    return rgba(img)


def flesh():
    img = np.full((P, P, 3), (128, 18, 20), dtype=float)
    for y in range(P):
        img[y] *= 0.8 + 0.3 * np.sin(y * 1.3 + np.arange(P) * 0.25)[..., None] ** 2
    img *= (0.75 + 0.4 * rng.random((P, P)))[..., None]
    for _ in range(5):  # fat and fascia
        y, x = rng.integers(0, P, 2)
        img[y, x] = (214, 178, 140)
    for _ in range(6):  # clotted, darker
        y, x = rng.integers(0, P, 2)
        img[y, x] = (62, 6, 8)
    return rgba(img)


def bone():
    img = np.full((P, P, 3), (226, 214, 186), dtype=float)
    img *= (0.88 + 0.14 * rng.random((P, P)))[..., None]
    img[:, :3] = img[:, :3] * 0.7 + np.array((190, 110, 100)) * 0.3
    return rgba(rim(img, (150, 40, 36), 0.5))


def cloth():
    img = np.full((P, P, 3), (60, 18, 16), dtype=float)
    for y in range(P):
        for x in range(P):
            if (x + y) % 2 == 0:
                img[y, x] *= 0.82  # the weave
    img *= (0.8 + 0.35 * rng.random((P, P)))[..., None]
    return rgba(rim(img, (96, 10, 10), 0.4))


def blood():
    img = np.full((P, P, 3), (104, 4, 8), dtype=float)
    img *= (0.85 + 0.2 * rng.random((P, P)))[..., None]
    img[4:6, 4:9] = (190, 60, 64)  # wet highlight
    return rgba(img)


def main():
    atlas = Image.open(PATH).convert("RGBA")
    put(atlas, 51, walnut())
    put(atlas, 52, parkerized())
    put(atlas, 53, pad())
    put(atlas, 54, hull())
    put(atlas, 55, checker())
    put(atlas, 56, bore())
    put(atlas, 57, flesh())
    put(atlas, 58, bone())
    put(atlas, 59, cloth())
    put(atlas, 60, blood())
    atlas.save(PATH)


if __name__ == "__main__":
    main()
