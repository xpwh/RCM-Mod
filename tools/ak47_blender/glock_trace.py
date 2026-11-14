"""Trace the outlines of a Glock (left side, muzzle to the left, white background) and turn them into a Glock 19.

The photograph is of a Glock 17 Gen 5; the Glock 19 is the same gun 12 mm shorter in front of the trigger guard
(slide, barrel, dust cover) and 11 mm shorter in the grip, so both stretches are taken out of the outlines.

Output (glock_profile.json), in the model's coordinates: y forward (towards the muzzle), z up, metres; the
origin at the middle of the grip, as the mod's item space has it:
  slide, frame (outer outline), guard (the trigger guard's opening), base (magazine floor plate),
  bore (height of the bore), plus the landmarks the model is built around.

    python3 glock_trace.py <photo.png> glock_profile.json
"""
import json
import sys
from collections import deque

import numpy as np
from PIL import Image

LENGTH_MM = 202.0  # Glock 17 Gen 5, overall
FRONT_CUT_MM = 12.0
GRIP_CUT_MM = 11.0

img = np.asarray(Image.open(sys.argv[1]).convert("RGB")).astype(int)
H, W, _ = img.shape
light = img.min(2) > 215


def flood(start_mask):
    seen = np.zeros((H, W), bool)
    q = deque()
    for y, x in zip(*np.where(start_mask)):
        seen[y, x] = True
        q.append((y, x))
    while q:
        y, x = q.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < H and 0 <= nx < W and light[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True
                q.append((ny, nx))
    return seen


border = np.zeros((H, W), bool)
border[0, :] = border[-1, :] = border[:, 0] = border[:, -1] = True
outside = flood(border & light)
gun = ~outside
cols = np.where(gun.any(0))[0]
k = LENGTH_MM / (cols.max() - cols.min()) / 1000.0  # metres per pixel


def trace(mask):
    """Outer boundary of the largest blob in mask (Moore neighbourhood), as (x, y) pixels."""
    ys, xs = np.where(mask)
    start = (ys.min(), xs[ys == ys.min()].min())
    nb = [(-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1)]
    path = [start]
    cur, d = start, 6
    for _ in range(200000):
        for i in range(8):
            nd = (d + i) % 8
            ny, nx = cur[0] + nb[nd][0], cur[1] + nb[nd][1]
            if 0 <= ny < H and 0 <= nx < W and mask[ny, nx]:
                cur = (ny, nx)
                d = (nd + 6) % 8
                break
        if cur == start:
            break
        path.append(cur)
    return [(x, y) for y, x in path]


def simplify(pts, eps):
    """Ramer-Douglas-Peucker."""
    pts = np.asarray(pts, float)
    if len(pts) < 3:
        return pts.tolist()
    a, b = pts[0], pts[-1]
    ab = b - a
    n = np.linalg.norm(ab)
    if n < 1e-9:
        d = np.linalg.norm(pts - a, axis=1)
    else:
        d = np.abs(ab[0] * (pts[:, 1] - a[1]) - ab[1] * (pts[:, 0] - a[0])) / n
    i = int(d.argmax())
    if d[i] > eps:
        return simplify(pts[:i + 1], eps)[:-1] + simplify(pts[i:], eps)
    return [a.tolist(), b.tolist()]


def closed_simplify(pts, eps=1.2):
    half = len(pts) // 2
    return simplify(pts[:half + 1], eps)[:-1] + simplify(pts[half:] + [pts[0]], eps)[:-1]


# the slide's lower edge: the dark seam between slide and frame
SLIDE_BOTTOM = 162
SIGHT_TOP = 44  # top of the slide (the sights stand above it)
slide_mask = gun.copy()
slide_mask[SLIDE_BOTTOM + 1:, :] = False
slide_mask[:SIGHT_TOP, :] = False
frame_mask = gun.copy()
frame_mask[:SLIDE_BOTTOM + 1, :] = False
BASE_TOP = 752
base_mask = frame_mask.copy()
base_mask[:BASE_TOP, :] = False
frame_mask[BASE_TOP:, :] = False
# the trigger guard's opening: the light region inside it (filled from a point in it)
inside = light & gun
seed = np.zeros((H, W), bool)
seed[330, 520] = True
_light = light
light = inside
guard_mask = flood(seed & inside)
light = _light

# landmarks (pixels) read off the photograph
muzzle_hole = np.where(gun[:, cols.min():cols.min() + 3].any(1))[0]
BORE_PX = float(muzzle_hole.mean())
GUARD_FRONT_PX = 459.0
GRIP_TOP_PX = 450.0


def warp(x, y):
    """Glock 17 -> 19: the stretch in front of the trigger guard and a band of the grip taken out."""
    cut_x = FRONT_CUT_MM / 1000.0 / k
    s = np.clip((455.0 - x) / (455.0 - 345.0), 0.0, 1.0)
    x = x + cut_x * s
    cut_y = GRIP_CUT_MM / 1000.0 / k
    t = np.clip((y - GRIP_TOP_PX) / 200.0, 0.0, 1.0) if x > 700 else 0.0
    y = y - cut_y * t
    return x, y


def grip_middle():
    """The middle of the grip (warped pixels): half way down it, half way between its front and back strap."""
    ys = []
    for y in range(500, 700):
        xs = np.where(frame_mask[y, 700:])[0]
        if len(xs):
            ys.append((y, 700 + xs.min(), 700 + xs.max()))
    y, x0, x1 = ys[len(ys) // 2]
    return warp((x0 + x1) / 2.0, y)


GX, GY = grip_middle()
# the origin up the grip to where the mod's item space has it: the bore 58 mm above it

BORE_ABOVE = (GY - warp(cols.min(), BORE_PX)[1]) * k
GY -= (BORE_ABOVE - 0.058) / k


def to_model(x, y):
    """Warped pixel -> model metres: y forward (muzzle side is smaller x), z up."""
    wx, wy = warp(x, y)
    return [round(float(-(wx - GX) * k), 5), round(float(-(wy - GY) * k), 5)]


def outline(mask, eps=1.2):
    return [to_model(x, y) for x, y in closed_simplify(trace(mask), eps)]


# small parts, read off the photograph (pixel boxes x0, y0, x1, y1)
MARKS_PX = {
    "front_sight": (125, 20, 155, 44),
    "rear_sight": (1025, 20, 1062, 44),
    "front_serrations": (150, 50, 340, 158),
    "rear_serrations": (868, 50, 1062, 158),
    "slide_stop": (780, 165, 838, 205),
    "mag_release": (768, 322, 838, 386),
    "takedown": (568, 180, 610, 228),
    "pin": (640, 212, 662, 234),
    "trigger": (598, 262, 724, 378),
    "rail_slot": (205, 222, 385, 240),
}


def box_model(b):
    x0, y0, x1, y1 = b
    p0, p1 = to_model(x0, y1), to_model(x1, y0)
    return [min(p0[0], p1[0]), min(p0[1], p1[1]), max(p0[0], p1[0]), max(p0[1], p1[1])]


def hull(pts):
    pts = sorted(map(tuple, pts))
    def half(seq):
        h = []
        for p in seq:
            while len(h) >= 2 and (h[-1][0] - h[-2][0]) * (p[1] - h[-2][1]) - (h[-1][1] - h[-2][1]) * (p[0] - h[-2][0]) <= 0:
                h.pop()
            h.append(p)
        return h
    return half(pts)[:-1] + half(pts[::-1])[:-1]


out = {
    "metres_per_pixel": k,
    "slide": outline(slide_mask),
    "frame": outline(frame_mask),
    "guard": [list(p) for p in hull(outline(guard_mask, 1.0))],
    "marks": {name: box_model(b) for name, b in MARKS_PX.items()},
    "base": outline(base_mask),
    "bore": to_model(cols.min(), BORE_PX)[1],
    "slide_bottom": to_model(300, SLIDE_BOTTOM)[1],
    "slide_top": to_model(300, SIGHT_TOP)[1],
}
json.dump(out, open(sys.argv[2], "w"), indent=1)
for key, v in out.items():
    print(key, (len(v), v[0], v[-1]) if isinstance(v, list) else v)
