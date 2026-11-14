"""Thunder: a close strike's tearing crack - the channel heating in microseconds, a ripping, crackling report
from the stepped leader's branches arriving one after another - then the deep boom and a long rumble rolling
away off the hills; from far off only the low rolling rumble, the highs gone.
python3 gen_sounds_storm.py  (replaced: the thunder now comes from real recordings, tools/import_thunder_sounds.py)
"""
import numpy as np

import gen_sounds as s
from gen_sounds import crackle, env_exp, fade, fft_filter, limiter, lowpass_sweep, normalize, place, reverb, saturate, slow_mod, t_axis, terrain_echoes, white

rng = np.random.default_rng(11)


def brown(seconds):
    b = np.cumsum(white(seconds))
    b -= fft_filter(b, high=6.0)
    return normalize(b)


def thunder(near, variant):
    d = 11.0 if near else 12.0
    x = np.zeros(s.n_samples(d))
    t = t_axis(d)
    start = 0.0
    if near:
        # the rip: a fast run of sharp cracks along the channel, a few milliseconds apart, then the main report
        at = 0.0
        for k in range(int(rng.integers(14, 28))):
            place(x, s.n_wave(rng.uniform(0.6, 2.2)) * rng.uniform(0.3, 1.0), at, 1.0)
            at += rng.uniform(0.004, 0.03)
        place(x, crackle(0.6, 2500.0, decay_s=0.15) * 0.8, 0.0)
        place(x, s.n_wave(6.0) * 1.0, at + 0.02, 1.4)
        start = at
    # the boom: low, swelling, then rolling in irregular waves as sound from further along the channel arrives
    env = np.interp(t, [0, start + 0.05, start + 0.6, start + 2.2, d * 0.7, d], [0, 0.4 if near else 0.0, 1.0, 0.7, 0.25, 0])
    roll = slow_mod(d, 1.2 + variant * 0.4, 0.75) * slow_mod(d, 3.5, 0.35)
    rumble = fft_filter(brown(d), high=180 if not near else 320) * env * roll
    body = fft_filter(white(d), low=60, high=900 if near else 260) * env * roll * (0.35 if near else 0.15)
    x += rumble * 1.4 + body
    # low swell
    sub = np.sin(2 * np.pi * np.cumsum(28 + 10 * np.exp(-t / 2.0)) / s.SR) * env * 0.6
    x += sub
    x = terrain_echoes(x, [(0.7, 0.35, 600), (1.6, 0.28, 400), (2.9, 0.2, 250)])
    x = reverb(x, 4.0, 1.6, 0.35, 1400)
    if not near:
        x = lowpass_sweep(x, 700, 180, blocks=24)
    return fade(limiter(x, 0.97, 1.2), 0.0005, 1.5)


if __name__ == "__main__":
    for i in range(3):
        s.save(f"storm/thunder_near{i + 1}", thunder(True, i))
        s.save(f"storm/thunder_far{i + 1}", thunder(False, i))
