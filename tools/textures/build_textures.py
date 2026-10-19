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
import random
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


def loadout_effect_textures():
    """
    Neutral (white-luminance) beam textures that the renderer tints per ammunition, plus the
    shield lattice, singularity surface and supply-capsule transfer stream.
    """
    E = A / "textures/effect"
    W = [(0, (0, 0, 0, 0))]

    h, w = 256, 32
    streak = warp(periodic_noise(h, w, 48, 3, 3), periodic_noise(h, w, 32, 8, 2), periodic_noise(h, w, 32, 8, 2), 8)
    v = 0.5 + 0.5 * streak ** 1.4
    save_rgba(ramp(v, [(0, (200, 200, 200, 110)), (0.6, (235, 235, 235, 225)), (1, (255, 255, 255, 255))]), E / "tint_core.png")

    h, w = 256, 64
    body = warp(periodic_noise(h, w, 110, 24, 4, 0.5), periodic_noise(h, w, 80, 30, 2), periodic_noise(h, w, 80, 30, 2), 28)
    fil = np.abs(np.sin(body * 7.0)) ** 12
    v = np.clip(body ** 1.2 * 0.7 + fil * 0.7, 0, 1)
    save_rgba(ramp(v, [(0, (40, 40, 40, 0)), (0.3, (120, 120, 120, 90)), (0.65, (210, 210, 210, 200)),
                       (1, (255, 255, 255, 255))]), E / "tint_plasma.png")

    h, w = 128, 64
    wisps = warp(periodic_noise(h, w, 36, 18, 4), periodic_noise(h, w, 20, 20, 2), periodic_noise(h, w, 20, 20, 2), 20)
    v = np.clip((wisps - 0.25) * 1.7, 0, 1) ** 1.3
    save_rgba(ramp(v, [(0, (80, 80, 80, 0)), (0.5, (170, 170, 170, 90)), (1, (255, 255, 255, 170))]), E / "tint_halo.png")

    h, w = 128, 16
    u = np.linspace(-1, 1, w)[None, :]
    vv = np.arange(h)[:, None] / h
    edge = np.exp(-((np.abs(u) - 0.75) / 0.14) ** 2) + 0.4 * np.exp(-(u / 0.2) ** 2)
    chev = 0.4 + 0.6 * (np.sin((vv * 10 + np.abs(u) * 0.8) * 2 * np.pi) > 0)
    v = np.clip(edge * chev, 0, 1)
    save_rgba(ramp(v, [(0, (100, 100, 100, 0)), (0.5, (220, 220, 220, 170)), (1, (255, 255, 255, 255))]), E / "tint_helix.png")

    h, w = 32, 256
    vv = np.linspace(0, 1, h)[:, None]
    v = np.clip(np.exp(-((vv - 0.1) / 0.07) ** 2) * 0.95 + np.clip(1 - vv, 0, 1) ** 2.5 * periodic_noise(h, w, 6, 20, 3) * 0.7, 0, 1)
    save_rgba(ramp(v, [(0, (80, 80, 80, 0)), (0.4, (180, 180, 180, 120)), (1, (255, 255, 255, 255))]), E / "tint_shockwave.png")

    # Shield lattice: hexagon cells with bright edges and faint fill (tiles: 4 hex columns).
    h, w = 128, 128
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    size = 16.0
    qx = xx / (size * 1.5)
    col = np.floor(qx)
    yoff = np.where(col % 2 == 1, size * math.sqrt(3) / 2, 0)
    cy = (yy + yoff) / (size * math.sqrt(3))
    fx = (qx - col - 0.5) * 1.5
    fy = (cy - np.floor(cy) - 0.5) * math.sqrt(3)
    d = np.maximum(np.abs(fy) * 2 / math.sqrt(3), (np.abs(fx) + np.abs(fy) / math.sqrt(3)))
    edge = np.clip(1 - np.abs(d - 0.92) / 0.08, 0, 1)
    shimmer = periodic_noise(h, w, 40, 40, 2)
    v = np.clip(edge * 0.9 + 0.12 + shimmer * 0.15, 0, 1)
    save_rgba(ramp(v, [(0, (60, 160, 255, 0)), (0.2, (80, 190, 255, 40)), (0.6, (140, 220, 255, 150)),
                       (1, (230, 250, 255, 255))]), E / "shield_hex.png")

    # Singularity surface: near-black with faint violet caustics (drawn opaque).
    h, w = 64, 64
    n = warp(periodic_noise(h, w, 16, 16, 3), periodic_noise(h, w, 12, 12, 2), periodic_noise(h, w, 12, 12, 2), 10)
    caust = np.abs(np.sin(n * 9)) ** 16
    rgb = np.dstack([4 + caust * 70, 2 + caust * 20, 10 + caust * 110])
    save_rgba(np.dstack([rgb, np.full((h, w), 255.0)]), E / "singularity.png")

    # Transfer stream: teal data ribbon with packets.
    h, w = 128, 16
    u = np.linspace(-1, 1, w)[None, :]
    vv = np.arange(h)[:, None] / h
    core = np.exp(-(u / 0.35) ** 2)
    packets = (np.sin(vv * 2 * np.pi * 12) > 0.6) * np.exp(-(u / 0.6) ** 2)
    v = np.clip(core * 0.6 + packets * 0.8 + periodic_noise(h, w, 10, 4, 2) * 0.2, 0, 1)
    save_rgba(ramp(v, [(0, (0, 80, 60, 0)), (0.4, (40, 220, 170, 140)), (1, (220, 255, 245, 255))]), E / "transfer_stream.png")


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


def shard(size, pts, color, edge, glow=None):
    img = Image.new("RGBA", (size, size))
    d = ImageDraw.Draw(img)
    d.polygon(pts, fill=color)
    d.line(pts + [pts[0]], fill=edge, width=1)
    arr = np.array(img).astype(float)
    if glow is not None:
        g = glow_frame(size, 0.25, 0.7, glow)
        g[..., 3] *= 0.45
        arr = np.maximum(arr, g)
    return arr


