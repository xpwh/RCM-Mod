"""Aircraft and air-defence sounds from real recordings (freesound.org, CC0 / CC BY - see CREDITS.md).

usage: python3 import_aircraft_sounds.py <dir with the 44.1 kHz mono WAVs named as in the download manifest>

Engine sounds are loops that the game moves, pitches and fades with the aircraft (JetSound): each
is cut from the steadiest stretch of a real recording and cross-faded into a seamless loop. Gunfire,
the sonic boom and the siren are one-shots.
"""
import numpy as np

import gen_sounds as s
from gen_sounds import fft_filter, limiter, make_loop
from import_gun_sounds import SR, load, save


def steadiest(x, seconds, start=0.0, end=None, loud=True):
    """Start (s) of the window of `seconds` in [start, end] whose level varies least (and is loud)."""
    end = len(x) / SR - seconds if end is None else min(end, len(x) / SR) - seconds
    hop = 0.25
    best, best_t = None, start
    t = start
    while t <= end:
        w = x[int(t * SR): int((t + seconds) * SR)]
        frames = w[: len(w) // 2205 * 2205].reshape(-1, 2205)
        rms = np.sqrt(np.mean(frames ** 2, axis=1)) + 1e-9
        db = 20 * np.log10(rms)
        score = np.std(db) - (0.15 * np.mean(db) if loud else 0.0)
        if best is None or score < best:
            best, best_t = score, t
        t += hop
    return best_t


def loop(name, file, seconds, start=0.0, end=None, high=None, low=40.0, xfade=1.0):
    """A seamless engine loop from the steadiest stretch of a recording."""
    x = load(file)
    t = steadiest(x, seconds + xfade, start, end)
    seg = x[int(t * SR): int((t + seconds + xfade + 0.05) * SR)]
    y = make_loop(seg, seconds, xfade=xfade)
    y = fft_filter(y, low=low, high=high, slope=1.5)  # zero-phase over the whole loop: stays seamless
    y = limiter(y, 0.95, 1.4)
    s.save(name, s.normalize(y, 0.95))
    print("  %-26s loop %.1fs from %.1fs" % (name, seconds, t))


def shot(name, file, start, seconds, highpass=40.0, loud=True):
    x = load(file)
    y = x[int(start * SR): int((start + seconds) * SR)].copy()
    n = int(0.003 * SR)
    y[:n] *= np.linspace(0, 1, n)
    if loud:
        y = limiter(fft_filter(y, low=highpass, slope=1.5), 0.98, 1.6)
    save(name, y, highpass=highpass)


def main():
    # ---- fighter jets: the close roar, the distant rumble high up, the afterburner, the low end
    loop("jet/fighter", "1_fs395419_hhoffren_fa18_hornet_airshow", 8.0, 110.0, 205.0)
    loop("jet/fighter_far", "1_fs742242_klankbeeld_high_altitude_f16", 12.0, 35.0, 120.0)
    loop("jet/afterburner", "1_fs349713_lonemonk_cf18_vertical_climb_afterburner", 5.0, 11.0, 21.0, xfade=0.8)
    loop("jet/sub", "1_fs742242_klankbeeld_high_altitude_f16", 12.0, 35.0, 120.0, high=220.0, low=20.0)
    # the Mach cone sweeping over: a real sonic boom
    shot("jet/boom", "1_fs182050_qubodup_sonic_boom_usgov", 0.9, 6.0, highpass=20.0)
    shot("missile/sonic_boom", "1_fs182050_qubodup_sonic_boom_usgov", 0.9, 4.0, highpass=20.0)

    # ---- A-10: its TF34 turbofans, from a real flyby
    loop("a10/engine", "2_fs189644_qubodup_a10_warthog_flyby", 2.5, 0.0, None, xfade=0.6)
    # ---- B-2: a heavy jet passing high (B-52 flyby), the highs gone with the altitude
    loop("b2/engine", "3_fs437931_craigsmith_b52_flyby", 8.0, 12.0, 27.0, high=3000.0)
    # ---- AC-130: four turboprops droning overhead (two C-130 Hercules)
    loop("ac130/engine", "4_fs581835_klankbeeld_2x_hercules_overhead", 10.0)
    # ---- helicopter rotor (MH-60S, a steady rotor loop) for the Apache and the gunship
    loop("apache/rotor", "5_fs162437_qubodup_mh60s_seahawk_rotor_loop_usgov", 10.0)
    # ---- Reaper: a surveillance drone passing overhead
    loop("reaper/engine", "6_fs537598_PostProdDog_uav_surveillance_drone", 10.0, 10.0, 50.0)

    # ---- guns: 25 mm Bushmaster burst (chain gun), its first round alone (40 mm), artillery (105 mm),
    # the Phalanx CIWS
    shot("apache/chain_gun", "4_fs854186_qubodup_autocannon_25mm_burst_usgov", 0.0, 2.2)
    shot("ac130/gun_40", "4_fs854186_qubodup_autocannon_25mm_burst_usgov", 0.0, 0.9)
    shot("ac130/gun_105", "4_fs239137_qubodup_artillery_gunfire_usgov", 0.0, 3.0, highpass=25.0)
    shot("ciws/fire", "7_fs163119_qubodup_phalanx_ciws_burst_usgov", 0.0, 2.5)

    # ---- air-raid siren (a real public siren test, Prague)
    shot("missile/siren", "8_fs382611_nooly_air_raid_siren_prague", 1.0, 14.0, highpass=80.0, loud=False)


if __name__ == "__main__":
    main()
