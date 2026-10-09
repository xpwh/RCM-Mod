"""9.9 shotgun sounds, cut from the Red Library's public-domain (CC0) gun recordings (archive.org,
Red_Library_Guns_Misc, see CREDITS.md):

  shotgun/shot1..5      close: a shotgun blast (R11-58 "Loud Shotgun Blasts") with the bright crack of
                        R11-57 "Shotgun Blast" laid over its first instant
  shotgun/shot_far      the same, far off: the top end gone, a rolling echo
  shotgun/pump1..2      racking the slide: back, then forward (R12-26 "Guns Cocking")
  shotgun/shell_in1..3  a shell thumbed into the magazine tube (metal clicks, R12-32 "Gun Movement")
  shotgun/dry           the hammer on an empty chamber (R12-26)
  shotgun/handle1..2    the gun brought up, handled (R12-32)

python3 import_shotgun_sounds.py <folder with the .flac files>
"""
import os
import subprocess
import sys
import tempfile

import numpy as np

import gen_sounds as s
from gen_sounds import fade, fft_filter, normalize, place

SR = s.SR
SRC = sys.argv[1] if len(sys.argv) > 1 else "."


def load(name):
    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "x.f32")
        subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-i", os.path.join(SRC, name), "-ac", "1", "-ar", str(SR), "-f", "f32le", raw], check=True)
        return np.fromfile(raw, dtype=np.float32).astype(np.float64)


def cut(x, t0, t1, fin=0.003, fout=0.08):
    seg = x[int(t0 * SR):int(t1 * SR)].copy()
    seg -= np.mean(seg)
    return fade(seg, fin, fout)


blasts = load("R11-58-Loud Shotgun Blasts.flac")
cracks = load("R11-57-Shotgun Blast.flac")
crack48 = load("R11-48-Shotgun Blast.flac")
cocking = load("R12-26-Guns Cocking.flac")
movement = load("R12-32-Gun Movement in Room.flac")

out = {}
crack_src = [cut(cracks, 0.235, 0.9), cut(cracks, 4.175, 4.84), cut(crack48, 0.225, 0.89)]
for i, t in enumerate([0.64, 2.53, 3.895, 5.95, 7.84]):
    boom = cut(blasts, t - 0.012, t + 1.75, fout=0.4)
    crack = fft_filter(crack_src[i % 3], low=900.0, slope=1.0)
    mix = boom / (np.max(np.abs(boom)) + 1e-9)
    place(mix, crack / (np.max(np.abs(crack)) + 1e-9) * 0.55, 0.002)
    out[f"shotgun/shot{i + 1}"] = normalize(mix, 0.97)
# far: the low boom and a few late echoes off the land
far = fft_filter(out["shotgun/shot1"], high=850.0, slope=1.5)
far = np.concatenate([far, np.zeros(int(SR * 1.5))])
for delay, gain in [(0.35, 0.45), (0.8, 0.3), (1.3, 0.18)]:
    place(far, fft_filter(out["shotgun/shot2"], high=600.0, slope=1.5) * gain, delay)
out["shotgun/shot_far"] = fade(normalize(far, 0.9), 0.01, 0.6)
out["shotgun/pump1"] = normalize(cut(cocking, 0.15, 1.2), 0.95)
out["shotgun/pump2"] = normalize(cut(cocking, 11.70, 12.80), 0.95)
for i, t in enumerate([3.745, 5.575, 5.17]):
    out[f"shotgun/shell_in{i + 1}"] = normalize(cut(movement, t - 0.01, t + 0.32, fout=0.06), 0.9)
out["shotgun/dry"] = normalize(cut(cocking, 2.66, 2.86, fout=0.05), 0.9)
out["shotgun/handle1"] = normalize(cut(movement, 0.96, 1.4), 0.8)
out["shotgun/handle2"] = normalize(cut(movement, 2.5, 2.9), 0.8)

for name, x in out.items():
    s.save(name, x)
