#!/usr/bin/env python3
"""
Sounds for HANGEKI // Reversal Edge, synthesised from scratch with the DSP toolkit in build_sounds.py and merged into
sounds.json under "hangeki.*" (build_sounds.py keeps those entries when it rewrites the file). Nothing is sampled.

  hangeki_warn     the threat meter's alarm pip: a tight two-tone sting over a sub tick (played faster as it closes)
  hangeki_slow     time slowing: a deep downward suction whoosh, a sub drop and two slow heartbeats
  hangeki_stance   the parry stance: a long katana-draw "shing" (bright struck-steel modes over a scrape), a shimmer
  hangeki_impact   THE hit: a reverse-suck into a huge sub slam, a cracked white transient, a heavy struck-plate clang,
                   distorted anime "DOOM" bass, glitch grit and a long dark hall tail
  hangeki_reflect  the thing flying back: a doppler zip rising to a sonic crack with electric crackle
  hangeki_resume   time snapping back: a rising reverse whoosh into a tick
  hangeki_counter  the melee counter: a sharp blade clang on a mid boom and a short sub
  hangeki_guard    raising the guard with nothing to parry: a small steel tick and a breath of air
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_sounds as B  # noqa: E402

SR = B.SR
rng = np.random.default_rng(4419)


def bandnoise(dur, lo, hi, order=2):
    return B.filt(B.white(dur), "bandpass", [lo, hi], order)


def env_curve(dur, pts):
    """Piecewise-linear envelope from (time, level) points."""
    t = B.t_axis(dur)
    xs, ys = zip(*pts)
    return np.interp(t, xs, ys)


def crush(x, bits=5, hold=6):
    """Bit-crush and sample-hold: digital grit for the impact frames."""
    q = 2 ** bits
    y = np.round(x * q) / q
    y = np.repeat(y[::hold], hold)[: len(x)]
    return y


def steel(base, dur, tau, bright=1.0, ratios=(1.0, 2.76, 5.40, 8.93, 13.34, 18.64)):
    """A long struck blade: free-free bar modes (very inharmonic), slow decay, bright."""
    t = B.t_axis(dur)
    x = np.zeros_like(t)
    for i, r in enumerate(ratios):
        f = base * r
        if f > SR / 2 - 500:
            continue
        beat = 1 + 0.002 * np.sin(2 * np.pi * (0.7 + i) * t)
        x += (bright ** i) / (1 + i * 0.6) * np.sin(2 * np.pi * f * beat * t + rng.random() * 6.28) * np.exp(-t / (tau / (1 + 0.35 * i)))
    return x


def plate(base, dur, tau):
    """A heavy struck plate: dense inharmonic low modes (the body of the clang)."""
    freqs = [base * r for r in (1.0, 1.42, 1.87, 2.31, 2.83, 3.41, 4.05, 4.71, 5.62, 6.67)]
    taus = [tau / (1 + 0.28 * i) for i in range(len(freqs))]
    amps = [1.0 / (1 + 0.45 * i) for i in range(len(freqs))]
    return B.modal(freqs, taus, amps, dur, strike=0.003)


def whoosh(dur, f0, f1, q=2.2, shape=None):
    x = B.sweep(B.white(dur), "bandpass", f0, f1, blocks=200, order=2, q=q)
    if shape is not None:
        x *= shape
    return x


# ----------------------------------------------------------------------------------------------------------- sounds

def hangeki_warn():
    d = 0.32
    out = B.canvas(d)
    for at, f in ((0.0, 1760), (0.09, 2349)):
        tone = B.osc(f, 0.07, "tri") * B.decay(0.07, 0.03, 0.001)
        tone += B.osc(f * 2.01, 0.07) * B.decay(0.07, 0.015) * 0.3
        B.place(out, tone, at, 0.55)
    B.place(out, B.kick(160, 60, 0.12, 0.04, 0.2), 0.0, 0.7)
    return B.room(out, 0.4, 0.15)


def hangeki_slow():
    d = 2.2
    shape = env_curve(d, [(0, 0), (0.12, 1), (1.1, 0.7), (2.2, 0)])
    suck = whoosh(d, 5200, 140, 1.8, shape) * 0.9
    air = B.filt(B.white(d), "lowpass", 700) * env_curve(d, [(0, 0), (0.3, 0.6), (2.2, 0)]) * 0.5
    sub = B.osc(B.glide(90, 24, d, 0.6), d) * env_curve(d, [(0, 0), (0.1, 1), (1.6, 0.6), (2.2, 0)])
    out = B.mix(suck, air, sub * 0.9)
    for at in (0.55, 1.35):
        beat = B.kick(70, 30, 0.35, 0.1, 0.05)
        B.place(out, beat, at, 0.9)
        B.place(out, beat, at + 0.16, 0.6)
    # a stretched, falling metallic tone: the world's sound smearing out
    smear = B.unison(B.glide(660, 110, d, 0.7), d, 5, 0.02, "saw")
    smear = B.filt(smear, "lowpass", 1800) * env_curve(d, [(0, 0), (0.2, 0.25), (2.2, 0)])
    out = B.mix(out, smear)
    return B.room(B.mband_sat(out, (2.0, 1.4, 1.1)), 2.4, 0.35, 3500)


def hangeki_stance():
    d = 1.9
    scrape = bandnoise(0.5, 2500, 9000) * env_curve(0.5, [(0, 0), (0.05, 0.6), (0.38, 0.9), (0.5, 0)])
    scrape *= 1 + 0.6 * np.sin(2 * np.pi * 47 * B.t_axis(0.5))
    ring = B.mix(steel(1180, d, 1.6, 0.9) * 0.9, steel(1630, d, 1.1, 0.85) * 0.4)
    shimmer = B.unison(B.glide(3520, 3700, d), d, 7, 0.01, "sine") * env_curve(d, [(0, 0), (0.45, 0.25), (1.9, 0)])
    low = B.osc(B.glide(55, 48, d), d) * env_curve(d, [(0, 0), (0.4, 0.5), (1.9, 0)])
    out = B.canvas(d)
    B.place(out, scrape, 0.0, 0.7)
    B.place(out, ring, 0.42, 0.8)
    B.place(out, shimmer, 0.0, 1.0)
    B.place(out, low, 0.0, 0.8)
    B.place(out, B.kick(220, 70, 0.2, 0.05, 0.6), 0.42, 0.55)
    return B.room(out, 2.0, 0.32, 6000)


def hangeki_impact():
    d = 4.0
    out = B.canvas(d)
    pre = 0.22
    # reverse suck into the hit
    rev = whoosh(pre, 300, 7000, 1.6) * np.linspace(0, 1, B.n_of(pre)) ** 2.5
    B.place(out, rev, 0.0, 0.9)
    t0 = pre
    # the slam: a long sub kick and a second, lower body
    B.place(out, B.kick(150, 26, 2.6, 0.9, 1.0), t0, 1.6)
    B.place(out, B.osc(B.glide(62, 31, 2.0, 0.5), 2.0) * B.decay(2.0, 0.8), t0 + 0.01, 1.0)
    # white crack
    crack = B.filt(B.white(0.06), "highpass", 900) * B.decay(0.06, 0.012, 0.0005)
    B.place(out, crack, t0, 1.4)
    # heavy clang: struck plate + blade
    clang = B.mix(plate(176, 2.8, 1.1) * 0.9, steel(820, 2.8, 1.3, 0.8) * 0.55, plate(311, 2.0, 0.6) * 0.4)
    B.place(out, B.sat(clang * 1.5, 1.8), t0, 0.8)
    # distorted anime DOOM: a saturated saw stack dropping
    doom_d = 1.6
    doom = B.unison(B.glide(110, 41, doom_d, 0.4), doom_d, 5, 0.03, "saw")
    doom = B.filt(B.sat(doom * 2.5, 3.0), "lowpass", 900) * B.decay(doom_d, 0.5)
    B.place(out, doom, t0 + 0.005, 0.8)
    # glitch grit (impact frames)
    grit = crush(bandnoise(0.45, 400, 6000) * B.decay(0.45, 0.12), 4, 9)
    B.place(out, grit, t0 + 0.02, 0.45)
    for k in range(4):
        tick = B.filt(B.white(0.02), "highpass", 3000) * B.decay(0.02, 0.004)
        B.place(out, tick, t0 + 0.06 + k * 0.07, 0.5)
    # rumble tail
    rumble = B.filt(B.brown(d - t0), "lowpass", 220) * env_curve(d - t0, [(0, 0), (0.05, 1), (1.5, 0.5), (d - t0, 0)])
    B.place(out, rumble, t0, 0.7)
    out = B.mband_sat(out, (3.0, 1.8, 1.3))
    return B.room(out, 3.2, 0.42, 3000, 0.045)


def hangeki_reflect():
    d = 1.3
    f = B.glide(380, 2600, d, 2.2)
    zip_ = B.unison(f, d, 4, 0.015, "saw")
    zip_ = B.filt(zip_, "lowpass", 5000) * env_curve(d, [(0, 0), (0.1, 0.6), (0.85, 1.0), (1.05, 0.2), (1.3, 0)])
    air = whoosh(d, 600, 7000, 2.0, env_curve(d, [(0, 0), (0.8, 1), (1.3, 0)]))
    cr = B.crackle(d, 160, hp=3000, amp_env=env_curve(d, [(0, 0.2), (0.9, 1), (1.3, 0)]))
    out = B.mix(zip_ * 0.55, air * 0.7, cr * 0.8)
    boom = B.kick(200, 45, 0.5, 0.15, 1.0)
    B.place(out, boom, 0.86, 0.9)
    B.place(out, B.filt(B.white(0.04), "highpass", 1200) * B.decay(0.04, 0.008), 0.86, 0.9)
    return B.room(B.mband_sat(out), 1.6, 0.28)


def hangeki_resume():
    d = 0.75
    rise = whoosh(d, 160, 6000, 1.8, np.linspace(0, 1, B.n_of(d)) ** 2)
    tone = B.osc(B.glide(60, 240, d, 2.0), d) * np.linspace(0, 1, B.n_of(d)) ** 3 * 0.6
    out = B.mix(rise, tone)
    tick = B.filt(B.white(0.03), "highpass", 2500) * B.decay(0.03, 0.005)
    B.place(out, tick, d - 0.04, 0.8)
    return B.room(out, 0.8, 0.2)


def hangeki_counter():
    d = 2.0
    out = B.canvas(d)
    B.place(out, B.kick(180, 40, 1.2, 0.35, 0.9), 0.0, 1.2)
    B.place(out, B.filt(B.white(0.04), "highpass", 1500) * B.decay(0.04, 0.007), 0.0, 1.2)
    clang = B.mix(steel(960, d, 1.0, 0.85), plate(240, 1.4, 0.5) * 0.6)
    B.place(out, B.sat(clang * 1.3, 1.6), 0.0, 0.85)
    B.place(out, whoosh(0.35, 6000, 800, 2.0) * B.decay(0.35, 0.12), 0.0, 0.5)
    return B.room(B.mband_sat(out, (2.5, 1.6, 1.2)), 1.8, 0.33, 4500)


def hangeki_guard():
    d = 0.5
    out = B.canvas(d)
    B.place(out, steel(2100, d, 0.18, 0.7), 0.0, 0.5)
    B.place(out, whoosh(0.25, 900, 4000, 2.0) * B.decay(0.25, 0.08), 0.0, 0.4)
    return B.room(out, 0.5, 0.15)


SOUNDS = {
    "hangeki_warn": hangeki_warn, "hangeki_slow": hangeki_slow, "hangeki_stance": hangeki_stance, "hangeki_impact": hangeki_impact,
    "hangeki_reflect": hangeki_reflect, "hangeki_resume": hangeki_resume, "hangeki_counter": hangeki_counter, "hangeki_guard": hangeki_guard,
}
DIST = {"hangeki_warn": 8, "hangeki_slow": 24, "hangeki_stance": 24, "hangeki_impact": 64, "hangeki_reflect": 48, "hangeki_resume": 16,
        "hangeki_counter": 32, "hangeki_guard": 12}


def main():
    out = B.OUT
    out.mkdir(parents=True, exist_ok=True)
    for name, fn in SOUNDS.items():
        x = B.norm(fn(), 0.95)
        x = B.limit(x, 0.92)
        x = B.fade(B.norm(B.trim(x), 0.9), 0.001, 0.08)
        B.write_ogg(x, out / f"{name}.ogg")
        print(f"{name:16s} {len(x) / SR:5.2f}s")
    sj = out.parent / "sounds.json"
    js = json.loads(sj.read_text())
    for name in SOUNDS:
        event = "hangeki." + name[8:]
        js[event] = {"subtitle": f"subtitles.orbital_railgun.{event}",
                     "sounds": [{"name": f"orbital_railgun:{name}", "attenuation_distance": DIST[name]}]}
    sj.write_text(json.dumps(js, indent=2))


if __name__ == "__main__":
    main()
