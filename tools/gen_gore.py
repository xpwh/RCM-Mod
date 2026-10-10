"""9.15 gore: textures/entity/gore.png (1024 x 2048 since 9.21: an 8 x 16 grid of 128 x 128 tiles).

Wound decals are painted from 3D: each texel is the point it covers on a body part (in that part's own
space, in skin pixels, +y down), and the wounds are fields in that space - so a crater or a run of blood
carries on round an edge onto the next face, and blood runs down (+y). Each kind of wound comes in
several versions (different places, shapes, runs), so no two look the same.

Head (x -4..4, +X the player's left; y -8 crown .. 0 jaw; z -4 face .. 4 back), faces in the order
front, left, right, top, back (u, v as the face is seen from outside... as GoreMesh maps them):
  front/back (x, y)  left/right (z, y)  top (x, z)
Torso (x -4..4, y 0 neck .. 12 waist, z -2 chest .. 2 back): front (x, y), back (x, y).

Tiles (index = row * 8 + column):
   0..14  head graze, versions 0-2 x 5 faces          15..29  head blown open, versions 0-2 x 5 faces
  30 brain  31 bone splinter  32 thin bone splinter  33 scalp flap  34 limb cross-section
  35 torn cloth  36 strand of blood  37 raw meat  38 intestine  39 liver  40 heart  41 lung
  42..59  torso wounds: 3 levels x 3 versions x (front, back)
  60..63  bullet wounds for animals, 4 versions
  64 teeth  65 tongue  66 the palate, raw  67 the throat at the back of a torn-open mouth
  68..76  the lower jaw shot away: the torn edge of what is left of the face, versions 0-2 x (front, left, right)
          (the cut itself is geometry: jaw_y / jaw_back here are GoreJaw's jawY / back in the mod)
"""
import os

import numpy as np
from PIL import Image

T = 128
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "textures", "entity", "gore.png")

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


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


# ---------------------------------------------------------------- faces

g = (np.arange(T) + 0.5) / T
UV = np.meshgrid(g, g)

# face: (axis held, value, u axis, u range, v axis, v range)
HEAD = {
    "front": (2, -4.0, 0, (-4, 4), 1, (-8, 0)),
    "left": (0, 4.0, 2, (-4, 4), 1, (-8, 0)),
    "right": (0, -4.0, 2, (-4, 4), 1, (-8, 0)),
    "top": (1, -8.0, 0, (-4, 4), 2, (-4, 4)),
    "back": (2, 4.0, 0, (-4, 4), 1, (-8, 0)),
}
HEAD_FACES = ["front", "left", "right", "top", "back"]
TORSO = {
    "front": (2, -2.0, 0, (-4, 4), 1, (0, 12)),
    "back": (2, 2.0, 0, (-4, 4), 1, (0, 12)),
}


def points(face):
    axis, value, ua, ur, va, vr = face
    u, v = UV
    p = [None, None, None]
    p[axis] = np.full_like(u, value)
    p[ua] = ur[0] + u * (ur[1] - ur[0])
    p[va] = vr[0] + v * (vr[1] - vr[0])
    return p[0], p[1], p[2]


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


def wet(color, x, y, z, seed):
    """Blood is never one flat colour: darker where it pooled and clotted, a wet sheen on top."""
    n = fbm(x * 1.7, y * 1.7, z * 1.7, seed)
    c = color[None, None, :] * (0.75 + 0.5 * n[..., None])
    sheen = smoothstep(0.62, 0.75, fbm(x * 3.1 + 7, y * 3.1, z * 3.1, seed + 3))
    return c + sheen[..., None] * np.array([70, 40, 40])


def run_paths(layer, paths, coord, y, seed):
    """Runs of blood: each path a list of (across, y) points on this face, its width shrinking as it runs dry."""
    for k, (pts, w0) in enumerate(paths):
        pts = np.array(pts, float)
        if len(pts) < 2:
            continue
        cover = np.zeros((T, T))
        total = max(1e-6, np.sum(np.linalg.norm(np.diff(pts, axis=0), axis=1)))
        run = 0.0
        for i in range(len(pts) - 1):
            p, q = pts[i], pts[i + 1]
            seg = q - p
            L = max(1e-6, np.linalg.norm(seg))
            t = np.clip(((coord - p[0]) * seg[0] + (y - p[1]) * seg[1]) / (L * L), 0, 1)
            d = np.sqrt((coord - (p[0] + seg[0] * t)) ** 2 + (y - (p[1] + seg[1] * t)) ** 2)
            along = (run + t * L) / total
            w = w0 * (1.0 - 0.55 * along) * (0.8 + 0.4 * vnoise(coord * 2, y * 5, k * 5.0, seed + k))
            cover = np.maximum(cover, smoothstep(w, w * 0.55, d))
            run += L
        end = pts[-1]
        dd = np.sqrt((coord - end[0]) ** 2 + ((y - end[1]) * 0.8) ** 2)
        cover = np.maximum(cover, smoothstep(w0 * 0.85, w0 * 0.4, dd))
        col = wet(BLOOD, coord, y, np.full_like(y, k * 3.0), seed + 11 * k)
        edge = smoothstep(0.3, 0.9, cover) * (1 - smoothstep(0.9, 1.0, cover))
        col = col * (1 - 0.35 * edge[..., None])
        layer.paint(col, cover * 0.96)


