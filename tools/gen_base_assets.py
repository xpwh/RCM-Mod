"""11.0 military bases: block textures, block/item models, blockstates, loot tables, tags and the worldgen data.

Run from the repository root: python3 tools/gen_base_assets.py
"""
import json
import math
import os

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
A = os.path.join(ROOT, "assets", "ballisticmissiles")
D = os.path.join(ROOT, "data", "ballisticmissiles")
NS = "ballisticmissiles"
rng = np.random.default_rng(1100)


def save_png(name, arr):
    arr = np.clip(arr, 0, 255).astype(np.uint8)
    path = os.path.join(A, "textures", "block", name + ".png")
    Image.fromarray(arr, "RGBA").save(path)


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent="\t", ensure_ascii=False)
        f.write("\n")


def canvas(rgb, noise=8.0, size=16):
    a = np.zeros((size, size, 4), np.float32)
    a[..., :3] = rgb
    a[..., :3] += rng.normal(0, noise, (size, size, 1))
    a[..., 3] = 255
    return a


def shade(a, x0, y0, x1, y1, k):
    a[y0:y1, x0:x1, :3] *= k


# ---------------------------------------------------------------- textures

def sandbags():
    a = canvas((150, 132, 96), 5)
    for row in range(4):
        y0 = row * 4
        off = 0 if row % 2 == 0 else 4
        for col in range(-1, 3):
            x0 = col * 8 + off
            for y in range(4):
                for x in range(8):
                    px = x0 + x
                    if not 0 <= px < 16:
                        continue
                    # each bag: rounded, lit from above, seam at its edges
                    edge = x in (0, 7) or y == 3
                    k = 1.08 - 0.1 * y
                    if (x in (0, 7)) and y in (0, 3):
                        a[y0 + y, px, :3] = (70, 60, 44)
                        continue
                    if edge:
                        k *= 0.68
                    a[y0 + y, px, :3] *= k
            # tie at the end of the bag
            if 0 <= x0 + 6 < 16:
                a[y0 + 1, x0 + 6, :3] = (96, 84, 60)
    # dirt and stains
    for _ in range(10):
        x, y = rng.integers(0, 16, 2)
        a[y, x, :3] *= 0.82
    save_png("sandbags", a)
    t = canvas((140, 124, 90), 5)
    for y in range(16):
        for x in range(16):
            if y % 8 in (0, 7) or (x + (4 if (y // 8) % 2 else 0)) % 16 in (0, 15):
                t[y, x, :3] *= 0.72
    save_png("sandbags_top", t)


def hesco():
    a = canvas((176, 160, 122), 6)
    # geotextile liner with a wire mesh over it
    for y in range(16):
        for x in range(16):
            if x % 4 == 0 or y % 4 == 0:
                a[y, x, :3] = (124, 126, 124) if (x + y) % 2 == 0 else (150, 152, 150)
    a[15, :, :3] *= 0.75
    for _ in range(12):
        x, y = rng.integers(0, 16, 2)
        a[y, x, :3] *= 0.85
    save_png("hesco_barrier", a)
    t = canvas((112, 96, 72), 12)
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                t[y, x, :3] = (140, 142, 140)
            elif rng.random() < 0.18:
                t[y, x, :3] = (130, 128, 120)
    save_png("hesco_barrier_top", t)


def concrete(name, base=(168, 166, 160)):
    a = canvas(base, 6)
    for _ in range(14):
        x, y = rng.integers(0, 16, 2)
        a[y, x, :3] *= 0.9
    # a stain running down from the top
    for x in rng.integers(0, 16, 3):
        for y in range(rng.integers(3, 10)):
            a[y, x, :3] *= 0.92
    a[15, :, :3] *= 0.85
    save_png(name, a)


def chainlink():
    a = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            # diamonds: two diagonal families of wire
            if (x + y) % 8 == 0 or (x - y) % 8 == 0:
                g = 168 + 26 * ((x + 2 * y) % 3 == 0)
                a[y, x] = (g, g + 2, g + 4, 255)
    save_png("chainlink_fence", a)
    e = np.zeros((16, 16, 4), np.float32)
    e[:, 7:9] = (150, 152, 156, 255)
    e[:, 7, :3] = 176
    save_png("chainlink_fence_edge", e)


def barbed_wire():
    a = np.zeros((16, 16, 4), np.float32)
    # coils of razor wire seen from the side: overlapping ellipses with barbs
    for cx in (4, 11):
        for t in np.linspace(0, 2 * math.pi, 64):
            x = int(round(cx + 4.2 * math.cos(t)))
            y = int(round(9 + 5.5 * math.sin(t)))
            if 0 <= x < 16 and 0 <= y < 16:
                g = 150 + 50 * math.sin(t + 1.0)
                a[y, x] = (g, g + 3, g + 6, 255)
    for x, y in ((2, 4), (8, 6), (13, 4), (5, 14), (10, 13), (1, 9), (15, 10), (7, 3)):
        a[y, x] = (210, 212, 216, 255)
        if x + 1 < 16:
            a[y, x + 1] = (120, 122, 126, 255)
    save_png("barbed_wire", a)


def camo_net():
    a = np.zeros((16, 16, 4), np.float32)
    cols = [(64, 78, 42), (88, 96, 52), (104, 86, 56), (52, 60, 34), (120, 112, 74)]
    for y in range(16):
        for x in range(16):
            if (x + y) % 3 == 0 or (x - y) % 3 == 0:
                a[y, x] = (60, 62, 48, 255)  # the net's cord
            if rng.random() < 0.55:
                c = cols[rng.integers(0, len(cols))]
                a[y, x] = (*c, 255)
    save_png("camo_net", a)


def crate():
    side = canvas((84, 92, 52), 5)
    for y in range(16):
        if y % 5 == 0:
            side[y, :, :3] *= 0.7
    side[:, 0:2, :3] *= 0.78
    side[:, 14:16, :3] *= 0.78
    side[0:2, :, :3] *= 0.78
    side[14:16, :, :3] *= 0.78
    # yellow stencil marks
    for x in range(4, 12):
        if x % 2 == 0:
            side[7, x, :3] = (196, 170, 70)
            side[8, x, :3] = (196, 170, 70)
    side[10, 4:7, :3] = (196, 170, 70)
    save_png("military_crate", side)
    top = canvas((90, 98, 56), 5)
    for x in range(16):
        if x % 4 == 0:
            top[:, x, :3] *= 0.72
    top[0:2, :, :3] *= 0.8
    top[14:16, :, :3] *= 0.8
    top[6:10, 7:9, :3] = (60, 60, 58)  # handle
    save_png("military_crate_top", top)


def corrugated(name, base):
    a = canvas(base, 3)
    for x in range(16):
        k = 0.82 + 0.26 * (0.5 + 0.5 * math.cos(x / 4 * 2 * math.pi))
        a[:, x, :3] *= k
    for _ in range(4):
        x = rng.integers(0, 16)
        for y in range(rng.integers(6, 16), 16):
            a[y, x, :3] = a[y, x, :3] * 0.7 + np.array((120, 70, 30)) * 0.3  # rust running down
    a[0, :, :3] *= 0.8
    save_png(name, a)


def steel_grate():
    a = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            if x % 4 in (0, 1) or y % 4 in (0, 1):
                g = 92 + rng.normal(0, 6) + (14 if x % 4 == 0 or y % 4 == 0 else 0)
                a[y, x] = (g, g + 2, g + 4, 255)
    save_png("steel_grate", a)


def fuel_drum():
    a = canvas((120, 36, 30), 4)
    for x in range(16):
        a[:, x, :3] *= 0.78 + 0.34 * math.sin(math.pi * (x + 0.5) / 16)
    for y in (3, 4, 11, 12):
        a[y, :, :3] *= 0.7
    a[3, :, :3] += 30
    a[11, :, :3] += 30
    # white stencil band
    a[7, 3:13, :3] = (210, 206, 196)
    a[7, 5, :3] = (120, 36, 30)
    a[7, 9, :3] = (120, 36, 30)
    for _ in range(8):
        x, y = rng.integers(0, 16, 2)
        a[y, x, :3] = a[y, x, :3] * 0.6 + np.array((90, 60, 40)) * 0.4
    save_png("fuel_drum", a)
    t = canvas((128, 40, 34), 4)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if 5.5 < r < 6.6:
                t[y, x, :3] *= 0.7
    t[3:5, 10:12, :3] = (70, 70, 72)  # bung
    t[11:12, 4:6, :3] = (70, 70, 72)
    save_png("fuel_drum_top", t)


def field_generator():
    side = canvas((78, 88, 50), 4)
    for y in range(3, 12, 2):
        side[y, 2:14, :3] *= 0.55  # vents
    side[13:16, :, :3] *= 0.7
    save_png("field_generator", side)
    front = canvas((78, 88, 50), 4)
    front[2:10, 2:14, :3] = (44, 46, 44)
    front[3:5, 3:6, :3] = (210, 200, 180)  # gauges
    front[3:5, 7:10, :3] = (210, 200, 180)
    front[6, 3, :3] = (60, 220, 70)
    front[6, 5, :3] = (220, 60, 40)
    front[7:9, 11:13, :3] = (200, 170, 40)
    front[12:14, 3:13, :3] = (196, 170, 70)
    save_png("field_generator_front", front)
    top = canvas((84, 94, 54), 4)
    top[0:2, :, :3] *= 0.8
    top[6:10, 10:14, :3] = (50, 50, 50)
    save_png("field_generator_top", top)


def lamp_parts():
    housing = canvas((54, 56, 58), 4)
    housing[0, :, :3] *= 0.8
    save_png("floodlight_housing", housing)
    lens = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5) / 8
            g = 255 - 40 * r
            lens[y, x] = (g, g * 0.97, g * 0.86, 255)
            if (x + y) % 4 == 0:
                lens[y, x, :3] *= 0.93
    save_png("floodlight_lens", lens)
    off = lens.copy()
    off[..., :3] = off[..., :3] * 0.32 + 30
    save_png("floodlight_lens_off", off)
    dome = np.zeros((16, 16, 4), np.float32)
    for y in range(16):
        for x in range(16):
            k = 0.65 + 0.35 * math.sin(math.pi * (x + 0.5) / 16)
            dome[y, x] = (230 * k, 40 * k, 30 * k, 255)
            if y % 4 == 0:
                dome[y, x, :3] *= 0.85
    save_png("alarm_light_on", dome)
    d2 = dome.copy()
    d2[..., :3] *= 0.45
    save_png("alarm_light", d2)


def flag_plate():
    a = canvas((96, 98, 102), 5)
    a[0, :, :3] *= 1.1
    save_png("base_flag", a)


# ---------------------------------------------------------------- models

def tex(name):
    return f"{NS}:block/{name}"


def model(name, obj):
    write(os.path.join(A, "models", "block", name + ".json"), obj)


def item(name, model_name=None, flat=False):
    if flat:
        write(os.path.join(A, "models", "item", name + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": tex(name)}})
        m = f"{NS}:item/{name}"
    else:
        m = f"{NS}:block/{model_name or name}"
    write(os.path.join(A, "items", name + ".json"), {"model": {"type": "minecraft:model", "model": m}})


def simple_state(name, model_name=None):
    write(os.path.join(A, "blockstates", name + ".json"), {"variants": {"": {"model": f"{NS}:block/{model_name or name}"}}})


def facing_state(name, models_by_lit=None):
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for f, y in rot.items():
        if models_by_lit:
            for lit, m in models_by_lit.items():
                v = {"model": f"{NS}:block/{m}"}
                if y:
                    v["y"] = y
                variants[f"facing={f},lit={lit}"] = v
        else:
            v = {"model": f"{NS}:block/{name}"}
            if y:
                v["y"] = y
            variants[f"facing={f}"] = v
    write(os.path.join(A, "blockstates", name + ".json"), {"variants": variants})


def faces(t, uv=None, cull=None, emission=None):
    out = {}
    for f in ("north", "south", "east", "west", "up", "down"):
        d = {"texture": t}
        if uv:
            d["uv"] = uv
        if cull and f in cull:
            d["cullface"] = f
        out[f] = d
    return out


def el(fr, to, t, emission=None, shade=True, face_tex=None):
    e = {"from": fr, "to": to, "faces": {}}
    for f in ("north", "south", "east", "west", "up", "down"):
        e["faces"][f] = {"texture": (face_tex or {}).get(f, t)}
    if emission:
        e["light_emission"] = emission
    if not shade:
        e["shade"] = False
    return e


def models():
    # plain cubes
    model("sandbags", {"parent": "minecraft:block/cube_column", "textures": {"side": tex("sandbags"), "end": tex("sandbags_top")}})
    model("hesco_barrier", {"parent": "minecraft:block/cube_column", "textures": {"side": tex("hesco_barrier"), "end": tex("hesco_barrier_top")}})
    model("military_crate", {"parent": "minecraft:block/cube_column", "textures": {"side": tex("military_crate"), "end": tex("military_crate_top")}})
    for n in ("corrugated_olive", "corrugated_grey", "steel_grate"):
        model(n, {"parent": "minecraft:block/cube_all", "textures": {"all": tex(n)}})
    for n in ("sandbags", "hesco_barrier", "military_crate", "corrugated_olive", "corrugated_grey", "steel_grate"):
        simple_state(n)
        item(n)

    # jersey barrier (runs along x)
    c = "#c"
    model("jersey_barrier", {"parent": "minecraft:block/block", "textures": {"particle": tex("jersey_barrier"), "c": tex("jersey_barrier")}, "elements": [
        el([0, 0, 2], [16, 4, 14], c), el([0, 4, 4], [16, 9, 12], c), el([0, 9, 5.5], [16, 15, 10.5], c)]})
    facing_state("jersey_barrier")
    item("jersey_barrier")

    # chain-link: the vanilla bars templates with our textures
    for part in ("post_ends", "post", "cap", "cap_alt", "side", "side_alt"):
        model(f"chainlink_fence_{part}", {"parent": f"minecraft:block/template_bars_{part}",
                                          "textures": {"bars": tex("chainlink_fence"), "edge": tex("chainlink_fence_edge"), "particle": tex("chainlink_fence")}})
    multipart = iron_bars_multipart()
    write(os.path.join(A, "blockstates", "chainlink_fence.json"), {"multipart": multipart})
    item("chainlink_fence", flat=True)

    model("barbed_wire", {"parent": "minecraft:block/cross", "textures": {"cross": tex("barbed_wire")}})
    simple_state("barbed_wire")
    item("barbed_wire", flat=True)

    model("camo_net", {"parent": "minecraft:block/block", "textures": {"particle": tex("camo_net"), "n": tex("camo_net")}, "elements": [
        {"from": [0, 13.5, 0], "to": [16, 13.5, 16], "faces": {"up": {"texture": "#n"}, "down": {"texture": "#n"}}}]})
    simple_state("camo_net")
    item("camo_net", flat=True)

    # floodlight: a lamp head on a yoke, lens to the north
    for lit in (True, False):
        lens = "#lens"
        elements = [
            el([5, 0, 5], [11, 1, 11], "#h"),
            el([7, 1, 7], [9, 5, 9], "#h"),
            el([3, 5, 7], [13, 6, 9], "#h"),
            el([3, 6, 7], [4, 11, 9], "#h"),
            el([12, 6, 7], [13, 11, 9], "#h"),
            el([4, 6, 5], [12, 13, 12], "#h"),
            el([4, 13, 3], [12, 13.5, 6], "#h"),
            el([4.5, 6.5, 4.5], [11.5, 12.5, 5], lens, emission=15 if lit else None, shade=not lit),
        ]
        model("floodlight" + ("" if lit else "_off"), {"parent": "minecraft:block/block", "ambientocclusion": False,
                                                       "textures": {"particle": tex("floodlight_housing"), "h": tex("floodlight_housing"),
                                                                    "lens": tex("floodlight_lens" if lit else "floodlight_lens_off")},
                                                       "elements": elements})
    facing_state("floodlight", {"true": "floodlight", "false": "floodlight_off"})
    item("floodlight")

    for lit in (True, False):
        model("alarm_light" + ("_on" if lit else ""), {"parent": "minecraft:block/block", "ambientocclusion": False,
                                                       "textures": {"particle": tex("floodlight_housing"), "h": tex("floodlight_housing"),
                                                                    "d": tex("alarm_light_on" if lit else "alarm_light")},
                                                       "elements": [
                                                           el([4, 0, 4], [12, 2, 12], "#h"),
                                                           el([5, 2, 5], [11, 8, 11], "#d", emission=12 if lit else None, shade=not lit),
                                                           el([6, 8, 6], [10, 9, 10], "#h"),
                                                       ]})
    facing_state("alarm_light", {"true": "alarm_light_on", "false": "alarm_light"})
    item("alarm_light")

    model("fuel_drum", {"parent": "minecraft:block/block", "textures": {"particle": tex("fuel_drum"), "s": tex("fuel_drum"), "t": tex("fuel_drum_top")},
                        "elements": [
                            el([2, 0, 3], [14, 15, 13], "#s", face_tex={"up": "#t", "down": "#t"}),
                            el([3, 0, 2], [13, 15, 14], "#s", face_tex={"up": "#t", "down": "#t"}),
                        ]})
    simple_state("fuel_drum")
    item("fuel_drum")

    model("field_generator", {"parent": "minecraft:block/block", "textures": {"particle": tex("field_generator"), "s": tex("field_generator"),
                                                                             "f": tex("field_generator_front"), "t": tex("field_generator_top")},
                              "elements": [
                                  el([1, 0, 3], [15, 1, 13], "#s"),
                                  el([1.5, 1, 3.5], [14.5, 11, 12.5], "#s", face_tex={"north": "#f", "up": "#t"}),
                                  el([11, 11, 8], [13, 14, 10], "#s", face_tex={"up": "#t"}),
                                  el([3, 11, 5], [9, 12, 11], "#t"),
                              ]})
    facing_state("field_generator", {"true": "field_generator", "false": "field_generator"})
    item("field_generator")

    model("base_flag", {"parent": "minecraft:block/block", "textures": {"particle": tex("base_flag"), "p": tex("base_flag")},
                        "elements": [el([4, 0, 4], [12, 2, 12], "#p"), el([7, 2, 7], [9, 16, 9], "#p")]})
    write(os.path.join(A, "blockstates", "base_flag.json"), {"variants": {"captured=false": {"model": f"{NS}:block/base_flag"},
                                                                         "captured=true": {"model": f"{NS}:block/base_flag"}}})
    item("base_flag")


def iron_bars_multipart():
    p = f"{NS}:block/chainlink_fence_"
    F = "false"
    T = "true"
    return [
        {"apply": {"model": p + "post_ends"}},
        {"when": {"north": F, "east": F, "south": F, "west": F}, "apply": {"model": p + "post"}},
        {"when": {"north": T, "east": F, "south": F, "west": F}, "apply": {"model": p + "cap"}},
        {"when": {"north": F, "east": T, "south": F, "west": F}, "apply": {"model": p + "cap", "y": 90}},
        {"when": {"north": F, "east": F, "south": T, "west": F}, "apply": {"model": p + "cap_alt"}},
        {"when": {"north": F, "east": F, "south": F, "west": T}, "apply": {"model": p + "cap_alt", "y": 90}},
        {"when": {"north": T}, "apply": {"model": p + "side"}},
        {"when": {"east": T}, "apply": {"model": p + "side", "y": 90}},
        {"when": {"south": T}, "apply": {"model": p + "side_alt"}},
        {"when": {"west": T}, "apply": {"model": p + "side_alt", "y": 90}},
    ]


# ---------------------------------------------------------------- data

BLOCKS = ["sandbags", "hesco_barrier", "jersey_barrier", "chainlink_fence", "barbed_wire", "camo_net", "military_crate", "floodlight",
          "alarm_light", "corrugated_olive", "corrugated_grey", "steel_grate", "fuel_drum", "field_generator", "base_flag"]


def loot():
    for b in BLOCKS:
        if b == "military_crate":
            continue
        write(os.path.join(D, "loot_table", "blocks", b + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{NS}:{b}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}],
            "random_sequence": f"{NS}:blocks/{b}"})

    def e(name, w, lo=1, hi=1):
        x = {"type": "minecraft:item", "name": name, "weight": w}
        if hi > 1:
            x["functions"] = [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}}]
        return x

    gear = [
        e(f"{NS}:ak_magazine", 14, 1, 2), e(f"{NS}:glock_magazine", 10, 1, 2), e(f"{NS}:shotgun_shell", 10, 4, 10),
        e(f"{NS}:grenade", 8, 1, 2), e(f"{NS}:smoke_grenade", 6), e(f"{NS}:flashbang", 5),
        e(f"{NS}:first_aid_kit", 7), e(f"{NS}:tourniquet", 7), e(f"{NS}:rpg_rocket", 2),
        e(f"{NS}:armor_light_vest", 3), e(f"{NS}:armor_standard_helmet", 3), e(f"{NS}:armor_standard_vest", 2),
        e(f"{NS}:night_vision_goggles", 1), e(f"{NS}:glock19", 2), e(f"{NS}:ak47", 1),
        e("minecraft:bread", 6, 1, 4), e("minecraft:iron_ingot", 4, 1, 3),
    ]
    write(os.path.join(D, "loot_table", "blocks", "military_crate.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": {"type": "minecraft:uniform", "min": 1, "max": 3}, "entries": gear,
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        "random_sequence": f"{NS}:blocks/military_crate"})
    write(os.path.join(D, "loot_table", "chests", "military_base.json"), {
        "type": "minecraft:chest",
        "pools": [{"rolls": {"type": "minecraft:uniform", "min": 3, "max": 6}, "entries": gear}],
        "random_sequence": f"{NS}:chests/military_base"})


