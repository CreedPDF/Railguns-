#!/usr/bin/env python3
"""
Sounds for the KRL (Kinetic Rail Launcher), synthesised from scratch with the DSP toolkit in build_sounds.py and
merged into sounds.json under "krl.*" (build_sounds.py keeps those entries when it rewrites the file).

  krl_load       loading the rail: loader motor, the body scraping up the rail and seating, two clamp locks, vent
  krl_mag        a round sliding into a magazine canister
  krl_refit      the integration bench: pneumatic wrench, torque click, panel seat
  krl_link       console handshake: two-tone data chirp with relay clicks
  krl_click      a heavy console switch
  krl_arm        arming: key turn, cover flip, a rising servo and a two-note warning tone
  krl_siren      pad warning siren (loop) while the motor burns on the rail
  krl_ignite     motor ignition: igniter pop, a pressure-rising whoomp into roar
  krl_burn       the motor burning on the pad (loop): low thunder, crackle, turbulent hiss
  krl_boom       liftoff and the sonic boom: the N-wave double crack, a rolling thunder tail
  krl_climb      the motor at full thrust as heard from on board (fades with altitude)
  krl_stall      burnout and the silence of the stall: wind dying, tank ticking, a low drone
  krl_wind       the freefall wind (loop): howling, rising
  krl_plasma     the plasma sheath crackling round the nose (loop)
  krl_incoming   heard on the ground: a falling shriek tearing down the sky
  krl_impact     the superheated plasma explosion: a white crack, a sub-bass slam, rolling detonation, debris
"""
import json
import sys
from pathlib import Path

import numpy as np
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_sounds as B  # noqa: E402

SR = B.SR
rng = np.random.default_rng(1307)


def bandnoise(dur, lo, hi, order=2):
    return B.filt(B.white(dur), "bandpass", [lo, hi], order)


# ---- modal / physical pieces for the loading sounds (built here from scratch, not from the shared kit)

def modal(dur, freqs, decays, amps, strike=0.002, jitter=0.0):
    """A struck object: inharmonic partials, each with its own decay, excited by a short noise strike."""
    t = B.t_axis(dur)
    x = np.zeros_like(t)
    for f, d, a in zip(freqs, decays, amps):
        f2 = f * (1 + jitter * (rng.random() - 0.5))
        x += a * np.sin(2 * np.pi * f2 * t + rng.random() * 6.28) * np.exp(-t / d)
    n = int(strike * SR)
    hit = rng.standard_normal(n) * np.linspace(1, 0, n) ** 2
    x[:n] += hit * 0.6
    return x


def scrape(dur, lo, hi, rate=38.0, depth=0.7):
    """Steel sliding on steel: band noise chopped by stick-slip pulses at an irregular rate."""
    t = B.t_axis(dur)
    n = bandnoise(dur, lo, hi, 2)
    phase = np.cumsum(rate * (1 + 0.35 * np.sin(2 * np.pi * 1.7 * t) + 0.25 * rng.standard_normal(len(t)) * 0.05)) / SR
    stick = 0.5 + 0.5 * np.sign(np.sin(2 * np.pi * phase))
    stick = B.filt(stick, "lowpass", 400, 1)
    return n * (1 - depth + depth * stick)


def motor(dur, f0, f1, mesh=13, amp=1.0):
    """An electric gear motor spinning up or down: a hum, its gear-mesh whine and brush noise."""
    t = B.t_axis(dur)
    f = f0 + (f1 - f0) * np.clip(t / dur, 0, 1) ** 0.6
    ph = 2 * np.pi * np.cumsum(f) / SR
    x = 0.5 * np.sin(ph) + 0.25 * np.sin(2 * ph) + 0.18 * np.sin(mesh * ph) + 0.08 * np.sin(mesh * 2 * ph + 1)
    x += bandnoise(dur, 2500, 7000, 2) * 0.08
    return x * amp


def hiss(dur, lo, hi, attack=0.01, tau=0.25):
    t = B.t_axis(dur)
    e = np.minimum(1, t / attack) * np.exp(-t / tau)
    return bandnoise(dur, lo, hi, 2) * e


