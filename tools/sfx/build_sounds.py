#!/usr/bin/env python3
"""
Synthesizes every Orbital Railgun sound effect from scratch (no samples, nothing from Minecraft)
and encodes them to mono Ogg Vorbis for assets/orbital_railgun/sounds/.

Building blocks: band-limited oscillators, white/pink/brown noise, Butterworth filters
(including time-varying sweeps done block-wise), envelopes, tanh saturation, FM, and a
synthetic convolution reverb (exponentially decaying stereo-free noise impulse response).

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
rng = np.random.default_rng(1337)

# =============================================================================================
# DSP toolkit
# =============================================================================================


def t_axis(dur):
    return np.arange(int(dur * SR)) / SR


def white(dur):
    return rng.standard_normal(int(dur * SR))


def pink(dur):
    n = int(dur * SR)
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    spec /= np.sqrt(np.maximum(f, 20.0))
    x = np.fft.irfft(spec, n)
    return x / (np.abs(x).max() + 1e-9)


def brown(dur):
    x = np.cumsum(rng.standard_normal(int(dur * SR)))
    x = signal.sosfilt(signal.butter(1, 15, "hp", fs=SR, output="sos"), x)
    return x / (np.abs(x).max() + 1e-9)


def filt(x, kind, freq, order=4):
    sos = signal.butter(order, freq, kind, fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def sweep_filter(x, kind, f_start, f_end, blocks=200, order=2, curve="exp"):
    """Time-varying filter: process in blocks, carrying filter state across them."""
    out = np.zeros_like(x)
    edges = np.linspace(0, len(x), blocks + 1).astype(int)
    zi = None
    for i in range(blocks):
        a, b = edges[i], edges[i + 1]
        p = i / max(1, blocks - 1)
        if curve == "exp":
            f = f_start * (f_end / f_start) ** p
        else:
            f = f_start + (f_end - f_start) * p
        if kind == "bandpass":
            lo, hi = f / 1.6, min(f * 1.6, SR / 2 - 100)
            sos = signal.butter(order, [lo, hi], "bandpass", fs=SR, output="sos")
        else:
            sos = signal.butter(order, min(f, SR / 2 - 100), kind, fs=SR, output="sos")
        if zi is None:
            zi = np.zeros((sos.shape[0], 2))
        out[a:b], zi = signal.sosfilt(sos, x[a:b], zi=zi)
    return out


def osc(freq, dur, wave="sine", phase=0.0):
    """freq may be a scalar or an array (for sweeps). Phase-accumulated, so sweeps are clean."""
    n = int(dur * SR)
    f = np.broadcast_to(np.asarray(freq, dtype=float), (n,)) if np.ndim(freq) == 0 else np.asarray(freq)[:n]
    ph = 2 * np.pi * np.cumsum(f) / SR + phase
    if wave == "sine":
        return np.sin(ph)
    if wave == "saw":  # additive band-limited-ish saw (8 partials)
        return sum(np.sin(k * ph) / k for k in range(1, 9)) * 0.6
    if wave == "square":
        return sum(np.sin(k * ph) / k for k in range(1, 12, 2)) * 0.9
    if wave == "tri":
        return sum(((-1) ** ((k - 1) // 2)) * np.sin(k * ph) / (k * k) for k in range(1, 12, 2)) * 0.8
    raise ValueError(wave)


def exp_sweep(f0, f1, dur, shape=1.0):
    p = np.linspace(0, 1, int(dur * SR)) ** shape
    return f0 * (f1 / f0) ** p


def env_adsr(dur, a=0.005, d=0.1, s=0.6, r=0.2):
    n = int(dur * SR)
    e = np.zeros(n)
    na, nd, nr = int(a * SR), int(d * SR), int(r * SR)
    ns = max(0, n - na - nd - nr)
    e[:na] = np.linspace(0, 1, na, endpoint=False)
    e[na:na + nd] = np.linspace(1, s, nd, endpoint=False)
    e[na + nd:na + nd + ns] = s
    e[na + nd + ns:] = np.linspace(s, 0, n - (na + nd + ns))
    return e


def env_exp(dur, decay, attack=0.002):
    t = t_axis(dur)
    e = np.exp(-t / decay)
    na = max(1, int(attack * SR))
    e[:na] *= np.linspace(0, 1, na)
    return e


def place(buf, x, at, gain=1.0):
    i = int(at * SR)
    j = min(len(buf), i + len(x))
    buf[i:j] += x[: j - i] * gain
    return buf


def mix(*parts):
    """Sum signals of different lengths (zero-padded)."""
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    return out


def sat(x, drive=2.0):
    return np.tanh(x * drive) / np.tanh(drive)


def reverb(x, size=1.8, mix=0.3, damp=4000, predelay=0.02):
    """Convolution with a synthetic decaying-noise impulse response."""
    ir = white(size) * np.exp(-t_axis(size) / (size / 6.0))
    ir = filt(ir, "lowpass", damp, 2)
    ir = np.concatenate([np.zeros(int(predelay * SR)), ir])
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    wet = signal.fftconvolve(x, ir)[: len(x) + len(ir)]
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return dry * (1 - mix) + wet * mix * 0.6


def crackle(dur, rate, decay=0.002, hp=2000):
    """Sparse random impulses (electric arcs / debris)."""
    n = int(dur * SR)
    x = np.zeros(n)
    hits = rng.random(n) < rate / SR
    x[hits] = rng.uniform(-1, 1, hits.sum())
    k = np.exp(-np.arange(int(decay * SR * 6)) / (decay * SR))
    x = np.convolve(x, k)[:n]
    return filt(x, "highpass", hp, 2)


def fade(x, fin=0.002, fout=0.05):
    a, b = int(fin * SR), min(int(fout * SR), len(x) // 3)
    x = x.copy()
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b)
    return x


def trim_tail(x, thresh_db=-62):
    """Drop trailing near-silence left by reverb padding."""
    thr = 10 ** (thresh_db / 20) * np.abs(x).max()
    idx = np.nonzero(np.abs(x) > thr)[0]
    return x[: idx[-1] + int(0.05 * SR)] if len(idx) else x


def loud(x, drive=2.2):
    """Soft-clip limiter: raises perceived loudness of huge one-shots without hard clipping."""
    return sat(normalize(x, 1.0), drive)


def normalize(x, peak=0.89):
    x = x - np.mean(x)
    return x / (np.abs(x).max() + 1e-9) * peak


# =============================================================================================
# Sound designs
# =============================================================================================


def ui_click():
    d = 0.07
    x = filt(white(d), "bandpass", [2500, 7000]) * env_exp(d, 0.006)
    x += osc(2100, d) * env_exp(d, 0.012) * 0.4
    return fade(x, 0, 0.01)


def ui_countdown_beep():
    d = 0.2
    x = (osc(1320, d) + 0.35 * osc(2640, d, "tri")) * env_adsr(d, 0.003, 0.04, 0.7, 0.08)
    return reverb(x, 0.4, 0.15)


def ui_denied():
    d = 0.6
    buf = np.zeros(int(d * SR))
    for i, f in enumerate((220, 165)):
        tone = osc(f, 0.2, "square") * env_adsr(0.2, 0.004, 0.05, 0.8, 0.06)
        place(buf, filt(tone, "lowpass", 2500), i * 0.22)
    return reverb(buf, 0.5, 0.15)


def ui_target_lock():
    d = 0.8
    buf = np.zeros(int(d * SR))
    for i in range(3):
        f = exp_sweep(900 + i * 250, 1800 + i * 300, 0.07)
        place(buf, osc(f, 0.07, "tri") * env_adsr(0.07, 0.002, 0.02, 0.7, 0.03), i * 0.09, 0.7)
    tone = (osc(1760, 0.35) + 0.5 * osc(2637, 0.35)) * env_adsr(0.35, 0.004, 0.05, 0.6, 0.25)
    place(buf, tone, 0.3)
    return reverb(buf, 0.6, 0.2)


def ui_uplink():
    d = 1.6
    buf = np.zeros(int(d * SR))
    notes = [523.25, 659.25, 783.99, 1046.5, 1318.5]
    for i, f in enumerate(notes):
        blip = (osc(f, 0.16, "square") * 0.4 + osc(f * 2, 0.16)) * env_adsr(0.16, 0.003, 0.05, 0.5, 0.09)
        place(buf, filt(blip, "lowpass", 6000), i * 0.075, 0.6)
    swoosh = sweep_filter(white(1.0), "bandpass", 400, 6000) * env_adsr(1.0, 0.3, 0.2, 0.5, 0.5) * 0.35
    place(buf, swoosh, 0.0)
    chord = sum(osc(f, 0.9) for f in (1046.5, 1318.5, 1568.0)) * env_adsr(0.9, 0.02, 0.2, 0.4, 0.6) * 0.3
    place(buf, chord, 0.42)
    return reverb(buf, 1.0, 0.3)


def railgun_reentry():
    """7 s plasma re-entry roar that rises toward impact; played on the falling pod."""
    d = 7.0
    t = t_axis(d)
    rise = (t / d) ** 1.6
    roar = sweep_filter(brown(d), "lowpass", 180, 1400) * (0.5 + 0.8 * rise)
    hiss = sweep_filter(white(d), "bandpass", 900, 4500) * (0.15 + 0.5 * rise)
    rumble = osc(exp_sweep(28, 55, d), d) * (0.4 + 0.6 * rise)
    flutter = 1 + 0.25 * np.sin(2 * np.pi * 7 * t) * rise
    arcs = crackle(d, 40 + 140 * rise.mean(), 0.0015, 1500) * (0.3 + rise) * 0.8
    whistle = osc(exp_sweep(320, 900, d, 2.0), d, "saw") * rise ** 2 * 0.08
    x = (roar * 1.2 + hiss + rumble * 0.7 + whistle) * flutter + arcs
    x = sat(x * 0.9, 1.6) * env_adsr(d, 0.8, 0.1, 1.0, 0.25)
    return reverb(x, 1.5, 0.2)


def metal_clang(dur, base, decay=0.9):
    partials = [1.0, 2.76, 5.40, 8.93, 13.34]
    x = sum(osc(base * p, dur) * env_exp(dur, decay / (1 + i * 0.6)) / (1 + i * 0.5)
            for i, p in enumerate(partials))
    return x * (1 + 0.15 * osc(5.0, dur))


def railgun_land():
    d = 4.5
    buf = np.zeros(int(d * SR))
    thump = osc(exp_sweep(110, 32, 1.2, 0.5), 1.2) * env_exp(1.2, 0.35) * 1.4
    blast = filt(white(1.5), "lowpass", 900) * env_exp(1.5, 0.18) * 1.3
    crack = filt(white(0.12), "highpass", 2500) * env_exp(0.12, 0.02)
    debris = filt(crackle(3.5, 90, 0.004, 400), "lowpass", 5000) * env_exp(3.5, 1.0) * 0.8
    rumble = filt(brown(4.0), "lowpass", 160) * env_exp(4.0, 1.2) * 0.9
    place(buf, sat(mix(thump, blast), 2.5), 0)
    place(buf, crack, 0, 0.7)
    place(buf, metal_clang(2.5, 92, 1.1) * 0.35, 0.01)
    place(buf, debris, 0.08)
    place(buf, rumble, 0.02)
    return reverb(buf, 2.5, 0.35, 2500)


def hydraulic(dur):
    x = filt(white(dur), "highpass", 3000) * env_adsr(dur, 0.02, 0.1, 0.5, dur * 0.5)
    return x * (1 + 0.3 * np.sin(2 * np.pi * 23 * t_axis(dur)))


def clunk(at_gain=1.0):
    d = 0.5
    x = osc(exp_sweep(160, 60, d), d) * env_exp(d, 0.07)
    x += filt(white(d), "lowpass", 1800) * env_exp(d, 0.03)
    x += metal_clang(d, 310, 0.15) * 0.3
    return sat(x * at_gain, 2.0)


def railgun_deploy():
    d = 3.4
    buf = np.zeros(int(d * SR))
    place(buf, hydraulic(1.1) * 0.5, 0.05)
    place(buf, hydraulic(0.8) * 0.4, 1.3)
    servo_f = np.concatenate([exp_sweep(180, 420, 0.9), exp_sweep(420, 260, 0.4), exp_sweep(260, 520, 0.9)])
    servo = filt(osc(servo_f, len(servo_f) / SR, "saw"), "lowpass", 1600) * 0.35
    servo *= env_adsr(len(servo) / SR, 0.05, 0.1, 0.9, 0.2)
    place(buf, servo, 0.25)
    for at, g in ((0.95, 1.0), (1.95, 0.9), (2.75, 1.2)):
        place(buf, clunk(g), at, 0.8)
    ping = (osc(1568, 0.6) + 0.4 * osc(2349, 0.6)) * env_exp(0.6, 0.18) * 0.35
    place(buf, ping, 2.85)
    return reverb(buf, 1.4, 0.25)


def railgun_charge_loop():
    """Seamless 2 s loop: every component completes a whole number of cycles in 2 s."""
    d = 2.0
    t = t_axis(d)
    hum = sum(np.sin(2 * np.pi * 55 * k * t) / k ** 1.1 for k in range(1, 9))
    am = 1 + 0.35 * np.sin(2 * np.pi * 8 * t)
    whine = np.sin(2 * np.pi * 1760 * t + 3 * np.sin(2 * np.pi * 4 * t)) * 0.12
    shimmer = np.sin(2 * np.pi * 3520 * t) * (0.5 + 0.5 * np.sin(2 * np.pi * 2 * t)) * 0.05
    # Loopable noise: circular filtering via FFT keeps the end continuous with the start.
    n = len(t)
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    spec *= np.exp(-((f - 2600) / 1400) ** 2)
    arcs = np.fft.irfft(spec, n)
    gate = np.clip((np.sin(2 * np.pi * 3 * t) - 0.4) * 3, 0, 1)  # smooth gate, no clicks
    arcs = arcs / np.abs(arcs).max() * (0.4 + 0.6 * gate)
    x = sat(hum * am * 0.5, 1.5) + whine + shimmer + arcs * 0.15
    return normalize(x, 0.8)


def railgun_fire():
    d = 5.5
    buf = np.zeros(int(d * SR))
    crack = filt(white(0.05), "highpass", 1500) * env_exp(0.05, 0.008) * 1.5
    zap_f = exp_sweep(7000, 140, 0.55, 0.45)
    zap = osc(zap_f + 900 * osc(exp_sweep(400, 40, 0.55), 0.55), 0.55, "saw") * env_exp(0.55, 0.16)
    boom = osc(exp_sweep(95, 28, 2.0, 0.6), 2.0) * env_exp(2.0, 0.6) * 1.6
    blast = filt(white(2.5), "lowpass", 1400) * env_exp(2.5, 0.35)
    sizzle = filt(crackle(4.5, 260, 0.0012, 2500), "lowpass", 9000) * env_exp(4.5, 1.1) * 1.2
    tail = sweep_filter(white(4.0), "bandpass", 3000, 300) * env_exp(4.0, 1.2) * 0.35
    ring = metal_clang(3.0, 57, 1.6) * 0.25
    place(buf, crack, 0)
    place(buf, sat(zap, 1.8) * 0.7, 0.005)
    place(buf, sat(mix(boom, blast), 3.0), 0.01)
    place(buf, sizzle, 0.06)
    place(buf, tail, 0.1)
    place(buf, ring, 0.02)
    return reverb(buf, 3.0, 0.35, 3500)


def railgun_impact():
    d = 6.5
    buf = np.zeros(int(d * SR))
    for i, (at, g) in enumerate(((0.0, 1.0), (0.32, 0.55), (0.75, 0.4), (1.3, 0.3))):
        dur = 3.0
        boom = osc(exp_sweep(80 - i * 6, 25, dur, 0.5), dur) * env_exp(dur, 0.7)
        body = filt(white(dur), "lowpass", 700 - i * 120) * env_exp(dur, 0.45)
        place(buf, sat((boom * 1.3 + body) * g, 2.2), at)
    rumble = filt(brown(6.0), "lowpass", 120) * env_adsr(6.0, 0.05, 0.5, 0.7, 4.0) * 0.8
    debris = filt(crackle(5.0, 70, 0.005, 300), "lowpass", 3500) * env_exp(5.0, 1.6) * 0.6
    place(buf, rumble, 0.0)
    place(buf, debris, 0.4)
    return reverb(buf, 3.5, 0.4, 1800, 0.06)


def railgun_power_down():
    d = 2.8
    buf = np.zeros(int(d * SR))
    whine = osc(exp_sweep(1900, 90, 2.2, 0.7), 2.2, "tri") * env_adsr(2.2, 0.01, 0.2, 0.7, 1.2) * 0.5
    hum = osc(exp_sweep(110, 30, 2.2), 2.2, "saw") * env_adsr(2.2, 0.01, 0.4, 0.5, 1.3) * 0.25
    vent = filt(white(1.6), "bandpass", [2000, 9000]) * env_adsr(1.6, 0.02, 0.3, 0.4, 1.1) * 0.35
    place(buf, filt(whine + hum, "lowpass", 5000), 0)
    place(buf, vent, 0.6)
    place(buf, clunk(0.8) * 0.6, 2.15)
    return reverb(buf, 1.2, 0.25)


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
}

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
    "railgun.fire": ("railgun_fire", {"attenuation_distance": 256}),
    "railgun.impact": ("railgun_impact", {"attenuation_distance": 256}),
    "railgun.power_down": ("railgun_power_down", {"attenuation_distance": 48}),
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
                        "-c:a", "libvorbis", "-q:a", "5", "-ac", "1", str(path)], check=True)
    Path(tmp.name).unlink()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in SOUNDS.items():
        x = fn()
        if name in ("railgun_fire", "railgun_land", "railgun_impact"):
            x = loud(x, 2.6)
        if name != "railgun_charge_loop":
            x = fade(normalize(trim_tail(x)), 0.001, 0.08)
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
