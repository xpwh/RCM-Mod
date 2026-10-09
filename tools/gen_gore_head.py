"""9.12 head wounds: textures/entity/gore_head.png (512 x 512, tiles of 128 x 128).

Every face of the head (8 x 8 skin pixels) gets a 128 x 128 decal painted from 3D: each texel is
the point it covers on the head (head space, in pixels: x -4..4 with +X the player's left, y -8 crown
.. 0 jaw, z -4 face .. 4 back), and the wounds are fields in that space - so a crater or a stream of
blood runs on across the edge from one face to the next, and blood runs down (+y).

Tiles (column, row):
  (0,0) graze front   (1,0) graze left   (2,0) graze top    (3,0) graze back
  (0,1) shot front    (1,1) shot right   (2,1) shot top     (3,1) shot back
  (0,2) shot left     (1,2) brain        (2,2) bone shard   (3,2) scalp flap
Face texel -> point: front/back (x, y): u = (x+4)/8, v = (y+8)/8; left/right (z, y): u = (z+4)/8,
v = (y+8)/8; top (x, z): u = (x+4)/8, v = (z+4)/8. GoreHead.java maps its quads the same way.
"""
import os

import numpy as np
from PIL import Image

T = 128
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "entity",
                   "gore_head.png")
rng = np.random.default_rng(2024)

# ---------------------------------------------------------------- noise


def _hash(ix, iy, iz, seed):
    h = (ix * 374761393 + iy * 668265263 + iz * 2147483647 + seed * 144665) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def vnoise(x, y, z, seed=0):
    xi, yi, zi = np.floor(x).astype(np.int64), np.floor(y).astype(np.int64), np.floor(z).astype(np.int64)
    xf, yf, zf = x - xi, y - yi, z - zi
    u, v, w = xf * xf * (3 - 2 * xf), yf * yf * (3 - 2 * yf), zf * zf * (3 - 2 * zf)
    out = 0.0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                h = _hash(xi + dx, yi + dy, zi + dz, seed)
                out = out + h * (u if dx else 1 - u) * (v if dy else 1 - v) * (w if dz else 1 - w)
    return out


def fbm(x, y, z, seed=0, octaves=4):
    a, f, s = 0.5, 1.0, 0.0
    for o in range(octaves):
        s = s + a * vnoise(x * f, y * f, z * f, seed + o * 17)
        a *= 0.5
        f *= 2.03
    return s


# ---------------------------------------------------------------- face texel -> head point

g = (np.arange(T) + 0.5) / T


def face_points(face):
    u, v = np.meshgrid(g, g)
    a = u * 8 - 4
    b = v * 8 - 8
    if face == "front":
        return a, b, np.full_like(a, -4.0)
    if face == "back":
        return a, b, np.full_like(a, 4.0)
    if face == "left":
        return np.full_like(a, 4.0), b, a
    if face == "right":
        return np.full_like(a, -4.0), b, a
    if face == "top":
        return a, np.full_like(a, -8.0), v * 8 - 4
    raise ValueError(face)


# ---------------------------------------------------------------- colours

BLOOD = np.array([96, 6, 10], float)
BLOOD_DARK = np.array([52, 2, 5], float)
BLOOD_FRESH = np.array([150, 14, 18], float)
FLESH = np.array([150, 28, 30], float)
FAT = np.array([222, 190, 128], float)
DERMIS = np.array([206, 112, 104], float)
BONE = np.array([228, 216, 190], float)
BRAIN = np.array([226, 160, 158], float)


class Layer:
    """An RGBA image built up by painting colours with coverage over what is there."""

    def __init__(self):
        self.rgb = np.zeros((T, T, 3))
        self.a = np.zeros((T, T))

    def paint(self, color, cover):
        cover = np.clip(cover, 0.0, 1.0)
        if np.ndim(color) == 1:
            color = np.broadcast_to(color, (T, T, 3))
        self.rgb = self.rgb * (1 - cover[..., None]) + color * cover[..., None]
        self.a = self.a + (1 - self.a) * cover

    def image(self):
        rgb = np.clip(self.rgb, 0, 255)
        return np.dstack([rgb, np.clip(self.a, 0, 1) * 255]).astype(np.uint8)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def wet(color, x, y, z, seed):
    """Blood is never one flat colour: darker where it pooled and clotted, a wet sheen on top."""
    n = fbm(x * 1.7, y * 1.7, z * 1.7, seed)
    c = color[None, None, :] * (0.75 + 0.5 * n[..., None])
    sheen = smoothstep(0.62, 0.75, fbm(x * 3.1 + 7, y * 3.1, z * 3.1, seed + 3))
    return c + sheen[..., None] * np.array([70, 40, 40])


