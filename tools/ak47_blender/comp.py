"""Lay the transparent renders over a dark studio gradient."""
import sys
import numpy as np
from PIL import Image
for p in sys.argv[1:]:
    im = Image.open(p).convert("RGBA")
    w, h = im.size
    y, x = np.mgrid[0:h, 0:w]
    r = np.sqrt(((x - w * 0.5) / w) ** 2 + ((y - h * 0.42) / h) ** 2 * 1.6)
    g = np.clip(1.0 - r * 1.25, 0, 1) ** 1.6
    top = np.array([12, 13, 15]); mid = np.array([58, 60, 64])
    bg = (top + (mid - top) * g[..., None]).astype(np.uint8)
    bgi = Image.fromarray(np.dstack([bg, np.full((h, w), 255, np.uint8)]), "RGBA")
    out = Image.alpha_composite(bgi, im).convert("RGB")
    out.save(p.replace(".png", ".jpg"), quality=93)
