"""FPV drone sounds from freesound (CC0), cut into seamless loops.

usage: import_drone_sounds.py <dir with 854466.ogg 854353.ogg 680584.ogg>
  854466  qubodup "FPV Drone Flight 3"                 -> drone/fpv.ogg    (what the pilot hears)
  854353  qubodup "Quadcopter Drone Stalking Hovering 1" -> drone/buzz.ogg  (heard from outside)
  680584  4l3xoid "DJI FPV power on"                    -> drone/arm.ogg
"""
import os
import subprocess
import sys
import wave

import numpy as np

SR = 44100
src = sys.argv[1]
out = os.path.join(os.path.dirname(__file__), '..', 'src/main/resources/assets/ballisticmissiles/sounds/drone')


def load(name):
    wav = os.path.join(src, name + '.wav')
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', os.path.join(src, name + '.ogg'), '-ac', '1', '-ar', str(SR), wav], check=True)
    w = wave.open(wav)
    return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768.0


def loop(x, a, b, fade=0.35):
    """The stretch a..b seconds, its end cross-faded into its start so it repeats without a seam."""
    seg = x[int(a * SR):int(b * SR)]
    n = int(fade * SR)
    t = np.linspace(0.0, 1.0, n)
    y = seg[:-n].copy()
    y[:n] = seg[:n] * np.sqrt(t) + seg[-n:] * np.sqrt(1.0 - t)
    return y


def save(y, name, peak=0.85):
    y = y / max(1e-9, np.max(np.abs(y))) * peak
    wav = os.path.join(src, name + '_out.wav')
    w = wave.open(wav, 'wb')
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((y * 32767).astype(np.int16).tobytes())
    w.close()
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', wav, '-c:a', 'libvorbis', '-q:a', '5', os.path.join(out, name + '.ogg')], check=True)


save(loop(load('854466'), 9.8, 13.4), 'fpv')
save(loop(load('854353'), 0.2, 6.2, 0.5), 'buzz')
arm = load('680584')
save(arm[int(0.15 * SR):int(1.7 * SR)] * np.minimum(1.0, np.linspace(30.0, 0.0, int(1.7 * SR) - int(0.15 * SR))), 'arm', 0.7)