def tube(x, f0, fb=0.82):
    """Colour a sound with a hollow tube's resonances (a feedback comb)."""
    d = max(1, int(SR / f0))
    y = np.copy(x)
    for i in range(d, len(y)):
        y[i] += fb * y[i - d]
    return y * (1 - fb)


def place_env(out, x, at, fade_in=0.02, fade_out=0.05):
    n = len(x)
    e = np.ones(n)
    a, b = int(fade_in * SR), int(fade_out * SR)
    if a > 0:
        e[:a] = np.linspace(0, 1, a)
    if b > 0:
        e[-b:] *= np.linspace(1, 0, b)
    B.place(out, x * e, at)


def krl_load():
    """Loading the launch rail: the loader arm's motor winds up, the missile body scrapes up the rail and seats with a
    heavy hollow thud, two clamp servos close with sharp locks, and the rail vents air."""
    out = B.canvas(2.9)
    place_env(out, motor(1.25, 70, 165, 11, 0.32), 0.0, 0.08, 0.25)
    place_env(out, scrape(1.05, 700, 3200, 31, 0.75) * 0.45, 0.25, 0.1, 0.12)
    place_env(out, B.filt(scrape(1.05, 120, 500, 17, 0.5), "lowpass", 600) * 0.6, 0.25, 0.1, 0.12)
    # the 7 m body seating: low tube modes and the rail's ring
    body = modal(1.4, [58, 141, 236, 377, 612], [0.42, 0.3, 0.2, 0.12, 0.07], [1.0, 0.6, 0.35, 0.25, 0.12], 0.004, 0.02)
    B.place(out, body * 0.95, 1.3)
    ring = modal(1.1, [1180, 2870, 4410], [0.35, 0.22, 0.12], [0.25, 0.18, 0.1], 0.001)
    B.place(out, ring * 0.35, 1.31)
    for k, at in enumerate((1.68, 1.9)):
        place_env(out, motor(0.16, 260 + 40 * k, 520 + 40 * k, 7, 0.25), at - 0.16, 0.01, 0.03)
        lock = modal(0.35, [1850 + 140 * k, 3120, 5240, 7600], [0.09, 0.06, 0.04, 0.02], [0.8, 0.5, 0.35, 0.2], 0.0008, 0.05)
        B.place(out, lock * 0.75, at)
        B.place(out, modal(0.3, [210 + 30 * k, 520], [0.08, 0.05], [0.6, 0.3], 0.002) * 0.6, at)
    B.place(out, hiss(0.75, 1800, 9000, 0.005, 0.22) * 0.35, 2.05)
    for k in range(5):
        B.place(out, modal(0.06, [3400 + 400 * k], [0.012], [0.25], 0.0005), 2.25 + k * 0.09 + rng.random() * 0.03)
    return B.room(out, 0.7, 0.18)


def krl_mag():
    """A round sliding into a magazine canister: a hollow rushing slide, a soft-capped thunk, a detent and a status tick."""
    out = B.canvas(1.4)
    sl = tube(bandnoise(0.5, 300, 4000, 2), 260, 0.75) * np.linspace(0.3, 1, int(0.5 * SR)) * 0.8
    place_env(out, sl, 0.0, 0.03, 0.04)
    B.place(out, modal(0.7, [92, 233, 410, 690], [0.22, 0.15, 0.09, 0.05], [1.0, 0.5, 0.3, 0.15], 0.003) * 0.9, 0.5)
    B.place(out, B.filt(modal(0.25, [160], [0.05], [1.0], 0.006), "lowpass", 800) * 0.5, 0.505)
    B.place(out, modal(0.15, [2650, 4100, 6200], [0.035, 0.025, 0.015], [0.6, 0.4, 0.2], 0.0006) * 0.6, 0.66)
    B.place(out, modal(0.15, [2900, 4450], [0.03, 0.02], [0.5, 0.3], 0.0006) * 0.5, 0.71)
    t = B.t_axis(0.07)
    B.place(out, np.sin(2 * np.pi * 1975 * t) * np.exp(-t / 0.02) * 0.12, 0.92)
    return B.room(out, 0.45, 0.14)