def streams(layer, paths, coord, y, seed):
    """Runs of blood: each path a list of (across, y) points on this face, its width shrinking as it runs dry."""
    for k, (pts, w0) in enumerate(paths):
        pts = np.array(pts, float)
        cover = np.zeros((T, T))
        total = np.sum(np.linalg.norm(np.diff(pts, axis=0), axis=1))
        run = 0.0
        for i in range(len(pts) - 1):
            p, q = pts[i], pts[i + 1]
            seg = q - p
            L = np.linalg.norm(seg)
            t = np.clip(((coord - p[0]) * seg[0] + (y - p[1]) * seg[1]) / (L * L), 0, 1)
            dx = coord - (p[0] + seg[0] * t)
            dy = y - (p[1] + seg[1] * t)
            d = np.sqrt(dx * dx + dy * dy)
            along = (run + t * L) / total
            w = w0 * (1.0 - 0.55 * along) * (0.8 + 0.4 * vnoise(coord * 2, y * 5, k * 5.0, seed + k))
            cover = np.maximum(cover, smoothstep(w, w * 0.55, d))
            run += L
        # a drop gathered at the end
        end = pts[-1]
        dd = np.sqrt((coord - end[0]) ** 2 + ((y - end[1]) * 0.8) ** 2)
        cover = np.maximum(cover, smoothstep(w0 * 0.85, w0 * 0.4, dd))
        col = wet(BLOOD, coord, y, np.full_like(y, k * 3.0), seed + 11 * k)
        # the edge of a run dries darker
        edge = smoothstep(0.3, 0.9, cover) * (1 - smoothstep(0.9, 1.0, cover))
        col = col * (1 - 0.35 * edge[..., None])
        layer.paint(col, cover * 0.96)


def wander(rs, x0, y0, y1, step=0.6, sway=0.05):
    pts = [(x0, y0)]
    x = x0
    y = y0
    while y < y1:
        y += step
        x += rs.normal(0, sway)
        pts.append((x, min(y, y1)))
    return pts


def spatter(layer, x, y, z, n, center, radius, seed, size=(0.06, 0.2)):
    rs = np.random.default_rng(seed)
    cover = np.zeros((T, T))
    for i in range(n):
        d = rs.normal(0, radius, 3)
        c = center + d
        r = rs.uniform(*size)
        dist = np.sqrt((x - c[0]) ** 2 + (y - c[1]) ** 2 + (z - c[2]) ** 2)
        cover = np.maximum(cover, smoothstep(r, r * 0.4, dist))
    layer.paint(wet(BLOOD_DARK * 1.4, x, y, z, seed), cover * 0.9)


# ---------------------------------------------------------------- the graze: a furrow across the left side

FURROW = (np.array([4.0, -6.45, -2.7]), np.array([4.0, -6.2, 1.9]))


def furrow_field(x, y, z):
    """Distance from the furrow's line (on the left side), as a fraction of its half-width there."""
    a, b = FURROW
    ab = b - a
    t = np.clip(((z - a[2]) * ab[2] + (y - a[1]) * ab[1]) / (ab[1] ** 2 + ab[2] ** 2), 0, 1)
    dy = y - (a[1] + ab[1] * t)
    dz = z - (a[2] + ab[2] * t)
    hw = 0.85 * np.sqrt(np.clip(np.sin(np.pi * t), 0, 1)) + 0.06
    hw = hw * (0.8 + 0.45 * fbm(z * 2.3, y * 2.3, x, 5))
    on_side = smoothstep(3.6, 3.95, x)
    return np.sqrt(dy * dy + (dz * 0.35) ** 2) / hw + (1 - on_side) * 10, t


