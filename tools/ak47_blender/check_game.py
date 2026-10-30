"""Read the exported game mesh like the mod does and draw it (item space, seen from the right) with its texture."""
import gzip, struct, sys
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
SEL = np.array([0.0225, 0.0691, 0.0784])
W, H = 1400, 560
sc = 1100.0
def proj(p):  # view from +x: screen x = -z, screen y = -y
    return np.stack([W * 0.56 - p[..., 2] * sc, H * 0.42 - p[..., 1] * sc], -1)
img = np.full((H, W, 3), 0.15, np.float32); zb = np.full((H, W), -1e9, np.float32)
light = np.array([0.3, 0.8, 0.5]); light /= np.linalg.norm(light)
for k, q in parts.items():
    if k == 'band':
        continue
    q = q.copy()
    if k == 'selector':
        q[..., :3] += SEL
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
            img[yy, xx] = T[ty, tx] * sh[:, None]
            zb[yy, xx] = d
im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
dr = ImageDraw.Draw(im)
def mark(p, c, label):
    x, y = proj(np.array(p)); dr.ellipse((x - 4, y - 4, x + 4, y + 4), outline=c, width=2); dr.text((x + 6, y - 6), label, fill=c)
mark((0, -0.04, 0.082), (255, 80, 80), 'GRIP')
mark((-0.004, 0.026, -0.335), (255, 80, 80), 'HANDGUARD')
mark((0.086, 0.092, -0.029), (255, 200, 80), 'HANDLE')
mark((0, 0.029, -0.115), (80, 200, 255), 'MAG_TOP')
mark((-0.006, -0.105, -0.194), (80, 200, 255), 'MAG_HOLD')
mark((0, 0, 0), (255, 255, 255), 'origin')
a = proj(np.array([0, 0.1473, -0.2025])); b = proj(np.array([0, 0.1473 - 0.002 * 1.2 / 0.4764, -0.2025 - 1.2]))
dr.line((tuple(a), tuple(b)), fill=(80, 255, 80))
a = proj(np.array([0, 0.085, 0.2])); b = proj(np.array([0, 0.085, -0.712]))
dr.line((tuple(a), tuple(b)), fill=(255, 80, 255))
im.save(out)
