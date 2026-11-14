"""12.27: the Nether's ash storm - a hot, dry gale: a deep roar of moving air, gusts howling through the basalt as
whistling resonances that rise and fall, and the hiss and tick of ash and embers driven against everything.
A seamless loop. python3 gen_sounds_ashstorm.py
"""
import numpy as np

import gen_sounds as s
from gen_sounds import SR, crackle, fft_filter, make_loop, normalize, slow_mod, t_axis, white

rng = np.random.default_rng(27)


def brown(seconds):
    b = np.cumsum(white(seconds))
    b -= fft_filter(b, high=4.0)
    return normalize(b)


def howl(seconds, f0, depth):
    """A gust whistling through a gap: a narrow resonance in the noise, its pitch wandering with the gust."""
    n = s.n_samples(seconds)
    t = t_axis(seconds)
    noise = white(seconds)
    out = np.zeros(n)
    block = 2048
    for i in range(0, n, block):
        tt = t[i]
        f = f0 * (1.0 + depth * np.sin(2 * np.pi * 0.11 * tt + 1.3) * np.sin(2 * np.pi * 0.047 * tt))
        seg = noise[i:i + block * 2]
        y = fft_filter(seg, low=f * 0.93, high=f * 1.07, slope=4.0)[:block]
        out[i:i + len(y)] = y[: n - i]
    return normalize(out)


def main():
    d = 14.0
    roar = fft_filter(brown(d), high=320) * slow_mod(d, 0.13, 0.5) * 1.3
    rush = fft_filter(white(d), low=180, high=2600, slope=1.5) * slow_mod(d, 0.21, 0.6) * 0.45
    whistle = howl(d, 620.0, 0.25) * slow_mod(d, 0.09, 0.9) * 0.22 + howl(d, 1100.0, 0.2) * slow_mod(d, 0.17, 0.9) * 0.12
    grit = fft_filter(crackle(d, 900.0, min_ms=0.2, max_ms=0.9), low=2500) * slow_mod(d, 0.3, 0.7) * 0.25
    x = roar + rush + whistle + grit
    s.save("nether/ash_storm", normalize(make_loop(x, 12.0, xfade=2.0), 0.85), quality=6)


if __name__ == "__main__":
    main()
