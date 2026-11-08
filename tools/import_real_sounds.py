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
    # Public domain, Fg2 via Wikimedia Commons: a single real detonation with its echo
    "bang.ogg": "https://upload.wikimedia.org/wikipedia/commons/b/b9/Explosion-LS100155.ogg",
    # Public domain, SoundBible.com "Missile Impact" (#1592): a missile flying in and hitting
    "missile_impact.wav": "https://soundbible.com/grab.php?id=1592&type=wav",
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


def missile_impact(folder):
    """SoundBible 'Missile Impact': the fly-in becomes the incoming whoosh, the hit the explosion."""
    from gen_sounds import env_exp, place, sweep, t_axis, white
    from gen_sounds_v5 import echo_cloud, mic_clip
    path = fetch(folder, "missile_impact.wav")
    # the missile flying in, ending right before it hits
    fly = load(path, 0.15, 2.52)
    fly *= np.clip(t_axis(len(fly) / s.SR) / 0.3, 0, 1)
    save("missile/incoming", fade(limiter(fly, 0.98, 1.6), fin=0.05, fout=0.02))

    hit = load(path, 2.62, 5.3)
    hit = hit / (np.max(np.abs(hit)) + 1e-9)

    def impact(distance):
        seconds = 6.0 + distance
        x = np.zeros(s.n_samples(seconds))
        place(x, hit * 1.6, 0.0)
        # a heavier punch and the deep rumble rolling on under it
        punch = sweep(0.45, 120, 55, 0.7) * env_exp(0.45, 0.12, 0.02)
        place(x, punch * 1.6, 0.0)
        tt = t_axis(seconds)
        rum = fft_filter(white(seconds), low=18, high=65, slope=2.0)
        rum *= np.interp(tt, [0, 0.3, 1.4, seconds], [0.2, 1.0, 0.8, 0.0]) ** 1.2
        x += rum * 1.6
        x += echo_cloud(fft_filter(x, low=150), 100, 0.15, seconds * 0.7, 0.25 + 0.05 * distance, seconds * 0.25, 2500, 300)
        if distance >= 1:
            x = fft_filter(x, high=2600 if distance == 1 else 900, slope=1.4)
        x = mic_clip(x, 50.0, 4.0 if distance == 0 else 2.5)
        return fade(limiter(x, 0.995, 2.4 if distance == 0 else 1.9), fin=0.0005, fout=0.8)

    save("explosion/near", impact(0))
    save("explosion/mid", impact(1))
    save("explosion/far", impact(2))


def resample(x, factor):
    """Plays x at `factor` times the speed (factor < 1: slower, deeper, longer)."""
    idx = np.arange(0, len(x) - 1, factor)
    return np.interp(idx, np.arange(len(x)), x)


def loud(name, x, drive=3.2):
    """Masters a shock crack as loud as it gets: the onset flat-topped like an overloaded mic,
    then hard limiting so the whole bang sits at full scale."""
    from gen_sounds_v5 import mic_clip
    x = mic_clip(x, 80.0, 5.0)
    save(name, fade(limiter(x, 0.995, drive), fin=0.0005, fout=0.4))


