"""1.6 sound pass: the A-10's GAU-8 "BRRRT", the A-10's TF34 turbofans and the B-2's buried engines.

* GAU-8: 65 rounds a second fuse into one tearing, saw-toothed buzz with a 65 Hz fundamental; every
  round is still a sharp supersonic crack on top, the barrel cluster spins up and winds down, and the
  whole burst rolls back off the terrain for seconds (the famous long "brrrt" echo).
* TF34: the A-10's high-bypass fans give a piercing, slightly unsteady whistle (blade-passing tone
  and its harmonics) over a soft airy roar - the "hog whine".
* B-2: four engines buried deep in the wing with shielded exhausts: almost no whine, a smooth, very
  deep and wide rumble that seems to come from everywhere.

Needs ffmpeg on the PATH. Reuses the DSP helpers of gen_sounds.py / gen_sounds_v5.py.
"""
import numpy as np

import gen_sounds as s
from gen_sounds import (env_exp, fade, fft_filter, harmonics_for_sub, limiter, make_loop, multiband_crush, n_samples, normalize, place,
                        reverb, slow_mod, sweep, t_axis, white)
from gen_sounds_v3 import comb_phasing
from gen_sounds_v5 import echo_cloud, jet_crackle, mic_clip, save

rng = np.random.default_rng(1606)


def gau8_burst():
    """1.2 s burst of 30 mm at 3900 rounds/min, then a long rolling echo off the terrain."""
    d = 7.0
    burst = 1.2
    rate = 65.0
    x = np.zeros(n_samples(d))
    t = 0.0
    k = 0
    while t < burst:
        spin = min(1.0, 0.55 + t / 0.25)  # the barrels are still spinning up for the first rounds
        g = rng.uniform(0.8, 1.0) * (0.7 + 0.3 * spin)
        crack_len = 0.012
        tt = t_axis(crack_len)
        crack = np.where(tt < crack_len * 0.15, tt / (crack_len * 0.15), 1 - 1.35 * (tt - crack_len * 0.15) / (crack_len * 0.85))
        place(x, crack * g * 1.2, t)  # muzzle blast / sonic crack of the round
        place(x, fft_filter(white(0.02), low=900, high=9000) * env_exp(0.02, 0.004, 0.0003) * 0.6 * g, t)
        boom_len = 0.05
        place(x, np.sin(2 * np.pi * 70 * t_axis(boom_len)) * env_exp(boom_len, 0.015, 0.001) * 0.9 * g, t)
        k += 1
        t += (1.0 / rate) / spin * rng.uniform(0.97, 1.03)
    tt = t_axis(d)
    # the saw-tooth "buzz" the separate cracks fuse into, plus the gun drive whining
    buzz_env = np.interp(tt, [0, 0.05, burst, burst + 0.08, d], [0, 1, 1, 0, 0])
    phase = np.cumsum(rate * np.clip(0.55 + tt / 0.25, 0, 1)) / s.SR
    saw = 2 * (phase % 1.0) - 1
    x += fft_filter(saw, high=1800, slope=1.2) * buzz_env * 0.55
    drive = sweep(d, 900, 620, 1.0) * np.interp(tt, [0, 0.2, burst, burst + 0.6, d], [0, 0.12, 0.1, 0, 0])
    x += drive
    x += fft_filter(white(d), high=120, slope=2) * buzz_env * 1.6  # chest-pounding low end
    dry = x.copy()
    x += echo_cloud(dry, 160, 0.25, 4.5, 0.42, 1.8, 2200, 300)
    x = reverb(x, seconds=3.5, decay=1.3, mix=0.3, damp=2200)
    x = mic_clip(x, 1200.0, 3.5)
    x = multiband_crush(x, (3.0, 2.4, 1.8))
    save("a10/gun", fade(limiter(x, 0.99, 1.8), fout=1.5))


def a10_engine_loop():
    """TF34 at full power: the piercing fan whistle over an airy roar."""
    d = 6.0
    full = d + 1.0
    t = t_axis(full)
    roar = fft_filter(white(full), low=50, high=1800, slope=1.3) * 1.5 * slow_mod(full, 3, 0.3)
    rumble = fft_filter(white(full), high=90, slope=2) * 1.8
    hiss = fft_filter(white(full), low=2500, high=9000, slope=1.5) * 0.35
    whistle = np.zeros_like(t)
    for f0, g in ((2650, 0.32), (5300, 0.12), (1325, 0.1), (3975, 0.06)):
        wob = 1 + 0.008 * np.sin(2 * np.pi * 0.35 * t + f0) + 0.004 * slow_mod(full, 2, 1.0)
        whistle += np.sin(2 * np.pi * np.cumsum(f0 * wob) / s.SR) * g
    whistle *= 0.75 + 0.25 * slow_mod(full, 1.5, 1.0)
    x = rumble + roar + hiss + whistle
    x = comb_phasing(x, (0.5, 4.0), 0.12, 0.6)
    save("a10/engine", limiter(make_loop(x, d), 0.97, 1.4))


def b2_engine_loop():
    """B-2 overhead: deep, smooth, wide rumble with only a faint, muffled turbine tone."""
    d = 7.0
    full = d + 1.0
    t = t_axis(full)
    sub = fft_filter(white(full), high=60, slope=2) * 3.4
    body = fft_filter(white(full), low=30, high=500, slope=1.6) * 1.8 * slow_mod(full, 0.8, 0.35)
    air = fft_filter(white(full), low=400, high=1600, slope=1.6) * 0.35
    tone = np.sin(2 * np.pi * np.cumsum(820 * (1 + 0.004 * np.sin(2 * np.pi * 0.2 * t))) / s.SR) * 0.05
    x = sub + body + air + tone
    x = comb_phasing(x, (1.5, 9.0), 0.1, 0.55)
    x = harmonics_for_sub(normalize(x))
    save("b2/engine", limiter(make_loop(x, d), 0.97, 1.5))


if __name__ == "__main__":
    print("synthesizing 1.6 sounds:")
    gau8_burst()
    a10_engine_loop()
    b2_engine_loop()