def graze_tile(face):
    x, y, z = face_points(face)
    L = Layer()
    f, t = furrow_field(x, y, z)
    # bruised, blood-smeared skin round it
    L.paint(wet(BLOOD * 1.1, x, y, z, 1), smoothstep(2.2, 1.0, f) * 0.5 * (0.5 + fbm(x * 3, y * 3, z * 3, 0)))
    # torn skin edge, the pale fat under it, raw tissue, the skull at the bottom of the furrow
    L.paint(DERMIS * (0.85 + 0.3 * fbm(x * 4, y * 4, z * 4, 2))[..., None], smoothstep(1.08, 0.95, f))
    L.paint(FAT * 0.8 * (0.85 + 0.25 * fbm(x * 5, y * 5, z * 5, 3))[..., None], smoothstep(0.9, 0.84, f) * 0.8)
    L.paint(wet(FLESH, x * 2, y * 2, z * 2, 4), smoothstep(0.86, 0.72, f))
    bone = BONE * (0.82 + 0.25 * fbm(x * 6, y * 9, z * 2, 6))[..., None] - smoothstep(0.55, 0.7, fbm(z * 8, y * 8, x, 7))[..., None] * 40
    L.paint(bone, smoothstep(0.42, 0.3, f))
    L.paint(wet(BLOOD_FRESH, x, y, z, 8), smoothstep(0.2, 0.05, np.abs(f - 0.5)) * 0.5)
    if face == "left":
        rs = np.random.default_rng(31)
        paths = []
        for z0, end in ((-1.6, -0.2), (-0.2, -3.4), (0.9, -1.6)):
            paths.append((wander(rs, z0, -5.9, end), rs.uniform(0.18, 0.34)))
        streams(L, paths, z, y, 40)
        spatter(L, x, y, z, 26, np.array([4.0, -6.5, -0.3]), 1.3, 41)
    elif face == "front":
        rs = np.random.default_rng(32)
        # round the temple onto the face and down the cheek, one through the eyebrow
        paths = [(wander(rs, 3.55, -6.3, -0.6, sway=0.08), 0.32), (wander(rs, 2.6, -5.6, -2.4, sway=0.1), 0.22),
                 (wander(rs, 3.2, -2.0, 0.0, sway=0.06), 0.26)]
        streams(L, paths, x, y, 50)
        spatter(L, x, y, z, 10, np.array([3.6, -5.8, -4.0]), 0.6, 51)
    elif face == "top":
        # blood matted into the hair above it
        m = smoothstep(2.4, 0.4, np.sqrt((x - 4.0) ** 2 + (z + 0.3) ** 2 * 0.25)) * smoothstep(0.35, 0.6, fbm(x * 2.5, z * 2.5, 0, 9))
        L.paint(wet(BLOOD_DARK * 1.2, x, y, z, 10), m * 0.85)
    elif face == "back":
        rs = np.random.default_rng(33)
        streams(L, [(wander(rs, 3.7, -6.0, -2.5, sway=0.05), 0.2)], x, y, 60)
    return L.image()


# ---------------------------------------------------------------- the skull blown open

CRATER = np.array([-3.4, -7.9, 2.7])
ENTRY = np.array([0.9, -6.0, -4.0])


def crater_field(x, y, z):
    """Distance from the crater's centre over its (ragged) radius: below 1 the head is open."""
    d = np.stack([x - CRATER[0], y - CRATER[1], z - CRATER[2]], -1)
    r = np.linalg.norm(d, axis=-1) + 1e-6
    n = d / r[..., None]
    rag = fbm(n[..., 0] * 3.0 + 3, n[..., 1] * 3.0, n[..., 2] * 3.0, 21) * 1.4 + fbm(n[..., 0] * 9, n[..., 1] * 9, n[..., 2] * 9, 22) * 0.5
    return r / (1.85 + 0.62 * rag)


