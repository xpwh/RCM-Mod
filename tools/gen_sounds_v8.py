"""1.8 sounds: the shock wave crack - the brutal, overloading BANG the moment a blast wave reaches you.

Every explosion type gets its own crack, mastered as loud as possible (hard limited, the onset
clipped like an overloaded microphone):
* shock_he        conventional high explosive: a razor-sharp crack with a hard slap off the ground
* shock_heavy     big bombs (MOAB, 155 mm, 105 mm): a wider, deeper N-wave with a chest punch
* shock_thermo    fuel-air: a small pop, then the huge pressure WHUMP and the air sucking back
* shock_bunker    bunker buster: a muffled ground-borne thud that kicks, then the crack
* shock_nuke      nuclear: the double crack of the incident wave and the Mach stem, then thunder
* shock_emp       high-altitude burst: a distant, dry double crack with a crackling tail
* shock_antimatter annihilation: a crack with a metallic, ringing, phasing resonance

Needs ffmpeg on the PATH. Reuses the DSP helpers of gen_sounds.py / gen_sounds_v5.py.
"""
import numpy as np

import gen_sounds as s
from gen_sounds import env_exp, fade, fft_filter, limiter, n_samples, n_wave, place, reverb, sweep, t_axis, white
from gen_sounds_v5 import echo_cloud, mic_clip, save

rng = np.random.default_rng(1808)


def crack(length_ms, gain=1.0):
    """Steep-fronted N-wave: the shock front itself (rise in a fraction of a millisecond)."""
    n = n_wave(length_ms) * gain
    return n


def thump(seconds, f0, f1, decay, gain):
    return sweep(seconds, f0, f1, 0.6) * env_exp(seconds, decay, 0.001) * gain


def air(seconds, low, high, decay, gain, attack=0.0005):
    return fft_filter(white(seconds), low=low, high=high, slope=1.3) * env_exp(seconds, decay, attack) * gain


def master(name, x, clip_ms=60.0, drive=8.0, lim=2.6):
    x = mic_clip(x, clip_ms, drive)
    save(name, fade(limiter(x, 0.995, lim), fin=0.0005, fout=0.3))


def shock_he():
    d = 3.0
    x = np.zeros(n_samples(d))
    place(x, crack(3.5, 2.6), 0.0)
    place(x, air(0.08, 900, 12000, 0.012, 1.6), 0.0)            # the crack's ripping top end
    place(x, thump(0.35, 95, 40, 0.07, 2.2), 0.0)               # punch in the chest
    place(x, crack(2.5, 1.2), 0.018)                            # ground reflection slap
    x += echo_cloud(x.copy(), 80, 0.08, 2.2, 0.35, 0.7, 3500, 500)
    master("explosion/shock_he", x)


def shock_heavy():
    d = 4.0
    x = np.zeros(n_samples(d))
    place(x, crack(7.0, 2.8), 0.0)
    place(x, air(0.15, 500, 9000, 0.02, 1.5), 0.0)
    place(x, thump(0.7, 70, 28, 0.15, 2.8), 0.0)
    place(x, crack(5.0, 1.4), 0.03)
    place(x, air(1.5, 30, 300, 0.35, 1.2, 0.005), 0.01)          # rolling low pressure
    x += echo_cloud(x.copy(), 120, 0.1, 3.0, 0.4, 1.0, 2500, 350)
    master("explosion/shock_heavy", x, 80.0, 9.0, 2.8)


def shock_thermo():
    d = 4.5
    x = np.zeros(n_samples(d))
    place(x, crack(2.0, 0.8), 0.0)                              # the dispersal charge
    place(x, air(0.05, 1500, 9000, 0.01, 0.6), 0.0)
    whump = thump(1.0, 55, 22, 0.25, 3.2) + air(1.0, 20, 400, 0.3, 2.2, 0.01)
    place(x, whump, 0.12)                                       # the fuel cloud detonates
    place(x, crack(9.0, 2.6), 0.12)
    rush = fft_filter(white(1.8), low=60, high=1200, slope=1.3) * np.interp(t_axis(1.8), [0, 0.6, 1.8], [0, 1, 0]) ** 1.5
    place(x, rush, 0.9, 1.1)                                    # air rushing back into the void
    x += echo_cloud(x.copy(), 100, 0.15, 3.0, 0.35, 1.0, 2200, 300)
    master("explosion/shock_thermo", x, 200.0, 7.0, 2.6)


