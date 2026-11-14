import sys, struct, math, os
import numpy as np
from PIL import Image, ImageDraw
W, H = 480, 270
F = (H / 2) / math.tan(math.radians(70) / 2)
LIGHT = np.array([0.35, 0.8, 0.45]); LIGHT /= np.linalg.norm(LIGHT)

def frames(path):
    with open(path, 'rb') as f:
        data = f.read()
    n = struct.unpack_from('>i', data, 0)[0]; off = 4
    for _ in range(n):
        q = struct.unpack_from('>i', data, off)[0]; off += 4
        arr = np.frombuffer(data, dtype='>f4', count=q * 13, offset=off).reshape(q, 13) if False else None
        rec = np.dtype([('v', '>f4', (12,)), ('c', '>i4')])
        a = np.frombuffer(data, dtype=rec, count=q, offset=off); off += q * 52
        yield a['v'].reshape(q, 4, 3).astype(np.float64), a['c']

def background():
    img = Image.new('RGB', (W, H))
    px = np.zeros((H, W, 3), np.uint8)
    for y in range(H):
        t = y / H
        px[y] = (110 + 60 * t, 160 + 40 * t, 220 - 20 * t) if y < H * 0.55 else (95, 120, 70)
    return Image.fromarray(px)

BG = background()

NEAR = -0.05

def clip(v):
    """The polygon cut at the near plane, as the GPU does (None: wholly behind)."""
    inside = v[:, 2] <= NEAR
    if inside.all():
        return v
    if not inside.any():
        return None
    out = []
    n = len(v)
    for k in range(n):
        a, b = v[k], v[(k + 1) % n]
        ia, ib = a[2] <= NEAR, b[2] <= NEAR
        if ia:
            out.append(a)
        if ia != ib:
            t = (NEAR - a[2]) / (b[2] - a[2])
            out.append(a + (b - a) * t)
    return np.array(out) if len(out) >= 3 else None

def render(quads, cols):
    img = BG.copy(); d = ImageDraw.Draw(img)
    z = quads[:, :, 2].mean(1)
    order = np.argsort(z)  # most negative (far) first
    for i in order:
        v = clip(quads[i])
        if v is None:
            continue
        n = np.cross(v[1] - v[0], v[2] - v[0]) if len(v) >= 3 else np.zeros(3)
        ln = np.linalg.norm(n)
        if ln < 1e-12 and len(v) >= 4:
            n = np.cross(v[2] - v[0], v[3] - v[0]); ln = np.linalg.norm(n)
        if ln < 1e-12:
            continue
            if ln < 1e-12: continue
        n /= ln
        shade = 0.45 + 0.55 * abs(n @ LIGHT)
        c = int(cols[i])
        rgb = tuple(int(min(255, ((c >> s) & 255) * shade)) for s in (16, 8, 0))
        pts = [(W / 2 + p[0] / -p[2] * F, H / 2 - p[1] / -p[2] * F) for p in v]
        if len(pts) < 3:
            continue
        d.polygon(pts, fill=rgb)
    return img

def main(path, outdir, step=2):
    name = os.path.splitext(os.path.basename(path))[0]
    imgs = [render(q, c) for k, (q, c) in enumerate(frames(path)) if k % step == 0]
    imgs[0].save(f'{outdir}/{name}.gif', save_all=True, append_images=imgs[1:], duration=int(1000 / 30), loop=0)
    # contact sheet: 12 frames spread over the scene
    pick = [imgs[int(i * (len(imgs) - 1) / 11)] for i in range(12)]
    sheet = Image.new('RGB', (W * 4, H * 3))
    for i, im in enumerate(pick):
        sheet.paste(im, ((i % 4) * W, (i // 4) * H))
        ImageDraw.Draw(sheet).text(((i % 4) * W + 6, (i // 4) * H + 4), f'{name} {i}', fill=(0, 0, 0))
    sheet.save(f'{outdir}/{name}_sheet.png')
    print(name, len(imgs))

if __name__ == '__main__':
    for p in sys.argv[2:]:
        main(p, sys.argv[1])
