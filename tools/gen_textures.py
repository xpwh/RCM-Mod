"""Generates all textures for the Ballistic Missiles mod.

Missile textures match the UV layout in MissileMesh.java:
  rows   0-191 : body unwrapped (row = height from nose (0) to tail (191), column = angle)
  rows 192-223 : fin skin
  rows 224-255 : dark nozzle / conduit metal
"""
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures")
random.seed(1337)
np.random.seed(1337)


def out(path):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    return full


def noise(w, h, scale=1.0, octaves=4):
    acc = np.zeros((h, w))
    amp = 1.0
    total = 0.0
    for o in range(octaves):
        cw, ch = max(2, int(w / (32 / 2 ** o) * scale)), max(2, int(h / (32 / 2 ** o) * scale))
        small = np.random.rand(ch, cw)
        img = Image.fromarray((small * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
        acc += np.asarray(img, dtype=np.float64) / 255.0 * amp
        total += amp
        amp *= 0.5
    return acc / total


def shade(arr, n, strength):
    arr[..., :3] = np.clip(arr[..., :3] * (1.0 + (n[..., None] - 0.5) * strength), 0, 255)


# ---------------------------------------------------------------------------------------------- missiles

K = 2  # missile textures are 512x512; decal code below is written in 256-space and scaled by K


class ScaledDraw:
    """ImageDraw proxy: takes 256-space coordinates, draws on the 512 texture."""

    def __init__(self, draw):
        self.d = draw

    @staticmethod
    def _xy(xy):
        if isinstance(xy, (list, tuple)) and xy and isinstance(xy[0], (list, tuple)):
            return [(p[0] * K, p[1] * K) for p in xy]
        return [v * K for v in xy]

    def rectangle(self, xy, **kw):
        x0, y0, x1, y1 = xy
        self.d.rectangle([x0 * K, y0 * K, x1 * K + K - 1, y1 * K + K - 1], **kw)

    def ellipse(self, xy, **kw):
        self.d.ellipse(self._xy(xy), **kw)

    def pieslice(self, xy, a0, a1, **kw):
        self.d.pieslice(self._xy(xy), a0, a1, **kw)

    def polygon(self, xy, **kw):
        self.d.polygon(self._xy(xy), **kw)

    def line(self, xy, fill=None, width=1):
        self.d.line(self._xy(xy), fill=fill, width=width * K)

    def point(self, xy, fill=None):
        x, y = xy
        self.d.rectangle([x * K, y * K, x * K + K - 1, y * K + K - 1], fill=fill)


def missile_texture(name, length, bands, base, fin_color, decals, soot=0.35, streaks=0.5, steel=(150, 150, 154)):
    """bands: list of (y_from, y_to, rgb) in missile-space; y=0 at the nozzle. Painted at 512x512."""
    W, H = 256 * K, 256 * K
    BODY = 192 * K
    img = np.zeros((H, W, 4), dtype=np.float64)
    img[..., 3] = 255

    def row(y):
        return int(round((1.0 - y / length) * 192))

    def rowk(y):
        return int(round((1.0 - y / length) * BODY))

    img[0:BODY, :, :3] = base
    for y0, y1, rgb in bands:
        r0, r1 = rowk(y1), rowk(y0)
        img[max(0, r0):min(BODY, r1), :, :3] = rgb

    img[BODY:224 * K, :, :3] = fin_color
    # metal strip: dark nozzle interior on the left, bare engine steel on the right
    img[224 * K:, : int(W * 0.45), :3] = (30, 30, 33)
    img[224 * K:, int(W * 0.55):, :3] = steel
    img[224 * K:, int(W * 0.45):int(W * 0.55), :3] = (60, 60, 64)

    # large-scale paint mottling, fine grain
    shade(img, noise(W, H, 1.2, 5), 0.16)
    shade(img, np.random.rand(H, W), 0.05)

    # vertical rain / exhaust streaks running towards the tail
    if streaks > 0:
        sn = noise(W, 8, 6.0, 3)
        st = np.asarray(Image.fromarray((sn * 255).astype(np.uint8)).resize((W, BODY), Image.BILINEAR), dtype=np.float64) / 255
        img[0:BODY, :, :3] *= (1.0 - streaks * 0.12 * (st[..., None] - 0.5))
    # soot towards the tail of the body
    if soot > 0:
        rows = np.arange(BODY) / BODY
        g = np.clip((rows - 0.82) / 0.18, 0, 1) ** 1.6 * soot
        img[0:BODY, :, :3] *= (1.0 - g[:, None, None])
    # heat tint on bare steel (bronze -> blue toward the exit)
    yy = np.linspace(0, 1, H - 224 * K)[:, None]
    tint = np.stack([1.0 + 0.10 * (1 - yy), 0.97 + 0 * yy, 0.92 + 0.14 * yy], axis=-1)
    img[224 * K:, int(W * 0.55):, :3] *= tint
    # soot inside the nozzle
    img[224 * K:, : int(W * 0.45), :3] *= 0.75 + 0.25 * noise(int(W * 0.45), H - 224 * K, 3.0)[..., None]

    arr = img
    # panel seams: soft dark line with a highlight below
    y = 1.0
    while y < length - 1.0:
        r = rowk(y)
        if 2 < r < BODY - 2:
            arr[r, :, :3] *= 0.72
            arr[r + 1, :, :3] *= 0.86
            arr[r + 2, :, :3] = np.minimum(255, arr[r + 2, :, :3] * 1.06)
        y += 0.75
    for x in range(0, W, 32 * K):
        arr[12:BODY - 12, x, :3] *= 0.72
        arr[12:BODY - 12, x + 1, :3] *= 0.88

    pil = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(pil)
    # rivet rows beside the seams
    for x in range(0, W, 32 * K):
        for r in range(24, BODY - 24, 9):
            d.ellipse([x + 4, r, x + 6, r + 2], fill=(205, 205, 200, 120))
    # fine scratches
    rng = np.random.default_rng(len(name) * 31)
    for _ in range(140):
        x = int(rng.uniform(0, W))
        r = int(rng.uniform(0, BODY))
        ln = int(rng.uniform(3, 14))
        d.line([(x, r), (x + ln, r + int(rng.uniform(-2, 2)))], fill=(235, 235, 230, int(rng.uniform(25, 60))))

    sd = ScaledDraw(d)
    for decal in decals:
        decal(pil, sd, row)

    # fin skin details: leading edge wear, rivet line
    fc = tuple(int(c * 0.62) for c in fin_color) + (255,)
    sd.rectangle([0, 192, 127, 223], outline=fc)
    sd.line([(0, 196), (127, 196)], fill=tuple(int(c * 0.78) for c in fin_color) + (255,))
    for x in range(4, 128, 6):
        sd.point((x, 199), fill=(200, 200, 196, 150))
    pil.save(out(f"entity/{name}.png"))


def text_vertical(pil, text, x, row_top, color, size=11):
    path = "C:/Windows/Fonts/arialbd.ttf"
    font = ImageFont.truetype(path, size * K) if os.path.exists(path) else ImageFont.load_default()
    tmp = Image.new("RGBA", (400 * K, 40 * K), (0, 0, 0, 0))
    td = ImageDraw.Draw(tmp)
    td.text((0, 0), text, font=font, fill=color)
    tmp = tmp.crop(tmp.getbbox())
    # body rows run nose->tail, so text reads along the length when rotated
    tmp = tmp.rotate(-90, expand=True)
    pil.alpha_composite(tmp, (x * K, row_top * K))


def checker_band(y0, y1, count=8, a=(20, 20, 20), b=(232, 232, 228)):
    def decal(pil, d, row):
        r0, r1 = row(y1), row(y0)
        w = 256 // count
        for i in range(count):
            d.rectangle([i * w, r0, i * w + w - 1, r1], fill=(a if i % 2 == 0 else b) + (255,))
    return decal


def hazard_band(y0, y1, c1=(230, 180, 20), c2=(20, 20, 20)):
    def decal(pil, d, row):
        r0, r1 = row(y1), row(y0)
        d.rectangle([0, r0, 255, r1], fill=c1 + (255,))
        for x in range(-16, 256, 16):
            d.polygon([(x, r1), (x + 8, r1), (x + 8 + (r1 - r0), r0), (x + (r1 - r0), r0)], fill=c2 + (255,))
    return decal


def stripe(y0, y1, color):
    def decal(pil, d, row):
        d.rectangle([0, row(y1), 255, row(y0)], fill=color + (255,))
    return decal


def trefoil_decal(rows, xs, ring=(20, 20, 20), fill=(245, 200, 20), r=16):
    def decal(pil, d, row):
        cy = (row(rows[0]) + row(rows[1])) // 2
        for cx in xs:
            d.ellipse([cx - r - 3, cy - r - 3, cx + r + 3, cy + r + 3], fill=ring + (255,))
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=fill + (255,))
            for k in range(3):
                a0 = -90 + k * 120 - 30
                d.pieslice([cx - r + 2, cy - r + 2, cx + r - 2, cy + r - 2], a0, a0 + 60, fill=ring + (255,))
            d.ellipse([cx - 5, cy - 5, cx + 5, cy + 5], fill=fill + (255,))
            d.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=ring + (255,))
    return decal


