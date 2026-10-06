"""Sounds for the content added in 1.3: CIWS gun, fighter jet, falling bombs.

Reuses the DSP helpers of gen_sounds.py and only writes the new files (needs ffmpeg on the PATH).
"""
import numpy as np

import gen_sounds as s
from gen_sounds import (crackle, env_exp, fade, fft_filter, limiter, lowpass_sweep, make_loop, multiband_crush, n_samples, n_wave,
                        place, reverb, save, slow_mod, t_axis, terrain_echoes, white)

rng = np.random.default_rng(1303)


def ciws_burst():
    """Phalanx-style gatling burst: ~75 rounds per second fuse into a tearing BRRRT, then the echo."""
    d = 2.2
    burst = 0.85
    x = np.zeros(n_samples(d))
    rate = 75.0
    k = 0
    while k / rate < burst:
        at = k / rate + rng.uniform(-0.0015, 0.0015)
        shot = np.zeros(n_samples(0.03))
        place(shot, n_wave(1.2), 0.0, 1.0)
        place(shot, fft_filter(white(0.03), low=600, high=7000) * env_exp(0.03, 0.006, 0.0002), 0.0, 0.8)
        place(x, shot, max(0.0, at), rng.uniform(0.75, 1.0))
        k += 1
    t = t_axis(d)
    body = fft_filter(white(d), low=60, high=900) * np.interp(t, [0, 0.03, burst, burst + 0.15, d], [0, 1, 1, 0, 0]) * 0.8
    buzz = np.sin(2 * np.pi * rate * t) * np.interp(t, [0, 0.03, burst, burst + 0.05, d], [0, 1, 1, 0, 0]) * 0.5
    x = multiband_crush(x + body + buzz, (3.0, 2.4, 1.6))
    x = terrain_echoes(x, [(0.35, 0.35, 2000), (0.8, 0.22, 900)])
    x = reverb(x, seconds=1.5, decay=0.5, mix=0.3, damp=2500)
    save("ciws/fire", fade(limiter(x, 0.95, 1.5), fout=0.6))


def fighter_loop():
    """Fighter jet with afterburner: deep roar, crackling turbulence, a little compressor whine."""
    d = 4.0
    t = t_axis(d + 1.0)
    rumble = fft_filter(white(d + 1.0), high=110, slope=2) * 2.4
    roar = fft_filter(white(d + 1.0), low=80, high=1600, slope=1.3) * 1.4 * slow_mod(d + 1.0, 5, 0.3)
    hiss = fft_filter(white(d + 1.0), low=3000, high=10000) * 0.3
    pops = fft_filter(crackle(d + 1.0, 160), low=250) * 0.8
    whine = np.zeros_like(t)
    for f0, a in ((1850, 0.2), (3700, 0.08)):
        whine += np.sin(2 * np.pi * np.cumsum(f0 * (1 + 0.003 * np.sin(2 * np.pi * 0.6 * t))) / s.SR) * a
    x = multiband_crush(rumble + roar + hiss + pops + whine, (3.2, 2.2, 1.5))
    save("jet/fighter", limiter(make_loop(x, d), 0.95, 1.4))


def bomb_whistle():
    """Falling bomb: descending whistle that ends in silence (the explosion is a separate sound)."""
    d = 3.2
    t = t_axis(d)
    freq = 1500 * (380 / 1500) ** (t / d) ** 0.8
    tone = np.sin(2 * np.pi * np.cumsum(freq) / s.SR)
    air = fft_filter(white(d), low=500, high=4000) * 0.25
    x = (tone * 0.7 + air) * np.interp(t, [0, 0.6, d - 0.25, d], [0, 1, 1, 0])
    x = lowpass_sweep(x, 6000, 2500, 1.0)
    save("bomb/whistle", fade(limiter(x, 0.8, 1.2), fout=0.2))


if __name__ == "__main__":
    print("synthesizing 1.3 sounds:")
    ciws_burst()
    fighter_loop()
    bomb_whistle()
    print("sounds ok")
