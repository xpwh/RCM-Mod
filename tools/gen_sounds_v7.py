"""1.7 sounds: AC-130 turboprops, MQ-9 pusher prop, AH-64 rotor, 105 mm howitzer, 40 mm Bofors,
30 mm chain gun.

* Propellers and rotors are heard mostly as their blade-passing frequency: a periodic thump train
  (AC-130: 4 blades x 1020 rpm = 68 Hz, MQ-9: 3 blades, ~80 Hz buzz, AH-64: 4 blades x 289 rpm =
  19 Hz "wop-wop" blade slap) on top of the turbine whine and broadband roar.
* Guns: a sharp muzzle blast (N-wave) with a low body, then the long rolling echo.

Needs ffmpeg on the PATH. Reuses the DSP helpers of gen_sounds.py / gen_sounds_v5.py.
"""
import numpy as np

import gen_sounds as s
from gen_sounds import (env_exp, fade, fft_filter, harmonics_for_sub, limiter, make_loop, multiband_crush, n_samples, n_wave, normalize, place,
                        reverb, slow_mod, sweep, t_axis, white)
from gen_sounds_v3 import comb_phasing
from gen_sounds_v5 import echo_cloud, mic_clip, save

rng = np.random.default_rng(1707)


def pulse_train(seconds, freq, width, jitter=0.0, shape=2.0):
    """Periodic soft pulses at `freq` Hz (blade passing), optionally with slow frequency wobble."""
    t = t_axis(seconds)
    f = freq * (1 + jitter * slow_mod(seconds, 0.5, 1.0))
    phase = np.cumsum(f) / s.SR
    frac = phase % 1.0
    return np.exp(-((frac - 0.5) / width) ** 2 * shape)


def tone(seconds, freq, wobble=0.004, rate=0.3):
    t = t_axis(seconds)
    return np.sin(2 * np.pi * np.cumsum(freq * (1 + wobble * np.sin(2 * np.pi * rate * t))) / s.SR)


def ac130_engine():
    """Four Allison T56 turboprops: a deep throbbing propeller drone with beating between engines."""
    d = 8.0
    full = d + 1.0
    x = np.zeros(n_samples(full))
    for k, f in enumerate((68.0, 68.6, 67.5, 69.1)):  # four props slightly out of sync -> beating
        p = pulse_train(full, f, 0.18, 0.003)
        x += fft_filter(p - p.mean(), high=900, slope=1.5) * 0.6
    x += fft_filter(white(full), low=40, high=1400, slope=1.4) * 1.2 * slow_mod(full, 2.0, 0.25)
    x += fft_filter(white(full), high=90, slope=2) * 1.6
    x += tone(full, 1740, 0.003) * 0.05 + tone(full, 3480, 0.003) * 0.02  # turbine whine
    x = comb_phasing(x, (1.0, 6.0), 0.1, 0.6)
    save("ac130/engine", limiter(make_loop(x, d), 0.97, 1.5))


def reaper_engine():
    """MQ-9: a single turboprop pusher, a nasal buzzing drone, quieter and higher than a big transport."""
    d = 6.0
    full = d + 1.0
    p = pulse_train(full, 82.0, 0.12, 0.004)
    x = fft_filter(p - p.mean(), high=2400, slope=1.3) * 0.9
    x += fft_filter(white(full), low=120, high=2200, slope=1.4) * 0.6 * slow_mod(full, 1.5, 0.3)
    x += tone(full, 2900, 0.002) * 0.05
    x = comb_phasing(x, (1.0, 5.0), 0.1, 0.5)
    save("reaper/engine", limiter(make_loop(x, d), 0.97, 1.4))


def apache_rotor():
    """AH-64: the heavy 'wop-wop' blade slap of the main rotor, the buzzing tail rotor, turbine whine."""
    d = 6.0
    full = d + 1.0
    t = t_axis(full)
    slap = pulse_train(full, 19.3, 0.06, 0.002, shape=3.0)
    slap = fft_filter(slap - slap.mean(), low=30, high=1800, slope=1.2) * 1.4
    body = fft_filter(white(full), high=160, slope=2) * 1.6 * (0.6 + 0.4 * pulse_train(full, 19.3, 0.2, 0.002))
    tail = pulse_train(full, 93.0, 0.15, 0.003)
    tail = fft_filter(tail - tail.mean(), high=1500) * 0.35
    whine = tone(full, 6200, 0.002) * 0.04 + tone(full, 1550, 0.003) * 0.05
    hiss = fft_filter(white(full), low=1500, high=7000, slope=1.4) * 0.3
    x = slap + body + tail + whine + hiss
    save("apache/rotor", limiter(make_loop(x, d), 0.97, 1.5))


def gun_shot(d, blast, body_hz, body_len, echo_gain, tail):
    x = np.zeros(n_samples(d))
    place(x, n_wave(blast) * 2.0, 0.0)
    place(x, fft_filter(white(0.05), low=600) * env_exp(0.05, 0.008, 0.0002) * 0.8, 0.0)
    place(x, sweep(body_len, body_hz, body_hz * 0.5, 0.7) * env_exp(body_len, body_len * 0.25, 0.002) * 1.4, 0.0)
    x += fft_filter(white(d), high=150, slope=2) * np.exp(-t_axis(d) / tail) * np.clip(t_axis(d) / 0.02, 0, 1) * 1.2
    dry = x.copy()
    x += echo_cloud(dry, 120, 0.2, d * 0.75, echo_gain, d * 0.25, 1800, 300)
    x = reverb(x, seconds=2.5, decay=0.9, mix=0.25, damp=2500)
    return mic_clip(x, 25.0, 4.0)


def gun_105():
    """M102 105 mm howitzer firing out of the side door: a huge flat crack and a long rolling boom."""
    x = gun_shot(6.0, 8.0, 70.0, 0.5, 0.4, 0.9)
    save("ac130/gun_105", fade(limiter(x, 0.99, 1.7), fout=1.5))


def gun_40():
    """L/60 Bofors 40 mm: two quick heavy thumps (it fires 120 rounds a minute)."""
    d = 3.0
    x = np.zeros(n_samples(d))
    for at in (0.0, 0.5):
        place(x, gun_shot(1.5, 3.5, 110.0, 0.2, 0.25, 0.3), at)
    save("ac130/gun_40", fade(limiter(x, 0.99, 1.6), fout=0.8))


def chain_gun():
    """M230 30 mm chain gun: a short burst, about ten rounds a second, each a sharp bark."""
    d = 2.5
    x = np.zeros(n_samples(d))
    for i in range(4):
        place(x, gun_shot(1.2, 2.5, 160.0, 0.12, 0.2, 0.2) * rng.uniform(0.85, 1.0), i * 0.1 + rng.uniform(0, 0.008))
    save("apache/chain_gun", fade(limiter(x, 0.99, 1.6), fout=0.8))


if __name__ == "__main__":
    print("synthesizing 1.7 sounds:")
    ac130_engine()
    reaper_engine()
    apache_rotor()
    gun_105()
    gun_40()
    chain_gun()
