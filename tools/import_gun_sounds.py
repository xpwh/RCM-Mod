"""Builds the AK sounds from real field recordings (freesound.org, CC0 / CC BY - see CREDITS.md).

usage: python3 import_gun_sounds.py <dir with the 44.1 kHz mono WAVs named as in the download manifest>

Every AK sound comes from a real recording: close and distant shots, the room/cave/outdoor tails
(cut from the recordings after the direct blast), handling, cases, a bullet flying past, ricochet and
metal hits. Impacts on earth, stone, wood and flesh use Minecraft's own recorded sounds (sounds.json).
"""
import os
import sys
import wave

import numpy as np

import gen_sounds as s
from gen_sounds import fade, fft_filter

SR = s.SR
SRC = sys.argv[1] if len(sys.argv) > 1 else "."


def load(name):
    with wave.open(os.path.join(SRC, name + ".wav")) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, name
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float) / 32768.0


def envelope(x, ms=4.0):
    k = max(1, int(SR * ms / 1000))
    return np.convolve(np.abs(x), np.ones(k) / k, mode="same")


def onsets(x, rel=0.35, quiet=0.06, gap=0.15):
    """Sharp attacks: the envelope jumping above rel*max after at least `gap` s below quiet*max."""
    env = envelope(x)
    top = env.max()
    out = []
    last_loud = -1e9
    for i in range(1, len(env)):
        if env[i] >= rel * top and env[i - 1] < rel * top:
            # back up to where it rose out of the quiet
            j = i
            while j > 0 and env[j] > quiet * top * 0.5:
                j -= 1
            if (j - last_loud) / SR >= gap or not out:
                out.append(j)
        if env[i] > quiet * top:
            last_loud = i
    return out


def peaks(x, rel=0.2, gap=0.5):
    """Loud moments at least `gap` s apart (for takes that never fall fully quiet in between)."""
    env = envelope(x, 6.0)
    top = env.max()
    out = []
    w = int(gap * SR)
    i = 0
    while i < len(env):
        if env[i] >= rel * top:
            j = i + int(np.argmax(env[i:i + w]))
            # back up to the start of the attack
            k = j
            while k > 0 and env[k] > env[j] * 0.2:
                k -= 1
            out.append(k)
            i = j + w
        else:
            i += 1
    return out


def stretch(x, factor):
    """Slow down (factor > 1): lower and longer, like a bigger space."""
    idx = np.arange(0, len(x) - 1, 1.0 / factor)
    return np.interp(idx, np.arange(len(x)), x)


def cut(x, start, seconds, pre=0.004):
    a = max(0, start - int(pre * SR))
    return x[a:a + int(seconds * SR)].copy()


def peak_at(x, t):
    """Shift x so its loudest moment lands at t seconds (pad or trim the front)."""
    p = int(np.argmax(envelope(x, 2.0)))
    want = int(t * SR)
    if p < want:
        return np.concatenate([np.zeros(want - p), x])
    return x[p - want:]


def save(name, x, highpass=60.0):
    x = fft_filter(x, low=highpass, slope=1.5)
    x = fade(x, 0.001, min(0.08, len(x) / SR * 0.3))
    s.save(name, s.normalize(x, 0.97))
    print("  %-26s %.2fs" % (name, len(x) / SR))


def loud(name, x, highpass=60.0):
    """A rifle report as loud as it is beside you: compressed up to full scale."""
    save(name, s.limiter(fft_filter(x, low=highpass, slope=1.5), 0.98, 2.0), highpass=highpass)


