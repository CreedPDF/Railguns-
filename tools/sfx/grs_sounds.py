#!/usr/bin/env python3
"""
Synthesizes the Gungnir Railway Station (GRS) sounds from scratch - no samples, nothing from Minecraft, nothing from
the railgun's sound set - and encodes them to mono Ogg Vorbis. Adds their events to sounds.json (build_sounds.py keeps
them when it rewrites the file).

  grs_whoosh    the spear passing at light speed on its lap: doppler sweep, shimmer, air tear, sub push
  grs_goggles   the goggles' display booting
  grs_lock      target locked
  grs_yield     yield selector tick (pitched by the game)
  grs_denied    uplink refused
  grs_release   the spear breaking from its lap: accelerating passes spiralling in, the barrel charging
  grs_descent   the sky tearing open
  grs_impact    the strike
  grs_plant     the spear ringing in the ground, a struck colossal bell of metal
  grs_hum       the planted spear's resonance (loop)
  grs_recall    dissolving into light and rushing back up to orbit

Usage:  python3 tools/sfx/grs_sounds.py      (numpy, scipy, ffmpeg with libvorbis)
"""
import json
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np
from scipy import signal

SR = 44100
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/assets/orbital_railgun/sounds"
R = np.random.default_rng(1414)


def N(d):
    return max(1, int(d * SR))


def T(d):
    return np.arange(N(d)) / SR


def noise(d):
    return R.standard_normal(N(d))


def bp(x, lo, hi, o=3):
    return signal.sosfilt(signal.butter(o, [max(15, lo), min(hi, SR / 2 - 300)], "bandpass", fs=SR, output="sos"), x)


def lp(x, f, o=3):
    return signal.sosfilt(signal.butter(o, min(f, SR / 2 - 300), "low", fs=SR, output="sos"), x)


def hp(x, f, o=3):
    return signal.sosfilt(signal.butter(o, f, "high", fs=SR, output="sos"), x)


def moving_bp(x, f_path, q=2.0, blocks=300):
    """Band-pass whose centre follows f_path (array, Hz) - block-wise with carried state."""
    out = np.zeros_like(x)
    edges = np.linspace(0, len(x), blocks + 1).astype(int)
    zi = None
    for i in range(blocks):
        a, b = edges[i], edges[i + 1]
        if b <= a:
            continue
        f = float(np.mean(f_path[a:b]))
        sos = signal.butter(2, [max(20, f / q), min(f * q, SR / 2 - 300)], "bandpass", fs=SR, output="sos")
        if zi is None or zi.shape != (sos.shape[0], 2):
            zi = signal.sosfilt_zi(sos) * 0
        out[a:b], zi = signal.sosfilt(sos, x[a:b], zi=zi)
    return out


def env(d, a, h, rel, curve=2.0):
    t = T(d)
    e = np.ones_like(t)
    e[t < a] = (t[t < a] / max(a, 1e-4)) ** curve
    k = t > a + h
    e[k] = np.exp(-(t[k] - a - h) / max(rel, 1e-4))
    return e


def bell(d, center, width):
    t = T(d)
    return np.exp(-((t - center) / width) ** 2)


def osc(freq_path, shape="sine"):
    ph = 2 * np.pi * np.cumsum(freq_path) / SR
    if shape == "saw":
        return 2 * ((ph / (2 * np.pi)) % 1.0) - 1
    if shape == "tri":
        return 2 * np.abs(2 * ((ph / (2 * np.pi)) % 1.0) - 1) - 1
    return np.sin(ph)


def modal(d, freqs, decays, amps):
    t = T(d)
    out = np.zeros_like(t)
    for f, dc, a in zip(freqs, decays, amps):
        out += a * np.sin(2 * np.pi * f * t + R.uniform(0, 6.28)) * np.exp(-t / dc)
    return out


def sat(x, k=1.6):
    return np.tanh(x * k) / np.tanh(k)


def room(x, size=1.0, mix=0.35):
    """A small synthetic space: a few early taps and a damped noise tail."""
    taps = [(0.011, 0.5), (0.023, 0.38), (0.041, 0.3), (0.067, 0.22)]
    y = x.copy()
    for dt, g in taps:
        k = int(dt * size * SR)
        y[k:] += x[:-k] * g
    tail_d = 1.8 * size
    ir = noise(tail_d) * np.exp(-T(tail_d) / (0.45 * size))
    ir = lp(ir, 5000)
    wet = signal.fftconvolve(x, ir)[: len(x)]
    wet /= np.abs(wet).max() + 1e-9
    return y * (1 - mix) + wet * mix * np.abs(x).max()


