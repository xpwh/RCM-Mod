"""1.5 sound pass: more realistic explosions and a heavier fighter jet.

Overwrites the explosion/*, nuke/near|mid|far and jet/* files written by gen_sounds.py and
gen_sounds_v3.py, and adds jet/sub. What makes recordings of real blasts sound real, and is modelled here:

* an *echo cloud* instead of a few discrete echoes: hundreds of reflections off terrain, trees and
  buildings arriving over seconds, each one darker the longer its path (air absorbs the highs);
* the microphone overloading on the initial crack (hard clipping of the first milliseconds);
* the negative phase of the blast wave: the air rushing back in after the shock (suck-back whoosh);
* close by, the ground thump arriving through the soil a moment before the air shock, fragments
  whizzing past and glass and debris tinkling down afterwards.

Needs ffmpeg on the PATH. Reuses the DSP helpers of gen_sounds.py / gen_sounds_v3.py.
"""
import numpy as np

import gen_sounds as s
from gen_sounds import (crackle, debris_rain, env_exp, fade, fft_filter, friedlander, harmonics_for_sub, limiter, make_loop, multiband_crush,
                        n_samples, n_wave, normalize, place, reverb, saturate, slow_mod, sweep, t_axis, white)
from gen_sounds_v3 import comb_phasing

rng = np.random.default_rng(1505)


def save(name, x):
    """Removes DC / sub-audible drift (blast pressure pulses are one-sided) before writing."""
    x = fft_filter(x, low=12.0, slope=1.0)
    s.save(name, normalize(x, 0.99))


# ------------------------------------------------------------------------------------------ building blocks

