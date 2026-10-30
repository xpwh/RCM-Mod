"""The city's lights: textures, block states, models, items, loot tables and names for the room light and the
neon sign (CityLights).

    python3 tools/gen_city_lights.py
"""
import json
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "ballisticmissiles")
DATA = os.path.join(ROOT, "data", "ballisticmissiles")
TEX = os.path.join(ASSETS, "textures", "block")

# pink, cyan, red, green, yellow, purple
NEON = [(255, 60, 190), (40, 225, 255), (255, 45, 45), (70, 255, 110), (255, 215, 40), (175, 80, 255)]


def save(img, name):
    img.save(os.path.join(TEX, name + ".png"))


def room_light():
    """A ceiling panel: a frame round a diffuser in four squares; bright and warm when on, pale grey when off."""
    for lit in (True, False):
        im = Image.new("RGB", (16, 16))
        px = im.load()
        for y in range(16):
            for x in range(16):
                frame = x in (0, 15) or y in (0, 15)
                bar = x in (7, 8) or y in (7, 8)
                if frame:
                    c = (178, 178, 172)
                elif bar:
                    c = (212, 208, 196) if lit else (160, 160, 156)
                else:
                    # the diffuser: brightest in the middle of each square
                    cx, cy = (3.5 if x < 8 else 11.5), (3.5 if y < 8 else 11.5)
                    d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 / 5.0
                    if lit:
                        v = 1.0 - 0.12 * d
                        c = (int(255 * v), int(246 * v), int(222 * v))
                    else:
                        v = 0.78 - 0.06 * d
                        c = (int(200 * v), int(204 * v), int(206 * v))
                px[x, y] = c
        save(im, "room_light" + ("" if lit else "_off"))


def neon():
    """A sign board: dark backing with a tube border and tube "lettering"; the tubes white-hot with a coloured halo when lit."""
    rng = random.Random(7)
    for i, col in enumerate(NEON):
        # the tubes: a border and three letter-like strokes, the same lit or not
        tubes = set()
        for t in range(1, 15):
            tubes |= {(t, 1), (t, 14), (1, t), (14, t)}
        x = 4
        while x < 12:
            shape = rng.randrange(4)
            if shape == 0:  # I
                tubes |= {(x, y) for y in range(4, 12)}
                x += 3
            elif shape == 1:  # L
                tubes |= {(x, y) for y in range(4, 12)} | {(x + 1, 11), (x + 2, 11)}
                x += 4
            elif shape == 2:  # O
                tubes |= {(x, y) for y in range(5, 11)} | {(x + 2, y) for y in range(5, 11)} | {(x + 1, 4), (x + 1, 11)}
                x += 4
            else:  # N
                tubes |= {(x, y) for y in range(4, 12)} | {(x + 2, y) for y in range(4, 12)} | {(x + 1, 6), (x + 1, 7), (x + 1, 8)}
                x += 4
        for lit in (True, False):
            im = Image.new("RGB", (16, 16))
            px = im.load()
            for y in range(16):
                for x2 in range(16):
                    base = (18, 16, 20) if (x2 + y) % 5 else (24, 22, 26)
                    if (x2, y) in tubes:
                        c = tuple(min(255, int(v * 0.45 + 255 * 0.55)) for v in col) if lit else tuple(int(v * 0.3) + 18 for v in col)
                    else:
                        near = any((x2 + dx, y + dy) in tubes for dx in (-1, 0, 1) for dy in (-1, 0, 1))
                        c = tuple(int(b * 0.7 + v * 0.22) for b, v in zip(base, col)) if lit and near else base
                    px[x2, y] = c
            save(im, "neon_sign_%d%s" % (i, "" if lit else "_off"))


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent="\t")
        f.write("\n")


def models():
    bs = os.path.join(ASSETS, "blockstates")
    md = os.path.join(ASSETS, "models", "block")
    for suffix in ("", "_off"):
        write(os.path.join(md, "room_light%s.json" % suffix), {"parent": "minecraft:block/cube_all", "textures": {"all": "ballisticmissiles:block/room_light%s" % suffix}})
    write(os.path.join(bs, "room_light.json"), {"variants": {"lit=true": {"model": "ballisticmissiles:block/room_light"},
                                                             "lit=false": {"model": "ballisticmissiles:block/room_light_off"}}})
    variants = {}
    for i in range(len(NEON)):
        for lit in (True, False):
            name = "neon_sign_%d%s" % (i, "" if lit else "_off")
            write(os.path.join(md, name + ".json"), {"parent": "minecraft:block/cube_all", "textures": {"all": "ballisticmissiles:block/" + name}})
            variants["color=%d,lit=%s" % (i, "true" if lit else "false")] = {"model": "ballisticmissiles:block/" + name}
    write(os.path.join(bs, "neon_sign.json"), {"variants": variants})
    for name, model in (("room_light", "room_light"), ("neon_sign", "neon_sign_0")):
        write(os.path.join(ASSETS, "items", name + ".json"), {"model": {"type": "minecraft:model", "model": "ballisticmissiles:block/" + model}})
        write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "ballisticmissiles:" + name}], "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        })


def lang():
    names = {"en_us": {"room_light": "Ceiling Light", "neon_sign": "Neon Sign"}, "de_de": {"room_light": "Deckenleuchte", "neon_sign": "Leuchtreklame"}}
    for code, entries in names.items():
        path = os.path.join(ASSETS, "lang", code + ".json")
        data = json.load(open(path, encoding="utf-8"))
        for k, v in entries.items():
            data["block.ballisticmissiles." + k] = v
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent="\t", ensure_ascii=False)
            f.write("\n")


if __name__ == "__main__":
    room_light()
    neon()
    models()
    lang()
    print("city lights written")
