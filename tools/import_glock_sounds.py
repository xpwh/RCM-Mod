"""Glock 19 sounds, cut from real recordings (all CC0 - see CREDITS.md). The folder given holds them as
44.1 kHz mono WAV:

  ffsl_X_39P, ffsl_X_31P   The Free Firearm Sound Library: Walther PPQ (9 mm, striker-fired polymer pistol,
                           the Glock's twin in sound), near and mid distance
  ffsl_F_47P, ffsl_F_41P   the same library: Bersa .380 pistol, near and mid
  ffsl_G_31P, ffsl_G_20P   the same library: Carl Gustav M45 (9 mm), single shots, near and mid
  bsb1984..1987            BigSoundBank: a semi-automatic pistol's slide racked back and let go
  bsb1988..1991            BigSoundBank: a pistol magazine seated in the grip
  bsb1992..1995            BigSoundBank: pistol hammer/striker clicks
  bsb1355..1360            BigSoundBank: a 9 mm case falling on concrete

  glock/shot1..6        close, from the shooter's place
  glock/shot_mid1..5    from some way off
  glock/shot_far1..3    far off: the mid takes with the top taken off and the land ringing after them
  glock/slide_back1..3, slide_forward1..3
                        the slide pulled to the rear, and slammed home (also: the slide stop released)
  glock/mag_out1..2     the release pressed and the magazine sliding out of the grip
  glock/mag_in1..4      a magazine slapped home
  glock/dry1..3         the trigger pulled on nothing: the striker's dead click
  glock/case1..6        an empty 9 mm case bouncing on hard ground

python3 import_glock_sounds.py <folder>
"""
import os
import sys
import wave

import numpy as np

import gen_sounds as s
from gen_sounds import fade, fft_filter, limiter, reverb

SR = s.SR
SRC = sys.argv[1] if len(sys.argv) > 1 else "."


def load(name):
    with wave.open(os.path.join(SRC, name + ".wav")) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768.0


def onset(x, t, search=0.15):
    """The true start of the transient near t: where the level first climbs to a third of its peak."""
    a = max(0, int((t - search) * SR))
    b = min(len(x), int((t + search) * SR))
    seg = np.abs(x[a:b])
    i = int(np.argmax(seg > seg.max() / 3.0))
    return (a + i) / SR


def cut(x, t0, t1, fin=0.002, fout=0.08):
    seg = x[max(0, int(t0 * SR)):min(len(x), int(t1 * SR))].copy()
    seg -= np.mean(seg)
    return fade(seg, fin, fout)


def norm(x, peak_db):
    return x / (np.max(np.abs(x)) + 1e-12) * 10 ** (peak_db / 20.0)


def clean(x, low=40.0):
    return fft_filter(x, low=low, slope=2.0)


def loud(x, drive, peak_db):
    """Soft-limited: the first spike of a report rounded off so the body of it carries (a real shot is loud
    for longer than its first millisecond)."""
    return limiter(x, 10 ** (peak_db / 20.0), drive)


def mix(*parts):
    """(signal, start seconds, gain) layered into one."""
    n = max(int(t * SR) + len(p) for p, t, g in parts)
    out = np.zeros(n)
    for p, t, g in parts:
        i = int(t * SR)
        out[i:i + len(p)] += p * g
    return out


out = {}

# ---- close: the PPQ's three takes, the Bersa's two, the M45's single shots - a sharp, high crack
close = [("ffsl_X_39P", t) for t in (1.41, 6.44, 10.66)] + [("ffsl_F_47P", t) for t in (0.33, 4.41)] + [("ffsl_G_31P", 3.501)]
for i, (name, t) in enumerate(close):
    x = load(name)
    t0 = onset(x, t)
    out[f"glock/shot{i + 1}"] = loud(clean(cut(x, t0 - 0.003, t0 + 1.7, fout=0.55), 60.0), 6.0, -0.6)

