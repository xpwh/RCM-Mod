"""C4 sounds from real recordings - see CREDITS.md: the charge going off close by, at a distance and far off (only
real C-4 / military breaching-charge detonations, from US military footage on DVIDS, public domain); the
receiver's beep; pressing it onto a wall and taping it; the firing device's switch, safety cover and FIRE button
(freesound.org, CC0 / CC BY 4.0).

usage: python3 import_c4_sounds.py <dir with the foley WAVs> <dir with the C4 blast WAVs>   (44.1 kHz mono)
  blasts (second dir, each starting 20 ms before the blast):
  c4_near_1..5  breach charges on a breach wall, C4 on a demolition range, explosive door breaches
  c4_mid_1..4   C4 with a long echo down a valley, C-4 blow-in-place charges on vehicle hulks, C4 behind trees
  (far: there is no documented far-off C4 recording - the far sounds are the mid C4 recordings as heard from far
  away: the highs gone in the air, the crack softened, the echo longer)
  foley (first dir):
  beep_1, beep_3  a microwave's beep (KeyKrusher), an alarm clock's (reecord2)
  place_1..3    wet clay slapped down, mortar thrown on a wall (Funky_Audio, be_a_hero_not_a_patriot)
  place_4       duct tape torn off the roll (AlaskaRobotics)
  switch_1..3   a metal toggle switch; a red safety cover flipped open and the switch under it (TheTechTinkerer,
                cambrianman "detonator switching")
  click_1..2    a training clicker's snap, a stapler's heavy clunk (EricsSoundschmiede, giddster)
"""
import sys

import numpy as np

from gen_sounds import fade, fft_filter, limiter
from import_gun_sounds import SR, load, save


def mix(a, b, gain_b, offset):
    n = max(len(a), int(offset * SR) + len(b))
    out = np.zeros(n)
    out[:len(a)] += a
    o = int(offset * SR)
    out[o:o + len(b)] += b * gain_b
    return out


def blast(name, x, seconds, tail, highpass):
    """An explosion as loud as it is: compressed up to full scale, the tail faded out."""
    x = x[: int(seconds * SR)]
    x = limiter(fft_filter(x, low=highpass, slope=1.5), 0.98, 1.8)
    save(name, fade(x, 0.002, tail), highpass=highpass)


def blast_wav(name):
    """A blast recording from the second directory (the C4 footage)."""
    import os
    import wave
    with wave.open(os.path.join(sys.argv[2], name + ".wav")) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, name
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float) / 32768.0


def body(x):
    """Camera mics lose the low end of a blast: give back the pressure under the crack (the recording's own lows)."""
    return x + 1.4 * fft_filter(x, high=160.0, slope=1.5)


def far(x, k):
    """The same blast heard from far off: the highs gone in the air, the crack softened, the roll of it longer."""
    x = fft_filter(body(x), high=900.0 - 150.0 * k, slope=2.0)
    n = int(1.8 * SR)
    tail = np.zeros(len(x) + n)
    rng = np.random.default_rng(7 + k)
    for i in range(14):
        d = int((0.08 + 0.12 * i + 0.05 * rng.random()) * SR)
        g = 0.55 * np.exp(-i / 4.5)
        tail[d:d + len(x)] += x * g
    tail[:len(x)] += x * 0.6
    return fft_filter(tail, high=700.0, slope=1.5)


def main():
    for i, (sec, tail) in enumerate(((5.5, 1.5), (2.45, 0.8), (1.85, 0.6), (1.95, 0.6), (2.15, 0.7))):
        blast("c4/near%d" % (i + 1), body(blast_wav("c4_near_%d" % (i + 1))), sec, tail, 25.0)
    for i, (sec, tail) in enumerate(((8.0, 3.0), (7.6, 2.5), (8.0, 2.5), (3.2, 1.0))):
        blast("c4/mid%d" % (i + 1), body(blast_wav("c4_mid_%d" % (i + 1))), sec, tail, 25.0)
    for k, (src, sec) in enumerate((("c4_mid_1", 7.0), ("c4_mid_3", 6.0), ("c4_mid_2", 6.0))):
        blast("c4/far%d" % (k + 1), far(blast_wav(src), k), sec, 2.0, 20.0)
    # the receiver's beep: short, a little release so it does not click off
    for i, n in enumerate(("beep_1", "beep_3")):
        x = load(n)
        x = np.concatenate([x, x[-int(0.02 * SR):] * np.linspace(1, 0, int(0.02 * SR))])
        save("c4/beep%d" % (i + 1), fade(x, 0.002, 0.02), highpass=300.0)
    # pressing it on: the putty's slap, then the tape torn and pressed down over it
    tape = load("place_4")
    for i in range(3):
        x = load("place_%d" % (i + 1))
        x = mix(x, tape, 0.55, 0.22 + 0.04 * i)
        save("c4/place%d" % (i + 1), fade(x, 0.002, 0.1), highpass=70.0)
    for i in range(3):
        save("c4/switch%d" % (i + 1), load("switch_%d" % (i + 1)), highpass=150.0)
    for i in range(2):
        save("c4/click%d" % (i + 1), load("click_%d" % (i + 1)), highpass=120.0)


if __name__ == "__main__":
    main()
