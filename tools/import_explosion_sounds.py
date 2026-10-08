"""Explosions, the grenade and breathing from real field recordings (freesound.org, CC0 / CC BY -
see CREDITS.md). Every explosion sound of the mod comes from here; nothing is synthesized.

usage: python3 import_explosion_sounds.py <dir with the 44.1 kHz mono WAVs named as in the download manifest>
"""
import numpy as np

from gen_sounds import fft_filter, limiter
from import_gun_sounds import SR, cut, load, peaks, save, stretch


def loud(name, x, highpass=25.0):
    """Explosions as loud as the real thing feels: the recording compressed up to full scale."""
    save(name, limiter(fft_filter(x, low=highpass, slope=1.5), 0.98, 1.9), highpass=highpass)


def seg(name, start, seconds, fade_in=0.004):
    """A stretch of a recording from `start` seconds, with a short fade-in so it starts clean."""
    x = load(name)
    a = int(start * SR)
    y = x[a:a + int(seconds * SR)].copy()
    n = max(1, int(fade_in * SR))
    y[:n] *= np.linspace(0, 1, n)
    return y


def hit(name, around, seconds, pre=0.02):
    """The blast nearest `around` seconds (its attack found in a 1.5 s window), and what follows."""
    x = load(name)
    a = max(0, int((around - 0.6) * SR))
    window = x[a:a + int(1.5 * SR)]
    env = np.convolve(np.abs(window), np.ones(64) / 64, mode="same")
    p = int(np.argmax(env))
    k = p
    while k > 0 and env[k] > env[p] * 0.15:
        k -= 1
    return cut(x, a + k, seconds + pre, pre=pre)


