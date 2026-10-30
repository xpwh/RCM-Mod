"""The Glock's item icons (the pistol, or a magazine named after the paths): the exported game mesh read like the mod does, drawn from the right with its texture (as icon.py)."""
import gzip, math, struct, sys
import numpy as np
from PIL import Image, ImageDraw
mesh, tex, out = sys.argv[1:4]
f = gzip.open(mesh, 'rb')
n = struct.unpack('>I', f.read(4))[0]
parts = {}
for _ in range(n):
    l = struct.unpack('>H', f.read(2))[0]; name = f.read(l).decode()
    c = struct.unpack('>I', f.read(4))[0]
    parts[name] = np.array(struct.unpack(f'>{c}f', f.read(4 * c)), np.float32).reshape(-1, 4, 8)
T = np.asarray(Image.open(tex).convert('RGB'), np.float32) / 255
# winding check: geometric normal against the stored one
for k, q in parts.items():
    g = np.cross(q[:, 1, :3] - q[:, 0, :3], q[:, 2, :3] - q[:, 0, :3])
    ok = (np.einsum('ij,ij->i', g, q[:, :, 5:].mean(1)) >= 0).mean()
    print(k, len(q), 'quads, winding agrees', round(float(ok), 3), 'bounds', q[..., :3].reshape(-1, 3).min(0).round(3), q[..., :3].reshape(-1, 3).max(0).round(3))
# the magazine: from its own frame to its seat in the grip (GlockItemRenderer.submitMagazine)
c, sn = math.cos(math.radians(-20)), math.sin(math.radians(-20))
m = parts['mag'].copy()
y, z = m[..., 1].copy(), m[..., 2].copy()
m[..., 1], m[..., 2] = y * c - z * sn + 0.034, y * sn + z * c - 0.02415
ny, nz = m[..., 6].copy(), m[..., 7].copy()
m[..., 6], m[..., 7] = ny * c - nz * sn, ny * sn + nz * c
ONLY = sys.argv[4] if len(sys.argv) > 4 else None  # a magazine's icon: "mag" or "mag_ext", upright in its own frame
if ONLY:
    parts = {ONLY: parts[ONLY]}
else:
    parts = {'frame': parts['frame'], 'slide': parts['slide'], 'barrel': parts['barrel'], 'mag': m}
W, H = 512, 512
sc = 2350.0
A = math.radians(8)
OX, OY = 0.068, -0.008
if ONLY:
    lo = float(parts[ONLY][..., 1].min())
    sc, A, OX, OY = 0.82 * 512 / -lo, math.radians(-20), 0.007, lo / 2
def proj(p):
    x, y = -(p[..., 2] + OX), p[..., 1] - OY
    return np.stack([W / 2 + (x * math.cos(A) - y * math.sin(A)) * sc, H / 2 - (x * math.sin(A) + y * math.cos(A)) * sc], -1)
img = np.zeros((H, W, 3), np.float32); alpha = np.zeros((H, W), np.float32); zb = np.full((H, W), -1e9, np.float32)
light = np.array([0.5, 0.75, 0.3]); light /= np.linalg.norm(light)
for k, q in parts.items():
    q = q.copy()
    for quad in q:
        for tri in ((0, 1, 2), (0, 2, 3)):
            v = quad[list(tri)]
            if np.allclose(v[1, :3], v[2, :3]):
                continue
            s = proj(v[:, :3])
            e = (s[1, 0] - s[0, 0]) * (s[2, 1] - s[0, 1]) - (s[1, 1] - s[0, 1]) * (s[2, 0] - s[0, 0])
            if e >= 0:  # back face (screen y flipped, so front faces wind negative)
                continue
            x0, y0 = np.floor(s.min(0)).astype(int); x1, y1 = np.ceil(s.max(0)).astype(int)
            x0, y0 = max(x0, 0), max(y0, 0); x1, y1 = min(x1, W - 1), min(y1, H - 1)
            if x1 < x0 or y1 < y0:
                continue
            ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1] + 0.5
            w0 = ((s[1, 0] - xs) * (s[2, 1] - ys) - (s[1, 1] - ys) * (s[2, 0] - xs)) / e
            w1 = ((s[2, 0] - xs) * (s[0, 1] - ys) - (s[2, 1] - ys) * (s[0, 0] - xs)) / e
            w2 = 1 - w0 - w1
            m = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            if not m.any():
                continue
            depth = w0 * v[0, 0] + w1 * v[1, 0] + w2 * v[2, 0]
            yy, xx = (ys[m] - 0.5).astype(int), (xs[m] - 0.5).astype(int)
            d = depth[m]
            sel = d > zb[yy, xx]
            yy, xx, d = yy[sel], xx[sel], d[sel]
            ww = np.stack([w0[m][sel], w1[m][sel], w2[m][sel]], -1)
            uv = ww @ v[:, 3:5]
            nn = ww @ v[:, 5:8]
            tx = np.clip((uv[:, 0] * T.shape[1]).astype(int), 0, T.shape[1] - 1)
            ty = np.clip((uv[:, 1] * T.shape[0]).astype(int), 0, T.shape[0] - 1)
            sh = 0.45 + 0.55 * np.clip(nn @ light / np.maximum(np.linalg.norm(nn, axis=1), 1e-6), 0, 1)
            img[yy, xx] = T[ty, tx] * sh[:, None] * 1.25
            zb[yy, xx] = d
            alpha[yy, xx] = 1
rgba = np.dstack([np.clip(img, 0, 1) * 255, alpha * 255]).astype(np.uint8)
big = Image.fromarray(rgba, "RGBA")
big.save(out.replace(".png", "_big.png"))
small = big.resize((32, 32), Image.LANCZOS)
a = np.array(small)
a[..., 3] = np.where(a[..., 3] > 70, 255, 0)
# premultiplied edge pixels were darkened by the resize: lift them back
Image.fromarray(a, "RGBA").save(out)
