#!/usr/bin/env python3
"""
Effect textures for Space Command (orbital strike, station railgun), generated from scratch: white RGB with the shape
in the alpha channel (the game tints them by vertex colour and draws them additively).

  sc_beam     the strike beam's body: a soft column with fine flowing filaments, tiles vertically      64 x 256
  sc_core     the beam's white-hot core: a narrow hard line with a soft skirt, tiles vertically           32 x 128
  sc_spiral   a twisting plasma ribbon wound round the beam (tiles in both directions)                     128 x 128
  sc_reticle  the targeting reticle painted on the ground: rings, tick marks, chevrons, segmented arcs      512 x 512
  sc_shock    a shockwave annulus with a torn outer edge                                                   256 x 256
  sc_flare    a lens-flare star: round glow plus four long spikes                                          256 x 256
  sc_tracer   the railgun slug's trail: a bright needle fading to the tail                                 256 x 32
  sc_scorch   the burn scar under the beam (dark, alpha-blended)                                           256 x 256

Run: python3 tools/textures/station_textures.py
"""
from pathlib import Path

import numpy as np
from PIL import Image

OUT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/orbital_railgun/textures/station/fx"
rng = np.random.default_rng(4242)


def save(name, alpha, rgb=None):
    a = np.clip(alpha, 0, 1)
    h, w = a.shape
    if rgb is None:
        rgb = np.ones((h, w, 3))
    img = np.dstack([np.clip(rgb, 0, 1), a])
    OUT.mkdir(parents=True, exist_ok=True)
    Image.fromarray((img * 255 + 0.5).astype(np.uint8), "RGBA").save(OUT / f"{name}.png", optimize=True)
    print(name, w, h)


def tile_noise(h, w, octaves=4, base=4, seed=0):
    """Tileable value-ish noise from summed random Fourier modes."""
    r = np.random.default_rng(seed)
    y, x = np.mgrid[0:h, 0:w]
    out = np.zeros((h, w))
    amp = 1.0
    for o in range(octaves):
        f = base * 2 ** o
        for _ in range(6):
            kx, ky = r.integers(-f, f + 1), r.integers(-f, f + 1)
            ph = r.random() * np.pi * 2
            out += amp * np.cos(2 * np.pi * (kx * x / w + ky * y / h) + ph)
        amp *= 0.5
    out -= out.min()
    return out / out.max()


def beam():
    h, w = 256, 64
    y, x = np.mgrid[0:h, 0:w]
    u = (x + 0.5) / w * 2 - 1
    body = np.exp(-(u / 0.55) ** 2)
    fil = tile_noise(h, w, 4, 1, seed=1)
    streak = tile_noise(h, w, 3, 2, seed=2)
    # filaments: stretched along the beam (noise mostly varies across)
    fil = np.roll(fil, 0, axis=0)
    a = body * (0.55 + 0.45 * fil) * (0.8 + 0.2 * streak)
    a *= np.clip(1.2 - np.abs(u) ** 3, 0, 1)
    save("sc_beam", a)


def core():
    h, w = 128, 32
    y, x = np.mgrid[0:h, 0:w]
    u = (x + 0.5) / w * 2 - 1
    n = tile_noise(h, w, 3, 2, seed=3)
    a = np.exp(-(u / 0.12) ** 2) + 0.35 * np.exp(-(u / 0.45) ** 2) * (0.7 + 0.3 * n)
    save("sc_core", a)


def spiral():
    h, w = 128, 128
    y, x = np.mgrid[0:h, 0:w]
    a = np.zeros((h, w))
    for k in range(2):
        ph = (x / w + y / h * 1.0 + k * 0.5) % 1.0
        d = np.minimum(ph, 1 - ph)
        a += np.exp(-(d / 0.06) ** 2)
    n = tile_noise(h, w, 3, 2, seed=5)
    a *= 0.6 + 0.4 * n
    save("sc_spiral", a)