def stencil_decal(text, y_top, xs, color, size=11):
    def decal(pil, d, row):
        for x in xs:
            text_vertical(pil, text, x, row(y_top) + 3, color + (255,), size)
    return decal


def small_print(lines, y_top, x, color):
    """Block of tiny maintenance stencils, like the real ones."""
    def decal(pil, d, row):
        for i, t in enumerate(lines):
            text_vertical(pil, t, x + i * 7, row(y_top) + 2, color + (255,), 5)
    return decal


def access_panel(y0, y1, x0, x1, color):
    def decal(pil, d, row):
        d.rectangle([x0, row(y1), x1, row(y0)], outline=color + (255,))
        d.point((x0 + 2, row(y1) + 2), fill=(210, 210, 205, 200))
        d.point((x1 - 2, row(y1) + 2), fill=(210, 210, 205, 200))
        d.point((x0 + 2, row(y0) - 2), fill=(210, 210, 205, 200))
        d.point((x1 - 2, row(y0) - 2), fill=(210, 210, 205, 200))
    return decal


# ---- Iskander-style tactical family (L = 9) -------------------------------------------------------------------

def conventional():
    L = 9.0
    green = (80, 92, 56)
    missile_texture("ballistic_missile", L, [
        (0.0, 1.0, (64, 68, 62)),  # tail skirt
        (5.62, 5.90, (58, 66, 42)),  # instrument ring
    ], green, (74, 86, 52), [
        stencil_decal("9M723", 5.4, (40, 168), (230, 230, 220), 11),
        small_print(["\u0412\u0417\u0420", "48 \u0420\u0413 7"], 3.4, 100, (220, 220, 210)),
        access_panel(2.9, 3.5, 60, 84, (50, 58, 34)),
        access_panel(2.9, 3.5, 188, 212, (50, 58, 34)),
        stripe(6.6, 6.68, (200, 40, 30)),
    ], soot=0.45)


