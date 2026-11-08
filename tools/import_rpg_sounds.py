"""The RPG-7 from real field recordings (freesound.org, CC0 - see CREDITS.md).

usage: python3 import_rpg_sounds.py <dir with the 44.1 kHz mono WAVs named as in the download manifest>

Close shots: three exterior firings of an RPG launcher - the bang of the booster charge and the
rocket's motor tearing away downrange. Further shots: soldiers firing AT weapons on a training range
(from a US Government video, public domain). Far: the same shots with the highs gone and the report
smeared by ground reflections. The sustainer igniting out in front: the hiss of the motor, cut from
the close takes after the bang.
"""
import numpy as np

from gen_sounds import fft_filter
from import_gun_sounds import SR, cut, load, loud, peaks, save


def main():
    klang = fft_filter(load("r1_fs249298_klangfabrik_rpg_exterior"), low=35.0, slope=2.0)
    rng = fft_filter(load("r2_fs184274_qubodup_at_weapons_range"), low=35.0, slope=2.0)
    k = peaks(klang, rel=0.3, gap=2.5)[:3]
    r = peaks(rng, rel=0.5, gap=4.0)[:3]
    print("klangfabrik shots at", [round(o / SR, 2) for o in k], " range shots at", [round(o / SR, 2) for o in r])

    # ---- close: the booster's bang and the rocket screaming away
    close = [cut(klang, o, 3.4, pre=0.01) for o in k] + [cut(rng, o, 3.4, pre=0.01) for o in r[:2]]
    for i, x in enumerate(close):
        loud("rpg/shot%d" % (i + 1), x, highpass=35.0)

    # ---- far: a few hundred metres off - only the low thump and a smeared tail reach you
    for i, x in enumerate(close[:4]):
        y = fft_filter(x, low=40.0, high=1400.0, slope=1.5)
        y = y + np.concatenate([np.zeros(int(0.035 * SR)), y])[: len(y)] * 0.55
        y = y + np.concatenate([np.zeros(int(0.11 * SR)), y])[: len(y)] * 0.3
        loud("rpg/shot_far%d" % (i + 1), y, highpass=40.0)

    # ---- the sustainer catching out in front: the motor's roar from after the bang
    for i, o in enumerate(k[:2]):
        x = cut(klang, o + int(0.18 * SR), 1.6, pre=0.0)
        n = int(0.03 * SR)
        x[:n] *= np.linspace(0, 1, n)
        save("rpg/motor%d" % (i + 1), x, highpass=80.0)


if __name__ == "__main__":
    main()