def wander(rs, x0, y0, y1, step=0.6, sway=0.05):
    pts = [(x0, y0)]
    x, y = x0, y0
    while y < y1:
        y += step
        x += rs.normal(0, sway)
        pts.append((x, min(y, y1)))
    return pts


def spatter(layer, x, y, z, n, center, radius, seed, size=(0.06, 0.2)):
    rs = np.random.default_rng(seed)
    cover = np.zeros((T, T))
    for i in range(n):
        c = center + rs.normal(0, radius, 3)
        r = rs.uniform(*size)
        dist = np.sqrt((x - c[0]) ** 2 + (y - c[1]) ** 2 + (z - c[2]) ** 2)
        cover = np.maximum(cover, smoothstep(r, r * 0.4, dist))
    layer.paint(wet(BLOOD_DARK * 1.4, x, y, z, seed), cover * 0.9)


def head_streams(layer, face_name, sources, x, y, z, rs, seed):
    """Blood running down from points on the head: on the face each starts on (a point on the crown runs
    over the nearest edge first), straight down, each its own length."""
    face = HEAD[face_name]
    axis, value, ua = face[0], face[1], face[2]
    coord = (x, y, z)[ua]
    paths = []
    for (p, w, length) in sources:
        p = np.array(p, float)
        if abs(p[1] + 8.0) < 0.25 and face_name != "top":
            # on the crown: over the edge onto the side it is nearest
            side = ("left" if p[0] > 0 else "right") if abs(p[0]) > abs(p[2]) else ("back" if p[2] > 0 else "front")
            if side != face_name:
                continue
            p = p.copy()
            p[axis] = value
            p[1] = -7.9
        elif face_name == "top" or abs(p[axis] - value) > 0.3:
            continue
        across = p[ua]
        paths.append((wander(rs, across, p[1], min(0.3, p[1] + length), sway=0.05), w))
    run_paths(layer, paths, coord, y, seed)


# ---------------------------------------------------------------- head: the graze (a furrow torn to the skull)

FURROWS = [
    (np.array([4.0, -6.45, -2.7]), np.array([4.0, -6.2, 1.9])),     # across the left side, above the ear
    (np.array([-3.0, -8.0, -1.4]), np.array([1.8, -8.0, -3.4])),    # over the crown at the front, onto the forehead
    (np.array([-3.6, -6.2, 4.0]), np.array([1.0, -6.8, 4.0])),      # across the back of the head
]


def furrow_field(v, x, y, z):
    a, b = FURROWS[v]
    ab = b - a
    p = np.stack([x, y, z], -1)
    t = np.clip(((p - a) @ ab) / (ab @ ab), 0, 1)
    close = a + t[..., None] * ab
    d = np.linalg.norm(p - close, axis=-1)
    hw = 0.85 * np.sqrt(np.clip(np.sin(np.pi * t), 0, 1)) + 0.06
    hw = hw * (0.8 + 0.45 * fbm(x * 2.3, y * 2.3, z * 2.3, 5 + v))
    return d / hw, t


def graze_tile(v, face_name):
    x, y, z = points(HEAD[face_name])
    L = Layer()
    f, t = furrow_field(v, x, y, z)
    L.paint(wet(BLOOD * 1.1, x, y, z, 1 + v), smoothstep(2.2, 1.0, f) * 0.5 * (0.5 + fbm(x * 3, y * 3, z * 3, v)))
    L.paint(DERMIS * (0.85 + 0.3 * fbm(x * 4, y * 4, z * 4, 2))[..., None], smoothstep(1.08, 0.95, f))
    L.paint(FAT * 0.8 * (0.85 + 0.25 * fbm(x * 5, y * 5, z * 5, 3))[..., None], smoothstep(0.9, 0.84, f) * 0.8)
    L.paint(wet(FLESH, x * 2, y * 2, z * 2, 4 + v), smoothstep(0.86, 0.72, f))
    bone = BONE * (0.82 + 0.25 * fbm(x * 6, y * 9, z * 2, 6))[..., None] - smoothstep(0.55, 0.7, fbm(z * 8, y * 8, x, 7))[..., None] * 40
    L.paint(bone, smoothstep(0.42, 0.3, f))
    L.paint(wet(BLOOD_FRESH, x, y, z, 8), smoothstep(0.2, 0.05, np.abs(f - 0.5)) * 0.5)
    # runs from along the lower lip of the furrow
    rs = np.random.default_rng(300 + v)
    a, b = FURROWS[v]
    sources = []
    for i in range(4):
        q = a + (b - a) * rs.uniform(0.15, 0.85)
        lip = q if abs(q[1] + 8.0) < 0.01 else q + np.array([0, 0.45, 0])  # on the crown it runs over the edge
        sources.append((lip, rs.uniform(0.16, 0.34), rs.uniform(1.5, 6.0)))
    head_streams(L, face_name, sources, x, y, z, rs, 310 + v)
    mid = (a + b) / 2
    spatter(L, x, y, z, 18, mid, 1.2, 320 + v)
    if face_name == "top":
        m = smoothstep(2.6, 0.4, np.linalg.norm(np.stack([x - mid[0], (y - mid[1]) * 0, z - mid[2]], -1), axis=-1)) \
            * smoothstep(0.35, 0.6, fbm(x * 2.5, z * 2.5, 0, 9 + v))
        L.paint(wet(BLOOD_DARK * 1.2, x, y, z, 10), m * 0.8)
    return L.image()


