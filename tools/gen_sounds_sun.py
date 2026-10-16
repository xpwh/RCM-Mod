"""The Sun Destroyer: the Helios launch complex counting down and firing, the round in flight, and the artificial
star at the target - imploding, igniting, burning, collapsing, going nova.
python3 gen_sounds_sun.py
"""
import numpy as np

import gen_sounds as s
from gen_sounds import (crackle, debris_rain, env_exp, fade, fft_filter, friedlander, limiter, lowpass_sweep, make_loop, multiband_crush,
                        normalize, place, reverb, saturate, slow_mod, sweep, t_axis, terrain_echoes, white, harmonics_for_sub, pad)

rng = np.random.default_rng(7)


def tone(seconds, freq_curve, shape="sin"):
    """An oscillator following freq_curve (Hz per sample)."""
    phase = 2 * np.pi * np.cumsum(freq_curve) / s.SR
    if shape == "saw":
        return 2.0 * ((phase / (2 * np.pi)) % 1.0) - 1.0
    if shape == "square":
        return np.sign(np.sin(phase))
    return np.sin(phase)


def brown(seconds):
    w = white(seconds)
    b = np.cumsum(w)
    b -= fft_filter(b, high=8.0)
    return normalize(b)


def metal_hit(seconds, f0, decay, partials=(1.0, 2.76, 5.40, 8.93), gain=1.0):
    """A struck steel plate or beam: inharmonic modes ringing down."""
    t = t_axis(seconds)
    x = np.zeros_like(t)
    for i, p in enumerate(partials):
        x += np.sin(2 * np.pi * f0 * p * t + rng.uniform(0, 6)) * np.exp(-t / (decay / (1 + i * 0.6))) / (1 + i * 0.5)
    x += fft_filter(white(seconds), low=800) * env_exp(seconds, 0.01, 0.0003) * 0.8
    return x * gain


def outdoors(x, size=1.0):
    """The complex stands in the open: a big slap-back off the plinth and the land, then a long tail."""
    x = terrain_echoes(x, [(0.09 * size, 0.35, 4000), (0.31 * size, 0.22, 1800), (0.74 * size, 0.14, 900)])
    return reverb(x, seconds=3.0 * size, decay=0.9 * size, mix=0.25, damp=2500)


# ------------------------------------------------------------------------------------------ the complex

def load():
    d = 3.2
    x = np.zeros(s.n_samples(d))
    hiss = fft_filter(white(1.4), low=1500, high=9000) * np.interp(t_axis(1.4), [0, 0.1, 1.1, 1.4], [0, 0.4, 0.3, 0])
    whine = tone(1.4, 180 + 140 * np.clip(t_axis(1.4) / 1.2, 0, 1)) * 0.12
    place(x, hiss + whine, 0.0)
    place(x, metal_hit(1.8, 96.0, 0.5, gain=1.0), 1.25)
    place(x, metal_hit(1.2, 210.0, 0.25, gain=0.5), 1.45)
    place(x, fft_filter(white(0.6), high=300) * env_exp(0.6, 0.12, 0.002) * 1.2, 1.25)
    return fade(normalize(reverb(x, 1.5, 0.4, 0.25, 3000), 0.95), 0.002, 0.3)


def klaxon():
    """The launch klaxon: a hoarse, honking two-note horn, twice, ringing out over the complex."""
    d = 5.0
    x = np.zeros(s.n_samples(d))
    for i in range(2):
        seg = 1.35
        t = t_axis(seg)
        f = np.where(t < 0.62, 330.0 + 30 * np.clip(t / 0.08, 0, 1), 262.0)
        horn = tone(seg, f, "saw") * 0.7 + tone(seg, f * 2.01, "saw") * 0.25
        horn = saturate(horn * 1.5, 1.8)
        # a nasal horn's formants
        horn = fft_filter(horn, low=200, high=2600) + 0.6 * fft_filter(horn, low=900, high=1300, slope=3)
        env = np.clip(t / 0.03, 0, 1) * np.clip((seg - t) / 0.06, 0, 1)
        place(x, horn * env, i * 1.6)
    return fade(normalize(outdoors(x, 1.3), 0.95), 0.002, 0.6)


def abort():
    d = 4.5
    t = t_axis(d)
    f = 60 + 900 * np.exp(-t / 0.9)
    x = tone(d, f, "saw") * np.exp(-t / 1.6) * 0.4 + tone(d, f * 0.5) * np.exp(-t / 1.8) * 0.4
    x = fft_filter(x, high=3000)
    for at in (0.05, 0.25, 0.4):
        place(x, metal_hit(0.4, 400.0 + rng.uniform(-60, 60), 0.06, gain=0.5), at)
    place(x, fft_filter(white(1.5), low=2000) * np.interp(t_axis(1.5), [0, 0.05, 1.5], [0, 0.3, 0]), 0.3)
    return fade(normalize(reverb(x, 2.0, 0.6, 0.25), 0.9), 0.002, 0.5)


