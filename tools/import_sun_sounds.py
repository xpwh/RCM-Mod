"""12.12: the Sun Destroyer's sounds from real recordings (freesound.org, CC0 / CC BY - see CREDITS.md), taking
the place of the synthesized ones. Recordings are cut, layered, slowed or sped up, filtered and reversed - nothing
is generated.

usage: python3 import_sun_sounds.py <dir with the 44.1 kHz mono WAVs named fs<id>.wav>

The launch complex: a submarine's dive klaxon and an air-raid siren, heavy steel doors and hydraulics, a diesel
generator and a ventilation plant spooled up into the coils' charging whine, firecracker snaps for the arcs, and
the countdown beeps. The shot: a line charge, a sonic boom and the space shuttle's ignition, layered. The round in
flight: the shuttle's roar. At the target: a demolition run backwards into the collapsing core, a firework's deep
sub blast with a water tower coming down for the ignition, a rocket's ground rumble and a fighter's afterburner,
slowed, for the burning star, a chimney blast run backwards rising into the nova, and the nova itself - sonic
boom, the chimney and the water tower, the sub blast and rolling thunder. Cinematic hits from the sub blast and
thunder slowed an octave, and the shock wind from a jet's roar high up.
"""
import numpy as np

from gen_sounds import fft_filter, limiter, normalize
from import_gun_sounds import SR, envelope, load, save, stretch


def seg(n, start, seconds, fade_in=0.01, fade_out=0.05):
    x = load("fs%d" % n)
    a = int(start * SR)
    y = x[a:a + int(seconds * SR)].copy()
    k = max(1, int(fade_in * SR))
    y[:k] *= np.linspace(0, 1, k)
    k = max(1, int(fade_out * SR))
    y[-k:] *= np.linspace(1, 0, k)
    return y


def attack(n, around, seconds, window=1.5, pre=0.02):
    """The blast nearest `around` seconds and what follows it."""
    x = load("fs%d" % n)
    a = max(0, int((around - window / 2) * SR))
    w = x[a:a + int(window * SR)]
    env = np.convolve(np.abs(w), np.ones(64) / 64, mode="same")
    p = int(np.argmax(env))
    k = p
    while k > 0 and env[k] > env[p] * 0.15:
        k -= 1
    s = max(0, a + k - int(pre * SR))
    return x[s:s + int(seconds * SR)].copy()


def at(x, seconds, total):
    """x placed `seconds` into a buffer `total` seconds long."""
    out = np.zeros(int(total * SR))
    a = int(seconds * SR)
    n = min(len(x), len(out) - a)
    out[a:a + n] = x[:n]
    return out


def mix(total, *parts):
    """(signal, gain, start seconds) parts summed into one buffer."""
    out = np.zeros(int(total * SR))
    for x, g, t in parts:
        out += at(x, t, total) * g
    return out


def norm(x):
    return x / (np.sqrt(np.mean(x ** 2)) + 1e-9) * 0.1


def env_out(x, start, end):
    """Fade x out between `start` and `end` seconds."""
    y = x.copy()
    a, b = int(start * SR), min(len(x), int(end * SR))
    y[a:b] *= np.linspace(1, 0, b - a) ** 1.5
    y[b:] = 0.0
    return y


def ramp_pitch(x, f0, f1, seconds):
    """Read through x at a speed going from f0 to f1 (pitch and pace together, like a spooling machine)."""
    n = int(seconds * SR)
    speed = np.linspace(f0, f1, n)
    pos = np.cumsum(speed)
    pos = pos[pos < len(x) - 1]
    return np.interp(pos, np.arange(len(x)), x)


def loop(x, xfade=0.6):
    k = int(xfade * SR)
    y = x[:-k].copy()
    r = np.linspace(0, 1, k)
    y[:k] = y[:k] * r + x[-k:] * (1 - r)
    return y


def load_thud():
    return load("fs640204")


def big(name, x, highpass=18.0, drive=1.8):
    save(name, limiter(fft_filter(x, low=highpass, slope=1.5), 0.98, drive), highpass=highpass)


