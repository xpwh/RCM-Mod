"""Trace the side outlines of the AK-47 reference photo (left side, muzzle at the left) into rifle coordinates.

y: forward from the rear of the receiver (metres), z: up from the bore axis (metres).
"""
import json
import sys

import numpy as np
from PIL import Image

MM_PX = 870.0 / (1226 - 52)  # overall length 870 mm
X_REAR = 902                 # rear of the receiver
Y_BORE = 158                 # bore axis


def ref(x, py):
    return (round((X_REAR - x) * MM_PX / 1000, 5), round((Y_BORE - py) * MM_PX / 1000, 5))


a = np.array(Image.open(sys.argv[1]))[..., 3] > 128
out = {}

# magazine: for every row below the receiver, the left (front) and right (back) edge
front, back = [], []
for py in range(214, 431):
    xs = np.where(a[py, 495:706])[0]
    if len(xs) == 0:
        continue
    front.append(ref(495 + xs.min(), py))
    back.append(ref(495 + xs.max(), py))
out["mag_front"] = front
out["mag_back"] = back

# pistol grip: rows below the trigger guard
gf, gb = [], []
for py in range(222, 352):
    xs = np.where(a[py, 805:912])[0]
    if len(xs) == 0:
        continue
    gf.append(ref(805 + xs.min(), py))
    gb.append(ref(805 + xs.max(), py))
out["grip_front"] = gf
out["grip_back"] = gb

# stock: top and bottom per column (the sling swivel bump on top bridged over)
st = []
for x in range(906, 1227, 6):
    ys = np.where(a[:, x])[0]
    st.append((x, int(ys.min()), int(ys.max())))
xs = np.array([s[0] for s in st])
top = np.array([s[1] for s in st], float)
bump = (xs > 985) & (xs < 1065)
top[bump] = np.interp(xs[bump], xs[~bump], top[~bump])
out["stock"] = [(ref(x, t)[0], ref(x, t)[1], ref(x, b)[1]) for x, t, (_, _, b) in zip(xs, top, st)]

# lower handguard: bottom edge per column
lh = []
for x in range(338, 546, 6):
    ys = np.where(a[140:230, x])[0]
    lh.append((ref(x, 0)[0], ref(x, 140 + ys.max())[1]))
out["lower_bottom"] = lh

json.dump(out, open(sys.argv[2], "w"), indent=1)
for k, v in out.items():
    print(k, len(v), v[0], v[len(v) // 2], v[-1])
