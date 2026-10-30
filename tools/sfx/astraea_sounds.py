#!/usr/bin/env python3
"""
Sounds for ASTRAEA // The Worldsplitter, synthesised from scratch with the DSP toolkit in build_sounds.py (nothing
sampled, nothing reused) and merged into sounds.json under "astraea.*" (build_sounds.py keeps those entries).

  astraea_aura       the lance's aura (loop): faint electric crackle, a high glassy shimmer, a soft hum
  astraea_stance     raising the lance: a bright metallic draw, an upward rush of air, a shimmer
  astraea_rift       the rift tearing open: a descending groan, granular tearing that thickens, crystalline swells
  astraea_pulse      one pulse of the star: a soft bell cluster with a slow tremolo (played louder and faster)
  astraea_descend    the light coming down: a rising roar, a sustained choir-like chord, a building sub swell
  astraea_absorb     the energy taken in: a reverse swell into a heavy bass drop with a sparkling tail
  astraea_ascension  the Ascension impact: a struck bell, a sub slam, a bright chord stab and a long tail
  astraea_throw      the throw: a huge launch whoosh, a sonic crack, the lance's rising whine
  astraea_pass       one orbital pass, a doppler "zoom" (the client plays it faster and higher as it speeds up)
  astraea_drill      the lance drilling into Earth: grinding roar, rock shriek, sub rumble
  astraea_split      Earth splitting: a supersonic crack, a colossal sub-bass blast, rock tearing, rolling thunder
                     echoing for seconds, an air-pressure rush and falling debris
  astraea_quake      aftershock rumble (loop)
  astraea_tear       the ground tearing open nearby: a crack, a long grinding slide, a low collapse
  astraea_return     the lance falling back to its bearer: a descending whistle and a soft chime on the catch
"""
import json
import sys
from pathlib import Path

import numpy as np
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_sounds as B  # noqa: E402

SR = B.SR
rng = np.random.default_rng(31337)


def env_curve(dur, pts):
    t = B.t_axis(dur)
    xs, ys = zip(*pts)
    return np.interp(t, xs, ys)


def bandnoise(dur, lo, hi, order=2):
    return B.filt(B.white(dur), "bandpass", [lo, hi], order)


def bell(f, dur, tau, bright=1.0):
    """A struck bell: the minor-third bell partial series, each decaying at its own rate."""
    ratios = [0.5, 1.0, 1.2, 1.5, 2.0, 2.5, 3.0, 4.2, 5.4]
    t = B.t_axis(dur)
    x = np.zeros_like(t)
    for i, r in enumerate(ratios):
        x += (bright ** i) / (1 + 0.5 * i) * np.sin(2 * np.pi * f * r * t + rng.random() * 6.28) * np.exp(-t / (tau / (1 + 0.25 * i)))
    return x


def choir(freqs, dur, vib=0.004):
    """A sustained choir-like chord: detuned saw stacks through vowel formants ("aah")."""
    t = B.t_axis(dur)
    x = np.zeros_like(t)
    for f in freqs:
        fv = f * (1 + vib * np.sin(2 * np.pi * (4.6 + rng.random()) * t))
        x += B.unison(fv, dur, 5, 0.008, "saw")
    out = np.zeros_like(x)
    for fc, g in ((730, 1.0), (1090, 0.6), (2440, 0.3)):
        out += B.filt(x, "bandpass", [fc * 0.85, fc * 1.15], 2) * g
    return out


def crackle_dense(dur, rate_env, lo=300, hi=4000, tau=0.004):
    """Rock fracturing: a Poisson storm of short band-limited bursts whose rate follows rate_env (0..1)."""
    out = B.canvas(dur)
    n = int(rate_env.mean() * dur * 320)
    times = []
    cdf = np.cumsum(rate_env)
    cdf /= cdf[-1]
    for u in rng.random(n):
        times.append(np.searchsorted(cdf, u) / SR)
    for at in times:
        L = tau * (1 + rng.random() * 4)
        burst = B.white(L * 6) * B.decay(L * 6, L)
        B.place(out, burst, at, rng.random() ** 1.5)
    # loudness follows the rate too, so a thinning storm also fades
    return B.filt(out, "bandpass", [lo, hi], 2) * np.sqrt(np.clip(rate_env, 0, 1))


