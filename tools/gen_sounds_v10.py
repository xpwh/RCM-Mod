"""1.10 orbital strike sounds:

uplink/servo     the dish's azimuth/elevation drives: electric motor whine with gear mesh, spinning
                 up, running and coasting down, with the clunk of the brakes
uplink/data      a burst of uplink telemetry heard on the console speaker: FSK chirps and static
uplink/confirm   console confirmation: three tones and a relay clack
uplink/alarm     strike release: two-tone klaxon from the shelter
rod/reentry      the rod coming down: a tearing roar that rises and sharpens as it falls
supply/launch    the supply rocket lifting off: ignition crack and a long, receding roar

python3 gen_sounds_v10.py
"""
import numpy as np

import gen_sounds as s
from gen_sounds import crackle, env_exp, fade, fft_filter, limiter, n_samples, place, rocket_roar, slow_mod, t_axis, white
from gen_sounds_v5 import save

rng = np.random.default_rng(1910)


def tone(seconds, freq, phase_mod=None):
    t = t_axis(seconds)
    f = np.full(len(t), float(freq)) if np.isscalar(freq) else freq
    ph = 2 * np.pi * np.cumsum(f) / s.SR
    if phase_mod is not None:
        ph += phase_mod
    return np.sin(ph)


def servo():
    seconds = 2.6
    t = t_axis(seconds)
    # spin-up, run, coast-down profile of the motor speed
    speed = np.interp(t, [0, 0.35, 2.0, 2.45, seconds], [0.0, 1.0, 1.0, 0.0, 0.0])
    f0 = 95 + 70 * speed
    motor = tone(seconds, f0) * 0.6 + tone(seconds, f0 * 3) * 0.25 + tone(seconds, f0 * 5.02) * 0.12
    gear = tone(seconds, f0 * 9.3) * 0.18 * (0.6 + 0.4 * slow_mod(seconds, 13, 1.0))
    hiss = fft_filter(white(seconds), low=1500, high=6000) * 0.05
    x = (motor + gear + hiss) * np.clip(speed * 1.4, 0, 1)
    # brake clunks at start and end
    for at in (0.02, 2.45):
        k = fft_filter(white(0.06), low=80, high=1200) * env_exp(0.06, 0.012)
        place(x, k * 1.4, at)
    return fade(limiter(x, 0.95, 1.2), 0.005, 0.1)


def data():
    seconds = 1.6
    t = t_axis(seconds)
    x = np.zeros(len(t))
    pos = 0.05
    while pos < 1.35:
        length = rng.uniform(0.03, 0.12)
        bits = rng.integers(0, 2, int(length * 300) + 1)
        f = np.repeat(np.where(bits > 0, 2200.0, 1200.0), int(s.SR / 300) + 1)[:n_samples(length)]
        burst = tone(length, f) * 0.45
        place(x, burst * np.hanning(len(burst)) ** 0.2, pos)
        pos += length + rng.uniform(0.01, 0.06)
    static = fft_filter(white(seconds), low=300, high=4500) * 0.08 * (0.6 + 0.4 * slow_mod(seconds, 9, 1.0))
    x += static
    # radio band-limit and squelch tail
    x = fft_filter(x, low=300, high=3400)
    place(x, fft_filter(white(0.12), low=800, high=3000) * env_exp(0.12, 0.05) * 0.6, 1.42)
    return fade(limiter(x, 0.9, 1.3), 0.004, 0.05)


def confirm():
    seconds = 1.2
    x = np.zeros(n_samples(seconds))
    for i, f in enumerate((880, 1175, 1568)):
        b = tone(0.13, f) * env_exp(0.13, 0.09, 0.004) * 0.5 + tone(0.13, f * 2) * env_exp(0.13, 0.05) * 0.1
        place(x, b, 0.05 + i * 0.16)
    clack = fft_filter(white(0.05), low=600, high=5000) * env_exp(0.05, 0.008)
    place(x, clack * 0.9, 0.6)
    place(x, clack * 0.6, 0.66)
    return fade(x, 0.002, 0.08)


def alarm():
    seconds = 2.2
    t = t_axis(seconds)
    f = np.where((t * 2.5).astype(int) % 2 == 0, 620.0, 470.0)
    horn = np.sign(tone(seconds, f)) * 0.35 + tone(seconds, f * 2) * 0.2
    horn = fft_filter(horn, low=250, high=3500)
    gate = np.clip(np.sin(np.pi * np.clip(t / seconds, 0, 1)) * 3, 0, 1)
    return fade(limiter(horn * gate, 0.95, 1.4), 0.01, 0.15)


def reentry():
    seconds = 6.5
    t = t_axis(seconds)
    grow = np.clip(t / seconds, 0, 1) ** 1.6
    roar = rocket_roar(seconds, darkness=0.8) * (0.15 + 0.85 * grow)
    # the shriek of the shock layer: a high, tearing band that sharpens as it comes
    shriek = np.zeros(len(t))
    noise = white(seconds)
    for i in range(16):
        a, b = n_samples(i * seconds / 16), n_samples((i + 1) * seconds / 16)
        lo = 900 + 1500 * i / 15
        seg = fft_filter(noise, low=lo, high=lo * 2.2, slope=1.4)
        shriek[a:b] = seg[a:b]
    shriek *= grow ** 1.4 * 0.8
    crack = fft_filter(crackle(seconds, 300, min_ms=0.2, max_ms=1.5), low=1500) * grow * 0.5
    x = roar + shriek + crack
    return fade(limiter(x, 0.97, 1.8), 0.3, 0.05)


def supply_launch():
    seconds = 9.0
    t = t_axis(seconds)
    x = rocket_roar(seconds) * np.interp(t, [0, 0.5, 3.0, seconds], [0.0, 1.0, 0.85, 0.0])
    ign = fft_filter(white(0.4), low=60, high=3000) * env_exp(0.4, 0.12, 0.003)
    place(x, ign * 2.5, 0.0)
    # receding: the high end goes first
    x = s.lowpass_sweep(x, 9000, 900, 1.3)
    return fade(limiter(x, 0.97, 1.6), 0.01, 0.6)


def main():
    save("uplink/servo", servo())
    save("uplink/data", data())
    save("uplink/confirm", confirm())
    save("uplink/alarm", alarm())
    save("rod/reentry", reentry())
    save("supply/launch", supply_launch())


if __name__ == "__main__":
    main()
