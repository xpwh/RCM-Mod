"""Thunder from real field recordings (see CREDITS.md) - the synthesized thunder of gen_sounds_storm.py is gone.

  near  a strike close by: the crack and the boom with the rumble rolling after (the rain around it left in)
  far   a storm off over the hills: only the low rolling rumble, the highs (and the rain, the birds) filtered away

usage: python3 import_thunder_sounds.py <dir with the 44.1 kHz mono WAVs>
  ij_close.wav          InspectorJ, "Thunder, Very Close, Rain, 01" (opengameart.org/content/thunder-very-close-rain-01)
  oga_rain_thunder.wav  "Rain + Long Thunder" (opengameart.org/content/rain-long-thunder)
  gq_ambience.wav       Gregor Quendel, "Thunder / Lightning Ambience - Field Recording" (opengameart.org/node/140426)
"""
import numpy as np

import gen_sounds as s
from gen_sounds import fade, fft_filter
from import_gun_sounds import SR, load

# (recording, from, to): where to look for the strike's onset
NEAR = [
    ("ij_close", 0.0, 12.6),
    ("oga_rain_thunder", 20.0, 36.0),
    ("gq_ambience", 172.0, 186.0),
    ("gq_ambience", 295.0, 309.0),
    ("gq_ambience", 133.5, 147.5),
]
FAR = [
    ("gq_ambience", 197.0, 209.0),
    ("gq_ambience", 246.0, 256.0),
    ("gq_ambience", 446.0, 457.0),
    ("gq_ambience", 484.0, 496.0),
    ("gq_ambience", 537.0, 549.0),
    ("gq_ambience", 609.0, 623.0),
]


def low_env(x, hop=0.02):
    """Loudness below 200 Hz (dB), every `hop` seconds: thunder lives down there, rain and birds do not."""
    n = int(hop * SR)
    m = len(x) // n
    f = np.fft.rfftfreq(n, 1 / SR)
    spec = np.abs(np.fft.rfft(x[: m * n].reshape(m, n) * np.hanning(n), axis=1))
    return 20 * np.log10(np.sqrt((spec[:, (f > 25) & (f < 200)] ** 2).mean(1)) + 1e-9), hop


def strike(name, a, b, pre=0.06):
    """The recording from just before the thunder starts to `b`: the onset found where the low band climbs out
    of the background."""
    x = load(name)
    seg = x[int(a * SR): int(b * SR)]
    env, hop = low_env(seg)
    floor = np.percentile(env[: max(5, int(0.6 / hop))], 50)
    p = int(np.argmax(env))
    k = p
    while k > 0 and env[k] > floor + 8.0:
        k -= 1
    start = max(0, int((k * hop - pre) * SR))
    return seg[start:].copy()


def level(x, target_db, ceiling=0.97):
    """As loud as the others: the loudest two seconds brought to `target_db` (rms), the peaks held by a limiter."""
    n = int(2.0 * SR)
    c = np.cumsum(np.concatenate([[0.0], x ** 2]))
    rms = np.sqrt(((c[n:] - c[:-n]) / n).max()) if len(x) > n else np.sqrt((x ** 2).mean())
    x = x * (10 ** (target_db / 20) / max(rms, 1e-6))
    return ceiling * np.tanh(x / ceiling) if np.abs(x).max() > ceiling * 0.8 else x


def main():
    for i, (name, a, b) in enumerate(NEAR):
        x = strike(name, a, b)
        x = fft_filter(x, low=22.0, slope=1.5)
        if name == "gq_ambience":
            x = fft_filter(x, high=5200.0, slope=1.2)  # the spring birds of that recording, under the crack
        x = fade(x, 0.01, min(3.5, len(x) / SR * 0.35))
        s.save("storm/thunder_near%d" % (i + 1), level(x, -12.0))
        print("  near%d %-18s %.1fs" % (i + 1, name, len(x) / SR))
    for i, (name, a, b) in enumerate(FAR):
        x = strike(name, a, b, pre=0.2)
        # far off: only the rumble; the highs die over the distance (rain, birds and crack with them)
        x = fft_filter(x, low=20.0, high=700.0, slope=1.4)
        x = fade(x, 0.25, min(3.5, len(x) / SR * 0.4))
        s.save("storm/thunder_far%d" % (i + 1), level(x, -14.0))
        print("  far%d  %-18s %.1fs" % (i + 1, name, len(x) / SR))


if __name__ == "__main__":
    main()