def main():
    A = "A_fs165808_sidohzen_tnt_4kg_debris"
    Y_LOUD = "A_fs256297_YleArkisto_yle_echoing_loud_explosion"
    CHIMNEY = "A_fs768370_julujanus_chimney_detonation"
    RAIL = "A_fs73005_Benboncan_railway_cutting_blast"
    FACTORY = "A_fs125937_alienistcog_merrimack_st_demolition"
    TOWER = "A_fs866989_timkahn_watertower_demolition"

    # ---- close: demolition charges, a 4 kg TNT mine, a blast in a railway cutting
    near = [hit(A, 1.8, 6.0), hit(Y_LOUD, 0.34, 6.0), hit(CHIMNEY, 5.2, 7.0), hit(RAIL, 0.14, 5.0), hit(FACTORY, 10.0, 5.0)]
    for i, x in enumerate(near):
        loud("explosion/near%d" % (i + 1), x, highpass=25.0)
    # the shock front: the first instant of the close blasts, nothing after
    for i, x in enumerate(near[:4]):
        y = x[: int(0.9 * SR)].copy()
        y *= np.clip(1.0 - np.arange(len(y)) / len(y), 0, 1) ** 1.5
        loud("explosion/shock%d" % (i + 1), y, highpass=40.0)

    # ---- a few hundred metres off: blasts in the woods, the water tower from 90 m
    mid = [hit("B_fs256299_YleArkisto_yle_explosion_woods_far", 0.38, 5.0), hit("B_fs256296_YleArkisto_yle_explosion_woods_far_nagra", 0.42, 5.0),
           hit(TOWER, 181.1, 8.0)]
    for i, x in enumerate(mid):
        loud("explosion/mid%d" % (i + 1), x, highpass=25.0)

    # ---- far: a quarry blast from 800 m, a night exercise from 300 m, quarry works
    ex = "B_fs64647_juskiddink_military_exercise_night"
    far = [hit("B_fs82682_juskiddink_quarry_blasting_800m", 7.0, 4.5), hit(ex, 0.4, 3.2), hit(ex, 3.7, 3.2), hit(ex, 12.1, 3.5),
           hit("B_fs584594_tosha73_quarry_explosive_works", 50.2, 6.0)]
    for i, x in enumerate(far):
        loud("explosion/far%d" % (i + 1), x, highpass=25.0)

    # ---- the low end felt in the chest: the same real blasts, only their lows
    for i, x in enumerate([near[0], near[2], mid[2]]):
        loud("explosion/sub%d" % (i + 1), fft_filter(x, high=180.0, slope=2.0), highpass=18.0)

    # ---- debris: stones pattering down after rock blasting, a rockfall, the TNT mine's debris
    loud("explosion/debris1", seg("F_fs253095_YleArkisto_yle_rock_blasting_stones_patter", 1.0, 7.0))
    loud("explosion/debris2", seg(A, 3.0, 12.0))
    loud("explosion/debris3", seg("F_fs668605_EricsSoundschmiede_stone_rock_falling", 0.0, 6.0))

    # ---- the biggest conventional blasts: a chimney brought down, a factory demolished
    loud("explosion/thermobaric1", hit(CHIMNEY, 5.2, 11.0), highpass=20.0)
    loud("explosion/thermobaric2", hit(FACTORY, 10.0, 6.0), highpass=20.0)
    # ---- deep underground: the earth swallows the highs
    for i, x in enumerate([near[0], near[1]]):
        loud("explosion/bunker%d" % (i + 1), fft_filter(x, high=500.0, slope=1.6), highpass=20.0)

    # ---- nuclear: real thunder - a huge sudden pressure wave rolling on and on - and the
    # sub-bass of a rocket launch felt through the ground 20 km away
    loud("nuke/near1", hit("C_fs574387_TRP_rolling_thunder_closer", 3.1, 13.0), highpass=18.0)
    loud("nuke/near2", hit("C_fs614944_theplax_long_thunder_rumble", 6.8, 20.0), highpass=18.0)
    loud("nuke/mid1", hit("B_fs258203_YleArkisto_yle_305mm_cannon_distant", 0.24, 12.0), highpass=18.0)
    loud("nuke/mid2", hit("B_fs332934_YleArkisto_yle_155mm_cannon_long_echo", 0.7, 8.0), highpass=18.0)
    loud("nuke/far1", hit("C_fs576963_TRP_thunder_slow_rolls", 7.4, 14.0), highpass=18.0)
    loud("nuke/far2", hit("C_fs576963_TRP_thunder_slow_rolls", 37.9, 14.0), highpass=18.0)
    loud("nuke/sub1", seg("C_fs613617_felixblume_rocket_launch_rumble_geofon", 1.0, 20.0, fade_in=0.5), highpass=15.0)
    # the blast wind: hurricane roar
    loud("nuke/wind1", seg("I_fs404947_midazacom_hurricane_ophelia", 5.0, 18.0, fade_in=0.8), highpass=30.0)
    loud("nuke/wind2", seg("I_fs541461_jonhey_hurricane_zeta", 10.0, 15.0, fade_in=0.8), highpass=30.0)
    loud("nuke/wind3", seg("I_fs369206_solostud_hurricane_hugo", 2.0, 18.0, fade_in=0.8), highpass=30.0)

    # ---- cluster bomblets: sharp small charges
    loud("missile/cluster_pop1", hit("D_fs840508_qubodup_illegal_firework_blast", 0.1, 2.5), highpass=40.0)
    loud("missile/cluster_pop2", hit("D_fs609588_unfa_firecracker_explosion", 0.05, 2.0), highpass=40.0)

    # ---- the grenade: pin, lever, landing, and a real hand grenade going off near and further away
    save("grenade/pin", seg("J_fs93837_CGEffex_grenade_pin_pull_foley", 0.3, 1.6), highpass=150.0)
    save("grenade/spoon", seg("J_fs259553_nicktermer_small_metal_object_fall", 0.0, 1.2), highpass=200.0)
    toss = "J_fs93839_CGEffex_grenade_toss_cement_foley"
    for i, t in enumerate((0.62, 4.76, 8.46)):
        save("grenade/bounce%d" % (i + 1), hit(toss, t, 0.7, pre=0.01), highpass=120.0)
    loud("grenade/explode1", hit("K_fs256300_YleArkisto_yle_hand_grenade_echoing", 1.02, 5.5), highpass=30.0)
    loud("grenade/explode2", hit("K_fs256295_YleArkisto_yle_hand_grenade_further", 0.44, 4.0), highpass=30.0)
    loud("grenade/explode_far1", fft_filter(hit("K_fs256295_YleArkisto_yle_hand_grenade_further", 1.5, 4.0), high=2500.0, slope=1.3),
         highpass=30.0)

    # ---- breathing: single breaths cut from a man panting after a long run
    pant = load("L_fs395563_SoundsForHim_panting_after_run")[: int(12.5 * SR)]
    for i, o in enumerate(peaks(pant, rel=0.25, gap=0.55)[:6]):
        save("player/breath%d" % (i + 1), cut(pant, o, 0.75, pre=0.06), highpass=80.0)


if __name__ == "__main__":
    main()
