"""12.1: the blocks the new sites need - landmine, keycard reader, lift panel - and the keycard.

python3 tools/gen_site_assets.py
"""
import json
import os

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
A = os.path.join(ROOT, "assets", "ballisticmissiles")
D = os.path.join(ROOT, "data", "ballisticmissiles")
NS = "ballisticmissiles"
rng = np.random.default_rng(1211)


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent="\t", ensure_ascii=False)
        f.write("\n")


def save(kind, name, a):
    path = os.path.join(A, "textures", kind, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA").save(path)


def canvas(rgb, noise=5.0):
    a = np.zeros((16, 16, 4), np.float32)
    a[..., :3] = rgb
    a[..., :3] += rng.normal(0, noise, (16, 16, 1))
    a[..., 3] = 255
    return a


def textures():
    # the mine: dull olive casing, scuffed and dusty, a ring of ribs and the pressure fuse in the middle
    mine = canvas((84, 88, 58), 6)
    for y in range(16):
        for x in range(16):
            r = np.hypot(x - 7.5, y - 7.5)
            if 5.2 < r < 6.4:
                mine[y, x, :3] *= 0.7
            if r < 2.2:
                mine[y, x, :3] = (58, 60, 52)
            if r < 1.0:
                mine[y, x, :3] = (120, 116, 100)
            if rng.random() < 0.12:
                mine[y, x, :3] = mine[y, x, :3] * 0.6 + np.array([150, 128, 92]) * 0.4  # dirt on it
    save("block", "landmine", mine)
    # the reader: a dark grey box, a card slot, a red and a green lamp, a small keypad
    reader = canvas((52, 54, 58), 3)
    img = Image.fromarray(reader.astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(img)
    d.rectangle([5, 2, 10, 3], fill=(20, 20, 22, 255))
    d.rectangle([6, 5, 6, 5], fill=(220, 40, 30, 255))
    d.rectangle([9, 5, 9, 5], fill=(60, 210, 70, 255))
    for yy in range(8, 14, 2):
        for xx in range(5, 11, 2):
            d.point((xx, yy), fill=(150, 152, 156, 255))
    save("block", "card_reader", np.array(img).astype(np.float32))
    save("block", "card_reader_side", canvas((46, 48, 52), 3))
    # the lift panel: brushed steel, up and down buttons lit amber, a floor display
    panel = canvas((150, 154, 158), 3)
    for y in range(16):
        panel[y, :, :3] *= 0.95 + 0.06 * rng.random()
    img = Image.fromarray(panel.astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(img)
    d.rectangle([4, 2, 11, 4], fill=(18, 20, 18, 255))
    d.rectangle([6, 3, 9, 3], fill=(230, 120, 30, 255))
    d.ellipse([6, 7, 9, 10], fill=(70, 72, 74, 255))
    d.polygon([(7, 8), (8, 8), (7.5, 7)], fill=(250, 190, 70, 255))
    d.ellipse([6, 11, 9, 14], fill=(70, 72, 74, 255))
    d.point((7, 12), fill=(250, 190, 70, 255))
    d.point((8, 12), fill=(250, 190, 70, 255))
    save("block", "elevator_panel", np.array(img).astype(np.float32))
    save("block", "elevator_panel_side", canvas((120, 124, 128), 3))
    # the keycard: white plastic, a blue stripe, a gold chip
    card = np.zeros((16, 16, 4), np.float32)
    img = Image.fromarray(card.astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([1, 4, 14, 12], radius=1, fill=(232, 234, 236, 255), outline=(150, 152, 156, 255))
    d.rectangle([2, 5, 13, 6], fill=(40, 90, 180, 255))
    d.rectangle([3, 8, 5, 10], fill=(214, 176, 60, 255))
    d.line([(7, 9), (12, 9)], fill=(120, 122, 126, 255))
    d.line([(7, 11), (10, 11)], fill=(120, 122, 126, 255))
    save("item", "keycard", np.array(img).astype(np.float32))


def faces(tex, overrides=None):
    return {f: {"texture": (overrides or {}).get(f, tex)} for f in ("north", "south", "east", "west", "up", "down")}


def models():
    write(os.path.join(A, "models", "block", "landmine.json"), {
        "parent": "minecraft:block/block",
        "textures": {"particle": f"{NS}:block/landmine", "mine": f"{NS}:block/landmine"},
        "elements": [
            {"from": [4, 0, 4], "to": [12, 1, 12], "faces": faces("#mine")},
            {"from": [7, 1, 7], "to": [9, 1.6, 9], "faces": faces("#mine")},
        ],
    })
    write(os.path.join(A, "blockstates", "landmine.json"), {"variants": {"": {"model": f"{NS}:block/landmine"}}})
    write(os.path.join(A, "items", "landmine.json"), {"model": {"type": "minecraft:model", "model": f"{NS}:block/landmine"}})
    for name, elements in (
        ("card_reader", [{"from": [5, 3, 5], "to": [11, 13, 11], "faces": faces("#side", {"north": "#front", "south": "#front"})}]),
        ("elevator_panel", [
            {"from": [6, 0, 6], "to": [10, 9, 10], "faces": faces("#side")},
            {"from": [4, 9, 5], "to": [12, 16, 11], "faces": faces("#side", {"north": "#front", "south": "#front"})},
        ]),
    ):
        write(os.path.join(A, "models", "block", name + ".json"), {
            "parent": "minecraft:block/block",
            "textures": {"particle": f"{NS}:block/{name}_side" if name != "card_reader" else f"{NS}:block/card_reader_side",
                         "front": f"{NS}:block/{name}", "side": f"{NS}:block/{name}_side"},
            "elements": elements,
        })
        rot = {"north": 0, "east": 90, "south": 180, "west": 270}
        variants = {}
        for f, y in rot.items():
            v = {"model": f"{NS}:block/{name}"}
            if y:
                v["y"] = y
            variants[f"facing={f}"] = v
        write(os.path.join(A, "blockstates", name + ".json"), {"variants": variants})
        write(os.path.join(A, "items", name + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:block/{name}"}})
    write(os.path.join(A, "models", "item", "keycard.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/keycard"}})
    write(os.path.join(A, "items", "keycard.json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/keycard"}})


def data():
    for name in ("landmine", "card_reader", "elevator_panel"):
        pool = {"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{NS}:{name}"}]}
        if name != "landmine":
            pool["conditions"] = [{"condition": "minecraft:survives_explosion"}]
        write(os.path.join(D, "loot_table", "blocks", name + ".json"),
              {"type": "minecraft:block", "pools": [pool] if name != "landmine" else [], "random_sequence": f"{NS}:blocks/{name}"})
    path = os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable", "pickaxe.json")
    with open(path, encoding="utf-8") as fh:
        tag = json.load(fh)
    for name in ("landmine", "card_reader", "elevator_panel"):
        if f"{NS}:{name}" not in tag["values"]:
            tag["values"].append(f"{NS}:{name}")
    write(path, tag)


if __name__ == "__main__":
    textures()
    models()
    data()
