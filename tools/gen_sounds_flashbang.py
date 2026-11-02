"""The stun grenade's bang: 170 dB in a few milliseconds - a hard, dry, ear-splitting crack with almost no low end
(there is little charge in it), the pressure slapping back off walls and ground, and a short bright ring.
python3 gen_sounds_flashbang.py
"""
import numpy as np

import gen_sounds as s
from gen_sounds import env_exp, fade, fft_filter, limiter, place, reverb, saturate, t_axis, terrain_echoes, white


def bang(far=False):
    d = 2.8
    x = np.zeros(s.n_samples(d))
    # the crack itself: an N-wave of under two milliseconds and a white burst on top of it
    place(x, s.n_wave(1.6), 0.0, 1.0)
    burst = fft_filter(white(0.09), low=900) * env_exp(0.09, 0.012, 0.0001)
    place(x, burst, 0.0, 1.1)
    # the body: a short punch, mid-heavy
    body = fft_filter(white(0.35), low=120, high=3500) * env_exp(0.35, 0.05, 0.0005)
    place(x, body, 0.001, 0.9)
    # a bright metallic ring of the casing and the room
    t = t_axis(0.6)
    ring = (np.sin(2 * np.pi * 3150 * t) * 0.5 + np.sin(2 * np.pi * 4720 * t) * 0.3) * np.exp(-t / 0.08)
    place(x, ring, 0.002, 0.25)
    x = terrain_echoes(x, [(0.012, 0.5, 6000), (0.031, 0.35, 4500), (0.07, 0.25, 3000)])
    x = saturate(x * 2.5, 2.0)
    x = reverb(x, 1.6, 0.35, 0.3, 5000)
    if far:
        x = fft_filter(x, high=1500)
    return fade(limiter(x, 0.99, 2.2), 0.0002, 0.4)


if __name__ == "__main__":
    s.save("grenade/flashbang_bang", bang())
    s.save("grenade/flashbang_far", bang(far=True))