# ---------------------------------------------------------------- head: the skull blown open

CRATERS = [np.array([-3.4, -7.9, 2.7]), np.array([3.2, -7.6, 3.0]), np.array([-0.3, -8.0, 3.6])]
ENTRIES = [np.array([0.9, -6.0, -4.0]), np.array([-1.2, -5.6, -4.0]), np.array([0.2, -5.2, -4.0])]


def crater_radius(v, n):
    rag = fbm(n[..., 0] * 3.0 + 3 + v, n[..., 1] * 3.0, n[..., 2] * 3.0, 21 + v) * 1.4 + fbm(n[..., 0] * 9, n[..., 1] * 9, n[..., 2] * 9, 22) * 0.5
    return 1.85 + 0.62 * rag


def crater_field(v, x, y, z):
    c = CRATERS[v]
    d = np.stack([x - c[0], y - c[1], z - c[2]], -1)
    r = np.linalg.norm(d, axis=-1) + 1e-6
    return r / crater_radius(v, d / r[..., None])


def to_surface(p):
    q = np.clip(p, [-4, -8, -4], [4, 0, 4]).astype(float)
    dist = [4 - abs(q[0]), min(q[1] + 8, -q[1]), 4 - abs(q[2])]
    k = int(np.argmin(dist))
    if k == 0:
        q[0] = 4.0 if q[0] >= 0 else -4.0
    elif k == 1:
        q[1] = -8.0 if q[1] + 8 < -q[1] else 0.0
    else:
        q[2] = 4.0 if q[2] >= 0 else -4.0
    return q


def shot_tile(v, face_name):
    x, y, z = points(HEAD[face_name])
    L = Layer()
    c = crater_field(v, x, y, z)
    L.paint(wet(BLOOD * 1.1, x, y, z, 70 + v), smoothstep(1.45, 1.03, c) * (0.55 + 0.45 * fbm(x * 3, y * 3, z * 3, 69)))
    L.paint(DERMIS * (0.8 + 0.3 * fbm(x * 4, y * 4, z * 4, 71))[..., None], smoothstep(1.06, 1.0, c))
    L.paint(FAT * 0.75 * (0.85 + 0.2 * fbm(x * 5, y * 5, z * 5, 72))[..., None], smoothstep(0.99, 0.975, c) * 0.7)
    skull = BONE * (0.78 + 0.3 * fbm(x * 7, y * 7, z * 7, 73))[..., None]
    skull = skull - smoothstep(0.55, 0.75, fbm(x * 11, y * 11, z * 11, 74))[..., None] * np.array([30, 70, 70])
    L.paint(skull, smoothstep(0.96, 0.9, c))
    inside = wet(FLESH * 0.75, x * 1.5, y * 1.5, z * 1.5, 75 + v)
    folds = np.abs(np.sin(fbm(x * 1.6, y * 1.6, z * 1.6, 76) * 18.0))
    brain = BRAIN * (0.8 + 0.25 * folds)[..., None] - smoothstep(0.25, 0.05, folds)[..., None] * np.array([70, 70, 60])
    mix = smoothstep(0.4, 0.62, fbm(x * 1.3 + 4, y * 1.3, z * 1.3, 77 + v))
    inside = inside * (1 - mix[..., None]) + brain * mix[..., None]
    L.paint(inside, smoothstep(0.9, 0.86, c))
    L.paint(wet(BLOOD_FRESH, x, y, z, 78), smoothstep(0.55, 0.75, fbm(x * 2.2, y * 2.2, z * 2.2, 79)) * smoothstep(0.9, 0.8, c) * 0.7)
    spl = smoothstep(0.7, 0.78, fbm(x * 5.5, y * 5.5, z * 5.5, 80 + v)) * smoothstep(1.4, 1.05, c) * smoothstep(0.92, 1.0, c)
    L.paint(BONE * 0.95, spl)
    rs = np.random.default_rng(400 + v)
    # runs from the lower rim of the hole
    sources = []
    centre = CRATERS[v]
    for i in range(14):
        ang = rs.uniform(0, 2 * np.pi)
        d = np.array([np.cos(ang), abs(np.sin(ang)) * 0.8 + 0.2, rs.uniform(-1, 1)])
        d /= np.linalg.norm(d)
        r = float(crater_radius(v, d[None, None, :])[0, 0]) * 1.02
        p = to_surface(centre + d * r)
        sources.append((p, rs.uniform(0.15, 0.4), rs.uniform(1.5, 8.0)))
    head_streams(L, face_name, sources, x, y, z, rs, 410 + v)
    spatter(L, x, y, z, 30, centre + np.array([0, 2.0, 0]), 1.8, 420 + v)
    if face_name == "front":
        e = ENTRIES[v]
        r = np.sqrt((x - e[0]) ** 2 + (y - e[1]) ** 2)
        L.paint(np.array([88, 40, 70], float), smoothstep(1.2, 0.5, r) * 0.45)
        L.paint(DERMIS * 0.8, smoothstep(0.62, 0.5, r))
        L.paint(wet(BLOOD_DARK, x, y, z, 81), smoothstep(0.5, 0.36, r))
        L.paint(np.array([14, 4, 6], float), smoothstep(0.3, 0.2, r))
        paths = [(wander(rs, e[0], e[1] + 0.4, 0.0, sway=0.1), 0.38), (wander(rs, e[0] - 0.3, e[1] + 0.4, rs.uniform(-2.5, -0.5), sway=0.14), 0.26),
                 (wander(rs, -2.0, -3.3, rs.uniform(-1.0, 0.0), sway=0.08), 0.3), (wander(rs, 2.0, -3.3, rs.uniform(-1.0, 0.0), sway=0.08), 0.3),
                 (wander(rs, -0.4, -2.4, 0.0, sway=0.05), 0.34), (wander(rs, 0.4, -2.4, 0.0, sway=0.05), 0.3)]
        run_paths(L, paths, x, y, 91 + v)
        spatter(L, x, y, z, 40, np.array([e[0], -4.5, -4.0]), 1.8, 92 + v)
    return L.image()