def doors():
    """Two armoured leaves, tons each, grinding apart on their rails."""
    d = 6.5
    t = t_axis(d)
    env = np.interp(t, [0, 0.3, 0.6, 4.8, 5.3, d], [0, 0.6, 1, 1, 0.4, 0])
    motor = (tone(d, 48.0 + 4 * np.sin(t * 2)) * 0.5 + tone(d, 96.0) * 0.25 + tone(d, 144.0) * 0.12)
    grind = fft_filter(white(d), low=120, high=900) * slow_mod(d, 6.0, 0.6)
    squeal = fft_filter(white(d), low=1800, high=2400, slope=4) * slow_mod(d, 1.5, 0.9) * 0.35
    rumble = fft_filter(brown(d), high=120) * 0.8
    x = (motor * 0.5 + grind + squeal + rumble) * env
    place(x, metal_hit(1.2, 70.0, 0.4, gain=2.2), 0.05)
    place(x, metal_hit(1.2, 64.0, 0.5, gain=2.6), 5.15)
    place(x, fft_filter(white(0.6), high=250) * env_exp(0.6, 0.15) * 2.0, 5.15)
    return fade(normalize(outdoors(x), 0.95), 0.01, 0.6)


def lift():
    d = 7.0
    t = t_axis(d)
    env = np.interp(t, [0, 0.4, 5.6, 6.0, d], [0, 1, 1, 0.2, 0])
    pump = tone(d, 50.0 + 6 * t / d) * 0.6 + tone(d, 100.0 + 12 * t / d, "saw") * 0.18
    hyd = fft_filter(white(d), low=600, high=4000) * 0.25 * slow_mod(d, 3, 0.4)
    whine = tone(d, 300 + 260 * np.clip(t / 5.5, 0, 1)) * 0.06
    x = (pump + hyd + whine) * env
    place(x, metal_hit(1.0, 120.0, 0.3, gain=1.6), 5.75)
    place(x, fft_filter(white(0.8), low=1000) * env_exp(0.8, 0.3) * 0.5, 5.85)
    return fade(normalize(outdoors(x, 0.8), 0.95), 0.01, 0.5)


def charge():
    """Fourteen coils charging: transformer hum swelling, the capacitor whine climbing for fifteen seconds, the
    hiss and spit of corona discharge growing, then the held scream of full charge before the shot."""
    d = 19.5
    t = t_axis(d)
    ramp = np.clip(t / 15.0, 0, 1)
    f = 70.0 * (2600.0 / 70.0) ** (ramp ** 1.4)
    whine = tone(d, f) * 0.35 + tone(d, f * 1.5) * 0.1 + tone(d, f * 0.5) * 0.15
    # held at full charge, warbling
    held = t > 15.0
    whine *= 0.5 + 0.5 * ramp
    whine *= 1.0 + 0.25 * held * np.sin(2 * np.pi * 7.0 * t)
    hum = (tone(d, np.full_like(t, 50.0), "saw") * 0.5 + tone(d, np.full_like(t, 100.0)) * 0.4) * (0.2 + 0.8 * ramp)
    hum = fft_filter(hum, high=900)
    corona = fft_filter(white(d), low=3000, high=12000) * (0.03 + 0.25 * ramp ** 2) * slow_mod(d, 8, 0.6)
    pops = crackle(d, 40.0, start=2.0) * (0.1 + 0.6 * ramp)
    rumble = fft_filter(brown(d), high=90) * (0.2 + 0.8 * ramp)
    x = whine + hum * 0.6 + corona + pops * 0.5 + rumble * 0.5
    x *= np.interp(t, [0, 0.5, d - 0.15, d], [0, 1, 1, 0])
    return fade(normalize(outdoors(x, 0.9), 0.95), 0.05, 0.1)


def arc():
    d = 0.9
    x = np.zeros(s.n_samples(d))
    place(x, s.n_wave(1.2) * 1.0, 0.0)
    buzz_len = 0.35
    bt = t_axis(buzz_len)
    buzz = tone(buzz_len, np.full_like(bt, 120.0), "saw") * 0.6 + fft_filter(white(buzz_len), low=1500) * 0.7
    buzz *= np.exp(-bt / 0.12) * (0.6 + 0.4 * np.sign(np.sin(2 * np.pi * 37 * bt)))
    place(x, buzz, 0.002)
    place(x, crackle(0.5, 600.0, decay_s=0.08) * 0.8, 0.0)
    return fade(normalize(reverb(x, 1.2, 0.3, 0.3, 6000), 0.95), 0.0005, 0.2)