def pad(x, d):
    out = np.zeros(N(d))
    out[: min(len(x), len(out))] = x[: len(out)]
    return out


def place(dst, src, at):
    a = int(at * SR)
    b = min(len(dst), a + len(src))
    dst[a:b] += src[: b - a]


def norm(x, p=0.9):
    m = np.abs(x).max()
    return x * (p / m) if m > 1e-9 else x


def fade(x, fin=0.003, fout=0.08):
    x = x.copy()
    a, b = N(fin), min(len(x), N(fout))
    x[:a] *= np.linspace(0, 1, a)
    x[-b:] *= np.linspace(1, 0, b)
    return x


# ------------------------------------------------------------------------------------------------ the sounds

def whoosh():
    d = 1.7
    t = T(d)
    c = 0.62  # moment of closest approach
    # doppler: pitch falls through the pass (a light-speed body is mostly heard as the air it tears)
    fpath = 1400 * np.exp(-(t - c) * 2.4) / (1 + np.exp((t - c) * 9)) + 260
    air = moving_bp(noise(d), fpath * 2.2, 1.7) * bell(d, c, 0.2)
    air2 = moving_bp(noise(d), fpath * 0.9, 2.4) * bell(d, c + 0.04, 0.28)
    zing = osc(fpath * 1.5, "saw") * 0.25 + osc(fpath * 2.01) * 0.3
    zing = lp(zing, 6000) * bell(d, c, 0.09) ** 1.5
    shimmer = sum(osc(fpath * k) for k in (3.0, 4.03, 5.11)) * 0.06 * bell(d, c - 0.02, 0.06)
    sub = np.sin(2 * np.pi * np.cumsum(55 + 35 * np.exp(-(t - c) * 6).clip(0, 1)) / SR) * bell(d, c + 0.05, 0.14) * 0.8
    crack = hp(noise(d), 2500) * np.exp(-np.abs(t - c) / 0.012) * 0.6
    x = air * 1.0 + air2 * 0.8 + zing + shimmer + sub + crack
    return fade(room(sat(norm(x), 1.3), 0.8, 0.25))


def goggles():
    d = 1.0
    t = T(d)
    x = np.zeros(N(d))
    for k, (at, f) in enumerate(((0.0, 620), (0.09, 930), (0.18, 1240), (0.3, 1860))):
        tt = T(0.14)
        b = np.sin(2 * np.pi * f * tt + 0.3 * np.sin(2 * np.pi * 37 * tt)) * np.exp(-tt / 0.05)
        place(x, b * (0.6 + 0.1 * k), at)
    hum = osc(np.linspace(90, 180, N(d)), "saw")
    hum = lp(hum, 1600) * env(d, 0.25, 0.2, 0.18) * 0.25
    click = bp(noise(0.02), 2000, 9000) * np.exp(-T(0.02) / 0.004)
    place(x, click * 0.8, 0.42)
    return fade(room(norm(x + hum), 0.4, 0.2))


def lock():
    d = 1.0
    x = np.zeros(N(d))
    for k, f in enumerate((1980, 1760, 1480)):
        tt = T(0.09)
        place(x, np.sign(np.sin(2 * np.pi * f * tt)) * 0.25 * np.exp(-tt / 0.05) + np.sin(2 * np.pi * f * 2 * tt) * 0.2, k * 0.11)
    clunk = modal(0.5, [180, 410, 655, 1210], [0.12, 0.08, 0.05, 0.03], [1, 0.6, 0.4, 0.25])
    clunk += lp(noise(0.5), 900) * np.exp(-T(0.5) / 0.03) * 0.8
    place(x, clunk * 0.9, 0.36)
    return fade(room(norm(x), 0.5, 0.2))


def yield_tick():
    d = 0.14
    t = T(d)
    x = np.sin(2 * np.pi * 1320 * t) * np.exp(-t / 0.025) + np.sin(2 * np.pi * 2640 * t) * np.exp(-t / 0.012) * 0.4
    x += bp(noise(d), 3000, 9000) * np.exp(-t / 0.004) * 0.5
    return fade(norm(x), 0.0005, 0.03)