def krl_refit():
    """Refit at the integration bench: a pneumatic wrench runs down three fasteners, then a torque click and a panel seat."""
    out = B.canvas(2.0)
    for k in range(3):
        at = 0.05 + k * 0.42
        place_env(out, motor(0.26, 900, 1300, 5, 0.22), at, 0.005, 0.04)
        place_env(out, hiss(0.26, 2500, 9000, 0.004, 0.3) * 0.25, at, 0.005, 0.04)
        for j in range(4):
            B.place(out, modal(0.04, [2300 + 180 * j], [0.008], [0.3], 0.0004), at + 0.04 + j * 0.05)
    B.place(out, modal(0.2, [3300, 5100], [0.04, 0.025], [0.7, 0.4], 0.0006) * 0.6, 1.4)
    B.place(out, modal(0.6, [180, 470, 980], [0.14, 0.08, 0.05], [0.9, 0.4, 0.2], 0.003) * 0.7, 1.55)
    return B.room(out, 0.5, 0.15)


def krl_link():
    out = B.canvas(0.9)
    B.place(out, B.beep([880, 1320], 0.09, "tri", 0.6), 0.0)
    B.place(out, B.beep([1320, 1760], 0.09, "tri", 0.6), 0.12)
    for k in range(6):
        B.place(out, B.filt(B.white(0.004), "highpass", 3000) * B.decay(0.004, 0.001) * 0.5, 0.26 + k * 0.035)
    B.place(out, B.beep([1760], 0.18, "sine", 0.5), 0.5)
    return B.room(out, 0.4, 0.15)


def krl_click():
    x = B.mix(B.metal(900, 0.12, 0.03, 1.4) * 0.5, B.filt(B.white(0.01), "highpass", 2000) * B.decay(0.01, 0.002))
    return B.room(x, 0.3, 0.12)


def krl_arm():
    out = B.canvas(2.0)
    B.place(out, B.ratchet(0.2, 40, 1800, 0.6), 0.0)
    B.place(out, B.latch(0.9, 520), 0.25)
    B.place(out, B.servo(0.6, 300, 900, 9, 0.5), 0.35)
    tone = np.concatenate([B.osc(660, 0.22, "square"), B.osc(495, 0.22, "square")]) * 0.25
    tone = B.filt(tone, "lowpass", 3500)
    B.place(out, tone, 1.0)
    B.place(out, tone, 1.5)
    return B.room(out, 0.6, 0.2)


def krl_siren():
    dur = 4.0
    t = B.t_axis(dur)
    f = 520 + 260 * (0.5 - 0.5 * np.cos(2 * np.pi * t / 2.0))
    x = B.unison(f, dur, 3, 0.004, "saw")
    x = B.filt(x, "bandpass", [300, 3000]) * 0.5
    x = B.room(x, 1.4, 0.4)[: B.n_of(dur)]
    return B.make_loop(x, 0.3)


def roar(dur, level=1.0, bright=1.0):
    """Rocket motor: thunderous low rumble, turbulent mid roar, crackle and a hiss on top."""
    lo = B.filt(B.brown(dur), "lowpass", 140) * 1.2
    mid = B.filt(B.white(dur), "bandpass", [180, 1400], 2)
    flutter = 1 + 0.35 * B.filt(rng.standard_normal(B.n_of(dur)), "lowpass", 18)
    hiss = B.filt(B.white(dur), "highpass", 3500) * 0.18 * bright
    cr = B.crackle(dur, 260, 0.002, 700) * 0.9
    return B.mband_sat((lo + mid * 0.55 * flutter + hiss + cr) * level, (3.0, 1.8, 1.2))