# ---------------------------------------------------------------- torso: bullet holes in the chest and the back

def hole(L, x, y, z, cx, cy, r0, seed, exit_wound=False):
    """An entry wound (small, neat, a bruised ring) or an exit (ragged, flesh showing); the shirt soaking round it."""
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    ang = np.arctan2(y - cy, x - cx)
    rag = 1.0 + (0.45 if exit_wound else 0.12) * (fbm(np.cos(ang) * 2 + seed, np.sin(ang) * 2, 0, seed) - 0.5) * 2
    rr = d / (r0 * rag)
    soak = r0 * (5.0 if exit_wound else 3.6) * (0.75 + 0.5 * fbm(x * 1.4, y * 1.4, seed, seed + 1))
    L.paint(wet(BLOOD * 0.95, x, y, z, seed + 2), smoothstep(soak, soak * 0.45, d) * 0.85)
    if exit_wound:
        L.paint(DERMIS * 0.85, smoothstep(1.25, 1.1, rr))
        L.paint(FAT * 0.8, smoothstep(1.1, 1.0, rr) * 0.7)
        L.paint(wet(FLESH, x * 3, y * 3, z, seed + 3), smoothstep(1.0, 0.85, rr))
        L.paint(BONE * 0.9, smoothstep(0.66, 0.8, fbm(x * 9, y * 9, seed, seed + 4)) * smoothstep(0.9, 0.6, rr))
        L.paint(BLOOD_DARK, smoothstep(0.45, 0.2, rr))
    else:
        L.paint(np.array([90, 30, 50], float), smoothstep(2.0, 1.2, rr) * 0.35)
        L.paint(DERMIS * 0.7, smoothstep(1.35, 1.15, rr))
        L.paint(wet(BLOOD_DARK, x, y, z, seed + 5), smoothstep(1.15, 0.9, rr))
        L.paint(np.array([16, 3, 5], float), smoothstep(0.75, 0.5, rr))