def shot_tile(face):
    x, y, z = face_points(face)
    L = Layer()
    c = crater_field(x, y, z)
    # round the hole: soaked scalp, torn skin and fat, the cut edge of the skull, then the inside
    L.paint(wet(BLOOD * 1.1, x, y, z, 70), smoothstep(1.45, 1.03, c) * (0.55 + 0.45 * fbm(x * 3, y * 3, z * 3, 69)))
    L.paint(DERMIS * (0.8 + 0.3 * fbm(x * 4, y * 4, z * 4, 71))[..., None], smoothstep(1.06, 1.0, c))
    L.paint(FAT * 0.75 * (0.85 + 0.2 * fbm(x * 5, y * 5, z * 5, 72))[..., None], smoothstep(0.99, 0.975, c) * 0.7)
    skull = BONE * (0.78 + 0.3 * fbm(x * 7, y * 7, z * 7, 73))[..., None]
    # the porous middle layer of the skull showing in the break, reddened
    skull = skull - smoothstep(0.55, 0.75, fbm(x * 11, y * 11, z * 11, 74))[..., None] * np.array([30, 70, 70])
    L.paint(skull, smoothstep(0.96, 0.9, c))
    inside = wet(FLESH * 0.75, x * 1.5, y * 1.5, z * 1.5, 75)
    folds = np.abs(np.sin(fbm(x * 1.6, y * 1.6, z * 1.6, 76) * 18.0))
    brain = BRAIN * (0.8 + 0.25 * folds)[..., None] - smoothstep(0.25, 0.05, folds)[..., None] * np.array([70, 70, 60])
    mix = smoothstep(0.4, 0.62, fbm(x * 1.3 + 4, y * 1.3, z * 1.3, 77))
    inside = inside * (1 - mix[..., None]) + brain * mix[..., None]
    L.paint(inside, smoothstep(0.9, 0.86, c))
    L.paint(wet(BLOOD_FRESH, x, y, z, 78), smoothstep(0.55, 0.75, fbm(x * 2.2, y * 2.2, z * 2.2, 79)) * smoothstep(0.9, 0.8, c) * 0.7)
    # bone splinters lying round the rim
    spl = smoothstep(0.7, 0.78, fbm(x * 5.5, y * 5.5, z * 5.5, 80)) * smoothstep(1.4, 1.05, c) * smoothstep(0.92, 1.0, c)
    L.paint(BONE * 0.95, spl)
    if face == "front":
        # the entry wound: a small round hole, a ring of torn and bruised skin, then out of it, the eyes, the nose
        r = np.sqrt((x - ENTRY[0]) ** 2 + (y - ENTRY[1]) ** 2)
        L.paint(np.array([88, 40, 70], float), smoothstep(1.2, 0.5, r) * 0.45)
        L.paint(DERMIS * 0.8, smoothstep(0.62, 0.5, r))
        L.paint(wet(BLOOD_DARK, x, y, z, 81), smoothstep(0.5, 0.36, r))
        L.paint(np.array([14, 4, 6], float), smoothstep(0.3, 0.2, r))
        rs = np.random.default_rng(90)
        paths = [(wander(rs, 0.9, -5.6, 0.0, sway=0.1), 0.38), (wander(rs, 0.6, -5.6, -1.0, sway=0.14), 0.26),
                 (wander(rs, 1.3, -5.5, -2.0, sway=0.12), 0.2),
                 # out of both eyes and the nose, over the mouth and off the chin
                 (wander(rs, -2.0, -3.3, 0.0, sway=0.08), 0.3), (wander(rs, 2.0, -3.3, -0.2, sway=0.08), 0.3),
                 (wander(rs, -0.4, -2.4, 0.0, sway=0.05), 0.34), (wander(rs, 0.4, -2.4, 0.0, sway=0.05), 0.3)]
        streams(L, paths, x, y, 91)
        spatter(L, x, y, z, 40, np.array([0.5, -4.5, -4.0]), 1.8, 92)
    elif face in ("right", "back"):
        rs = np.random.default_rng(100 if face == "right" else 110)
        coord = z if face == "right" else x
        lo = []
        for i in range(4):
            a = rs.uniform(0.6, 3.9) if face == "right" else rs.uniform(-3.9, -1.4)
            lo.append((wander(rs, a, -5.4 + rs.uniform(-0.5, 0.6), rs.uniform(-2.5, 0.6), sway=0.06), rs.uniform(0.14, 0.38)))
        streams(L, lo, coord, y, 101 if face == "right" else 111)
        spatter(L, x, y, z, 30, np.array([-3.0, -5.0, 2.5]), 1.6, 102 if face == "right" else 112)
    elif face == "top":
        spatter(L, x, y, z, 40, np.array([-1.0, -8.0, 0.5]), 2.0, 120)
    elif face == "left":
        spatter(L, x, y, z, 14, np.array([4.0, -6.0, 2.0]), 1.5, 130)
    return L.image()


# ---------------------------------------------------------------- loose parts