def denied():
    d = 0.6
    x = np.zeros(N(d))
    for at in (0.0, 0.22):
        tt = T(0.17)
        b = osc(np.full(N(0.17), 140.0), "saw") + osc(np.full(N(0.17), 147.0), "saw")
        place(x, lp(b, 2200) * env(0.17, 0.005, 0.12, 0.02), at)
    return fade(norm(sat(x, 2.0)))


def release():
    d = 5.0
    t = T(d)
    x = np.zeros(N(d))
    # passes spiralling inward, each faster and higher
    at, gap = 0.0, 0.7
    k = 0
    while at < 3.3:
        w = whoosh()
        fac = 1 + k * 0.12
        idx = np.clip((np.arange(int(len(w) / fac)) * fac).astype(int), 0, len(w) - 1)
        place(x, w[idx] * (0.45 + 0.07 * k), at)
        at += gap
        gap = max(0.14, gap * 0.78)
        k += 1
    # the barrel charging: a rising detuned stack
    f = 70 * np.exp(t * 0.55)
    stack = sum(osc(f * m * (1 + 0.004 * j), "saw") for j, m in enumerate((1, 1.5, 2, 3)))
    stack = moving_bp(stack, np.minimum(f * 6, 9000), 2.5) * env(d, 3.2, 0.9, 0.5, 1.5) * 0.5
    crackle = hp(noise(d), 3000) * (R.random(N(d)) > 0.9985) * 8.0
    crackle = lp(crackle, 9000) * env(d, 2.0, 2.0, 0.5)
    x += stack + crackle * 0.3
    return fade(room(sat(norm(x), 1.4), 1.0, 0.3), 0.002, 0.4)


def descent():
    d = 2.6
    t = T(d)
    # the air tearing open from the top of the sky to the ground, faster than sound can follow
    f = 9000 * np.exp(-t * 2.2) + 120
    tear = moving_bp(noise(d), f, 1.6) * env(d, 0.05, 1.6, 0.4, 1.0)
    roar = lp(noise(d), 380) * env(d, 0.5, 1.4, 0.5, 1.5) * 1.5
    zap = osc(f * 0.25, "saw") * env(d, 0.02, 1.2, 0.5) * 0.25
    crack = hp(noise(d), 1800) * np.exp(-np.abs(t - 2.25) / 0.02) * 1.5
    x = tear + roar + lp(zap, 7000) + crack
    return fade(room(sat(norm(x), 1.8), 1.2, 0.3))


def impact():
    d = 8.0
    t = T(d)
    # sub kick: a pitch-dropping sine for the weight of 179 blocks of metal arriving
    kick = np.sin(2 * np.pi * np.cumsum(28 + 140 * np.exp(-t / 0.08)) / SR) * np.exp(-t / 0.9)
    crack = hp(noise(d), 1200) * np.exp(-t / 0.035) * 1.4
    body = lp(noise(d), 900) * np.exp(-t / 0.6) * 1.2
    rumble = lp(noise(d), 160) * env(d, 0.08, 0.5, 2.6, 1.0) * 1.6
    debris = np.zeros(N(d))
    for _ in range(140):
        at = 0.25 + R.exponential(1.1)
        if at > 6.5:
            continue
        dd = R.uniform(0.02, 0.08)
        g = bp(noise(dd), R.uniform(300, 2500), R.uniform(3000, 7000)) * np.exp(-T(dd) / (dd / 3)) * R.uniform(0.1, 0.4) * np.exp(-at / 2.2)
        place(debris, g, at)
    metal = modal(d, [43, 97, 151, 233, 377], [3.0, 2.2, 1.6, 1.1, 0.8], [0.5, 0.35, 0.25, 0.18, 0.12])
    x = kick * 1.6 + crack + body + rumble + debris + metal * 0.5
    return fade(room(sat(norm(x), 2.2), 2.0, 0.35), 0.0005, 0.8)