def torso_tile(level, v, side):
    x, y, z = points(TORSO[side])
    L = Layer()
    rs = np.random.default_rng(500 + level * 10 + v * 2 + (side == "back"))
    holes = []
    if level == 1:
        holes = [(rs.uniform(-2.6, 2.6), rs.uniform(2.5, 8.0), 0.3)]
    elif level == 2:
        holes = [(rs.uniform(-3.0, 3.0), rs.uniform(2.0, 9.5), 0.3) for _ in range(rs.integers(2, 4))]
    else:
        # buckshot: a fist-sized group of small holes, the shirt soaked through
        cx, cy = rs.uniform(-1.8, 1.8), rs.uniform(3.0, 7.5)
        holes = [(cx + rs.normal(0, 0.9), cy + rs.normal(0, 1.0), 0.17) for _ in range(rs.integers(7, 11))]
        soak = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / (3.0 + 1.2 * fbm(x, y, v, 600 + v))
        L.paint(wet(BLOOD * 0.9, x, y, z, 601), smoothstep(1.0, 0.5, soak) * 0.85)
    if side == "back":
        # out the back: fewer, bigger and ragged - and not every round comes through
        holes = [(hx + rs.normal(0, 0.5), hy + rs.normal(0, 0.5), r * (2.2 if level < 3 else 1.6)) for (hx, hy, r) in holes if rs.random() < 0.7]
    for i, (hx, hy, r) in enumerate(holes):
        hole(L, x, y, z, hx, hy, r, 520 + i * 7 + v, exit_wound=side == "back")
    paths = []
    for (hx, hy, r) in holes:
        for k in range(1 + int(rs.random() < 0.6)):
            paths.append((wander(rs, hx + rs.normal(0, r * 0.6), hy + r, min(12.2, hy + rs.uniform(1.5, 7.0)), sway=0.06), rs.uniform(0.2, 0.42)))
    run_paths(L, paths, x, y, 540 + v)
    return L.image()


# ---------------------------------------------------------------- animals: a bullet wound, seen square-on (runs to v = 1)

def mob_tile(v):
    u, w = UV
    x, y = u * 4 - 2, w * 4 - 2
    z = np.zeros_like(x)
    L = Layer()
    rs = np.random.default_rng(700 + v)
    hole(L, x, y, z, rs.normal(0, 0.1), -0.8 + rs.normal(0, 0.1), 0.18 + 0.08 * v % 0.2, 710 + v, exit_wound=v == 3)
    run_paths(L, [(wander(rs, rs.normal(0, 0.15), -0.55, rs.uniform(0.6, 1.9), sway=0.05), rs.uniform(0.12, 0.2)) for _ in range(1 + v % 2)], x, y, 720 + v)
    # fade out at the tile's edge so the decal has no border
    edge = smoothstep(2.0, 1.6, np.maximum(np.abs(x), np.abs(y)))
    L.a = L.a * edge
    return L.image()


# ---------------------------------------------------------------- loose parts

def opaque(rgb):
    return np.dstack([np.clip(rgb, 0, 255), np.full((T, T), 255)]).astype(np.uint8)


def brain_tile():
    u, v = np.meshgrid(g * 6, g * 6)
    wu = u + 1.8 * fbm(u * 0.5, v * 0.5, 0.5, 140) * 2
    wv = v + 1.8 * fbm(u * 0.5 + 9, v * 0.5, 0.5, 141) * 2
    h = np.abs(np.sin(wu * 1.6 + np.sin(wv * 1.3) * 1.6)) ** 0.45
    gy, gx = np.gradient(h)
    light = np.clip(0.95 - gx * 4 - gy * 4, 0.55, 1.2)
    rgb = BRAIN[None, None] * (0.7 + 0.35 * h)[..., None] * light[..., None]
    vessels = smoothstep(0.025, 0.0, np.abs(fbm(u * 1.4, v * 1.4, 2.0, 142) - 0.5))
    rgb = rgb * (1 - 0.6 * vessels[..., None]) + vessels[..., None] * np.array([150, 24, 30]) * 0.6
    blood = np.clip(smoothstep(0.6, 0.72, fbm(u * 0.8 + 5, v * 0.8, 1.0, 143)) + (1 - h) * 0.35, 0, 1)
    rgb = rgb * (1 - 0.75 * blood[..., None]) + blood[..., None] * BLOOD_FRESH * 0.75
    rgb = rgb + (smoothstep(0.7, 0.85, fbm(u * 1.9, v * 1.9, 3.0, 144)) * h)[..., None] * 45
    return opaque(rgb)


def ragged_alpha(seed, base_width=0.42, tip=0.06):
    """A jagged shard / strip outline: wide at the base (v = 1), torn to a ragged end at v = 0."""
    u, v = UV
    half = tip + (base_width - tip) * v ** 0.8
    edge = half * (0.75 + 0.5 * fbm(v * 9, u * 0.5, 0, seed))
    a = smoothstep(edge, edge - 0.02, np.abs(u - 0.5))
    a *= smoothstep(0.02, 0.06 + 0.08 * fbm(u * 12, 0, 1, seed + 1), v)
    return a, u, v


def bone_tile(seed, width):
    a, u, v = ragged_alpha(seed, base_width=width)
    rgb = BONE[None, None] * (0.8 + 0.25 * fbm(u * 6, v * 14, 0, seed + 1))[..., None]
    porous = smoothstep(0.08, 0.0, np.abs(u - 0.5)) * smoothstep(0.55, 0.7, fbm(u * 30, v * 30, 1, seed + 2))
    rgb = rgb - porous[..., None] * np.array([40, 90, 90])
    rgb = rgb * (1 - 0.6 * smoothstep(0.7, 1.0, v))[..., None] + smoothstep(0.7, 1.0, v)[..., None] * BLOOD * 0.6
    return np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)


