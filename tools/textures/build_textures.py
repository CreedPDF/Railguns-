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
    """Sprites for the loadout options (muzzles, ammunition, modules) and the supply capsule."""
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