# ---- some way off
mids = [("ffsl_X_31P", t) for t in (1.09, 5.24)] + [("ffsl_F_41P", t) for t in (0.44, 4.81)] + [("ffsl_G_20P", 2.259)]
for i, (name, t) in enumerate(mids):
    x = load(name)
    t0 = onset(x, t)
    out[f"glock/shot_mid{i + 1}"] = loud(clean(cut(x, t0 - 0.004, t0 + 2.0, fout=0.7), 60.0), 4.5, -1.0)

# ---- far: what reaches you from a few hundred metres - the dull pop, and the country round about ringing
for i, (name, t) in enumerate(mids[:3]):
    x = load(name)
    t0 = onset(x, t)
    seg = cut(x, t0 - 0.004, t0 + 1.4, fout=0.5)
    dull = fft_filter(seg, low=70.0, high=900.0 - 120.0 * i, slope=2.0)
    wet = reverb(np.concatenate([dull, np.zeros(int(1.6 * SR))]), seconds=2.4, decay=0.55, mix=0.55, damp=1600)
    out[f"glock/shot_far{i + 1}"] = norm(fade(wet, 0.002, 0.9), -6.0)

# ---- the slide: to the rear (against the stop), then home
racks = [("bsb1985", 0.085, 0.339), ("bsb1986", 0.072, 0.368), ("bsb1984", 0.133, 1.507)]
for i, (name, b, f) in enumerate(racks):
    x = load(name)
    tb = onset(x, b, 0.05)
    tf = onset(x, f, 0.05)
    out[f"glock/slide_back{i + 1}"] = loud(clean(cut(x, tb - 0.01, min(tb + 0.3, tf - 0.02), fout=0.04), 80.0), 2.5, -1.5)
    out[f"glock/slide_forward{i + 1}"] = loud(clean(cut(x, tf - 0.01, min(len(x) / SR, tf + 0.35), fout=0.08), 80.0), 2.5, -1.0)

# ---- a magazine home in the grip: the rattle of it going into the well, the click as the catch takes it
for i, (name, t) in enumerate([("bsb1988", 0.374), ("bsb1989", 0.377), ("bsb1990", 0.474), ("bsb1991", 0.544)]):
    x = load(name)
    t0 = onset(x, t, 0.05)
    out[f"glock/mag_in{i + 1}"] = loud(clean(cut(x, t0 - 0.18, t0 + 0.3, fin=0.04, fout=0.08), 80.0), 3.0, -1.5)

# ---- a magazine out: the release button's click, and the magazine sliding down out of the well (a seating
# rattle played backwards: the same metal on polymer, travelling the other way)
for i, (click, slide) in enumerate([("bsb1993", "bsb1991"), ("bsb1992", "bsb1989")]):
    c = load(click)
    tc = onset(c, 0.09, 0.05)
    press = clean(cut(c, tc - 0.004, tc + 0.12, fout=0.04), 200.0)
    y = load(slide)
    ty = onset(y, 0.544 if slide == "bsb1991" else 0.377, 0.05)
    rattle = clean(cut(y, ty - 0.3, ty - 0.01, fin=0.03, fout=0.03), 120.0)[::-1]
    out[f"glock/mag_out{i + 1}"] = loud(mix((press, 0.0, 1.0), (rattle, 0.035, 0.75)), 2.5, -2.5)

# ---- the dead trigger: the striker's click with nothing to fire
for i, (name, t) in enumerate([("bsb1994", 0.048), ("bsb1995", 0.089), ("bsb1992", 0.084)]):
    x = load(name)
    t0 = onset(x, t, 0.05)
    out[f"glock/dry{i + 1}"] = loud(clean(cut(x, t0 - 0.005, t0 + 0.18, fout=0.05), 150.0), 2.0, -3.0)

# ---- brass on the ground
for i, name in enumerate(["bsb1355", "bsb1356", "bsb1357", "bsb1358", "bsb1359", "bsb1360"]):
    x = load(name)
    t0 = onset(x, 0.07, 0.07)
    out[f"glock/case{i + 1}"] = loud(clean(cut(x, t0 - 0.004, len(x) / SR, fout=0.2), 200.0), 2.0, -4.0)

for name, x in out.items():
    s.save(name, x)