def plant():
    d = 6.0
    t = T(d)
    base = 38.0
    ratios = [1.0, 2.31, 3.1, 4.79, 6.2, 7.9, 10.3]
    bellx = modal(d, [base * r for r in ratios], [4.0, 3.0, 2.4, 1.7, 1.2, 0.9, 0.6], [1, 0.7, 0.55, 0.35, 0.25, 0.18, 0.12])
    beat = 1 + 0.25 * np.sin(2 * np.pi * 1.6 * t)
    strike = lp(noise(d), 2500) * np.exp(-t / 0.05) * 0.7
    grind = moving_bp(noise(d), 220 + 120 * np.exp(-t), 3.0) * env(d, 0.02, 0.4, 0.9) * 0.4
    x = bellx * beat + strike + grind
    return fade(room(sat(norm(x), 1.3), 2.2, 0.4), 0.0005, 1.0)


def hum():
    d = 4.0
    t = T(d)
    # a seamless loop: every component is periodic over the loop length
    def tone(f):
        f = round(f * d) / d
        return np.sin(2 * np.pi * f * t)
    x = tone(55) * 0.6 + tone(55.5) * 0.5 + tone(110.25) * 0.3 + tone(165) * 0.15 + tone(331) * 0.06
    x *= 1 + 0.2 * tone(0.5)
    crackle = bp(noise(d), 2500, 8000) * (R.random(N(d)) > 0.998) * 3
    x = x + crackle * 0.15
    # crossfade the ends so the loop is seamless
    k = N(0.1)
    x[:k] = x[:k] * np.linspace(0, 1, k) + x[-k:] * np.linspace(1, 0, k)
    return norm(x, 0.7)


def recall():
    d = 3.4
    t = T(d)
    swell = moving_bp(noise(d), 200 * np.exp(t * 1.2), 2.0) * env(d, 2.2, 0.3, 0.25, 2.5)
    rise = osc(80 * np.exp(t * 1.25), "saw")
    rise = lp(rise, 8000) * env(d, 2.4, 0.4, 0.2, 2.0) * 0.5
    zip_ = moving_bp(noise(d), 600 * np.exp((t - 2.6).clip(0) * 4), 1.8) * bell(d, 2.85, 0.15) * 1.4
    shimmer = sum(np.sin(2 * np.pi * f * t) for f in (1320, 1980, 2970)) * 0.05 * env(d, 1.5, 1.0, 0.4)
    x = swell + rise + zip_ + shimmer
    return fade(room(sat(norm(x), 1.4), 1.4, 0.3))


SOUNDS = {"grs_whoosh": whoosh, "grs_goggles": goggles, "grs_lock": lock, "grs_yield": yield_tick, "grs_denied": denied,
          "grs_release": release, "grs_descent": descent, "grs_impact": impact, "grs_plant": plant, "grs_hum": hum, "grs_recall": recall}
EVENTS = {
    "grs.whoosh": ("grs_whoosh", {}),
    "grs.goggles": ("grs_goggles", {}),
    "grs.lock": ("grs_lock", {}),
    "grs.yield": ("grs_yield", {}),
    "grs.denied": ("grs_denied", {}),
    "grs.release": ("grs_release", {"attenuation_distance": 256}),
    "grs.descent": ("grs_descent", {"attenuation_distance": 512}),
    "grs.impact": ("grs_impact", {"attenuation_distance": 512}),
    "grs.plant": ("grs_plant", {"attenuation_distance": 320}),
    "grs.hum": ("grs_hum", {"attenuation_distance": 64}),
    "grs.recall": ("grs_recall", {"attenuation_distance": 320}),
}


def write_ogg(x, path):
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        with wave.open(tmp.name, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-c:a", "libvorbis", "-q:a", "6", "-ac", "1", str(path)], check=True)
    Path(tmp.name).unlink()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in SOUNDS.items():
        x = fn()
        x = norm(x, 0.92 if name != "grs_hum" else 0.7)
        write_ogg(x, OUT / f"{name}.ogg")
        print(f"{name:14s} {len(x) / SR:5.2f}s")
    sj = OUT.parent / "sounds.json"
    js = json.loads(sj.read_text()) if sj.exists() else {}
    for ev, (file, props) in EVENTS.items():
        js[ev] = {"subtitle": f"subtitles.orbital_railgun.{ev}", "sounds": [{"name": f"orbital_railgun:{file}", **props}]}
    sj.write_text(json.dumps(js, indent=2))


if __name__ == "__main__":
    main()
