"""The round-lid silo's sounds, from a real recording of a giant round door: NASA Johnson Space Center's
Chamber A vacuum chamber door (40 ft across, some 40 t) closing - "NASA's Johnson Space Center Chamber A
Door Closing B-Roll", https://svs.gsfc.nasa.gov/12757/ (NASA, public domain).

usage: python3 import_silo_sounds.py <dir with chamberA.wav: the B-roll's audio, 44.1 kHz mono>
    ffmpeg -i Chamber_A_Door_Closing.webm -ac 1 -ar 44100 chamberA.wav

From it: the door's drive running (the lid swinging), the clank of its locking gear (the bolts) and the
heavy impact as the door seats (the lid closing on its seat, or hitting its open stops).
"""
import numpy as np

from gen_sounds import fade, fft_filter
from import_gun_sounds import SR, load, save, stretch


def seg(x, start, seconds):
    return x[int(start * SR):int((start + seconds) * SR)].copy()


def main():
    door = load("chamberA")
    # the drive: the door's motors and gearing running steadily, made a little heavier
    for i, start in enumerate((6.0, 19.0)):
        run = stretch(fft_filter(seg(door, start, 4.0), low=35.0, high=6000.0, slope=1.5), 1.18)
        save("silo/lid_drive%d" % (i + 1), fade(run, 0.35, 0.6), highpass=35.0)
    # the locking gear clanking home
    save("silo/lid_lock1", fade(seg(door, 35.65, 1.6), 0.005, 0.4), highpass=50.0)
    save("silo/lid_lock2", fade(stretch(seg(door, 35.65, 1.6), 1.2), 0.005, 0.4), highpass=50.0)
    # the door seating: a deep impact ringing out through the structure
    save("silo/lid_seat1", fade(seg(door, 39.15, 2.8), 0.004, 0.8), highpass=30.0)
    save("silo/lid_seat2", fade(stretch(seg(door, 39.15, 2.8), 1.3), 0.004, 0.9), highpass=30.0)


if __name__ == "__main__":
    main()