def bunker_buster():
    L = 9.0
    missile_texture("bunker_buster", L, [
        (0.0, 1.0, (52, 52, 56)),
        (2.0, 2.12, (220, 120, 30)),
        (5.90, 9.0, (46, 46, 48)),  # hardened penetrator nose
        (5.90, 6.05, (220, 120, 30)),
    ], (66, 70, 72), (60, 64, 66), [
        stencil_decal("BX-2  PENETRATOR", 5.5, (40, 168), (230, 150, 50), 10),
        access_panel(3.0, 3.6, 60, 84, (40, 42, 44)),
    ], soot=0.5)


def cluster():
    L = 9.0
    missile_texture("cluster_missile", L, [
        (0.0, 1.0, (88, 92, 80)),
        (5.90, 6.06, (240, 200, 40)),  # yellow: high explosive
        (8.75, 9.0, (40, 40, 40)),
    ], (138, 148, 116), (128, 138, 108), [
        stencil_decal("MGM-140", 5.4, (52, 180), (40, 42, 36), 11),
        access_panel(3.2, 5.6, 16, 48, (98, 106, 82)),
        access_panel(3.2, 5.6, 80, 112, (98, 106, 82)),
        access_panel(3.2, 5.6, 144, 176, (98, 106, 82)),
        access_panel(3.2, 5.6, 208, 240, (98, 106, 82)),
    ])


def thermobaric():
    L = 9.0

    def flames(pil, d, row):
        cy = (row(5.4) + row(4.4)) // 2
        for cx in (64, 192):
            d.polygon([(cx, cy - 12), (cx + 9, cy + 10), (cx - 9, cy + 10)], fill=(230, 90, 20, 255))
            d.polygon([(cx, cy - 4), (cx + 5, cy + 10), (cx - 5, cy + 10)], fill=(250, 210, 60, 255))

    missile_texture("thermobaric_missile", L, [
        (0.0, 1.0, (60, 56, 50)),
        (5.90, 6.06, (190, 30, 20)),
        (6.06, 6.16, (235, 190, 40)),
        (8.75, 9.0, (36, 36, 36)),
    ], (94, 80, 54), (86, 74, 50), [
        flames, stencil_decal("TBX-2  FAE", 4.0, (20, 148), (235, 225, 200), 10),
    ], soot=0.45)


# ---- strategic missiles (L = 12) --------------------------------------------------------------------------------

def nuclear():
    L = 12.0
    missile_texture("nuclear_missile", L, [
        (0.45, 0.92, (60, 60, 64)),
        (5.20, 5.50, (28, 28, 30)),
        (7.98, 8.28, (28, 28, 30)),
        (9.30, 9.80, (170, 172, 176)),
        (9.80, 12.0, (30, 30, 32)),  # black shroud
    ], (224, 226, 222), (210, 210, 205), [
        checker_band(3.8, 4.4, 8),
        stencil_decal("LGM-30", 7.9, (8, 136), (30, 30, 30), 9),
        trefoil_decal((6.0, 7.2), (100, 228)),
        small_print(["STAGE 2", "SRM  M57"], 7.0, 60, (40, 40, 40)),
        small_print(["STAGE 1", "SRM  M55"], 3.2, 60, (40, 40, 40)),
        stripe(1.6, 1.7, (180, 24, 20)),
    ], soot=0.3)


