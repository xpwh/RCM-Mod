"""9.20 wet blood and what comes to a body left lying.

  textures/entity/gore_gloss.png       how wet each texel of gore.png is (alpha), for the shine on wounds and organs
  textures/effect/blood_ground_gloss.png  the same for blood_ground.png, the stains on the ground
  textures/effect/fly.png              a blowfly seen from above, 2 frames (wings up / a blur of wings), 32 x 16
  textures/effect/stench.png           a soft, torn wisp of foul air rising off a corpse, 64 x 64

The gloss maps are white; their alpha says how much of the light's reflection a texel shows: blood, raw
meat, gut and the organs are wet through and shine, bone a little, cloth and skin hardly at all. A wet
surface never shines evenly - fine bumps and clots break the highlight up into glints - so the wetness
carries a fine grain and a scatter of bright points.
"""
import os

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures")
CLIENT = os.path.join(os.path.dirname(__file__), "..", "src", "client", "resources", "assets", "ballisticmissiles", "textures")


def _hash(ix, iy, seed):
    h = (ix * 374761393 + iy * 668265263 + seed * 144665) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def vnoise(x, y, seed=0):
    xi, yi = np.floor(x).astype(np.int64), np.floor(y).astype(np.int64)
    xf, yf = x - xi, y - yi
    u, v = xf * xf * (3 - 2 * xf), yf * yf * (3 - 2 * yf)
    a, b = _hash(xi, yi, seed), _hash(xi + 1, yi, seed)
    c, d = _hash(xi, yi + 1, seed), _hash(xi + 1, yi + 1, seed)
    return a * (1 - u) * (1 - v) + b * u * (1 - v) + c * (1 - u) * v + d * u * v


def fbm(x, y, seed=0, octaves=4):
    s, a, f = 0.0, 0.5, 1.0
    for o in range(octaves):
        s = s + a * vnoise(x * f, y * f, seed + o * 31)
        a *= 0.5
        f *= 2.0
    return s


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def grain(h, w, seed, scale):
    """The fine unevenness of a wet surface: mostly 0.55..1, with bright points where a bump catches the light."""
    y, x = np.mgrid[0:h, 0:w].astype(float)
    g = 0.55 + 0.45 * fbm(x / scale, y / scale, seed)
    pts = smoothstep(0.8, 0.9, fbm(x / (scale * 0.35), y / (scale * 0.35), seed + 7, octaves=2))
    return np.clip(g + 0.6 * pts, 0, 1.3)