def rumble(dur, lo=20, hi=140, mod=0.6, rate=2.5):
    t = B.t_axis(dur)
    x = B.filt(B.brown(dur), "bandpass", [lo, hi], 2)
    am = 1 + mod * B.filt(rng.standard_normal(len(t)), "lowpass", rate, 2) * 6
    return x * np.clip(am, 0.1, 3)


# ---------------------------------------------------------------------------------------------- the lance

def astraea_aura():
    d = 4.0
    t = B.t_axis(d)
    shimmer = sum(np.sin(2 * np.pi * f * t + rng.random() * 6) * (0.3 + 0.2 * np.sin(2 * np.pi * (0.2 + 0.1 * k) * t))
                  for k, f in enumerate((2093, 2637, 3136, 4186)))
    hum = np.sin(2 * np.pi * 110 * t) * 0.35 + np.sin(2 * np.pi * 220.7 * t) * 0.12
    cr = B.crackle(d, 26, tau=0.0012, hp=3000)
    x = shimmer * 0.12 + hum * 0.25 + cr * 0.9
    return B.make_loop(B.room(x, 0.8, 0.25)[:B.n_of(d + 0.3)], 0.3)


def astraea_stance():
    d = 2.6
    out = B.canvas(d)
    draw = bandnoise(0.6, 2500, 9000) * env_curve(0.6, [(0, 0), (0.08, 0.7), (0.5, 0.9), (0.6, 0)])
    B.place(out, draw, 0.0, 0.6)
    B.place(out, bell(1320, 2.2, 1.6, 0.85), 0.5, 0.45)
    rush = B.sweep(B.white(1.4), "bandpass", 300, 5000, q=1.8) * env_curve(1.4, [(0, 0), (1.0, 1), (1.4, 0)])
    B.place(out, rush, 0.0, 0.6)
    B.place(out, B.kick(140, 50, 0.6, 0.2, 0.3), 0.55, 0.6)
    return B.room(out, 2.0, 0.35, 6000)


# ---------------------------------------------------------------------------------------------- the ritual

def astraea_rift():
    d = 6.5
    groan = B.osc(B.glide(140, 38, d, 0.7), d, "saw")
    groan = B.filt(groan, "lowpass", 400) * env_curve(d, [(0, 0), (0.8, 0.8), (5.5, 0.6), (6.5, 0)])
    sub = B.osc(B.glide(55, 28, d, 0.5), d) * env_curve(d, [(0, 0), (1.5, 1), (6.5, 0)])
    tear = crackle_dense(d, env_curve(d, [(0, 0.05), (1.0, 0.4), (3.5, 1.0), (5.5, 0.6), (6.5, 0.0)]), 500, 6000)
    tearlow = crackle_dense(d, env_curve(d, [(0, 0.1), (2.5, 0.8), (6.5, 0.2)]), 80, 700, 0.01)
    t = B.t_axis(d)
    glass = sum(np.sin(2 * np.pi * f * t) * np.exp(-((t - c) / 0.9) ** 2)
                for f, c in ((2960, 1.6), (3520, 2.6), (4434, 3.4), (5274, 4.3)))
    x = B.mix(groan * 0.5, sub * 0.8, tear * 1.4, tearlow * 0.8, glass * 0.12)
    return B.room(B.mband_sat(x, (2.2, 1.5, 1.2)), 3.0, 0.4, 4000)


def astraea_pulse():
    d = 2.2
    t = B.t_axis(d)
    tone = sum(np.sin(2 * np.pi * f * t) * a for f, a in ((880, 0.5), (1318.5, 0.3), (1760, 0.25), (2637, 0.12)))
    trem = 0.6 + 0.4 * np.sin(2 * np.pi * 3.2 * t)
    x = tone * trem * env_curve(d, [(0, 0), (0.25, 1), (1.2, 0.5), (2.2, 0)])
    x += bell(440, d, 1.2, 0.7) * 0.25
    return B.room(x, 2.4, 0.45, 7000)


def astraea_descend():
    d = 4.5
    roar = B.sweep(B.white(d), "bandpass", 120, 4200, q=1.5) * env_curve(d, [(0, 0), (3.8, 1), (4.5, 0.6)])
    ch = choir([220, 277.2, 329.6, 440], d) * env_curve(d, [(0, 0), (1.5, 0.6), (4.2, 1), (4.5, 0.7)])
    sub = B.osc(B.glide(30, 62, d, 1.5), d) * env_curve(d, [(0, 0), (4.3, 1), (4.5, 0.8)])
    x = B.mix(roar * 0.7, ch * 0.35, sub * 0.9)
    return B.room(B.mband_sat(x), 3.0, 0.4, 5000)