def mirv():
    L = 12.0
    missile_texture("mirv_missile", L, [
        (0.45, 0.92, (60, 60, 64)),
        (5.20, 5.50, (28, 28, 30)),
        (7.98, 8.28, (28, 28, 30)),
        (9.30, 9.80, (28, 28, 30)),
        (9.80, 12.0, (232, 232, 228)),
    ], (232, 232, 228), (220, 220, 216), [
        checker_band(2.0, 2.8, 8),
        checker_band(6.4, 7.0, 8),
        checker_band(10.4, 10.9, 4),
        stencil_decal("LGM-118", 6.3, (8, 136), (30, 30, 30), 9),
        trefoil_decal((8.4, 9.2), (100, 228), r=12),
    ], soot=0.3)


def hydrogen():
    L = 12.0
    missile_texture("hydrogen_bomb", L, [
        (0.78, 0.95, (60, 60, 64)),
        (7.38, 7.92, (40, 42, 46)),  # truss interstage
        (10.15, 10.45, (40, 42, 46)),
        (10.45, 12.0, (54, 44, 36)),  # ablative re-entry vehicle
    ], (188, 192, 196), (176, 180, 184), [
        checker_band(2.0, 2.9, 8),
        checker_band(8.6, 9.3, 8),
        stencil_decal("LGM-25C", 6.9, (8, 136), (30, 30, 30), 13),
        stencil_decal("THERMONUCLEAR", 5.3, (40, 168), (170, 20, 20), 6),
        trefoil_decal((3.6, 4.6), (100, 228), ring=(170, 20, 20), fill=(240, 240, 235)),
        hazard_band(1.3, 1.6),
    ], soot=0.45)

    # truss lattice on the interstage
    p = out("entity/hydrogen_bomb.png")
    pil = Image.open(p)
    d = ImageDraw.Draw(pil)
    r0 = int(round((1 - 7.92 / L) * 192)) * K
    r1 = int(round((1 - 7.38 / L) * 192)) * K
    for x in range(0, 256 * K, 16 * K):
        d.line([(x, r0), (x + 16 * K, r1)], fill=(150, 152, 156, 255), width=3)
        d.line([(x + 16 * K, r0), (x, r1)], fill=(150, 152, 156, 255), width=3)
    pil.save(p)


# ---- other airframes ---------------------------------------------------------------------------------------------

def cruise():
    L = 6.0
    missile_texture("cruise_missile", L, [
        (2.40, 2.48, (60, 62, 66)),
        (5.35, 6.0, (64, 68, 78)),  # radome / seeker
    ], (222, 224, 222), (210, 212, 210), [
        stencil_decal("BGM-109", 4.6, (40, 168), (40, 42, 46), 10),
        stripe(4.75, 4.82, (190, 30, 30)),
        access_panel(1.2, 1.9, 100, 140, (180, 182, 180)),
        small_print(["FUEL JP-10", "DO NOT STEP"], 3.6, 64, (60, 60, 60)),
    ], soot=0.15, steel=(120, 122, 126))


def hypersonic():
    L = 8.0
    missile_texture("hypersonic_missile", L, [
        (0.0, 0.30, (70, 70, 74)),
        (4.40, 4.50, (40, 40, 44)),
        (6.4, 8.0, (58, 56, 60)),  # heat-resistant nose
    ], (214, 216, 214), (200, 202, 200), [
        stencil_decal("Kh-47M2", 4.1, (40, 168), (40, 40, 44), 11),
        stripe(1.2, 1.28, (40, 40, 44)),
    ], soot=0.3)
    # heat-scorched gradient on the nose cone
    p = out("entity/hypersonic_missile.png")
    arr = np.asarray(Image.open(p)).astype(np.float64)
    top = int(round((1 - 8.0 / L) * 192)) * K
    bot = int(round((1 - 4.5 / L) * 192)) * K
    for r in range(top, bot):
        f = 1.0 - (r - top) / max(1, bot - top)
        arr[r, :, 0] *= 1.0 - 0.25 * f
        arr[r, :, 1] *= 1.0 - 0.32 * f
        arr[r, :, 2] *= 1.0 - 0.38 * f
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA").save(p)


def reentry_vehicle():
    missile_texture("reentry_vehicle", 1.8, [(0.0, 0.1, (40, 36, 32))], (70, 56, 42), (70, 56, 42), [], soot=0.0, streaks=1.5)
    p = out("entity/reentry_vehicle.png")
    arr = np.asarray(Image.open(p)).astype(np.float64)
    arr[:192 * K, :, :3] *= (0.75 + 0.5 * noise(256 * K, 192 * K, 6.0)[..., None])  # charred ablative shield
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA").save(p)


def interceptor():
    missile_texture("interceptor", 2.6, [(0.0, 0.1, (40, 40, 44)), (1.9, 2.6, (70, 70, 74)), (1.0, 1.06, (30, 30, 30))],
                    (226, 226, 222), (210, 210, 206), [], soot=0.25)


def bomblet():
    missile_texture("bomblet", 0.6, [(0.0, 0.2, (60, 60, 60)), (0.42, 0.6, (30, 30, 30))], (200, 170, 40), (190, 160, 40), [], soot=0.0)