def redness(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    return np.clip((r - np.maximum(g, b)) / 80.0, 0, 1) * smoothstep(30, 60, r)


def gore_gloss():
    src = np.asarray(Image.open(os.path.join(ROOT, "entity", "gore.png")).convert("RGBA")).astype(float)
    T = 128
    out = np.zeros_like(src)
    # how wet each kind of tile is all over (None: wherever it is bloody)
    wet = {30: 1.0, 31: 0.35, 32: 0.35, 33: 0.8, 34: 0.9, 35: None, 36: 1.0, 37: 0.85, 38: 1.0, 39: 1.0, 40: 0.95, 41: 0.75,
           64: 0.6, 65: 1.0, 66: 1.0, 67: 1.0, 77: 1.0, 78: 0.85, 79: 1.0, 80: 0.9}
    for tile in range((src.shape[0] // T) * 8):
        r0, c0 = (tile // 8) * T, (tile % 8) * T
        t = src[r0:r0 + T, c0:c0 + T]
        level = wet.get(tile)
        if level is None:
            w = redness(t[..., :3])
        else:
            # a bone splinter is wet only where blood is on it
            w = np.maximum(level, redness(t[..., :3]) * 0.9) if level < 0.5 else np.full((T, T), level)
        w = w * grain(T, T, 300 + tile, 9.0) * (t[..., 3] / 255.0)
        out[r0:r0 + T, c0:c0 + T, :3] = (255, 250, 244)
        out[r0:r0 + T, c0:c0 + T, 3] = np.clip(w, 0, 1) * 255
    Image.fromarray(out.astype(np.uint8), "RGBA").save(os.path.join(ROOT, "entity", "gore_gloss.png"), optimize=True)


def ground_gloss():
    src = np.asarray(Image.open(os.path.join(CLIENT, "effect", "blood_ground.png")).convert("RGBA")).astype(float)
    h, w = src.shape[:2]
    a = src[..., 3] / 255.0
    # deep blood (dark) stands wet longest and reflects as a mirror; thin films at the edge hardly at all
    dark = 1.0 - np.clip(src[..., 0] / 200.0, 0, 1)
    wet = smoothstep(0.35, 0.9, a) * (0.5 + 0.5 * dark)
    wet = wet * grain(h, w, 500, 14.0)
    out = np.zeros_like(src)
    out[..., :3] = (255, 250, 244)
    out[..., 3] = np.clip(wet, 0, 1) * 255
    Image.fromarray(out.astype(np.uint8), "RGBA").save(os.path.join(CLIENT, "effect", "blood_ground_gloss.png"), optimize=True)


def fly():
    """A blowfly from above, 16 x 16 per frame: a dark, glinting body, red-brown eyes, wings."""
    img = np.zeros((16, 32, 4), float)
    y, x = np.mgrid[0:16, 0:16].astype(float) + 0.5
    for f in range(2):
        cx, cy = 8.0, 8.0
        # wings: frame 0 laid back over the body, frame 1 a pale blur out to the sides
        if f == 0:
            for s in (-1, 1):
                ex, ey = (x - (cx + s * 2.2)) / 2.2, (y - (cy + 2.0)) / 4.2
                m = (ex * ex + ey * ey) < 1
                img[:, f * 16:(f + 1) * 16][m] = (175, 185, 190, 120)
        else:
            for s in (-1, 1):
                ex, ey = (x - (cx + s * 4.2)) / 3.6, (y - (cy + 0.5)) / 2.6
                m = (ex * ex + ey * ey) < 1
                img[:, f * 16:(f + 1) * 16][m] = (190, 195, 200, 70)
        # thorax and abdomen, dark metallic
        ex, ey = (x - cx) / 2.0, (y - (cy + 1.6)) / 3.4
        body = (ex * ex + ey * ey) < 1
        sheen = np.clip(1.0 - ((x - cx + 0.6) ** 2 + (y - cy) ** 2) / 6.0, 0, 1)
        col = np.stack([28 + 40 * sheen, 34 + 55 * sheen, 30 + 40 * sheen, np.full_like(x, 255)], -1)
        tile = img[:, f * 16:(f + 1) * 16]
        tile[body] = col[body]
        # the head with its big red-brown eyes
        for s in (-1, 1):
            ex, ey = (x - (cx + s * 1.0)) / 1.1, (y - (cy - 2.4)) / 1.0
            m = (ex * ex + ey * ey) < 1
            tile[m] = (120, 30, 22, 255)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(os.path.join(CLIENT, "effect", "fly.png"))


def stench():
    T = 64
    y, x = np.mgrid[0:T, 0:T].astype(float) + 0.5
    u, v = x / T * 2 - 1, y / T * 2 - 1
    r = np.sqrt(u * u + v * v)
    n = fbm(x / 9.0, y / 9.0, 900) * 0.7 + fbm(x / 4.0, y / 4.0, 910, octaves=2) * 0.3
    a = smoothstep(1.0, 0.25, r + (n - 0.5) * 0.9) * smoothstep(0.28, 0.6, n)
    out = np.zeros((T, T, 4))
    out[..., :3] = 255
    out[..., 3] = np.clip(a, 0, 1) * 255
    Image.fromarray(out.astype(np.uint8), "RGBA").save(os.path.join(CLIENT, "effect", "stench.png"))


if __name__ == "__main__":
    gore_gloss()
    ground_gloss()
    fly()
    stench()