def astraea_absorb():
    d = 4.5
    pre = 1.3
    rev = B.sweep(B.white(pre), "bandpass", 400, 8000, q=1.6) * np.linspace(0, 1, B.n_of(pre)) ** 3
    out = B.canvas(d)
    B.place(out, rev, 0, 0.9)
    B.place(out, B.kick(110, 22, 3.0, 1.1, 0.4), pre, 1.5)
    drop = B.unison(B.glide(90, 30, 2.0, 0.4), 2.0, 5, 0.03, "saw")
    B.place(out, B.filt(B.sat(drop * 2, 2.5), "lowpass", 600) * B.decay(2.0, 0.7), pre, 0.7)
    sparkle = B.crackle(2.8, 90, tau=0.0008, hp=5000) * env_curve(2.8, [(0, 1), (2.8, 0)])
    B.place(out, sparkle, pre + 0.05, 0.6)
    B.place(out, bell(1760, 3.0, 2.0, 0.8), pre, 0.25)
    return B.room(B.mband_sat(out, (2.6, 1.5, 1.2)), 3.4, 0.4, 5000)


def astraea_ascension():
    d = 5.0
    out = B.canvas(d)
    B.place(out, B.filt(B.white(0.05), "highpass", 1000) * B.decay(0.05, 0.01), 0, 1.3)
    B.place(out, B.kick(140, 24, 3.0, 1.0, 1.0), 0, 1.6)
    B.place(out, bell(660, 4.5, 2.4, 0.9), 0, 0.7)
    stab = choir([261.6, 329.6, 392, 523.3], 2.5) * B.decay(2.5, 0.9)
    B.place(out, stab, 0.01, 0.5)
    B.place(out, B.filt(B.brown(4.0), "lowpass", 160) * B.decay(4.0, 1.4), 0, 0.6)
    return B.room(B.mband_sat(out, (3.0, 1.7, 1.3)), 3.6, 0.42, 4200, 0.04)


# ---------------------------------------------------------------------------------------------- the throw

def astraea_throw():
    d = 3.2
    out = B.canvas(d)
    whoosh = B.sweep(B.white(1.2), "bandpass", 200, 3000, q=1.4) * env_curve(1.2, [(0, 0), (0.25, 1), (1.2, 0)])
    B.place(out, whoosh, 0, 1.0)
    crack = B.filt(B.white(0.03), "highpass", 800) * B.decay(0.03, 0.005)
    B.place(out, crack, 0.32, 1.4)
    B.place(out, B.kick(160, 40, 0.8, 0.25, 0.6), 0.32, 1.1)
    whine = B.unison(B.glide(400, 2400, 2.6, 1.6), 2.6, 3, 0.01, "saw")
    whine = B.filt(whine, "lowpass", 6000) * env_curve(2.6, [(0, 0), (0.3, 0.5), (2.6, 0)])
    B.place(out, whine, 0.35, 0.4)
    return B.room(B.mband_sat(out), 2.2, 0.32)


def astraea_pass():
    d = 0.9
    t = B.t_axis(d)
    # a fast object passing: rising then falling pitch (doppler), loud in the middle
    f = 900 * (1.25 - 0.5 / (1 + np.exp(-(t - 0.4) * 14)))
    tone = B.unison(f, d, 3, 0.01, "saw")
    tone = B.filt(tone, "lowpass", 4000)
    air = B.sweep(B.white(d), "bandpass", 3000, 600, q=1.4)
    e = np.exp(-((t - 0.4) / 0.13) ** 2)
    x = (tone * 0.35 + air * 0.9) * e
    return B.room(x, 0.6, 0.2)


def astraea_drill():
    d = 6.0
    t = B.t_axis(d)
    grind = bandnoise(d, 200, 1800) * (1 + 0.7 * np.sin(2 * np.pi * 37 * t + 3 * np.sin(2 * np.pi * 0.7 * t)))
    shriek = B.unison(B.glide(1800, 2600, d, 1.0), d, 4, 0.02, "saw")
    shriek = B.filt(shriek, "bandpass", [1500, 5000]) * env_curve(d, [(0, 0), (1, 0.3), (6, 0.5)])
    x = B.mix(grind * 0.8, shriek * 0.25, rumble(d, 25, 120) * 1.2, crackle_dense(d, env_curve(d, [(0, 0.4), (6, 1)]), 200, 2500) * 0.9)
    x *= env_curve(d, [(0, 0), (0.5, 1), (6, 1)])
    return B.room(B.mband_sat(x, (2.4, 1.7, 1.2)), 2.4, 0.35, 4000)


