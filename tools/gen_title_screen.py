"""9.5 title screen: real photographs, a proper logo, glass buttons.

  textures/gui/title/launch1..3.png   Minuteman III ICBM test launches from Vandenberg at night, cropped to
                                      16:9 (public domain, U.S. Air Force / U.S. Space Force photos, see
                                      CREDITS.md). Pass the folder with the downloaded originals (mm1..3.jpg).
  textures/gui/title/logo.png         "BALLISTIC" in brushed steel over "MISSILES" in glowing amber
  minecraft:gui/sprites/widget/button*.png   dark glass buttons at twice the resolution; amber when hovered
python3 gen_title_screen.py <photo folder>
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets")
TITLE = os.path.join(ROOT, "ballisticmissiles", "textures", "gui", "title")
FONT = "/usr/share/fonts/opentype/inter/InterDisplay-Black.otf"
FONT_WIDE = "/usr/share/fonts/opentype/inter/InterDisplay-Bold.otf"
os.makedirs(TITLE, exist_ok=True)
rng = np.random.default_rng(7)

# ---------------------------------------------------------------------------- photographs
# (crop focus: where the interesting part of each picture sits, 0 top .. 1 bottom)
if len(sys.argv) > 1:
    for i, focus in ((1, 0.35), (2, 0.4), (3, 0.45)):
        im = Image.open(os.path.join(sys.argv[1], f"mm{i}.jpg")).convert("RGB")
        w, h = im.size
        ch = int(w * 9 / 16)
        top = int((h - ch) * focus)
        im = im.crop((0, top, w, top + ch)).resize((1600, 900), Image.LANCZOS)
        im.save(os.path.join(TITLE, f"launch{i}.png"), optimize=True)
        with open(os.path.join(TITLE, f"launch{i}.png.mcmeta"), "w") as f:
            f.write('{\n\t"texture": {\n\t\t"blur": true\n\t}\n}\n')

# ---------------------------------------------------------------------------- logo
S = 2  # supersampling
LW, LH = 1024, 300


def text_layer(text, font, size, tracking):
    f = ImageFont.truetype(font, size * S)
    widths = [f.getbbox(c)[2] - f.getbbox(c)[0] if c != " " else size * S // 3 for c in text]
    total = sum(widths) + tracking * S * (len(text) - 1)
    layer = Image.new("L", (LW * S, LH * S), 0)
    d = ImageDraw.Draw(layer)
    return f, widths, total, layer, d


def draw_text(text, font, size, tracking, y):
    f, widths, total, layer, d = text_layer(text, font, size, tracking)
    x = (LW * S - total) / 2
    for c, cw in zip(text, widths):
        bx = f.getbbox(c)[0]
        d.text((x - bx, y * S), c, font=f, fill=255)
        x += cw + tracking * S
    return layer


def gradient(top, bottom, y0, y1):
    g = np.zeros((LH * S, LW * S, 3))
    t = np.clip((np.arange(LH * S)[:, None] - y0 * S) / ((y1 - y0) * S), 0, 1)
    for k in range(3):
        g[..., k] = top[k] * (1 - t) + bottom[k] * t
    return g


logo = np.zeros((LH * S, LW * S, 4))


def composite(rgb, alpha):
    a = alpha[..., None]
    logo[..., :3] = rgb * a + logo[..., :3] * (1 - a)
    logo[..., 3:] = a + logo[..., 3:] * (1 - a)


big = draw_text("BALLISTIC", FONT, 150, 10, 18)
small = draw_text("MISSILES", FONT_WIDE, 64, 46, 200)
bigA = np.array(big) / 255.0
smallA = np.array(small) / 255.0
# drop shadow
shadow = np.array(Image.fromarray((np.maximum(bigA, smallA) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(10 * S))) / 255.0
shadow = np.roll(shadow, (6 * S, 4 * S), (0, 1))
composite(np.zeros((LH * S, LW * S, 3)), shadow * 0.85)
# amber glow behind "MISSILES"
glow = np.array(small.filter(ImageFilter.GaussianBlur(14 * S))) / 255.0
composite(np.ones((LH * S, LW * S, 3)) * np.array([255, 120, 30]), np.clip(glow * 1.6, 0, 1) * 0.7)
# dark outline round "BALLISTIC"
outline = np.array(big.filter(ImageFilter.MaxFilter(7))) / 255.0
composite(np.ones((LH * S, LW * S, 3)) * np.array([14, 16, 20]), outline)
# brushed steel face: light at the top, a horizon line, darker below, fine horizontal brushing
steel = gradient((250, 252, 255), (150, 158, 170), 30, 105)
lower = gradient((112, 120, 132), (205, 212, 222), 105, 185)
yy = np.arange(LH * S)[:, None]
face = np.where((yy >= 105 * S)[..., None], lower, steel)
face += rng.normal(0, 5, (LH * S, 1, 1)) + rng.normal(0, 2, (LH * S, LW * S, 1))
composite(np.clip(face, 0, 255), bigA)
# a hairline highlight along the top edge of each letter
edge = np.clip(bigA - np.roll(bigA, 3 * S, 0), 0, 1)
composite(np.ones((LH * S, LW * S, 3)) * 255, edge * 0.6)
# "MISSILES": amber, hot at the top
composite(gradient((255, 214, 120), (255, 110, 20), 200, 270), smallA)
# rules either side of "MISSILES"
d = ImageDraw.Draw(rules := Image.new("L", (LW * S, LH * S), 0))
f, widths, total, _, _ = text_layer("MISSILES", FONT_WIDE, 64, 46)
cx = LW * S / 2
for sgn in (-1, 1):
    x0 = cx + sgn * (total / 2 + 30 * S)
    x1 = cx + sgn * (total / 2 + 210 * S)
    d.rectangle([min(x0, x1), 236 * S, max(x0, x1), 241 * S], fill=255)
rulesA = np.array(rules) / 255.0
composite(np.ones((LH * S, LW * S, 3)) * np.array([255, 150, 50]), rulesA * 0.9)
out = Image.fromarray(np.clip(logo * [1, 1, 1, 255], 0, 255).astype(np.uint8), "RGBA").resize((LW, LH), Image.LANCZOS)
out.save(os.path.join(TITLE, "logo.png"))
with open(os.path.join(TITLE, "logo.png.mcmeta"), "w") as f:
    f.write('{\n\t"texture": {\n\t\t"blur": true\n\t}\n}\n')

# ---------------------------------------------------------------------------- buttons (2x resolution)
BW, BH = 400, 40


def button(top, bottom, alpha, border, border_alpha, inner, path, glow=None):
    b = np.zeros((BH, BW, 4))
    t = np.linspace(0, 1, BH)[:, None]
    for k in range(3):
        b[..., k] = top[k] * (1 - t) + bottom[k] * t
    b[..., 3] = alpha
    if glow is not None:
        # light from the edges inwards
        yy, xx = np.mgrid[0:BH, 0:BW]
        dist = np.minimum.reduce([yy, BH - 1 - yy, xx, BW - 1 - xx]).astype(float)
        g = np.exp(-dist / 5.0)[..., None]
        b[..., :3] = b[..., :3] * (1 - g * 0.6) + np.array(glow) * g * 0.6
        b[..., 3] = np.maximum(b[..., 3], 255 * g[..., 0] * 0.6)
    b[2:4, 2:-2, :3] = inner  # top bevel
    b[2:4, 2:-2, 3] = np.maximum(b[2:4, 2:-2, 3], 200)
    for sl in (np.s_[0:2, :], np.s_[-2:, :], np.s_[:, 0:2], np.s_[:, -2:]):
        b[sl][..., :3] = border
        b[sl][..., 3] = border_alpha
    Image.fromarray(np.clip(b, 0, 255).astype(np.uint8), "RGBA").save(path)


wid = os.path.join(ROOT, "minecraft", "textures", "gui", "sprites", "widget")
os.makedirs(wid, exist_ok=True)
button((40, 44, 50), (18, 20, 24), 215, (90, 96, 104), 255, (78, 84, 92), os.path.join(wid, "button.png"))
button((70, 56, 34), (36, 28, 18), 230, (255, 170, 60), 255, (150, 120, 70), os.path.join(wid, "button_highlighted.png"), glow=(255, 150, 40))
button((24, 25, 28), (14, 15, 17), 170, (44, 46, 50), 200, (34, 36, 40), os.path.join(wid, "button_disabled.png"))
meta = '{\n\t"gui": {\n\t\t"scaling": {\n\t\t\t"type": "nine_slice",\n\t\t\t"width": 200,\n\t\t\t"height": 20,\n\t\t\t"border": 3\n\t\t}\n\t}\n}\n'
for n in ("button", "button_highlighted", "button_disabled"):
    with open(os.path.join(wid, n + ".png.mcmeta"), "w") as f:
        f.write(meta)
print("ok")