def loadout_particles():
    """Sprites for the loadout options (muzzles, ammunition, modules)."""
    # Tungsten spall: bright blue-white metal splinters.
    frames = []
    for i in range(4):
        a0 = rng.random() * math.pi
        pts = [(8 + math.cos(a0) * 7, 8 + math.sin(a0) * 7), (8 + math.cos(a0 + 1.9) * 2, 8 + math.sin(a0 + 1.9) * 2),
               (8 + math.cos(a0 + math.pi) * 6, 8 + math.sin(a0 + math.pi) * 6), (8 + math.cos(a0 - 1.2) * 2, 8 + math.sin(a0 - 1.2) * 2)]
        frames.append(shard(16, pts, (225, 240, 255, 255), (120, 170, 255, 255), (120, 180, 255)))
    particle("spall", frames)

    # Thermite flame: white-yellow core licking into orange, rising tongues.
    frames = []
    for i in range(6):
        n = periodic_noise(32, 32, 6, 10, 4)
        r, _ = radial(32, cy=20)
        yy = np.linspace(0, 1, 32)[:, None]
        v = np.clip(1.15 - r * 1.2 - (1 - yy) * 0.45 + (n - 0.5) * 1.0 - i * 0.06, 0, 1) ** 1.2
        frames.append(ramp(v, [(0, (40, 5, 0, 0)), (0.25, (230, 60, 0, 170)), (0.55, (255, 150, 20, 230)),
                               (0.8, (255, 230, 120, 250)), (1, (255, 255, 235, 255))]))
    particle("thermite_flame", frames)

    # Frost: six-armed ice crystal, slowly turning, sparkling.
    frames = []
    for i in range(6):
        img = Image.new("RGBA", (16, 16))
        d = ImageDraw.Draw(img)
        rot = i * math.pi / 18
        for k in range(6):
            a = rot + k * math.pi / 3
            x1, y1 = 8 + math.cos(a) * 7, 8 + math.sin(a) * 7
            d.line([(8, 8), (x1, y1)], fill=(220, 250, 255, 255), width=1)
            for t in (0.45, 0.7):
                bx, by = 8 + math.cos(a) * 7 * t, 8 + math.sin(a) * 7 * t
                for s2 in (-0.8, 0.8):
                    d.line([(bx, by), (bx + math.cos(a + s2) * 2, by + math.sin(a + s2) * 2)], fill=(150, 230, 255, 255))
        arr = np.array(img).astype(float)
        g = glow_frame(16, 0.15, 0.5, (120, 230, 255))
        g[..., 3] *= 0.5 + 0.3 * math.sin(i)
        frames.append(np.maximum(arr, g))
    particle("frost", frames)

    # Ion arc: jagged violet lightning fragment.
    frames = []
    for i in range(4):
        img = Image.new("RGBA", (32, 32))
        d = ImageDraw.Draw(img)
        pts = [(2, 16)]
        for k in range(1, 8):
            pts.append((2 + k * 4, 16 + (rng.random() - 0.5) * 18))
        pts.append((30, 16))
        d.line(pts, fill=(170, 90, 255, 200), width=3)
        d.line(pts, fill=(250, 235, 255, 255), width=1)
        frames.append(np.array(img.filter(ImageFilter.GaussianBlur(0.4))).astype(float))
    particle("ion_arc", frames)

    # Bomblet: glowing submunition with a streak.
    frames = []
    for i in range(3):
        r, th = radial(16)
        core = glow_frame(16, 0.16, 0.35, (255, 200, 40))
        streak = np.exp(-((np.arange(16)[None, :] - 8) / 1.4) ** 2) * np.clip(np.linspace(1.2, -0.2, 16)[:, None], 0, 1)
        core[..., 3] = np.clip(core[..., 3] / 255 + streak * (0.6 + 0.2 * i), 0, 1) * 255
        frames.append(core)
    particle("bomblet", frames)

    # Singularity mote: dark violet orb with a bright rim (light being swallowed).
    frames = []
    for i in range(6):
        r, _ = radial(16)
        rim = np.exp(-((r - 0.55 - 0.05 * math.sin(i)) / 0.12) ** 2)
        a = np.clip(rim + (r < 0.5) * 0.9, 0, 1)
        rgb = np.dstack([40 + rim * 170, 10 + rim * 60, 70 + rim * 185])
        frames.append(np.dstack([rgb, a * 255]))
    particle("singularity_mote", frames)

    # Fallout: towering ash cloud puff, sickly green-grey with glowing underside.
    frames = []
    base = periodic_noise(32, 32, 10, 10, 4)
    for i in range(8):
        p = i / 7
        r, _ = radial(32)
        n = warp(base, periodic_noise(32, 32, 8, 8, 2), periodic_noise(32, 32, 8, 8, 2), 3 + i * 2)
        a = np.clip((1 - r / (0.7 + 0.25 * p) + (n - 0.5) * 0.8) * 1.6, 0, 1) * (1 - p * 0.6)
        yy = np.linspace(0, 1, 32)[:, None]
        hot = np.clip(yy - 0.55, 0, 1) * 2 * (1 - p)
        shade = 60 + 70 * n
        rgb = np.dstack([shade + hot * 140, shade * 1.05 + hot * 160, shade * 0.85 + hot * 40])
        frames.append(np.dstack([rgb, a * 230]))
    particle("fallout", frames)

    # Lens glint: crisp cyan anamorphic flare.
    frames = []
    for i in range(3):
        f = glow_frame(32, 0.1, 0.3, (110, 230, 255))
        yy, xx = np.mgrid[0:32, 0:32]
        streak = np.exp(-((yy - 15.5) / 1.0) ** 2) * np.exp(-((xx - 15.5) / (14 - i * 3)) ** 2)
        f[..., 3] = np.clip(f[..., 3] / 255 + streak, 0, 1) * 255
        frames.append(f)
    particle("lens_glint", frames)

    # Coolant vapour: white-cyan cold mist.
    frames = []
    for i in range(6):
        r, _ = radial(32)
        n = periodic_noise(32, 32, 7, 7, 3)
        a = np.clip((1 - r / (0.6 + 0.07 * i)) * (0.6 + 0.7 * n), 0, 1) * (1 - i / 7)
        frames.append(np.dstack([200 + 40 * n, 235 + 20 * n, np.full((32, 32), 255.0), a * 200]))
    particle("coolant_vapor", frames)

    # Overclock spark: hot red-white star.
    particle("overclock_spark", [glow_frame(16, 0.18 - i * 0.03, 0.45, (255, 70, 60), rays=4, ray_len=1.0, rot=i * 0.4)
                                 for i in range(4)])

    # Shield spark: hexagonal hard-light flash.
    frames = []
    for i in range(4):
        img = Image.new("RGBA", (32, 32))
        d = ImageDraw.Draw(img)
        rad = 6 + i * 3
        hexp = [(16 + math.cos(k * math.pi / 3) * rad, 16 + math.sin(k * math.pi / 3) * rad) for k in range(6)]
        d.polygon(hexp, outline=(140, 230, 255, 255 - i * 40))
        d.polygon([(x * 0.6 + 16 * 0.4, y * 0.6 + 16 * 0.4) for x, y in hexp], fill=(80, 200, 255, 90 - i * 18))
        frames.append(np.array(img).astype(float))
    particle("shield_spark", frames)

    # Sentry bolt: short orange laser dash.
    frames = []
    for i in range(2):
        yy, xx = np.mgrid[0:16, 0:16]
        a = np.exp(-((yy - 7.5) / 1.3) ** 2) * np.exp(-((xx - 7.5) / 6.5) ** 2)
        w = np.exp(-((yy - 7.5) / 0.6) ** 2) * np.exp(-((xx - 7.5) / 5) ** 2)
        rgb = np.dstack([np.full((16, 16), 255.0), 120 + 135 * w, 40 + 215 * w])
        frames.append(np.dstack([rgb, np.clip(a * 1.4, 0, 1) * 255]))
    particle("sentry_bolt", frames)

    # Thruster flame: blue-white shock-diamond plume.
    frames = []
    for i in range(6):
        r, _ = radial(32, cy=10)
        yy = np.linspace(0, 1, 32)[:, None]
        n = periodic_noise(32, 32, 6, 12, 3)
        v = np.clip(1.1 - r - yy * 0.3 + (n - 0.5) * 0.6, 0, 1)
        frames.append(ramp(v, [(0, (0, 20, 80, 0)), (0.3, (40, 90, 255, 160)), (0.65, (120, 190, 255, 230)),
                               (1, (240, 250, 255, 255))]))
    particle("thruster_flame", frames)

    # Seismic crack: dark radial fissure with dust.
    frames = []
    for i in range(4):
        img = Image.new("RGBA", (32, 32))
        d = ImageDraw.Draw(img)
        for k in range(5):
            a = rng.random() * 2 * math.pi
            x, y = 16.0, 16.0
            for step in range(5):
                a += (rng.random() - 0.5) * 0.9
                nx, ny = x + math.cos(a) * 3, y + math.sin(a) * 3
                d.line([(x, y), (nx, ny)], fill=(40, 30, 22, 230), width=2)
                x, y = nx, ny
        arr = np.array(img).astype(float)
        r, _ = radial(32)
        dust = np.clip(1 - r / 0.9, 0, 1) * 0.35 * (1 - i / 5)
        arr[..., :3] = np.where(arr[..., 3:4] > 0, arr[..., :3], np.array([150, 130, 105]))
        arr[..., 3] = np.maximum(arr[..., 3], dust * 255)
        frames.append(arr)
    particle("seismic_crack", frames)

    # Shell casing: big brass cartridge, a few rotations.
    frames = []
    for i in range(4):
        img = Image.new("RGBA", (16, 16))
        d = ImageDraw.Draw(img)
        a = i * math.pi / 4
        c, s_ = math.cos(a), math.sin(a)
        L, W = 6.5, 2.2
        pts = [(8 + c * L - s_ * W, 8 + s_ * L + c * W), (8 + c * L + s_ * W, 8 + s_ * L - c * W),
               (8 - c * L + s_ * W, 8 - s_ * L - c * W), (8 - c * L - s_ * W, 8 - s_ * L + c * W)]
        d.polygon(pts, fill=(200, 150, 60, 255), outline=(110, 75, 25, 255))
        d.line([pts[0], pts[1]], fill=(255, 220, 140, 255))
        frames.append(np.array(img).astype(float))
    particle("shell_casing", frames)

    # Plasma pool: bubbling molten plasma blob.
    frames = []
    for i in range(6):
        r, _ = radial(32)
        n = periodic_noise(32, 32, 6, 6, 3)
        v = np.clip(1 - r / (0.55 + 0.1 * math.sin(i)) + (n - 0.5) * 0.7, 0, 1)
        frames.append(ramp(v, [(0, (60, 0, 0, 0)), (0.3, (200, 20, 10, 160)), (0.6, (255, 90, 30, 230)),
                               (1, (255, 230, 170, 255))]))
    particle("plasma_pool", frames)

    # Transfer mote: teal data-light packet with a square core.
    frames = []
    for i in range(4):
        f = glow_frame(16, 0.14, 0.42, (60, 255, 200))
        f[6:10, 6:10] = (230, 255, 245, 255)
        f[..., 3] *= 0.7 + 0.3 * math.cos(i)
        frames.append(f)
    particle("transfer_mote", frames)