def astraea_split():
    """Earth splitting, heard from its surface. Layers, each with a physical story:
    1. the shock front: a supersonic N-wave crack (a sharp double transient);
    2. the blast: a colossal sub-bass pressure wave, with its own slow pitch fall;
    3. the near-field roar: heavy low-passed noise with chaotic amplitude (turbulence);
    4. rock tearing: a dense storm of fracture crackle that thins out over seconds;
    5. rolling thunder: the blast echoing off terrain and the atmosphere, delayed rumbles over ten seconds;
    6. the pressure rush: air pulled back in behind the blast, a rising then falling wind;
    7. debris: sparse falling-rock impacts in the tail."""
    d = 14.0
    out = B.canvas(d)
    # 1. N-wave: two sharp edges ~40 ms apart, slightly low-passed by distance
    nwave = np.zeros(B.n_of(0.1))
    i0, i1 = int(0.002 * SR), int(0.042 * SR)
    nwave[i0:i1] = np.linspace(1, -1, i1 - i0)
    nwave = B.filt(nwave, "lowpass", 6000, 2)
    B.place(out, nwave, 0.0, 1.8)
    B.place(out, B.filt(B.white(0.12), "highpass", 500) * B.decay(0.12, 0.02), 0.0, 1.2)
    # 2. the blast's sub-bass
    sub = B.osc(B.glide(58, 17, 6.0, 0.35), 6.0) * env_curve(6.0, [(0, 0), (0.03, 1), (1.0, 0.75), (6.0, 0)])
    B.place(out, sub, 0.01, 2.2)
    # 3. turbulent near-field roar
    roar = B.filt(B.brown(9.0), "lowpass", 380) * env_curve(9.0, [(0, 0), (0.05, 1), (2.0, 0.7), (9.0, 0)])
    t9 = B.t_axis(9.0)
    turb = np.clip(1 + 0.7 * B.filt(rng.standard_normal(len(t9)), "lowpass", 6, 2) * 8, 0.1, 2.5)
    B.place(out, roar * turb, 0.02, 2.0)
    mid = B.filt(B.white(7.0), "bandpass", [180, 1400], 2) * env_curve(7.0, [(0, 0), (0.04, 1), (1.5, 0.4), (7.0, 0)])
    B.place(out, mid * turb[:len(mid)], 0.02, 0.9)
    # 4. rock tearing
    tear = crackle_dense(8.0, env_curve(8.0, [(0, 1), (1.0, 0.7), (3, 0.2), (8, 0.0)]), 250, 4000, 0.006)
    B.place(out, tear, 0.05, 0.7)
    # 5. rolling thunder: delayed, darker echoes
    for k, (at, g, lp) in enumerate(((1.2, 0.8, 260), (2.3, 0.65, 220), (3.6, 0.55, 180), (5.2, 0.45, 150), (7.4, 0.32, 120))):
        r = rumble(5.5, 22, lp, 0.9, 3.0) * env_curve(5.5, [(0, 0), (0.4, 1), (2.0, 0.6), (5.5, 0)])
        B.place(out, r, at + rng.random() * 0.2, g * 1.2)
    # 6. the pressure rush
    rush = B.sweep(B.white(4.0), "bandpass", 200, 1200, q=1.3) * env_curve(4.0, [(0, 0), (1.5, 1), (4.0, 0)])
    B.place(out, rush, 1.0, 0.45)
    # 7. debris
    deb = B.canvas(8.0)
    for _ in range(140):
        at = 1.5 + rng.random() ** 0.7 * 6.0
        rock = B.filt(B.white(0.08), "bandpass", [300 + rng.random() * 1500, 4000]) * B.decay(0.08, 0.012)
        B.place(deb, rock, at, rng.random() ** 2 * 0.8)
    B.place(out, deb, 0.0, 0.25)
    # air absorption: the highs die within a few seconds while the lows roll on
    body = out.copy()
    head = B.n_of(0.06)
    body[:head] *= np.linspace(0, 1, head)
    absorbed = B.sweep(body, "lowpass", 13000, 380, blocks=300, order=2)
    out = absorbed + out * np.concatenate([np.ones(head), np.zeros(len(out) - head)])
    # distance and size: mild saturation, then a huge open space
    out = B.mband_sat(out, (2.2, 1.5, 1.15), (160, 2000))
    return B.room(out, 4.5, 0.4, 1800, 0.06)