def fire(far=False):
    """The shot: the coils fire in sequence in a few milliseconds - a sharp electric tear rising up the barrel, the
    round breaking the air at the muzzle with a crack like a lightning strike, the ground thump, and it all rolling
    away over the land."""
    d = 8.0
    x = np.zeros(s.n_samples(d))
    if not far:
        tear_len = 0.25
        tt = t_axis(tear_len)
        tear = tone(tear_len, 300 + 5000 * (tt / tear_len) ** 2, "saw") * np.interp(tt, [0, 0.2, 0.25], [0.2, 1, 0])
        place(x, fft_filter(tear, low=200) * 0.6, 0.0)
        place(x, crackle(0.4, 3000.0, decay_s=0.1) * 0.7, 0.05)
        place(x, s.shock_crack(0.3, 0.0, length_ms=3.5), 0.22, 1.6)
    boom = friedlander(3.0, 90.0) * env_exp(3.0, 0.9)
    boom = fft_filter(boom, high=900 if not far else 260) * 2.0
    place(x, boom, 0.22)
    sub = tone(3.0, 38.0 * np.exp(-t_axis(3.0) / 2.0)) * env_exp(3.0, 0.7) * 1.5
    place(x, harmonics_for_sub(sub), 0.22)
    rumble = fft_filter(brown(d), high=200 if not far else 120) * np.interp(t_axis(d), [0, 0.3, 1.5, d], [0, 1, 0.6, 0]) * 0.8
    x += rumble
    x = terrain_echoes(x, [(0.6, 0.4, 900), (1.4, 0.3, 500), (2.6, 0.2, 300)])
    x = multiband_crush(x)
    x = reverb(x, 4.0, 1.4, 0.3, 1500)
    if far:
        x = lowpass_sweep(x, 900, 250, blocks=24)
    return fade(limiter(x, 0.97), 0.001, 1.0)


def flight():
    """The round going over: a deep tearing roar of air and plasma, crackling, looping."""
    d = 7.0
    t = t_axis(d)
    roar = fft_filter(brown(d), high=500) * slow_mod(d, 2.0, 0.35)
    tear = fft_filter(white(d), low=400, high=3000) * 0.35 * slow_mod(d, 5.0, 0.5)
    cr = crackle(d, 90.0) * 0.4
    hum = tone(d, 72.0 + 3 * np.sin(2 * np.pi * 0.4 * t)) * 0.2
    x = roar + tear + fft_filter(cr, high=6000) + hum
    x = make_loop(normalize(x), 6.0, 1.0)
    return normalize(x, 0.95)


# ------------------------------------------------------------------------------------------ the star

def implode():
    """The core collapsing: everything sucked inwards - a rising reversed rush - into a deep, hollow thoom."""
    d = 3.6
    t = t_axis(2.0)
    rush = fft_filter(white(2.0), low=150, high=5000) * (t / 2.0) ** 3
    rush = lowpass_sweep(rush, 400, 6000, curve=2.0, blocks=24)
    rise = tone(2.0, 40 + 400 * (t / 2.0) ** 3) * (t / 2.0) ** 2 * 0.5
    x = np.zeros(s.n_samples(d))
    place(x, rush + rise, 0.0)
    thoom = tone(1.6, 55.0 * np.exp(-t_axis(1.6) / 0.8)) * env_exp(1.6, 0.5, 0.004) * 1.6
    place(x, harmonics_for_sub(thoom), 2.0)
    place(x, fft_filter(white(1.0), high=400) * env_exp(1.0, 0.2) * 0.6, 2.0)
    return fade(normalize(reverb(x, 3.0, 1.0, 0.3, 1800), 0.95), 0.01, 0.6)


def ignite():
    """A star igniting: a huge deep blast, and then the roar of fire swelling up out of it and holding."""
    d = 9.0
    x = np.zeros(s.n_samples(d))
    boom = friedlander(3.0, 140.0) * env_exp(3.0, 1.2)
    place(x, fft_filter(boom, high=700) * 2.5, 0.0)
    sub = tone(4.0, 30.0 + 12 * np.exp(-t_axis(4.0) / 1.0)) * env_exp(4.0, 1.4, 0.01) * 1.6
    place(x, harmonics_for_sub(sub), 0.0)
    t = t_axis(d)
    swell = np.interp(t, [0, 0.4, 2.5, 7.5, d], [0, 0.4, 1.0, 0.9, 0.0])
    roar = fft_filter(brown(d), high=600) * slow_mod(d, 1.5, 0.4) * swell
    fire_ = fft_filter(white(d), low=300, high=2500) * 0.3 * slow_mod(d, 4.0, 0.5) * swell
    x += roar * 1.2 + fire_ + crackle(d, 60.0) * 0.3 * swell
    x = multiband_crush(x, drives=(2.6, 2.0, 1.4))
    x = reverb(x, 4.0, 1.5, 0.3, 1600)
    return fade(limiter(x, 0.97), 0.002, 1.2)


