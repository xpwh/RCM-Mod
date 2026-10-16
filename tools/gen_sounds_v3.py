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


def comb_phasing(x, depth_ms=(0.6, 7.0), rate_hz=0.18, mix=0.8):
    """Ground-reflection comb filter with a slowly sweeping delay: the hollow 'phasing' roar of a flyby."""
    n = len(x)
    t = np.arange(n) / s.SR
    lo, hi = depth_ms
    delay = (lo + (hi - lo) * 0.5 * (1 + np.sin(2 * np.pi * rate_hz * t))) / 1000.0 * s.SR
    idx = np.clip(np.arange(n) - delay, 0, n - 1)
    i0 = np.floor(idx).astype(int)
    frac = idx - i0
    delayed = x[i0] * (1 - frac) + x[np.minimum(i0 + 1, n - 1)] * frac
    return x + delayed * mix


def fighter_loop():
    """Close fighter jet: tearing broadband roar, crackle, turbine whine and ground-reflection phasing."""
    d = 6.0
    full = d + 1.0
    t = t_axis(full)
    rumble = fft_filter(white(full), high=90, slope=2) * 2.6
    roar = fft_filter(white(full), low=70, high=2200, slope=1.2) * 1.5 * slow_mod(full, 4, 0.3)
    tear = fft_filter(white(full), low=1500, high=7000, slope=1.5) * 0.45 * slow_mod(full, 9, 0.5)
    pops = fft_filter(crackle(full, 120, min_ms=0.3, max_ms=1.6), low=400) * 1.0
    whine = np.zeros_like(t)
    for f0, a in ((1150, 0.16), (2300, 0.12), (4600, 0.06), (7900, 0.03)):
        whine += np.sin(2 * np.pi * np.cumsum(f0 * (1 + 0.004 * np.sin(2 * np.pi * 0.35 * t + f0))) / s.SR) * a
    x = rumble + roar + tear + pops + whine
    x = comb_phasing(x, (0.4, 5.0), 0.15, 0.75)
    x = multiband_crush(x, (3.4, 2.4, 1.6))
    save("jet/fighter", limiter(make_loop(x, d), 0.95, 1.5))


def fighter_far_loop():
    """Distant jet: the air has eaten the highs; a deep, rolling, thunder-like rumble."""
    d = 6.0
    full = d + 1.0
    rumble = fft_filter(white(full), high=70, slope=2) * 3.0
    body = fft_filter(white(full), low=40, high=450, slope=1.6) * 1.6
    x = (rumble + body) * slow_mod(full, 1.2, 0.45) * slow_mod(full, 5, 0.2)
    x = comb_phasing(x, (2.0, 14.0), 0.08, 0.6)
    x = fft_filter(x, high=600, slope=1.5)
    save("jet/fighter_far", limiter(make_loop(x, d), 0.95, 1.6))


def afterburner_loop():
    """Afterburner: raw, ripping, popping flame roar on top of the engine noise."""
    d = 5.0
    full = d + 1.0
    roar = fft_filter(white(full), low=50, high=1400, slope=1.2) * 1.8 * slow_mod(full, 12, 0.5)
    rip = fft_filter(crackle(full, 420, min_ms=0.2, max_ms=1.2), low=200, high=9000) * 1.6
    thump = fft_filter(crackle(full, 25, min_ms=4.0, max_ms=14.0), high=300) * 1.5
    x = multiband_crush(roar + rip + thump, (4.0, 3.0, 2.0))
    save("jet/afterburner", limiter(make_loop(x, d), 0.95, 1.7))


def jet_boom():
    """Fighter sonic boom: two sharp shocks ~0.12 s apart (bow and tail), then rolling rumble."""
    d = 5.0
    x = np.zeros(n_samples(d))
    for at, g in ((0.0, 1.0), (0.12, 0.85)):
        place(x, n_wave(4.0) * g, at)
        place(x, fft_filter(white(0.05), low=800) * env_exp(0.05, 0.008, 0.0002) * 0.6 * g, at)
    t = t_axis(d)
    rumble = fft_filter(white(d), high=120, slope=2) * np.exp(-t / 1.2) * np.clip(t / 0.05, 0, 1) * 1.6
    x += rumble
    x = terrain_echoes(x, [(0.6, 0.35, 1200), (1.4, 0.25, 600), (2.3, 0.15, 400)])
    x = reverb(x, seconds=3.0, decay=1.1, mix=0.3, damp=2500)
    save("jet/boom", fade(limiter(x, 0.98, 1.6), fout=1.0))


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
    fighter_far_loop()
    afterburner_loop()
    jet_boom()
    bomb_whistle()
    print("sounds ok")
