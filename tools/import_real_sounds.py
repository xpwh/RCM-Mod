"""Imports the real recordings used by the mod (see CREDITS.md): downloads the public-domain /
CC BY sources, cuts out the useful part and masters it into the mod's sound files.

Usage: python3 import_real_sounds.py <download dir>
Needs ffmpeg and numpy; reuses the helpers of gen_sounds.py.
"""
import os
import subprocess
import sys
import urllib.request

import numpy as np

import gen_sounds as s
from gen_sounds import fade, fft_filter, limiter, make_loop, normalize

NASA = "https://images-assets.nasa.gov/audio/{id}/{id}~128k.mp3"
SOURCES = {
    # CC BY 4.0, nicStage, via Wikimedia Commons (freesound #127737)
    "brrrt.wav": "https://upload.wikimedia.org/wikipedia/commons/3/39/BRRRRRRT.wav",
    # NASA, public domain: Falcon 9 IM-2 launch, pad microphones
    "im2.mp3": NASA.format(id="KSC-20250226-AU-ILW01-0002-SpaceX_CLPS_IM-2_Live_Launch_Coverage_PadMic-1_PadMic-2"),
    # NASA, public domain: SLS Artemis II launch, pad camera site 6
    "artemis.mp3": NASA.format(id="KSC-20260401-AU-LMM01-0001-Artemis_II_Live_Launch_Coverage_Pad_CS6"),
}
UA = "RCMModBot/1.0 (https://github.com/xpwh/RCM-Mod)"


def fetch(folder, name):
    path = os.path.join(folder, name)
    if not os.path.exists(path):
        req = urllib.request.Request(SOURCES[name], headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=300) as r, open(path, "wb") as f:
            f.write(r.read())
    return path


def load(path, start, length, channel=None):
    af = ["-af", f"pan=mono|c0=c{channel}"] if channel is not None else ["-ac", "1"]
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(start), "-t", str(length), "-i", path, *af,
                          "-ar", str(s.SR), "-f", "f32le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def save(name, x):
    x = fft_filter(x, low=18.0, slope=1.0)
    s.save(name, normalize(x, 0.99))


def main(folder):
    os.makedirs(folder, exist_ok=True)
    brrrt = fetch(folder, "brrrt.wav")
    im2 = fetch(folder, "im2.mp3")
    artemis = fetch(folder, "artemis.mp3")

    # A-10: the burst (0-1.4 s) and its long rolling echo, with a little more body
    x = load(brrrt, 0.0, 8.0)
    x += fft_filter(x, high=140, slope=2) * 0.8
    save("a10/gun", fade(limiter(x, 0.99, 1.5), fout=2.5))

    # launch: Falcon 9 lifting off right next to the pad microphone
    x = load(im2, 122.0, 13.0, channel=0)
    save("missile/ignition", fade(limiter(x, 0.98, 1.3), fin=0.01, fout=3.0))

    # deep layer under every launch: the SLS liftoff, low end only
    x = load(artemis, 455.6, 16.0)
    sub = fft_filter(x, high=260, slope=1.5)
    save("missile/ignition_sub", fade(limiter(sub, 0.98, 1.4), fin=0.02, fout=4.0))

    # in-flight roar following the missile: the steady part of the SLS roar, looped
    x = load(artemis, 461.0, 13.0)
    save("missile/engine", limiter(make_loop(x, 10.0, xfade=2.0), 0.97, 1.3))

    # close-up crackle of the exhaust: Falcon 9 at full thrust, highs only, looped
    x = load(im2, 127.0, 6.0, channel=0)
    save("missile/crackle", limiter(make_loop(fft_filter(x, low=700, slope=1.5), 4.5, xfade=1.0), 0.97, 1.4))

    # SAM launch: the first seconds of the Falcon 9 liftoff, sped up and shortened
    x = load(im2, 122.6, 7.0, channel=0)
    idx = np.arange(0, len(x) - 1, 1.45)
    x = np.interp(idx, np.arange(len(x)), x)
    save("air_defense/launch", fade(limiter(x[: s.n_samples(4.0)], 0.98, 1.5), fin=0.005, fout=1.2))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "real_audio_cache")