def burn():
    """The star burning: a deep, endless roar, the rumble of the ground, flaring and crackling."""
    d = 9.0
    t = t_axis(d)
    roar = fft_filter(brown(d), high=380) * slow_mod(d, 0.8, 0.35)
    body = fft_filter(white(d), low=120, high=1400) * 0.4 * slow_mod(d, 2.5, 0.5)
    drone = tone(d, 34.0 + 2 * np.sin(2 * np.pi * 0.2 * t)) * 0.4 + tone(d, 51.0) * 0.15
    cr = fft_filter(crackle(d, 45.0), high=5000) * 0.35
    x = roar + body + drone + cr
    x = make_loop(normalize(reverb(x, 2.5, 1.0, 0.25, 1500)), 8.0, 1.0)
    return normalize(x, 0.95)


def collapse():
    d = 3.2
    t = t_axis(d)
    f = 220.0 * np.exp(-t / 0.9) + 25.0
    groan = tone(d, f, "saw") * 0.5 + tone(d, f * 0.5) * 0.6
    groan = fft_filter(groan, high=1200) * np.interp(t, [0, 0.2, 2.6, d], [0, 1, 0.7, 0])
    rush = fft_filter(white(d), low=200, high=4000) * np.interp(t, [0, 2.0, 2.9, d], [0.5, 0.3, 1.0, 0]) * 0.5
    x = groan + rush
    return fade(normalize(reverb(x, 3.0, 1.0, 0.35, 2000), 0.95), 0.01, 0.2)


def nova(far=False):
    """The nova: the blast of the century - a crack that hits the chest, a long, deep rolling boom, the wind of the
    shock, and rocks and glass raining down."""
    d = 14.0 if not far else 16.0
    x = np.zeros(s.n_samples(d))
    if not far:
        place(x, s.shock_crack(0.4, 0.0, length_ms=12.0), 0.0, 2.0)
    boom = friedlander(5.0, 260.0, decay=1.2) * env_exp(5.0, 2.0)
    place(x, fft_filter(boom, high=1200 if not far else 220) * 2.6, 0.0)
    sub = tone(6.0, 24.0 + 18 * np.exp(-t_axis(6.0) / 1.5)) * env_exp(6.0, 2.2, 0.005) * 2.0
    place(x, harmonics_for_sub(sub), 0.0)
    t = t_axis(d)
    roll = fft_filter(brown(d), high=260 if not far else 140) * slow_mod(d, 1.2, 0.6) * np.interp(t, [0, 0.5, 3.0, d], [0, 1, 0.7, 0])
    x += roll * 1.4
    if not far:
        wind = fft_filter(white(d), low=150, high=2500) * np.interp(t, [0, 0.2, 2.5, 7.0, d], [0, 0.8, 0.5, 0.1, 0]) * slow_mod(d, 3, 0.5)
        x += wind * 0.6
        x += debris_rain(d, 2.0, 160, 2.5) * 0.7
    x = terrain_echoes(x, [(0.8, 0.45, 700), (2.0, 0.35, 400), (3.6, 0.25, 250), (5.5, 0.15, 180)])
    x = multiband_crush(x, drives=(3.2, 2.4, 1.6))
    x = reverb(x, 5.0, 2.0, 0.35, 1200)
    if far:
        x = lowpass_sweep(x, 600, 160, blocks=24)
    return fade(limiter(x, 0.98), 0.0005, 2.0)


if __name__ == "__main__":
    s.save("sun/load", load())
    s.save("sun/klaxon", klaxon())
    s.save("sun/abort", abort())
    s.save("sun/doors", doors())
    s.save("sun/lift", lift())
    s.save("sun/charge", charge())
    s.save("sun/arc", arc())
    s.save("sun/fire", fire())
    s.save("sun/fire_far", fire(far=True))
    s.save("sun/flight", flight())
    s.save("sun/implode", implode())
    s.save("sun/ignite", ignite())
    s.save("sun/burn", burn())
    s.save("sun/collapse", collapse())
    s.save("sun/nova", nova())
    s.save("sun/nova_far", nova(far=True))
