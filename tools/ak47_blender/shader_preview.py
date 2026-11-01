"""Draw the exported AK roughly as Minecraft would in first person: perspective from behind and left of the
rifle, the texture sampled with linear filtering (blur), shaded by Minecraft's two fixed entity lights
(0.4 ambient + 0.6 diffuse) and nothing else."""
import gzip
import math
import struct
import sys

import numpy as np
from PIL import Image

mesh, tex, matp, out = sys.argv[1:5]
yaw = math.radians(float(sys.argv[5]) if len(sys.argv) > 5 else -24.0)
pitch = math.radians(float(sys.argv[6]) if len(sys.argv) > 6 else 12.0)
f = gzip.open(mesh, "rb")
parts = {}
for _ in range(struct.unpack(">I", f.read(4))[0]):
    name = f.read(struct.unpack(">H", f.read(2))[0]).decode()
    c = struct.unpack(">I", f.read(4))[0]
    parts[name] = np.array(struct.unpack(f">{c}f", f.read(4 * c)), np.float32).reshape(-1, 4, 8)
T = np.asarray(Image.open(tex).convert("RGB"), np.float32) / 255
M = np.asarray(Image.open(matp).convert("RGBA"), np.float32) / 255
SEL = np.array([0.0225, 0.0691, 0.0784])
# Minecraft's entity light directions
L0 = np.array([0.2, 1.0, -0.7]); L0 /= np.linalg.norm(L0)
L1 = np.array([-0.2, 1.0, 0.7]); L1 /= np.linalg.norm(L1)
cy, sy, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
R = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]) @ np.eye(3)
R = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]]) @ R
CAM = np.array([0.0, 0.05, 0.75])
W, H = 1600, 900
FOCAL = 1250.0


def bilinear(u, v, T=T):
    x = u * T.shape[1] - 0.5
    y = v * T.shape[0] - 0.5
    x0 = np.clip(np.floor(x).astype(int), 0, T.shape[1] - 2)
    y0 = np.clip(np.floor(y).astype(int), 0, T.shape[0] - 2)
    fx = np.clip(x - x0, 0, 1)[:, None]
    fy = np.clip(y - y0, 0, 1)[:, None]
    return (T[y0, x0] * (1 - fx) * (1 - fy) + T[y0, x0 + 1] * fx * (1 - fy) + T[y0 + 1, x0] * (1 - fx) * fy
            + T[y0 + 1, x0 + 1] * fx * fy)


PI = math.pi


def env(d, rough):
    y = d[:, 1:2]
    sky = np.where(True, 0, 0) + (np.array([0.62, 0.64, 0.66]) * (1 - sstep(0, 0.7, y)) + np.array([0.34, 0.4, 0.5]) * sstep(0, 0.7, y))
    gm = sstep(0, 0.5, -y)
    ground = np.array([0.3, 0.28, 0.24]) * (1 - gm) + np.array([0.09, 0.085, 0.08]) * gm
    band = np.exp(-np.abs(y - 0.06) * (18 + (3 - 18) * rough)) * (0.35 + (0.08 - 0.35) * rough)
    e = np.where(y > 0, sky, ground) + band
    return e + (np.array([0.36, 0.37, 0.38]) - e) * (rough * rough * 0.85)


def sstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def ggx(nh, a):
    a2 = a * a
    d = nh * nh * (a2 - 1) + 1
    return a2 / (PI * d * d)


def smith(nv, nl, r):
    k = (r + 1) ** 2 / 8
    return nv / (nv * (1 - k) + k) * nl / (nl * (1 - k) + k)


def key(nrm, vv, l, colour, base, metal, rough, coat):
    nl = np.maximum((nrm * l).sum(1, keepdims=True), 0)
    nv = np.maximum((nrm * vv).sum(1, keepdims=True), 1e-3)
    h = vv + l
    h /= np.linalg.norm(h, axis=1, keepdims=True)
    nh = np.maximum((nrm * h).sum(1, keepdims=True), 0)
    vh = np.maximum((vv * h).sum(1, keepdims=True), 0)
    f0 = 0.04 + (base - 0.04) * metal
    f = f0 + (1 - f0) * (1 - vh) ** 5
    a = np.maximum(rough * rough, 0.002)
    spec = ggx(nh, a) * smith(nv, nl, rough) * f / (4 * nv * nl + 1e-4)
    diff = (1 - f) * (1 - metal) * base / PI
    c = (diff + spec) * nl
    fc = 0.04 + 0.96 * (1 - vh) ** 5
    c = c + coat * ggx(nh, 0.05) * smith(nv, nl, 0.22) * fc / (4 * nv * nl + 1e-4) * nl
    return c * colour


