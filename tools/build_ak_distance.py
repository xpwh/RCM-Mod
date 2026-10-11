"""9.31.3: the AK heard from a distance, made from the mod's real AK recordings (see CREDITS.md):

  ak/shot_mid1..3   ~40-150 blocks: the close range recordings (Felix Blume, CC0) carried through the air -
                    the crack's top end absorbed, the ground bounce smearing it, and most of what reaches you
                    the open country ringing after it (a long outdoor reverb, early echoes off trees and hills)
  ak/shot_far1..4   150+ blocks: the real distant gunfire recordings (army training in Denmark, CC BY 4.0;
                    hunting rifles in Provence, CC0), duller still and with a longer tail rolling away

Nothing is pushed up to full scale any more: a distant shot keeps its soft attack and its tail is as loud,
relative to the bang, as it really is. The game sets how loud each layer plays by distance (GunAudio).

usage: python3 tools/build_ak_distance.py   (reads and rewrites the ogg files in place)
"""
import os
import subprocess

import numpy as np

import gen_sounds as s
from gen_sounds import fft_filter, fade, lowpass_sweep

SR = s.SR
AK = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "sounds", "ak")
rng = np.random.default_rng(31)


def load(name):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", os.path.join(AK, name + ".ogg"), "-f", "s16le", "-ac", "1", "-ar", str(SR), "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.int16).astype(float) / 32768.0


def save(name, x):
    x = fade(x, 0.002, 0.25)
    s.OUT = os.path.join(os.path.dirname(AK))
    s.save("ak/" + name, s.normalize(x, 0.9))


def impulse(seconds, t60, predelay, bright, dark, echoes):
    """An outdoor reverb: a diffuse tail decaying over t60, darkening as it goes, plus discrete echoes."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    tail = rng.standard_normal(n) * np.exp(-6.9 * t / t60)
    tail = lowpass_sweep(tail, bright, dark, curve=0.6)
    # build up softly: open country has no early wall slap
    tail *= np.clip(t / 0.06, 0, 1) ** 1.5
    tail[: int(predelay * SR)] = 0
    for at, gain in echoes:
        i = int(at * SR)
        if i < n:
            k = int(0.03 * SR)
            burst = rng.standard_normal(k) * np.exp(-np.arange(k) / (0.008 * SR))
            tail[i:i + k] += burst[: n - i] * gain
    return tail / (np.sqrt(np.sum(tail ** 2)) + 1e-9)


def convolve(x, ir):
    n = len(x) + len(ir) - 1
    m = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, m) * np.fft.rfft(ir, m), m)[:n]


def distant(x, cutoff, ground_ms, direct, ir, seconds):
    """A shot heard from far off: air absorption, the ground reflection, little direct sound, much reverb."""
    y = fft_filter(x, low=70.0, high=cutoff, slope=1.3)
    d = int(ground_ms / 1000 * SR)
    y = y + np.concatenate([np.zeros(d), y])[: len(y)] * 0.7
    y = s.normalize(y)
    wet = s.normalize(convolve(y, ir))
    out = np.zeros(int(seconds * SR))
    dry = y[: len(out)]
    out[: len(dry)] += dry * direct
    out += wet[: len(out)] * (1.0 - direct) * 1.4
    return out


def main():
    close = [load("shot%d" % i) for i in range(1, 6)]
    for i in range(3):
        ir = impulse(2.2, 1.7, 0.02, 3500.0, 700.0, [(0.11 + 0.03 * i, 0.5), (0.27, 0.35), (0.48 + 0.05 * i, 0.25), (0.9, 0.15)])
        save("shot_mid%d" % (i + 1), distant(close[i], 2400.0, 4.0 + i, 0.5, ir, 2.2))
    for i in range(4):
        far = load("shot_far%d" % (i + 1))
        ir = impulse(3.0, 2.4, 0.04, 1600.0, 380.0, [(0.18 + 0.04 * i, 0.45), (0.42, 0.35), (0.85 + 0.1 * i, 0.3), (1.5, 0.2)])
        save("shot_far%d" % (i + 1), distant(far, 1300.0, 9.0, 0.45, ir, 3.0))


if __name__ == "__main__":
    main()
