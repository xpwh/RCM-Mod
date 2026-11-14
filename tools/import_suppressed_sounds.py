"""12.0: the suppressor, the workbench and surrender sounds, cut from real recordings (freesound.org, CC0 / CC BY,
see CREDITS.md). Pass the folder holding the downloaded previews, named <freesound id>.mp3:

  182815  qubodup               "Silenced Sniper Rifle" (US Government video, public domain)      CC0
  732250  areniporgen           "Heckler & Koch MP7 Suppressed (Semi & Full Auto)"                 CC0
  828790  areniporgen           "SIG Sauer P226 (Suppressed)"                                      CC0
  701229  areniporgen           "Welrod MK2 with slide reload sound"                               CC0
  441807  FST180081             "180081 Rilfe Suppressor 01" (screwing a can onto a .308)          CC BY 4.0
  487524  JonCon_Library        "Cable tie zipping"                                                CC0
  755987  serøutōnin--deprivəd  "A heavy battle rifle being dropped on the floor; 4 takes"         CC0
  463673  alec_mackay           "toolbox close & open on wood"                                     CC0
  767048  ThabzMalik            "Plastic toolbox latch opening and closing"                        CC0
  414030  johnnydekk            "Metal Filing"                                                     CC0
  855580  qubodup               "Pushing 50 Cal BMG Round into Magazine"                           CC0
  725813  F3ather               "small moving metallic part"                                       CC0
  555307  Nox_Sound             "Foley_Cloth_Movement_Jacket"                                      CC0

python3 import_suppressed_sounds.py <folder>
"""
import subprocess
import sys

import numpy as np

import gen_sounds as s
from gen_sounds import fft_filter

SR = s.SR
SRC = sys.argv[1] if len(sys.argv) > 1 else "."


def load(fid):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", f"{SRC}/{fid}.mp3", "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, np.int16).astype(float) / 32768.0


def cut(x, a, b, fin=0.002, fout=0.08):
    seg = x[int(a * SR):int(b * SR)].copy()
    n_in = max(1, int(fin * SR))
    n_out = max(1, int(fout * SR))
    seg[:n_in] *= np.linspace(0, 1, n_in)
    seg[-n_out:] *= np.linspace(1, 0, n_out) ** 1.5
    return seg


def start_at_attack(seg, lead=0.004, rel=0.3):
    """Trims the silence before the first attack, keeping {@code lead} seconds of run-up."""
    env = np.abs(seg)
    i = int(np.argmax(env > rel * env.max()))
    return seg[max(0, i - int(lead * SR)):]


def level(x, peak=0.9):
    return x / (np.abs(x).max() + 1e-9) * peak


def resample(x, ratio):
    """Plays {@code x} {@code ratio} times as fast (pitch and speed together, like tape)."""
    n = int(len(x) / ratio)
    return np.interp(np.arange(n) * ratio, np.arange(len(x)), x)


def mix(*parts):
    n = max(int(at * SR) + len(p) for p, at, _ in parts)
    out = np.zeros(n)
    for p, at, g in parts:
        i = int(at * SR)
        out[i:i + len(p)] += p * g
    return out


def main():
    sniper = load("182815")
    mp7 = load("732250")
    p226 = load("828790")
    welrod = load("701229")

    # rifle: two single shots of the suppressed sniper rifle, the MP7's first (semi-auto) shot
    rifle = [start_at_attack(cut(sniper, 0.95, 1.75)), start_at_attack(cut(sniper, 2.96, 3.76)), start_at_attack(cut(mp7, 0.0, 0.62))]
    for i, r in enumerate(rifle):
        s.save(f"gun/suppressed_rifle{i + 1}", level(r, 0.95))
    # pistol: the P226's three shots, the Welrod
    pistol = [start_at_attack(cut(p226, 0.0, 0.62)), start_at_attack(cut(p226, 0.72, 1.36)), start_at_attack(cut(p226, 1.42, 2.1)),
              start_at_attack(cut(welrod, 0.0, 0.6))]
    for i, p in enumerate(pistol):
        s.save(f"gun/suppressed_pistol{i + 1}", level(p, 0.9))
    # shotgun: no free recording of a suppressed 12-gauge exists; the rifle shots slowed a little give its
    # heavier, lower thump
    for i, r in enumerate(rifle[:2]):
        s.save(f"gun/suppressed_shotgun{i + 1}", level(fft_filter(resample(r, 0.8), high=7000), 0.95))
    # what carries far: the thump without its top
    far = fft_filter(rifle[0], high=1100)
    s.save("gun/suppressed_far", level(far, 0.8))

    # threading the can on: turns of the thread, the last one snugging it home
    can = load("441807")
    s.save("gun/suppressor_on", level(cut(can, 10.2, 13.5, 0.05, 0.25), 0.85))

    # workbench
    latch = load("767048")
    box = load("463673")
    s.save("workbench/open", level(mix((cut(latch, 2.85, 3.35, 0.002, 0.1), 0.0, 1.0), (cut(box, 6.6, 7.7, 0.002, 0.3), 0.32, 0.9)), 0.85))
    filing = load("414030")
    s.save("workbench/craft_metal", level(mix((cut(filing, 0.75, 2.6, 0.01, 0.15), 0.0, 1.0), (cut(filing, 5.0, 5.45), 1.95, 0.9)), 0.85))
    rnd = load("855580")
    s.save("workbench/craft_ammo", level(mix((rnd, 0.0, 1.0), (rnd, 0.42, 0.9), (rnd, 0.86, 1.0), (rnd, 1.27, 0.85)), 0.85))
    parts = load("725813")
    s.save("workbench/craft_grenade", level(mix((cut(parts, 6.35, 7.05), 0.0, 1.0), (cut(parts, 11.35, 12.1), 0.75, 1.0)), 0.85))

    # surrender and capture
    drop = load("755987")
    for i, (a, b) in enumerate([(0.3, 1.05), (2.25, 2.95), (4.45, 5.15), (6.8, 7.55)]):
        s.save(f"soldier/drop_weapon{i + 1}", level(start_at_attack(cut(drop, a, b)), 0.9))
    cloth = load("555307")
    s.save("soldier/surrender", level(cut(cloth, 13.3, 15.6, 0.05, 0.3), 0.7))
    tie = load("487524")
    s.save("soldier/zip_tie", level(cut(tie, 3.95, 4.9, 0.002, 0.08), 0.8))


if __name__ == "__main__":
    main()
