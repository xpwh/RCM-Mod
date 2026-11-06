"""Texture of one voxel-smoke cube face (16x16, nearest-filtered): near white - the smoke's colour and the face
shading come in as vertex colour - with a faint grain and a darker one-pixel rim, so the cubes of a voxel cloud
read as cubes even where they are all one colour.

usage: python3 tools/gen_smoke_voxel.py
"""
import numpy as np
from PIL import Image

OUT = "src/client/resources/assets/ballisticmissiles/textures/effect/smoke_voxel.png"


def main():
    rng = np.random.default_rng(7)
    n = 16
    img = 0.97 + 0.03 * rng.random((n, n))
    # a few slightly darker flecks, like the grain of Minecraft's own blocks
    for _ in range(9):
        x, y = rng.integers(2, n - 2, 2)
        img[y, x] *= 0.95
    # the rim: one pixel darker, the corners a little more; a hint of light along the top-left inner edge
    img[0, :] *= 0.84
    img[-1, :] *= 0.84
    img[:, 0] *= 0.84
    img[:, -1] *= 0.84
    img[1, 1:-1] = np.minimum(1.0, img[1, 1:-1] * 1.02)
    img[1:-1, 1] = np.minimum(1.0, img[1:-1, 1] * 1.02)
    v = (np.clip(img, 0, 1) * 255).astype(np.uint8)
    rgba = np.dstack([v, v, v, np.full_like(v, 255)])
    Image.fromarray(rgba, "RGBA").save(OUT, optimize=True)


if __name__ == "__main__":
    main()
