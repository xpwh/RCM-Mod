"""9.1 fighter cockpit and ground sounds.

  jet/startup      engine start: the jet fuel starter's whine winding up, light-off (a soft whoomp
                   and the roar coming in), spooling up to idle. Built from the mod's real F-16/F/A-18
                   engine recording, resampled along the spool-up curve (a turbine's pitch IS its rpm).
  jet/shutdown     the same recording winding down to silence
  jet/wind         airflow over the canopy, heard in the cockpit (a loop; pitch/volume follow speed)
  jet/growl        AIM-9 style seeker growl while it searches (a loop)
  jet/lock         the steady high tone once it has locked (a loop)
  jet/warn_stall   stall warning: fast beeps
  jet/warn_pullup  ground proximity warning: a two-tone warble
  jet/tyre1..3     main wheels touching down: the screech of rubber spun up from standstill

The cockpit tones are electronic in the real aircraft too, so they are synthesised.
python3 gen_sounds_v12.py   (writes into the mod's sounds folder)
"""
import os
import subprocess
import tempfile

import numpy as np

import gen_sounds as s
from gen_sounds import env_exp, fade, fft_filter, make_loop, n_samples, normalize, place, t_axis, white

SR = s.SR
rng = np.random.default_rng(912)


def load(path):
    """Decodes an .ogg to mono float samples at SR."""
    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "x.f32")
        subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "f32le", raw], check=True)
        return np.fromfile(raw, dtype=np.float32).astype(np.float64)


def varispeed(src, rate, seconds):
    """Plays `src` (looped) at a time-varying speed: rate[i] is the playback speed at output sample i."""
    n = n_samples(seconds)
    pos = np.cumsum(rate[:n])
    pos = pos % (len(src) - 2)
    i = pos.astype(np.int64)
    frac = pos - i
    return src[i] * (1 - frac) + src[i + 1] * frac


def smooth(x):
    return x * x * (3 - 2 * x)


ENGINE = load(os.path.join(s.OUT, "jet", "fighter.ogg"))


def startup():
    seconds = 9.0
    t = t_axis(seconds)
    # starter: 0-3 s winds to ~20 % rpm; light-off at 3 s; 3-8 s accelerates to idle
    rpm = np.where(t < 3.0, 0.2 * smooth(np.clip(t / 3.0, 0, 1)),
                   0.2 + 0.8 * (1 - np.exp(-(t - 3.0) / 1.6)) * smooth(np.clip((t - 3.0) / 5.0, 0, 1)) ** 0.5)
    rate = 0.18 + 0.82 * rpm
    body = varispeed(ENGINE, rate, seconds)
    # the whine: the high, tonal part of the engine; the roar: the rest, only after light-off
    whine = fft_filter(body, low=1400.0, slope=1.5)
    roar = fft_filter(body, high=2500.0, slope=1.2)
    lit = smooth(np.clip((t - 3.0) / 1.2, 0, 1))
    out = whine * (0.25 + 0.9 * rpm) + roar * lit * (0.3 + 0.7 * rpm)
    # the starter's own turbine: a thin rising tone
    f = 300.0 + 2600.0 * smooth(np.clip(t / 3.5, 0, 1))
    tone = np.sin(2 * np.pi * np.cumsum(f) / SR) * 0.05 * np.clip(t / 0.6, 0, 1) * np.clip((6.0 - t) / 3.0, 0, 1)
    out += tone
    # light-off: a soft low whoomp
    whoomp = fft_filter(white(1.5), high=180.0, slope=1.0) * env_exp(1.5, 0.35, attack=0.08) * 3.0
    place(out, whoomp, 3.0)
    out *= np.clip(t / 0.4, 0, 1)
    return fade(normalize(out, 0.95), 0.01, 0.6)


def shutdown():
    seconds = 7.0
    t = t_axis(seconds)
    rpm = np.exp(-t / 2.2)
    body = varispeed(ENGINE, 0.15 + 0.85 * rpm, seconds)
    out = fft_filter(body, high=6000.0, slope=1.0) * rpm ** 0.7
    return fade(normalize(out, 0.9), 0.02, 0.8)