# =============================================================================================
# v3.1: a dedicated effect set for every ammunition, muzzle device, module and the part drops
# =============================================================================================


def _aa(draw_fn, size, ss=4, blur=0.0):
    """Draws at ss x resolution with PIL and downsamples: smooth, anti-aliased sprites."""
    img = Image.new("RGBA", (size * ss, size * ss))
    draw_fn(ImageDraw.Draw(img), size * ss)
    if blur:
        img = img.filter(ImageFilter.GaussianBlur(blur * ss))
    return np.array(img.resize((size, size), Image.LANCZOS)).astype(float)


def _over(a, b):
    """Additive-ish max composite of two RGBA float frames."""
    out = a.copy()
    al = b[..., 3:4] / 255
    out[..., :3] = out[..., :3] * (1 - al) + b[..., :3] * al
    out[..., 3] = np.maximum(a[..., 3], b[..., 3])
    return out


def _tint(arr, rgb):
    out = arr.copy()
    out[..., :3] = np.array(rgb)[None, None, :] * (arr[..., :3].mean(axis=2, keepdims=True) / 255)
    return out


def _flash(size, color, hot, kind, i, n):
    """Impact flash variants: star / ring / hex / sun / void / burst / petal."""
    p = i / max(1, n - 1)
    r, th = radial(size)
    if kind == "star":                                       # hard 4-ray penetrator flash
        a = np.exp(-(r / (0.16 - 0.04 * p)) ** 2) + np.abs(np.cos(th * 2)) ** 60 * np.clip(1 - r / (1.0 - 0.3 * p), 0, 1) ** 1.5
        a += np.abs(np.cos(th * 2 + math.pi / 4)) ** 120 * np.clip(1 - r / 0.55, 0, 1) * 0.5
    elif kind == "bloom":                                    # incendiary bloom with 8 soft tongues
        n8 = periodic_noise(size, size, 4, 4, 3)
        a = np.exp(-(r / (0.35 + 0.25 * p)) ** 2) * (0.75 + 0.5 * n8) + np.abs(np.cos(th * 4 + i)) ** 8 * np.clip(1 - r / 0.9, 0, 1) * 0.5
    elif kind == "hex":                                      # cryo: crisp hexagonal flash ring
        hexr = r * np.cos((np.mod(th, math.pi / 3) - math.pi / 6)) / math.cos(math.pi / 6)
        ring = np.exp(-((hexr - (0.35 + 0.5 * p)) / 0.05) ** 2)
        a = ring + np.exp(-(r / 0.18) ** 2) * (1 - p)
    elif kind == "ring":                                     # ion: violet ring with crackle spokes
        ring = np.exp(-((r - (0.3 + 0.55 * p)) / 0.06) ** 2)
        spokes = np.abs(np.cos(th * 7 + i * 1.3)) ** 30 * np.clip(1 - r, 0, 1) * (r > 0.15)
        a = ring + spokes * 0.7 + np.exp(-(r / 0.14) ** 2)
    elif kind == "burst":                                    # cluster: chunky amber pop
        n8 = periodic_noise(size, size, 3, 3, 2)
        a = np.clip(1 - r / (0.55 + 0.3 * p) + (n8 - 0.5) * 0.6, 0, 1) ** 0.7 * (1 - 0.5 * p)
    elif kind == "void":                                     # singularity: dark core, lensed rim
        rim = np.exp(-((r - (0.35 + 0.15 * p)) / 0.07) ** 2)
        halo = np.exp(-((r - 0.6) / 0.25) ** 2) * 0.4
        a = np.clip(rim + halo + (r < 0.33) * 0.95, 0, 1)
        dark = (r < 0.33).astype(float)
        rgb = np.array(color)[None, None, :] * (rim + halo)[..., None] * 1.6
        rgb = rgb * (1 - dark[..., None]) + np.array((8, 0, 18))[None, None, :] * dark[..., None]
        return np.dstack([np.clip(rgb, 0, 255), a * 255])
    elif kind == "sun":                                      # sunburst: white disk + corona
        core = np.clip((0.32 + 0.1 * p - r) * 18, 0, 1)
        corona = np.exp(-((r - 0.36) / (0.22 + 0.1 * p)) ** 2) * (0.7 + 0.3 * np.abs(np.cos(th * 9 + i)) ** 3)
        a = np.clip(core + corona, 0, 1)
    elif kind == "petal":                                    # sentry: 3-petal muzzle flash
        a = np.exp(-(r / 0.14) ** 2) + np.abs(np.cos(th * 1.5 + 0.3)) ** 6 * np.clip(1 - r / (0.8 - 0.3 * p), 0, 1) ** 1.2
    elif kind == "dots":                                     # scatter: lime flash and pellet dots
        a = np.exp(-(r / 0.2) ** 2)
        for k in range(9):
            ang = k * 2.4 + i
            cx, cy = 0.5 + math.cos(ang) * (0.18 + 0.25 * p), 0.5 + math.sin(ang) * (0.18 + 0.25 * p)
            yy, xx = np.mgrid[0:size, 0:size] / size
            a += np.exp(-(((xx - cx) ** 2 + (yy - cy) ** 2) / 0.0012))
    else:
        a = np.exp(-(r / 0.3) ** 2)
    a = np.clip(a, 0, 1)
    w = np.clip(a ** 3, 0, 1)
    rgb = np.array(color)[None, None, :] * (1 - w[..., None]) + np.array(hot)[None, None, :] * w[..., None]
    return np.dstack([rgb, a * 255 * (1 - 0.35 * p)])


