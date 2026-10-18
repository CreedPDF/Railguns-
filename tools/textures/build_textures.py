#!/usr/bin/env python3
"""
Procedurally paints the mod's non-model textures:

  textures/effect/   beam_core, beam_plasma, beam_halo, beam_helix, shockwave  (tileable, for
                     the textured beam render layer; V runs along the beam and scrolls)
  textures/particle/ animated sprite frames for every custom particle
  particles/         particle definition JSONs (frame lists)
  textures/gui/      fire-control console panel and HUD countdown panel

Everything is generated with numpy (periodic FFT noise, domain warping, analytic shapes) so it
tiles cleanly. Nothing is copied from Minecraft.

Usage: python3 tools/textures/build_textures.py     (needs numpy + Pillow)
"""
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
A = ROOT / "src/main/resources/assets/orbital_railgun"
rng = np.random.default_rng(42)


# =============================================================================================
# Noise helpers (all periodic, so textures tile)
# =============================================================================================


def periodic_noise(h, w, scale_y, scale_x, octaves=4, persistence=0.55):
    """
    Fractal noise that tiles in both directions (band-limited random spectrum).
    scale_y / scale_x are feature wavelengths in pixels for the first octave.
    """
    out = np.zeros((h, w))
    amp = 1.0
    for o in range(octaves):
        spec = rng.standard_normal((h, w)) + 1j * rng.standard_normal((h, w))
        fy = np.fft.fftfreq(h)[:, None] * max(scale_y, 1.0)
        fx = np.fft.fftfreq(w)[None, :] * max(scale_x, 1.0)
        r = np.sqrt(fx ** 2 + fy ** 2)
        band = np.exp(-((r - 1.0) ** 2) / 0.35) * (r > 0)
        layer = np.real(np.fft.ifft2(spec * band))
        layer /= np.abs(layer).max() + 1e-9
        out += layer * amp
        amp *= persistence
        scale_y /= 2.0
        scale_x /= 2.0
    out -= out.min()
    return out / (out.max() + 1e-9)


def warp(img, dy, dx, strength):
    """Periodic domain warp: sample img at (y + dy*s, x + dx*s) with wraparound."""
    h, w = img.shape
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    sy = (yy + (dy - 0.5) * strength) % h
    sx = (xx + (dx - 0.5) * strength) % w
    y0, x0 = np.floor(sy).astype(int), np.floor(sx).astype(int)
    fy, fx = sy - y0, sx - x0
    y1, x1 = (y0 + 1) % h, (x0 + 1) % w
    return (img[y0, x0] * (1 - fy) * (1 - fx) + img[y1, x0] * fy * (1 - fx)
            + img[y0, x1] * (1 - fy) * fx + img[y1, x1] * fy * fx)


def ramp(v, stops):
    """Map 0..1 to RGBA via [(pos, (r,g,b,a)), ...] linear stops."""
    v = np.clip(v, 0, 1)
    out = np.zeros(v.shape + (4,))
    pos = [p for p, _ in stops]
    cols = np.array([c for _, c in stops], dtype=float)
    for ch in range(4):
        out[..., ch] = np.interp(v, pos, cols[:, ch])
    return out