def plasma():
    """Re-entry plasma sheath, mapped with the body UVs: bright at the nose, gone towards the tail."""
    W, H = 128, 256
    img = np.zeros((H, W, 4))
    n = noise(W, H, 4.0)
    for r in range(H):
        f = r / 192.0  # 0 at the nose tip
        a = max(0.0, 1.0 - f / 0.55) ** 1.3 if r < 192 else 0.0
        img[r, :, 0] = 255
        img[r, :, 1] = 255 * (0.85 - 0.55 * f)
        img[r, :, 2] = 255 * (0.95 - 0.7 * f)
        img[r, :, 3] = 255 * a
    img[..., 3] *= 0.7 + 0.3 * n
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(out("entity/plasma.png"))


def flame():
    W, H = 64, 128
    img = np.zeros((H, W, 4), dtype=np.float64)
    n = noise(W, H, 3.0)
    for yy in range(H):
        f = yy / (H - 1)  # 0 at nozzle, 1 at tail
        core = max(0.0, 1.0 - f * 1.6)
        r = 255
        g = 255 * (0.95 - 0.6 * f)
        b = 255 * max(0.0, 0.85 - 1.6 * f) + 60 * core
        img[yy, :, 0] = r
        img[yy, :, 1] = g
        img[yy, :, 2] = min(255, b)
        img[yy, :, 3] = 255 * (1.0 - f) ** 1.2
    img[..., 3] *= 0.65 + 0.35 * n
    img[..., 1] *= 0.85 + 0.15 * n
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(out("entity/exhaust_flame.png"))


# ---------------------------------------------------------------------------------------------- particles

def fbm(size, seed, base=4, octaves=5):
    """Tileless fractal noise in [0,1]."""
    r = np.random.default_rng(seed)
    acc = np.zeros((size, size))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = base * 2 ** o
        small = r.random((cells + 1, cells + 1))
        img = Image.fromarray((small * 255).astype(np.uint8)).resize((size, size), Image.BICUBIC)
        acc += np.asarray(img, dtype=np.float64) / 255.0 * amp
        total += amp
        amp *= 0.55
    return acc / total


def cloud_heightfield(size, seed):
    """Cauliflower cloud: a core sphere with billows growing on its surface (mostly upwards)."""
    r = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    c = size / 2
    h = np.zeros((size, size))
    blobs = [(c, c + size * 0.04, size * 0.3)]
    for _ in range(r.integers(9, 14)):
        ang = r.uniform(0, 2 * np.pi)
        # bias billows to the top (screen y up = negative)
        dist = size * r.uniform(0.14, 0.25)
        bx = c + np.cos(ang) * dist
        by = c + np.sin(ang) * dist * 0.8 - size * 0.04
        br = size * r.uniform(0.12, 0.2)
        blobs.append((bx, by, br))
    for bx, by, br in blobs:
        d2 = (xx - bx) ** 2 + (yy - by) ** 2
        h = np.maximum(h, np.sqrt(np.clip(br * br - d2, 0, None)) / (size * 0.3))
    return h


def volumetric_puff(size, shape_seed, stage, hot):
    h = cloud_heightfield(size, shape_seed)
    detail = fbm(size, shape_seed * 7 + 1, base=4)
    fine = fbm(size, shape_seed * 13 + 2, base=8, octaves=4)
    h = h * (0.75 + 0.5 * detail) + (fine - 0.5) * 0.08 * (h > 0)

    # dissolve: erode the edges and thin out the density as the puff ages
    erosion = stage / 3.0
    density = np.clip(h * 1.8 - erosion * (0.55 + 0.9 * fbm(size, shape_seed * 3 + stage, base=3)), 0, 1)
    density = density ** (1.0 + erosion * 0.8)
    density = np.asarray(Image.fromarray((density * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.1 + stage * 1.0)), dtype=np.float64) / 255
    yy, xx = np.mgrid[0:size, 0:size]
    rr = np.sqrt((xx - size / 2) ** 2 + (yy - size / 2) ** 2) / (size / 2)
    density *= np.clip((1.0 - rr) * 4.0, 0, 1)  # never touch the sprite border

    # lighting from the height field normal: sun up and slightly left/front
    gy, gx = np.gradient(h * size * 0.35)
    nx, ny, nz = -gx, -gy, np.ones_like(h)
    nl = np.sqrt(nx ** 2 + ny ** 2 + nz ** 2)
    nx, ny, nz = nx / nl, ny / nl, nz / nl
    lx, ly, lz = -0.35, -0.8, 0.5
    ll = np.sqrt(lx * lx + ly * ly + lz * lz)
    lambert = np.clip((nx * lx + ny * ly + nz * lz) / ll, 0, 1)
    vertical = 1.0 - yy / size  # brighter top, shadowed underside
    ao = np.clip(h * 1.6, 0, 1) ** 0.5  # crevices between billows get darker
    light = 0.36 + 0.5 * lambert + 0.22 * vertical
    light *= 0.7 + 0.3 * ao
    light *= 0.92 + 0.16 * (fine - 0.5)
    light = light * (1 - erosion * 0.25) + erosion * 0.25 * 0.8  # thin smoke scatters flat
    light = np.clip(light, 0, 1)

    img = np.zeros((size, size, 4))
    if hot:
        core = np.clip(h * 1.8 - stage * 0.25, 0, 1)
        img[..., 0] = 255
        img[..., 1] = 255 * np.clip(0.55 + 0.45 * core, 0, 1)
        img[..., 2] = 255 * np.clip(0.25 + 0.75 * core ** 2, 0, 1)
        img[..., :3] *= np.clip(0.75 + 0.35 * light, 0, 1)[..., None]
    else:
        v = 255 * light
        img[..., 0] = v
        img[..., 1] = v * 0.99
        img[..., 2] = v * 0.97
    img[..., 3] = 255 * np.clip(density * (1.25 if stage == 0 else 1.0), 0, 1)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA")


