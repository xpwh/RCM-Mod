"""12.23: the sounds of a summer night - a field cricket chirping in the grass, frogs calling at the pond, a tawny
owl hooting in the wood (and the female's sharp "ke-wick"). Synthesised: each is built the way the animal makes
it - the cricket's chirp a few pulses of a pure tone from the wing file, the frog's croak a fast train of
glottal clicks through the resonance of its vocal sac, the owl's hoot a breathy, slightly falling whistle with a
tremolo on the long note.
python3 gen_sounds_night.py
"""
import numpy as np

import gen_sounds as s
from gen_sounds import SR, fade, fft_filter, normalize, place, reverb, t_axis, white

rng = np.random.default_rng(23)


def tone(seconds, f0, f1=None, vib_rate=0.0, vib_depth=0.0):
    """A sine sliding from f0 to f1 with optional vibrato; phase-continuous."""
    t = t_axis(seconds)
    f1 = f0 if f1 is None else f1
    f = f0 + (f1 - f0) * t / max(seconds, 1e-6)
    if vib_depth:
        f = f * (1.0 + vib_depth * np.sin(2 * np.pi * vib_rate * t))
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def env(seconds, attack, release, shape=1.5):
    n = s.n_samples(seconds)
    e = np.ones(n)
    a = max(1, int(attack * SR))
    r = max(1, int(release * SR))
    e[:a] = np.linspace(0, 1, a) ** shape
    e[-r:] *= np.linspace(1, 0, r) ** shape
    return e


# ------------------------------------------------------------------ the cricket

def chirp(pitch, pulses):
    """One chirp: a few 12-18 ms pulses of a pure tone (the wing's file and scraper), ~ 30 per second."""
    gap = rng.uniform(0.028, 0.036)
    x = np.zeros(s.n_samples(pulses * gap + 0.05))
    for k in range(pulses):
        d = rng.uniform(0.012, 0.018)
        p = tone(d, pitch * 1.01, pitch * 0.99) + 0.12 * tone(d, pitch * 2.02, pitch * 1.98)
        p *= env(d, 0.002, 0.006, 1.2)
        # the middle pulses loudest
        place(x, p, k * gap, 0.7 + 0.3 * np.sin(np.pi * (k + 0.5) / pulses))
    return x


def cricket(seconds, pitch, rate):
    x = np.zeros(s.n_samples(seconds))
    at = rng.uniform(0.0, 0.2)
    while at < seconds - 0.2:
        place(x, chirp(pitch * rng.uniform(0.995, 1.005), int(rng.integers(3, 5))), at, rng.uniform(0.8, 1.0))
        at += 1.0 / rate * rng.uniform(0.9, 1.1)
    # a cricket stops for a moment now and then
    return fade(x, 0.05, 0.6)


# ------------------------------------------------------------------ frogs

def band(x, f, bw):
    """A resonance: a Gaussian band round f (width bw, Hz), applied in the frequency domain."""
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / SR)
    return np.fft.irfft(spec * np.exp(-0.5 * ((freqs - f) / bw) ** 2), len(x))


def croak(seconds, pulse_hz, formants, low=False):
    """A croak: a fast train of clicks (the air pushed past the vocal cords) through the vocal sac's resonances."""
    n = s.n_samples(seconds)
    x = np.zeros(n)
    t = 0.0
    while t < seconds:
        i = int(t * SR)
        if i < n:
            x[i] = rng.uniform(0.7, 1.0)
        t += 1.0 / (pulse_hz * rng.uniform(0.93, 1.07))
    y = np.zeros(n)
    for f, bw, g in formants:
        y += band(x, f, bw) * g
    if low:
        y += band(x, 180, 70) * 0.5
    return normalize(y) * env(seconds, 0.012, 0.04, 1.3)