def flap_tile():
    a, u, v = ragged_alpha(160, base_width=0.48, tip=0.18)
    rgb = DERMIS[None, None] * (0.75 + 0.35 * fbm(u * 5, v * 5, 0, 161))[..., None]
    fat = smoothstep(0.55, 0.7, fbm(u * 8, v * 8, 2, 162))
    rgb = rgb * (1 - fat[..., None] * 0.5) + fat[..., None] * FAT * 0.5
    blood = np.clip(smoothstep(0.45, 0.6, fbm(u * 3, v * 3, 3, 163)) + smoothstep(0.75, 1.0, v), 0, 1)
    rgb = rgb * (1 - blood[..., None] * 0.75) + blood[..., None] * BLOOD * 0.75
    return np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)


def section_tile():
    """A limb cut through: the skin round the outside, the yellow fat under it, the muscle in its bundles
    (pale lines of fascia between them), the bone with its marrow - all wet with blood."""
    u, v = UV
    x, y = u * 2 - 1, v * 2 - 1
    r = np.sqrt(x * x + y * y)
    ang = np.arctan2(y, x)
    rag = r * (1.0 + 0.06 * (fbm(np.cos(ang) * 3, np.sin(ang) * 3, 0, 170) - 0.5))
    muscle = FLESH[None, None] * (0.75 + 0.35 * fbm(x * 6, y * 6, 1, 171))[..., None]
    fascia = smoothstep(0.03, 0.0, np.abs(np.sin(fbm(x * 2.5, y * 2.5, 2, 172) * 14)))
    muscle = muscle * (1 - 0.4 * fascia[..., None]) + fascia[..., None] * np.array([220, 170, 160]) * 0.4
    rgb = muscle
    rgb = np.where((rag > 0.82)[..., None], FAT[None, None] * (0.85 + 0.2 * fbm(x * 9, y * 9, 3, 173))[..., None], rgb)
    rgb = np.where((rag > 0.93)[..., None], DERMIS[None, None] * (0.85 + 0.2 * fbm(x * 9, y * 9, 4, 174))[..., None], rgb)
    br = np.sqrt((x - 0.12) ** 2 + (y + 0.1) ** 2)
    rgb = np.where((br < 0.26)[..., None], BONE[None, None] * (0.85 + 0.2 * fbm(x * 12, y * 12, 5, 175))[..., None], rgb)
    rgb = np.where((br < 0.14)[..., None], np.array([120, 30, 34])[None, None] * (0.8 + 0.3 * fbm(x * 15, y * 15, 6, 176))[..., None], rgb)
    blood = smoothstep(0.55, 0.7, fbm(x * 3, y * 3, 7, 177))
    rgb = rgb * (1 - 0.6 * blood[..., None]) + blood[..., None] * BLOOD_FRESH * 0.6
    rgb = rgb + smoothstep(0.7, 0.8, fbm(x * 4, y * 4, 8, 178))[..., None] * 40
    a = smoothstep(1.0, 0.97, rag)
    return np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)


def cloth_tile():
    a, u, v = ragged_alpha(180, base_width=0.5, tip=0.3)
    weave = 0.85 + 0.15 * (np.sin(u * 300) * np.sin(v * 300) > 0)
    rgb = np.array([58, 50, 52])[None, None] * weave[..., None] * (0.85 + 0.3 * fbm(u * 6, v * 6, 0, 181))[..., None]
    soak = np.clip(smoothstep(0.2, 0.9, v) + 0.4 * fbm(u * 4, v * 4, 1, 182), 0, 1)
    rgb = rgb * (1 - soak[..., None] * 0.8) + soak[..., None] * BLOOD * 0.8
    return np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)


def strand_tile():
    a, u, v = ragged_alpha(190, base_width=0.16, tip=0.08)
    rgb = wet(BLOOD_FRESH * 0.8, u * 4, v * 4, np.zeros_like(u), 191)
    return np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)


def meat_tile():
    u, v = UV
    fib = 0.8 + 0.2 * np.sin(v * 70 + u * 12 + fbm(u * 3, v * 3, 0, 200) * 18)
    rgb = FLESH[None, None] * fib[..., None] * (0.8 + 0.3 * fbm(u * 5, v * 5, 1, 201))[..., None]
    fat = smoothstep(0.62, 0.72, fbm(u * 3, v * 7, 2, 202))
    rgb = rgb * (1 - fat[..., None]) + fat[..., None] * FAT * 0.9
    rgb = rgb + smoothstep(0.7, 0.82, fbm(u * 6, v * 6, 3, 203))[..., None] * 45
    return opaque(rgb)