def particles():
    for f in os.listdir(out("particle")[:-0] if False else os.path.join(ROOT, "particle")):
        os.remove(os.path.join(ROOT, "particle", f))
    for shape in range(8):
        for stage in range(4):
            volumetric_puff(64, 300 + shape, stage, False).save(out(f"particle/smoke_{shape}_{stage}.png"))
    for shape in range(4):
        for stage in range(4):
            volumetric_puff(64, 500 + shape, stage, True).save(out(f"particle/fire_{shape}_{stage}.png"))


def item_missile(name, body, nose, fin, band, nuclear, wings=False):
    S = 16
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    p = img.load()
    tx, ty, nx, ny = 2.0, 14.0, 14.0, 2.0
    ax, ay = nx - tx, ny - ty
    length = math.hypot(ax, ay)
    ax, ay = ax / length, ay / length
    px_, py_ = -ay, ax  # perpendicular

    def dim(c, f):
        return tuple(max(0, min(255, int(v * f))) for v in c)

    for x in range(S):
        for y in range(S):
            cx, cy = x + 0.5 - tx, y + 0.5 - ty
            u = (cx * ax + cy * ay) / length
            v = cx * px_ + cy * py_
            col = None
            w = 1.3
            if 0.07 <= u < 0.76 and abs(v) < w:
                col = body
                if any(b0 <= u < b1 for b0, b1 in band):
                    col = (232, 188, 36) if not nuclear else (26, 26, 28)
            elif 0.76 <= u <= 1.02 and abs(v) < w * max(0.0, 1 - (u - 0.76) / 0.26) ** 0.6 + 0.15:
                col = nose
            if col is None and 0.07 <= u < 0.32 and abs(v) < w + 1.9 * (0.32 - u) / 0.25 and abs(v) >= w - 0.01:
                col = fin
            if col is None and -0.04 <= u < 0.07 and abs(v) < 0.95:
                col = (255, 170, 40) if u < 0.02 else (60, 60, 64)
            if col is None:
                continue
            if v > 0.45:
                col = dim(col, 0.72)
            elif v < -0.45:
                col = dim(col, 1.18)
            p[x, y] = col + (255,)
    if nuclear:
        # tiny trefoil dot
        p[8, 8] = (245, 200, 20, 255)
    if wings:
        for k in range(1, 4):
            for (x, y) in ((8 + k, 8 + k), (7 - k + 1, 7 - k + 1)):
                if 0 <= x < S and 0 <= y < S and p[x, y][3] == 0:
                    p[x, y] = fin + (255,)
    outline(img)
    img.save(out(f"item/{name}.png"))


def outline(img):
    S = img.size[0]
    src = img.copy().load()
    p = img.load()
    for x in range(S):
        for y in range(S):
            if src[x, y][3] == 0:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < S and 0 <= ny < S and src[nx, ny][3] > 0:
                        p[x, y] = (24, 24, 28, 255)
                        break


def item_designator():
    S = 16
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([3, 5, 12, 12], fill=(58, 62, 66, 255))  # body
    d.rectangle([3, 5, 12, 6], fill=(84, 90, 96, 255))
    d.rectangle([5, 12, 7, 15], fill=(40, 42, 46, 255))  # grip
    d.rectangle([12, 7, 14, 9], fill=(30, 30, 34, 255))  # lens barrel
    d.point((15, 8), fill=(255, 40, 40, 255))  # laser emitter
    d.rectangle([5, 7, 9, 10], fill=(20, 60, 30, 255))  # screen
    d.point((6, 8), fill=(80, 255, 120, 255))
    d.point((8, 9), fill=(80, 255, 120, 255))
    d.point((7, 8), fill=(255, 60, 60, 255))
    d.rectangle([10, 3, 11, 5], fill=(40, 40, 44, 255))  # antenna
    d.point((10, 2), fill=(255, 60, 60, 255))
    outline(img)
    img.save(out("item/target_designator.png"))
    # empty variant uses same art (keeps model simple)


