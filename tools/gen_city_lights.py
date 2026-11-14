"""The city's lights: textures, block states, models, items, loot tables and names for the room light, the neon
sign, the street lamp, the traffic light and the car headlights (CityLights).

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


def emergency():
    """The room light as an emergency light in a power cut: the panel glowing a dim red."""
    im = Image.open(os.path.join(TEX, "room_light_off.png")).convert("RGB")
    px = im.load()
    for y in range(16):
        for x in range(16):
            r, g, b = px[x, y]
            if not (x in (0, 15) or y in (0, 15)):
                px[x, y] = (min(255, int(r * 0.3 + 95)), int(g * 0.16 + 6), int(b * 0.14 + 6))
    save(im, "room_light_emergency")


def street_lamp():
    """The lamp head: a dark grey metal housing, the diffuser underneath warm white when lit, grey when not."""
    im = Image.new("RGB", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            v = 62 + (6 if (x * 3 + y * 5) % 7 == 0 else 0) - (10 if y in (0, 15) or x in (0, 15) else 0)
            px[x, y] = (v, v + 2, v + 4)
    save(im, "street_lamp_housing")
    for lit in (True, False):
        im = Image.new("RGB", (16, 16))
        px = im.load()
        for y in range(16):
            for x in range(16):
                d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5 / 10.6
                px[x, y] = (255, int(232 - 30 * d), int(170 - 50 * d)) if lit else (int(150 - 20 * d), int(150 - 20 * d), int(146 - 20 * d))
        save(im, "street_lamp_glass" + ("" if lit else "_off"))


def traffic_light():
    """A signal face: a black housing with three lamps (red, amber, green, top to bottom) under their visors, one lit."""
    lamps = {"red": (255, 40, 30), "yellow": (255, 190, 20), "green": (40, 255, 110)}
    order = ["red", "yellow", "green"]
    for state in ("red", "yellow", "green", "dark"):
        im = Image.new("RGB", (16, 16), (20, 20, 22))
        px = im.load()
        for y in range(16):
            for x in range(16):
                if x in (0, 15) or y in (0, 15):
                    px[x, y] = (34, 34, 36)
        for i, name in enumerate(order):
            cy = 3 + i * 5
            col = lamps[name]
            on = state == name
            for y in range(cy - 2, cy + 3):
                for x in range(5, 11):
                    d = ((x - 7.5) ** 2 + ((y - cy) * 1.15) ** 2) ** 0.5
                    if d <= 2.6:
                        if on:
                            core = max(0.0, 1.0 - d / 2.6)
                            px[x, y] = tuple(min(255, int(c * 0.75 + 255 * 0.45 * core)) for c in col)
                        else:
                            px[x, y] = tuple(int(c * 0.16) + 14 for c in col)
            # the visor's shadow above each lamp
            for x in range(5, 11):
                px[x, cy - 3] = (8, 8, 9)
        save(im, "traffic_light_" + state)
    im = Image.new("RGB", (16, 16), (28, 28, 30))
    save(im, "traffic_light_top")


def headlight():
    """A car's front: a dark bumper with a grille between two headlamps; the lamps white-hot when lit."""
    for lit in (True, False):
        im = Image.new("RGB", (16, 16), (24, 24, 26))
        px = im.load()
        for y in range(16):
            for x in range(16):
                if 6 <= y <= 9 and (2 <= x <= 5 or 10 <= x <= 13):
                    edge = x in (2, 5, 10, 13) or y in (6, 9)
                    px[x, y] = ((255, 250, 228) if not edge else (230, 226, 210)) if lit else ((150, 156, 162) if not edge else (110, 114, 118))
                elif 11 <= y <= 13 and 4 <= x <= 11:
                    px[x, y] = (12, 12, 13) if (x + y) % 2 else (40, 40, 42)  # the grille
                elif y == 14:
                    px[x, y] = (60, 60, 64)
        save(im, "car_headlight" + ("" if lit else "_off"))
    im = Image.new("RGB", (16, 16), (24, 24, 26))
    save(im, "car_headlight_side")