def tags():
    base = os.path.join(ROOT, "data", "minecraft", "tags", "block", "mineable")
    add = {
        "pickaxe.json": ["jersey_barrier", "chainlink_fence", "corrugated_olive", "corrugated_grey", "steel_grate", "fuel_drum",
                         "field_generator", "base_flag", "floodlight", "alarm_light", "barbed_wire"],
        "shovel.json": ["sandbags", "hesco_barrier"],
        "axe.json": ["military_crate", "camo_net"],
    }
    for f, names in add.items():
        path = os.path.join(base, f)
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        for n in names:
            v = f"{NS}:{n}"
            if v not in data["values"]:
                data["values"].append(v)
        write(path, data)


BIOMES = [
    "minecraft:plains", "minecraft:sunflower_plains", "minecraft:savanna", "minecraft:savanna_plateau", "minecraft:windswept_savanna",
    "minecraft:desert", "minecraft:snowy_plains", "minecraft:taiga", "minecraft:snowy_taiga", "minecraft:old_growth_pine_taiga",
    "minecraft:old_growth_spruce_taiga", "minecraft:meadow", "minecraft:badlands", "minecraft:wooded_badlands", "minecraft:eroded_badlands",
    "minecraft:sparse_jungle", "minecraft:forest", "minecraft:birch_forest", "minecraft:flower_forest", "minecraft:cherry_grove",
    "minecraft:dark_forest", "minecraft:pale_garden", "minecraft:swamp", "minecraft:windswept_hills", "minecraft:windswept_forest",
    "minecraft:windswept_gravelly_hills", "minecraft:grove", "minecraft:snowy_slopes", "minecraft:ice_spikes",
]