def block_textures():
    S = 16
    rng = random.Random(7)
    # top: steel deck with hazard border and flame trench grate
    top = Image.new("RGBA", (S, S), (92, 96, 100, 255))
    p = top.load()
    for x in range(S):
        for y in range(S):
            v = rng.randint(-8, 8)
            c = p[x, y]
            p[x, y] = (c[0] + v, c[1] + v, c[2] + v, 255)
            border = x < 2 or y < 2 or x > 13 or y > 13
            if border:
                p[x, y] = (230, 180, 20, 255) if ((x + y) // 2) % 2 == 0 else (24, 24, 24, 255)
    d = ImageDraw.Draw(top)
    d.rectangle([5, 5, 10, 10], fill=(30, 30, 32, 255))
    for i in range(5, 11, 2):
        d.line([(5, i), (10, i)], fill=(70, 70, 74, 255))
    d.ellipse([4, 4, 11, 11], outline=(140, 30, 20, 255))
    top.save(out("block/launch_pad_top.png"))

    side = Image.new("RGBA", (S, S), (80, 84, 88, 255))
    p = side.load()
    for x in range(S):
        for y in range(S):
            v = rng.randint(-7, 7)
            c = p[x, y]
            p[x, y] = (c[0] + v, c[1] + v, c[2] + v, 255)
    d = ImageDraw.Draw(side)
    for x in range(0, S, 4):
        d.line([(x, 10), (x + 3, 13)], fill=(230, 180, 20, 255))
        d.line([(x + 2, 10), (x + 5, 13)], fill=(24, 24, 24, 255))
    d.line([(0, 9), (15, 9)], fill=(50, 52, 56, 255))
    d.line([(0, 14), (15, 14)], fill=(50, 52, 56, 255))
    for x in (1, 14):
        d.point((x, 11), fill=(170, 170, 170, 255))
    side.save(out("block/launch_pad_side.png"))

    clamp = Image.new("RGBA", (S, S), (54, 56, 60, 255))
    p = clamp.load()
    for x in range(S):
        for y in range(S):
            v = rng.randint(-6, 6)
            c = p[x, y]
            p[x, y] = (c[0] + v, c[1] + v, c[2] + v, 255)
    d = ImageDraw.Draw(clamp)
    d.rectangle([0, 0, 15, 1], fill=(180, 30, 25, 255))
    clamp.save(out("block/launch_pad_clamp.png"))


def item_geiger():
    S = 16
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([2, 4, 11, 14], fill=(214, 176, 30, 255))  # housing
    d.rectangle([2, 4, 11, 5], fill=(236, 200, 60, 255))
    d.rectangle([3, 6, 10, 10], fill=(232, 228, 210, 255))  # dial
    d.line([(4, 10), (8, 7)], fill=(200, 30, 30, 255))
    d.point((6, 12), fill=(30, 30, 30, 255))
    d.point((9, 12), fill=(30, 30, 30, 255))
    d.rectangle([12, 2, 13, 11], fill=(70, 70, 74, 255))  # probe
    d.line([(11, 13), (13, 11)], fill=(40, 40, 40, 255))
    outline(img)
    img.save(out("item/geiger_counter.png"))


def tile(base, seed, var=8):
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16), base + (255,))
    p = img.load()
    for x in range(16):
        for y in range(16):
            v = rng.randint(-var, var)
            c = p[x, y]
            p[x, y] = (max(0, min(255, c[0] + v)), max(0, min(255, c[1] + v)), max(0, min(255, c[2] + v)), 255)
    return img


