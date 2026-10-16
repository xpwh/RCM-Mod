"""12.6: a round into water - the drop, spout, mist and ripple particle textures.

python3 tools/gen_water_fx.py
"""
import json
import os

import numpy as np
from PIL import Image

ASSETS = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles")
OUT = os.path.join(ASSETS, "textures", "particle")
rng = np.random.default_rng(126)


def save(name, rgb, a):
    img = np.zeros(a.shape + (4,))
    img[..., :3] = rgb
    img[..., 3] = a * 255
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(os.path.join(OUT, name + ".png"))


def grid(n):
    y, x = np.mgrid[0:n, 0:n] + 0.5
    return (x - n / 2) / (n / 2), (y - n / 2) / (n / 2)


def noise(n, scale):
    # smooth value noise: random grid blown up and blurred
    g = rng.random((scale + 1, scale + 1))
    im = Image.fromarray((g * 255).astype(np.uint8)).resize((n, n), Image.BICUBIC)
    return np.asarray(im, np.float32) / 255


def drop(i, squash):
    # a bead of clear water: you see through it, with a dark refracting rim, a bright caustic crescent at
    # the bottom where it focuses the light, and a hard specular glint up top
    n = 16
    x, y = grid(n)
    r = np.sqrt(x * x + (y / squash) ** 2)
    body = np.clip((1.0 - r) * 5.0, 0, 1)
    rim = np.exp(-((r - 0.78) / 0.14) ** 2)
    caustic = np.exp(-((x * 1.3) ** 2 + ((y - 0.48) / 0.22) ** 2)) * (r < 0.95)
    glint = np.exp(-(((x + 0.32) ** 2 + (y + 0.36) ** 2) / 0.018))
    lum = 0.7 - 0.25 * rim + 0.5 * caustic + 0.9 * glint
    a = body * np.clip(0.28 + 0.5 * rim + 0.45 * caustic + glint, 0, 1)
    rgb = np.stack([lum * 225, lum * 240, np.minimum(255, lum * 262)], -1)
    save(f"water_drop_{i}", rgb, a)


def jet(i):
    # a slug of white, frothy water: soft-edged, streaky up and down, light and dark clots in it
    n = 16
    x, y = grid(n)
    edge = np.clip(1.0 - x * x - 0.35 * y ** 4, 0, 1) ** 1.4
    froth = 0.55 * noise(n, 5) + 0.45 * noise(n, 9)
    streak = np.repeat(rng.random((1, n)), n, 0)
    streak = np.asarray(Image.fromarray((streak * 255).astype(np.uint8)).resize((n, n), Image.BILINEAR), np.float32) / 255
    a = edge * np.clip(0.35 + 0.65 * froth - 0.2 * streak, 0, 1)
    lum = 0.78 + 0.22 * froth
    rgb = np.stack([lum * 238, lum * 246, lum * 255], -1)
    save(f"water_jet_{i}", rgb, a)


def mist(i):
    n = 16
    x, y = grid(n)
    r = np.sqrt(x * x + y * y)
    a = np.clip(1.0 - r, 0, 1) ** 1.1 * (0.55 + 0.45 * noise(n, 4))
    rgb = np.full((n, n, 3), 245.0)
    save(f"water_mist_{i}", rgb, a)


def ripple():
    # the crest of a ring wave catching the light, the trough just inside it darker, a weaker ring inside
    n = 64
    x, y = grid(n)
    r = np.sqrt(x * x + y * y)
    ang = np.arctan2(y, x)
    wobble = sum(rng.normal(0, 0.004) * np.sin(k * ang + rng.uniform(0, 6.3)) for k in range(3, 9))
    crest = np.exp(-((r - 0.88 + wobble) / 0.035) ** 2)
    trough = np.exp(-((r - 0.8 + wobble) / 0.04) ** 2)
    inner = 0.4 * np.exp(-((r - 0.62 + wobble) / 0.035) ** 2)
    a = np.clip(crest + 0.45 * trough + inner, 0, 1) * (r < 1.0)
    lum = np.clip(0.55 + 0.45 * (crest + inner) / np.maximum(crest + 0.45 * trough + inner, 1e-3), 0, 1)
    rgb = np.stack([lum * 232, lum * 243, lum * 255], -1)
    save("water_ripple", rgb, a)


def particle_json(name, textures):
    with open(os.path.join(ASSETS, "particles", name + ".json"), "w") as f:
        json.dump({"textures": [f"ballisticmissiles:{t}" for t in textures]}, f, indent="\t")
        f.write("\n")


if __name__ == "__main__":
    for i, s in enumerate((1.0, 1.18, 0.9)):
        drop(i, s)
    for i in range(4):
        jet(i)
    for i in range(3):
        mist(i)
    ripple()
    particle_json("water_drop", [f"water_drop_{i}" for i in range(3)])
    particle_json("water_jet", [f"water_jet_{i}" for i in range(4)])
    particle_json("water_mist", [f"water_mist_{i}" for i in range(3)])
    particle_json("water_ripple", ["water_ripple"])