# name, site, spacing, separation, salt (spacing/separation in chunks)
SITES = [
    ("military_base", "base", 30, 12, 1100317),
    ("checkpoint", "checkpoint", 16, 6, 1100421),
    ("sam_site", "sam_site", 20, 8, 1100533),
    ("weapons_cache", "cache", 14, 5, 1100647),
]


def worldgen():
    for name, site, spacing, separation, salt in SITES:
        write(os.path.join(D, "worldgen", "structure", name + ".json"), {
            "type": f"{NS}:military_base",
            "site": site,
            "biomes": f"#{NS}:has_structure/military_base",
            "step": "surface_structures",
            "terrain_adaptation": "beard_thin",
            "spawn_overrides": {},
        })
        placement = {"type": "minecraft:random_spread", "salt": salt, "spacing": spacing, "separation": separation}
        if site == "base":
            placement["exclusion_zone"] = {"chunk_count": 6, "other_set": "minecraft:villages"}
        else:
            placement["exclusion_zone"] = {"chunk_count": 5, "other_set": f"{NS}:military_bases"}
        write(os.path.join(D, "worldgen", "structure_set", ("military_bases" if site == "base" else name) + ".json"), {
            "placement": placement,
            "structures": [{"structure": f"{NS}:{name}", "weight": 1}],
        })
    write(os.path.join(D, "tags", "worldgen", "biome", "has_structure", "military_base.json"), {"values": BIOMES})
    # every military site, for /locate structure #ballisticmissiles:military
    write(os.path.join(D, "tags", "worldgen", "structure", "military.json"), {"values": [f"{NS}:{n}" for n, *_ in SITES]})


if __name__ == "__main__":
    sandbags()
    hesco()
    concrete("jersey_barrier")
    chainlink()
    barbed_wire()
    camo_net()
    crate()
    corrugated("corrugated_olive", (88, 98, 58))
    corrugated("corrugated_grey", (140, 144, 146))
    steel_grate()
    fuel_drum()
    field_generator()
    lamp_parts()
    flag_plate()
    models()
    loot()
    tags()
    worldgen()