def tool_blocks():
    # radar: white dish, grey mast, base housing with status lamp
    dish = tile((228, 230, 228), 11, 5)
    d = ImageDraw.Draw(dish)
    for r in (7, 5, 3):
        d.ellipse([8 - r, 8 - r, 7 + r, 7 + r], outline=(196, 198, 196, 255))
    d.ellipse([6, 6, 9, 9], fill=(80, 80, 84, 255))
    dish.save(out("block/radar_dish.png"))
    mast = tile((110, 114, 118), 12, 6)
    d = ImageDraw.Draw(mast)
    for y in range(0, 16, 4):
        d.line([(0, y), (15, y + 3)], fill=(90, 94, 98, 255))
    mast.save(out("block/radar_mast.png"))
    base = tile((84, 92, 70), 13, 6)
    d = ImageDraw.Draw(base)
    d.rectangle([1, 1, 14, 14], outline=(60, 66, 50, 255))
    d.rectangle([3, 3, 7, 6], fill=(20, 40, 24, 255))
    d.point((4, 4), fill=(80, 255, 120, 255))
    d.line([(9, 4), (13, 4)], fill=(40, 40, 40, 255))
    d.line([(9, 6), (13, 6)], fill=(40, 40, 40, 255))
    base.save(out("block/radar_base.png"))
    lamp = tile((84, 92, 70), 13, 6)
    d = ImageDraw.Draw(lamp)
    d.rectangle([1, 1, 14, 14], outline=(60, 66, 50, 255))
    d.rectangle([3, 3, 7, 6], fill=(60, 10, 10, 255))
    d.point((4, 4), fill=(255, 60, 40, 255))
    d.point((5, 5), fill=(255, 60, 40, 255))
    lamp.save(out("block/radar_base_alert.png"))

    # air defense: olive launcher canisters
    side = tile((86, 96, 62), 21, 6)
    d = ImageDraw.Draw(side)
    d.rectangle([0, 0, 15, 15], outline=(62, 70, 44, 255))
    d.line([(0, 8), (15, 8)], fill=(62, 70, 44, 255))
    d.line([(8, 0), (8, 15)], fill=(62, 70, 44, 255))
    for (x, y) in ((2, 2), (12, 2), (2, 12), (12, 12)):
        d.point((x, y), fill=(170, 170, 160, 255))
    side.save(out("block/air_defense_side.png"))
    front = tile((86, 96, 62), 22, 6)
    d = ImageDraw.Draw(front)
    for (x0, y0) in ((1, 1), (9, 1), (1, 9), (9, 9)):
        d.rectangle([x0, y0, x0 + 5, y0 + 5], fill=(36, 38, 34, 255))
        d.ellipse([x0 + 1, y0 + 1, x0 + 4, y0 + 4], fill=(20, 20, 20, 255))
        d.point((x0 + 2, y0 + 2), fill=(200, 60, 40, 255))
    front.save(out("block/air_defense_front.png"))
    top = tile((80, 90, 58), 23, 6)
    d = ImageDraw.Draw(top)
    d.rectangle([0, 0, 15, 15], outline=(60, 66, 44, 255))
    d.rectangle([5, 5, 10, 10], fill=(60, 66, 44, 255))
    top.save(out("block/air_defense_top.png"))
    plinth = tile((70, 72, 74), 24, 6)
    d = ImageDraw.Draw(plinth)
    for x in range(0, 16, 4):
        d.line([(x, 11), (x + 3, 14)], fill=(230, 180, 20, 255))
    plinth.save(out("block/air_defense_base.png"))


def icon():
    S = 128
    img = Image.new("RGBA", (S, S), (16, 18, 22, 255))
    d = ImageDraw.Draw(img)
    for r in range(60, 0, -4):
        a = int(255 * (1 - r / 60))
        d.ellipse([64 - r, 70 - r, 64 + r, 70 + r], fill=(255, 120 + a // 3, 30, 40 + a // 2))
    d.polygon([(64, 8), (74, 30), (74, 90), (54, 90), (54, 30)], fill=(226, 226, 220, 255))
    d.polygon([(64, 8), (74, 30), (54, 30)], fill=(30, 30, 30, 255))
    d.polygon([(54, 78), (42, 96), (54, 92)], fill=(200, 200, 196, 255))
    d.polygon([(74, 78), (86, 96), (74, 92)], fill=(200, 200, 196, 255))
    d.rectangle([54, 52, 74, 56], fill=(30, 30, 30, 255))
    d.polygon([(56, 92), (72, 92), (64, 124)], fill=(255, 210, 90, 255))
    img.save(os.path.join(ROOT, "..", "icon.png"))


if __name__ == "__main__":
    conventional()
    nuclear()
    hydrogen()
    mirv()
    hypersonic()
    reentry_vehicle()
    interceptor()
    plasma()
    bunker_buster()
    cluster()
    thermobaric()
    cruise()
    bomblet()
    flame()
    particles()
    item_missile("ballistic_missile", (90, 104, 58), (52, 58, 40), (70, 80, 46), [(0.60, 0.66)], False)
    item_missile("nuclear_missile", (228, 228, 222), (40, 40, 44), (190, 190, 186), [(0.36, 0.41), (0.62, 0.67)], True)
    item_missile("hydrogen_bomb", (52, 54, 58), (20, 20, 22), (70, 72, 76), [(0.36, 0.41), (0.62, 0.67)], True)
    item_missile("bunker_buster", (70, 74, 78), (40, 40, 42), (60, 64, 68), [(0.58, 0.64)], False)
    item_missile("cluster_missile", (180, 160, 114), (48, 50, 52), (150, 134, 96), [(0.60, 0.66)], False)
    item_missile("thermobaric_missile", (98, 82, 54), (40, 38, 36), (84, 72, 48), [(0.58, 0.66)], False)
    item_missile("cruise_missile", (156, 162, 168), (50, 54, 64), (120, 126, 132), [(0.06, 0.16)], False, wings=True)
    item_missile("hypersonic_missile", (214, 216, 214), (58, 56, 60), (180, 182, 180), [(0.52, 0.56)], False)
    item_missile("mirv_missile", (232, 232, 228), (232, 232, 228), (200, 200, 196), [(0.22, 0.30), (0.52, 0.58)], True)
    item_geiger()
    tool_blocks()
    item_designator()
    block_textures()
    icon()
    print("textures ok")
