"""12.0: the weapons workbench (model, textures, blockstate, loot) and the suppressor's icon.

python3 tools/gen_workbench_assets.py
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
rng = np.random.default_rng(1201)


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent="\t", ensure_ascii=False)
        f.write("\n")


def save(kind, name, a):
    path = os.path.join(A, "textures", kind, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA").save(path)


def canvas(rgb, noise=6.0, size=16):
    a = np.zeros((size, size, 4), np.float32)
    a[..., :3] = rgb
    a[..., :3] += rng.normal(0, noise, (size, size, 1))
    a[..., 3] = 255
    return a


def textures():
    # bench steel: dark, brushed along x
    steel = canvas((70, 72, 74), 3)
    for y in range(16):
        steel[y, :, :3] *= 0.92 + 0.1 * rng.random()
    save("block", "workbench_steel", steel)
    # the top plate: scratched, an oil stain, a few bright nicks
    top = canvas((92, 94, 96), 4)
    for _ in range(9):
        x0, y0 = rng.integers(0, 16, 2)
        for k in range(rng.integers(3, 8)):
            x, y = (x0 + k) % 16, (y0 + k // 2) % 16
            top[y, x, :3] = top[y, x, :3] * 0.6 + 160 * 0.4
    for y in range(16):
        for x in range(16):
            if math.hypot(x - 11, y - 5) < 2.6:
                top[y, x, :3] *= 0.62
    top[0, :, :3] *= 0.8
    top[15, :, :3] *= 0.8
    save("block", "workbench_top", top)
    # the tool board: brown hardboard with a grid of holes and tools hung on it
    peg = canvas((120, 92, 62), 4)
    for y in range(1, 16, 3):
        for x in range(1, 16, 3):
            peg[y, x, :3] = (52, 40, 30)
    img = Image.fromarray(peg.astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(img)
    d.line([(2, 2), (2, 9)], fill=(150, 152, 156, 255))   # screwdriver shaft
    d.rectangle([1, 9, 3, 12], fill=(200, 40, 30, 255))   # its handle
    d.line([(6, 2), (6, 11)], fill=(170, 172, 176, 255))  # wrench
    d.rectangle([5, 1, 7, 3], fill=(170, 172, 176, 255))
    d.rectangle([5, 11, 7, 13], fill=(170, 172, 176, 255))
    d.rectangle([10, 2, 14, 4], fill=(110, 112, 116, 255))  # hammer head
    d.line([(12, 4), (12, 13)], fill=(150, 110, 70, 255))   # its handle
    d.line([(9, 7), (9, 13)], fill=(120, 124, 128, 255))    # pliers
    d.line([(10, 7), (10, 13)], fill=(120, 124, 128, 255))
    peg = np.array(img).astype(np.float32)
    peg[0, :, :3] *= 0.7
    peg[15, :, :3] *= 0.7
    save("block", "workbench_pegboard", peg)
    wood = canvas((132, 98, 62), 5)
    for y in range(16):
        wood[y, :, :3] *= 0.9 + 0.12 * math.sin(y * 1.7)
    save("block", "workbench_wood", wood)
    red = canvas((168, 38, 30), 4)
    red[7:9, :, :3] *= 0.6
    save("block", "workbench_toolbox", red)
    olive = canvas((80, 88, 50), 4)
    olive[2:4, 3:13, :3] = (196, 170, 70)
    save("block", "workbench_ammo_can", olive)

    # the suppressor's icon: a black can on the diagonal with a steel collar
    icon = np.zeros((16, 16, 4), np.float32)
    for i in range(16):
        for j in range(16):
            # distance from the diagonal from (3,12) to (13,2)
            t = ((i - 3) + (12 - j)) / 20.0
            dperp = abs((i - 3) - (12 - j)) / math.sqrt(2)
            if 0.0 <= t <= 1.0 and dperp < 2.2:
                shade = 1.0 - 0.35 * dperp / 2.2
                c = (44, 46, 48) if t > 0.16 else (150, 152, 156)
                icon[j, i] = (*[v * shade for v in c], 255)
                if dperp < 0.7:
                    icon[j, i, :3] += 28
    icon[2, 13] = (20, 20, 22, 255)
    save("item", "suppressor", icon)


def el(fr, to, tex, faces=None):
    e = {"from": fr, "to": to, "faces": {}}
    for f in ("north", "south", "east", "west", "up", "down"):
        e["faces"][f] = {"texture": (faces or {}).get(f, tex)}
    return e


def models():
    elements = [
        el([0, 12, 0], [16, 14, 16], "#steel", {"up": "#top"}),
        el([1, 0, 1], [3, 12, 3], "#steel"), el([13, 0, 1], [15, 12, 3], "#steel"),
        el([1, 0, 13], [3, 12, 15], "#steel"), el([13, 0, 13], [15, 12, 15], "#steel"),
        el([1, 3, 1], [15, 4, 15], "#wood"),
        # tool board on the back edge, with a shelf
        el([0, 14, 14.5], [16, 28, 16], "#peg", {"north": "#peg", "south": "#steel", "east": "#steel", "west": "#steel", "up": "#steel"}),
        el([1, 20, 12.5], [15, 20.6, 14.5], "#wood"),
        # vice on the front right
        el([10, 14, 1], [14, 15.5, 5], "#steel"),
        el([10, 15.5, 1], [14, 18, 2], "#steel"), el([10, 15.5, 4], [14, 18, 5], "#steel"),
        el([11.5, 16.2, -1], [12.5, 17.2, 1], "#top"),
        el([10.5, 16.4, -1.4], [13.5, 17, -0.8], "#top"),
        # toolbox on the shelf, an ammo can and a box of parts on the top
        el([3, 4, 5], [10, 7.5, 10], "#red"),
        el([5.5, 7.5, 7], [7.5, 8.2, 8], "#steel"),
        el([1.5, 14, 4], [6.5, 17, 7], "#olive"),
        el([2, 21, 13], [6, 23, 14.5], "#olive"),
    ]
    write(os.path.join(A, "models", "block", "weapons_workbench.json"), {
        "parent": "minecraft:block/block",
        "textures": {"particle": f"{NS}:block/workbench_steel", "steel": f"{NS}:block/workbench_steel", "top": f"{NS}:block/workbench_top",
                     "peg": f"{NS}:block/workbench_pegboard", "wood": f"{NS}:block/workbench_wood", "red": f"{NS}:block/workbench_toolbox",
                     "olive": f"{NS}:block/workbench_ammo_can"},
        "elements": elements,
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -1.5, 0], "scale": [0.5, 0.5, 0.5]}},
    })
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for f, y in rot.items():
        v = {"model": f"{NS}:block/weapons_workbench"}
        if y:
            v["y"] = y
        variants[f"facing={f}"] = v
    write(os.path.join(A, "blockstates", "weapons_workbench.json"), {"variants": variants})
    write(os.path.join(A, "items", "weapons_workbench.json"), {"model": {"type": "minecraft:model", "model": f"{NS}:block/weapons_workbench"}})
    write(os.path.join(A, "models", "item", "suppressor.json"), {"parent": "minecraft:item/handheld", "textures": {"layer0": f"{NS}:item/suppressor"}})
    write(os.path.join(A, "items", "suppressor.json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/suppressor"}})


def data():
    write(os.path.join(D, "loot_table", "blocks", "weapons_workbench.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{NS}:weapons_workbench"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        "random_sequence": f"{NS}:blocks/weapons_workbench"})
    # the bench itself, on a crafting table: iron, a smithing table, a crafting table
    write(os.path.join(D, "recipe", "weapons_workbench.json"), {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["III", "SCS", "I I"],
        "key": {"I": "minecraft:iron_ingot", "S": "minecraft:smithing_table", "C": "minecraft:crafting_table"},
        "result": {"id": f"{NS}:weapons_workbench", "count": 1}})
    path = os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable", "pickaxe.json")
    with open(path, encoding="utf-8") as fh:
        tag = json.load(fh)
    if f"{NS}:weapons_workbench" not in tag["values"]:
        tag["values"].append(f"{NS}:weapons_workbench")
    write(path, tag)


if __name__ == "__main__":
    textures()
    models()
    data()
