"""The last synthesized sounds replaced by real recordings (freesound.org, CC0 - see CREDITS.md).

usage: python3 import_misc_sounds.py <dir with the 44.1 kHz mono WAVs named as in the download manifest>

- bomb/whistle: a falling bomb's whistle from WW2 footage, the last four seconds before the bang
  (it ends where the bomb lands)
- missile/jet: the turbofan of a real Kh-101 cruise missile flying over Kyiv, as a seamless loop
- radar/alarm: the diving alarm of the submarine USS Woodrow Wilson
- missile/beep: an electronic timer's beep
"""
import numpy as np

import gen_sounds as s
from gen_sounds import fft_filter, limiter
from import_aircraft_sounds import loop
from import_gun_sounds import SR, load, save


def main():
    # ---- the whistle: 4000 Hz falling to 2100 Hz; the explosion that follows it is left out
    x = load("2_fs434739_SvennSound_whizzbang_drop")
    y = x[int(2.55 * SR): int(6.55 * SR)].copy()
    n = int(0.25 * SR)
    y[:n] *= np.linspace(0, 1, n) ** 2  # it comes in from far above
    y[-int(0.04 * SR):] *= np.linspace(1, 0, int(0.04 * SR))
    save("bomb/whistle", limiter(fft_filter(y, low=400.0, slope=1.5), 0.95, 1.3), highpass=400.0)

    # ---- the cruise missile's engine, after the interception at the start of the clip has died away
    loop("missile/jet", "1_fs824805_Invadium_kh101_cruise_flyby", 4.0, 2.75, 10.0, low=60.0, xfade=0.8)

    # ---- radar alarm: the dive alarm's first two blasts
    x = load("3_fs156672_mkjunker_uss_dive_alarm")
    save("radar/alarm", x[: int(2.15 * SR)].copy(), highpass=120.0)

    # ---- the countdown beep
    x = load("4_fs536422_RudmerRotteveel_timer_beep")
    i = int(np.argmax(np.abs(x) > 0.05)) - int(0.002 * SR)
    save("missile/beep", x[max(i, 0): max(i, 0) + int(0.16 * SR)].copy(), highpass=200.0)


if __name__ == "__main__":
    main()
