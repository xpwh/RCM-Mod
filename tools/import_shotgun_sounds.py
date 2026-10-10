"""9.10 shotgun sounds, cut from real recordings of pump-action shotguns on freesound.org
(CC0 / CC BY - see CREDITS.md). The folder given holds the freesound HQ previews converted to
44.1 kHz mono WAV, named <category>_fs<id>_<author>_<what>.wav:

  shotgun/shot1..6      close, from the shooter's place: a pump 12-gauge at an outdoor clay range
                        (fs677396), each shot with its own outdoor tail
  shotgun/shot_mid1..2  from some way off: a 20-gauge in a forest (fs410551), one in a field (fs191449)
  shotgun/shot_far1..3  far off: a boar hunt (fs842326), a distant shotgun (fs431822)
  shotgun/pump_back1..5, pump_forward1..5
                        a Winchester 1300's slide snapped back to the stop and driven home (fs790980)
  shotgun/shell_in1..5  shells thumbed into its magazine tube (fs790978)
  shotgun/dry1..4       its hammer falling on an empty chamber (fs790976)
  shotgun/hull1..3      an empty 12-gauge hull hitting concrete and wood and bouncing (fs436070, fs654490, fs654489)
  shotgun/handle1..3    the gun handled, shouldered (fs790977)

python3 import_shotgun_sounds.py <folder>
"""
import glob
import os
import sys
import wave

import numpy as np

import gen_sounds as s
from gen_sounds import fade, fft_filter

SR = s.SR
SRC = sys.argv[1] if len(sys.argv) > 1 else "."


def load(fs_id):
    path = glob.glob(os.path.join(SRC, f"*_fs{fs_id}_*.wav"))[0]
    with wave.open(path) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768.0


def onset(x, t, search=0.15):
    """The true start of the transient near t: where the level first climbs to a third of its peak."""
    a = max(0, int((t - search) * SR))
    b = min(len(x), int((t + search) * SR))
    seg = np.abs(x[a:b])
    peak = seg.max()
    i = int(np.argmax(seg > peak / 3.0))
    return (a + i) / SR


def cut(x, t0, t1, fin=0.002, fout=0.08):
    seg = x[max(0, int(t0 * SR)):int(t1 * SR)].copy()
    seg -= np.mean(seg)
    return fade(seg, fin, fout)


def norm(x, peak_db):
    return x / (np.max(np.abs(x)) + 1e-12) * 10 ** (peak_db / 20.0)


def clean(x, low=40.0):
    return fft_filter(x, low=low, slope=2.0)


out = {}

# ---- close shots: the range session, each shot with its own tail
range_ = load(677396)
shots = [10.36, 21.18, 32.16, 42.22, 53.65, 64.40, 110.83, 122.33, 134.31, 145.60]
picked = []
for t in shots:
    t0 = onset(range_, t)
    seg = cut(range_, t0 - 0.003, t0 + 2.3, fout=0.6)
    picked.append((np.max(np.abs(seg[: int(0.05 * SR)])), seg))
# the six hardest-hitting takes
picked.sort(key=lambda p: -p[0])
for i, (_, seg) in enumerate(picked[:6]):
    out[f"shotgun/shot{i + 1}"] = norm(clean(seg, 35.0), -0.6)
# ---- from some way off, and far
for i, (fs_id, t, length) in enumerate([(410551, 0.71, 2.9), (191449, 3.15, 2.6)]):
    x = load(fs_id)
    t0 = onset(x, t)
    out[f"shotgun/shot_mid{i + 1}"] = norm(clean(cut(x, t0 - 0.004, t0 + length, fout=0.7), 50.0), -1.0)
for i, (fs_id, t, length) in enumerate([(842326, 0.005, 2.5), (842326, 2.659, 3.6), (431822, 0.317, 1.9)]):
    x = load(fs_id)
    t0 = onset(x, t)
    out[f"shotgun/shot_far{i + 1}"] = norm(clean(cut(x, max(0.0, t0 - 0.004), t0 + length, fout=0.8), 50.0), -1.5)

# ---- the action: back to the stop, then home
pump = load(790980)
for i, (b, f) in enumerate([(0.141, 0.832), (2.35, 2.97), (4.404, 5.077), (6.634, 7.143), (8.684, 9.419)]):
    tb = onset(pump, b, 0.06)
    tf = onset(pump, f, 0.06)
    back = cut(pump, tb - 0.012, min(tb + 0.32, tf - 0.03), fout=0.05)
    home = cut(pump, tf - 0.012, tf + 0.42, fout=0.08)
    out[f"shotgun/pump_back{i + 1}"] = norm(clean(back, 60.0), -1.0)
    out[f"shotgun/pump_forward{i + 1}"] = norm(clean(home, 60.0), -1.0)

# ---- shells into the tube
load_ = load(790978)
for i, t in enumerate([0.18, 1.567, 3.167, 4.57, 6.101]):
    t0 = onset(load_, t, 0.08)
    out[f"shotgun/shell_in{i + 1}"] = norm(clean(cut(load_, t0 - 0.03, t0 + 0.42, fout=0.08), 60.0), -2.0)

# ---- dry fire
dry = load(790976)
for i, t in enumerate([0.123, 2.838, 4.195, 5.579]):
    t0 = onset(dry, t, 0.06)
    out[f"shotgun/dry{i + 1}"] = norm(clean(cut(dry, t0 - 0.008, t0 + 0.3, fout=0.06), 60.0), -2.0)

# ---- the empty hull landing
for i, (fs_id, t, length) in enumerate([(436070, 1.612, 1.3), (654490, 0.978, 1.0), (654489, 0.38, 1.0)]):
    x = load(fs_id)
    t0 = onset(x, t, 0.08)
    out[f"shotgun/hull{i + 1}"] = norm(clean(cut(x, t0 - 0.01, t0 + length, fout=0.25), 120.0), -3.0)

# ---- handling
handling = load(790977)
for i, (a, b) in enumerate([(0.5, 1.6), (3.2, 4.5), (8.28, 9.4)]):
    out[f"shotgun/handle{i + 1}"] = norm(clean(cut(handling, a, b, fin=0.03, fout=0.15), 60.0), -4.0)

# the old cuts this set replaces
for old in ["shot7", "shot8", "pump1", "pump2", "shot_far", "dry", "shell_in1", "shell_in2", "shell_in3", "shot1", "shot2", "shot3", "shot4", "shot5", "handle1", "handle2"]:
    path = os.path.join(s.OUT, "shotgun", old + ".ogg")
    if os.path.exists(path) and f"shotgun/{old}" not in out:
        os.remove(path)

for name, x in out.items():
    s.save(name, x)
print("\n".join(f"{k}: {len(v) / SR:.2f} s" for k, v in out.items()))