def reticle():
    s = 512
    y, x = np.mgrid[0:s, 0:s]
    cx = (x + 0.5) / s * 2 - 1
    cy = (y + 0.5) / s * 2 - 1
    r = np.hypot(cx, cy)
    ang = np.arctan2(cy, cx)
    a = np.zeros((s, s))

    def ring(r0, wdt, k=1.0):
        return k * np.exp(-((r - r0) / wdt) ** 2)

    a += ring(0.96, 0.008)
    a += ring(0.9, 0.004) * 0.7
    # segmented arc band (8 segments with gaps)
    seg = ((ang / (2 * np.pi) * 8) % 1.0) < 0.78
    a += ring(0.8, 0.018) * seg
    # 72 tick marks
    tick = (((ang / (2 * np.pi) * 72) % 1.0) < 0.12) & (r > 0.86) & (r < 0.93)
    a += tick * 0.9
    # long ticks at the quarters
    q = (np.abs(((ang / (np.pi / 2)) + 0.5) % 1.0 - 0.5) < 0.012) & (r > 0.55) & (r < 0.97)
    a += q * 1.0
    # inner rings and a dashed ring
    a += ring(0.5, 0.006)
    dash = ((ang / (2 * np.pi) * 36) % 1.0) < 0.5
    a += ring(0.38, 0.01) * dash * 0.8
    a += ring(0.12, 0.012)
    # four chevrons pointing in
    for k in range(4):
        th = k * np.pi / 2 + np.pi / 4
        rx = cx * np.cos(th) + cy * np.sin(th)
        ry = -cx * np.sin(th) + cy * np.cos(th)
        v = (np.abs(ry) < 0.12) & (np.abs(rx - 0.66 - np.abs(ry) * 0.9) < 0.018)
        a += v * 0.95
    a = np.clip(a, 0, 1) * (r < 0.99)
    save("sc_reticle", a)


def shock():
    s = 256
    y, x = np.mgrid[0:s, 0:s]
    cx = (x + 0.5) / s * 2 - 1
    cy = (y + 0.5) / s * 2 - 1
    r = np.hypot(cx, cy)
    ang = np.arctan2(cy, cx)
    torn = 0.04 * np.sin(ang * 23 + 1.3) + 0.03 * np.sin(ang * 41) + 0.02 * np.sin(ang * 7 + 0.4)
    edge = 0.9 + torn
    a = np.clip((r - (edge - 0.35)) / 0.35, 0, 1) ** 2 * np.clip((edge - r) / 0.03, 0, 1)
    a += 0.5 * np.exp(-((r - edge + 0.01) / 0.02) ** 2)
    save("sc_shock", np.clip(a, 0, 1) * (r < 1))


def flare():
    s = 256
    y, x = np.mgrid[0:s, 0:s]
    cx = (x + 0.5) / s * 2 - 1
    cy = (y + 0.5) / s * 2 - 1
    r = np.hypot(cx, cy)
    a = np.exp(-(r / 0.18) ** 2) + 0.35 * np.exp(-(r / 0.45) ** 2)
    a += np.exp(-(np.abs(cy) / 0.012) ** 2) * np.clip(1 - np.abs(cx), 0, 1) ** 2 * 0.9
    a += np.exp(-(np.abs(cx) / 0.012) ** 2) * np.clip(1 - np.abs(cy), 0, 1) ** 2 * 0.9
    save("sc_flare", np.clip(a, 0, 1) * (r < 1))


def tracer():
    h, w = 32, 256
    y, x = np.mgrid[0:h, 0:w]
    u = (y + 0.5) / h * 2 - 1
    t = (x + 0.5) / w
    a = np.exp(-(u / 0.18) ** 2) * t ** 1.5 + 0.4 * np.exp(-(u / 0.5) ** 2) * t ** 3
    save("sc_tracer", a)


def scorch():
    s = 256
    y, x = np.mgrid[0:s, 0:s]
    cx = (x + 0.5) / s * 2 - 1
    cy = (y + 0.5) / s * 2 - 1
    r = np.hypot(cx, cy)
    n = tile_noise(s, s, 4, 3, seed=9)
    a = np.clip(1.05 - r - 0.35 * (n - 0.5), 0, 1) ** 0.8
    rays = 0.15 * (np.cos(np.arctan2(cy, cx) * 17) > 0.6) * np.clip(1 - r, 0, 1)
    a = np.clip(a + rays, 0, 1) * (r < 1)
    rgb = np.dstack([np.full((s, s), 0.06), np.full((s, s), 0.045), np.full((s, s), 0.035)])
    save("sc_scorch", a * 0.92, rgb)


if __name__ == "__main__":
    beam()
    core()
    spiral()
    reticle()
    shock()
    flare()
    tracer()
    scorch()