def astraea_quake():
    d = 6.0
    x = rumble(d, 18, 90, 1.0, 1.8) * 1.4 + crackle_dense(d, env_curve(d, [(0, 0.3), (6, 0.3)]), 80, 600, 0.02) * 0.6
    return B.make_loop(B.room(x, 1.5, 0.3, 1500)[:B.n_of(d + 0.3)], 0.4)


def astraea_tear():
    d = 4.5
    out = B.canvas(d)
    B.place(out, B.filt(B.white(0.06), "bandpass", [200, 3000]) * B.decay(0.06, 0.015), 0, 1.4)
    B.place(out, B.kick(90, 28, 1.6, 0.6, 0.6), 0, 1.2)
    slide = crackle_dense(3.5, env_curve(3.5, [(0, 1), (3.5, 0.15)]), 120, 1800, 0.012)
    B.place(out, slide, 0.05, 1.1)
    B.place(out, rumble(4.0, 20, 160) * env_curve(4.0, [(0, 0), (0.3, 1), (4.0, 0)]), 0.05, 1.2)
    return B.room(B.mband_sat(out, (2.4, 1.5, 1.1)), 2.6, 0.38, 2500)


def astraea_return():
    d = 2.8
    t = B.t_axis(2.0)
    whistle = B.osc(B.glide(3200, 700, 2.0, 0.8), 2.0) * env_curve(2.0, [(0, 0), (1.4, 0.6), (2.0, 0)])
    air = B.sweep(B.white(2.0), "bandpass", 5000, 700, q=2.0) * env_curve(2.0, [(0, 0), (1.8, 1), (2.0, 0)])
    out = B.canvas(d)
    B.place(out, whistle * 0.3 + air * 0.6, 0, 1.0)
    B.place(out, bell(1046.5, 1.5, 1.2, 0.85), 1.95, 0.5)
    B.place(out, B.kick(160, 60, 0.4, 0.1, 0.5), 1.95, 0.7)
    return B.room(out, 1.8, 0.3)


SOUNDS = {
    "astraea_aura": astraea_aura, "astraea_stance": astraea_stance, "astraea_rift": astraea_rift, "astraea_pulse": astraea_pulse,
    "astraea_descend": astraea_descend, "astraea_absorb": astraea_absorb, "astraea_ascension": astraea_ascension,
    "astraea_throw": astraea_throw, "astraea_pass": astraea_pass, "astraea_drill": astraea_drill, "astraea_split": astraea_split,
    "astraea_quake": astraea_quake, "astraea_tear": astraea_tear, "astraea_return": astraea_return,
}
LOOPS = {"astraea_aura", "astraea_quake"}
DIST = {"astraea_aura": 10, "astraea_stance": 24, "astraea_rift": 128, "astraea_pulse": 96, "astraea_descend": 128,
        "astraea_absorb": 64, "astraea_ascension": 96, "astraea_throw": 64, "astraea_pass": 16, "astraea_drill": 16,
        "astraea_split": 16, "astraea_quake": 16, "astraea_tear": 160, "astraea_return": 48}


def main():
    out = B.OUT
    only = set(sys.argv[1:])
    for name, fn in SOUNDS.items():
        if only and name not in only:
            continue
        x = B.norm(fn(), 0.95)
        x = B.limit(x, 0.92)
        if name not in LOOPS:
            x = B.fade(B.norm(B.trim(x), 0.9), 0.001, 0.1)
        else:
            x = B.norm(x, 0.85)
        B.write_ogg(x, out / f"{name}.ogg")
        print(f"{name:18s} {len(x) / SR:5.2f}s")
    sj = out.parent / "sounds.json"
    js = json.loads(sj.read_text())
    for name in SOUNDS:
        event = "astraea." + name[8:]
        js[event] = {"subtitle": f"subtitles.orbital_railgun.{event}",
                     "sounds": [{"name": f"orbital_railgun:{name}", "attenuation_distance": DIST[name], "stream": name in ("astraea_split",)}]}
    sj.write_text(json.dumps(js, indent=2))


if __name__ == "__main__":
    main()
