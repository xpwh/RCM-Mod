"""Cuts the real A-10 recording into the mod's A-10 sounds.

Source: "A-10.ogg" by qubodup, https://freesound.org/people/qubodup/sounds/205582/ - CC0
(extracted from a US Government video). Freesound blocks scripted downloads, so the archived
preview is used:
https://web.archive.org/web/20250630153131id_/https://cdn.freesound.org/previews/205/205582_71257-lq.mp3

The recording (89 s) holds several flybys and GAU-8 bursts; the bursts were found by their 70 Hz
firing-rate modulation (4200 rounds a minute). Cut:
  a10/gun.ogg       the long burst at 79.25-81.70 s (2.45 s, ends with the real cut-off)
  a10/gun_tail.ogg  what follows the cut-off: the rumble rolling away (81.64-83.6 s)
  a10/flyby.ogg     a close pass at 12.75-19.75 s, loudest 4.0 s in

Usage: python3 tools/import_a10_freesound.py <downloaded.mp3>
"""
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/assets/ballisticmissiles/sounds/a10"
SR = 44100


def load(src):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(src), "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, np.int16).astype(np.float64) / 32768.0


def cut(x, a, b, fade_in, fade_out, curve=1.0):
    s = x[int(a * SR):int(b * SR)].copy()
    n_in = max(1, int(fade_in * SR))
    n_out = max(1, int(fade_out * SR))
    s[:n_in] *= np.linspace(0, 1, n_in)
    s[-n_out:] *= np.linspace(1, 0, n_out) ** curve
    return s


def save(name, s, gain, extra_filter=None):
    OUT.mkdir(parents=True, exist_ok=True)
    tmp = OUT / (name + ".tmp.wav")
    with wave.open(str(tmp), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(s * gain, -1, 1) * 32767).astype(np.int16).tobytes())
    cmd = ["ffmpeg", "-loglevel", "error", "-y", "-i", str(tmp)]
    if extra_filter:
        cmd += ["-af", extra_filter]
    cmd += ["-c:a", "libvorbis", "-q:a", "6", str(OUT / (name + ".ogg"))]
    subprocess.run(cmd, check=True)
    tmp.unlink()


def main(src):
    x = load(src)
    burst = cut(x, 79.25, 81.70, 0.012, 0.03)
    tail = cut(x, 81.64, 83.60, 0.02, 1.3, curve=2.0)
    flyby = cut(x, 12.75, 19.75, 0.4, 0.8)
    # the burst and its tail keep their real level relative to each other
    gain = 10 ** (-1 / 20) / np.abs(burst).max()
    # the preview is a thin 64 kbit/s mp3: give the 30 mm cannon its weight back
    save("gun", burst, gain, "bass=g=5:f=90,acompressor=threshold=0.35:ratio=2.5:attack=5:release=80")
    save("gun_tail", tail, gain * 2.6, "bass=g=4:f=80,alimiter=limit=0.89:level=disabled")
    save("flyby", flyby, 10 ** (-1 / 20) / np.abs(flyby).max(), "bass=g=3:f=100")
    print("ok")


if __name__ == "__main__":
    main(sys.argv[1])