def main():
    # ---- close shots: Felix Blume's AK-47 at an outdoor range (CC0), a Saiga in 7.62x39 (CC0)
    shots = []
    blume = load("c1_fs136399_felixblume_ak47_range_close")
    blume = fft_filter(blume, low=90.0, slope=2.0)  # wind rumble
    for o in peaks(blume, rel=0.25, gap=1.0)[:11]:
        shots.append(cut(blume, o, 0.75))
    saiga = load("c1_fs698984_areniporgen_saiga_762x39_ext")
    for o in peaks(saiga, rel=0.3, gap=1.0)[:3]:
        shots.append(cut(saiga, o, 0.75))
    # keep the cleanest, strongest takes
    shots.sort(key=lambda x: -np.sqrt(np.mean(x[: int(0.05 * SR)] ** 2)))
    for i, x in enumerate(shots[:5]):
        loud("ak/shot%d" % (i + 1), x)

    # ---- medium distance: the same shots as heard a few hundred metres off - the highs gone,
    # the report smeared by ground reflections
    for i, x in enumerate(shots[:3]):
        y = fft_filter(x, low=60.0, high=2600.0, slope=1.5)
        y = y + np.concatenate([np.zeros(int(0.018 * SR)), y])[: len(y)] * 0.5
        loud("ak/shot_mid%d" % (i + 1), y)

    # ---- far: real distant gunfire across open country (army training, Denmark, CC BY 4.0;
    # hunting rifles in Provence, CC0)
    far = []
    dane = load("c3_fs647509_mugwood_army_training_distant")
    for t in (11.0, 153.0, 154.0, 185.0, 200.0, 227.0):
        seg = dane[int((t - 1.0) * SR): int((t + 3.0) * SR)]
        o = onsets(seg, rel=0.45, gap=0.3)
        if o:
            far.append(cut(seg, o[0], 1.8, pre=0.01))
    hunt = load("c3_fs842326_iainmccurdy_distant_hunting")
    for o in onsets(hunt, rel=0.45, gap=0.5)[:2]:
        far.append(cut(hunt, o, 1.8, pre=0.01))
    for i, x in enumerate(far[:4]):
        loud("ak/shot_far%d" % (i + 1), x, highpass=35.0)

    # ---- handling: an AKM foley set (DrinkingWindGames, CC BY 4.0), racks by dwightsabeast (CC BY 3.0)
    save("ak/dry", load("c5_fs851754_dwg_akm_trigger_dry"), highpass=150.0)
    for i, n in enumerate(("c5_fs851749_dwg_akm_selector_auto_to_safe", "c5_fs851750_dwg_akm_selector_safe_to_auto",
                           "c5_fs851751_dwg_akm_selector_safe_to_semi", "c5_fs851753_dwg_akm_selector_semi_to_safe")):
        save("ak/selector%d" % (i + 1), load(n), highpass=150.0)
    save("ak/mag_out1", load("c5_fs851743_dwg_akm_mag_out_loaded_polymer"), highpass=100.0)
    save("ak/mag_out2", load("c5_fs851742_dwg_akm_mag_out_loaded_metal"), highpass=100.0)
    # the catch snapping home lands 0.14 s in, where the animation seats the magazine
    save("ak/mag_in1", peak_at(load("c5_fs851748_dwg_akm_mag_in_loaded_polymer"), 0.14), highpass=100.0)
    save("ak/mag_in2", peak_at(load("c5_fs851746_dwg_akm_mag_in_loaded_metal"), 0.14), highpass=100.0)
    # the carrier slamming home lands 0.26 s in, where the animation lets go of the handle
    for i, n in enumerate(("c5_fs543594_dwightsabeast_ak47_rack3", "c5_fs543597_dwightsabeast_ak47_rack4", "c5_fs543595_dwightsabeast_ak47_rack2")):
        save("ak/charge%d" % (i + 1), peak_at(load(n), 0.26), highpass=100.0)

    # ---- cases: a real AK case on tiles (kaniaplania, CC0)
    case = load("c7_fs507048_kaniaplania_ak47_shell_tiles")
    for i, o in enumerate(peaks(case, rel=0.12, gap=1.0)[:4]):
        save("ak/shell%d" % (i + 1), cut(case, o, 0.6), highpass=300.0)

    # ---- the space answering the shot: real tails cut from the recordings, after the direct blast
    def tail(x, o, start, seconds):
        t = cut(x, o + int(start * SR), seconds, pre=0.0)
        return t * np.clip(np.arange(len(t)) / (0.015 * SR), 0, 1)
    saiga_on = peaks(saiga, rel=0.3, gap=1.0)[:3]
    for i, o in enumerate(saiga_on):
        loud("ak/tail_outdoor%d" % (i + 1), tail(saiga, o, 0.07, 1.0))
    rooms = []
    for n in ("c1_fs812210_mahecic_kalash_indoor1", "c1_fs812211_mahecic_kalash_indoor2"):
        x = load(n)
        rooms.append(tail(x, onsets(x, rel=0.4)[0], 0.05, 1.4))
    for i, x in enumerate(rooms):
        loud("ak/tail_indoor%d" % (i + 1), x)
        # a cave: the same room sound, bigger and darker
        loud("ak/tail_cave%d" % (i + 1), fft_filter(stretch(x, 1.35), high=3500.0, slope=1.2))

    # ---- a real bullet going past (after an M240 burst, NATO footage, CC0)
    fly = load("c4_fs855248_qubodup_real_bullet_flyby")
    save("bullet/crack1", fly, highpass=400.0)
    save("bullet/whiz1", stretch(fly, 1.6), highpass=200.0)

    # ---- bullets hitting metal (Woodingp, CC0)
    hits = load("c6_fs116645_woodingp_bullets_hit_metal")
    for i, o in enumerate(peaks(hits, rel=0.3, gap=0.6)[:4]):
        save("bullet/impact_metal%d" % (i + 1), cut(hits, o, 0.45), highpass=150.0)

    # ---- one real ricochet off steel (gezortenplotz, CC BY 3.0) alongside the synthetic ones
    save("bullet/ricochet4", load("c6_fs43403_gezortenplotz_rifle_steel_ricochets"), highpass=200.0)


if __name__ == "__main__":
    main()
