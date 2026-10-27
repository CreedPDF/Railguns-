#!/usr/bin/env python3
"""
Sounds for Space Command (the orbital station and its strike satellite), synthesised from scratch with the DSP toolkit
in build_sounds.py and merged into sounds.json under "sc.*" (build_sounds.py keeps those entries when it rewrites it).

  sc_link           a module joining the station grid: relay clack, a two-note bell chime, a short data warble
  sc_deploy         a solar array rolling out (5 s, the roll-out time): gimbal servo, drum ratchet, the blanket
                    unspooling, tension latch at the end
  sc_hum            the station grid powering up: transformer hum rising out of silence, fans spinning up
  sc_alarm          debris alarm: a two-tone warbling klaxon, twice
  sc_rail_charge    station railgun capacitors charging (0.8 s): rising whine, gathering crackle
  sc_rail_fire      the railgun firing: magnetic crack, a hard sub punch, ringing barrel, hiss
  sc_rail_hit       a meteorite shattering: crunch, low boom, rock fragments rattling away
  sc_sat_uplink     the tablet's strike order acknowledged: data chirps and a falling lock tone
  sc_sat_charge     the satellite's emitter charging (2.5 s): deep swelling drone, a shimmering chorus, accelerating pulses
  sc_sat_fire       the emitter's discharge: a vast bright crack with a falling "pew" and sub drop
  sc_strike_beam    the beam tearing down through the atmosphere: a falling, ripping roar
  sc_strike_impact  the beam hitting the ground: white crack, sub slam, rolling detonation, debris raining down
  sc_strike_rumble  the beam boring into the crater: a long seething roar and crackle, fading out
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_sounds as B  # noqa: E402

SR = B.SR
rng = np.random.default_rng(7041)


def bandnoise(dur, lo, hi, order=2):
    return B.filt(B.white(dur), "bandpass", [lo, hi], order)


def bell(f, dur, tau=0.6, level=1.0):
    """FM bell: a warm struck tone with inharmonic shimmer."""
    t = B.t_axis(dur)
    x = B.fm(f, 1.41, 2.2 * np.exp(-t / (tau * 0.3)), dur)
    return x * B.decay(dur, tau, 0.002) * level


def sc_link():
    out = B.canvas(1.2)
    B.place(out, B.latch(0.6, 620), 0.0)
    B.place(out, bell(880, 0.9, 0.35, 0.45), 0.06)
    B.place(out, bell(1318, 0.9, 0.4, 0.4), 0.18)
    t = B.t_axis(0.25)
    warble = np.sign(np.sin(2 * np.pi * 37 * t)) * 0.5 + 0.5
    data = B.osc(np.where(warble > 0.5, 2400, 1800), 0.25, "tri") * B.env(0.25, 0.01, 0.05, 0.7, 0.08) * 0.15
    B.place(out, data, 0.36)
    return B.room(out, 0.6, 0.25)


def sc_deploy():
    d = 5.0
    out = B.canvas(d + 0.8)
    B.place(out, B.servo(1.3, 70, 140, 9, 0.6), 0.0)
    B.place(out, B.latch(0.5, 360), 1.25)
    # the drums turning and the blanket unspooling
    t = B.t_axis(3.4)
    e = np.minimum(1, t / 0.4) * np.minimum(1, (3.4 - t) / 0.4)
    spool = bandnoise(3.4, 300, 2600) * e * (0.6 + 0.4 * np.sin(2 * np.pi * 9 * t)) * 0.35
    rustle = bandnoise(3.4, 3000, 9000) * e * 0.12 * (0.5 + 0.5 * B.filt(rng.standard_normal(len(t)), "lowpass", 14) * 4)
    B.place(out, spool + rustle, 1.4)
    B.place(out, B.ratchet(3.3, 13, 1700, 0.25), 1.45)
    B.place(out, B.servo(3.4, 180, 160, 13, 0.25), 1.4)
    B.place(out, B.clank(0.7, 240, 0.25), 4.8)
    B.place(out, bell(660, 0.8, 0.3, 0.2), 4.85)
    return B.room(out, 0.9, 0.25)


def sc_hum():
    d = 3.2
    t = B.t_axis(d)
    rise = np.clip(t / 1.4, 0, 1) ** 1.5
    hum = B.power_hum(d, 50 * (0.6 + 0.4 * rise), 0.5) * rise
    fan_f = 40 + 140 * rise
    fan = bandnoise(d, 400, 2400) * (0.6 + 0.4 * np.sin(B.phase_of(fan_f, len(t)))) * rise * 0.18
    whine = B.osc(B.glide(300, 1600, d, 0.5), d) * 0.06 * rise
    x = (hum + fan + whine) * np.minimum(1, (d - t) / 0.6)
    out = B.canvas(d)
    B.place(out, x, 0)
    B.place(out, B.latch(0.5, 520), 0.02)
    return B.room(out, 1.0, 0.3)


def sc_alarm():
    out = B.canvas(2.4)
    for k in range(2):
        for j, f in enumerate((620, 470)):
            dur = 0.36
            t = B.t_axis(dur)
            tone = B.osc(f * (1 + 0.02 * np.sin(2 * np.pi * 18 * t)), dur, "square")
            tone = B.filt(tone, "bandpass", [300, 3200]) * B.env(dur, 0.01, 0.04, 0.85, 0.06) * 0.5
            B.place(out, tone, k * 0.82 + j * 0.38)
    return B.room(out, 1.4, 0.35)


def sc_rail_charge():
    d = 0.85
    t = B.t_axis(d)
    whine = B.unison(B.glide(320, 2600, d, 1.6), d, 3, 0.01, "saw")
    whine = B.filt(whine, "lowpass", 6000) * (t / d) ** 1.2 * 0.4
    sub = B.osc(B.glide(55, 110, d), d) * 0.3 * (t / d)
    crk = B.crackle(d, 140, 0.0012, 3000, amp_env=(t / d) ** 2) * 1.2
    return B.mix(whine, sub, crk) * np.minimum(1, (d - t) / 0.02 + 0.0)


def sc_rail_fire():
    out = B.canvas(2.2)
    crack = B.filt(B.white(0.03), "highpass", 2000) * B.decay(0.03, 0.005) * 2.0
    B.place(out, crack, 0.0)
    B.place(out, B.zap(0.18, 1.1), 0.0)
    B.place(out, B.kick(170, 40, 0.8, 0.12, 0.9) * 1.4, 0.0)
    B.place(out, B.metal(520, 1.6, 0.6, 1.4) * 0.35, 0.01)
    B.place(out, B.steam(1.2, 0.3), 0.05)
    snap = B.osc(B.glide(4200, 600, 0.12), 0.12) * B.decay(0.12, 0.03) * 0.5
    B.place(out, snap, 0.0)
    return B.room(B.mband_sat(out, (2.6, 1.8, 1.3)), 1.2, 0.3)


def sc_rail_hit():
    out = B.canvas(2.2)
    crunch = bandnoise(0.25, 400, 6000) * B.decay(0.25, 0.05) * 1.4
    B.place(out, crunch, 0.0)
    B.place(out, B.kick(120, 38, 1.0, 0.2, 0.6), 0.0)
    for k in range(40):
        f = 600 + rng.random() * 2600
        B.place(out, B.metal(f, 0.12, 0.02, 1.5) * 0.08 * rng.random(), 0.04 + rng.random() * 1.3)
    tail = B.filt(B.brown(1.8), "lowpass", 500) * B.decay(1.8, 0.5) * 0.5
    B.place(out, tail, 0.02)
    return B.room(B.mband_sat(out), 1.4, 0.3)


def sc_sat_uplink():
    out = B.canvas(1.4)
    for k, f in enumerate((1600, 2100, 1600, 2400, 2800)):
        B.place(out, B.beep([f], 0.05, "tri", 0.3), k * 0.07)
    lock = B.osc(B.glide(1400, 700, 0.5, 0.7), 0.5, "tri") * B.env(0.5, 0.01, 0.1, 0.6, 0.2) * 0.4
    B.place(out, lock, 0.4)
    B.place(out, bell(700, 0.8, 0.4, 0.25), 0.42)
    return B.room(out, 0.8, 0.3)


def sc_sat_charge():
    d = 2.6
    t = B.t_axis(d)
    k = t / d
    drone = B.unison(B.glide(42, 84, d, 1.2), d, 5, 0.008, "saw")
    drone = B.filt(drone, "lowpass", 900) * (0.3 + 0.7 * k) * 0.55
    chorus = B.unison(B.glide(330, 990, d, 1.4), d, 6, 0.015, "sine") * k ** 1.5 * 0.25
    # pulses accelerating from 3 to 22 per second
    rate = 3 + 19 * k ** 2
    ph = np.cumsum(rate) / SR
    pulse = (0.5 + 0.5 * np.cos(2 * np.pi * ph)) ** 6
    pul = B.osc(B.glide(600, 1800, d), d, "tri") * pulse * k * 0.25
    crk = B.crackle(d, 60, 0.0015, 2500, amp_env=k ** 2) * 0.6
    return B.room(B.mix(drone, chorus, pul, crk), 1.4, 0.3)


def sc_sat_fire():
    out = B.canvas(3.0)
    crack = B.filt(B.white(0.05), "highpass", 1500) * B.decay(0.05, 0.008) * 2.4
    B.place(out, crack, 0.0)
    pew = B.unison(B.glide(2800, 90, 1.1, 0.35), 1.1, 5, 0.02, "saw")
    pew = B.filt(pew, "lowpass", 7000) * B.decay(1.1, 0.4) * 0.7
    B.place(out, pew, 0.0)
    B.place(out, B.kick(90, 22, 2.0, 0.6, 0.8) * 1.4, 0.0)
    B.place(out, B.zap(0.3, 1.0), 0.0)
    B.place(out, B.filt(B.brown(2.4), "lowpass", 400) * B.decay(2.4, 0.8) * 0.6, 0.02)
    return B.room(B.mband_sat(out, (3.0, 2.0, 1.4)), 2.2, 0.35)


def sc_strike_beam():
    d = 1.6
    t = B.t_axis(d)
    rip = B.sweep(B.white(d), "bandpass", 5000, 400, 200, 2, 2.0) * 0.9
    roar = B.filt(B.brown(d), "lowpass", 700) * 0.7
    tone = B.unison(B.glide(900, 120, d, 0.6), d, 4, 0.03, "saw") * 0.18
    e = np.minimum(1, t / 0.15) * np.minimum(1, (d - t) / 0.3)
    crk = B.crackle(d, 220, 0.001, 2500) * 0.8
    return B.room((rip + roar + tone + crk) * e, 1.6, 0.3)


def sc_strike_impact():
    out = B.canvas(8.0)
    flash = B.filt(B.white(0.03), "highpass", 1200) * B.decay(0.03, 0.006) * 2.5
    B.place(out, flash, 0.0)
    B.place(out, B.kick(110, 16, 3.0, 1.1, 0.9) * 1.9, 0.0)
    det = B.filt(B.brown(7.0), "lowpass", 260) * B.decay(7.0, 2.4, 0.03) * 1.4
    mid = bandnoise(5.0, 100, 2000) * B.decay(5.0, 1.0, 0.02) * 0.9
    B.place(out, B.mix(det, mid), 0.01)
    B.place(out, B.crackle(5.0, 500, 0.002, 800) * np.exp(-B.t_axis(5.0) / 1.6) * 1.3, 0.04)
    debris = B.canvas(7.0)
    for k in range(90):
        f = 150 + rng.random() * 1100
        B.place(debris, B.metal(f, 0.3, 0.07) * 0.05 * rng.random(), 0.6 + rng.random() * 5.5)
        B.place(debris, bandnoise(0.06, 800, 5000) * B.decay(0.06, 0.015) * 0.12 * rng.random(), 0.5 + rng.random() * 5.0)
    B.place(out, debris, 0.0)
    return B.room(B.mband_sat(out, (3.6, 2.3, 1.4)), 3.2, 0.4)


def sc_strike_rumble():
    d = 3.6
    t = B.t_axis(d)
    e = np.minimum(1, t / 0.3) * np.exp(-np.maximum(0, t - 1.8) / 0.9)
    roar = B.filt(B.brown(d), "lowpass", 380) * 1.0
    seethe = bandnoise(d, 600, 3500) * (0.6 + 0.4 * np.sin(2 * np.pi * 5.3 * t)) * 0.35
    crk = B.crackle(d, 160, 0.0015, 1800) * 0.7
    hum = B.osc(36, d) * 0.3
    return B.room((roar + seethe + crk + hum) * e, 2.4, 0.35)


SOUNDS = {
    "sc_link": sc_link, "sc_deploy": sc_deploy, "sc_hum": sc_hum, "sc_alarm": sc_alarm, "sc_rail_charge": sc_rail_charge,
    "sc_rail_fire": sc_rail_fire, "sc_rail_hit": sc_rail_hit, "sc_sat_uplink": sc_sat_uplink, "sc_sat_charge": sc_sat_charge,
    "sc_sat_fire": sc_sat_fire, "sc_strike_beam": sc_strike_beam, "sc_strike_impact": sc_strike_impact, "sc_strike_rumble": sc_strike_rumble,
}
DIST = {"sc_link": 16, "sc_deploy": 32, "sc_hum": 24, "sc_alarm": 96, "sc_rail_charge": 96, "sc_rail_fire": 256, "sc_rail_hit": 256,
        "sc_sat_uplink": 8, "sc_sat_charge": 48, "sc_sat_fire": 256, "sc_strike_beam": 640, "sc_strike_impact": 900, "sc_strike_rumble": 640}


def main():
    out = B.OUT
    out.mkdir(parents=True, exist_ok=True)
    only = set(sys.argv[1:])
    for name, fn in SOUNDS.items():
        if only and name not in only:
            continue
        x = B.norm(fn(), 0.95)
        x = B.limit(x, 0.92)
        x = B.fade(B.norm(B.trim(x), 0.9), 0.001, 0.08)
        B.write_ogg(x, out / f"{name}.ogg")
        print(f"{name:18s} {len(x) / SR:5.2f}s")
    sj = out.parent / "sounds.json"
    js = json.loads(sj.read_text())
    for name in SOUNDS:
        event = "sc." + name[3:]
        js[event] = {"subtitle": f"subtitles.orbital_railgun.{event}",
                     "sounds": [{"name": f"orbital_railgun:{name}", "attenuation_distance": DIST[name]}]}
    sj.write_text(json.dumps(js, indent=2))


if __name__ == "__main__":
    main()
