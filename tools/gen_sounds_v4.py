"""Sounds for the content added in 1.4: the laser defense beam.

Reuses the DSP helpers of gen_sounds.py and only writes the new files (needs ffmpeg on the PATH).
"""
import numpy as np

import gen_sounds as s
from gen_sounds import crackle, fade, fft_filter, limiter, reverb, save, slow_mod, t_axis, white


def laser_beam():
    """High-energy laser firing: mains hum of the power plant, a rising electrical whine and the
    hiss and crackle of air and metal burning at the focus."""
    d = 1.1
    t = t_axis(d)
    hum = sum(np.sin(2 * np.pi * f * t) * a for f, a in ((100, 0.5), (200, 0.3), (300, 0.18), (500, 0.08)))
    whine = np.sin(2 * np.pi * np.cumsum(2400 + 500 * t / d) / s.SR) * 0.18
    hiss = fft_filter(white(d), low=3000, high=12000) * 0.35 * slow_mod(d, 15, 0.5)
    sizzle = fft_filter(crackle(d, 600, min_ms=0.1, max_ms=0.6), low=1500) * 0.5
    env = np.interp(t, [0, 0.04, d - 0.08, d], [0, 1, 1, 0])
    x = (hum + whine + hiss + sizzle) * env
    x = reverb(x, seconds=0.8, decay=0.25, mix=0.2, damp=6000)
    save("laser/beam", fade(limiter(x, 0.9, 1.3), fout=0.05))


if __name__ == "__main__":
    print("synthesizing 1.4 sounds:")
    laser_beam()
    print("sounds ok")