def models():
    bs = os.path.join(ASSETS, "blockstates")
    md = os.path.join(ASSETS, "models", "block")
    B = "ballisticmissiles:block/"
    for suffix in ("", "_off", "_emergency"):
        write(os.path.join(md, "room_light%s.json" % suffix), {"parent": "minecraft:block/cube_all", "textures": {"all": B + "room_light" + suffix}})
    write(os.path.join(bs, "room_light.json"), {"variants": {
        "emergency=false,lit=true": {"model": B + "room_light"}, "emergency=false,lit=false": {"model": B + "room_light_off"},
        "emergency=true,lit=true": {"model": B + "room_light"}, "emergency=true,lit=false": {"model": B + "room_light_emergency"}}})
    variants = {}
    for i in range(len(NEON)):
        for lit in (True, False):
            name = "neon_sign_%d%s" % (i, "" if lit else "_off")
            write(os.path.join(md, name + ".json"), {"parent": "minecraft:block/cube_all", "textures": {"all": B + name}})
            variants["color=%d,lit=%s" % (i, "true" if lit else "false")] = {"model": B + name}
    write(os.path.join(bs, "neon_sign.json"), {"variants": variants})
    # the street lamp: a neck up from the post, the housing over it, the diffuser underneath
    for lit in (True, False):
        glass = B + "street_lamp_glass" + ("" if lit else "_off")
        housing = B + "street_lamp_housing"
        cube = lambda tex: {f: {"texture": tex} for f in ("north", "south", "east", "west", "up", "down")}
        write(os.path.join(md, "street_lamp%s.json" % ("" if lit else "_off")), {
            "parent": "minecraft:block/block",
            "textures": {"particle": housing, "housing": housing, "glass": glass},
            "elements": [
                {"from": [7, 0, 7], "to": [9, 9, 9], "faces": cube("#housing")},
                {"from": [3, 10, 3], "to": [13, 14, 13], "faces": cube("#housing")},
                {"from": [4, 9, 4], "to": [12, 10, 12], "faces": cube("#glass")},
            ],
        })
    write(os.path.join(bs, "street_lamp.json"), {"variants": {"lit=true": {"model": B + "street_lamp"}, "lit=false": {"model": B + "street_lamp_off"}}})
    # the traffic light: east and west faces for the traffic along x, north and south for z
    x_state = ["green", "yellow", "red", "red", "dark"]
    z_state = ["red", "red", "green", "yellow", "dark"]
    tl = {}
    for p in range(5):
        name = "traffic_light_%d" % p
        write(os.path.join(md, name + ".json"), {
            "parent": "minecraft:block/cube",
            "textures": {"particle": B + "traffic_light_top", "up": B + "traffic_light_top", "down": B + "traffic_light_top",
                         "east": B + "traffic_light_" + x_state[p], "west": B + "traffic_light_" + x_state[p],
                         "north": B + "traffic_light_" + z_state[p], "south": B + "traffic_light_" + z_state[p]},
        })
        tl["phase=%d" % p] = {"model": B + name}
    write(os.path.join(bs, "traffic_light.json"), {"variants": tl})
    # the headlights: an orientable front
    hl = {}
    for lit in (True, False):
        name = "car_headlight" + ("" if lit else "_off")
        write(os.path.join(md, name + ".json"), {"parent": "minecraft:block/orientable",
                                                 "textures": {"front": B + name, "side": B + "car_headlight_side", "top": B + "car_headlight_side"}})
        for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            for running in ("true", "false"):
                v = {"model": B + name}
                if rot:
                    v["y"] = rot
                hl["facing=%s,lit=%s,running=%s" % (facing, "true" if lit else "false", running)] = v
    write(os.path.join(bs, "car_headlight.json"), {"variants": hl})
    for name, model in (("room_light", "room_light"), ("neon_sign", "neon_sign_0"), ("street_lamp", "street_lamp"), ("traffic_light", "traffic_light_0"),
                        ("car_headlight", "car_headlight")):
        write(os.path.join(ASSETS, "items", name + ".json"), {"model": {"type": "minecraft:model", "model": B + model}})
        write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "ballisticmissiles:" + name}], "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        })


def lang():
    names = {"en_us": {"room_light": "Ceiling Light", "neon_sign": "Neon Sign", "street_lamp": "Street Lamp", "traffic_light": "Traffic Light",
                       "car_headlight": "Car Headlights"},
             "de_de": {"room_light": "Deckenleuchte", "neon_sign": "Leuchtreklame", "street_lamp": "Straßenlaterne", "traffic_light": "Ampel",
                       "car_headlight": "Autoscheinwerfer"}}
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
    emergency()
    neon()
    street_lamp()
    traffic_light()
    headlight()
    models()
    lang()
    print("city lights written")
