"""9.9 heartbeat for the dying: "lub-dub", a dull low thump and a softer second one, as heard from
inside your own head (no real recording needed: it is a low-passed pressure pulse).
python3 gen_sounds_v13.py
"""
import numpy as np

import gen_sounds as s
from gen_sounds import env_exp, fade, fft_filter, normalize, place, t_axis, white


def thump(seconds, f0, decay, gain):
    t = t_axis(seconds)
    body = np.sin(2 * np.pi * f0 * t * (1 - 0.25 * t / seconds)) * np.exp(-t / decay)
    body += fft_filter(white(seconds), high=140.0, slope=1.5) * np.exp(-t / (decay * 0.6)) * 0.6
    return body * gain


x = np.zeros(int(s.SR * 0.9))
place(x, thump(0.25, 52.0, 0.06, 1.0), 0.0)
place(x, thump(0.22, 60.0, 0.05, 0.6), 0.28)
s.save("player/heartbeat", fade(normalize(fft_filter(x, high=400.0, slope=1.0), 0.95), 0.002, 0.1))