def echo_cloud(x, count, t0, t1, gain, decay, lp_near, lp_far, bands=8):
    """Statistical reflections: `count` delayed copies between t0 and t1 seconds, louder early,
    darker the later they arrive. Grouped into `bands` delay bands that share one filtered copy."""
    out = np.zeros(len(x))
    edges = np.linspace(t0, t1, bands + 1)
    for b in range(bands):
        mid = (edges[b] + edges[b + 1]) / 2
        f = (mid - t0) / max(1e-6, t1 - t0)
        filtered = fft_filter(x, high=lp_near * (lp_far / lp_near) ** f, slope=1.5)
        for _ in range(count // bands):
            delay = rng.uniform(edges[b], edges[b + 1])
            g = gain * np.exp(-(delay - t0) / decay) * rng.uniform(0.25, 1.0)
            place(out, filtered, delay, g if rng.random() < 0.8 else -g)
    return out


def mic_clip(x, ms=40.0, drive=6.0):
    """The recorder overloading on the first crack: the onset is flat-topped (the limiter afterwards
    brings the level back up, so the crack stays loud but sounds overdriven)."""
    n = min(len(x), n_samples(ms / 1000.0))
    peak = np.max(np.abs(x[:n])) + 1e-9
    ceiling = peak * 2.0 / drive
    w = np.zeros(len(x))
    w[:n] = 1.0
    ramp = min(len(x) - n, n_samples(0.03))
    w[n:n + ramp] = np.linspace(1, 0, ramp)
    clipped = np.clip(x, -ceiling, ceiling)
    return x * (1 - w) + clipped * w


def suck_back(seconds, at, length, gain):
    """Negative phase: a low, breathy inrush of air after the shock has passed."""
    x = np.zeros(n_samples(seconds))
    rush = fft_filter(white(length), low=60, high=900, slope=1.3)
    rush *= np.interp(t_axis(length), [0, length * 0.3, length], [0, 1, 0]) ** 1.5
    return place(x, rush, at, gain)


def whizz(seconds, count, start, spread):
    """Fragments flying past: short, fast Doppler-falling hisses."""
    x = np.zeros(n_samples(seconds))
    for _ in range(count):
        dur = rng.uniform(0.08, 0.25)
        tt = t_axis(dur)
        f = rng.uniform(1800, 4200) * (0.55 ** (tt / dur))
        tone = np.sin(2 * np.pi * np.cumsum(f) / s.SR) * 0.4 + fft_filter(white(dur), low=1500, high=8000) * 0.6
        tone *= np.interp(tt, [0, dur * 0.45, dur], [0, 1, 0])
        place(x, tone, start + abs(rng.normal(0, spread)), rng.uniform(0.15, 0.45))
    return x


def glass_tinkle(seconds, count, start, spread):
    """Shards and small debris raining down: bright, ringing ticks."""
    x = np.zeros(n_samples(seconds))
    for _ in range(count):
        dur = rng.uniform(0.04, 0.18)
        tt = t_axis(dur)
        f0 = rng.uniform(2500, 7000)
        ping = (np.sin(2 * np.pi * f0 * tt) + 0.5 * np.sin(2 * np.pi * f0 * 2.76 * tt)) * np.exp(-tt / (dur * 0.25))
        place(x, ping, start + abs(rng.normal(0, spread)), rng.uniform(0.03, 0.15))
    return x


def ground_thump(seconds, at, freq, length, gain):
    """Ground-coupled shock: arrives through the soil, felt more than heard."""
    x = np.zeros(n_samples(seconds))
    th = sweep(length, freq, freq * 0.45, 0.6) * env_exp(length, length * 0.3, 0.004)
    return place(x, th, at, gain)


# ------------------------------------------------------------------------------------------ explosions

def explosion_near():
    """A heavy warhead a few dozen blocks away."""
    d = 10.0
    t = t_axis(d)
    x = ground_thump(d, 0.0, 70, 0.6, 1.8)
    crack = np.zeros(n_samples(d))
    place(crack, n_wave(10.0), 0.03, 2.4)
    place(crack, fft_filter(white(0.06), low=1200) * env_exp(0.06, 0.01, 0.0002) * 1.4, 0.03)
    x += crack
    x += place(np.zeros(n_samples(d)), fft_filter(friedlander(1.2, 32.0, 1.4), high=900) * 3.2, 0.03)
    blast = saturate(normalize(fft_filter(white(1.6), high=2500) * env_exp(1.6, 0.24, 0.0008)), 7.0) * 1.7
    place(x, blast, 0.033)
    boom = sweep(3.5, 90, 24, 0.35) * env_exp(3.5, 0.8, 0.003) * 2.4
    boom += fft_filter(white(3.5), high=320) * env_exp(3.5, 1.0, 0.004) * 2.8
    place(x, boom, 0.03)
    x += suck_back(d, 0.35, 1.2, 0.9)
    x += fft_filter(crackle(d, 180, decay_s=1.4, start=0.08), low=500) * 0.55
    x += whizz(d, 7, 0.05, 0.25)
    x += debris_rain(d, 0.9, 260, 1.1) * 0.8
    x += glass_tinkle(d, 70, 1.4, 0.9)
    dry = x.copy()
    x += echo_cloud(dry, 160, 0.25, 5.5, 0.32, 1.8, 2200, 280)
    x += fft_filter(white(d), high=240) * env_exp(d, 2.8, 0.3) * slow_mod(d, 1.1, 0.65) * 1.8
    x = reverb(x, seconds=3.5, decay=1.3, mix=0.2, damp=3000)
    x = mic_clip(multiband_crush(x, (4.2, 2.6, 1.6)), 45.0, 5.0)
    save("explosion/near", fade(limiter(x, 0.99, 1.7), fout=2.0))


def explosion_mid():
    """Some hundred blocks away: the crack has lost its edge, the hills answer for seconds."""
    d = 11.0
    t = t_axis(d)
    x = np.zeros(n_samples(d))
    place(x, fft_filter(n_wave(14.0), high=3000), 0.0, 1.8)
    place(x, fft_filter(friedlander(1.4, 45.0, 1.3), high=450) * 2.8, 0.0)
    place(x, fft_filter(white(3.0), high=380) * env_exp(3.0, 0.9, 0.008) * 2.4, 0.0)
    x += suck_back(d, 0.5, 1.6, 0.5)
    dry = x.copy()
    x += echo_cloud(dry, 200, 0.35, 8.0, 0.45, 2.6, 1200, 180)
    x += fft_filter(white(d), high=200) * env_exp(d, 3.2, 0.3) * slow_mod(d, 0.9, 0.7) * 2.0
    x = reverb(x, seconds=4.5, decay=2.0, mix=0.3, damp=1000)
    save("explosion/mid", fade(limiter(multiband_crush(x, (3.6, 2.2, 1.4)), 0.99, 1.7), fout=2.5))


def explosion_far():
    """Kilometres away: no crack at all any more, a dull whump and long rolling thunder."""
    d = 11.0
    x = np.zeros(n_samples(d))
    whump = sweep(3.0, 62, 22, 0.4) * env_exp(3.0, 0.9, 0.04) * 2.2
    whump += fft_filter(white(3.0), high=200) * env_exp(3.0, 1.1, 0.05) * 2.6
    place(x, whump, 0.0)
    dry = x.copy()
    x += echo_cloud(dry, 220, 0.6, 9.5, 0.55, 3.2, 420, 120)
    x += fft_filter(white(d), high=140) * env_exp(d, 3.6, 0.5) * slow_mod(d, 0.7, 0.75) * 2.0
    x = reverb(x, seconds=5.0, decay=2.4, mix=0.45, damp=400)
    x = fft_filter(x, high=380)
    x[: n_samples(0.08)] *= np.linspace(0, 1, n_samples(0.08))
    save("explosion/far", fade(limiter(multiband_crush(x, (3.2, 1.8, 1.2)), 0.99, 1.7), fout=2.5))


def explosion_sub():
    d = 8.0
    tone = sweep(d, 55, 20, 0.3) * env_exp(d, 1.5, 0.003)
    rumble = fft_filter(white(d), high=70, slope=3) * env_exp(d, 2.4, 0.01) * slow_mod(d, 1.3, 0.55)
    thump = ground_thump(d, 0.0, 45, 0.8, 1.0)
    x = harmonics_for_sub(normalize(tone) * 1.2 + normalize(rumble) + normalize(thump) * 0.8)
    save("explosion/sub", fade(limiter(x, 0.99, 2.6), fout=1.8))


def nuke_near():
    """Inside the blast zone: the ground heaves, an overloaded crack, a furnace roar, a minute of thunder."""
    d = 20.0
    t = t_axis(d)
    x = ground_thump(d, 0.0, 40, 2.0, 2.4)
    place(x, n_wave(16.0), 0.12, 2.6)
    place(x, fft_filter(white(0.12), low=900) * env_exp(0.12, 0.03, 0.0003) * 1.6, 0.12)
    place(x, fft_filter(friedlander(3.5, 180.0, 1.3), high=380) * 3.8, 0.12)
    place(x, saturate(normalize(fft_filter(white(3.5), high=1700) * env_exp(3.5, 0.6, 0.001)), 8.0) * 2.2, 0.125)
    boom = sweep(7.0, 62, 15, 0.3) * env_exp(7.0, 2.4, 0.008) * 2.8
    boom += fft_filter(white(7.0), high=480) * env_exp(7.0, 1.5, 0.005) * 3.3
    place(x, boom, 0.12)
    x += suck_back(d, 1.4, 4.0, 1.2)
    furnace = fft_filter(white(d), low=35, high=650, slope=1.3) * slow_mod(d, 3.5, 0.5)
    furnace *= np.interp(t, [0, 0.4, 4.5, 11.0, 20.0], [0, 1, 0.85, 0.3, 0]) * 1.7
    x += furnace
    x += fft_filter(crackle(d, 280, decay_s=4.5, start=0.15), low=400) * 0.6
    x += debris_rain(d, 2.0, 600, 2.5) * 0.7
    x += glass_tinkle(d, 160, 2.5, 2.0)
    dry = x.copy()
    x += echo_cloud(dry, 260, 0.5, 14.0, 0.38, 4.5, 1500, 160)
    x += fft_filter(white(d), high=190) * env_exp(d, 7.0, 0.4) * slow_mod(d, 0.8, 0.75) * 2.8
    x = reverb(x, seconds=6.0, decay=2.6, mix=0.26, damp=2000)
    x = mic_clip(multiband_crush(x, (4.6, 3.0, 1.7)), 120.0, 6.0)
    save("nuke/near", fade(limiter(x, 0.99, 1.8), fout=3.5))


def nuke_mid():
    """A few kilometres away: one sharp thump that rattles everything, then endless rolling thunder."""
    d = 20.0
    t = t_axis(d)
    x = np.zeros(n_samples(d))
    place(x, fft_filter(n_wave(30.0), high=1600), 0.0, 2.0)
    place(x, fft_filter(friedlander(3.5, 240.0, 1.2), high=220) * 3.4, 0.0)
    rattle = fft_filter(crackle(2.0, 900, decay_s=0.5, min_ms=0.2, max_ms=0.8), low=1500, high=6000) * 0.35
    place(x, rattle, 0.05)  # windows and doors rattling in their frames
    swell = np.interp(t, [0, 0.1, 2.8, 20], [0, 1, 0.8, 0])
    x += sweep(d, 34, 15, 0.4) * swell * 1.7
    dry = x.copy()
    x += echo_cloud(dry, 280, 0.8, 15.0, 0.5, 5.0, 520, 130)
    x += fft_filter(white(d), high=170) * swell * slow_mod(d, 0.6, 0.8) * 2.8
    x = reverb(x, seconds=6.0, decay=3.0, mix=0.4, damp=700)
    save("nuke/mid", fade(limiter(multiband_crush(x, (4.0, 2.4, 1.3)), 0.99, 1.9), fout=3.5))


def nuke_far():
    """Far beyond the horizon: a deep pressure swell and rumble that seems to come from everywhere."""
    d = 20.0
    t = t_axis(d)
    swell = np.interp(t, [0, 0.4, 2.5, 20], [0, 1, 0.8, 0])
    x = sweep(d, 34, 14, 0.4) * swell * 2.0
    dry = x.copy()
    x += echo_cloud(dry, 240, 1.0, 16.0, 0.55, 6.0, 260, 100)
    x += fft_filter(white(d), high=110) * swell * slow_mod(d, 0.5, 0.8) * 3.0
    x = reverb(x, seconds=7.0, decay=3.2, mix=0.55, damp=400)
    x = fft_filter(x, high=240)
    save("nuke/far", fade(limiter(multiband_crush(x, (3.6, 2.0, 1.2)), 0.99, 2.0), fout=3.5))


# ------------------------------------------------------------------------------------------ jet

def jet_crackle(seconds, rate):
    """Shock-associated noise of a supersonic exhaust: asymmetric, steep-fronted pops (skewed noise)."""
    n = n_samples(seconds)
    out = np.zeros(n)
    hits = np.nonzero(rng.random(n) < rate / s.SR)[0]
    for i in hits:
        ms = rng.uniform(0.15, 1.2)
        m = max(3, n_samples(ms / 1000.0))
        pop = np.concatenate([np.linspace(0, 1, max(2, m // 6)), np.linspace(1, -0.35, m - max(2, m // 6))])
        pop *= rng.uniform(0.2, 1.0) ** 1.3
        end = min(n, i + len(pop))
        out[i:end] += pop[: end - i]
    return out


def fighter_loop():
    """Close fighter in military power: tearing roar, crackle, buzz-saw fan tones, ground phasing."""
    d = 6.0
    full = d + 1.0
    t = t_axis(full)
    rumble = fft_filter(white(full), high=80, slope=2) * 3.0
    roar = fft_filter(white(full), low=60, high=2000, slope=1.2) * 1.7 * slow_mod(full, 4, 0.35)
    tear = fft_filter(white(full), low=1200, high=7000, slope=1.5) * 0.55 * slow_mod(full, 11, 0.55)
    pops = fft_filter(jet_crackle(full, 260), low=300) * 1.6
    buzz = np.zeros_like(t)
    for k in range(1, 7):  # fan blade-passing tones and their "buzz-saw" harmonics, wobbling
        f = 410 * k * (1 + 0.006 * np.sin(2 * np.pi * 0.4 * t + k))
        buzz += np.sign(np.sin(2 * np.pi * np.cumsum(f) / s.SR)) * 0.05 / k
    whine = np.sin(2 * np.pi * np.cumsum(3100 * (1 + 0.003 * np.sin(2 * np.pi * 0.3 * t))) / s.SR) * 0.08
    x = rumble + roar + tear + pops + fft_filter(buzz, high=5000) + whine
    x = comb_phasing(x, (0.4, 5.0), 0.15, 0.7)
    x = multiband_crush(x, (3.8, 2.6, 1.7))
    save("jet/fighter", limiter(make_loop(x, d), 0.97, 1.6))


def fighter_far_loop():
    """Distant jet: a deep, rolling, thunder-like rumble; the air has eaten the highs."""
    d = 6.0
    full = d + 1.0
    rumble = fft_filter(white(full), high=65, slope=2) * 3.2
    body = fft_filter(white(full), low=35, high=420, slope=1.6) * 1.7
    x = (rumble + body) * slow_mod(full, 1.1, 0.5) * slow_mod(full, 4.5, 0.22)
    x = comb_phasing(x, (2.0, 14.0), 0.08, 0.65)
    x = fft_filter(x, high=550, slope=1.5)
    save("jet/fighter_far", limiter(make_loop(x, d), 0.97, 1.7))


def afterburner_loop():
    """Afterburner: ripping, popping flame roar with a pulsing low end (screech-free, raw)."""
    d = 5.0
    full = d + 1.0
    roar = fft_filter(white(full), low=40, high=1400, slope=1.2) * 2.0 * slow_mod(full, 13, 0.55)
    rip = fft_filter(jet_crackle(full, 700), low=180, high=10000) * 2.2
    thump = fft_filter(crackle(full, 30, min_ms=4.0, max_ms=16.0), high=280) * 1.8
    pulse = fft_filter(white(full), high=90, slope=2) * (1 + 0.5 * np.sin(2 * np.pi * 7.5 * t_axis(full))) * 2.0
    x = multiband_crush(roar + rip + thump + pulse, (4.4, 3.2, 2.2))
    save("jet/afterburner", limiter(make_loop(x, d), 0.97, 1.8))


def jet_sub_loop():
    """Chest-thumping low end of a close fighter (its own layer, so it adds to the volume cap)."""
    d = 5.0
    full = d + 1.0
    x = fft_filter(white(full), high=55, slope=3) * slow_mod(full, 2.0, 0.5)
    x += sweep(full, 48, 44, 1.0) * 0.4
    x = harmonics_for_sub(normalize(x))
    save("jet/sub", limiter(make_loop(x, d), 0.97, 2.4))


def jet_boom():
    """Fighter sonic boom: two shocks (bow and tail) that overload the mic, rattling, rolling echo."""
    d = 6.0
    x = np.zeros(n_samples(d))
    for at, g in ((0.0, 1.0), (0.11, 0.9)):
        place(x, n_wave(5.0) * g * 2.2, at)
        place(x, fft_filter(white(0.06), low=700) * env_exp(0.06, 0.01, 0.0002) * 0.9 * g, at)
    place(x, fft_filter(crackle(1.5, 900, decay_s=0.35, min_ms=0.2, max_ms=0.8), low=1500, high=6000) * 0.4, 0.02)
    t = t_axis(d)
    x += fft_filter(white(d), high=110, slope=2) * np.exp(-t / 1.3) * np.clip(t / 0.05, 0, 1) * 1.8
    dry = x.copy()
    x += echo_cloud(dry, 140, 0.3, 4.5, 0.35, 1.6, 1500, 250)
    x = reverb(x, seconds=3.0, decay=1.1, mix=0.25, damp=2500)
    x = mic_clip(x, 30.0, 5.0)
    save("jet/boom", fade(limiter(x, 0.99, 1.7), fout=1.2))


if __name__ == "__main__":
    print("synthesizing 1.5 sounds:")
    explosion_near()
    explosion_mid()
    explosion_far()
    explosion_sub()
    nuke_near()
    nuke_mid()
    nuke_far()
    fighter_loop()
    fighter_far_loop()
    afterburner_loop()
    jet_sub_loop()
    jet_boom()
    print("sounds ok")