def shock_bunker():
    d = 4.0
    x = np.zeros(n_samples(d))
    place(x, thump(0.9, 45, 18, 0.25, 3.4), 0.0)                # through the ground first
    place(x, fft_filter(white(0.6), high=180, slope=2) * env_exp(0.6, 0.12, 0.002) * 2.0, 0.0)
    place(x, crack(6.0, 2.2), 0.06)
    place(x, air(0.12, 400, 6000, 0.03, 1.0), 0.06)
    place(x, air(2.0, 25, 250, 0.5, 1.4, 0.01), 0.1)
    x += echo_cloud(x.copy(), 90, 0.12, 2.6, 0.3, 0.9, 1800, 300)
    master("explosion/shock_bunker", x, 120.0, 7.0, 2.6)


def shock_nuke():
    d = 9.0
    x = np.zeros(n_samples(d))
    place(x, crack(14.0, 3.0), 0.0)                             # incident wave
    place(x, crack(12.0, 2.6), 0.22)                            # Mach stem
    place(x, air(0.3, 300, 8000, 0.05, 1.8), 0.0)
    place(x, thump(1.6, 40, 16, 0.4, 3.5), 0.0)
    place(x, air(6.0, 18, 220, 1.8, 2.4, 0.02), 0.05)           # the thunder rolling on
    place(x, air(4.0, 200, 2500, 1.0, 0.6, 0.05), 0.3)          # wind and debris noise
    x += echo_cloud(x.copy(), 200, 0.3, 6.5, 0.45, 2.0, 1500, 200)
    x = reverb(x, seconds=4.0, decay=1.6, mix=0.25, damp=1800)
    master("explosion/shock_nuke", x, 400.0, 6.0, 2.8)


def shock_emp():
    d = 6.0
    x = np.zeros(n_samples(d))
    place(x, crack(10.0, 1.6), 0.0)
    place(x, crack(10.0, 1.3), 0.35)
    place(x, air(4.5, 20, 160, 1.4, 1.6, 0.02), 0.0)
    sizzle = fft_filter(white(2.5), low=2500, high=9000) * (rng.random(n_samples(2.5)) < 0.004) * 0.6
    place(x, sizzle * env_exp(2.5, 0.8, 0.01), 0.2)
    x += echo_cloud(x.copy(), 150, 0.3, 5.0, 0.35, 1.6, 1200, 200)
    master("explosion/shock_emp", x, 200.0, 5.0, 2.4)


def shock_antimatter():
    d = 6.0
    x = np.zeros(n_samples(d))
    place(x, crack(5.0, 3.0), 0.0)
    place(x, air(0.2, 1000, 14000, 0.03, 1.8), 0.0)
    place(x, thump(1.2, 60, 20, 0.3, 3.0), 0.0)
    t = t_axis(3.5)
    ring = sum(np.sin(2 * np.pi * f * t * (1 - 0.15 * t / 3.5)) / (k + 1) for k, f in enumerate((220, 331, 497, 746, 1119)))
    ring *= env_exp(3.5, 0.9, 0.01) * 0.9 * (1 + 0.5 * np.sin(2 * np.pi * 3.0 * t))
    place(x, ring, 0.02)
    x += echo_cloud(x.copy(), 120, 0.15, 4.0, 0.35, 1.3, 3000, 400)
    master("explosion/shock_antimatter", x, 100.0, 7.0, 2.6)


if __name__ == "__main__":
    print("synthesizing 1.8 shock cracks:")
    shock_he()
    shock_heavy()
    shock_thermo()
    shock_bunker()
    shock_nuke()
    shock_emp()
    shock_antimatter()
