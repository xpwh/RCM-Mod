"""10.0 missile skins: the slim turbojet missile (Harpoon / AIM-120 style) and the Kh-31PD anti-radiation
missile. Same layout as every missile skin (see gen_textures.missile_texture): y = 0 at the tail.
"""
import numpy as np

from gen_textures import missile_texture, small_print, stencil_decal, stripe

np.random.seed(1010)


def jet_cruise():
    # light gull grey, a dielectric radome, the yellow band of a live warhead, brown of a live motor
    missile_texture("jet_cruise_missile", 4.6, [
        (0.0, 0.14, (96, 98, 102)),           # tail cone round the turbojet nozzle
        (0.62, 0.68, (120, 84, 40)),          # brown: live sustainer fuel
        (2.30, 2.36, (226, 196, 40)),         # yellow: live warhead
        (3.00, 3.72, (176, 180, 182)),        # seeker section
        (3.72, 4.6, (214, 210, 196)),         # dielectric radome, off-white
    ], (164, 168, 172), (150, 154, 158), [
        stencil_decal("JCM-84E", 1.7, (40, 150), (46, 48, 52), 9),
        small_print(["TURBOJET", "DO NOT STEP"], 1.1, 70, (60, 60, 64)),
        stripe(3.70, 3.74, (70, 72, 76)),
    ], soot=0.12, streaks=0.35, steel=(126, 128, 132))


def kh31pd():
    # Soviet-style light grey-blue, a dark radome over the passive seeker, red bands, the ramjet intakes' ring
    missile_texture("kh31pd_missile", 4.7, [
        (0.0, 0.2, (80, 82, 86)),             # nozzle shroud
        (0.95, 1.05, (180, 40, 34)),          # red band
        (1.6, 1.68, (60, 62, 66)),            # joint of the ramjet section
        (3.25, 3.32, (180, 40, 34)),          # red band: warhead
        (3.72, 4.7, (58, 62, 60)),            # dark passive seeker radome
    ], (178, 186, 190), (166, 174, 178), [
        stencil_decal("KH-31PD", 2.6, (40, 150), (40, 42, 46), 9),
        small_print(["X-31PD", "RF PASSIVE"], 2.1, 70, (50, 52, 56)),
    ], soot=0.2, streaks=0.4, steel=(118, 120, 124))


if __name__ == "__main__":
    jet_cruise()
    kh31pd()