def brain_tile():
    u, v = np.meshgrid(g * 6, g * 6)
    # convolutions: a strongly warped stripe pattern, each gyrus rounded (height), lit from above-left
    wu = u + 1.8 * fbm(u * 0.5, v * 0.5, 0.5, 140) * 2
    wv = v + 1.8 * fbm(u * 0.5 + 9, v * 0.5, 0.5, 141) * 2
    h = np.abs(np.sin(wu * 1.6 + np.sin(wv * 1.3) * 1.6))
    h = h ** 0.45  # wide rounded gyri, narrow deep sulci
    gy, gx = np.gradient(h)
    light = np.clip(0.95 - gx * 4 - gy * 4, 0.55, 1.2)
    rgb = BRAIN[None, None] * (0.7 + 0.35 * h)[..., None] * light[..., None]
    vessels = smoothstep(0.025, 0.0, np.abs(fbm(u * 1.4, v * 1.4, 2.0, 142) - 0.5))
    rgb = rgb * (1 - 0.6 * vessels[..., None]) + vessels[..., None] * np.array([150, 24, 30]) * 0.6
    blood = smoothstep(0.6, 0.72, fbm(u * 0.8 + 5, v * 0.8, 1.0, 143)) + (1 - h) * 0.35
    blood = np.clip(blood, 0, 1)
    rgb = rgb * (1 - 0.75 * blood[..., None]) + blood[..., None] * BLOOD_FRESH * 0.75
    sheen = smoothstep(0.7, 0.85, fbm(u * 1.9, v * 1.9, 3.0, 144)) * h
    rgb = rgb + sheen[..., None] * 45
    return np.dstack([np.clip(rgb, 0, 255), np.full((T, T), 255)]).astype(np.uint8)


def ragged_alpha(seed, base_width=0.42, tip=0.06):
    """A jagged shard / flap outline: wide at the base (v = 1), torn to a ragged point at v = 0."""
    u, v = np.meshgrid(g, g)
    half = tip + (base_width - tip) * v ** 0.8
    edge = half * (0.75 + 0.5 * fbm(v * 9, u * 0.5, 0, seed))
    a = smoothstep(edge, edge - 0.02, np.abs(u - 0.5))
    a *= smoothstep(0.02, 0.06 + 0.08 * fbm(u * 12, 0, 1, seed + 1), v)
    return a, u, v


def bone_tile():
    a, u, v = ragged_alpha(150)
    rgb = BONE[None, None] * (0.8 + 0.25 * fbm(u * 6, v * 14, 0, 151))[..., None]
    porous = smoothstep(0.08, 0.0, np.abs(u - 0.5)) * smoothstep(0.55, 0.7, fbm(u * 30, v * 30, 1, 152))
    rgb = rgb - porous[..., None] * np.array([40, 90, 90])
    rgb = rgb * (1 - 0.6 * smoothstep(0.7, 1.0, v))[..., None] + smoothstep(0.7, 1.0, v)[..., None] * BLOOD * 0.6
    return np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)


def flap_tile():
    a, u, v = ragged_alpha(160, base_width=0.48, tip=0.18)
    rgb = DERMIS[None, None] * (0.75 + 0.35 * fbm(u * 5, v * 5, 0, 161))[..., None]
    fat = smoothstep(0.55, 0.7, fbm(u * 8, v * 8, 2, 162))
    rgb = rgb * (1 - fat[..., None] * 0.5) + fat[..., None] * FAT * 0.5
    blood = smoothstep(0.45, 0.6, fbm(u * 3, v * 3, 3, 163)) + smoothstep(0.75, 1.0, v)
    rgb = rgb * (1 - np.clip(blood, 0, 1)[..., None] * 0.75) + np.clip(blood, 0, 1)[..., None] * BLOOD * 0.75
    return np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)


def main():
    atlas = np.zeros((4 * T, 4 * T, 4), np.uint8)

    def put(col, row, img):
        atlas[row * T:(row + 1) * T, col * T:(col + 1) * T] = img

    for i, f in enumerate(["front", "left", "top", "back"]):
        put(i, 0, graze_tile(f))
    for i, f in enumerate(["front", "right", "top", "back"]):
        put(i, 1, shot_tile(f))
    put(0, 2, shot_tile("left"))
    put(1, 2, brain_tile())
    put(2, 2, bone_tile())
    put(3, 2, flap_tile())
    Image.fromarray(atlas, "RGBA").save(OUT)


if __name__ == "__main__":
    main()