def save_rgba(arr, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA").save(path)


def radial(size, cx=None, cy=None):
    cx = (size - 1) / 2 if cx is None else cx
    cy = (size - 1) / 2 if cy is None else cy
    yy, xx = np.mgrid[0:size, 0:size].astype(float)
    return np.hypot(xx - cx, yy - cy) / (size / 2), np.arctan2(yy - cy, xx - cx)


# =============================================================================================
# Beam textures (U = around the tube, V = along the beam; V scrolls in game)
# =============================================================================================


def beam_textures():
    E = A / "textures/effect"

    # Core: white-hot with fast bright filaments streaking along V.
    h, w = 256, 32
    streak = periodic_noise(h, w, 64, 3, 3)
    streak = warp(streak, periodic_noise(h, w, 32, 8, 2), periodic_noise(h, w, 32, 8, 2), 6)
    v = 0.55 + 0.45 * streak ** 1.5
    save_rgba(ramp(v, [(0, (255, 120, 90, 120)), (0.6, (255, 220, 200, 230)), (1, (255, 255, 250, 255))]),
              E / "beam_core.png")

    # Plasma body: turbulent red filaments.
    h, w = 256, 64
    base = periodic_noise(h, w, 128, 28, 4, 0.45)
    dy, dx = periodic_noise(h, w, 96, 32, 2), periodic_noise(h, w, 96, 32, 2)
    body = warp(base, dy, dx, 24)
    fil = np.abs(np.sin(body * 6.0)) ** 10  # sharp filament lines
    v = np.clip(body ** 1.3 * 0.7 + fil * 0.6, 0, 1)
    save_rgba(ramp(v, [(0, (60, 0, 0, 0)), (0.3, (170, 10, 5, 90)), (0.65, (255, 60, 30, 200)),
                       (0.9, (255, 170, 120, 245)), (1, (255, 235, 220, 255))]), E / "beam_plasma.png")

    # Halo: soft wispy smoke-like glow.
    h, w = 128, 64
    wisps = warp(periodic_noise(h, w, 40, 20, 4), periodic_noise(h, w, 24, 24, 2),
                 periodic_noise(h, w, 24, 24, 2), 18)
    v = np.clip((wisps - 0.25) * 1.6, 0, 1) ** 1.4
    save_rgba(ramp(v, [(0, (90, 0, 0, 0)), (0.5, (200, 20, 10, 90)), (1, (255, 90, 60, 170))]),
              E / "beam_halo.png")

    # Helix ribbon: bright edges, energy dashes along its length.
    h, w = 128, 16
    u = np.linspace(-1, 1, w)[None, :]
    edge = np.exp(-((np.abs(u) - 0.8) / 0.12) ** 2) + 0.35 * np.exp(-(u / 0.25) ** 2)
    vv = np.arange(h)[:, None] / h
    dashes = 0.45 + 0.55 * (np.sin(vv * 2 * np.pi * 8) > -0.3)
    noise = periodic_noise(h, w, 16, 4, 2)
    v = np.clip(edge * dashes * (0.7 + 0.5 * noise), 0, 1)
    save_rgba(ramp(v, [(0, (120, 0, 0, 0)), (0.5, (255, 50, 30, 170)), (1, (255, 220, 200, 255))]),
              E / "beam_helix.png")

    # Shockwave ring: U around, V radial (0 = inner edge). Bright leading edge, broken tail.
    h, w = 32, 256
    vv = np.linspace(0, 1, h)[:, None]
    front = np.exp(-((vv - 0.12) / 0.08) ** 2)
    tail = np.clip(1 - vv, 0, 1) ** 2 * periodic_noise(h, w, 8, 24, 3)
    v = np.clip(front * 0.9 + tail * 0.7, 0, 1)
    save_rgba(ramp(v, [(0, (80, 0, 0, 0)), (0.4, (220, 40, 20, 120)), (0.8, (255, 150, 90, 220)),
                       (1, (255, 240, 230, 255))]), E / "shockwave.png")


# =============================================================================================
# Particles
# =============================================================================================

PARTICLES = {}


def particle(name, frames, mode="age"):
    """Saves frames as textures/particle/<name>_<i>.png and registers the particle JSON."""
    for i, f in enumerate(frames):
        save_rgba(f, A / f"textures/particle/{name}_{i}.png")
    PARTICLES[name] = [f"orbital_railgun:{name}_{i}" for i in range(len(frames))]


def glow_frame(size, core, falloff, color, rays=0, ray_len=0.0, ray_w=0.06, rot=0.0):
    r, th = radial(size)
    a = np.exp(-(r / core) ** 2) + 0.6 * np.exp(-(r / falloff) ** 2)
    if rays:
        streak = np.abs(np.cos((th + rot) * rays / 2)) ** (1 / ray_w)
        a += streak * np.clip(1 - r / ray_len, 0, 1) ** 2 * 0.8
    a = np.clip(a, 0, 1)
    white = np.clip(np.exp(-(r / (core * 0.6)) ** 2), 0, 1)
    rgb = np.array(color)[None, None, :] * (1 - white[..., None]) + 255 * white[..., None]
    return np.dstack([rgb, a * 255])


def particles():
    # Plasma spark: hot core with 4-point glare, shrinking.
    particle("plasma_spark", [glow_frame(16, 0.22 - i * 0.04, 0.55 - i * 0.08, (255, 50, 25),
                                         rays=4, ray_len=1.0 - i * 0.15, rot=i * 0.3) for i in range(4)])

    # Energy mote: pulsing orb with a thin ring.
    frames = []
    for i in range(6):
        size = 16
        r, _ = radial(size)
        pulse = 0.5 + 0.5 * math.sin(i / 6 * 2 * math.pi)
        f = glow_frame(size, 0.18 + 0.06 * pulse, 0.5, (255, 40, 30))
        ring = np.exp(-((r - (0.55 + 0.25 * pulse)) / 0.07) ** 2) * 0.7
        f[..., 3] = np.clip(f[..., 3] / 255 + ring, 0, 1) * 255
        frames.append(f)
    particle("energy_mote", frames)

    # Plasma smoke: billowing dark puff, grows and thins over 8 frames.
    frames = []
    base = periodic_noise(32, 32, 10, 10, 4)
    for i in range(8):
        p = i / 7
        r, _ = radial(32)
        n = warp(base, periodic_noise(32, 32, 8, 8, 2), periodic_noise(32, 32, 8, 8, 2), 4 + i * 2)
        shape = np.clip(1 - r / (0.65 + 0.3 * p) + (n - 0.5) * 0.9, 0, 1)
        a = np.clip(shape * 1.8, 0, 1) ** 1.2 * (1 - p * 0.75)
        shade = 30 + 70 * n + 25 * (1 - r)
        ember = np.clip((n - 0.7) * 4, 0, 1) * (1 - p) * 0.8
        rgb = np.dstack([shade + ember * 180, shade * 0.92 + ember * 40, shade * 0.9])
        frames.append(np.dstack([rgb, a * 235]))
    particle("plasma_smoke", frames)

    # Re-entry fire: orange-red flame plasma, flickering.
    frames = []
    for i in range(6):
        n = periodic_noise(32, 32, 8, 12, 4)
        r, _ = radial(32, cy=18)
        yy = np.linspace(0, 1, 32)[:, None]
        flame = np.clip(1.1 - r - (1 - yy) * 0.35 + (n - 0.5) * 0.8, 0, 1)
        v = flame ** 1.4
        frames.append(ramp(v, [(0, (60, 0, 0, 0)), (0.3, (200, 30, 10, 150)), (0.6, (255, 120, 30, 220)),
                               (0.85, (255, 210, 120, 250)), (1, (255, 250, 230, 255))]))
    particle("reentry_fire", frames)

    # Ember: small glowing chunk, cooling.
    frames = []
    for i in range(4):
        f = np.zeros((16, 16, 4))
        img = Image.new("RGBA", (16, 16))
        d = ImageDraw.Draw(img)
        pts = [(8 + math.cos(a) * (3 + rng.random() * 2), 8 + math.sin(a) * (3 + rng.random() * 2))
               for a in np.linspace(0, 2 * math.pi, 6, endpoint=False)]
        heat = 1 - i / 4
        d.polygon(pts, fill=(255, int(80 + 150 * heat), int(30 * heat), 255))
        img = img.filter(ImageFilter.GaussianBlur(0.6))
        g = glow_frame(16, 0.2, 0.6, (255, 90, 20))
        g[..., 3] *= 0.5 * heat
        f = np.maximum(np.array(img).astype(float), g)
        frames.append(f)
    particle("ember", frames)

    # Debris: four rock-chunk variants (picked at random, not animated).
    frames = []
    for i in range(4):
        img = Image.new("RGBA", (16, 16))
        d = ImageDraw.Draw(img)
        k = 5 + i
        pts = [(8 + math.cos(a) * (4 + rng.random() * 3), 8 + math.sin(a) * (4 + rng.random() * 3))
               for a in np.linspace(0, 2 * math.pi, k, endpoint=False)]
        base = (70 + 15 * i, 64 + 10 * i, 58 + 8 * i, 255)
        d.polygon(pts, fill=base)
        d.polygon(pts[: k // 2 + 1] + [(8, 8)], fill=tuple(min(255, c + 35) for c in base[:3]) + (255,))
        arr = np.array(img).astype(float)
        speck = rng.random((16, 16)) < 0.15
        arr[speck & (arr[..., 3] > 0), :3] *= 0.7
        hot = rng.random((16, 16)) < 0.06
        arr[hot & (arr[..., 3] > 0)] = (255, 90, 30, 255)
        frames.append(arr)
    particle("debris", frames)

    # Flash: big lens flare star.
    particle("flash", [glow_frame(64, 0.12, 0.45, (255, 80, 50), rays=6, ray_len=1.0, ray_w=0.04)])

    # Shock dust: pale ring-shaped dust puff for ground shockwaves.
    frames = []
    for i in range(6):
        r, _ = radial(32)
        n = periodic_noise(32, 32, 8, 8, 3)
        rad = 0.3 + 0.12 * i
        a = np.clip(np.exp(-((r - rad) / 0.18) ** 2) * (0.6 + 0.6 * n) * (1 - i / 7), 0, 1)
        shade = 120 + 60 * n
        frames.append(np.dstack([shade, shade * 0.95, shade * 0.88, a * 220]))
    particle("shock_dust", frames)

    for name, tex in PARTICLES.items():
        p = A / f"particles/{name}.json"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps({"textures": tex}, indent=2))


# =============================================================================================
# GUI
# =============================================================================================

GUI_W, GUI_H = 336, 232


def panel_rect(d, box, fill, light, dark, width=1):
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=fill)
    for i in range(width):
        d.line([(x0 + i, y1 - i), (x0 + i, y0 + i), (x1 - i, y0 + i)], fill=light)
        d.line([(x0 + i, y1 - i), (x1 - i, y1 - i), (x1 - i, y0 + i)], fill=dark)


def recess(d, box, fill=(10, 12, 16, 255)):
    panel_rect(d, box, fill, (6, 7, 9, 255), (70, 76, 86, 255))


def hazard(img, box, period=8):
    """Yellow/black diagonal stripes clipped to the box."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0 + 1, y1 - y0 + 1
    tile = Image.new("RGBA", (w, h), (24, 20, 8, 255))
    d = ImageDraw.Draw(tile)
    for x in range(-h, w + h, period):
        d.polygon([(x, h), (x + period // 2, h), (x + period // 2 + h, 0), (x + h, 0)], fill=(214, 150, 20, 255))
    d.rectangle([0, 0, w - 1, h - 1], outline=(30, 24, 8, 255))
    img.paste(tile, (x0, y0))


def gui_panel():
    W, H = GUI_W, GUI_H
    img = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    base = periodic_noise(H, W, 40, 40, 4)
    metal = np.dstack([28 + base * 14, 31 + base * 15, 37 + base * 17, np.full((H, W), 250.0)])
    # brushed streaks
    metal[..., :3] += (periodic_noise(H, W, 2, 120, 2)[..., None] - 0.5) * 8
    img.paste(Image.fromarray(np.clip(metal, 0, 255).astype(np.uint8), "RGBA"), (0, 0))
    d = ImageDraw.Draw(img)

    # Outer frame: double bevel + red accent line.
    d.rectangle([0, 0, W - 1, H - 1], outline=(8, 9, 12, 255))
    d.rectangle([1, 1, W - 2, H - 2], outline=(84, 90, 102, 255))
    d.rectangle([3, 3, W - 4, H - 4], outline=(18, 20, 24, 255))
    d.line([(4, H - 5), (W - 5, H - 5)], fill=(120, 20, 18, 255))

    # Header
    panel_rect(d, (4, 4, W - 5, 22), (38, 12, 14, 255), (90, 30, 30, 255), (14, 4, 5, 255))
    hazard(img, (W - 76, 7, W - 52, 19), 6)

    # Chamfered corners with rivets
    for cx, cy in ((7, 26), (W - 8, 26), (7, H - 8), (W - 8, H - 8)):
        d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=(110, 116, 128, 255), outline=(10, 10, 12, 255))

    # Divider between columns
    d.line([(169, 28), (169, 162)], fill=(12, 13, 16, 255))
    d.line([(170, 28), (170, 162)], fill=(64, 70, 80, 255))
    d.line([(8, 166), (W - 9, 166)], fill=(12, 13, 16, 255))
    d.line([(8, 167), (W - 9, 167)], fill=(64, 70, 80, 255))

    # Coordinate field recesses + axis tags
    for i, y in enumerate((44, 66, 88)):
        recess(d, (28, y - 1, 161, y + 17))
        panel_rect(d, (10, y - 1, 25, y + 17), (60, 14, 14, 255), (110, 30, 30, 255), (20, 4, 4, 255))
    # Button wells
    for box in ((9, 111, 85, 130), (89, 111, 165, 130)):
        recess(d, box, (16, 18, 22, 255))
    # Info well
    recess(d, (9, 134, 165, 161), (12, 14, 18, 255))

    # Right column: status plate, capacitor slot, oscilloscope
    recess(d, (175, 40, 327, 58), (14, 16, 20, 255))
    recess(d, (175, 69, 327, 85))
    recess(d, (175, 89, 327, 129), (8, 14, 10, 255))
    for gx in range(176, 327, 10):
        d.line([(gx, 90), (gx, 128)], fill=(20, 36, 24, 255))
    for gy in range(90, 129, 8):
        d.line([(176, gy), (326, gy)], fill=(20, 36, 24, 255))
    recess(d, (175, 132, 327, 161), (12, 14, 18, 255))

    # Fire well with hazard frame, abort/recall wells
    hazard(img, (8, 170, 208, 224), 10)
    recess(d, (12, 174, 204, 220), (20, 6, 6, 255))
    for box in ((212, 171, 328, 195), (212, 199, 328, 223)):
        recess(d, box, (16, 18, 22, 255))

    # Vent slots under the header
    for x in range(180, 324, 6):
        d.rectangle([x, 27, x + 3, 33], fill=(10, 11, 14, 255))
    img.save(A / "textures/gui/fire_control.png") if (A / "textures/gui").exists() else None


def hud_panel():
    W, H = 240, 64
    img = Image.new("RGBA", (256, 64), (0, 0, 0, 0))
    base = periodic_noise(H, W, 20, 40, 3)
    arr = np.dstack([10 + base * 10, 11 + base * 10, 15 + base * 12, np.full((H, W), 200.0)])
    # scanlines
    arr[::2, :, 3] = 170
    img.paste(Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA"), (0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W - 1, H - 1], outline=(130, 24, 22, 255))
    for (x, y, sx, sy) in ((0, 0, 1, 1), (W - 1, 0, -1, 1), (0, H - 1, 1, -1), (W - 1, H - 1, -1, -1)):
        d.line([(x, y), (x + 10 * sx, y)], fill=(255, 60, 50, 255), width=2)
        d.line([(x, y), (x, y + 10 * sy)], fill=(255, 60, 50, 255), width=2)
    hazard(img, (4, 4, 30, 10), 6)
    hazard(img, (W - 31, 4, W - 5, 10), 6)
    recess(d, (8, 40, W - 9, 46), (20, 6, 6, 255))
    img.save(A / "textures/gui/hud_countdown.png")


def main():
    (A / "textures/gui").mkdir(parents=True, exist_ok=True)
    beam_textures()
    particles()
    gui_panel()
    hud_panel()
    print("textures written:", len(list((A / "textures").rglob("*.png"))))


if __name__ == "__main__":
    main()
