"""Textures for 1.5: the GBU-43/B MOAB dropped by the B-2."""
import random

import numpy as np

from gen_textures import missile_texture, small_print, stencil_decal

random.seed(1515)
np.random.seed(1515)


def moab():
    missile_texture("moab", 7.4, [
        (0.0, 0.9, (70, 76, 58)),
        (5.0, 5.12, (240, 200, 40)),  # yellow bands = live high-explosive filler
        (5.25, 5.37, (240, 200, 40)),
        (7.2, 7.4, (60, 60, 64)),
    ], (96, 104, 74), (84, 92, 66), [
        stencil_decal("GBU-43/B", 4.6, (40, 168), (230, 225, 200), 11),
        small_print(["MOAB", "H-6 18700 LB", "AIR BURST"], 2.4, 96, (220, 215, 190)),
    ], soot=0.0, streaks=0.5)


if __name__ == "__main__":
    moab()
