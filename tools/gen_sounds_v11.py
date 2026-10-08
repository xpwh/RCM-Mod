"""1.10 AK-47 sound set, modelled on recordings of real 7.62x39 rifles.

  ak/shot          close: the muzzle blast (a hard pressure spike, saturated like a real mic), the
                   body of the report, the bolt carrier slamming back and home, ground reflection
  ak/shot_mid      100-250 m: the top end gone, the crack softer, longer and boomier
  ak/shot_far      far: a dull pop rolling into a low rumble
  ak/mech          the action cycling, heard by the shooter (bolt carrier, spring, case ejected)
  ak/tail_outdoor  the report rolling away over open country
  ak/tail_indoor   a short, bright, dense room slap
  ak/tail_cave     a long, dark, dense cavern roar
  ak/dry, ak/selector, ak/mag_out, ak/mag_in, ak/charge, ak/shell   handling
  bullet/crack     the supersonic snap of a bullet passing close
  bullet/whiz      a slower round fluttering past
  bullet/ricochet  the classic whine of a tumbling bullet skipping off rock
  bullet/impact_*  dirt, stone, metal, wood, flesh

python3 gen_sounds_v11.py   (writes into the mod's sounds folder; real recordings, when present,
replace the close shots: see import_gun_sounds.py)
"""
import numpy as np

import gen_sounds as s
from gen_sounds import env_exp, fade, fft_filter, limiter, n_samples, place, t_axis, white
from gen_sounds_v5 import save

SR = s.SR
rng = np.random.default_rng(1947)


def conv(x, ir):
    n = len(x) + len(ir) - 1
    size = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(ir, size), size)[:n]


def ir(seconds, decay, low=None, high=None, density_ms=0.0, early=()):
    """Synthetic impulse response: decaying filtered noise, optional discrete early reflections."""
    t = t_axis(seconds)
    noise = white(seconds) * np.exp(-t / decay)
    if density_ms > 0:
        # sparse at first (distinct reflections), denser later
        mask = rng.random(len(t)) < np.clip(t / (density_ms / 1000.0), 0.02, 1.0)
        noise *= mask
    noise = fft_filter(noise, low=low, high=high, slope=1.2)
    out = noise / (np.max(np.abs(noise)) + 1e-9)
    for at, gain in early:
        i = n_samples(at)
        if i < len(out):
            out[i] += gain
    return out


def ring(seconds, freqs, decay, amp=1.0):
    """Metallic ring: a few damped partials."""
    t = t_axis(seconds)
    x = np.zeros(len(t))
    for f, a in freqs:
        x += np.sin(2 * np.pi * f * t + rng.random() * 6.28) * a
    return x * np.exp(-t / decay) * amp


def click(length_ms, low, high, decay_ms, gain=1.0):
    seconds = length_ms / 1000.0
    return fft_filter(white(seconds), low=low, high=high) * env_exp(seconds, decay_ms / 1000.0, 0.0003) * gain



def mix(*parts):
    """Sum arrays of different lengths (padded at the end)."""
    n = max(len(q) for q in parts)
    out = np.zeros(n)
    for q in parts:
        out[:len(q)] += q
    return out


def blast(seconds, body_hz=220.0, crack=1.0):
    """The muzzle blast: N-wave spike, a saturated broadband burst and the low body of the report."""
    t = t_axis(seconds)
    x = np.zeros(len(t))
    # the shock front: positive spike then the suction phase
    n = n_samples(0.0012)
    x[:n] += np.linspace(1.0, -0.6, n) * 3.0 * crack
    burst = fft_filter(white(seconds), low=200, high=12000, slope=1.0) * np.exp(-t / 0.018)
    body = fft_filter(white(seconds), low=60, high=body_hz * 3, slope=1.5) * np.exp(-t / 0.06)
    boom = np.sin(2 * np.pi * body_hz * 0.45 * t) * np.exp(-t / 0.05)
    x += burst * 2.2 * crack + body * 3.0 + boom * 0.8
    return np.tanh(x * 2.4) / np.tanh(2.4)  # the mic overloading


def mech(seconds=0.35):
    """Bolt carrier slamming back, the case flicked out, the carrier slamming home."""
    x = np.zeros(n_samples(seconds))
    place(x, mix(click(25, 900, 7000, 6, 1.0), ring(0.025, [(2100, 0.4), (3300, 0.3)], 0.01)), 0.004)
    place(x, ring(0.06, [(5200, 0.3), (7400, 0.25)], 0.02), 0.012)            # case tinkle out of the port
    place(x, mix(click(30, 500, 6000, 8, 1.3), ring(0.04, [(1600, 0.5), (2700, 0.35), (4100, 0.2)], 0.015)), 0.055)
    place(x, fft_filter(white(0.05), low=2000, high=8000) * env_exp(0.05, 0.015) * 0.2, 0.03)  # spring
    return x


