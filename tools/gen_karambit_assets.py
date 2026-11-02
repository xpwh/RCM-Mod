"""12.3: the karambit - the atlas its mesh is coloured from (stonewashed blade, polished edge bevel, G10,
liner, pins), the icon, item models, sounds, recipe. (The sounds themselves: tools/import_knife_sounds.py.)

python3 tools/gen_karambit_assets.py
"""
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
A = os.path.join(ROOT, "assets", "ballisticmissiles")
D = os.path.join(ROOT, "data", "ballisticmissiles")
NS = "ballisticmissiles"
rng = np.random.default_rng(1230)


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent="\t", ensure_ascii=False)
        f.write("\n")


def cell(rgb, noise):
    a = np.zeros((16, 16, 3), np.float32)
    a[...] = rgb
    a += rng.normal(0, noise, (16, 16, 1))
    return a


def atlas():
    img = np.zeros((128, 128, 4), np.float32)
    # 0 the blade's flat: black coating, a faint satin sheen
    flat = cell((64, 64, 68), 3)
    for x in range(16):
        flat[:, x] *= 0.95 + 0.08 * rng.random()
    # 1 the sharpened edge: bright steel where the coating is ground off
    edge = cell((196, 200, 204), 4)
    # 2 G10: tan, its fine sanded grain; 8 the wave grooves milled into it, in shadow on one side
    g = cell((188, 170, 132), 4)
    ridge = cell((150, 134, 102), 4)
    for x in range(16):
        ridge[:, x] *= 0.8 + 0.35 * math.sin(x / 15.0 * math.pi)
    # 3 the tang's edge (the light line under the scales), 4 screw heads, 5 jimping / torx recess
    # 6 the bevel: the coating a shade darker, 7 the ring: black steel
    patches = {0: flat, 1: edge, 2: g, 3: cell((172, 172, 168), 4), 4: cell((30, 30, 33), 2), 5: cell((20, 20, 22), 2),
               6: cell((42, 42, 46), 3), 7: cell((48, 48, 52), 3), 8: ridge}
    for i, c in patches.items():
        x0, y0 = (i % 8) * 16, (i // 8) * 16
        img[y0:y0 + 16, x0:x0 + 16, :3] = c
        img[y0:y0 + 16, x0:x0 + 16, 3] = 255
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(os.path.join(A, "textures", "entity", "karambit.png"))


def icon():
    s = 8  # drawn at 128 px and shrunk, for clean curves
    im = Image.new("RGBA", (16 * s, 16 * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # ring, top left
    d.ellipse([1 * s, 1 * s, 6.5 * s, 6.5 * s], outline=(40, 42, 46, 255), width=int(1.6 * s))
    # handle, down to the right
    d.polygon([(4.5 * s, 5.2 * s), (6.6 * s, 4.2 * s), (10.2 * s, 9.6 * s), (8.2 * s, 10.8 * s)], fill=(38, 40, 44, 255))
    d.ellipse([6.3 * s, 6.3 * s, 7.2 * s, 7.2 * s], fill=(190, 194, 198, 255))
    d.ellipse([8.1 * s, 8.6 * s, 9.0 * s, 9.5 * s], fill=(190, 194, 198, 255))
    # the claw: an arc from the foot of the handle sweeping round and up
    cx, cy, r = 11.2 * s, 7.4 * s, 4.4 * s
    pts_out, pts_in = [], []
    for i in range(21):
        a = math.radians(140 - i * 9.5)
        w = (1.0 - i / 20) ** 0.75 * 2.6 * s + 0.6 * s
        pts_out.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        pts_in.append((cx + (r - w) * math.cos(a), cy + (r - w) * math.sin(a)))
    d.polygon(pts_out + pts_in[::-1], fill=(108, 112, 116, 255))
    d.line(pts_in, fill=(226, 230, 236, 255), width=int(0.7 * s))
    im = im.resize((16, 16), Image.LANCZOS)
    a = np.array(im).astype(np.float32)
    solid = a[..., 3] > 70
    a[..., :3] = np.where(solid[..., None], a[..., :3] / np.maximum(a[..., 3:4], 1) * 255, 0)
    a[..., 3] = np.where(solid, 255, 0)
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")
    im.save(os.path.join(A, "textures", "item", "karambit.png"))


def models():
    write(os.path.join(A, "models", "item", "karambit.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/karambit"}})
    write(os.path.join(A, "models", "item", "karambit_in_hand.json"), {
        "textures": {"particle": f"{NS}:item/karambit"},
        "display": {
            "thirdperson_righthand": {"rotation": [0, -90, 0], "translation": [0, 1.5, 1.0], "scale": [1.3, 1.3, 1.3]},
            "thirdperson_lefthand": {"rotation": [0, 90, 0], "translation": [0, 1.5, 1.0], "scale": [1.3, 1.3, 1.3]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 1, 0], "scale": [1.0, 1.0, 1.0]},
            "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [1.8, 1.8, 1.8]},
        }})
    write(os.path.join(A, "items", "karambit.json"), {"model": {
        "type": "minecraft:select", "property": "minecraft:display_context",
        "cases": [
            {"when": ["gui"], "model": {"type": "minecraft:model", "model": f"{NS}:item/karambit"}},
            {"when": ["firstperson_righthand"], "model": {"type": "minecraft:empty"}},
        ],
        "fallback": {"type": "minecraft:special", "base": f"{NS}:item/karambit_in_hand", "model": {"type": f"{NS}:karambit"}}}})


def sounds():
    path = os.path.join(A, "sounds.json")
    with open(path, encoding="utf-8") as fh:
        js = json.load(fh)

    def ev(name, files, sub, dist=10):
        js[name] = {"subtitle": f"subtitles.ballisticmissiles.{sub}",
                    "sounds": [{"name": f"{NS}:knife/{f}", "attenuation_distance": dist} for f in files]}

    ev("knife.swish", [f"swish{i}" for i in range(1, 11)], "knife_swish", 12)
    ev("knife.spin", ["spin"], "knife_spin", 8)
    ev("knife.draw", ["draw1", "draw2"], "knife_draw", 10)
    ev("knife.catch", ["catch"], "knife_catch", 8)
    ev("knife.hit", ["hit"], "knife_hit", 12)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(js, fh, indent="\t", ensure_ascii=False)
        fh.write("\n")


def recipe():
    write(os.path.join(D, "recipe", "karambit.json"), {
        "type": "minecraft:crafting_shaped", "category": "equipment",
        "pattern": ["  I", " IB", "N  "],
        "key": {"I": "minecraft:iron_ingot", "B": "minecraft:black_dye", "N": "minecraft:iron_nugget"},
        "result": {"id": f"{NS}:karambit", "count": 1}})


if __name__ == "__main__":
    atlas()
    icon()
    models()
    sounds()
    recipe()
