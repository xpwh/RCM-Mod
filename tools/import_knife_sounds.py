"""12.3: the karambit - drawn, swung, spun round the finger and caught, cutting - cut from real recordings
(freesound.org, all CC0, see CREDITS.md). Pass the folder holding the downloaded previews, named <id>.mp3:

  344403  jawbutch       "Knife Swish 1.wav"                       CC0
  344408  jawbutch       "Knife Swish 2.wav"                       CC0
  389690  Shamewap       "Small Knife Whoosh.wav"                  CC0
  471097  spycrah        "Knife/sword swing"                       CC0
  427979  Kinoton        "Short Whoosh, 13x"                       CC0
  240640  MissCellany    "Metallic Whoosh.wav"                     CC0
  466216  Harrisando     "UnsheathingSmallKnife.wav"               CC0
  387957  giddster       "Metal sound 9 (knife unsheathed)"        CC0
  455437  ExplosiveVFX   "flip knife.wav"                          CC0
  504610  NeoSpica       "Knife slice"                             CC0

python3 import_knife_sounds.py <folder>
"""
import numpy as np

import gen_sounds as s
from import_suppressed_sounds import SR, cut, level, load, start_at_attack


def onsets(x, thresh=0.25, gap=0.25):
    env = np.convolve(np.abs(x), np.ones(int(0.01 * SR)) / (0.01 * SR), "same")
    top = env.max()
    out, last = [], -1e9
    for i in range(len(env)):
        if env[i] > thresh * top and i - last > gap * SR:
            out.append(i / SR)
            last = i
    return out


def main():
    # quick swishes through the air: the swing, the spins
    swish = [start_at_attack(cut(load("344403"), 0.35, 0.85)), start_at_attack(cut(load("344408"), 0.15, 0.65)),
             start_at_attack(cut(load("389690"), 0.25, 0.8)), start_at_attack(cut(load("471097"), 1.6, 2.3))]
    burst = load("427979")
    for t in onsets(burst)[:6]:
        swish.append(start_at_attack(cut(burst, max(0.0, t - 0.15), t + 0.45)))
    for i, w in enumerate(swish):
        s.save(f"knife/swish{i + 1}", level(w, 0.8))
    # the blade turning round the finger: a thin metallic whirr
    s.save("knife/spin", level(start_at_attack(cut(load("240640"), 0.0, 0.38)), 0.7))
    # drawing it
    s.save("knife/draw1", level(start_at_attack(cut(load("466216"), 0.0, 1.2)), 0.8))
    s.save("knife/draw2", level(start_at_attack(cut(load("387957"), 0.4, 1.8)), 0.8))
    # caught in the hand again
    s.save("knife/catch", level(start_at_attack(cut(load("455437"), 0.0, 0.33)), 0.8))
    # the cut
    s.save("knife/hit", level(start_at_attack(cut(load("504610"), 0.0, 0.9)), 0.85))
    print(len(swish), "swishes")


if __name__ == "__main__":
    main()
