#!/usr/bin/env python3
"""
Cosmic Reliquary sounds, synthesised from scratch with the DSP toolkit in build_sounds.py, written to
assets/cosmic_reliquary/sounds/ with their own sounds.json.

  star_eater_hum      (loop) a low digital drone: a quantised (bit-crushed) detuned saw stack with a slow comb
                      sweep and a stepped arpeggiated overtone; the game raises its pitch as the charge builds
  singularity_form    the collapse: a sucking reverse swell into a sub drop and a deep resonant hum
  core_implode        a crushing vacuum fracture: air rushing inward, a hard crack, a crunching implosion, sub thump
  void_slash          a sudden air-tearing vacuum suction on the swing: a fast inward whoosh and a torn-cloth rip
  void_arc            the rift's arcs: sharp electric snaps with a falling buzz
  core_revolver_fire  a loud, heavy kinetic sonic boom: N-wave crack, sub punch, ringing frame, rolling tail
                      (limited and normalised so it never clips)
  molten_sizzle       molten surface: hiss and popping crackle
  orbital_link        the link to orbit: a rising beam tone, a deep chime and a far-off answering boom
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_sounds as B  # noqa: E402

SR = B.SR
OUT = B.ROOT / "src/main/resources/assets/cosmic_reliquary/sounds"
rng = np.random.default_rng(9113)


def crush(x, bits=6):
    q = 2 ** (bits - 1)
    return np.round(x * q) / q


def star_eater_hum():
    d = 4.0
    t = B.t_axis(d)
    stack = B.unison(48, d, 5, 0.01, "saw")
    stack = B.filt(crush(stack * 0.8, 5), "lowpass", 1400) * 0.55
    comb_rate = 0.5
    sweep = B.filt(B.white(d), "bandpass", [600, 2400]) * (0.5 + 0.5 * np.sin(2 * np.pi * comb_rate * t)) * 0.08
    steps = np.floor(t * 8) % 4
    arp_f = 192 * (2 ** (np.choose(steps.astype(int), [0, 7, 12, 3]) / 12))
    arp = crush(B.osc(arp_f, d, "square"), 4) * 0.06
    sub = np.sin(2 * np.pi * 36 * t) * 0.35
    return B.make_loop(stack + sweep + arp + sub, 0.5)


def singularity_form():
    d = 3.2
    out = B.canvas(d)
    sw = B.sweep(B.white(1.2), "bandpass", 6000, 200, 120, 2, 1.6)
    sw *= np.linspace(0, 1, len(sw)) ** 2
    B.place(out, sw * 0.9, 0.0)
    B.place(out, B.kick(80, 18, 2.0, 0.7, 0.3) * 1.3, 1.15)
    t = B.t_axis(2.0)
    hum = (np.sin(2 * np.pi * 41 * t) + 0.5 * np.sin(2 * np.pi * 61.7 * t)) * B.decay(2.0, 0.9) * 0.4
    B.place(out, hum, 1.15)
    return B.room(out, 1.8, 0.35)


def core_implode():
    out = B.canvas(2.6)
    rush = B.sweep(B.white(0.45), "bandpass", 300, 4000, 60, 2, 2.0) * np.linspace(0.2, 1, B.n_of(0.45)) ** 2
    B.place(out, rush, 0.0)
    B.place(out, B.filt(B.white(0.02), "highpass", 1800) * B.decay(0.02, 0.004) * 2.2, 0.45)
    crunch = B.crackle(0.4, 900, 0.0015, 400) * 1.4
    B.place(out, crunch, 0.45)
    B.place(out, B.kick(140, 25, 1.4, 0.35, 0.7) * 1.5, 0.45)
    B.place(out, B.metal(220, 1.2, 0.3, 0.8) * 0.3, 0.46)
    return B.room(B.mband_sat(out, (3.0, 2.0, 1.4)), 1.4, 0.3)


def void_slash():
    out = B.canvas(1.1)
    suck = B.sweep(B.white(0.3), "bandpass", 5500, 500, 60, 2, 1.8) * np.sin(np.pi * np.linspace(0, 1, B.n_of(0.3))) ** 1.2
    B.place(out, suck * 1.2, 0.0)
    rip = B.crackle(0.18, 1400, 0.0008, 1200) * np.linspace(1, 0, B.n_of(0.18)) * 0.9
    B.place(out, rip, 0.12)
    B.place(out, B.osc(B.glide(140, 60, 0.4), 0.4) * B.decay(0.4, 0.12) * 0.5, 0.12)
    return B.room(out, 0.9, 0.3)


def void_arc():
    out = B.canvas(0.9)
    for k in range(5):
        B.place(out, B.zap(0.06 + rng.random() * 0.05, 0.8), rng.random() * 0.25)
    buzz = B.filt(B.osc(B.glide(420, 90, 0.4), 0.4, "square"), "bandpass", [200, 3000]) * B.decay(0.4, 0.12) * 0.35
    B.place(out, buzz, 0.02)
    return B.room(out, 0.7, 0.25)


def core_revolver_fire():
    out = B.canvas(3.0)
    n = B.n_of(0.012)
    nwave = np.concatenate([np.linspace(1, -1, n), np.zeros(4)]) * 1.6
    B.place(out, nwave, 0.0)
    B.place(out, B.filt(B.white(0.025), "highpass", 1500) * B.decay(0.025, 0.005) * 2.0, 0.0)
    B.place(out, B.kick(150, 30, 1.2, 0.22, 0.9) * 1.6, 0.0)
    B.place(out, B.metal(640, 1.4, 0.4, 1.4) * 0.25, 0.005)
    tail = B.filt(B.brown(2.6), "lowpass", 420) * B.decay(2.6, 0.7, 0.01) * 0.8
    B.place(out, tail, 0.01)
    return B.room(B.mband_sat(out, (2.6, 1.7, 1.2)), 2.0, 0.3)


def molten_sizzle():
    d = 1.4
    t = B.t_axis(d)
    hiss = B.filt(B.white(d), "bandpass", [2500, 9000]) * B.env(d, 0.02, 0.1, 0.6, 0.6) * 0.4
    pops = B.crackle(d, 60, 0.002, 900) * 0.9
    return B.room(hiss + pops, 0.5, 0.2)


def orbital_link():
    d = 3.6
    out = B.canvas(d)
    t = B.t_axis(1.6)
    rise = B.unison(B.glide(110, 880, 1.6, 0.7), 1.6, 5, 0.01, "saw")
    rise = B.filt(rise, "lowpass", 3500) * np.minimum(1, t / 0.2) * np.exp(-np.maximum(0, t - 1.2) / 0.2) * 0.4
    B.place(out, rise, 0.0)
    t2 = B.t_axis(2.2)
    chime = B.fm(330, 1.5, 2.4 * np.exp(-t2 / 0.3), 2.2) * B.decay(2.2, 0.9, 0.003) * 0.5
    B.place(out, chime, 1.3)
    B.place(out, B.filt(B.kick(70, 25, 1.8, 0.7, 0.1), "lowpass", 300) * 0.8, 1.9)
    return B.room(out, 2.4, 0.4)


SOUNDS = {"star_eater_hum": star_eater_hum, "singularity_form": singularity_form, "core_implode": core_implode, "void_slash": void_slash,
          "void_arc": void_arc, "core_revolver_fire": core_revolver_fire, "molten_sizzle": molten_sizzle, "orbital_link": orbital_link}
DIST = {"star_eater_hum": 24, "singularity_form": 64, "core_implode": 48, "void_slash": 24, "void_arc": 32, "core_revolver_fire": 160,
        "molten_sizzle": 16, "orbital_link": 64}
LOOPS = {"star_eater_hum"}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    js = {}
    for name, fn in SOUNDS.items():
        x = B.norm(fn(), 0.95)
        x = B.limit(x, 0.89)
        x = B.norm(x, 0.8) if name in LOOPS else B.fade(B.norm(B.trim(x), 0.89), 0.001, 0.06)
        B.write_ogg(x, OUT / f"{name}.ogg")
        print(f"{name:20s} {len(x) / SR:5.2f}s  peak {np.abs(x).max():.2f}")
        js[name] = {"subtitle": f"subtitles.cosmic_reliquary.{name}",
                    "sounds": [{"name": f"cosmic_reliquary:{name}", "attenuation_distance": DIST[name]}]}
    (OUT.parent / "sounds.json").write_text(json.dumps(js, indent=2))


if __name__ == "__main__":
    main()
