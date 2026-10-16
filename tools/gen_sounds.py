"""Synthesizes every sound of the Ballistic Missiles mod (mono 44.1 kHz -> OGG Vorbis via ffmpeg).

Nothing is sampled. Explosions are layered (supersonic N-wave crack, saturated blast, terrain echoes,
rolling thunder, falling debris) and the low end lives in separate "sub" files that the game plays on
top, because Minecraft caps every single sound instance at volume 1.0.
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ballisticmissiles", "sounds")
rng = np.random.default_rng(42)


# ------------------------------------------------------------------------------------------ dsp helpers

def n_samples(seconds):
    return int(SR * seconds)


def t_axis(seconds):
    return np.arange(n_samples(seconds)) / SR


def white(seconds):
    return rng.standard_normal(n_samples(seconds))


def fft_filter(x, low=None, high=None, slope=2.0):
    """Zero-phase spectral band filter with smooth roll-off (low/high in Hz)."""
    n = len(x)
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    gain = np.ones_like(f)
    if high is not None:
        gain *= 1.0 / (1.0 + (f / high) ** (2 * slope))
    if low is not None:
        gain *= 1.0 / (1.0 + (low / np.maximum(f, 1e-3)) ** (2 * slope))
    return np.fft.irfft(spec * gain, n)


def lowpass_sweep(x, f_start, f_end, curve=1.0, blocks=48):
    """Time-varying low-pass: crossfades between statically filtered blocks."""
    n = len(x)
    edges = np.linspace(0, n, blocks + 1).astype(int)
    out = np.zeros(n)
    win = 4096
    for i in range(blocks):
        frac = (i / (blocks - 1)) ** curve
        fc = f_start * (f_end / f_start) ** frac
        a = max(0, edges[i] - win)
        b = min(n, edges[i + 1] + win)
        seg = fft_filter(x[a:b], high=fc)
        w = np.zeros(b - a)
        core_a, core_b = edges[i] - a, edges[i + 1] - a
        w[core_a:core_b] = 1
        ramp = min(win, core_a)
        if ramp:
            w[core_a - ramp:core_a] = np.linspace(0, 1, ramp)
        ramp2 = min(win, (b - a) - core_b)
        if ramp2:
            w[core_b:core_b + ramp2] = np.linspace(1, 0, ramp2)
        out[a:b] += seg * w
    norm = np.zeros(n)
    for i in range(blocks):
        a = max(0, edges[i] - win)
        b = min(n, edges[i + 1] + win)
        w = np.zeros(b - a)
        core_a, core_b = edges[i] - a, edges[i + 1] - a
        w[core_a:core_b] = 1
        ramp = min(win, core_a)
        if ramp:
            w[core_a - ramp:core_a] = np.linspace(0, 1, ramp)
        ramp2 = min(win, (b - a) - core_b)
        if ramp2:
            w[core_b:core_b + ramp2] = np.linspace(1, 0, ramp2)
        norm[a:b] += w
    return out / np.maximum(norm, 1e-6)


def env_exp(seconds, decay, attack=0.002):
    t = t_axis(seconds)
    return np.exp(-t / decay) * np.clip(t / attack, 0, 1)


def sweep(seconds, f0, f1, curve=1.0):
    t = t_axis(seconds)
    freq = f0 + (f1 - f0) * (t / seconds) ** curve
    return np.sin(2 * np.pi * np.cumsum(freq) / SR)


def slow_mod(seconds, rate_hz, depth):
    """Random slow amplitude modulation (turbulence, rolling thunder)."""
    m = fft_filter(white(seconds), high=rate_hz)
    m /= np.max(np.abs(m)) + 1e-9
    return 1.0 + depth * m


def place(dst, src, at_seconds, gain=1.0):
    i = n_samples(at_seconds)
    if i >= len(dst):
        return dst
    end = min(len(dst), i + len(src))
    dst[i:end] += src[: end - i] * gain
    return dst


def reverb(x, seconds=2.5, decay=0.6, mix=0.35, damp=3000):
    ir = white(seconds) * np.exp(-t_axis(seconds) / decay)
    ir = fft_filter(ir, high=damp)
    ir[0] = 0
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    n = len(x) + len(ir)
    wet = np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(ir, n), n)[: len(x)]
    return x * (1 - mix) + wet * mix * 2.5


def terrain_echoes(x, taps):
    """Discrete reflections off hills: (delay s, gain, lowpass Hz)."""
    out = x.copy()
    for delay, gain, lp in taps:
        out = place(out, fft_filter(x, high=lp), delay, gain)
    return out


def n_wave(length_ms):
    """Shock-wave pressure signature: instant rise, linear fall through zero, instant return."""
    n = max(4, int(SR * length_ms / 1000))
    return np.linspace(1, -1, n)


def shock_crack(seconds, at, length_ms=6.0, gain=1.0):
    x = np.zeros(n_samples(seconds))
    place(x, n_wave(length_ms), at, gain)
    burst = fft_filter(white(0.05), low=1500) * env_exp(0.05, 0.008, 0.0002)
    return place(x, burst, at, gain * 0.6)


def crackle(seconds, rate, decay_s=1e9, min_ms=0.4, max_ms=2.5, start=0.0):
    """Dense random N-wave pops: rocket-exhaust crackle / burning debris."""
    n = n_samples(seconds)
    out = np.zeros(n)
    t = t_axis(seconds)
    density = rate * np.exp(-np.maximum(t - start, 0) / decay_s) * (t >= start)
    hits = np.nonzero(rng.random(n) < density / SR)[0]
    for i in hits:
        w = n_wave(rng.uniform(min_ms, max_ms)) * rng.uniform(0.15, 1.0) ** 1.5
        end = min(n, i + len(w))
        out[i:end] += w[: end - i]
    return out


def debris_rain(seconds, start, count, spread):
    """Rocks and dirt clods landing: filtered thuds and clatters scattered in time."""
    n = n_samples(seconds)
    out = np.zeros(n)
    for _ in range(count):
        at = start + abs(rng.normal(0, spread))
        i = n_samples(at)
        if i >= n:
            continue
        size = rng.uniform(0.2, 1.0)
        dur = 0.03 + 0.12 * size
        thud = white(dur) * env_exp(dur, dur * 0.25, 0.0005)
        thud = fft_filter(thud, low=80 + 600 * (1 - size), high=900 + 5000 * (1 - size))
        end = min(n, i + len(thud))
        out[i:end] += thud[: end - i] * size * rng.uniform(0.4, 1.0)
    hiss = fft_filter(white(seconds), low=1500, high=7000) * 0.05
    hiss *= np.interp(t_axis(seconds), [0, start, start + spread, start + spread * 3, seconds], [0, 0, 1, 0.3, 0])
    return out + hiss


def friedlander(seconds, positive_ms, decay=1.6):
    """Physical blast-wave pressure history: instant overpressure, exponential fall, then suction."""
    t = t_axis(seconds)
    T = positive_ms / 1000.0
    p = (1.0 - t / T) * np.exp(-decay * t / T)
    return p / np.max(np.abs(p))


def saturate(x, drive):
    return np.tanh(x * drive) / np.tanh(drive)


def multiband_crush(x, drives=(3.0, 2.2, 1.6), splits=(160, 1800)):
    """Saturate low / mid / high bands separately: massive but not mushy."""
    low = fft_filter(x, high=splits[0], slope=3)
    high = fft_filter(x, low=splits[1], slope=3)
    mid = x - low - high
    bands = [low, mid, high]
    out = np.zeros_like(x)
    for b, d in zip(bands, drives):
        out += saturate(normalize(b), d) * np.max(np.abs(b))
    return out


def harmonics_for_sub(x):
    """Adds the 2nd/3rd harmonics of sub-bass so it is felt on small speakers too."""
    return x + 0.35 * saturate(x * 3.0, 2.0) - 0.35 * x


def limiter(x, ceiling=0.98, drive=1.6):
    """Brickwall-ish loudness: normalize, soft-clip, normalize again."""
    return normalize(saturate(normalize(x), drive), ceiling)


def pad(x, seconds):
    n = n_samples(seconds)
    return x[:n] if len(x) >= n else np.concatenate([x, np.zeros(n - len(x))])


def normalize(x, peak=1.0):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def fade(x, fin=0.002, fout=0.05):
    x = x.copy()
    a = min(len(x), int(SR * fin))
    b = min(len(x), int(SR * fout))
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b)
    return x


def make_loop(x, seconds, xfade=1.0):
    n = n_samples(seconds)
    xf = n_samples(xfade)
    loop = x[:n].copy()
    w = np.linspace(0, 1, xf)
    loop[:xf] = x[:xf] * np.sqrt(w) + x[n:n + xf] * np.sqrt(1 - w)
    return loop


def save(name, x, quality=7):
    path = os.path.join(OUT, name + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        wav_path = tmp.name
    with wave.open(wav_path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path, "-ac", "1", "-c:a", "libvorbis", "-q:a", str(quality), path],
        check=True,
    )
    os.remove(wav_path)
    rms = np.sqrt(np.mean(x ** 2))
    print(f"   {name:<24} {len(x) / SR:5.1f}s  rms {20 * np.log10(rms + 1e-9):6.1f} dB")


# ------------------------------------------------------------------------------------------ launch / flight

def rocket_roar(seconds, darkness=1.0):
    """Broadband rocket noise: chest rumble + roar + hiss, turbulent, saturated."""
    rumble = fft_filter(white(seconds), high=90 * darkness, slope=2) * 2.6
    roar = fft_filter(white(seconds), low=70, high=1100 * darkness, slope=1.4) * 1.3
    hiss = fft_filter(white(seconds), low=2500, high=10000) * 0.22
    turb = slow_mod(seconds, 7, 0.35) * slow_mod(seconds, 1.5, 0.2)
    pops = fft_filter(crackle(seconds, 220), low=300) * 0.9
    return multiband_crush((rumble + roar) * turb + hiss + pops, drives=(3.5, 2.5, 1.5))


def siren():
    d = 3.6
    t = t_axis(d)
    shape = np.interp(t, [0, 1.3, 2.1, 3.6], [0.0, 1.0, 1.0, 0.1])
    f = 240 + 470 * shape + 7 * np.sin(2 * np.pi * 5.5 * t)
    phase = 2 * np.pi * np.cumsum(f) / SR
    # two slightly detuned rotors = the classic beating air-raid siren
    tone = sum(a * (np.sin(k * phase) + np.sin(k * phase * 1.012)) for k, a in ((1, 1), (2, 0.55), (3, 0.35), (5, 0.18)))
    tone *= np.interp(t, [0, 0.15, 2.5, 3.6], [0, 1, 1, 0])
    tone = saturate(normalize(tone), 2.2)
    wind = fft_filter(white(d), low=400, high=2500) * 0.06 * shape
    x = reverb(tone + wind, seconds=2.5, decay=0.9, mix=0.45)
    save("missile/siren", fade(limiter(x, 0.9, 1.3)))


def beep():
    d = 0.7
    t = t_axis(d)
    x = np.zeros_like(t)
    for start in (0.0, 0.22):
        seg = (t >= start) & (t < start + 0.14)
        tt = t[seg] - start
        x[seg] += np.sign(np.sin(2 * np.pi * 1180 * tt)) * 0.35 + np.sin(2 * np.pi * 2360 * tt) * 0.2
        x[seg] *= np.clip(tt / 0.004, 0, 1) * np.clip((0.14 - tt) / 0.01, 0, 1)
    x = reverb(fft_filter(x, high=5000), seconds=0.6, decay=0.15, mix=0.25)
    save("missile/beep", fade(normalize(x, 0.75)))


def ignition():
    d = 7.0
    t = t_axis(d)
    x = np.zeros(n_samples(d))
    # igniter pops, then the main chamber lights with a violent WHUMP
    for at in (0.0, 0.07, 0.15):
        place(x, fft_filter(white(0.08), low=300, high=5000) * env_exp(0.08, 0.02, 0.0005), at, 0.5)
    whump = sweep(1.2, 110, 28, 0.5) * env_exp(1.2, 0.35, 0.003) * 2.5
    whump += fft_filter(white(1.2), high=400) * env_exp(1.2, 0.25, 0.002) * 2.0
    place(x, whump, 0.22)
    place(x, shock_crack(0.2, 0.0, 4.0), 0.22, 0.9)
    roar = rocket_roar(d)
    ramp = np.clip((t - 0.25) / 1.8, 0, 1) ** 1.3
    tail = np.interp(t, [0, 5.0, 7.0], [1, 1, 0])
    x += roar * ramp * tail * 1.1
    # the roar opens up as the flame clears the pad
    x = lowpass_sweep(x, 900, 14000, 0.6)
    x = terrain_echoes(x, [(0.35, 0.35, 2000), (0.8, 0.25, 1200), (1.5, 0.18, 700)])
    x = reverb(x, seconds=3.5, decay=1.1, mix=0.3, damp=2500)
    save("missile/ignition", fade(limiter(multiband_crush(x, (3.0, 2.0, 1.4)), 0.98, 1.4), fout=0.8))


def ignition_sub():
    d = 7.0
    t = t_axis(d)
    sub = fft_filter(white(d), high=70, slope=3) * slow_mod(d, 3, 0.4)
    tone = sweep(d, 34, 26, 1.0) * 0.6
    env = np.clip((t - 0.2) / 1.2, 0, 1) * np.interp(t, [0, 5.0, 7.0], [1, 1, 0])
    thump = pad(sweep(1.0, 70, 25, 0.5) * env_exp(1.0, 0.4, 0.003), d) * 2.0
    x = harmonics_for_sub((normalize(sub) + tone) * env + np.roll(thump, n_samples(0.22)))
    save("missile/ignition_sub", fade(limiter(x, 0.98, 2.2), fout=0.8))


def engine_loop():
    d = 4.0
    x = rocket_roar(d + 1.0)
    save("missile/engine", limiter(make_loop(x, d), 0.97, 1.5))


def engine_crackle():
    d = 3.0
    pops = crackle(d + 1.0, 650, min_ms=0.3, max_ms=3.5)
    pops = fft_filter(pops, low=250, high=12000)
    bed = fft_filter(white(d + 1.0), low=600, high=4000) * 0.15 * slow_mod(d + 1.0, 9, 0.5)
    x = saturate(normalize(pops + bed), 2.5)
    save("missile/crackle", limiter(make_loop(x, d, 0.6), 0.95, 1.3))


def incoming():
    d = 4.5
    t = t_axis(d)
    f = 2400 * (1 - t / d) ** 1.4 + 330
    phase = 2 * np.pi * np.cumsum(f) / SR
    whistle = np.sin(phase) + 0.3 * np.sin(2 * phase) + 0.12 * np.sin(3 * phase)
    air = fft_filter(white(d), low=300, high=3500) * 0.45 * slow_mod(d, 12, 0.4)
    roar = fft_filter(white(d), high=200) * 1.0
    gain = (t / d) ** 1.8
    x = (whistle * 0.55 + air + roar * (t / d) ** 2) * gain
    x = saturate(normalize(x), 1.8)
    x = reverb(x, seconds=1.0, decay=0.3, mix=0.2)
    x[-n_samples(0.02):] *= np.linspace(1, 0, n_samples(0.02))
    save("missile/incoming", limiter(x, 0.95, 1.2))


# ------------------------------------------------------------------------------------------ explosions

def explosion_near():
    d = 9.0
    t = t_axis(d)
    x = shock_crack(d, 0.0, 9.0, 2.2)
    pulse = fft_filter(friedlander(1.0, 28.0), high=900) * 3.0
    place(x, pulse, 0.0)
    place(x, fft_filter(pulse, high=500), 0.011, 0.7)  # ground reflection
    # saturated pressure blast: the "crunch"
    blast = fft_filter(white(1.5), high=2200) * env_exp(1.5, 0.22, 0.0008)
    blast = saturate(normalize(blast) * 1.0, 7.0) * 1.6
    place(x, blast, 0.002)
    # body: deep boom
    boom = sweep(3.0, 95, 26, 0.35) * env_exp(3.0, 0.7, 0.003) * 2.2
    boom += fft_filter(white(3.0), high=300) * env_exp(3.0, 0.9, 0.004) * 2.6
    place(x, boom, 0.0)
    # rolling thunder tail with slow surges
    roll = fft_filter(white(d), high=260) * env_exp(d, 2.6, 0.25) * slow_mod(d, 1.2, 0.6) * 2.4
    x += roll
    x += fft_filter(crackle(d, 160, decay_s=1.5, start=0.05), low=500) * 0.5
    x = terrain_echoes(x, [(0.45, 0.45, 1500), (1.1, 0.35, 900), (1.9, 0.25, 600), (3.0, 0.15, 400)])
    x = reverb(x, seconds=4.0, decay=1.5, mix=0.28, damp=3000)
    x = multiband_crush(x, (4.0, 2.6, 1.6))
    save("explosion/near", fade(limiter(x, 0.99, 1.6), fout=1.5))


def explosion_sub():
    d = 7.0
    t = t_axis(d)
    tone = sweep(d, 58, 22, 0.3) * env_exp(d, 1.4, 0.003)
    rumble = fft_filter(white(d), high=75, slope=3) * env_exp(d, 2.2, 0.01) * slow_mod(d, 1.5, 0.5)
    x = harmonics_for_sub(normalize(tone) * 1.3 + normalize(rumble))
    save("explosion/sub", fade(limiter(x, 0.99, 2.6), fout=1.5))


def explosion_far():
    d = 9.0
    x = np.zeros(n_samples(d))
    boom = sweep(3.0, 70, 22, 0.4) * env_exp(3.0, 0.9, 0.03) * 2.0
    boom += fft_filter(white(3.0), high=220) * env_exp(3.0, 1.0, 0.03) * 2.5
    place(x, boom, 0.0)
    x += fft_filter(white(d), high=160) * env_exp(d, 3.0, 0.4) * slow_mod(d, 0.8, 0.7) * 2.2
    x = terrain_echoes(x, [(0.7, 0.6, 400), (1.6, 0.5, 300), (2.8, 0.4, 250), (4.2, 0.25, 200)])
    x = reverb(x, seconds=5.0, decay=2.2, mix=0.5, damp=500)
    x = fft_filter(x, high=420)
    x[: n_samples(0.06)] *= np.linspace(0, 1, n_samples(0.06))
    save("explosion/far", fade(limiter(multiband_crush(x, (3.0, 1.8, 1.2)), 0.99, 1.7), fout=2.0))


def explosion_debris():
    d = 7.0
    x = debris_rain(d, 0.6, 520, 1.3)
    x += fft_filter(crackle(d, 90, decay_s=2.5, start=0.8), low=800) * 0.25
    x = reverb(x, seconds=1.5, decay=0.4, mix=0.25)
    save("explosion/debris", fade(limiter(x, 0.9, 1.3), fout=1.5))


def nuke_near():
    d = 18.0
    t = t_axis(d)
    x = shock_crack(d, 0.0, 14.0, 2.5)
    pulse = fft_filter(friedlander(3.0, 160.0, 1.3), high=400) * 3.5
    place(x, pulse, 0.0)
    place(x, fft_filter(pulse, high=250), 0.03, 0.7)
    blast = fft_filter(white(3.0), high=1600) * env_exp(3.0, 0.5, 0.001)
    place(x, saturate(normalize(blast), 8.0) * 2.0, 0.003)
    boom = sweep(6.0, 70, 18, 0.3) * env_exp(6.0, 2.0, 0.008) * 2.6
    boom += fft_filter(white(6.0), high=500) * env_exp(6.0, 1.3, 0.005) * 3.2
    place(x, boom, 0.0)
    # the fireball keeps roaring like a gigantic furnace
    furnace = fft_filter(white(d), low=40, high=700, slope=1.3) * slow_mod(d, 4, 0.45)
    furnace *= np.interp(t, [0, 0.3, 4.0, 10.0, 18.0], [0, 1, 0.8, 0.25, 0]) * 1.6
    x += furnace
    roll = fft_filter(white(d), high=200) * env_exp(d, 6.5, 0.4) * slow_mod(d, 0.9, 0.7) * 3.2
    x += roll
    x += fft_filter(crackle(d, 260, decay_s=4.0, start=0.1), low=400) * 0.6
    x = terrain_echoes(x, [(0.6, 0.5, 1200), (1.5, 0.45, 700), (2.6, 0.35, 450), (4.0, 0.3, 350), (6.0, 0.2, 250)])
    x = reverb(x, seconds=6.0, decay=2.4, mix=0.32, damp=2200)
    x = multiband_crush(x, (4.5, 3.0, 1.7))
    save("nuke/near", fade(limiter(x, 0.99, 1.8), fout=3.0))


def nuke_sub():
    d = 16.0
    t = t_axis(d)
    tone = sweep(d, 42, 16, 0.25) * env_exp(d, 4.0, 0.01)
    rumble = fft_filter(white(d), high=60, slope=3) * env_exp(d, 6.0, 0.05) * slow_mod(d, 0.7, 0.6)
    quake = fft_filter(white(d), high=30, slope=3) * np.interp(t, [0, 0.5, 3, 16], [0, 1, 0.7, 0])
    x = harmonics_for_sub(normalize(tone) * 1.3 + normalize(rumble) + normalize(quake) * 0.8)
    save("nuke/sub", fade(limiter(x, 0.99, 2.8), fout=3.0))


def nuke_far():
    d = 18.0
    t = t_axis(d)
    swell = np.interp(t, [0, 0.25, 2.0, 18], [0, 1, 0.8, 0])
    sub = sweep(d, 38, 15, 0.4) * swell * 2.0
    roll = fft_filter(white(d), high=120) * swell * slow_mod(d, 0.6, 0.75) * 3.2
    x = sub + roll
    x = terrain_echoes(x, [(1.0, 0.6, 250), (2.3, 0.55, 200), (3.8, 0.45, 160), (5.5, 0.35, 140), (8.0, 0.25, 120)])
    x = reverb(x, seconds=7.0, decay=3.0, mix=0.55, damp=500)
    x = fft_filter(x, high=260)
    save("nuke/far", fade(limiter(multiband_crush(x, (3.5, 2.0, 1.2)), 0.99, 2.0), fout=3.0))


def nuke_wind():
    d = 11.0
    t = t_axis(d)
    swell = np.interp(t, [0, 0.25, 2.5, 11.0], [0, 1, 0.6, 0])
    rush = fft_filter(white(d), low=120, high=3000, slope=1.2) * swell * slow_mod(d, 3, 0.5)
    howl = np.zeros_like(t)
    for f0 in (170, 255, 390, 560):
        wob = f0 * (1 + 0.18 * np.sin(2 * np.pi * 0.27 * t + f0))
        howl += np.sin(2 * np.pi * np.cumsum(wob) / SR) * 0.08
    howl *= swell
    debris = debris_rain(d, 0.4, 300, 1.8) * 0.7
    x = reverb(rush + howl + debris, seconds=2.5, decay=0.8, mix=0.35)
    save("nuke/wind", fade(limiter(x, 0.95, 1.5), fout=2.0))


def jet_loop():
    """Turbofan: screaming compressor whine, blade-pass tones, broadband jet roar."""
    d = 4.0
    t = t_axis(d + 1.0)
    whine = np.zeros_like(t)
    for f0, a in ((2950, 0.35), (4420, 0.18), (5900, 0.1), (1475, 0.22)):
        wob = f0 * (1 + 0.004 * np.sin(2 * np.pi * 0.8 * t + f0))
        whine += np.sin(2 * np.pi * np.cumsum(wob) / SR) * a
    whine *= slow_mod(d + 1.0, 4, 0.15)
    roar = fft_filter(white(d + 1.0), low=150, high=2500, slope=1.3) * 1.2 * slow_mod(d + 1.0, 6, 0.25)
    rumble = fft_filter(white(d + 1.0), high=160) * 0.9
    hiss = fft_filter(white(d + 1.0), low=4000, high=11000) * 0.25
    x = multiband_crush(whine * 0.5 + roar + rumble + hiss, (2.2, 1.8, 1.4))
    save("missile/jet", limiter(make_loop(x, d), 0.95, 1.4))


def cluster_pop():
    d = 3.0
    x = shock_crack(d, 0.0, 3.0, 1.2)
    burst = fft_filter(white(0.6), low=300, high=6000) * env_exp(0.6, 0.08, 0.0005)
    place(x, saturate(normalize(burst), 3.0), 0.0)
    thump = sweep(0.8, 160, 60, 0.5) * env_exp(0.8, 0.15, 0.002)
    place(x, thump, 0.0)
    # the bomblets rattle out of the dispenser
    for i in range(28):
        clank = fft_filter(white(0.05), low=1500, high=8000) * env_exp(0.05, 0.01, 0.0002)
        place(x, clank, 0.05 + rng.uniform(0, 0.4), rng.uniform(0.15, 0.4))
    x = terrain_echoes(x, [(0.4, 0.3, 1500), (1.0, 0.2, 800)])
    x = reverb(x, seconds=2.0, decay=0.8, mix=0.35, damp=3000)
    save("missile/cluster_pop", fade(limiter(x, 0.95, 1.5), fout=0.8))


def thermobaric():
    """Fuel-air: a deep WHOOMPH, a long roaring fireball and a vacuum suck-back."""
    d = 12.0
    t = t_axis(d)
    x = np.zeros(n_samples(d))
    # suction of the dispersing fuel cloud
    place(x, fft_filter(white(0.35), low=200, high=1500) * np.linspace(0, 1, n_samples(0.35)) ** 2 * 0.6, 0.0)
    ign = 0.32
    place(x, shock_crack(1.0, 0.0, 12.0, 2.0), ign)
    whoomph = sweep(3.0, 70, 22, 0.35) * env_exp(3.0, 0.9, 0.01) * 2.8
    whoomph += fft_filter(white(3.0), high=380) * env_exp(3.0, 0.7, 0.006) * 3.2
    place(x, whoomph, ign)
    roar = fft_filter(white(d), low=60, high=1600, slope=1.3) * slow_mod(d, 5, 0.5)
    roar *= np.interp(t, [0, ign, ign + 0.2, 3.5, 8.0, 12.0], [0, 0, 1.6, 1.0, 0.3, 0])
    x += roar
    x += fft_filter(crackle(d, 320, decay_s=3.0, start=ign), low=500) * 0.7
    x += fft_filter(white(d), high=180) * env_exp(d, 3.5, 0.4) * slow_mod(d, 1.0, 0.6) * 2.2
    x = terrain_echoes(x, [(0.6, 0.45, 1200), (1.4, 0.35, 700), (2.5, 0.25, 450)])
    x = reverb(x, seconds=5.0, decay=1.8, mix=0.3, damp=2500)
    x = multiband_crush(x, (4.2, 2.8, 1.6))
    save("explosion/thermobaric", fade(limiter(x, 0.99, 1.7), fout=2.0))


def bunker():
    """Deep underground detonation: muffled, ground-shaking, then rocks and dirt raining down."""
    d = 10.0
    x = np.zeros(n_samples(d))
    boom = sweep(4.0, 60, 18, 0.3) * env_exp(4.0, 1.4, 0.02) * 3.0
    boom += fft_filter(white(4.0), high=160) * env_exp(4.0, 1.0, 0.015) * 3.4
    place(x, boom, 0.0)
    # the ground cracks open
    place(x, fft_filter(crackle(3.0, 500, decay_s=0.8), low=200, high=3000) * 0.9, 0.12)
    x += debris_rain(d, 0.9, 700, 1.6) * 0.9
    x += fft_filter(white(d), high=110) * env_exp(d, 3.0, 0.2) * slow_mod(d, 1.0, 0.6) * 2.0
    x = terrain_echoes(x, [(0.7, 0.4, 500), (1.6, 0.3, 350)])
    x = reverb(x, seconds=3.0, decay=1.2, mix=0.3, damp=1200)
    save("explosion/bunker", fade(limiter(multiband_crush(x, (4.5, 2.4, 1.4)), 0.99, 1.8), fout=1.5))


def explosion_mid():
    """A few hundred blocks away: crisp but rounded crack, then rolling thunder off the hills."""
    d = 10.0
    x = np.zeros(n_samples(d))
    crack = fft_filter(shock_crack(0.3, 0.0, 12.0, 1.6), high=3500)
    place(x, crack, 0.0)
    pulse = fft_filter(friedlander(1.2, 40.0), high=450) * 2.6
    place(x, pulse, 0.0)
    boom = fft_filter(white(3.0), high=350) * env_exp(3.0, 0.8, 0.01) * 2.4
    place(x, boom, 0.0)
    x += fft_filter(white(d), high=220) * env_exp(d, 2.8, 0.3) * slow_mod(d, 1.0, 0.7) * 2.4
    x = terrain_echoes(x, [(0.6, 0.55, 900), (1.4, 0.45, 600), (2.4, 0.35, 400), (3.6, 0.25, 300)])
    x = reverb(x, seconds=5.0, decay=2.0, mix=0.42, damp=1200)
    save("explosion/mid", fade(limiter(multiband_crush(x, (3.5, 2.2, 1.4)), 0.99, 1.7), fout=2.0))


def nuke_mid():
    """Nuclear blast from a few kilometres: a sharp thud, then minutes-long thunder (compressed)."""
    d = 18.0
    t = t_axis(d)
    x = np.zeros(n_samples(d))
    place(x, fft_filter(shock_crack(0.4, 0.0, 30.0, 1.8), high=1800), 0.0)
    place(x, fft_filter(friedlander(3.0, 220.0, 1.2), high=220) * 3.2, 0.0)
    swell = np.interp(t, [0, 0.1, 2.5, 18], [0, 1, 0.75, 0])
    x += fft_filter(white(d), high=180) * swell * slow_mod(d, 0.7, 0.75) * 3.0
    x += sweep(d, 36, 16, 0.4) * swell * 1.6
    x = terrain_echoes(x, [(0.9, 0.6, 400), (2.0, 0.5, 300), (3.4, 0.4, 220), (5.0, 0.3, 180), (7.5, 0.22, 150)])
    x = reverb(x, seconds=6.0, decay=2.8, mix=0.5, damp=800)
    save("nuke/mid", fade(limiter(multiband_crush(x, (4.0, 2.4, 1.3)), 0.99, 1.9), fout=3.0))


def ear_ringing():
    """Acoustic trauma tinnitus: a pure high tone with slow beating that fades over ~12 s."""
    d = 13.0
    t = t_axis(d)
    tone = np.sin(2 * np.pi * 4100 * t) + 0.6 * np.sin(2 * np.pi * 4107 * t) + 0.15 * np.sin(2 * np.pi * 6150 * t)
    hiss = fft_filter(white(d), low=3000, high=9000) * 0.08
    env = np.clip(t / 0.25, 0, 1) * np.exp(-t / 4.5)
    save("ear/ringing", fade(normalize((tone * 0.35 + hiss) * env, 0.55), fout=1.0))


def sonic_boom():
    """Hypersonic pass: the classic double N-wave crack (bow + tail shock) plus rushing air."""
    d = 5.0
    x = np.zeros(n_samples(d))
    for at in (0.0, 0.11):
        place(x, n_wave(16.0) * 1.0, at)
        place(x, fft_filter(white(0.12), low=800) * env_exp(0.12, 0.02, 0.0002) * 0.6, at)
    x += fft_filter(white(d), low=100, high=2500) * env_exp(d, 0.9, 0.05) * 0.6
    x = terrain_echoes(x, [(0.5, 0.4, 1500), (1.2, 0.3, 900), (2.1, 0.2, 600)])
    x = reverb(x, seconds=3.0, decay=1.2, mix=0.35, damp=2500)
    save("missile/sonic_boom", fade(limiter(x, 0.98, 1.6), fout=1.0))


def radar_alarm():
    """Two-tone warbling klaxon of the early-warning radar."""
    d = 2.0
    t = t_axis(d)
    f = np.where((t * 4).astype(int) % 2 == 0, 880.0, 660.0)
    phase = 2 * np.pi * np.cumsum(f) / SR
    tone = np.sign(np.sin(phase)) * 0.5 + np.sin(2 * phase) * 0.2
    tone = fft_filter(tone, high=4500) * np.clip(t / 0.02, 0, 1)
    x = reverb(tone, seconds=1.0, decay=0.35, mix=0.25)
    save("radar/alarm", fade(normalize(x, 0.8), fout=0.1))


def sam_launch():
    """Interceptor leaving its canister: ejection thump, booster whoosh, receding roar."""
    d = 4.0
    t = t_axis(d)
    x = np.zeros(n_samples(d))
    place(x, sweep(0.5, 140, 50, 0.5) * env_exp(0.5, 0.12, 0.002) * 1.6, 0.0)
    place(x, fft_filter(white(0.2), low=300, high=5000) * env_exp(0.2, 0.05, 0.001), 0.0)
    roar = fft_filter(white(d), low=150, high=4500) * slow_mod(d, 8, 0.3)
    roar *= np.interp(t, [0, 0.08, 0.5, 4.0], [0, 1.2, 0.8, 0]) ** 1.2
    x += roar + fft_filter(crackle(d, 300, decay_s=1.0), low=500) * 0.4
    x = lowpass_sweep(x, 9000, 1500, 0.8)  # flies away
    x = reverb(x, seconds=2.5, decay=0.9, mix=0.3)
    save("air_defense/launch", fade(limiter(x, 0.95, 1.4), fout=0.6))


def geiger_click():
    d = 0.06
    x = np.zeros(n_samples(d))
    place(x, n_wave(0.6), 0.0)
    place(x, fft_filter(white(0.01), low=2000) * env_exp(0.01, 0.002, 0.0001) * 0.5, 0.0)
    save("geiger/click", fade(normalize(x, 0.7), fin=0.0, fout=0.01))


def lock():
    d = 0.5
    t = t_axis(d)
    x = np.zeros_like(t)
    for start, f in ((0.0, 1600), (0.11, 2400), (0.22, 3200)):
        seg = (t >= start) & (t < start + 0.08)
        tt = t[seg] - start
        x[seg] += np.sin(2 * np.pi * f * tt) * np.exp(-tt / 0.05)
    x = reverb(x, seconds=0.5, decay=0.12, mix=0.2)
    save("designator/lock", fade(normalize(x, 0.6)))


if __name__ == "__main__":
    print("synthesizing sounds:")
    siren()
    beep()
    ignition()
    ignition_sub()
    engine_loop()
    engine_crackle()
    jet_loop()
    cluster_pop()
    thermobaric()
    bunker()
    incoming()
    explosion_near()
    explosion_sub()
    explosion_far()
    explosion_debris()
    nuke_near()
    nuke_sub()
    nuke_far()
    nuke_wind()
    lock()
    explosion_mid()
    nuke_mid()
    ear_ringing()
    sonic_boom()
    radar_alarm()
    sam_launch()
    geiger_click()
    print("sounds ok")
