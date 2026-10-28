#!/usr/bin/env python3
"""
Arsenal Legion sounds, synthesised from scratch with the DSP toolkit in build_sounds.py, written to
assets/arsenal_legion/sounds/ with their own sounds.json.

  swarm_hum      (loop) the telekinetic field holding the swarm up: a low beating drone of detuned sines, a slow
                 throb, a faint glassy shimmer and air hiss, seamless
  weapon_launch  a weapon breaking orbit: a sharp metallic whip - a falling-to-rising swish, a blade ring and a crack
  weapon_pierce  the blade punching through a target: a meaty thunk, a short tearing swish and a ringing edge
  weapon_parry   a projectile knocked aside: a bright clang with a fast ring-off and a spark crackle
  altar_bind     a weapon binding to the altar: a deep resonant chime, the field catching it, a rising shimmer
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_sounds as B  # noqa: E402

SR = B.SR
OUT = B.ROOT / "src/main/resources/assets/arsenal_legion/sounds"
rng = np.random.default_rng(6060)


def swarm_hum():
    d = 6.25
    t = B.t_axis(d)
    drone = sum(np.sin(2 * np.pi * f * t + rng.random() * 6) * a for f, a in ((55.0, 0.6), (55.7, 0.5), (82.5, 0.3), (110.3, 0.18)))
    throb = 0.75 + 0.25 * np.sin(2 * np.pi * 0.8 * t)
    shimmer = B.unison(660, d, 4, 0.006, "sine") * (0.5 + 0.5 * np.sin(2 * np.pi * 0.32 * t)) * 0.06
    air = B.filt(B.white(d), "bandpass", [300, 1400]) * 0.05 * (0.6 + 0.4 * np.sin(2 * np.pi * 0.48 * t + 1))
    x = (drone * throb * 0.5 + shimmer + air)
    return B.make_loop(x, 0.6)


def whip(dur, f0, f1, f2):
    """Air swish: band noise whose centre sweeps down then up."""
    half = dur / 2
    a = B.sweep(B.white(half), "bandpass", f0, f1, 80, 2, 1.8)
    b = B.sweep(B.white(half), "bandpass", f1, f2, 80, 2, 1.8)
    x = np.concatenate([a, b])
    t = B.t_axis(dur)
    return x * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 1.5


def weapon_launch():
    out = B.canvas(1.0)
    B.place(out, whip(0.22, 2500, 700, 5200) * 1.1, 0.0)
    B.place(out, B.filt(B.white(0.012), "highpass", 3000) * B.decay(0.012, 0.002) * 1.4, 0.11)
    B.place(out, B.metal(1180, 0.8, 0.28, 1.6) * 0.35, 0.1)
    B.place(out, B.osc(B.glide(900, 2400, 0.18, 0.5), 0.18, "tri") * B.decay(0.18, 0.08) * 0.18, 0.08)
    return B.room(out, 0.7, 0.22)


def weapon_pierce():
    out = B.canvas(0.9)
    B.place(out, B.kick(160, 60, 0.3, 0.06, 0.5) * 0.9, 0.0)
    B.place(out, B.filt(B.white(0.09), "bandpass", [300, 2500]) * B.decay(0.09, 0.025) * 0.9, 0.0)
    B.place(out, whip(0.14, 3200, 1200, 2600) * 0.6, 0.01)
    B.place(out, B.metal(1500, 0.6, 0.18, 1.8) * 0.2, 0.02)
    return B.room(B.mband_sat(out, (2.2, 1.6, 1.2)), 0.6, 0.2)


def weapon_parry():
    out = B.canvas(1.2)
    B.place(out, B.metal(860, 1.0, 0.4, 2.0) * 0.7, 0.0)
    B.place(out, B.metal(1390, 0.7, 0.2, 2.0) * 0.4, 0.0)
    B.place(out, B.filt(B.white(0.015), "highpass", 2500) * B.decay(0.015, 0.003) * 1.5, 0.0)
    B.place(out, B.crackle(0.35, 260, 0.0008, 3500) * 0.7, 0.01)
    return B.room(out, 0.8, 0.25)


def altar_bind():
    d = 2.6
    out = B.canvas(d)
    t = B.t_axis(2.2)
    chime = B.fm(196, 1.5, 2.6 * np.exp(-t / 0.25), 2.2) * B.decay(2.2, 0.9, 0.003) * 0.5
    B.place(out, chime, 0.0)
    B.place(out, B.fm(294, 2.01, 1.4 * np.exp(-t / 0.2), 2.2) * B.decay(2.2, 0.7) * 0.3, 0.05)
    rise = B.unison(B.glide(220, 880, 1.2, 0.8), 1.2, 5, 0.01, "sine") * B.env(1.2, 0.3, 0.2, 0.7, 0.5) * 0.18
    B.place(out, rise, 0.2)
    B.place(out, B.kick(90, 40, 0.8, 0.25, 0.2) * 0.5, 0.0)
    return B.room(out, 1.6, 0.35)


def execution():
    out = B.canvas(1.6)
    B.place(out, B.filt(B.white(0.02), "highpass", 2000) * B.decay(0.02, 0.004) * 1.8, 0.0)
    B.place(out, B.kick(130, 35, 0.9, 0.22, 0.8) * 1.3, 0.0)
    B.place(out, whip(0.2, 4200, 900, 6000) * 0.9, 0.0)
    B.place(out, B.metal(990, 1.2, 0.45, 2.0) * 0.4, 0.01)
    t = B.t_axis(0.9)
    B.place(out, B.fm(523, 2.0, 2.0 * np.exp(-t / 0.15), 0.9) * B.decay(0.9, 0.35) * 0.35, 0.03)
    return B.room(B.mband_sat(out, (2.6, 1.8, 1.3)), 1.0, 0.3)


def blade_storm():
    d = 3.8
    t = B.t_axis(d)
    e = np.minimum(1, t / 0.4) * np.minimum(1, (d - t) / 0.8)
    howl = B.sweep(B.white(d), "bandpass", 500, 2600, 200, 2, 2.4) * 0.6
    swish = np.zeros_like(t)
    for k in range(26):
        at = 0.2 + k * 0.13 + rng.random() * 0.04
        w = whip(0.12, 3800, 900, 4800) * (0.4 + 0.4 * rng.random())
        swish[int(at * SR):int(at * SR) + len(w)] += w[:max(0, len(swish) - int(at * SR))]
    drone = B.unison(B.glide(70, 140, d, 1.0), d, 5, 0.02, "saw")
    drone = B.filt(drone, "lowpass", 900) * 0.3
    out = (howl + swish + drone) * e
    B.place(out, B.kick(100, 28, 1.2, 0.4, 0.6), 0.0)
    return B.room(out, 1.6, 0.35)


def formation_shift():
    out = B.canvas(1.0)
    for k, f in enumerate((660, 880, 990)):
        t = B.t_axis(0.5)
        B.place(out, B.fm(f, 1.5, 1.2 * np.exp(-t / 0.08), 0.5) * B.decay(0.5, 0.2) * 0.3, k * 0.05)
    B.place(out, whip(0.3, 1500, 600, 3500) * 0.4, 0.0)
    return B.room(out, 0.9, 0.3)


def fury_ready():
    out = B.canvas(1.8)
    t = B.t_axis(1.4)
    B.place(out, B.unison(B.glide(220, 660, 0.5, 0.6), 0.5, 4, 0.01, "saw") * B.env(0.5, 0.05, 0.1, 0.7, 0.2) * 0.25, 0.0)
    B.place(out, B.fm(440, 1.5, 2.2 * np.exp(-t / 0.2), 1.4) * B.decay(1.4, 0.6) * 0.45, 0.4)
    B.place(out, B.kick(80, 40, 0.6, 0.2, 0.2) * 0.6, 0.4)
    return B.room(out, 1.2, 0.35)


SOUNDS = {"swarm_hum": swarm_hum, "weapon_launch": weapon_launch, "weapon_pierce": weapon_pierce, "weapon_parry": weapon_parry,
          "altar_bind": altar_bind, "execution": execution, "blade_storm": blade_storm, "formation_shift": formation_shift,
          "fury_ready": fury_ready}
DIST = {"swarm_hum": 12, "weapon_launch": 24, "weapon_pierce": 24, "weapon_parry": 24, "altar_bind": 16, "execution": 32, "blade_storm": 48, "formation_shift": 16, "fury_ready": 12}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    js = {}
    for name, fn in SOUNDS.items():
        x = B.norm(fn(), 0.95)
        x = B.limit(x, 0.92)
        if name == "swarm_hum":
            x = B.norm(x, 0.8)
        else:
            x = B.fade(B.norm(B.trim(x), 0.9), 0.001, 0.06)
        B.write_ogg(x, OUT / f"{name}.ogg")
        print(f"{name:14s} {len(x) / SR:5.2f}s")
        js[name] = {"subtitle": f"subtitles.arsenal_legion.{name}",
                    "sounds": [{"name": f"arsenal_legion:{name}", "attenuation_distance": DIST[name]}]}
    (OUT.parent / "sounds.json").write_text(json.dumps(js, indent=2))


if __name__ == "__main__":
    main()
