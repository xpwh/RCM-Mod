"""C4 and firing-device inventory icons from the Blender renders (tools/structures/c4.py --icon): cropped to the
model, brought down to 16 x 16, the edge cut hard (no half-transparent fringe) and a dark outline round it like
the other icons.

usage: python3 tools/gen_c4_icons.py <dir with icon_c4.png and icon_c4_detonator.png>
"""
import sys

import numpy as np
from PIL import Image, ImageEnhance

OUT = "src/main/resources/assets/ballisticmissiles/textures/item/"


def icon(src, dst):
    im = Image.open(src).convert("RGBA")
    a = np.array(im)[..., 3]
    ys, xs = np.where(a > 10)
    box = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    im = im.crop(box)
    s = max(im.size)
    sq = Image.new("RGBA", (s, s))
    sq.paste(im, ((s - im.size[0]) // 2, (s - im.size[1]) // 2))
    sq = ImageEnhance.Brightness(sq).enhance(0.6)
    sq = ImageEnhance.Color(sq).enhance(1.7)
    sq = ImageEnhance.Contrast(sq).enhance(1.35)
    small = np.array(sq.resize((14, 14), Image.LANCZOS)).astype(float)
    out = np.zeros((16, 16, 4))
    out[1:15, 1:15] = small
    alpha = out[..., 3] > 90
    rgb = out[..., :3]
    # a dark outline where the model meets the air
    edge = np.zeros_like(alpha)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        edge |= np.roll(alpha, (dy, dx), (0, 1))
    edge &= ~alpha
    res = np.zeros((16, 16, 4), np.uint8)
    res[alpha, :3] = np.clip(rgb[alpha], 0, 255)
    res[alpha, 3] = 255
    res[edge] = (24, 24, 20, 255)
    Image.fromarray(res, "RGBA").save(dst)


icon(sys.argv[1] + "/icon_c4.png", OUT + "c4.png")
icon(sys.argv[1] + "/icon_c4_detonator.png", OUT + "c4_detonator.png")
