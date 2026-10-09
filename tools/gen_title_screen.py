"""9.4 title screen art.

  textures/gui/title_background.png   640x360 pixel-art dusk over a missile base: stars, a sunset glow
                                       behind blue mountains, two fighters drawing contrails, a distant
                                       mushroom cloud, a launch gantry with its missile, a radar dish,
                                       a fence line (drawn up crisp, Minecraft style, by the title screen)
  minecraft:gui/sprites/widget/button*.png   dark gunmetal buttons with a thin border; amber when hovered
python3 gen_title_screen.py
"""
import os

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets")
rng = np.random.default_rng(1983)
W, H = 640, 360
HORIZON = 250


def lerp(a, b, t):
    return np.array(a, float) * (1 - t) + np.array(b, float) * t


# ---------------------------------------------------------------------------- sky
img = np.zeros((H, W, 3))
stops = [(0, (6, 9, 24)), (120, (24, 32, 64)), (200, (88, 62, 86)), (235, (196, 106, 70)), (HORIZON, (246, 150, 72))]
for y in range(H):
    for (y0, c0), (y1, c1) in zip(stops, stops[1:]):
        if y0 <= y <= y1:
            img[y, :] = lerp(c0, c1, (y - y0) / max(1, y1 - y0))
            break
    else:
        img[y, :] = stops[-1][1]
yy, xx = np.mgrid[0:H, 0:W]
glow = np.exp(-(((xx - 430) / 150.0) ** 2 + ((yy - HORIZON) / 60.0) ** 2))
img += glow[..., None] * np.array([90, 45, 10])
# stars, fading towards the glow
for _ in range(260):
    x, y = rng.integers(0, W), rng.integers(0, 170)
    b = rng.uniform(120, 255) * (1 - y / 190.0)
    img[y, x] = np.maximum(img[y, x], b)
# ---------------------------------------------------------------------------- contrails and fighters
pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
d = ImageDraw.Draw(pil, "RGBA")
for (x0, y0, x1, y1) in [(40, 110, 330, 46), (60, 122, 345, 58)]:
    for k in range(40):
        t0, t1 = k / 40, (k + 1) / 40
        a = int(20 + 120 * t1)
        d.line([(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0), (x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1)], fill=(225, 225, 235, a), width=2)
    # the jet at the head of the trail: a small dark arrow
    d.polygon([(x1 + 8, y1 - 2), (x1 - 3, y1 - 6), (x1 - 1, y1 - 1), (x1 - 3, y1 + 3)], fill=(20, 22, 30, 255))
# distant mushroom cloud on the horizon, lit from below
cx = 118
d.rectangle([cx - 5, 196, cx + 5, HORIZON - 8], fill=(150, 92, 76, 150))
d.ellipse([cx - 34, 168, cx + 34, 204], fill=(170, 104, 84, 170))
d.ellipse([cx - 24, 162, cx + 24, 188], fill=(196, 122, 92, 170))
d.ellipse([cx - 44, 236, cx + 44, HORIZON + 4], fill=(150, 90, 70, 120))
img = np.array(pil).astype(float)
# ---------------------------------------------------------------------------- mountains


def ridge(base, amp, freq, seed):
    r = np.random.default_rng(seed)
    x = np.arange(W)
    y = np.zeros(W)
    for f, a in zip(freq, amp):
        y += a * np.sin(x * f + r.uniform(0, 6.28))
    return base + y


far = ridge(226, (10, 5, 2), (0.012, 0.031, 0.09), 1)
mid = ridge(246, (8, 4, 2), (0.017, 0.043, 0.11), 2)
for x in range(W):
    img[int(far[x]):, x] = lerp(img[int(far[x]), x], (70, 60, 92), 0.75)
    img[int(mid[x]):, x] = (34, 30, 50)
ground = ridge(266, (3, 1.5), (0.02, 0.07), 3)
for x in range(W):
    g = int(ground[x])
    img[g:, x] = (12, 12, 18)
    img[g, x] = (70, 42, 30)  # rim light from the sunset
pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
d = ImageDraw.Draw(pil)
dark = (9, 9, 13)
# ---------------------------------------------------------------------------- the launch gantry with its missile
gx = 470
d.rectangle([gx - 14, 300, gx + 70, 312], fill=dark)  # pad
for px in (gx, gx + 26):
    d.rectangle([px, 150, px + 3, 302], fill=dark)
