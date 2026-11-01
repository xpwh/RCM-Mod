"""Gore sounds from real recordings (opengameart.org, CC0 / CC BY / OGA-BY - see CREDITS.md): a round going into a body,
bone breaking, a round stopped by a soft vest, pain and death voices, an artery spurting, a throat gurgling.

usage: python3 import_gore_sounds.py <dir with the 44.1 kHz mono WAVs, named as below>
  towel_NN      qubodup, "40 wet towel club/pound/hit/attack sounds" (CC0)       - the thud of a hit on a body
  splat_NN      qubodup / Johannes Pinter, "8 wet squish slurp impacts" (CC0)    - the wet part of it
  wetbreak_N    Zane Little Music, "Fleshy bone break/snap SFX" (CC0)
  fight_*       willleamon, "Fleshy fight sounds" (OGA-BY 3.0)                     - armoured (vest) hits
  emo_N         emopreben, "Pain sounds" (CC0)
  michel_*      Michel Baradari, "11 male human pain/death sounds" (CC BY 3.0)
  haeldb_*      haeldb, "Male grunt/yelling sounds" (CC0)
  bard_long     thebardofblasphemy, "Grunts - male death and pain" (CC0)
  rd_*          rubberduck, "40 CC0 water / splash / slime SFX" (CC0)
"""
import numpy as np

import gen_sounds as s
from gen_sounds import fade, fft_filter
from import_gun_sounds import SR, envelope, load, peaks, save, stretch


def trim(x, floor_db=-42.0, pad=0.01):
    """Silence cut off both ends."""
    env = envelope(x, 5.0)
    thr = env.max() * 10 ** (floor_db / 20)
    idx = np.where(env > thr)[0]
    if len(idx) == 0:
        return x
    a = max(0, idx[0] - int(pad * SR))
    b = min(len(x), idx[-1] + int(pad * SR * 4))
    return x[a:b].copy()


def mix(a, b, gain_b, offset=0.0):
    n = max(len(a), int(offset * SR) + len(b))
    out = np.zeros(n)
    out[:len(a)] += a
    o = int(offset * SR)
    out[o:o + len(b)] += b * gain_b
    return out


def takes(x, count, length, rel=0.25, gap=0.6):
    """The loudest separate takes of a long recording."""
    starts = peaks(x, rel, gap)
    env = envelope(x, 6.0)
    starts = sorted(starts, key=lambda p: -env[p:p + int(0.3 * SR)].max())[:count]
    return [trim(x[max(0, p - int(0.02 * SR)):p + int(length * SR)]) for p in sorted(starts)]


def main():
    # ---- a round into a body: the thud of the blow, a wet slap in it, shorter and sharper than a punch
    towels = sorted(range(1, 41), key=lambda i: -np.abs(load("towel_%02d" % i)).max())[:6]
    splats = [trim(load("splat_%02d" % i)) for i in (1, 3, 5, 6, 7, 8)]
    for k, i in enumerate(towels):
        t = trim(load("towel_%02d" % i))
        t = stretch(t, 1 / 1.25)  # quicker: the blow of a round, not of a towel
        x = mix(t, fft_filter(splats[k], low=400.0)[: int(0.35 * SR)], 0.45, 0.004)
        x = fade(x[: int(0.45 * SR)], 0.001, 0.12)
        save("gore/hit%d" % (k + 1), x, highpass=50.0)
    # ---- bone breaking
    for k, i in enumerate((1, 3, 4, 6, 8, 10)):
        x = trim(load("wetbreak_%d" % i))[: int(0.7 * SR)]
        save("gore/bone%d" % (k + 1), fade(x, 0.001, 0.15), highpass=60.0)
    # ---- a round stopped by a soft vest: a dull, padded blow
    for k, name in enumerate(("guns-arm-2a", "guns-arm-2b", "hammer-arm-2a", "hammer-arm-2b")):
        x = trim(load("fight_" + name))[: int(0.5 * SR)]
        save("gore/vest%d" % (k + 1), fade(x, 0.001, 0.12), highpass=60.0)
    # ---- pain: short cries and grunts of men hit
    pains = [trim(load("emo_%d" % i)) for i in range(1, 7)]
    pains += [trim(load("michel_pain%d" % i)) for i in range(1, 7)]
    pains += [trim(load("haeldb_3grunt%d" % i)) for i in range(1, 7)]
    for k, x in enumerate(pains):
        save("gore/pain%d" % (k + 1), fade(x[: int(1.2 * SR)], 0.003, 0.15), highpass=90.0)
    # ---- dying: long groans
    deaths = [trim(load("michel_die1")), trim(load("michel_die2")), trim(load("michel_deathh"))]
    deaths += takes(load("bard_long"), 5, 2.2)
    for k, x in enumerate(deaths):
        save("gore/death%d" % (k + 1), fade(x[: int(2.4 * SR)], 0.005, 0.4), highpass=80.0)
    # ---- an artery spurting: a short wet squirt
    for k, i in enumerate((2, 5, 9, 13)):
        x = trim(load("rd_slime_%02d" % i))
        x = fft_filter(stretch(x, 0.85), low=250.0, high=6000.0)
        save("gore/spurt%d" % (k + 1), fade(x[: int(0.4 * SR)], 0.002, 0.1), highpass=200.0)
    # ---- a throat full of blood: bubbles slowed and darkened
    for k, i in enumerate((1, 2, 3)):
        x = trim(load("rd_bubble_%02d" % i))
        x = fft_filter(stretch(x, 1.6), high=1800.0)
        x = mix(x, stretch(x, 1.2), 0.6, 0.12)
        save("gore/gurgle%d" % (k + 1), fade(x[: int(1.4 * SR)], 0.01, 0.3), highpass=70.0)


if __name__ == "__main__":
    main()
