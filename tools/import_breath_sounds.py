"""9.20 breathing after a run, softer and whole (freesound 395563, SoundsForHim, CC0 - see CREDITS.md).

Each sound is one full breath - the drawn-in breath and the breath let out after it - not just the gasp of
the exhale; the hiss above ~3.5 kHz rolled off (it is your own breath, heard more through your head than
your ears), and the level kept well under full scale so it sits under the footsteps rather than over them.

usage: python3 import_breath_sounds.py <dir with L_fs395563_SoundsForHim_panting_after_run.wav>
"""
import numpy as np

import gen_sounds as s
from gen_sounds import fft_filter
from import_gun_sounds import SR, envelope, load


def main():
    pant = load("L_fs395563_SoundsForHim_panting_after_run")[: int(12.5 * SR)]
    pant = fft_filter(pant, low=90.0, high=3500.0, slope=1.2)
    env = envelope(pant, 4.0)
    # the breaths out: the loudest moments, a breath apart
    outs = []
    gap = int(1.1 * SR)
    i = int(0.55 * SR)
    while i < len(env) - int(0.7 * SR):
        j = i + int(np.argmax(env[i:i + gap]))
        if env[j] > 0.3 * env.max() and (not outs or j - outs[-1] > int(0.9 * SR)):
            outs.append(j)
            i = j + int(0.9 * SR)
        else:
            i += gap // 2
    made = 0
    for o in outs:
        a, b = o - int(0.6 * SR), o + int(0.6 * SR)
        if a < 0 or b > len(pant):
            continue
        x = pant[a:b].copy()
        n = len(x)
        t = np.arange(n) / n
        # breathing in swells up, breathing out dies away
        x *= np.clip(t / 0.3, 0, 1) ** 1.5 * np.clip((1 - t) / 0.35, 0, 1) ** 1.2
        # tame the burst of the exhale against the rest
        x = np.tanh(x / (np.abs(x).max() * 0.55)) * 0.55
        # all at the same loudness (by their power, not their peak), never near full scale
        rms = np.sqrt(np.mean(x ** 2))
        if rms < 0.06 * np.abs(x).max():
            continue  # mostly silence round a click: not a breath
        x = x * (10 ** (-24 / 20) / rms)
        x = np.tanh(x / 0.6) * 0.6
        made += 1
        s.save("player/breath%d" % made, x)
        print("  player/breath%d  %.2fs" % (made, n / SR))
        if made == 6:
            break


if __name__ == "__main__":
    main()