def organ_tile(base, seed, vessels=0.5, sheen=55, mottle=0.25):
    u, v = UV
    rgb = np.array(base, float)[None, None] * (1 - mottle + 2 * mottle * fbm(u * 5, v * 5, 0, seed))[..., None]
    ves = smoothstep(0.03, 0.0, np.abs(fbm(u * 2, v * 2, 1, seed + 1) - 0.5)) * vessels
    rgb = rgb * (1 - ves[..., None] * 0.6) + ves[..., None] * np.array([120, 20, 40]) * 0.6
    rgb = rgb + smoothstep(0.66, 0.8, fbm(u * 3.5, v * 3.5, 2, seed + 2))[..., None] * sheen
    return opaque(rgb)


def intestine_tile():
    u, v = UV
    seg = 0.85 + 0.15 * np.cos(v * 2 * np.pi * 6)
    rgb = np.array([214, 150, 140])[None, None] * seg[..., None] * (0.85 + 0.25 * fbm(u * 6, v * 6, 0, 210))[..., None]
    ves = smoothstep(0.025, 0.0, np.abs(fbm(u * 3, v * 3, 1, 211) - 0.5))
    rgb = rgb * (1 - ves[..., None] * 0.5) + ves[..., None] * np.array([150, 40, 70]) * 0.5
    blood = smoothstep(0.6, 0.75, fbm(u * 2.5, v * 2.5, 2, 212))
    rgb = rgb * (1 - blood[..., None] * 0.7) + blood[..., None] * BLOOD_FRESH * 0.7
    rgb = rgb + smoothstep(0.65, 0.8, fbm(u * 5, v * 5, 3, 213))[..., None] * 50
    return opaque(rgb)


# ---------------------------------------------------------------- the jaw shot away (9.21)

def jaw_y(x, v):
    """Where the face is torn off across the front: below this (y down) the lower jaw is gone."""
    return -2.75 + 0.35 * np.sin(1.3 * x + 2.1 * v) + 0.18 * np.sin(3.7 * x + 1.3 * v)


def jaw_side_y(z, side, v):
    return jaw_y(4.0 * side, v) + 0.25 * np.sin(2.7 * z + v)


def jaw_back(y, v):
    """How far back along the cheeks it is torn away (z, from the face at -4)."""
    return -0.9 + 0.35 * np.sin(2.2 * y + 1.7 * v) + 0.2 * np.sin(4.1 * y + v)


def jaw_rim_tile(v, face):
    """What is left of the face round the tear: a raw, ragged edge, blood soaked up into the skin above it and
    spattered over it, a few runs. Transparent where the face is gone (and away from the tear)."""
    x, y, z = points(HEAD[face])
    if face == "front":
        d = jaw_y(x, v) - y  # > 0: still there, this far above the tear
    else:
        side = 1.0 if face == "left" else -1.0
        dy = jaw_side_y(z, side, v) - y
        dz = z - jaw_back(y, v)
        # the distance to the torn-away corner (dy < 0 and dz < 0 inside it)
        d = np.where((dy >= 0) & (dz >= 0), np.sqrt(dy * dy + dz * dz), np.where(dy >= 0, dy, np.where(dz >= 0, dz, -np.minimum(-dy, -dz))))
    L = Layer()
    n = fbm(x * 1.3 + v, y * 1.3, z * 1.3, 700 + v)
    soak = smoothstep(1.3 + 0.6 * n, 0.15, d) * (d > -0.05)
    L.paint(wet(BLOOD, x, y, z, 710 + v), soak * 0.85)
    spat = smoothstep(0.8, 0.87, fbm(x * 3.5, y * 3.5, z * 3.5, 720 + v)) * smoothstep(3.5, 1.0, d) * (d > 0)
    L.paint(wet(BLOOD_FRESH, x, y, z, 730 + v), spat)
    raw = smoothstep(0.55 + 0.25 * n, 0.15, d) * (d > -0.05)
    L.paint(FLESH * (0.75 + 0.4 * fbm(x * 5, y * 5, z * 5, 740 + v))[..., None], raw)
    edge = smoothstep(0.18, 0.0, d) * (d > -0.05)
    L.paint(BLOOD_DARK, edge * 0.8)
    return L.image()


def teeth_tile():
    """Wrapped round a tooth (u round it, v from the gum down): gum at the top, then enamel, chipped and bloody."""
    u, v = UV
    enamel = np.array([232, 224, 200], float)[None, None] * (0.85 + 0.2 * fbm(u * 8, v * 4, 0, 760))[..., None]
    enamel = enamel - smoothstep(0.6, 1.0, v)[..., None] * np.array([20, 24, 40])
    gum = np.array([170, 60, 64], float)[None, None] * (0.8 + 0.3 * fbm(u * 6, v * 6, 1, 761))[..., None]
    g_line = 0.22 + 0.06 * np.sin(u * 2 * np.pi * 2)
    rgb = np.where((v < g_line)[..., None], gum, enamel)
    blood = smoothstep(0.55, 0.7, fbm(u * 3, v * 3, 2, 762)) + smoothstep(0.1, 0.0, np.abs(v - g_line)) * 0.6
    rgb = rgb * (1 - np.clip(blood, 0, 1)[..., None] * 0.7) + np.clip(blood, 0, 1)[..., None] * BLOOD_FRESH * 0.7
    return opaque(rgb)


