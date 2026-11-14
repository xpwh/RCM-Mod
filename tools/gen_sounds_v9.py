"""1.9 explosion pass, modelled on a reference "huge explosion": a heavy punch in the 60-150 Hz band
and a broad roar at the onset (the real recorded bang on top), the fireball roar darkening as it
dies away, debris crackle, and from about 0.3 s on a very deep 20-60 Hz rumble that rolls on for
seconds. Variants for close / mid / far conventional blasts and close / mid nuclear blasts.

Needs ffmpeg and the public-domain bang (tools/import_real_sounds.py fetches it):
python3 gen_sounds_v9.py <download dir>
"""
import sys

import numpy as np

import gen_sounds as s
from gen_sounds import crackle, env_exp, fade, fft_filter, limiter, n_samples, place, sweep, t_axis, white
from gen_sounds_v5 import echo_cloud, mic_clip, save
from import_real_sounds import fetch, load, resample

rng = np.random.default_rng(1909)


def darkening(seconds, start_hz, end_hz, steps=24):
    """Broad roar whose top end closes down over time (the fireball cooling, the sound dying away)."""
    out = np.zeros(n_samples(seconds))
    noise = white(seconds)
    edges = np.linspace(0, seconds, steps + 1)
    for i in range(steps):
        cut = start_hz * (end_hz / start_hz) ** (i / (steps - 1))
        seg = fft_filter(noise, low=120, high=cut, slope=1.2)
        a, b = n_samples(edges[i]), n_samples(edges[i + 1])
        w = np.zeros(len(out))
        ramp = n_samples(0.02)
        w[max(0, a - ramp):min(len(out), b + ramp)] = 1.0
        out += seg * w / 1.0
    # overlapping windows double up at the joins; smooth that out
    return fft_filter(out, high=start_hz * 1.2) / 1.6


def blast(seconds, scale, bang, distance):
    """distance: 0 close, 1 mid, 2 far."""
    t = t_axis(seconds)
    x = np.zeros(n_samples(seconds))
    # onset: the real bang (slowed for bigger blasts) and the 60-150 Hz punch, a 25 ms swell
    b = resample(bang, 1.0 / scale)
    place(x, b * (1.3 if distance == 0 else 0.7), 0.0)
    punch = sweep(0.45 * scale, 130 / scale ** 0.3, 60 / scale ** 0.3, 0.7) * env_exp(0.45 * scale, 0.11 * scale, 0.025)
    place(x, punch * 2.0, 0.0)
    # the fireball roar, broadband and darkening
    roar = darkening(seconds, 4200 / scale ** 0.4, 700 / scale ** 0.4)
    roar *= np.exp(-t / (0.75 * scale)) * np.clip(t / 0.02, 0, 1)
    x += roar * 2.6
    # debris and burning crackle, thinning out
    deb = fft_filter(crackle(seconds, 260, decay_s=0.8 * scale, min_ms=0.3, max_ms=3.0), low=500, high=7000)
    x += deb * 0.5 * (1.0 if distance == 0 else 0.3)
    # the deep 20-60 Hz rumble that takes over after ~0.3 s and rolls on
    rum = fft_filter(white(seconds), low=18, high=65, slope=2.0)
    rum *= np.interp(t, [0, 0.3 * scale, 1.2 * scale, seconds], [0.3, 1.0, 0.85, 0.0]) ** 1.2
    echo_src = fft_filter(x, low=150, slope=1.5)   # echo the roar and the bang, not the punch
    x += rum * 9.0
    x += echo_cloud(echo_src, 120, 0.15, seconds * 0.7, 0.3, seconds * 0.25, 2500 if distance == 0 else 1200, 300)
    if distance >= 1:
        x = fft_filter(x, high=2600 if distance == 1 else 900, slope=1.4)   # air eats the highs
    x = mic_clip(x, 60.0 * scale, 4.0 if distance == 0 else 2.5)
    return fade(limiter(x, 0.995, 2.6 if distance == 0 else 2.0), fin=0.0005, fout=0.6 * scale)


def main(folder):
    bang = load(fetch(folder, "bang.ogg"), 0.57, 1.0)
    bang = bang / (np.max(np.abs(bang)) + 1e-9)
    save("explosion/near", blast(5.0, 1.0, bang, 0))
    save("explosion/mid", blast(6.0, 1.15, bang, 1))
    save("explosion/far", blast(7.0, 1.3, bang, 2))
    save("nuke/near", blast(14.0, 2.6, bang, 0))
    save("nuke/mid", blast(16.0, 2.8, bang, 1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "real_audio_cache")