def shot_close():
    seconds = 0.9
    x = np.zeros(n_samples(seconds))
    b = blast(0.5, body_hz=240.0)
    place(x, b, 0.0)
    place(x, b * 0.35, 0.006)          # ground reflection
    place(x, mech() * 0.5, 0.0)
    # the near field "slap" of nearby surfaces
    x += conv(x, ir(0.25, 0.05, low=300, high=7000, early=((0.011, 0.4), (0.023, 0.25))))[:len(x)] * 0.25
    return fade(limiter(x, 0.99, 2.2), 0.0005, 0.25)


def shot_mid():
    seconds = 1.4
    x = np.zeros(n_samples(seconds))
    b = blast(0.6, body_hz=200.0, crack=0.6)
    b = fft_filter(b, low=50, high=3500, slope=1.4)
    place(x, b, 0.0)
    place(x, b * 0.5, 0.03)
    x += conv(x, ir(1.2, 0.35, low=80, high=2500, density_ms=60))[:len(x)] * 0.35
    return fade(limiter(x, 0.97, 1.8), 0.002, 0.4)


def shot_far():
    seconds = 2.0
    x = np.zeros(n_samples(seconds))
    b = fft_filter(blast(0.7, body_hz=160.0, crack=0.3), low=40, high=900, slope=1.6)
    place(x, b, 0.0)
    rumble = fft_filter(white(seconds), low=30, high=260, slope=2.0) * np.interp(t_axis(seconds), [0, 0.05, 0.4, seconds], [0, 1.0, 0.5, 0])
    x += rumble * 0.8
    x += conv(x, ir(1.6, 0.5, low=40, high=700, density_ms=120))[:len(x)] * 0.4
    return fade(limiter(x, 0.96, 1.6), 0.005, 0.6)


def tail(seconds, decay, low, high, density, slaps=()):
    imp = np.zeros(n_samples(0.02))
    imp[0] = 1.0
    out = conv(imp, ir(seconds, decay, low=low, high=high, density_ms=density, early=slaps))
    t = np.arange(len(out)) / SR
    out *= np.clip(t / 0.01, 0, 1)
    return fade(limiter(out, 0.9, 1.3), 0.003, seconds * 0.3)


def dry():
    x = np.zeros(n_samples(0.15))
    place(x, mix(click(20, 1500, 9000, 3, 1.0), ring(0.03, [(3100, 0.5), (5600, 0.3)], 0.008)), 0.0)
    return fade(x, 0.0005, 0.05)


def selector():
    x = np.zeros(n_samples(0.25))
    place(x, mix(click(25, 700, 7000, 5, 1.0), ring(0.05, [(1900, 0.6), (3400, 0.3)], 0.018)), 0.0)
    place(x, click(15, 1200, 8000, 3, 0.5), 0.06)
    return fade(x, 0.0005, 0.05)


def mag_out():
    x = np.zeros(n_samples(0.6))
    place(x, mix(click(20, 800, 7000, 4, 1.0), ring(0.04, [(2400, 0.4)], 0.012)), 0.0)          # release paddle
    slide = fft_filter(white(0.18), low=600, high=4000) * np.interp(t_axis(0.18), [0, 0.03, 0.15, 0.18], [0, 0.4, 0.3, 0])
    place(x, slide, 0.04)                                                                     # rocked out of the well
    place(x, click(30, 200, 2500, 10, 0.6), 0.24)                                             # in the hand
    return fade(x, 0.0005, 0.08)


def mag_in():
    x = np.zeros(n_samples(0.6))
    place(x, click(25, 300, 3000, 8, 0.6), 0.0)                                               # front lug hooked in
    slide = fft_filter(white(0.08), low=800, high=5000) * np.interp(t_axis(0.08), [0, 0.02, 0.08], [0, 0.35, 0])
    place(x, slide, 0.05)
    place(x, mix(click(30, 500, 7000, 7, 1.3), ring(0.06, [(1800, 0.6), (2900, 0.4), (4600, 0.2)], 0.02)), 0.14)   # rocked back: the catch snaps
    return fade(x, 0.0005, 0.1)


def charge():
    x = np.zeros(n_samples(0.7))
    scrape = fft_filter(white(0.12), low=1500, high=7000) * np.interp(t_axis(0.12), [0, 0.02, 0.1, 0.12], [0, 0.5, 0.6, 0])
    place(x, click(20, 600, 6000, 4, 0.8), 0.0)
    place(x, scrape, 0.01)                                                                    # carrier pulled back
    place(x, mix(click(25, 500, 6000, 6, 0.9), ring(0.04, [(2300, 0.4)], 0.012)), 0.13)          # at the rear
    place(x, fft_filter(white(0.06), low=2500, high=9000) * env_exp(0.06, 0.02) * 0.4, 0.22)  # let go: spring
    place(x, mix(click(35, 400, 7000, 8, 1.4), ring(0.08, [(1500, 0.6), (2600, 0.5), (3900, 0.3)], 0.025)), 0.26)  # slams home
    return fade(x, 0.0005, 0.1)


