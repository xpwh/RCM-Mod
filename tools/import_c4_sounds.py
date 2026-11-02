"""C4 sounds from real recordings (freesound.org, CC0 / CC BY 4.0 - see CREDITS.md): the charge going off close
by, at a distance and far off; the receiver's beep; pressing it onto a wall and taping it; the firing device's
switch, safety cover and FIRE button.

usage: python3 import_c4_sounds.py <dir with the 44.1 kHz mono WAVs, named as below>
  c4_near_1..4  demolition charges / TNT close by (tim.kahn, YleArkisto, sidohzen, qubodup)
  c4_mid_1..3   dynamite in the mountains with its echoes, demolition charges across a lot, a chimney blast
                (felix.blume, alienistcog, julujanus)
  c4_far_1..4   quarry blasts from 800 m, a distant explosion, quarry works, an exercise behind a hill
                (juskiddink, tosha73)
  beep_1, beep_3  a microwave's beep (KeyKrusher), an alarm clock's (reecord2)
  place_1..3    wet clay slapped down, mortar thrown on a wall (Funky_Audio, be_a_hero_not_a_patriot)
  place_4       duct tape torn off the roll (AlaskaRobotics)
  switch_1..3   a metal toggle switch; a red safety cover flipped open and the switch under it (TheTechTinkerer,
                cambrianman "detonator switching")
  click_1..2    a training clicker's snap, a stapler's heavy clunk (EricsSoundschmiede, giddster)
"""
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


def main():
    for i, (sec, tail) in enumerate(((6.0, 1.5), (6.5, 2.0), (7.0, 2.5), (4.3, 1.0))):
        blast("c4/near%d" % (i + 1), load("c4_near_%d" % (i + 1)), sec, tail, 25.0)
    for i, (sec, tail) in enumerate(((9.0, 3.0), (7.0, 2.0), (5.5, 1.5))):
        blast("c4/mid%d" % (i + 1), load("c4_mid_%d" % (i + 1)), sec, tail, 25.0)
    for i, (sec, tail) in enumerate(((4.5, 1.2), (3.0, 1.0), (6.0, 1.8), (3.4, 0.8))):
        # far off the highs are gone in the air
        x = fft_filter(load("c4_far_%d" % (i + 1)), high=2500.0, slope=2.0)
        blast("c4/far%d" % (i + 1), x, sec, tail, 20.0)
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
