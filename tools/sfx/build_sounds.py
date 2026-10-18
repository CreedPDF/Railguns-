#!/usr/bin/env python3
"""
Synthesizes every Orbital Railgun sound effect from scratch (no samples, nothing from Minecraft)
and encodes them to mono Ogg Vorbis for assets/orbital_railgun/sounds/.

Sound design approach (v3):
  * Impacts are layered: a pitch-dropping sub "kick" for weight, a broadband transient for the
    crack, modal metal resonances (sums of inharmonic damped partials) for the body, and a
    filtered-noise tail for air and debris.
  * Machines are built from parts that each have a physical story: servo motors (detuned saw
    stacks with gear-whine harmonics), hydraulics (band-passed hiss with pressure flutter),
    latches (modal clacks), ratchets (trains of tiny modal ticks).
  * Energy sounds use unison detuned oscillators, FM, ring modulation and electric crackle.
  * Everything is finished with multiband saturation, a look-ahead-free soft limiter and a
    synthetic room: early reflections + a damped late tail whose high end decays faster.
  * Every machine sound is timed against the animation timeline in tools/blender/build_models.py.

Usage:  python3 tools/sfx/build_sounds.py            (needs numpy, scipy, ffmpeg with libvorbis)
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
rng = np.random.default_rng(90210)

# =============================================================================================
# DSP toolkit
# =============================================================================================


def n_of(dur):
    return max(1, int(round(dur * SR)))


def t_axis(dur):
    return np.arange(n_of(dur)) / SR


def white(dur):
    return rng.standard_normal(n_of(dur))


def brown(dur):
    x = np.cumsum(rng.standard_normal(n_of(dur)))
    x = signal.sosfilt(signal.butter(1, 12, "hp", fs=SR, output="sos"), x)
    return x / (np.abs(x).max() + 1e-9)


def filt(x, kind, freq, order=4):
    if np.ndim(freq) == 0:
        freq = min(float(freq), SR / 2 - 200)
    else:
        freq = [max(10.0, freq[0]), min(freq[1], SR / 2 - 200)]
    sos = signal.butter(order, freq, kind, fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def sweep(x, kind, f0, f1, blocks=240, order=2, q=1.6):
    """Time-varying Butterworth (block-wise with carried state). Exponential cutoff path."""
    out = np.zeros_like(x)
    edges = np.linspace(0, len(x), blocks + 1).astype(int)
    zi = None
    for i in range(blocks):
        a, b = edges[i], edges[i + 1]
        if b <= a:
            continue
        f = f0 * (f1 / f0) ** (i / max(1, blocks - 1))
        if kind == "bandpass":
            sos = signal.butter(order, [max(20, f / q), min(f * q, SR / 2 - 200)], "bandpass", fs=SR, output="sos")
        else:
            sos = signal.butter(order, min(f, SR / 2 - 200), kind, fs=SR, output="sos")
        if zi is None:
            zi = np.zeros((sos.shape[0], 2))
        out[a:b], zi = signal.sosfilt(sos, x[a:b], zi=zi)
    return out


def phase_of(freq, n):
    f = np.full(n, float(freq)) if np.ndim(freq) == 0 else np.resize(np.asarray(freq, dtype=float), n)
    return 2 * np.pi * np.cumsum(f) / SR


def osc(freq, dur, wave="sine", ph0=0.0):
    n = n_of(dur)
    ph = phase_of(freq, n) + ph0
    if wave == "sine":
        return np.sin(ph)
    if wave == "saw":
        return sum(np.sin(k * ph) / k for k in range(1, 14)) * 0.55
    if wave == "square":
        return sum(np.sin(k * ph) / k for k in range(1, 16, 2)) * 0.85
    if wave == "tri":
        return sum(((-1) ** ((k - 1) // 2)) * np.sin(k * ph) / (k * k) for k in range(1, 12, 2)) * 0.8
    raise ValueError(wave)


def unison(freq, dur, voices=5, detune=0.012, wave="saw"):
    """Detuned oscillator stack (supersaw-style) for thick, wide energy tones."""
    out = 0
    for v in range(voices):
        d = 1 + detune * (v - (voices - 1) / 2) / max(1, (voices - 1) / 2)
        f = freq * d
        out = out + osc(f, dur, wave, ph0=rng.random() * 6.28)
    return out / voices


def fm(carrier, mod_ratio, index, dur):
    n = n_of(dur)
    cph = phase_of(carrier, n)
    mph = phase_of(np.asarray(carrier) * mod_ratio if np.ndim(carrier) else carrier * mod_ratio, n)
    idx = np.resize(np.asarray(index, dtype=float), n) if np.ndim(index) else index
    return np.sin(cph + idx * np.sin(mph))


def glide(f0, f1, dur, shape=1.0):
    p = np.linspace(0, 1, n_of(dur)) ** shape
    return f0 * (f1 / f0) ** p


def env(dur, a=0.005, d=0.1, s=0.6, r=0.2):
    n = n_of(dur)
    e = np.zeros(n)
    na, nd, nr = int(a * SR), int(d * SR), int(r * SR)
    ns = max(0, n - na - nd - nr)
    e[:na] = np.linspace(0, 1, na, endpoint=False)
    e[na:na + nd] = np.linspace(1, s, nd, endpoint=False)
    e[na + nd:na + nd + ns] = s
    e[na + nd + ns:] = np.linspace(s, 0, n - (na + nd + ns))
    return e


def decay(dur, tau, attack=0.0015):
    t = t_axis(dur)
    e = np.exp(-t / tau)
    na = max(1, int(attack * SR))
    e[:na] *= np.linspace(0, 1, na)
    return e


def place(buf, x, at, gain=1.0):
    i = int(at * SR)
    if i >= len(buf):
        return buf
    j = min(len(buf), i + len(x))
    buf[i:j] += x[: j - i] * gain
    return buf


def canvas(dur):
    return np.zeros(n_of(dur))


def mix(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[: len(p)] += p
    return out


def sat(x, drive=2.0):
    return np.tanh(x * drive) / np.tanh(drive)


def mband_sat(x, drives=(2.5, 1.6, 1.2), xover=(180, 2200)):
    """Split into low / mid / high and saturate each band separately (punch without mud)."""
    lo = filt(x, "lowpass", xover[0], 4)
    hi = filt(x, "highpass", xover[1], 4)
    mid = x - lo - hi
    return sat(lo, drives[0]) * 1.0 + sat(mid, drives[1]) * 0.9 + sat(hi, drives[2]) * 0.8


def limit(x, ceiling=0.92, release=0.08):
    """Soft envelope-follower limiter."""
    a = np.abs(x)
    rel = np.exp(-1 / (release * SR))
    e = np.empty_like(a)
    acc = 0.0
    for i in range(0, len(a), 64):
        blk = a[i:i + 64].max()
        acc = blk if blk > acc else acc * rel ** 64 + blk * (1 - rel ** 64)
        e[i:i + 64] = acc
    gain = np.minimum(1.0, ceiling / np.maximum(e, 1e-9))
    # zero-phase smoothing: no start-up transient (keeps loops seamless) and a little look-ahead
    gain = signal.sosfiltfilt(signal.butter(1, 60, "lowpass", fs=SR, output="sos"), gain)
    gain = np.minimum(gain, ceiling / np.maximum(a, 1e-9))
    return np.clip(x * gain, -1, 1)


def room(x, size=1.6, wet=0.3, damp=5000, early=0.03):
    """Early reflections (sparse taps) + late tail whose highs decay faster than its lows."""
    n_ir = n_of(size)
    t = np.arange(n_ir) / SR
    lo = filt(rng.standard_normal(n_ir), "lowpass", 900) * np.exp(-t / (size * 0.36))
    hi = filt(rng.standard_normal(n_ir), "highpass", 900) * np.exp(-t / (size * 0.16))
    tail = filt(lo + 0.6 * hi, "lowpass", damp, 2)
    ir = np.zeros(n_ir)
    for k in range(9):
        tap = int((early * (0.4 + k * 0.3) + rng.random() * 0.004) * SR)
        if tap < n_ir:
            ir[tap] += (0.55 ** k) * (1 if rng.random() > 0.5 else -1)
    pre = int(early * 1.5 * SR)
    ir[pre:] += tail[: n_ir - pre] * 0.25
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    wet_sig = signal.fftconvolve(x, ir)[: len(x) + n_ir]
    out = np.zeros(len(x) + n_ir)
    out[: len(x)] += x * (1 - wet * 0.5)
    out[: len(wet_sig)] += wet_sig[: len(out)] * wet
    return out


def crackle(dur, rate, tau=0.0015, hp=2500, amp_env=None):
    """Random electric / debris impulses (Poisson process) each with a tiny decaying burst."""
    out = canvas(dur)
    count = rng.poisson(rate * dur)
    for _ in range(count):
        at = rng.random() * dur
        g = rng.random() ** 2
        if amp_env is not None:
            g *= amp_env[min(len(amp_env) - 1, int(at * SR))]
        place(out, white(tau * 6) * decay(tau * 6, tau), at, g)
    return filt(out, "highpass", hp, 2)


def modal(freqs, taus, amps, dur, strike=0.002):
    """Modal synthesis: a struck object is a sum of damped sinusoids at inharmonic ratios."""
    t = t_axis(dur)
    out = np.zeros_like(t)
    for f, tau, a in zip(freqs, taus, amps):
        out += a * np.sin(2 * np.pi * f * t + rng.random() * 6.28) * np.exp(-t / tau)
    exc = filt(white(strike * 4), "highpass", 1500) * decay(strike * 4, strike)
    out[: len(exc)] += exc * 0.4
    return out


def metal(base, dur=1.2, tau=0.5, bright=1.0):
    ratios = [1.0, 1.59, 2.14, 2.30, 2.65, 3.16, 3.91, 4.52, 5.41]
    freqs = [base * r for r in ratios]
    taus = [tau / (1 + 0.45 * i) for i in range(len(ratios))]
    amps = [1.0 / (1 + i * (0.7 / bright)) for i in range(len(ratios))]
    return modal(freqs, taus, amps, dur)


def kick(f0=120, f1=32, dur=1.0, tau=0.35, click=0.6):
    """Sub thump: fast pitch drop, plus a short click so it reads on small speakers."""
    body = osc(glide(f0, f1, dur, 0.25), dur) * decay(dur, tau)
    c = filt(white(0.012), "bandpass", [1500, 6000]) * decay(0.012, 0.002)
    out = body.copy()
    out[: len(c)] += c * click
    return out


def norm(x, peak=0.9):
    m = np.abs(x).max()
    return x * (peak / m) if m > 1e-9 else x


def fade(x, fin=0.002, fout=0.05):
    x = x.copy()
    a, b = n_of(fin), min(len(x), n_of(fout))
    x[:a] *= np.linspace(0, 1, a)
    x[-b:] *= np.linspace(1, 0, b)
    return x


def trim(x, thresh_db=-60):
    thr = 10 ** (thresh_db / 20) * np.abs(x).max()
    idx = np.where(np.abs(x) > thr)[0]
    return x[: idx[-1] + int(0.05 * SR)] if len(idx) else x


def make_loop(x, xfade=0.25):
    """Seamless loop: crossfade the tail into the head (equal power)."""
    n = n_of(xfade)
    body = x[:-n].copy()
    w = np.linspace(0, np.pi / 2, n)
    body[:n] = body[:n] * np.sin(w) + x[-n:] * np.cos(w)
    return body


# =============================================================================================
# Machine parts
# =============================================================================================


def servo(dur, f0, f1, gear=11, level=1.0):
    """Electric motor: detuned saw stack + gear-mesh whine + brush noise, soft start/stop."""
    f = glide(f0, f1, dur, 0.8)
    tone = unison(f, dur, 3, 0.006, "saw")
    whine = osc(f * gear, dur) * 0.18
    brush = filt(white(dur), "bandpass", [2500, 7000]) * 0.08 * (1 + 0.5 * np.sin(phase_of(f * 2, n_of(dur))))
    x = filt(tone, "lowpass", 2200) * 0.5 + whine + brush
    return x * env(dur, 0.06, 0.1, 0.9, 0.12) * level


def hydraulic(dur, f=3200, level=1.0):
    """Pressurized fluid: band-passed hiss with pressure flutter and a low pump pulse."""
    n = n_of(dur)
    hiss = filt(white(dur), "bandpass", [f * 0.5, f * 1.8])
    flutter = 1 + 0.35 * np.sin(2 * np.pi * 21 * np.arange(n) / SR + rng.random() * 6)
    pump = osc(48, dur) * (0.5 + 0.5 * np.sin(2 * np.pi * 6 * np.arange(n) / SR)) * 0.4
    return (hiss * flutter * 0.5 + pump) * env(dur, 0.03, 0.05, 0.85, dur * 0.35) * level


def latch(level=1.0, base=420):
    """Heavy latch / lock: modal clack + low thud."""
    d = 0.5
    x = metal(base, d, 0.07, 1.5) * 0.7 + kick(160, 60, d, 0.05, 0.2) * 0.8
    return x * level


def clank(level=1.0, base=180, tau=0.35):
    """Big steel part seating home: thud + ringing body."""
    d = 1.4
    return (kick(110, 45, d, 0.12, 0.4) * 0.9 + metal(base, d, tau, 1.0) * 0.45) * level


def ratchet(dur, rate, base=1400, level=1.0):
    out = canvas(dur)
    n = int(dur * rate)
    for i in range(n):
        tick = metal(base * (0.95 + rng.random() * 0.1), 0.05, 0.008, 2.0)
        place(out, tick, i / rate, 0.5 + 0.5 * rng.random())
    return out * level


def steam(dur, level=1.0):
    return filt(white(dur), "bandpass", [2500, 9000]) * env(dur, 0.01, 0.1, 0.6, dur * 0.6) * level


def beep(freqs, dur, wave="tri", level=1.0):
    out = sum(osc(f, dur, wave) for f in freqs) / len(freqs)
    return out * env(dur, 0.004, 0.04, 0.6, dur * 0.4) * level


def power_hum(dur, f=55, level=1.0):
    """Mains-style hum with harmonics, slowly beating."""
    t = t_axis(dur)
    x = sum(np.sin(2 * np.pi * f * k * t + rng.random()) / k for k in (1, 2, 3, 5))
    return x * (0.85 + 0.15 * np.sin(2 * np.pi * 0.7 * t)) * level


def zap(dur=0.12, level=1.0):
    """Electric arc: ring-modulated noise with a fast pitch fall."""
    n = osc(glide(3000, 400, dur), dur, "square")
    x = filt(white(dur), "highpass", 1500) * n
    return x * decay(dur, dur * 0.3) * level


# =============================================================================================
# UI
# =============================================================================================


def ui_click():
    d = 0.14
    x = metal(2600, d, 0.012, 2.0) * 0.6 + osc(glide(1900, 1300, d), d, "tri") * decay(d, 0.02) * 0.5
    x += filt(white(d), "highpass", 5000) * decay(d, 0.004) * 0.3
    return room(x, 0.25, 0.12)


def ui_countdown_beep():
    d = 0.45
    x = canvas(d)
    place(x, fm(1760, 2.0, 1.2, 0.09) * env(0.09, 0.002, 0.02, 0.7, 0.04), 0)
    place(x, fm(1760, 2.0, 0.6, 0.18) * env(0.18, 0.002, 0.03, 0.6, 0.1) * 0.8, 0.11)
    place(x, osc(110, 0.2) * decay(0.2, 0.05) * 0.4, 0.0)
    return room(x, 0.5, 0.18)


def ui_denied():
    d = 0.6
    x = canvas(d)
    for i, f in enumerate((233, 175)):
        tone = filt(unison(f, 0.18, 3, 0.01, "square"), "lowpass", 1400)
        place(x, tone * env(0.18, 0.003, 0.03, 0.8, 0.05) * 0.6, i * 0.2)
    place(x, filt(white(0.4), "bandpass", [200, 900]) * decay(0.4, 0.08) * 0.2, 0)
    return room(x, 0.4, 0.15)


def ui_target_lock():
    d = 1.1
    x = canvas(d)
    for i, f in enumerate((880, 1175, 1568, 2093)):
        place(x, beep([f, f * 1.003], 0.09, "tri", 0.6), i * 0.06)
    place(x, latch(0.8, 900), 0.27)
    lock = fm(2093, 3.01, 2.0, 0.5) * env(0.5, 0.002, 0.05, 0.4, 0.3) * 0.35
    place(x, lock, 0.3)
    place(x, filt(white(0.3), "highpass", 6000) * decay(0.3, 0.05) * 0.2, 0.27)
    return room(x, 0.7, 0.22)


def ui_uplink():
    d = 2.2
    x = canvas(d)
    # modem warble: data bursts
    for i in range(14):
        f = rng.choice([1200, 1800, 2400, 2100, 1500])
        place(x, osc(f, 0.04, "square") * env(0.04, 0.002, 0.005, 0.8, 0.01) * 0.12, 0.05 + i * 0.045)
    up = osc(glide(180, 1400, 0.9, 1.6), 0.9, "saw")
    place(x, filt(up, "lowpass", 3000) * env(0.9, 0.3, 0.1, 0.7, 0.2) * 0.35, 0.5)
    chord = sum(fm(f, 1.5, 0.6, 1.0) for f in (523.3, 659.3, 784.0, 1046.5)) / 4
    place(x, chord * env(1.0, 0.01, 0.2, 0.5, 0.6) * 0.6, 1.25)
    place(x, kick(90, 45, 0.6, 0.12, 0.3) * 0.5, 1.25)
    return room(x, 1.2, 0.3)


def ui_tablet_open():
    d = 1.0
    x = canvas(d)
    place(x, sweep(white(0.45), "bandpass", 400, 6000) * env(0.45, 0.25, 0.05, 0.6, 0.12) * 0.35, 0)
    place(x, servo(0.35, 300, 520, 9, 0.3), 0.02)
    for i, f in enumerate((659.3, 987.8, 1318.5)):
        place(x, beep([f], 0.13, "tri", 0.5), 0.33 + i * 0.07)
    place(x, latch(0.3, 1600), 0.3)
    return room(x, 0.6, 0.2)


def ui_tablet_close():
    d = 0.7
    x = canvas(d)
    place(x, sweep(white(0.35), "bandpass", 6000, 400) * env(0.35, 0.02, 0.05, 0.6, 0.2) * 0.35, 0)
    place(x, beep([1318.5, 659.3], 0.12, "tri", 0.45), 0.02)
    place(x, latch(0.35, 1300), 0.28)
    return room(x, 0.5, 0.2)


# =============================================================================================
# Railgun
# =============================================================================================


def railgun_reentry():
    """Seamless loop: plasma sheath roar + buffeting + crackle (pitch/volume driven in code)."""
    d = 4.25
    t = t_axis(d)
    roar = filt(brown(d), "lowpass", 700) * 1.1 + filt(white(d), "bandpass", [500, 3000]) * 0.35
    buffet = 0.7 + 0.3 * np.sin(2 * np.pi * 3.2 * t) * np.sin(2 * np.pi * 0.9 * t)
    whistle = osc(glide(900, 860, d), d) * 0.05
    crack = crackle(d, 90, 0.002, 1500) * 0.5
    x = mband_sat(roar * buffet + whistle + crack, (2.2, 1.6, 1.2))
    return make_loop(x, 0.25)


def railgun_land():
    d = 5.0
    x = canvas(d)
    place(x, kick(140, 28, 2.5, 0.6, 0.9) * 1.6, 0)
    place(x, filt(white(0.5), "lowpass", 4000) * decay(0.5, 0.06) * 1.0, 0)
    place(x, metal(95, 3.0, 1.2, 0.8) * 0.6, 0.005)
    place(x, metal(260, 1.6, 0.5, 1.2) * 0.3, 0.01)
    deb_env = decay(2.5, 0.6)
    place(x, crackle(2.5, 140, 0.003, 800, deb_env) * 0.9, 0.05)
    rumble = filt(brown(4.5), "lowpass", 160) * env(4.5, 0.05, 0.4, 0.5, 3.5) * 0.9
    place(x, rumble, 0.02)
    place(x, steam(1.6, 0.25), 0.5)
    place(x, latch(0.8, 380), 1.1)
    return room(mband_sat(x, (2.8, 1.8, 1.3)), 2.4, 0.35, 3500)


def railgun_deploy():
    """Orbital release confirmation (tablet CALL DOWN)."""
    d = 2.6
    x = canvas(d)
    place(x, kick(70, 35, 1.2, 0.35, 0.2) * 0.8, 0)
    place(x, sweep(white(1.6), "bandpass", 300, 5000) * env(1.6, 0.6, 0.2, 0.6, 0.7) * 0.3, 0.1)
    for i in range(3):
        place(x, fm(440 * (1.5 ** i), 2.0, 1.0, 0.3) * env(0.3, 0.005, 0.05, 0.5, 0.2) * 0.4, 0.4 + i * 0.22)
    return room(x, 1.8, 0.35)


def railgun_charge_loop():
    """Seamless 2 s capacitor loop; code raises its pitch 0.5x -> 2x as the charge fills."""
    d = 2.25
    t = t_axis(d)
    base = 220.0
    # every modulation completes whole cycles in 2 s so the loop point is clean
    whine = unison(base * 2, d, 5, 0.008, "saw")
    whine = filt(whine, "bandpass", [300, 4000]) * 0.35
    sub = osc(base / 2, d) * 0.35
    pulse = 0.65 + 0.35 * np.sin(2 * np.pi * 8 * t) ** 2
    ring = fm(base * 4, 1.5, 1.5 + np.sin(2 * np.pi * 1 * t), d) * 0.12
    hum = power_hum(d, base / 4, 0.25)
    sparks = crackle(d, 40, 0.0012, 3500) * 0.35
    x = (whine + ring) * pulse + sub + hum + sparks
    return make_loop(mband_sat(x, (1.8, 1.5, 1.2)), 0.25)


def railgun_fire():
    """
    The shot (FIRE_TICKS 70 = 3.5 s): an ignition crack and sub slam, a pitch-dropping
    electromagnetic 'THOOM', then a sustained, churning plasma burn with arcs, then collapse.
    """
    d = 6.5
    x = canvas(d)
    # ignition
    place(x, kick(180, 24, 3.0, 0.9, 1.0) * 2.0, 0)
    place(x, filt(white(0.25), "highpass", 800) * decay(0.25, 0.03) * 1.6, 0)
    place(x, metal(70, 3.5, 1.4, 0.7) * 0.5, 0)
    thoom = unison(glide(420, 38, 2.2, 0.35), 2.2, 7, 0.02, "saw")
    place(x, filt(thoom, "lowpass", 2600) * env(2.2, 0.004, 0.25, 0.6, 1.6) * 1.1, 0.0)
    snap = fm(glide(5000, 300, 0.4, 0.5), 1.41, 4.0, 0.4) * decay(0.4, 0.07)
    place(x, snap * 0.7, 0)
    # sustained burn
    bd = 3.6
    tb = t_axis(bd)
    burn_env = env(bd, 0.08, 0.3, 0.85, 1.4)
    roar = filt(brown(bd), "lowpass", 900) * 1.2 + filt(white(bd), "bandpass", [700, 5000]) * 0.4
    churn = 0.7 + 0.3 * np.sin(2 * np.pi * 9 * tb) * np.sin(2 * np.pi * 2.3 * tb)
    hum = power_hum(bd, 46, 0.8) * (0.8 + 0.2 * np.sin(2 * np.pi * 13 * tb))
    plasma = fm(glide(110, 70, bd), 2.5, 3 + 2 * np.sin(2 * np.pi * 4 * tb), bd) * 0.35
    arcs = crackle(bd, 220, 0.0018, 1800, burn_env) * 1.1
    burn = (roar * churn + hum + plasma + arcs) * burn_env
    place(x, burn, 0.08)
    for i in range(12):
        at = 0.15 + rng.random() * 2.8
        place(x, zap(0.08 + rng.random() * 0.12, (0.5 + rng.random() * 0.4) * (1 - at / 3.4)), at)
    # collapse
    place(x, osc(glide(300, 40, 1.4, 0.6), 1.4, "saw") * env(1.4, 0.05, 0.2, 0.6, 1.0) * 0.4, 3.0)
    place(x, steam(2.2, 0.35), 3.4)
    return room(mband_sat(x, (3.0, 2.0, 1.4)), 3.2, 0.32, 3200)


def railgun_impact():
    """Main blast plus the two ring blasts at 0.4 s and 0.8 s, then a long debris rumble."""
    d = 8.0
    x = canvas(d)

    def blast(scale):
        b = mix(kick(150, 22, 3.5, 1.0 * scale, 1.0) * 2.0,
                filt(white(1.2), "lowpass", 3500) * decay(1.2, 0.18 * scale) * 1.4,
                filt(white(0.15), "highpass", 1500) * decay(0.15, 0.02) * 1.2)
        return b

    place(x, blast(1.0), 0)
    place(x, blast(0.6) * 0.55, 0.4)
    place(x, blast(0.6) * 0.5, 0.8)
    deb = crackle(5.0, 180, 0.003, 700, decay(5.0, 1.3)) * 1.2
    place(x, deb, 0.05)
    rumble = filt(brown(7.5), "lowpass", 140) * env(7.5, 0.05, 0.6, 0.55, 5.5) * 1.4
    place(x, rumble, 0.02)
    place(x, sweep(white(3.0), "lowpass", 6000, 300) * decay(3.0, 0.9) * 0.6, 0.02)
    return room(mband_sat(x, (3.2, 2.0, 1.4)), 3.8, 0.4, 2600)


def railgun_power_down():
    d = 3.2
    x = canvas(d)
    place(x, unison(glide(900, 60, 2.6, 0.6), 2.6, 5, 0.01, "saw") * env(2.6, 0.01, 0.3, 0.5, 2.0) * 0.4, 0)
    place(x, latch(0.9, 520), 0.05)
    place(x, latch(0.7, 610), 0.35)
    place(x, steam(2.4, 0.55), 0.2)
    place(x, kick(80, 40, 0.8, 0.2, 0.3) * 0.5, 0.05)
    for i in range(4):
        place(x, metal(1800 + i * 230, 0.3, 0.05, 1.5) * 0.15, 0.9 + i * 0.35 + rng.random() * 0.1)   # cooling ticks
    return room(x, 1.8, 0.3)


def railgun_panel():
    """animation.railgun.panel_open: tablet pops out 0-0.3 s, slides into the core by 1.2 s."""
    d = 2.0
    x = canvas(d)
    place(x, latch(0.8, 700), 0.0)
    place(x, steam(0.3, 0.2), 0.02)
    place(x, servo(0.9, 380, 260, 13, 0.6), 0.3)
    place(x, hydraulic(0.8, 3000, 0.3), 0.35)
    place(x, clank(0.9, 340, 0.2), 1.2)
    place(x, beep([1568, 2093], 0.22, "tri", 0.35), 1.35)
    return room(x, 0.9, 0.22)


# Deployment timeline (s), mirrors tools/blender/build_models.py
T_BOLTS, T_UNLATCH, T_GLIDE = 0.3, 1.8, (2.3, 3.8)
T_DECKS, T_CLAMP_OUT, T_SLAM = 3.9, (4.8, 5.95), 6.5
T_TURRET, T_RADAR, T_RAIL = (6.8, 8.3), 8.3, 8.8
T_SEG, T_COILS, T_PANEL = (10.2, 10.7), 11.2, 11.55


def railgun_transform():
    """12 s, synced to animation.railgun.deploy."""
    d = 13.5
    x = canvas(d)
    place(x, kick(70, 40, 1.0, 0.25, 0.1) * 0.4, 0.0)                        # systems wake
    place(x, power_hum(11.8, 55, 0.08) * env(11.8, 1.0, 0.1, 1.0, 1.0), 0.0)
    for i in range(4):                                                        # bolts pop
        place(x, latch(0.75, 800 + 60 * i), T_BOLTS + 0.15 * i + 0.05)
        place(x, steam(0.35, 0.3), T_BOLTS + 0.15 * i + 0.08)
    for i in range(4):
        place(x, latch(0.35, 1100), 1.55 + 0.02 * i)                          # bolts retract
    place(x, clank(1.0, 210, 0.3), T_UNLATCH)                                 # shell unlatches
    place(x, steam(0.7, 0.5), T_UNLATCH + 0.05)
    g0, g1 = T_GLIDE
    place(x, servo(g1 - g0 + 0.1, 70, 140, 17, 0.9), g0)                      # quarters glide
    place(x, filt(brown(g1 - g0), "lowpass", 300) * env(g1 - g0, 0.3, 0.1, 0.9, 0.3) * 0.6, g0)
    place(x, hydraulic(g1 - g0, 2400, 0.45), g0 + 0.05)                       # struts telescope
    place(x, clank(1.1, 160, 0.4), g1)                                        # bulwarks lock
    for i in range(3):                                                        # deck plates
        place(x, servo(0.7, 120, 180, 9, 0.45), T_DECKS + 0.25 * i)
        place(x, latch(0.6, 500), T_DECKS + 0.25 * i + 0.7)
    c0, c1 = T_CLAMP_OUT
    place(x, hydraulic(c1 - c0, 3600, 0.7), c0)                               # clamps slide out
    place(x, servo(c1 - c0, 160, 260, 13, 0.5), c0)
    place(x, servo(0.35, 300, 520, 7, 0.6), T_SLAM - 0.4)                     # wind-up
    slam = mix(kick(160, 30, 2.0, 0.5, 1.0) * 1.8, metal(110, 2.0, 0.9, 1.0) * 0.6,
               filt(white(0.4), "lowpass", 3000) * decay(0.4, 0.06),
               crackle(1.2, 120, 0.003, 900, decay(1.2, 0.3)) * 0.8)
    place(x, mband_sat(slam, (3.0, 2.0, 1.3)), T_SLAM)                         # SLAM
    u0, u1 = T_TURRET
    place(x, servo(u1 - u0, 60, 150, 21, 0.9), u0)                            # turret rises
    place(x, hydraulic(u1 - u0, 2000, 0.4), u0)
    place(x, clank(1.0, 240, 0.35), u1)
    place(x, ratchet(0.5, 30, 1700, 0.5), T_RADAR)                            # radar mast
    place(x, ratchet(0.5, 30, 2100, 0.4), T_RADAR + 0.2)                      # antenna
    place(x, servo(3.0, 600, 620, 3, 0.06), T_RADAR + 0.5)                    # dish motor
    place(x, servo(1.3, 200, 330, 15, 0.8), T_RAIL)                           # rail lowers
    place(x, hydraulic(1.3, 2800, 0.5), T_RAIL)
    place(x, clank(0.9, 300, 0.3), T_RAIL + 1.3)
    s0, s1 = T_SEG
    for s in (s0, s1):                                                        # barrel telescopes
        place(x, servo(0.55, 300, 700, 9, 0.7), s)
        place(x, latch(0.9, 650), s + 0.55)
    for i in range(3):                                                        # coils snap on
        hum = unison(110 * (1.5 ** i), 0.6, 3, 0.01, "saw")
        place(x, filt(hum, "lowpass", 2500) * env(0.6, 0.005, 0.1, 0.4, 0.4) * 0.45, T_COILS + 0.12 * i)
        place(x, zap(0.08, 0.5), T_COILS + 0.12 * i)
    place(x, latch(0.5, 900), 11.85)                                          # cooling fins
    place(x, servo(0.45, 380, 300, 13, 0.4), T_PANEL)                         # tablet back out
    place(x, beep([880, 1318.5, 1760], 0.9, "tri", 0.35), 12.0)               # online chime
    return room(x, 1.8, 0.25)


def railgun_pack():
    """12 s, synced to animation.railgun.pack (the deployment in reverse)."""
    d = 13.5
    x = canvas(d)
    R = lambda t: 12.0 - t
    place(x, unison(glide(600, 90, 1.2), 1.2, 3, 0.01, "saw") * env(1.2, 0.01, 0.2, 0.5, 0.8) * 0.25, 0)
    place(x, servo(0.45, 300, 380, 13, 0.4), 0.0)                             # tablet in
    place(x, latch(0.5, 900), R(11.85))                                       # fins down
    s0, s1 = T_SEG
    for s in (s1, s0):                                                        # barrel retracts
        place(x, latch(0.6, 650), R(s + 0.55))
        place(x, servo(0.55, 700, 300, 9, 0.7), R(s + 0.55))
    place(x, clank(0.8, 300, 0.25), R(T_RAIL + 1.3) + 1.3)
    place(x, servo(1.3, 330, 200, 15, 0.8), R(T_RAIL + 1.3))                  # rail rises
    place(x, hydraulic(1.3, 2800, 0.5), R(T_RAIL + 1.3))
    place(x, ratchet(0.5, 30, 2100, 0.4), R(T_RADAR + 0.7))
    place(x, ratchet(0.5, 30, 1700, 0.5), R(T_RADAR + 0.5))
    u0, u1 = T_TURRET
    place(x, servo(u1 - u0, 150, 60, 21, 0.9), R(u1))                         # turret sinks
    place(x, hydraulic(u1 - u0, 2000, 0.4), R(u1))
    place(x, clank(1.0, 200, 0.4), R(u0))
    place(x, latch(1.0, 420), R(T_SLAM))                                      # clamps unlock
    place(x, hydraulic(0.5, 4000, 0.6), R(T_SLAM))
    c0, c1 = T_CLAMP_OUT
    place(x, hydraulic(c1 - c0, 3600, 0.7), R(c1))                            # clamps pull in
    place(x, servo(c1 - c0, 260, 160, 13, 0.5), R(c1))
    for i in range(3):                                                        # decks slide under
        st = R(T_DECKS + 0.25 * (2 - i) + 0.7)
        place(x, servo(0.7, 180, 120, 9, 0.45), st)
        place(x, latch(0.5, 480), st + 0.7)
    g0, g1 = T_GLIDE
    place(x, servo(g1 - g0 + 0.1, 140, 70, 17, 0.9), R(g1))                   # quarters close in
    place(x, filt(brown(g1 - g0), "lowpass", 300) * env(g1 - g0, 0.3, 0.1, 0.9, 0.3) * 0.6, R(g1))
    place(x, hydraulic(g1 - g0, 2400, 0.45), R(g1))
    place(x, clank(1.2, 200, 0.45), R(T_UNLATCH))                             # shell seals
    place(x, steam(0.8, 0.5), R(T_UNLATCH) + 0.05)
    for i in range(4):                                                        # bolts lock
        place(x, latch(0.8, 760 + 60 * i), R(T_BOLTS + 0.15 * (3 - i) + 0.25))
    place(x, servo(0.45, 300, 380, 13, 0.4), T_PANEL)                         # tablet back out
    place(x, beep([1318.5, 880], 0.5, "tri", 0.3), 12.0)
    return room(x, 1.8, 0.25)


def railgun_launch():
    d = 9.0
    x = canvas(d)
    place(x, latch(1.0, 380), 0.0)
    place(x, kick(120, 26, 2.5, 0.7, 0.8) * 1.6, 0.05)
    ign = filt(white(1.0), "lowpass", 3000) * decay(1.0, 0.15) * 1.2
    place(x, ign, 0.05)
    rd = 8.5
    t = t_axis(rd)
    shape = np.clip(t / 0.35, 0, 1) * np.exp(-np.maximum(t - 1.8, 0) / 2.3)
    roar = filt(brown(rd), "lowpass", 800) * 1.3 + filt(white(rd), "bandpass", [500, 6000]) * 0.5
    roar = sweep(roar, "lowpass", 7000, 600) * shape
    rumble = filt(brown(rd), "lowpass", 90) * shape * 1.2
    crack = crackle(rd, 260, 0.0025, 1000, shape) * 0.9
    place(x, roar + rumble + crack, 0.08)
    return room(mband_sat(x, (3.0, 2.0, 1.4)), 3.0, 0.35, 3000)


SOUNDS = {
    "ui_click": ui_click,
    "ui_countdown_beep": ui_countdown_beep,
    "ui_denied": ui_denied,
    "ui_target_lock": ui_target_lock,
    "ui_uplink": ui_uplink,
    "railgun_reentry": railgun_reentry,
    "railgun_land": railgun_land,
    "railgun_deploy": railgun_deploy,
    "railgun_charge_loop": railgun_charge_loop,
    "railgun_fire": railgun_fire,
    "railgun_impact": railgun_impact,
    "railgun_power_down": railgun_power_down,
    "railgun_panel": railgun_panel,
    "railgun_transform": railgun_transform,
    "railgun_pack": railgun_pack,
    "railgun_launch": railgun_launch,
    "ui_tablet_open": ui_tablet_open,
    "ui_tablet_close": ui_tablet_close,
}
LOOPS = {"railgun_charge_loop", "railgun_reentry"}

# sound event id -> (file, extra properties)
EVENTS = {
    "ui.click": ("ui_click", {}),
    "ui.countdown_beep": ("ui_countdown_beep", {}),
    "ui.denied": ("ui_denied", {}),
    "ui.target_lock": ("ui_target_lock", {}),
    "ui.uplink": ("ui_uplink", {}),
    "railgun.reentry": ("railgun_reentry", {"attenuation_distance": 160}),
    "railgun.land": ("railgun_land", {"attenuation_distance": 128}),
    "railgun.deploy": ("railgun_deploy", {"attenuation_distance": 48}),
    "railgun.charge_loop": ("railgun_charge_loop", {"attenuation_distance": 64}),
    "railgun.fire": ("railgun_fire", {"attenuation_distance": 320}),
    "railgun.impact": ("railgun_impact", {"attenuation_distance": 320}),
    "railgun.power_down": ("railgun_power_down", {"attenuation_distance": 48}),
    "railgun.panel": ("railgun_panel", {"attenuation_distance": 24}),
    "railgun.transform": ("railgun_transform", {"attenuation_distance": 96}),
    "railgun.pack": ("railgun_pack", {"attenuation_distance": 96}),
    "railgun.launch": ("railgun_launch", {"attenuation_distance": 256}),
    "ui.tablet_open": ("ui_tablet_open", {}),
    "ui.tablet_close": ("ui_tablet_close", {}),
}


def write_ogg(x, path):
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        with wave.open(tmp.name, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name,
                        "-c:a", "libvorbis", "-q:a", "6", "-ac", "1", str(path)], check=True)
    Path(tmp.name).unlink()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in SOUNDS.items():
        x = norm(fn(), 0.95)
        x = limit(x, 0.92)
        if name not in LOOPS:
            x = fade(norm(trim(x), 0.9), 0.001, 0.08)
        else:
            x = norm(x, 0.85)
        write_ogg(x, OUT / f"{name}.ogg")
        print(f"{name:22s} {len(x) / SR:5.2f}s")

    sounds_json = {}
    for event, (file, props) in EVENTS.items():
        sounds_json[event] = {
            "subtitle": f"subtitles.orbital_railgun.{event}",
            "sounds": [{"name": f"orbital_railgun:{file}", **props}],
        }
    (OUT.parent / "sounds.json").write_text(json.dumps(sounds_json, indent=2))


if __name__ == "__main__":
    main()