def tongue_tile():
    u, v = UV
    base = np.array([186, 84, 92], float)
    rgb = base[None, None] * (0.8 + 0.3 * fbm(u * 5, v * 5, 0, 770))[..., None]
    pap = smoothstep(0.72, 0.8, fbm(u * 40, v * 40, 1, 771))
    rgb = rgb + pap[..., None] * np.array([40, 30, 30])
    groove = smoothstep(0.04, 0.0, np.abs(u - 0.5)) * 0.4
    rgb = rgb * (1 - groove[..., None])
    blood = smoothstep(0.5, 0.68, fbm(u * 2.5, v * 2.5, 2, 772))
    rgb = rgb * (1 - blood[..., None] * 0.75) + blood[..., None] * BLOOD_FRESH * 0.75
    return opaque(rgb)


def palate_tile():
    """The roof of the mouth, torn open: ridges across it, wet, bloody, bits of bone at the front."""
    u, v = UV
    ridges = 0.85 + 0.15 * np.sin(v * 2 * np.pi * 7 + np.sin(u * 9) * 1.5)
    rgb = np.array([176, 70, 74], float)[None, None] * ridges[..., None] * (0.8 + 0.3 * fbm(u * 5, v * 5, 0, 780))[..., None]
    blood = np.clip(smoothstep(0.45, 0.62, fbm(u * 3, v * 3, 1, 781)) + smoothstep(0.75, 1.0, v), 0, 1)
    rgb = rgb * (1 - blood[..., None] * 0.8) + blood[..., None] * BLOOD * 0.8
    bone = smoothstep(0.12, 0.02, v) * smoothstep(0.55, 0.7, fbm(u * 10, v * 3, 2, 782))
    rgb = rgb * (1 - bone[..., None]) + bone[..., None] * BONE
    return opaque(rgb)


def throat_tile():
    u, v = UV
    x, y = u * 2 - 1, v * 2 - 1
    r = np.sqrt(x * x + y * y * 1.6)
    rgb = FLESH[None, None] * (0.6 + 0.4 * fbm(u * 6, v * 6, 0, 790))[..., None]
    fib = 0.85 + 0.15 * np.sin(v * 60 + fbm(u * 3, v * 3, 1, 791) * 10)
    rgb = rgb * fib[..., None]
    hole = smoothstep(0.55, 0.15, r)
    rgb = rgb * (1 - hole[..., None] * 0.85) + hole[..., None] * np.array([20, 2, 4])
    blood = smoothstep(0.5, 0.65, fbm(u * 3, v * 3, 2, 792))
    rgb = rgb * (1 - blood[..., None] * 0.6) + blood[..., None] * BLOOD_FRESH * 0.6
    return opaque(rgb)


def main():
    atlas = np.zeros((16 * T, 8 * T, 4), np.uint8)

    def put(i, img):
        r, c = divmod(i, 8)
        atlas[r * T:(r + 1) * T, c * T:(c + 1) * T] = img

    for v in range(3):
        for f, name in enumerate(HEAD_FACES):
            put(v * 5 + f, graze_tile(v, name))
            put(15 + v * 5 + f, shot_tile(v, name))
    put(30, brain_tile())
    put(31, bone_tile(150, 0.42))
    put(32, bone_tile(155, 0.26))
    put(33, flap_tile())
    put(34, section_tile())
    put(35, cloth_tile())
    put(36, strand_tile())
    put(37, meat_tile())
    put(38, intestine_tile())
    put(39, organ_tile((92, 22, 24), 220, vessels=0.2, sheen=70, mottle=0.15))   # liver
    put(40, organ_tile((140, 30, 40), 230, vessels=0.8, sheen=50))               # heart
    put(41, organ_tile((214, 128, 132), 240, vessels=0.6, sheen=25, mottle=0.35))  # lung
    for level in (1, 2, 3):
        for v in range(3):
            for s, side in enumerate(("front", "back")):
                put(42 + ((level - 1) * 3 + v) * 2 + s, torso_tile(level, v, side))
    for v in range(4):
        put(60 + v, mob_tile(v))
    put(64, teeth_tile())
    put(65, tongue_tile())
    put(66, palate_tile())
    put(67, throat_tile())
    for v in range(3):
        for f, name in enumerate(("front", "left", "right")):
            put(68 + v * 3 + f, jaw_rim_tile(v, name))
    Image.fromarray(atlas, "RGBA").save(OUT, optimize=True)


if __name__ == "__main__":
    main()