def frog_call(variant):
    """A frog at the pond: a few croaks, the tree frog's quick 'rep-rep-rep' or the deeper pond frog's rattle."""
    if variant < 2:
        # tree frog: a quick run of short bright croaks
        out = np.zeros(s.n_samples(2.6))
        at = 0.05
        for k in range(int(rng.integers(5, 9))):
            c = croak(rng.uniform(0.06, 0.08), rng.uniform(170, 210), [(1850, 160, 1.0), (3700, 260, 0.3)])
            place(out, c, at, rng.uniform(0.75, 1.0))
            at += rng.uniform(0.15, 0.2)
    else:
        # pond frog: two or three longer, lower, rattling croaks ("rrroak")
        out = np.zeros(s.n_samples(3.0))
        at = 0.05
        for k in range(int(rng.integers(2, 4))):
            d = rng.uniform(0.32, 0.5)
            c = croak(d, rng.uniform(55, 75), [(700, 110, 1.0), (1450, 160, 0.45), (2300, 220, 0.15)], low=True)
            # the sac swells: a little rise in loudness through the croak
            c *= np.linspace(0.7, 1.0, len(c))
            place(out, c, at, 1.0)
            at += d + rng.uniform(0.25, 0.45)
    return out


def frog_chorus(seconds):
    """Many frogs further off, overlapping."""
    x = np.zeros(s.n_samples(seconds))
    for i in range(14):
        call = frog_call(int(rng.integers(0, 4)))
        place(x, fft_filter(call, high=3000) * rng.uniform(0.2, 0.5), rng.uniform(0, seconds - 2.8))
    return x


# ------------------------------------------------------------------ the owl

def hoot(seconds, f0, f1, tremolo=0.0):
    """A tawny owl's hoot: a breathy whistle, soft on, falling a little, the long note quavering."""
    t = t_axis(seconds)
    body = tone(seconds, f0, f1, vib_rate=7.5 if tremolo else 0.0, vib_depth=0.012 * tremolo)
    body += 0.18 * tone(seconds, f0 * 2, f1 * 2) + 0.05 * tone(seconds, f0 * 3, f1 * 3)
    breath = fft_filter(white(seconds), low=f0 * 0.8, high=f0 * 1.6) * 0.25
    am = 1.0 + (0.35 * tremolo) * np.sin(2 * np.pi * 9.0 * t)
    return (body + breath) * am * env(seconds, seconds * 0.25, seconds * 0.35, 1.6)


def owl_male():
    """'Hoooo ... hu, hu-hu-hoooooo'."""
    out = np.zeros(s.n_samples(5.2))
    place(out, hoot(0.9, 520, 470), 0.05)
    gap = 0.9 + 1.6
    place(out, hoot(0.12, 470, 455), gap, 0.55)
    place(out, hoot(0.12, 480, 460), gap + 0.24, 0.6)
    place(out, hoot(0.14, 490, 465), gap + 0.46, 0.7)
    place(out, hoot(1.5, 505, 440, tremolo=1.0), gap + 0.75, 1.0)
    return out


def owl_female():
    """'Ke-wick': a sharp, hoarse, rising-falling call."""
    out = np.zeros(s.n_samples(1.6))
    for k in range(int(rng.integers(1, 3))):
        d = 0.38
        call = tone(d, 1500, 1900, vib_rate=40, vib_depth=0.02) * env(d, 0.03, 0.15)
        call += fft_filter(white(d), low=1300, high=2600) * env(d, 0.02, 0.2) * 0.6
        call += 0.3 * tone(d, 3000, 3800) * env(d, 0.03, 0.15)
        place(out, call, 0.05 + k * 0.7)
    return out


def night_air(x, wet=0.28):
    """The open night: a little reverb off the trees, the highs a touch softened."""
    y = reverb(x, seconds=1.6, decay=0.5, mix=wet, damp=5000)
    return normalize(fade(y, 0.01, 0.3), 0.9)


def main():
    for i, (pitch, rate) in enumerate([(4600, 2.4), (4850, 2.9), (4400, 2.1), (5100, 3.3)]):
        s.save(f"night/cricket{i + 1}", night_air(cricket(6.0, pitch, rate), 0.18) * 0.8)
    for i in range(4):
        s.save(f"night/frog{i + 1}", night_air(frog_call(i), 0.12) * 0.85)
    for i in range(2):
        s.save(f"night/frog_chorus{i + 1}", night_air(frog_chorus(9.0), 0.35) * 0.7)
    for i in range(3):
        s.save(f"night/owl{i + 1}", night_air(owl_male(), 0.4) * 0.8)
    for i in range(2):
        s.save(f"night/owl_female{i + 1}", night_air(owl_female(), 0.4) * 0.75)


if __name__ == "__main__":
    main()