def krl_ignite():
    out = B.canvas(4.5)
    B.place(out, B.filt(B.white(0.05), "bandpass", [600, 5000]) * B.decay(0.05, 0.01) * 1.2, 0.0)
    B.place(out, B.kick(70, 28, 1.2, 0.5, 0.2), 0.12)
    r = roar(4.2) * np.minimum(1, np.linspace(0, 3.0, B.n_of(4.2)))
    B.place(out, r * 0.8, 0.15)
    return B.room(out, 1.8, 0.3)


def krl_burn():
    dur = 5.0
    x = roar(dur, 1.0)
    return B.make_loop(x, 0.6)


def krl_boom():
    """The sonic boom at liftoff as you hear it on the ground: a sharp double BANG (the N-wave's two shocks), each a
    broadband crack with a real mid-bass punch so it reads on any speakers, slapback echoes off the terrain and a
    rolling thunder tail."""
    out = B.canvas(6.5)

    def bang(gain, dark=False):
        snap = B.filt(B.white(0.006), "highpass", 1800) * B.decay(0.006, 0.0012) * 2.5
        crack = B.filt(B.white(0.09), "bandpass", [250, 5200], 2) * B.decay(0.09, 0.014) * 2.4
        body = B.filt(B.white(0.25), "bandpass", [90, 700], 2) * B.decay(0.25, 0.05) * 1.6
        thump = B.osc(B.glide(150, 48, 0.4, 0.3), 0.4) * B.decay(0.4, 0.085) * 1.5
        x = B.mix(snap, crack, body, thump) * gain
        return B.filt(x, "lowpass", 1400) if dark else x

    B.place(out, bang(1.0), 0.0)
    B.place(out, bang(0.9), 0.085)
    # slapback off the ground and the hills
    B.place(out, bang(0.32, True), 0.42)
    B.place(out, bang(0.2, True), 0.5)
    B.place(out, bang(0.14, True), 1.05)
    thunder = B.filt(B.brown(6.0), "lowpass", 380) * B.decay(6.0, 1.3, 0.04) * 0.9
    rumble = B.filt(B.white(6.0), "bandpass", [70, 900], 2) * B.decay(6.0, 0.9, 0.04) * 0.45
    B.place(out, B.mix(thunder, rumble), 0.06)
    return B.room(B.mband_sat(out, (2.4, 2.2, 1.4)), 2.2, 0.28)


def krl_climb():
    dur = 11.0
    t = B.t_axis(dur)
    level = np.exp(-t / 5.0)
    x = roar(dur, 1.0, 1.4) * level
    whine = B.osc(B.glide(180, 420, dur, 1.0), dur, "saw") * 0.05 * level
    return B.fade(x + B.filt(whine, "lowpass", 2500), 0.05, 1.5)


def krl_stall():
    dur = 4.0
    t = B.t_axis(dur)
    wind = B.sweep(B.white(dur), "bandpass", 900, 220, q=2.0) * np.exp(-t / 1.4) * 0.6
    drone = B.osc(48, dur) * 0.2 * (1 - np.exp(-t / 0.8))
    ticks = B.canvas(dur)
    for k in range(7):
        B.place(ticks, B.metal(1600 + 300 * k, 0.08, 0.02) * 0.15, 0.7 + k * 0.45 + rng.random() * 0.1)
    return B.room(B.mix(wind, drone, ticks), 1.2, 0.3)


def krl_wind():
    dur = 6.0
    t = B.t_axis(dur)
    base = B.white(dur)
    howl = sum(B.filt(base, "bandpass", [f * 0.94, f * 1.06], 2) * a for f, a in ((300, 0.9), (520, 0.6), (870, 0.4), (1400, 0.25)))
    gust = 1 + 0.4 * B.filt(rng.standard_normal(B.n_of(dur)), "lowpass", 2.5)
    rush = B.filt(B.white(dur), "bandpass", [200, 6000], 2) * 0.5
    x = (howl + rush) * gust
    return B.make_loop(x, 0.8)


def krl_plasma():
    dur = 5.0
    cr = B.crackle(dur, 900, 0.0012, 1800) * 1.2
    sizzle = B.filt(B.white(dur), "highpass", 5000) * 0.25
    buzz = B.filt(B.osc(B.glide(95, 105, dur), dur, "saw"), "bandpass", [150, 2500]) * 0.2
    return B.make_loop(cr + sizzle + buzz, 0.5)