for y in range(156, 300, 12):
    d.line([(gx, y), (gx + 28, y + 12)], fill=dark)
    d.line([(gx + 28, y), (gx, y + 12)], fill=dark)
    d.line([(gx, y), (gx + 28, y)], fill=dark)
d.rectangle([gx - 2, 148, gx + 31, 152], fill=dark)
for y in (190, 240):  # swing arms to the missile
    d.rectangle([gx + 28, y, gx + 40, y + 2], fill=dark)
mx = gx + 42
d.rectangle([mx, 176, mx + 9, 300], fill=(18, 18, 24))
d.polygon([(mx, 176), (mx + 9, 176), (mx + 4.5, 156)], fill=(18, 18, 24))
d.polygon([(mx, 300), (mx - 5, 300), (mx, 286)], fill=(18, 18, 24))
d.polygon([(mx + 9, 300), (mx + 14, 300), (mx + 9, 286)], fill=(18, 18, 24))
d.line([(mx + 9, 178), (mx + 9, 298)], fill=(64, 40, 32))  # sunset catching its flank
# ---------------------------------------------------------------------------- radar dish
rx = 150
d.rectangle([rx - 2, 262, rx + 2, 300], fill=dark)
d.rectangle([rx - 14, 296, rx + 14, 304], fill=dark)
# a parabolic dish tilted up to the sky, with its feed horn on a tripod
import math
pts = []
for k in range(0, 21):
    u = -1 + k / 10.0
    x, y = u * 24, 8 * u * u  # bowl profile, opening upwards
    c, sn = math.cos(-0.6), math.sin(-0.6)
    pts.append((rx + x * c - y * sn, 262 - (x * sn + y * c) - 6))
d.polygon(pts, fill=dark)
d.line([(rx, 258), (rx + 14, 236)], fill=dark, width=2)
d.line([(rx - 12, 266), (rx + 14, 236)], fill=dark, width=1)
d.rectangle([rx + 12, 233, rx + 16, 237], fill=dark)
# ---------------------------------------------------------------------------- bunker, silo hatch, fence
d.polygon([(250, 312), (262, 296), (330, 296), (342, 312)], fill=dark)
d.rectangle([286, 302, 306, 312], fill=(30, 30, 36))
d.rectangle([552, 309, 580, 313], fill=(30, 30, 36))  # open silo door (the launch comes from here)
for x in range(0, W, 14):
    d.rectangle([x, 318, x + 1, 334], fill=dark)
d.line([(0, 321), (W, 321)], fill=dark)
d.line([(0, 327), (W, 327)], fill=dark)
out = os.path.join(ROOT, "ballisticmissiles", "textures", "gui")
os.makedirs(out, exist_ok=True)
pil.save(os.path.join(out, "title_background.png"))

# ---------------------------------------------------------------------------- buttons


def button(fill, edge, top, path):
    b = np.zeros((20, 200, 4))
    b[..., :3] = fill
    b[..., 3] = 235
    noise = rng.normal(0, 2.0, (20, 200, 1))
    b[..., :3] += noise
    b[1, 1:-1, :3] = top  # bevel highlight
    b[0, :, :3] = edge
    b[-1, :, :3] = edge
    b[:, 0, :3] = edge
    b[:, -1, :3] = edge
    b[-2, 1:-1, :3] = np.array(fill) * 0.6
    b[0, :, 3] = b[-1, :, 3] = b[:, 0, 3] = b[:, -1, 3] = 255
    Image.fromarray(np.clip(b, 0, 255).astype(np.uint8), "RGBA").save(path)


wid = os.path.join(ROOT, "minecraft", "textures", "gui", "sprites", "widget")
os.makedirs(wid, exist_ok=True)
button((38, 42, 46), (12, 13, 15), (70, 76, 82), os.path.join(wid, "button.png"))
button((62, 58, 40), (240, 168, 48), (110, 100, 64), os.path.join(wid, "button_highlighted.png"))
button((26, 27, 29), (10, 10, 12), (36, 37, 40), os.path.join(wid, "button_disabled.png"))
meta = '{\n\t"gui": {\n\t\t"scaling": {\n\t\t\t"type": "nine_slice",\n\t\t\t"width": 200,\n\t\t\t"height": 20,\n\t\t\t"border": 3\n\t\t}\n\t}\n}\n'
for n in ("button", "button_highlighted", "button_disabled"):
    with open(os.path.join(wid, n + ".png.mcmeta"), "w") as f:
        f.write(meta)
print("ok")
