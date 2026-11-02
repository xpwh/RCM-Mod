"""12.2: cigarettes and the Zippo - the atlas their meshes are coloured from, the item icons, models, sounds,
recipes. (The sounds themselves: tools/import_smoking_sounds.py.)

python3 tools/gen_smoking_assets.py
"""
import json
import os

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
A = os.path.join(ROOT, "assets", "ballisticmissiles")
D = os.path.join(ROOT, "data", "ballisticmissiles")
NS = "ballisticmissiles"
rng = np.random.default_rng(1220)


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent="\t", ensure_ascii=False)
        f.write("\n")


def cell(rgb, noise=4.0):
    a = np.zeros((16, 16, 3), np.float32)
    a[...] = rgb
    a += rng.normal(0, noise, (16, 16, 1))
    return a


def atlas():
    img = np.zeros((128, 128, 4), np.float32)
    patches = {}
    # 0 paper: off-white, faint lengthwise fibres
    p = cell((236, 234, 226), 2)
    for x in range(16):
        p[:, x] *= 0.97 + 0.04 * rng.random()
    patches[0] = p
    # 1 filter: cork tipping, tan-orange with darker speckles
    p = cell((206, 140, 72), 6)
    for _ in range(40):
        x, y = rng.integers(0, 16, 2)
        p[y, x] = p[y, x] * 0.6 + np.array([120, 70, 30]) * 0.4
    patches[1] = p
    # 2 ash: grey, flecked light and dark
    p = cell((128, 126, 122), 18)
    patches[2] = p
    # 3 ember: glowing coal - orange-yellow with red and dark flecks (drawn emissive)
    p = cell((255, 132, 40), 10)
    for _ in range(50):
        x, y = rng.integers(0, 16, 2)
        p[y, x] = [255, 210, 120] if rng.random() < 0.4 else [190, 40, 10]
    patches[3] = p
    # 4 pack red, 5 pack white card, 6 foil, 7 chrome, 8 dark
    patches[4] = cell((176, 24, 30), 3)
    patches[5] = cell((242, 240, 236), 2)
    p = cell((196, 198, 202), 14)
    for _ in range(30):
        x, y = rng.integers(0, 16, 2)
        p[y, x] = [240, 242, 246]
    patches[6] = p
    p = cell((182, 186, 192), 3)
    for y in range(16):
        p[y, :] *= 0.9 + 0.18 * rng.random()  # brushed
    patches[7] = p
    patches[8] = cell((26, 26, 28), 3)
    # 9 flame, 10 its blue root, 11 cardboard, 12 gold
    patches[9] = cell((255, 196, 84), 6)
    patches[10] = cell((90, 140, 255), 6)
    patches[11] = cell((190, 168, 132), 4)
    patches[12] = cell((214, 176, 70), 5)
    for i, p in patches.items():
        x0, y0 = (i % 8) * 16, (i // 8) * 16
        img[y0:y0 + 16, x0:x0 + 16, :3] = p
        img[y0:y0 + 16, x0:x0 + 16, 3] = 255
    path = os.path.join(A, "textures", "entity", "smoking.png")
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(path)


def icons():
    # the pack, lid open, a cigarette standing out of it
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([4, 5, 11, 15], fill=(176, 24, 30, 255))
    d.rectangle([4, 5, 11, 8], fill=(240, 238, 232, 255))
    d.rectangle([6, 11, 9, 11], fill=(214, 176, 70, 255))
    d.line([(4, 5), (4, 15)], fill=(130, 16, 20, 255))
    d.line([(11, 5), (11, 15)], fill=(120, 14, 18, 255))
    d.rectangle([5, 2, 11, 4], fill=(225, 222, 214, 255))  # the lid, flipped back
    d.rectangle([7, 0, 8, 4], fill=(236, 234, 226, 255))  # a cigarette
    d.rectangle([7, 3, 8, 5], fill=(206, 140, 72, 255))
    d.point((5, 5), fill=(196, 198, 202, 255))
    d.point((10, 5), fill=(196, 198, 202, 255))
    im.save(os.path.join(A, "textures", "item", "cigarettes.png"))
    # the Zippo, closed
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([5, 4, 10, 14], fill=(176, 180, 186, 255))
    d.rectangle([5, 4, 10, 7], fill=(196, 200, 206, 255))
    d.line([(5, 7), (10, 7)], fill=(90, 92, 96, 255))
    d.line([(6, 5), (6, 13)], fill=(226, 230, 236, 255))
    d.line([(10, 4), (10, 14)], fill=(120, 124, 130, 255))
    d.line([(5, 14), (10, 14)], fill=(110, 114, 120, 255))
    d.point((11, 6), fill=(140, 144, 150, 255))
    im.save(os.path.join(A, "textures", "item", "lighter.png"))


def models():
    for name in ("cigarettes", "lighter"):
        write(os.path.join(A, "models", "item", name + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{name}"}})
    # in first person the pack is drawn by the hand animation itself
    write(os.path.join(A, "items", "cigarettes.json"), {"model": {
        "type": "minecraft:select", "property": "minecraft:display_context",
        "cases": [{"when": ["firstperson_righthand"], "model": {"type": "minecraft:empty"}}],
        "fallback": {"type": "minecraft:model", "model": f"{NS}:item/cigarettes"}}})
    write(os.path.join(A, "items", "lighter.json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/lighter"}})


def sounds():
    path = os.path.join(A, "sounds.json")
    with open(path, encoding="utf-8") as fh:
        js = json.load(fh)

    def ev(name, files, sub, dist=10):
        js[name] = {"subtitle": f"subtitles.ballisticmissiles.{sub}",
                    "sounds": [{"name": f"{NS}:smoke/{f}", "attenuation_distance": dist} for f in files]}

    ev("smoke.pack_open", ["pack_open1", "pack_open2"], "smoke_pack")
    ev("smoke.pack_pull", ["pack_pull1", "pack_pull2"], "smoke_pack")
    ev("smoke.zippo_light", [f"zippo_light{i}" for i in range(1, 6)], "smoke_zippo", 12)
    ev("smoke.zippo_close", ["zippo_close1", "zippo_close2"], "smoke_zippo_close", 12)
    ev("smoke.drag", ["drag1", "drag2", "drag3"], "smoke_drag", 6)
    ev("smoke.exhale", ["exhale1", "exhale2", "exhale3"], "smoke_exhale", 8)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(js, fh, indent="\t", ensure_ascii=False)
        fh.write("\n")


def recipes():
    write(os.path.join(D, "recipe", "cigarettes.json"), {
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": ["minecraft:paper", "minecraft:paper", "minecraft:wheat", "minecraft:brown_dye"],
        "result": {"id": f"{NS}:cigarettes", "count": 1}})
    write(os.path.join(D, "recipe", "lighter.json"), {
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": ["minecraft:iron_ingot", "minecraft:flint", "minecraft:string"],
        "result": {"id": f"{NS}:lighter", "count": 1}})


if __name__ == "__main__":
    atlas()
    icons()
    models()
    sounds()
    recipes()