def shade(alb, mat, nrm, pos):
    base = alb ** 2.2
    metal, ao, coat = mat[:, 0:1], mat[:, 2:3], mat[:, 3:4] * 0.3
    wear = sstep(0.35, 0.75, alb.mean(1, keepdims=True))
    rough = np.clip(np.maximum(mat[:, 1:2], 0.52 + (0.68 - 0.52) * metal) - 0.12 * wear * metal, 0.3, 1)
    vv = -pos / np.linalg.norm(pos, axis=1, keepdims=True)
    flip = (nrm * vv).sum(1, keepdims=True) < 0
    nrm = np.where(flip, -nrm, nrm)
    nv = np.maximum((nrm * vv).sum(1, keepdims=True), 1e-3)
    col = key(nrm, vv, L0, np.array([1.0, 0.96, 0.9]) * 2.6, base, metal, rough, coat)
    col += key(nrm, vv, L1, np.array([0.85, 0.9, 1.0]) * 1.1, base, metal, rough, coat)
    t = nrm[:, 1:2] * 0.5 + 0.5
    hemi = (np.array([0.32, 0.29, 0.25]) * (1 - t) + np.array([0.66, 0.69, 0.74]) * t) * 0.9
    f0 = 0.04 + (base - 0.04) * metal
    fr = f0 + (np.maximum(1 - rough, f0) - f0) * (1 - nv) ** 5
    col += (1 - fr) * (1 - metal) * base * hemi * ao
    r = -vv - 2 * (( -vv * nrm).sum(1, keepdims=True)) * nrm
    col += env(r, rough) * fr * ao * (0.35 + 0.2 * metal)
    fc = 0.04 + 0.96 * (1 - nv) ** 5
    col += coat * fc * env(r, 0.22) * ao * 0.7
    col = np.clip((col * (2.51 * col + 0.03)) / (col * (2.43 * col + 0.59) + 0.14), 0, 1)
    return col ** (1 / 2.2)


img = np.zeros((H, W, 3), np.float32)
yy_, xx_ = np.mgrid[0:H, 0:W]
img[:] = (0.42 + 0.25 * (yy_ / H))[..., None] * np.array([0.55, 0.68, 0.85])  # a sky-ish backdrop
zb = np.full((H, W), 1e9, np.float32)
for k, q in parts.items():
    if k == "band":
        continue
    q = q.copy()
    if k == "selector":
        q[..., :3] += SEL
    p = q[..., :3] @ R.T - CAM
    n = q[..., 5:8] @ R.T
    shade_n = q[..., 5:8]  # the lights are fixed to the item here
    for i in range(len(q)):
        for tri in ((0, 1, 2), (0, 2, 3)):
            v = p[i, list(tri)]
            if np.allclose(v[1], v[2]) or (v[:, 2] > -0.05).any():
                continue
            s = np.stack([W / 2 + FOCAL * v[:, 0] / -v[:, 2], H / 2 - FOCAL * v[:, 1] / -v[:, 2]], -1)
            e = (s[1, 0] - s[0, 0]) * (s[2, 1] - s[0, 1]) - (s[1, 1] - s[0, 1]) * (s[2, 0] - s[0, 0])
            if e >= 0:
                continue
            x0, y0 = np.floor(s.min(0)).astype(int)
            x1, y1 = np.ceil(s.max(0)).astype(int)
            x0, y0, x1, y1 = max(x0, 0), max(y0, 0), min(x1, W - 1), min(y1, H - 1)
            if x1 < x0 or y1 < y0:
                continue
            ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1] + 0.5
            w0 = ((s[1, 0] - xs) * (s[2, 1] - ys) - (s[1, 1] - ys) * (s[2, 0] - xs)) / e
            w1 = ((s[2, 0] - xs) * (s[0, 1] - ys) - (s[2, 1] - ys) * (s[0, 0] - xs)) / e
            w2 = 1 - w0 - w1
            m = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            if not m.any():
                continue
            ww = np.stack([w0[m], w1[m], w2[m]], -1)
            d = -(ww @ v[:, 2])
            yy, xx = (ys[m] - 0.5).astype(int), (xs[m] - 0.5).astype(int)
            sel = d < zb[yy, xx]
            if not sel.any():
                continue
            yy, xx, d, ww = yy[sel], xx[sel], d[sel], ww[sel]
            uv = ww @ q[i, list(tri), 3:5]
            nn = ww @ n[i, list(tri)]
            nn /= np.maximum(np.linalg.norm(nn, axis=1, keepdims=True), 1e-6)
            pos = ww @ v
            img[yy, xx] = shade(bilinear(uv[:, 0], uv[:, 1]), bilinear(uv[:, 0], uv[:, 1], M), nn, pos)
            zb[yy, xx] = d
Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).save(out)
