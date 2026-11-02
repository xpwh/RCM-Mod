"""Everyday foley that used to borrow Minecraft's sounds (pistons, anvils, chains, note blocks,
fireworks...) - now all from real recordings (freesound.org, CC0 / CC BY - see CREDITS.md).

usage: python3 import_foley_sounds.py <dir with the 44.1 kHz mono WAVs named fs<id>.wav>

Water: splashes of three sizes. Metal: a heavy thud, a crash of steel and debris. Machinery:
hydraulic rams, compressed-air releases and the gas-generator whoomp of a cold launch, heavy steel
doors, chains, a truck's diesel, a generator and a ventilation plant. Handling: an RPG-7 grenade
being loaded, a knock on it, magazines dropped on hard ground, jacket/gear rustle, a drone set down.
Signals: a radio's key-up tone and squelch. Flares: the pop of a flare cartridge and the burning hiss.
"""
import numpy as np

from gen_sounds import fft_filter, limiter, normalize
from import_gun_sounds import SR, cut, load, loud, peaks, save, stretch


def seg(n, start, seconds, fade_in=0.004):
    x = load("fs%d" % n)
    a = int(start * SR)
    y = x[a:a + int(seconds * SR)].copy()
    k = max(1, int(fade_in * SR))
    y[:k] *= np.linspace(0, 1, k)
    return y


def hits(n, rel, gap, count, seconds, pre=0.01):
    x = load("fs%d" % n)
    return [cut(x, o, seconds, pre=pre) for o in peaks(x, rel=rel, gap=gap)[:count]]


def loop(x, xfade=0.25):
    """Make a steady recording loop without a seam: its end crossfaded into its start."""
    k = int(xfade * SR)
    y = x[:-k].copy()
    ramp = np.linspace(0, 1, k)
    y[:k] = y[:k] * ramp + x[-k:] * (1 - ramp)
    return y


def mix(*parts):
    n = max(len(p) for p, _ in parts)
    out = np.zeros(n)
    for p, g in parts:
        out[:len(p)] += p * g
    return out


def main():
    # ---- water: a magazine plopping in, a body-sized splash, a missile or a jet going in
    felix = load("fs434978")
    small = cut(felix, peaks(felix, rel=0.4, gap=1.0)[0], 0.9)
    save("water/splash_small1", fft_filter(small, low=250.0, slope=1.5), highpass=250.0)
    save("water/splash_small2", stretch(fft_filter(small, low=250.0), 0.8), highpass=250.0)
    big = load("fs442773")
    cannon = hits(260131, 0.5, 3.0, 1, 2.6)[0]
    loud("water/splash1", big)
    loud("water/splash2", cannon)
    loud("water/splash_huge1", fft_filter(stretch(big, 1.8), high=5000.0, slope=1.2))
    loud("water/splash_huge2", fft_filter(stretch(mix((big, 1.0), (cannon, 0.7)), 1.6), high=5000.0, slope=1.2))

    # ---- metal: something heavy set down on steel/earth; a crash of steel and debris
    thud = load("fs640204")
    loud("metal/thud1", thud)
    loud("metal/thud2", stretch(thud, 1.25))
    dump = load("fs859154")
    debris = load("fs703247")
    loud("metal/crash1", mix((dump, 1.0), (debris, 0.5)))
    loud("metal/crash2", stretch(mix((dump, 0.8), (debris, 0.8)), 1.35))

    # ---- hydraulics: a ram running out (and in again), ending on its stop
    ram = load("fs835133")
    save("hydraulic/extend1", ram)
    save("hydraulic/extend2", stretch(ram, 1.15))
    save("hydraulic/retract1", seg(637811, 0.5, 4.2))
    save("hydraulic/retract2", seg(637811, 27.0, 4.0))

    # ---- compressed air: a valve blowing off; and the cold-launch gas generator's whoomp
    blow = hits(454033, 0.75, 8.0, 3, 1.6, pre=0.05)
    for i, x in enumerate(blow):
        save("air/release%d" % (i + 1), fft_filter(x, low=300.0, slope=1.5), highpass=300.0)
    bursts = hits(751354, 0.5, 1.5, 4, 1.0)
    bursts.sort(key=lambda x: -np.max(np.abs(x)))
    for i, x in enumerate(bursts[:2]):
        # a far bigger volume of gas: slowed right down, with the thump of the piston under it
        whoomp = stretch(x, 2.6)
        body = fft_filter(stretch(thud, 2.0), high=400.0, slope=1.5)
        loud("air/launch%d" % (i + 1), mix((whoomp, 1.0), (body, 0.9)), highpass=30.0)

    # ---- heavy steel doors and hatches
    save("door/heavy_open1", seg(383830, 0.7, 3.6))
    save("door/heavy_open2", stretch(seg(383830, 0.7, 3.6), 1.3))
    for i, x in enumerate(hits(426623, 0.6, 2.0, 2, 1.6)):
        loud("door/heavy_close%d" % (i + 1), x)

    # ---- chains, the truck, the bunker plant
    chain = load("fs798148")
    for i, o in enumerate(peaks(chain, rel=0.3, gap=2.0)[:2]):
        save("chain/rattle%d" % (i + 1), cut(chain, o, 1.4))
    save("truck/diesel", fft_filter(seg(187564, 2.0, 3.5), low=30.0), highpass=30.0)
    save("bunker/generator", loop(seg(606933, 10.0, 4.25)), highpass=30.0)
    save("bunker/vent", loop(seg(835642, 5.0, 4.25)), highpass=40.0)

    # ---- handling: RPG grenade going into the tube, a knock on it; magazines; gear; a drone
    rpg = load("fs725401")
    save("rpg/load", cut(rpg, int(5.0 * SR), 2.8, pre=0.0))
    knock = cut(rpg, int(3.55 * SR), 0.45, pre=0.0)
    save("rpg/knock1", knock)
    save("rpg/knock2", stretch(knock, 1.35))
    mags = [load("fs444402")] + hits(123010, 0.35, 0.8, 4, 0.5)
    for i, x in enumerate(mags):
        save("mag/drop%d" % (i + 1), x, highpass=80.0)
    for i, x in enumerate(hits(427864, 0.3, 1.2, 4, 1.1)):
        save("gear/rustle%d" % (i + 1), x, highpass=80.0)
    save("drone/place", load("fs637747"), highpass=60.0)

    # ---- radio: the key-up tone and the squelch tail
    save("radio/click", load("fs701314"), highpass=200.0)
    save("radio/squelch1", load("fs524205"), highpass=200.0)
    save("radio/squelch2", load("fs760245"), highpass=200.0)

    # ---- flares: the cartridge popping out, then the burning hiss
    sizzle = seg(348767, 1.0, 3.0)
    for i, x in enumerate(hits(675636, 0.6, 2.5, 3, 0.6)):
        y = mix((x, 1.0), (np.concatenate([np.zeros(int(0.12 * SR)), sizzle]), 0.45))
        loud("flare/launch%d" % (i + 1), y)


if __name__ == "__main__":
    main()