def main():
    # ---- the launch complex
    # the klaxon: a submarine's dive alarm, two blasts, with the hall of the complex ringing after them
    k = load("fs156672")[: int(2.3 * SR)]
    hall = fft_filter(stretch(k, 1.25), high=2500.0) * 0.35
    save("sun/klaxon", mix(3.4, (k, 1.0, 0.0), (hall, 1.0, 0.12)), highpass=110.0)
    # the air-raid siren winding up over the complex as the countdown begins
    save("sun/siren", env_out(seg(382611, 0.0, 16.0, fade_in=0.3), 12.0, 16.0), highpass=90.0)
    # the blast doors: a heavy steel door slowed to the size of the shaft's lid, the hydraulics, a thud as they stop
    door = stretch(seg(383830, 0.0, 9.0), 1.6)
    rams = stretch(seg(835133, 0.0, 5.5), 1.35)
    thud = stretch(load("fs640204"), 1.8)
    big("sun/doors", mix(15.0, (norm(door), 1.0, 0.0), (norm(rams), 0.7, 0.4), (norm(thud), 1.4, 13.0)), highpass=30.0, drive=1.3)
    # the lift: hydraulic rams working and a chain running
    lift = stretch(seg(637811, 0.5, 9.0), 1.3)
    chain = stretch(seg(798148, 0.0, 9.0), 1.2)
    save("sun/lift", mix(11.0, (norm(lift), 1.0, 0.0), (norm(chain), 0.35, 0.8)), highpass=40.0)
    # the coils charging: a generator and a ventilation plant spooled up from a growl to a scream over 15 s
    gen = np.tile(seg(606933, 10.0, 20.0), 3)
    vent = np.tile(seg(835642, 1.0, 20.0), 3)
    whine = ramp_pitch(gen, 0.55, 3.2, 15.0)
    air = ramp_pitch(vent, 0.7, 2.2, 15.0)
    n = min(len(whine), len(air))
    rise = np.linspace(0.35, 1.0, n) ** 1.5
    charge = (norm(whine[:n]) * 0.8 + norm(fft_filter(air[:n], low=300.0)) * 0.5) * rise
    save("sun/charge", charge, highpass=50.0)
    # the arcs: firecracker snaps, high-passed into the crack of a discharge
    snaps = [attack(609588, 0.05, 0.5), attack(840508, 0.1, 0.6)]
    for i, x in enumerate(snaps):
        y = fft_filter(x, low=900.0)
        y *= np.exp(-np.arange(len(y)) / (0.09 * SR))
        save("sun/arc%d" % (i + 1), y, highpass=900.0)
    # the countdown beep
    b = load("fs536422")
    i = int(np.argmax(np.abs(b) > 0.05)) - int(0.002 * SR)
    beep = b[max(i, 0): max(i, 0) + int(0.18 * SR)]
    save("sun/beep", mix(0.9, (beep, 1.0, 0.0), (fft_filter(stretch(beep, 1.02), high=3000.0), 0.25, 0.09), (beep, 0.08, 0.2)), highpass=200.0)

    # loading the round into the complex: an RPG grenade slid into its tube, slowed to the size of the thing, a thud home
    slide = stretch(seg(725401, 0.0, 2.6), 1.7)
    save("sun/load", mix(5.0, (norm(slide), 1.0, 0.0), (norm(stretch(load_thud(), 1.4)), 1.2, 4.0)), highpass=40.0)
    # the launch called off: the klaxon slowed and low, the pressure let out of the system
    k2 = stretch(load("fs156672")[: int(2.3 * SR)], 1.35)
    hiss = seg(454033, 0.0, 6.0)
    hiss = hiss[int(np.argmax(envelope(hiss) > envelope(hiss).max() * 0.5)):]
    save("sun/abort", mix(5.0, (norm(k2), 1.0, 0.0), (norm(hiss[: int(3.0 * SR)]), 0.7, 2.6)), highpass=80.0)

    # ---- the shot: a line charge's blast, a sonic boom on top, the shuttle's ignition roaring after it
    crack = attack(162261, 0.3, 2.2)
    boom = attack(182050, 0.9, 5.0)
    roar = seg(90765, 262.0, 14.0, fade_in=0.4)
    fire = mix(14.0, (norm(crack), 1.4, 0.0), (norm(boom), 1.2, 0.02), (norm(fft_filter(roar, high=4000.0)), 0.9, 0.15))
    big("sun/fire", env_out(fire, 9.0, 14.0), drive=1.9)
    # far off: the 305 mm cannon from kilometres away, its long echo
    big("sun/fire_far", attack(258203, 0.24, 12.0), highpass=18.0, drive=1.6)
    # the round in flight: the shuttle at full thrust
    save("sun/flight", loop(fft_filter(seg(90765, 300.0, 12.0, fade_in=0.2), low=30.0, high=6000.0), 1.0), highpass=30.0)

    # ---- at the target
    # the core collapsing: a building's demolition run backwards - everything drawn in, ending in a hard stop
    implo = seg(98852, 110.0, 14.0, fade_in=0.01)[::-1].copy()
    implo *= np.linspace(0.2, 1.0, len(implo)) ** 2
    big("sun/implode", implo, highpass=25.0, drive=1.5)
    # ignition: the sub blast, slowed, the water tower and the echoing explosion over it
    sub = stretch(attack(840759, 1.0, 6.0), 1.6)
    tower = attack(866989, 181.1, 9.0)
    echo = attack(256297, 0.34, 9.0)
    big("sun/ignite", mix(12.0, (norm(sub), 1.3, 0.0), (norm(tower), 1.0, 0.03), (norm(echo), 0.8, 0.05)), drive=2.0)
    # the star burning: a rocket's ground rumble and a fighter's afterburner, slowed into a roiling roar
    rumble = seg(613617, 2.0, 24.0, fade_in=0.2)
    burner = stretch(seg(162242, 8.0, 7.0), 2.2)
    burn = mix(20.0, (norm(fft_filter(rumble, high=900.0)), 1.0, 0.0), (norm(fft_filter(burner, high=2500.0)), 0.6, 0.5),
               (norm(fft_filter(burner, high=2500.0)), 0.6, 10.5))
    save("sun/burn", loop(burn[: int(19.5 * SR)], 1.5), highpass=20.0)
    # the collapse: a chimney blast run backwards, rising into the nova, which lands on its last instant
    rev = attack(768370, 5.2, 4.0)[::-1].copy()
    thunder = stretch(seg(683419, 0.5, 3.5), 1.4)[::-1].copy()
    col = mix(4.0, (norm(rev), 1.0, 0.0), (norm(fft_filter(thunder, high=600.0)), 0.7, 0.0))
    col *= np.linspace(0.15, 1.0, len(col)) ** 2.5
    big("sun/collapse", col, highpass=22.0, drive=1.6)
    # the nova: everything at once - sonic boom, chimney, water tower, the slowed sub blast - and the thunder after
    nova = mix(16.0, (norm(boom), 1.3, 0.0), (norm(attack(768370, 5.2, 9.0)), 1.1, 0.02), (norm(tower), 1.0, 0.06),
               (norm(stretch(attack(840759, 1.0, 5.0), 2.0)), 1.4, 0.0), (norm(seg(119403, 3112.0 / 10.0, 14.0, fade_in=0.4)), 0.9, 1.5))
    big("sun/nova", env_out(nova, 11.0, 16.0), drive=2.1)
    # from far off: the woods blast slowed and deep thunder rolling over it
    far = mix(16.0, (norm(stretch(attack(256299, 0.38, 8.0), 1.5)), 1.0, 0.0), (norm(seg(576963, 6.8, 15.0, fade_in=0.3)), 0.9, 0.4))
    big("sun/nova_far", env_out(far, 11.0, 16.0), drive=1.7)

    # ---- the cinematic hits and the shock wind
    hit = mix(7.0, (norm(stretch(attack(840759, 1.0, 3.0), 2.0)), 1.0, 0.0), (norm(fft_filter(stretch(seg(683419, 1.0, 4.0), 1.5), high=500.0)), 0.6, 0.0))
    big("sun/hit", env_out(hit, 4.5, 7.0), highpass=20.0, drive=1.7)
    wind = fft_filter(seg(451953, 10.0, 7.0, fade_in=0.02), high=1800.0)
    wind *= np.exp(-np.arange(len(wind)) / (2.2 * SR))
    shock = mix(7.0, (norm(attack(182050, 0.9, 4.0)), 1.2, 0.0), (norm(wind), 0.9, 0.05))
    big("sun/shock", shock, highpass=20.0, drive=1.8)


if __name__ == "__main__":
    main()