def shock_cracks(folder):
    """The shock wave cracks, built on a real recorded detonation."""
    from gen_sounds import env_exp, place, sweep, t_axis, white
    from gen_sounds_v5 import echo_cloud
    bang = load(fetch(folder, "bang.ogg"), 0.57, 1.0)
    bang = bang / (np.max(np.abs(bang)) + 1e-9)
    body = fft_filter(bang, high=220, slope=1.5)

    def canvas(seconds):
        return np.zeros(s.n_samples(seconds))

    def thump(seconds, f0, f1, decay, gain):
        return sweep(seconds, f0, f1, 0.6) * env_exp(seconds, decay, 0.001) * gain

    def rumble(seconds, decay, gain):
        return fft_filter(white(seconds), high=200, slope=2) * env_exp(seconds, decay, 0.01) * gain

    # conventional: the real bang, fattened low end, longer echo
    x = canvas(3.5)
    place(x, bang * 1.4 + body * 1.2, 0.0)
    place(x, thump(0.4, 90, 40, 0.08, 1.2), 0.0)
    x += echo_cloud(x.copy(), 80, 0.15, 2.6, 0.3, 0.8, 3000, 400)
    loud("explosion/shock_he", x)

    # heavy bombs: the bang slowed down (bigger charge), deep punch, rolling low end
    big = resample(bang, 0.72)
    x = canvas(4.5)
    place(x, big * 1.5 + fft_filter(big, high=180) * 1.4, 0.0)
    place(x, thump(0.8, 65, 26, 0.18, 1.6), 0.0)
    place(x, rumble(3.0, 0.8, 1.0), 0.05)
    x += echo_cloud(x.copy(), 120, 0.2, 3.5, 0.32, 1.1, 2400, 300)
    loud("explosion/shock_heavy", x)

    # thermobaric: a small pop, then the huge whump, then the air rushing back
    x = canvas(5.0)
    place(x, resample(bang, 1.35) * 0.5, 0.0)
    place(x, resample(bang, 0.6) * 1.6, 0.12)
    place(x, thump(1.1, 50, 20, 0.3, 2.0), 0.12)
    rush = fft_filter(white(1.8), low=60, high=1200, slope=1.3) * np.interp(t_axis(1.8), [0, 0.6, 1.8], [0, 1, 0]) ** 1.5
    place(x, rush, 1.0, 0.7)
    x += echo_cloud(x.copy(), 100, 0.2, 3.5, 0.3, 1.0, 2200, 300)
    loud("explosion/shock_thermo", x)

    # bunker buster: muffled through the ground first, then the bang breaks out
    x = canvas(4.5)
    place(x, fft_filter(resample(bang, 0.6), high=350, slope=2) * 1.8, 0.0)
    place(x, thump(0.9, 42, 18, 0.25, 2.0), 0.0)
    place(x, resample(bang, 0.8) * 1.2, 0.08)
    place(x, rumble(2.5, 0.6, 1.0), 0.1)
    x += echo_cloud(x.copy(), 90, 0.15, 3.0, 0.28, 1.0, 1800, 300)
    loud("explosion/shock_bunker", x)

    # nuclear: incident wave and Mach stem as two huge slowed bangs, then thunder for seconds
    x = canvas(9.0)
    place(x, resample(bang, 0.5) * 1.6, 0.0)
    place(x, resample(bang, 0.45) * 1.4, 0.22)
    place(x, thump(1.8, 38, 15, 0.45, 2.2), 0.0)
    place(x, rumble(7.0, 2.2, 1.6), 0.05)
    x += echo_cloud(x.copy(), 200, 0.3, 7.0, 0.35, 2.2, 1500, 200)
    loud("explosion/shock_nuke", x, 3.0)

    # EMP (high-altitude burst): far away - a dull, dry double bang and a long low roll
    x = canvas(6.0)
    far = fft_filter(resample(bang, 0.65), high=1800, slope=1.5)
    place(x, far * 1.2, 0.0)
    place(x, far * 1.0, 0.35)
    place(x, rumble(4.5, 1.4, 1.1), 0.0)
    x += echo_cloud(x.copy(), 150, 0.3, 5.0, 0.3, 1.6, 1200, 200)
    loud("explosion/shock_emp", x, 2.6)

    # antimatter: the bang with a metallic, ringing, phasing resonance
    x = canvas(6.0)
    place(x, bang * 1.5 + body, 0.0)
    place(x, thump(1.2, 60, 20, 0.3, 1.4), 0.0)
    tt = t_axis(3.5)
    ring = sum(np.sin(2 * np.pi * f * tt * (1 - 0.15 * tt / 3.5)) / (k + 1) for k, f in enumerate((220, 331, 497, 746, 1119)))
    place(x, ring * env_exp(3.5, 0.9, 0.01) * 0.5 * (1 + 0.5 * np.sin(2 * np.pi * 3.0 * tt)), 0.02)
    x += echo_cloud(x.copy(), 120, 0.15, 4.0, 0.3, 1.3, 3000, 400)
    loud("explosion/shock_antimatter", x)


if __name__ == "__main__":
    folder = sys.argv[1] if len(sys.argv) > 1 else "real_audio_cache"
    if len(sys.argv) > 2 and sys.argv[2] == "cracks":
        shock_cracks(folder)
    elif len(sys.argv) > 2 and sys.argv[2] == "impact":
        missile_impact(folder)
    else:
        main(folder)
        shock_cracks(folder)
        missile_impact(folder)