def _smoke(size, color, glow, i, n, density=1.0, swirl=3.0):
    p = i / max(1, n - 1)
    base = periodic_noise(size, size, 3, 3, 3)
    nn = warp(base, periodic_noise(size, size, 3, 3, 2), periodic_noise(size, size, 3, 3, 2), swirl + i * 1.5)
    nn = np.array(Image.fromarray((np.clip(nn, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))) / 255.0
    r, _ = radial(size)
    a = np.clip((1 - r / (0.55 + 0.35 * p) + (nn - 0.5) * 0.9) * 1.7, 0, 1) * (1 - p * 0.7) * density
    yy = np.linspace(0, 1, size)[:, None]
    hot = np.clip(yy - 0.45, 0, 1) * 1.8 * (1 - p) if glow is not None else 0
    shade = 0.55 + 0.45 * nn
    rgb = np.array(color)[None, None, :] * shade[..., None]
    if glow is not None:
        rgb = rgb + np.array(glow)[None, None, :] * hot[..., None] if np.ndim(hot) else rgb
    return np.dstack([np.clip(rgb, 0, 255), a * 230])


def _spark(size, color, hot, kind, i, n):
    p = i / max(1, n - 1)
    yy, xx = np.mgrid[0:size, 0:size].astype(float) / (size - 1) - 0.5
    if kind == "streak":                                     # long hot tracer
        a = np.exp(-(xx / 0.05) ** 2) * np.exp(-(yy / (0.42 - 0.1 * p)) ** 2)
    elif kind == "drop":                                     # molten droplet with a tail
        a = np.exp(-((xx ** 2 + (yy - 0.12) ** 2) / 0.012)) + np.exp(-(xx / 0.05) ** 2) * np.clip(-(yy - 0.12) / 0.45, 0, 1) * (yy < 0.12)
    elif kind == "shard":                                    # ice shard: diamond
        a = np.clip(1 - (np.abs(xx) / 0.14 + np.abs(yy) / 0.45), 0, 1) ** 0.6
        a += np.clip(1 - (np.abs(xx) / 0.05 + np.abs(yy) / 0.3), 0, 1) * 0.5
    elif kind == "crackle":                                  # ion: forked mini-bolt
        frame = _aa(lambda d, S: _bolt(d, S, (230, 200, 255, 255), i), size, blur=0.02)
        return _over(frame, glow_frame(size, 0.08, 0.3, color))
    elif kind == "twinkle":                                  # 4-point twinkle star
        r = np.hypot(xx, yy)
        a = np.exp(-(r / 0.08) ** 2) + (np.exp(-(xx / 0.02) ** 2) + np.exp(-(yy / 0.02) ** 2)) * np.clip(1 - r / (0.5 - 0.2 * p), 0, 1) ** 2
    elif kind == "comet":                                    # magenta comet curling inward
        r = np.hypot(xx, yy)
        th = np.arctan2(yy, xx)
        a = np.exp(-((r - 0.3) / 0.04) ** 2) * np.clip((th + math.pi) / (2 * math.pi) - 0.4, 0, 1) * 1.6 + np.exp(-((xx - 0.3) ** 2 + yy ** 2) / 0.004)
    else:                                                    # ember mote
        a = np.exp(-((xx ** 2 + yy ** 2) / 0.02))
    a = np.clip(a, 0, 1)
    w = a ** 2.5
    rgb = np.array(color)[None, None, :] * (1 - w[..., None]) + np.array(hot)[None, None, :] * w[..., None]
    return np.dstack([rgb, a * 255])


def _bolt(d, S, col, seed, branches=2, width=0.05):
    rr = random.Random(seed * 31 + 7)
    pts = [(S * 0.05, S * 0.5)]
    for k in range(1, 9):
        pts.append((S * (0.05 + k * 0.11), S * (0.5 + (rr.random() - 0.5) * 0.5)))
    d.line(pts, fill=col[:3] + (120,), width=max(1, int(S * width * 2.2)), joint="curve")
    d.line(pts, fill=col, width=max(1, int(S * width)), joint="curve")
    for b in range(branches):
        j = rr.randrange(2, 7)
        x, y = pts[j]
        bp = [(x, y)]
        for k in range(3):
            x += S * 0.08
            y += S * (rr.random() - 0.5) * 0.3
            bp.append((x, y))
        d.line(bp, fill=col, width=max(1, int(S * width * 0.6)))


AMMO_FX = {
    #  id           flash colour       flash core       kind      smoke colour      smoke glow       spark colour     spark hot        spark kind
    "tungsten":    ((120, 175, 255), (240, 248, 255), "star",  (120, 130, 150), (140, 180, 255), (150, 200, 255), (255, 255, 255), "streak"),
    "thermite":    ((255, 110, 20), (255, 240, 170), "bloom",  (40, 30, 26), (255, 120, 30), (255, 120, 20), (255, 245, 200), "drop"),
    "cryo":        ((110, 230, 255), (240, 255, 255), "hex",   (210, 240, 255), None, (140, 235, 255), (255, 255, 255), "shard"),
    "ion":         ((170, 90, 255), (250, 235, 255), "ring",   (90, 50, 150), (180, 110, 255), (180, 110, 255), (255, 255, 255), "crackle"),
    "cluster":     ((255, 190, 40), (255, 250, 210), "burst",  (110, 90, 70), (255, 170, 40), (255, 200, 60), (255, 255, 220), "twinkle"),
    "singularity": ((170, 70, 255), (255, 255, 255), "void",   (30, 10, 50), (150, 60, 255), (230, 80, 255), (255, 220, 255), "comet"),
    "sunburst":    ((200, 255, 100), (255, 255, 235), "sun",   (150, 120, 70), (230, 255, 120), (220, 255, 120), (255, 255, 240), "ember"),
}


def option_particles():
    for aid, (fc, fh, fk, sc, sg, kc, kh, kk) in AMMO_FX.items():
        particle(f"{aid}_flash", [_flash(64, fc, fh, fk, i, 5) for i in range(5)])
        particle(f"{aid}_smoke", [_smoke(48, sc, sg, i, 8, density=0.9 if aid != "cryo" else 0.7) for i in range(8)])
        particle(f"{aid}_spark", [_spark(32, kc, kh, kk, i, 4) for i in range(4)])

    # ---- muzzle devices ----
    # Tri-prong: teal three-way fork of hard light.
    def prong(d, S, i):
        c = (S / 2, S / 2)
        for k in range(3):
            a = k * 2 * math.pi / 3 + i * 0.3
            e = (c[0] + math.cos(a) * S * 0.45, c[1] + math.sin(a) * S * 0.45)
            d.line([c, e], fill=(80, 255, 220, 140), width=int(S * 0.07))
            d.line([c, e], fill=(220, 255, 250, 255), width=int(S * 0.025))
    particle("prong_arc", [_over(_aa(lambda d, S, i=i: prong(d, S, i), 32, blur=0.01), glow_frame(32, 0.12, 0.4, (60, 255, 210))) for i in range(4)])
    # Shroud: deep indigo suppressed flame cone.
    particle("shroud_flame", [ramp(np.clip(1.15 - radial(48, cy=34)[0] * 1.3 - (1 - np.linspace(0, 1, 48)[:, None]) * 0.5
                                           + (periodic_noise(48, 48, 5, 9, 3) - 0.5) * 0.9 - i * 0.07, 0, 1),
                                   [(0, (10, 0, 40, 0)), (0.3, (60, 30, 200, 150)), (0.65, (120, 110, 255, 230)), (1, (230, 235, 255, 255))])
                              for i in range(6)])
    particle("scatter_flash", [_flash(64, (150, 255, 60), (245, 255, 220), "dots", i, 4) for i in range(4)])
    # Graviton ripple: thin lensing ring, violet edge with a dark inner band.
    frames = []
    for i in range(6):
        r, _ = radial(64)
        rr0 = 0.2 + 0.13 * i
        edge = np.exp(-((r - rr0) / 0.025) ** 2)
        inner = np.exp(-((r - rr0 + 0.06) / 0.04) ** 2) * 0.5
        rgb = np.dstack([140 + 100 * edge, 90 + 60 * edge, 255 * np.ones_like(r)])
        frames.append(np.dstack([rgb * (1 - inner[..., None] * 0.8), np.clip(edge + inner, 0, 1) * 220 * (1 - i / 7)]))
    particle("graviton_ripple", frames)
    # Ghost: pale translucent wisps.
    particle("ghost_wisp", [_tint(_smoke(48, (230, 240, 255), None, i, 6, density=0.45, swirl=8.0), (220, 235, 255)) for i in range(6)])

    # ---- modules ----
    particle("capacitor_arc", [_over(_aa(lambda d, S, i=i: _bolt(d, S, (255, 250, 200, 255), 100 + i, 3, 0.045), 32, blur=0.01),
                                     glow_frame(32, 0.1, 0.35, (255, 230, 80))) for i in range(4)])
    particle("sentry_flash", [_flash(32, (255, 150, 40), (255, 250, 220), "petal", i, 3) for i in range(3)])
    frames = []
    for i in range(6):
        r, th = radial(64)
        rr0 = 0.15 + 0.15 * i
        ring = np.exp(-((r - rr0) / 0.05) ** 2) * (0.7 + 0.3 * np.abs(np.cos(th * 6)))
        rgb = np.dstack([150 + 100 * ring, 200 + 55 * ring, 255 * np.ones_like(r)])
        frames.append(np.dstack([rgb, np.clip(ring, 0, 1) * 235 * (1 - i / 7)]))
    particle("thruster_shock", frames)

    def reticle(d, S, i):
        c = S / 2
        g = S * (0.3 - 0.04 * i)
        col = (255, 60, 50, 255)
        w = max(2, int(S * 0.03))
        for sx in (-1, 1):
            for sy in (-1, 1):
                x0, y0 = c + sx * g, c + sy * g
                d.line([(x0, y0), (x0 - sx * S * 0.12, y0)], fill=col, width=w)
                d.line([(x0, y0), (x0, y0 - sy * S * 0.12)], fill=col, width=w)
        d.line([(c - S * 0.08, c), (c + S * 0.08, c)], fill=(255, 220, 210, 255), width=w)
        d.line([(c, c - S * 0.08), (c, c + S * 0.08)], fill=(255, 220, 210, 255), width=w)
    particle("target_ping", [_over(_aa(lambda d, S, i=i: reticle(d, S, i), 64), glow_frame(64, 0.05, 0.2, (255, 60, 50))) for i in range(4)])

    # ---- part drops ----
    particle("drone_trail", [_spark(32, (255, 140, 60), (255, 245, 230), "streak", i, 4) for i in range(4)])
    frames = []
    for i in range(5):
        r, _ = radial(32, cy=8)
        yy = np.linspace(0, 1, 32)[:, None]
        n2 = periodic_noise(32, 32, 4, 8, 2)
        v = np.clip(1.15 - r * 1.1 - yy * 0.2 + (n2 - 0.5) * 0.4 - i * 0.12, 0, 1)
        frames.append(ramp(v, [(0, (0, 30, 90, 0)), (0.35, (40, 140, 255, 170)), (0.7, (150, 220, 255, 235)), (1, (250, 255, 255, 255))]))
    particle("drone_jet", frames)
    particle("dock_spark", [glow_frame(16, 0.12 - i * 0.02, 0.35, (255, 220, 120), rays=4, ray_len=0.9, rot=i * 0.5) for i in range(4)])

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

    loadout_particles()
    option_particles()
    scan_textures()
    star_textures()
    rift_textures()

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


def _display_glass(sw, sh, tint):
    yy = np.linspace(0, 1, sh)[:, None]
    disp = np.dstack([np.broadcast_to(tint[0] * (0.6 + 0.4 * (1 - yy)), (sh, sw)),
                      np.broadcast_to(tint[1] * (0.6 + 0.4 * (1 - yy)), (sh, sw)),
                      np.broadcast_to(tint[2] * (0.6 + 0.4 * (1 - yy)), (sh, sw)), np.full((sh, sw), 255.0)]).copy()
    r = np.hypot((np.arange(sw)[None, :] - sw / 2) / sw, (np.arange(sh)[:, None] - sh / 2) / sh)
    disp[..., :3] *= (1 - 0.7 * r[..., None] ** 2)
    return Image.fromarray(np.clip(disp, 0, 255).astype(np.uint8), "RGBA")


def tablet_display():
    """
    The ARES-9 uplink tablet seen up close (matches the item model): carbon-black slate, raised
    off-white armour frame with hazard corners, ribbed handles, a cyan rim under the display.
    Display area: x 40..472, y 30..258 of 512x288 (keep in sync with TabletScreen).
    """
    W, H = 512, 288
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    weave = periodic_noise(H, W, 6, 6, 2)
    carbon = np.dstack([14 + weave * 12, 15 + weave * 12, 18 + weave * 14, np.full((H, W), 255.0)])
    yy, xx = np.mgrid[0:H, 0:W]
    carbon[((xx // 4 + yy // 4) % 2 == 0), :3] *= 1.18
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).polygon([(18, 0), (W - 1, 0), (W - 1, H - 19), (W - 19, H - 1), (0, H - 1), (0, 18)], fill=255)
    img.paste(Image.fromarray(np.clip(carbon, 0, 255).astype(np.uint8), "RGBA"), (0, 0), mask)
    # handles
    for x0 in (0, W - 22):
        d.rectangle([x0, 30, x0 + 21, H - 31], fill=(52, 58, 66, 255))
        for y in range(40, H - 40, 12):
            d.rectangle([x0 + 3, y, x0 + 18, y + 4], fill=(20, 22, 26, 255))
    # armour frame
    frame = (196, 202, 210, 255)
    d.rectangle([26, 12, W - 27, 22], fill=frame)
    d.rectangle([26, H - 26, W - 27, H - 14], fill=frame)
    d.rectangle([26, 22, 34, H - 26], fill=frame)
    d.rectangle([W - 35, 22, W - 27, H - 26], fill=frame)
    for (x0, y0) in ((26, 12), (W - 70, 12)):
        for k in range(0, 44, 8):
            d.polygon([(x0 + k, y0 + 10), (x0 + k + 4, y0 + 10), (x0 + k + 9, y0), (x0 + k + 5, y0)], fill=(214, 160, 24, 255))
    d.line([(26, 23), (W - 27, 23)], fill=(110, 116, 126, 255))
    # sensor + buttons
    d.rectangle([W // 2 - 40, 2, W // 2 + 40, 10], fill=(70, 76, 86, 255))
    d.ellipse([W // 2 - 4, 3, W // 2 + 4, 9], fill=(255, 60, 40, 255))
    for i, c in enumerate(((255, 70, 50), (255, 176, 32), (51, 224, 255), (51, 224, 255))):
        x = 60 + i * 40 if i < 2 else W - 180 + (i - 2) * 40
        d.rectangle([x, H - 23, x + 26, H - 17], fill=c + (255,))
    sx0, sy0, sx1, sy1 = 40, 30, 472, 258
    img.paste(_display_glass(sx1 - sx0, sy1 - sy0, (8, 16, 24)), (sx0, sy0))
    d.rectangle([sx0 - 2, sy1 + 1, sx1 + 1, sy1 + 3], fill=(51, 224, 255, 255))
    d.rectangle([sx0 - 1, sy0 - 1, sx1, sy1], outline=(4, 6, 8, 255))
    img.save(A / "textures/gui/tablet_display.png")


def panel_display():
    """
    The pod's field console seen up close (matches the model): dark armoured back plate,
    steel visor hood, side rails with grip pads, a cyan display rim, keypad row and status LEDs.
    Screen area: x 30..482, y 24..264 of 512x288 (keep in sync with PanelScreen).
    """
    W, H = 512, 288
    base = periodic_noise(H, W, 40, 60, 4)
    plate = np.dstack([22 + base * 14, 25 + base * 15, 30 + base * 17, np.full((H, W), 255.0)])
    img = Image.fromarray(np.clip(plate, 0, 255).astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(img)
    steel = (92, 100, 112, 255)
    d.rectangle([0, 0, W - 1, 15], fill=steel)                          # visor hood
    d.line([(0, 15), (W - 1, 15)], fill=(150, 158, 170, 255), width=2)
    hazard(img, (W // 2 - 80, 2, W // 2 + 80, 12), 10)
    d.rectangle([0, 16, 20, H - 1], fill=steel)                          # side rails
    d.rectangle([W - 21, 16, W - 1, H - 1], fill=steel)
    for y in range(40, H - 40, 14):
        d.rectangle([5, y, 15, y + 6], fill=(40, 44, 50, 255))
        d.rectangle([W - 16, y, W - 6, y + 6], fill=(40, 44, 50, 255))
    for x, c in ((80, (255, 70, 50)), (150, (255, 176, 32)), (220, (51, 224, 255)), (290, (51, 224, 255)), (360, (255, 176, 32))):
        d.rectangle([x - 4, H - 22, x + 46, H - 4], fill=(70, 76, 86, 255))
        d.rectangle([x, H - 19, x + 42, H - 8], fill=c + (255,))
    for i, c in enumerate(((140, 255, 80), (255, 176, 32), (255, 70, 50))):
        d.ellipse([W - 17, 30 + i * 14, W - 9, 38 + i * 14], fill=c + (255,))
    sx0, sy0, sx1, sy1 = 30, 24, 482, 264
    img.paste(_display_glass(sx1 - sx0, sy1 - sy0, (6, 14, 20)), (sx0, sy0))
    d.rectangle([sx0 - 3, sy0 - 3, sx1 + 2, sy1 + 2], outline=(51, 224, 255, 255), width=2)
    img.save(A / "textures/gui/panel_display.png")



# =============================================================================================
# Scan array: holographic wave, ore markers, target brackets, motes
# =============================================================================================


def scan_textures():
    E = A / "textures/effect"
    # wall: bright leading edge low on the band, fading upward, with fine scanlines and a hex mesh
    W, H = 128, 64
    yy, xx = np.mgrid[0:H, 0:W].astype(float)
    v = yy / (H - 1)                       # 0 top .. 1 bottom
    edge = np.exp(-((v - 0.72) / 0.05) ** 2)
    body = np.clip((v - 0.15) / 0.6, 0, 1) ** 2 * 0.45
    lines = ((yy % 4) < 1) * 0.25
    hexm = (np.abs(np.sin(xx / W * np.pi * 16) * np.sin(yy / H * np.pi * 8)) > 0.92) * 0.35 * (v > 0.2)
    a = np.clip(edge + body + lines * body * 2 + hexm * body * 2, 0, 1)
    save_rgba(np.dstack([np.full_like(a, 255), np.full_like(a, 255), np.full_like(a, 255), a * 255]), E / "scan_wall.png")
    # ground ring: hard outer rim, soft inner trail
    W, H = 64, 32
    yy, xx = np.mgrid[0:H, 0:W].astype(float)
    v = yy / (H - 1)                       # 0 inner .. 1 outer
    a = np.clip(np.exp(-((v - 0.88) / 0.06) ** 2) + v ** 3 * 0.45 + ((xx % 8) < 1) * 0.15 * v, 0, 1)
    save_rgba(np.dstack([np.full_like(a, 255), np.full_like(a, 255), np.full_like(a, 255), a * 255]), E / "scan_ring.png")

    # marker: diamond outline, crosshair ticks and a hot core (white, tinted per ore in game)
    def marker(d, S):
        c = S / 2
        r = S * 0.36
        pts = [(c, c - r), (c + r, c), (c, c + r), (c - r, c)]
        d.polygon(pts, outline=(255, 255, 255, 255), width=max(2, S // 24))
        r2 = S * 0.16
        d.polygon([(c, c - r2), (c + r2, c), (c, c + r2), (c - r2, c)], fill=(255, 255, 255, 230))
        t = S * 0.06
        for k in range(4):
            a_ = k * math.pi / 2
            x0, y0 = c + math.cos(a_) * r * 1.15, c + math.sin(a_) * r * 1.15
            x1, y1 = c + math.cos(a_) * r * 1.4, c + math.sin(a_) * r * 1.4
            d.line([(x0, y0), (x1, y1)], fill=(255, 255, 255, 200), width=max(1, int(t)))
    m = _aa(marker, 64)
    save_rgba(_over(m, glow_frame(64, 0.1, 0.35, (255, 255, 255))), E / "scan_marker.png")

    # bracket: four corner brackets
    def bracket(d, S):
        L, w, o = S * 0.22, max(2, S // 22), S * 0.08
        col = (255, 255, 255, 255)
        for sx in (0, 1):
            for sy in (0, 1):
                x = o if sx == 0 else S - o
                y = o if sy == 0 else S - o
                dx = L if sx == 0 else -L
                dy = L if sy == 0 else -L
                d.line([(x, y), (x + dx, y)], fill=col, width=w)
                d.line([(x, y), (x, y + dy)], fill=col, width=w)
        c = S / 2
        d.line([(c - S * 0.04, c), (c + S * 0.04, c)], fill=(255, 255, 255, 160), width=max(1, w // 2))
    save_rgba(_aa(bracket, 64), E / "scan_bracket.png")

    # particles
    particle("scan_mote", [glow_frame(16, 0.14 - i * 0.02, 0.4, (90, 235, 255)) for i in range(4)])
    frames = []
    for i in range(4):
        r, th = radial(64)
        ring = np.exp(-((r - (0.25 + 0.2 * i)) / 0.05) ** 2)
        core = np.exp(-(r / (0.12 - 0.02 * i)) ** 2)
        rays = np.abs(np.cos(th * 3 + i * 0.3)) ** 40 * np.clip(1 - r, 0, 1)
        a = np.clip(ring * (1 - i / 4) + core + rays * 0.6, 0, 1)
        frames.append(np.dstack([150 + 105 * a, 230 + 25 * a, np.full_like(a, 255), a * 255]))
    particle("scan_flare", frames)


# =============================================================================================
# Eclipse Core: star surface, corona, nova shell, plasma / ember / flash particles
# =============================================================================================


def star_textures():
    E = A / "textures/effect"
    # granulation: bright convection cells separated by dark lanes (tileable), warm grey (tinted in game)
    S = 256
    rs = np.random.default_rng(5)
    pts = rs.uniform(0, S, (420, 2))
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    d1 = np.full((S, S), 1e9, np.float32)
    d2 = np.full((S, S), 1e9, np.float32)
    for px, py in pts:
        dx = np.minimum(np.abs(xx - px), S - np.abs(xx - px))
        dy = np.minimum(np.abs(yy - py), S - np.abs(yy - py))
        d = np.sqrt(dx * dx + dy * dy)
        d2 = np.where(d < d1, d1, np.minimum(d2, d))
        d1 = np.minimum(d1, d)
    lanes = np.clip((d2 - d1) / 2.2, 0, 1)
    cell = np.clip(1 - d1 / 14.0, 0, 1)            # bright cell centres
    n = periodic_noise(S, S, 8, 8, 4)
    v = np.clip(0.5 + 0.5 * lanes ** 0.8 * (0.6 + 0.4 * cell + 0.35 * (n - 0.5)), 0, 1)
    v = np.asarray(Image.fromarray((v * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.9))).astype(np.float32) / 255
    spots = periodic_noise(S, S, 3, 3, 2)
    v *= np.clip(1.15 - np.clip(spots - 0.72, 0, 1) * 3.5, 0.35, 1)
    rgb = np.dstack([255 * np.ones_like(v), 255 * (0.55 + 0.45 * v), 255 * (0.25 + 0.75 * v ** 2)]) * v[..., None]
    save_rgba(np.dstack([rgb, np.full_like(v, 255)]), E / "star_surface.png")



# =============================================================================================
# Rift: deep space disk, nebula, orbit lines, planets, rift particles
# =============================================================================================


def _soft_noise(H, W, cells, seed, octaves=3):
    """Smooth tileable value noise: bicubic-upsampled random grids (no high-frequency grain)."""
    r = np.random.default_rng(seed)
    f = np.zeros((H, W), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        ch, cw = max(2, int(cells * H / W) * 2 ** o), cells * 2 ** o
        lo = np.tile(r.random((ch, cw)).astype(np.float32), (3, 3))
        up = np.asarray(Image.fromarray((lo * 255).astype(np.uint8)).resize((W * 3, H * 3), Image.BICUBIC)).astype(np.float32) / 255
        f += amp * up[H:2 * H, W:2 * W]
        tot += amp
        amp *= 0.5
    return f / tot


def _equirect_planet(W, H, fn):
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    lat = (0.5 - (yy + 0.5) / H) * np.pi
    lon = (xx + 0.5) / W * 2 * np.pi
    return fn(lat, lon, yy, xx)


def rift_textures():
    E = A / "textures/effect"
    rs = np.random.default_rng(23)
    # deep space: near-black indigo, two nebula clouds, thousands of stars (square, planar-mapped)
    S = 512
    n1 = periodic_noise(S, S, 4, 4, 5)
    n2 = periodic_noise(S, S, 6, 6, 4)
    base = np.dstack([np.full((S, S), 4.0), np.full((S, S), 3.0), np.full((S, S), 12.0)])
    neb = np.clip(n1 - 0.52, 0, 1) * 2.2
    neb2 = np.clip(n2 - 0.58, 0, 1) * 2.0
    img = base + neb[..., None] * np.array([60, 20, 110]) + neb2[..., None] * np.array([10, 50, 110])
    pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")
    d = ImageDraw.Draw(pil)
    for k in range(5200):
        x, y = rs.uniform(0, S), rs.uniform(0, S)
        m = rs.random() ** 7
        t = rs.random()
        col = np.array([0.8, 0.85, 1.0]) if t < 0.6 else np.array([1.0, 0.9, 0.8]) if t < 0.9 else np.array([0.9, 0.7, 1.0])
        v = 0.3 + 0.7 * m
        c = tuple(int(255 * min(1, v * ch)) for ch in col)
        if m > 0.5:
            d.ellipse([x - 1.1, y - 1.1, x + 1.1, y + 1.1], fill=c)
        else:
            d.point((x, y), fill=c)
    arr = np.asarray(pil).astype(np.float32)
    bloom = np.asarray(pil.filter(ImageFilter.GaussianBlur(1.6))).astype(np.float32)
    arr = np.clip(arr + bloom * 0.7, 0, 255)
    save_rgba(np.dstack([arr, np.full((S, S), 255.0)]), E / "rift_space.png")

    # nebula for the ring mapping (U around, V radial): soft clouds that fade at the rim
    W, H = 256, 64
    n = periodic_noise(H, W, 3, 10, 4)
    v = np.linspace(0, 1, H)[:, None]
    a = np.clip((n - 0.35) * 1.8, 0, 1) * np.clip(1 - v ** 2, 0, 1)
    save_rgba(np.dstack([180 + 75 * a, 120 + 60 * a, np.full_like(a, 255), a * 200]), E / "rift_nebula.png")

    # orbit line: a thin soft line across V
    W, H = 32, 32
    v = np.linspace(0, 1, H)[:, None] * np.ones((1, W))
    a = np.exp(-((v - 0.5) / 0.16) ** 2)
    save_rgba(np.dstack([np.full_like(a, 255), np.full_like(a, 255), np.full_like(a, 255), a * 255]), E / "orbit_ring.png")

    # planets (equirect 128x64)
    def rock(lat, lon, yy, xx):
        nz = _soft_noise(64, 128, 6, 47, 4)
        cr = _soft_noise(64, 128, 14, 53, 2)
        v = 0.55 + 0.35 * nz - np.clip(cr - 0.7, 0, 1) * 1.5
        return np.dstack([150 * v + 30, 135 * v + 25, 120 * v + 20])

    def ocean(lat, lon, yy, xx):
        nz = _soft_noise(64, 128, 6, 31, 4)
        land = nz > 0.52
        rgb = np.where(land[..., None], np.dstack([60 + 80 * nz, 120 + 60 * nz, 50 + 30 * nz]), np.dstack([20 + 0 * nz, 60 + 30 * nz, 150 + 60 * nz]))
        cap = np.abs(lat) > 1.2
        rgb[cap] = [235, 240, 250]
        cl = _soft_noise(64, 128, 8, 37, 3) > 0.62
        rgb[cl] = rgb[cl] * 0.3 + 230 * 0.7
        return rgb

    def desert(lat, lon, yy, xx):
        nz = _soft_noise(64, 128, 5, 43, 4)
        return np.dstack([200 * (0.6 + 0.4 * nz) + 30, 120 * (0.6 + 0.4 * nz) + 20, 70 * (0.6 + 0.4 * nz)])

    def gas(lat, lon, yy, xx):
        nz = periodic_noise(64, 128, 2, 16, 3)
        bands = 0.5 + 0.5 * np.sin(lat * 14 + nz * 3)
        rgb = np.dstack([180 + 60 * bands, 140 + 50 * bands, 100 + 40 * bands])
        spot = np.exp(-(((lat + 0.35) / 0.12) ** 2 + ((lon - 2.0) / 0.3) ** 2))
        rgb = rgb * (1 - spot[..., None] * 0.5) + spot[..., None] * np.array([200, 90, 60]) * 0.5
        return rgb

    def ice(lat, lon, yy, xx):
        nz = _soft_noise(64, 128, 5, 59, 3)
        return np.dstack([170 + 70 * nz, 210 + 40 * nz, 235 + 20 * nz])

    def lava(lat, lon, yy, xx):
        nz = _soft_noise(64, 128, 6, 41, 4)
        cracks = np.clip(1 - np.abs(nz - 0.5) * 12, 0, 1)
        return np.dstack([40 + 215 * cracks, 25 + 120 * cracks, 25 + 20 * cracks])

    for name, fn in (("rock", rock), ("ocean", ocean), ("desert", desert), ("gas", gas), ("ice", ice), ("lava", lava)):
        rgb = np.clip(_equirect_planet(128, 64, fn), 0, 255)
        save_rgba(np.dstack([rgb, np.full(rgb.shape[:2], 255.0)]), E / f"planet_{name}.png")

    # particles
    particle("rift_spark", [glow_frame(16, 0.14 - i * 0.02, 0.4, (190, 120, 255), rays=4, ray_len=0.9, rot=i * 0.4) for i in range(4)])
    frames = []
    for i in range(4):
        r, th = radial(16)
        a = np.exp(-(r / 0.12) ** 2) + (np.abs(np.cos(th * 2)) ** 60) * np.clip(1 - r / 0.9, 0, 1) * (0.6 - i * 0.12)
        a = np.clip(a, 0, 1)
        frames.append(np.dstack([220 + 35 * a, 200 + 55 * a, np.full_like(a, 255), a * 255]))
    particle("rift_dust", frames)
    frames = []
    for i in range(4):
        r, th = radial(128)
        a = np.exp(-(r / (0.16 + 0.1 * i)) ** 2) + 0.35 * np.exp(-(r / 0.7) ** 2)
        a = np.clip(a, 0, 1) * (1 - i * 0.2)
        frames.append(np.dstack([210 + 45 * a, 170 + 85 * a, np.full_like(a, 255), a * 255]))
    particle("rift_flash", frames)
    frames = []
    for i in range(6):
        r, _ = radial(32)
        nz = periodic_noise(32, 32, 4, 4, 3)
        a = np.clip((1 - r / (0.6 + 0.06 * i)) * (0.5 + 0.8 * nz), 0, 1) * (1 - i / 7)
        frames.append(np.dstack([140 + 60 * nz, 70 + 40 * nz, np.full((32, 32), 230.0), a * 200]))
    particle("rift_wisp", frames)

def main():
    (A / "textures/gui").mkdir(parents=True, exist_ok=True)
    panel_display()
    tablet_display()
    beam_textures()
    loadout_effect_textures()
    particles()
    hud_panel()
    print("textures written:", len(list((A / "textures").rglob("*.png"))))


if __name__ == "__main__":
    main()