def shell():
    x = np.zeros(n_samples(0.8))
    t0 = 0.0
    for i, g in enumerate((1.0, 0.55, 0.3, 0.15)):
        partials = [(rng.uniform(4800, 5600), 0.5), (rng.uniform(7600, 8800), 0.35), (rng.uniform(10500, 12000), 0.2)]
        place(x, mix(ring(0.12, partials, 0.035 - i * 0.006, g), click(6, 3000, 12000, 1.5, 0.4 * g)), t0)
        t0 += 0.09 / (i + 1) + 0.02
    return fade(x, 0.0005, 0.1)


def crack():
    """The bullet's shock wave: a sharp N-wave, a bright snap, a short slap off the ground."""
    seconds = 0.4
    x = np.zeros(n_samples(seconds))
    n = n_samples(0.0009)
    x[:n] = np.linspace(1.0, -1.0, n) * 2.5
    snap = fft_filter(white(0.05), low=1500, high=14000) * env_exp(0.05, 0.006, 0.0002)
    place(x, snap * 1.6, 0.0)
    x += conv(x, ir(0.3, 0.06, low=800, high=9000, early=((0.004, 0.5), (0.012, 0.25))))[:len(x)] * 0.3
    return fade(limiter(x, 0.99, 2.5), 0.0002, 0.1)


def whiz():
    seconds = 0.45
    t = t_axis(seconds)
    f = np.interp(t, [0, 0.2, seconds], [2600, 1700, 900])
    tone = np.sin(2 * np.pi * np.cumsum(f) / SR) * 0.3
    hiss = fft_filter(white(seconds), low=1200, high=6000)
    flutter = 0.6 + 0.4 * np.sin(2 * np.pi * 45 * t)
    env = np.interp(t, [0, 0.18, 0.24, seconds], [0.1, 1.0, 0.9, 0.0])
    return fade((tone + hiss * 0.8) * flutter * env, 0.005, 0.05)


def ricochet(variant):
    seconds = 0.9
    t = t_axis(seconds)
    start = 2400 + variant * 300
    f = start * np.exp(-t * (1.1 + variant * 0.3)) + 450
    tone = np.sin(2 * np.pi * np.cumsum(f) / SR)
    wobble = 1.0 + 0.25 * np.sin(2 * np.pi * (35 + variant * 8) * t)   # the tumbling
    band = fft_filter(white(seconds), low=900, high=5000) * 0.25
    env = np.exp(-t / 0.35) * np.clip(t / 0.01, 0, 1)
    x = (tone * 0.7 * wobble + band) * env
    place(x, click(15, 2000, 12000, 2, 0.8), 0.0)   # the strike
    return fade(x, 0.001, 0.15)


def impact(kind):
    seconds = 0.4
    x = np.zeros(n_samples(seconds))
    if kind == "dirt":
        place(x, fft_filter(white(0.12), low=80, high=2500) * env_exp(0.12, 0.03, 0.0005) * 1.2, 0.0)
        place(x, fft_filter(white(0.2), low=1500, high=6000) * env_exp(0.2, 0.06) * 0.25, 0.01)   # dirt pattering
    elif kind == "stone":
        place(x, click(30, 800, 12000, 4, 1.4), 0.0)
        place(x, fft_filter(white(0.15), low=2000, high=9000) * env_exp(0.15, 0.04) * 0.4, 0.008)  # chips
    elif kind == "metal":
        place(x, mix(click(10, 1500, 12000, 2, 1.0), ring(0.35, [(1900, 0.6), (3150, 0.5), (4700, 0.35), (6900, 0.2)], 0.09)), 0.0)
    elif kind == "wood":
        place(x, mix(click(40, 200, 3000, 10, 1.4), ring(0.06, [(420, 0.5), (900, 0.3)], 0.02)), 0.0)
    else:  # flesh
        place(x, fft_filter(white(0.08), low=60, high=900) * env_exp(0.08, 0.02, 0.001) * 1.6, 0.0)
    return fade(limiter(x, 0.95, 1.5), 0.0003, 0.08)


def main():
    save("ak/shot", shot_close())
    save("ak/shot_mid", shot_mid())
    save("ak/shot_far", shot_far())
    save("ak/mech", fade(mech(), 0.0005, 0.05))
    save("ak/tail_outdoor", tail(2.6, 0.7, 60, 1800, 140, slaps=((0.18, 0.5), (0.42, 0.3), (0.75, 0.2))))
    save("ak/tail_indoor", tail(0.7, 0.12, 200, 7000, 4, slaps=((0.009, 0.6), (0.017, 0.4))))
    save("ak/tail_cave", tail(3.0, 0.9, 50, 1400, 20, slaps=((0.06, 0.5), (0.13, 0.4))))
    save("ak/dry", dry())
    save("ak/selector", selector())
    save("ak/mag_out", mag_out())
    save("ak/mag_in", mag_in())
    save("ak/charge", charge())
    save("ak/shell", shell())
    save("bullet/crack", crack())
    save("bullet/whiz", whiz())
    for v in range(3):
        save(f"bullet/ricochet{v + 1}", ricochet(v))
    for k in ("dirt", "stone", "metal", "wood", "flesh"):
        save(f"bullet/impact_{k}", impact(k))


if __name__ == "__main__":
    main()
