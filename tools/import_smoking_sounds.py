"""12.2: the cigarette - pack, Zippo, the drag and the breath out - cut from real recordings (freesound.org,
all CC0, see CREDITS.md). Pass the folder holding the downloaded previews, named <freesound id>.mp3:

  47988   vibe_crc        "zippo_light_5shots.wav"                                  CC0
  42144   Vrezerino       "zippo.wav"                                               CC0
  85714   tmkappelt       "Zippo_close.aif"                                         CC0
  407429  DeezSoundzTho   "zippoclose.wav"                                          CC0
  125376  thegoose09      "Cigarettes.wav"                                          CC0
  383857  deleted_user_7146007 "Packing Opening New Pack of Cigarettes"             CC0
  853043  ATP2-kh         "Cigarette drag embers crackle - inhale - exhale (CC0)"   CC0
  157589  husky70         "Having A Smoke.wav"                                      CC0

python3 import_smoking_sounds.py <folder>
"""
import gen_sounds as s
from import_suppressed_sounds import cut, level, load, start_at_attack


def main():
    zippo5 = load("47988")
    zippo = load("42144")
    close_a = load("85714")
    close_b = load("407429")
    cigs = load("125376")
    pack = load("383857")
    drag = load("853043")
    smoke = load("157589")

    # the Zippo: lid flicked open and the wheel struck in one movement (five takes), the lid snapped shut
    for i, (a, b) in enumerate([(0.0, 0.62), (1.95, 2.6), (4.05, 4.7), (5.9, 6.55), (7.6, 8.25)]):
        s.save(f"smoke/zippo_light{i + 1}", level(start_at_attack(cut(zippo5, a, b)), 0.85))
    s.save("smoke/zippo_open", level(start_at_attack(cut(zippo, 0.0, 0.85)), 0.8))
    s.save("smoke/zippo_close1", level(start_at_attack(cut(close_a, 0.0, 0.84)), 0.85))
    s.save("smoke/zippo_close2", level(start_at_attack(cut(close_b, 0.0, 0.43)), 0.85))
    # the pack: the lid flipped, a cigarette drawn out of the foil
    s.save("smoke/pack_open1", level(cut(cigs, 0.4, 0.85), 0.7))
    s.save("smoke/pack_open2", level(cut(cigs, 2.15, 2.75), 0.7))
    s.save("smoke/pack_pull1", level(cut(pack, 9.0, 10.6, 0.02, 0.2), 0.6))
    s.save("smoke/pack_pull2", level(cut(pack, 12.2, 13.6, 0.02, 0.2), 0.6))
    # a drag: the ember crackling as the air is drawn through it
    s.save("smoke/drag1", level(cut(drag, 0.75, 2.45, 0.05, 0.25), 0.55))
    s.save("smoke/drag2", level(cut(drag, 11.4, 13.4, 0.05, 0.25), 0.55))
    s.save("smoke/drag3", level(cut(smoke, 4.75, 6.0, 0.05, 0.25), 0.55))
    # breathing the smoke out
    s.save("smoke/exhale1", level(cut(drag, 6.4, 8.7, 0.05, 0.4), 0.6))
    s.save("smoke/exhale2", level(cut(drag, 17.4, 19.2, 0.05, 0.4), 0.6))
    s.save("smoke/exhale3", level(cut(smoke, 6.6, 8.7, 0.05, 0.4), 0.6))


if __name__ == "__main__":
    main()