def wind():
    seconds = 6.0
    t = t_axis(seconds + 1.0)
    x = white(seconds + 1.0)
    # pink-ish broadband hiss with a hollow band where the canopy resonates
    x = fft_filter(x, low=120.0, high=3500.0, slope=1.0)
    x += fft_filter(white(seconds + 1.0), low=600.0, high=900.0, slope=3.0) * 0.6
    x *= 1.0 + 0.25 * np.sin(2 * np.pi * 0.37 * t) + 0.1 * np.sin(2 * np.pi * 1.3 * t + 1.0)
    return normalize(make_loop(x, seconds), 0.9)


def growl():
    """The seeker's audio: a buzzing tone whose roughness wanders as it scans."""
    seconds = 2.0
    t = t_axis(seconds)
    f = 420.0 + 30.0 * np.sin(2 * np.pi * 1.0 * t)
    phase = 2 * np.pi * np.cumsum(f) / SR
    x = np.sign(np.sin(phase)) * 0.4 + np.sin(phase) * 0.6
    x *= 0.7 + 0.3 * np.sign(np.sin(2 * np.pi * 26.0 * t))
    x = fft_filter(x, low=150.0, high=3000.0, slope=1.2)
    return normalize(x, 0.6)


def lock():
    seconds = 1.0
    t = t_axis(seconds)
    x = np.sin(2 * np.pi * 1600.0 * t) + 0.3 * np.sin(2 * np.pi * 3200.0 * t)
    return normalize(x, 0.5)


def beeps(seconds, count, freq, on):
    t = t_axis(seconds)
    out = np.zeros(len(t))
    period = seconds / count
    for k in range(count):
        tt = t - k * period
        gate = ((tt >= 0) & (tt < on)).astype(float)
        edge = np.clip(tt / 0.004, 0, 1) * np.clip((on - tt) / 0.004, 0, 1)
        out += np.sin(2 * np.pi * freq * t) * gate * edge
    return out


def warn_stall():
    return normalize(beeps(0.6, 4, 1250.0, 0.075), 0.6)


def warn_pullup():
    seconds = 0.8
    t = t_axis(seconds)
    f = np.where((t * 8).astype(int) % 2 == 0, 760.0, 1050.0)
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.clip(t / 0.01, 0, 1) * np.clip((seconds - t) / 0.02, 0, 1)
    return normalize(x, 0.6)


def tyre(seed):
    r = np.random.default_rng(seed)
    seconds = 0.9
    t = t_axis(seconds)
    length = r.uniform(0.25, 0.4)
    env = np.clip(t / 0.01, 0, 1) * np.exp(-np.maximum(0, t - 0.03) / length)
    # rubber dragged over concrete: a rough, squealing band with a wandering pitch
    f = r.uniform(1100, 1500) * (1.0 - 0.25 * np.clip(t / length, 0, 1)) * (1 + 0.03 * np.sin(2 * np.pi * 37 * t))
    squeal = np.sin(2 * np.pi * np.cumsum(f) / SR) * (0.5 + 0.5 * r.random(len(t)))
    noise = fft_filter(white(seconds), low=700.0, high=5000.0, slope=1.5)
    x = (squeal * 0.5 + noise) * env
    thump = fft_filter(white(0.4), high=120.0, slope=1.0) * env_exp(0.4, 0.08) * 3.0
    place(x, thump, 0.0)
    return fade(normalize(x, 0.95), 0.002, 0.15)


def main():
    out = {
        "jet/startup": startup(),
        "jet/shutdown": shutdown(),
        "jet/wind": wind(),
        "jet/growl": growl(),
        "jet/lock": lock(),
        "jet/warn_stall": warn_stall(),
        "jet/warn_pullup": warn_pullup(),
        "jet/tyre1": tyre(1),
        "jet/tyre2": tyre(2),
        "jet/tyre3": tyre(3),
    }
    for name, x in out.items():
        s.save(name, x)
        print(name, round(len(x) / SR, 2), "s")


if __name__ == "__main__":
    main()
