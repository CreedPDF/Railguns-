#!/usr/bin/env python3
"""
Effect textures for the KRL (Kinetic Rail Launcher), painted procedurally with numpy + Pillow.
Additive textures are white with the intensity in alpha (the game tints them); translucent ones carry colour.

  textures/krl/fx/smoke.png    2x2 atlas of billowing smoke puffs, lit from above (translucent)
  textures/krl/fx/flame.png    the motor's exhaust plume with shock diamonds (additive; u across, v along, v=0 at the nozzle)
  textures/krl/fx/glow.png     a soft round glow (additive)
  textures/krl/fx/ring.png     a shock ring with a sharp leading edge (additive)
  textures/krl/fx/vapor.png    the condensation collar of the sonic boom (translucent white, wispy)
  textures/krl/fx/plasma.png   tileable turbulent plasma (additive)
  textures/krl/fx/flare.png    a hot four-point star flare (additive)
  textures/krl/fx/scorch.png   the burnt crater decal (translucent)
  textures/krl/fx/cracks.png   its glowing cracks (additive)
  textures/krl/fx/trail.png    a soft streak for trails (additive; u along, bright at u=1)
  textures/krl/fx/white.png    plain white (for the plasma sheath shells drawn over the spear's nose)
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
A = ROOT / "src/main/resources/assets/orbital_railgun/textures/krl/fx"
rng = np.random.default_rng(4011)


def grid(n, m=None):
    m = m or n
    y, x = (np.mgrid[0:m, 0:n] + 0.5)
    return x / n * 2 - 1, y / m * 2 - 1


def fnoise(h, w, scale, seed, octaves=5):
    r = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        wn = r.standard_normal((h, w))
        ky = np.fft.fftfreq(h)[:, None]
        kx = np.fft.fftfreq(w)[None, :]
        s = scale / 2 ** o
        f = np.exp(-((kx * s) ** 2 + (ky * s) ** 2) * 2 * math.pi ** 2)
        n = np.real(np.fft.ifft2(np.fft.fft2(wn) * f))
        out += n / (n.std() + 1e-9) * amp
        tot += amp
        amp *= 0.55
    return out / tot


def save_add(a, name):
    im = np.zeros(a.shape + (4,))
    im[..., :3] = 1
    im[..., 3] = np.clip(a, 0, 1)
    save(im, name)


def save(im, name):
    A.mkdir(parents=True, exist_ok=True)
    Image.fromarray((np.clip(im, 0, 1) * 255).astype(np.uint8)).save(A / name)
    print("wrote", (A / name).relative_to(ROOT))


def smoke():
    n = 256
    atlas = np.zeros((n * 2, n * 2, 4))
    for k in range(4):
        x, y = grid(n)
        nz = fnoise(n, n, 18, 10 + k, 5)
        blobs = np.zeros((n, n))
        for _ in range(9):
            cx, cy, r = rng.uniform(-0.3, 0.3), rng.uniform(-0.3, 0.3), rng.uniform(0.4, 0.62)
            blobs = np.maximum(blobs, 1 - np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / r)
        d = np.clip(blobs + nz * 0.18, 0, 1)
        a = np.clip(d * 2.4, 0, 1) ** 0.9 * np.clip((1 - np.sqrt(x * x + y * y)) / 0.25, 0, 1)
        # lit from above: brighter on top, darker underneath; billow detail
        shade = np.clip(0.55 - y * 0.25 + nz * 0.12, 0.2, 1)
        v = shade * (0.85 + fnoise(n, n, 6, 30 + k, 3) * 0.08)
        tile = np.stack([v, v * 0.97, v * 0.95, a * 0.92], -1)
        oy, ox = (k // 2) * n, (k % 2) * n
        atlas[oy:oy + n, ox:ox + n] = tile
    save(atlas, "smoke.png")


def flame():
    w, h = 128, 512
    x, y = grid(w, h)
    v = (y + 1) / 2  # 0 at nozzle
    width = 0.32 + 0.55 * v ** 0.7
    core = np.exp(-(x / (width * 0.45)) ** 2) * (1 - v) ** 0.8
    body = np.exp(-(x / width) ** 2) * (1 - v) ** 1.6
    diamonds = np.clip(np.cos(v * 2 * math.pi * 5.5), 0, 1) ** 6 * np.exp(-(x / 0.18) ** 2) * (1 - v) ** 1.2
    turb = 0.85 + 0.15 * fnoise(h, w, 10, 5, 3)
    save_add((core * 1.2 + body * 0.6 + diamonds * 0.8) * turb, "flame.png")


def glow():
    x, y = grid(256)
    r = np.sqrt(x * x + y * y)
    save_add(np.exp(-(r / 0.38) ** 2) + 0.25 * np.exp(-(r / 0.55) ** 2) * np.clip((1 - r) / 0.3, 0, 1), "glow.png")


def ring():
    x, y = grid(512)
    r = np.sqrt(x * x + y * y)
    edge = np.exp(-((r - 0.9) / 0.02) ** 2)
    trail = np.clip((r - 0.55) / 0.35, 0, 1) ** 3 * (r < 0.9)
    nz = 0.8 + 0.2 * fnoise(512, 512, 30, 7, 3)
    save_add((edge + trail * 0.5) * nz, "ring.png")


def vapor():
    x, y = grid(512)
    r = np.sqrt(x * x + y * y)
    ang = np.arctan2(y, x)
    wisp = fnoise(512, 512, 20, 8, 5)
    band = np.exp(-((r - 0.7 - wisp * 0.06) / 0.16) ** 2)
    streaks = 0.7 + 0.3 * np.cos(ang * 40 + wisp * 6)
    a = np.clip(band * streaks * (0.8 + wisp * 0.3), 0, 1)
    im = np.stack([np.ones_like(a) * 0.96, np.ones_like(a) * 0.96, np.ones_like(a) * 0.98, a * 0.85], -1)
    save(im, "vapor.png")


def plasma():
    n = 512
    a = fnoise(n, n, 40, 12, 6)
    b = fnoise(n, n, 12, 13, 4)
    v = np.clip(0.5 + 0.35 * a + 0.25 * np.abs(b), 0, 1) ** 1.5
    save_add(v, "plasma.png")


def flare():
    x, y = grid(512)
    r = np.sqrt(x * x + y * y)
    rays = np.exp(-np.abs(x) / 0.012) * np.exp(-np.abs(y) / 0.6) + np.exp(-np.abs(y) / 0.012) * np.exp(-np.abs(x) / 0.6)
    core = np.exp(-(r / 0.08) ** 2) + 0.4 * np.exp(-(r / 0.3) ** 2)
    save_add(core + rays * 0.8, "flare.png")


def scorch():
    n = 512
    x, y = grid(n)
    r = np.sqrt(x * x + y * y)
    nz = fnoise(n, n, 25, 21, 5)
    rr = r + nz * 0.12
    a = np.clip(1.15 - rr * 1.15, 0, 1) ** 0.7
    v = 0.03 + 0.05 * np.clip(rr, 0, 1) + nz * 0.02
    im = np.stack([v * 1.1, v * 0.9, v * 0.85, a * 0.95], -1)
    save(im, "scorch.png")
    # glowing cracks: thin ridges of a warped noise, radiating
    ang = np.arctan2(y, x)
    cr = np.abs(np.sin(ang * 9 + nz * 7)) < 0.06
    ridge = np.abs(fnoise(n, n, 14, 22, 4)) < 0.05
    g = (cr | ridge) * np.clip(1 - rr, 0, 1) ** 0.8
    gl = np.asarray(Image.fromarray((g * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2))) / 255
    save_add(np.clip(g + gl * 1.3, 0, 1), "cracks.png")


def trail():
    w, h = 256, 32
    u = (np.arange(w) + 0.5) / w
    v = (np.arange(h) + 0.5) / h * 2 - 1
    a = (u[None, :] ** 1.6) * np.exp(-(v[:, None] / 0.5) ** 2)
    save_add(a, "trail.png")


def white():
    save(np.ones((4, 4, 4)), "white.png")


if __name__ == "__main__":
    white()
    smoke()
    flame()
    glow()
    ring()
    vapor()
    plasma()
    flare()
    scorch()
    trail()