def krl_incoming():
    dur = 4.2
    f = B.glide(2600, 380, dur, 1.6)
    shriek = B.unison(f, dur, 4, 0.02, "saw")
    shriek = B.filt(shriek, "bandpass", [250, 5000]) * np.linspace(0.1, 1.0, B.n_of(dur)) ** 1.5
    tear = B.filt(B.white(dur), "bandpass", [400, 4000]) * np.linspace(0.0, 0.8, B.n_of(dur)) ** 2
    return B.fade(B.room(shriek * 0.4 + tear, 1.0, 0.25), 0.4, 0.05)


def krl_impact():
    out = B.canvas(9.0)
    flash = B.filt(B.white(0.02), "highpass", 1500) * B.decay(0.02, 0.004) * 2.0
    B.place(out, flash, 0.0)
    B.place(out, B.kick(140, 18, 2.5, 0.9, 0.8) * 1.6, 0.0)
    det = B.filt(B.brown(8.0), "lowpass", 300) * B.decay(8.0, 2.2, 0.03) * 1.3
    mid = B.filt(B.white(6.0), "bandpass", [120, 1800]) * B.decay(6.0, 1.0, 0.02) * 0.8
    B.place(out, B.mix(det, mid), 0.01)
    B.place(out, B.crackle(5.0, 400, 0.002, 900) * np.exp(-B.t_axis(5.0) / 1.8) * 1.2, 0.05)
    debris = B.canvas(6.0)
    for k in range(70):
        B.place(debris, B.metal(200 + rng.random() * 900, 0.3, 0.08) * 0.06 * rng.random(), 0.5 + rng.random() * 4.5)
    B.place(out, debris, 0.0)
    return B.room(B.mband_sat(out, (3.5, 2.2, 1.3)), 3.0, 0.38)


SOUNDS = {
    "krl_load": krl_load, "krl_mag": krl_mag, "krl_refit": krl_refit, "krl_link": krl_link, "krl_click": krl_click, "krl_arm": krl_arm, "krl_siren": krl_siren,
    "krl_ignite": krl_ignite, "krl_burn": krl_burn, "krl_boom": krl_boom, "krl_climb": krl_climb, "krl_stall": krl_stall,
    "krl_wind": krl_wind, "krl_plasma": krl_plasma, "krl_incoming": krl_incoming, "krl_impact": krl_impact,
}
LOOPS = {"krl_siren", "krl_burn", "krl_wind", "krl_plasma"}
DIST = {"krl_load": 24, "krl_mag": 16, "krl_refit": 16, "krl_link": 16, "krl_click": 12, "krl_arm": 16, "krl_siren": 96, "krl_ignite": 160, "krl_burn": 160,
        "krl_boom": 512, "krl_climb": 256, "krl_stall": 64, "krl_wind": 48, "krl_plasma": 48, "krl_incoming": 256, "krl_impact": 640}


def main():
    out = B.OUT
    out.mkdir(parents=True, exist_ok=True)
    only = set(sys.argv[1:])
    for name, fn in SOUNDS.items():
        if only and name not in only:
            continue
        x = B.norm(fn(), 0.95)
        x = B.limit(x, 0.92)
        if name not in LOOPS:
            x = B.fade(B.norm(B.trim(x), 0.9), 0.001, 0.08)
        else:
            x = B.norm(x, 0.85)
        B.write_ogg(x, out / f"{name}.ogg")
        print(f"{name:14s} {len(x) / SR:5.2f}s")
    sj = out.parent / "sounds.json"
    js = json.loads(sj.read_text())
    for name in SOUNDS:
        event = "krl." + name[4:]
        js[event] = {"subtitle": f"subtitles.orbital_railgun.{event}",
                     "sounds": [{"name": f"orbital_railgun:{name}", "attenuation_distance": DIST[name]}]}
    sj.write_text(json.dumps(js, indent=2))


if __name__ == "__main__":
    main()
