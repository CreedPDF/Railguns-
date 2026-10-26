#!/usr/bin/env python3
"""
Effect and display textures for the Gungnir Railway Station, painted procedurally (numpy + Pillow).
Additive textures are white with the intensity in alpha (the game tints them); translucent ones carry real colour.

  textures/grs/fx/orb.png        the light-speed spear on its lap: a hot core in a soft corona
  textures/grs/fx/flare.png      a six-ray star flare with a ring
  textures/grs/fx/streak.png     a light streak (u = along, head at u=1)
  textures/grs/fx/shock.png      a shockwave ring with a ragged leading edge
  textures/grs/fx/scorch.png     the burnt ground around the spear (translucent, black and molten orange)
  textures/grs/fx/cracks.png     the molten cracks of the scorch (additive)
  textures/grs/fx/dust.png       a soft dust/smoke puff (translucent)
  textures/grs/fx/beam.png       the descent beam's cross-profile with flowing filaments
  textures/grs/hud/lens.png      the goggles' lens: vignette, hex grid, scanlines (drawn over the screen)
  textures/grs/hud/reticle.png   the targeting reticle
  textures/grs/hud/station.png   the station schematic for the display
  textures/grs/fx/jupiter_ring.png  Jupiter's faint dusty rings, the station's railway (u = inner -> outer edge)
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
A = ROOT / "src/main/resources/assets/orbital_railgun/textures/grs"
rng = np.random.default_rng(7331)


def grid(n):
    y, x = (np.mgrid[0:n, 0:n] + 0.5) / n * 2 - 1
    return x, y


def fbm(n, octaves=5, base=4, seed=0):
    r = np.random.default_rng(seed)
    out = np.zeros((n, n))
    amp = 1.0
    for o in range(octaves):
        s = base * 2 ** o
        g = r.random((s + 1, s + 1))
        img = Image.fromarray((g * 255).astype(np.uint8)).resize((n, n), Image.BICUBIC)
        out += np.asarray(img) / 255 * amp
        amp *= 0.5
    return out / (2 - 2 ** (1 - octaves))


def save_add(a, path, rgb=(1, 1, 1)):
    a = np.clip(a, 0, 1)
    im = np.zeros(a.shape + (4,))
    im[..., 0], im[..., 1], im[..., 2] = rgb
    im[..., 3] = a
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray((im * 255).astype(np.uint8)).save(path)
    print("wrote", path.relative_to(ROOT))


def save_rgba(im, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray((np.clip(im, 0, 1) * 255).astype(np.uint8)).save(path)
    print("wrote", path.relative_to(ROOT))


def orb():
    x, y = grid(256)
    r = np.sqrt(x * x + y * y)
    a = np.exp(-(r / 0.08) ** 2) + 0.55 * np.exp(-(r / 0.25) ** 2) + 0.22 * np.exp(-(r / 0.6) ** 1.5)
    save_add(a / a.max() * (r < 1), A / "fx/orb.png")


def flare():
    x, y = grid(512)
    r = np.sqrt(x * x + y * y) + 1e-6
    th = np.arctan2(y, x)
    rays = np.zeros_like(r)
    for k in range(6):
        ang = k * math.pi / 3 + 0.12
        d = np.abs(np.sin(th - ang))
        rays += np.exp(-(d * r / 0.006) ** 2) * np.exp(-r / (0.5 if k % 2 == 0 else 0.3))
    ring = np.exp(-((r - 0.46) / 0.012) ** 2) * 0.25
    core = np.exp(-(r / 0.05) ** 2) + 0.4 * np.exp(-(r / 0.18) ** 2)
    a = rays * 0.9 + ring + core
    save_add(a / a.max() * (r < 1), A / "fx/flare.png")


def streak():
    w, h = 512, 64
    u = (np.arange(w) + 0.5) / w
    v = (np.arange(h) + 0.5) / h * 2 - 1
    U, V = np.meshgrid(u, v)
    along = U ** 2.2 * (1 - np.exp(-(1 - U) / 0.01))
    across = np.exp(-(V / (0.15 + 0.35 * U)) ** 2)
    core = np.exp(-(V / 0.06) ** 2) * U ** 4
    a = along * across + core
    save_add(a / a.max(), A / "fx/streak.png")


def shock():
    x, y = grid(512)
    r = np.sqrt(x * x + y * y)
    th = np.arctan2(y, x)
    n = fbm(512, 5, 6, 3)
    edge = 0.9 + 0.008 * np.sin(th * 31) + (n - 0.5) * 0.08
    a = np.exp(-((r - edge) / 0.02) ** 2) + np.clip((r - edge * 0.7) / (edge * 0.3), 0, 1) ** 3 * (r < edge) * 0.4 * n
    save_add(np.clip(a, 0, 1) * (r < 1), A / "fx/shock.png")


def scorch():
    n = 1024
    x, y = grid(n)
    r = np.sqrt(x * x + y * y)
    th = np.arctan2(y, x)
    f = fbm(n, 6, 5, 11)
    f2 = fbm(n, 5, 12, 12)
    burn = np.clip(1 - (r + (f - 0.5) * 0.35) / 0.95, 0, 1) ** 0.6
    # radial cracks: thin lines along jittered rays plus a cellular net in the middle
    cracks = np.zeros_like(r)
    for k in range(44):
        ang = rng.uniform(0, math.tau)
        wob = np.sin(r * rng.uniform(8, 18) + rng.uniform(0, 6)) * rng.uniform(0.02, 0.06)
        d = np.abs(np.sin(th - ang - wob)) * r
        L = rng.uniform(0.35, 0.95)
        cracks = np.maximum(cracks, np.exp(-(d / 0.0045) ** 2) * np.clip(1 - r / L, 0, 1) ** 0.5)
    cell = np.abs(np.sin(f2 * 40)) < 0.06
    cracks = np.maximum(cracks, cell * np.clip(1 - r / 0.55, 0, 1))
    col = np.zeros((n, n, 4))
    base = 0.03 + 0.05 * f
    col[..., 0] = base + cracks * 0.8
    col[..., 1] = base * 0.9 + cracks * 0.28
    col[..., 2] = base * 0.8 + cracks * 0.04
    col[..., 3] = np.clip(burn * (0.75 + 0.25 * f2), 0, 0.96)
    save_rgba(col, A / "fx/scorch.png")
    glow = cracks * np.clip(1 - r * 0.9, 0, 1) * (0.6 + 0.4 * f)
    save_add(glow, A / "fx/cracks.png")


def dust():
    x, y = grid(256)
    r = np.sqrt(x * x + y * y)
    f = fbm(256, 5, 4, 21)
    a = np.clip(1 - (r + (f - 0.5) * 0.5) / 0.9, 0, 1) ** 1.6
    col = np.zeros((256, 256, 4))
    shade = 0.32 + 0.4 * f
    col[..., 0] = shade * 1.0
    col[..., 1] = shade * 0.92
    col[..., 2] = shade * 0.85
    col[..., 3] = a * 0.9
    save_rgba(col, A / "fx/dust.png")


def beam():
    w, h = 128, 512
    u = (np.arange(w) + 0.5) / w * 2 - 1
    v = (np.arange(h) + 0.5) / h
    U, V = np.meshgrid(u, v)
    core = np.exp(-(U / 0.12) ** 2)
    glow = np.exp(-(U / 0.5) ** 2) * 0.45
    fil = np.zeros_like(U)
    for k in range(9):
        c = rng.uniform(-0.6, 0.6)
        fil += np.exp(-((U - c - 0.08 * np.sin(V * math.tau * rng.integers(1, 4) + k)) / 0.025) ** 2) * rng.uniform(0.2, 0.6)
    a = core + glow + fil * np.exp(-(U / 0.7) ** 2)
    save_add(a / a.max(), A / "fx/beam.png")


def lens():
    w, h = 960, 540
    y, x = np.mgrid[0:h, 0:w]
    nx, ny = (x + 0.5) / w * 2 - 1, (y + 0.5) / h * 2 - 1
    # two eye cups merging into one view, darkest at the rim
    r = np.sqrt((nx * 1.0) ** 2 + (ny * 1.05) ** 2)
    vig = np.clip((r - 0.82) / 0.4, 0, 1) ** 1.6
    # hex grid
    s = 26.0
    qx = x / s
    qy = y / (s * 0.866)
    row = np.floor(qy)
    off = (row % 2) * 0.5
    hx = (qx + off) % 1.0 - 0.5
    hy = qy % 1.0 - 0.5
    hexd = np.maximum(np.abs(hx), (np.abs(hx) * 0.5 + np.abs(hy) * 0.866 * 1.15))
    hexline = (hexd > 0.47).astype(float) * 0.06 * np.clip(r * 1.3, 0, 1)
    scan = (y % 3 == 0) * 0.035
    img = np.zeros((h, w, 4))
    img[..., 0] = 1.0
    img[..., 1] = 0.45
    img[..., 2] = 0.08
    img[..., 3] = np.clip(hexline + scan, 0, 1)
    dark = vig
    # composite the dark rim over the tint
    out = np.zeros((h, w, 4))
    a1 = img[..., 3]
    out[..., 3] = dark + a1 * (1 - dark)
    for c in range(3):
        out[..., c] = (img[..., c] * a1 * (1 - dark)) / np.maximum(out[..., 3], 1e-4)
    out[..., 0:3] *= 1
    save_rgba(out, A / "hud/lens.png")


def reticle():
    n = 256
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = n / 2
    col = (255, 255, 255, 255)
    for rr, wdt in ((92, 3), (70, 2)):
        for k in range(4):
            a0 = k * 90 + 12
            d.arc((c - rr, c - rr, c + rr, c + rr), a0, a0 + 66, fill=col, width=wdt)
    for k in range(4):
        a = math.radians(k * 90 + 45)
        d.line((c + math.cos(a) * 30, c + math.sin(a) * 30, c + math.cos(a) * 58, c + math.sin(a) * 58), fill=col, width=3)
    for k in range(36):
        a = math.radians(k * 10)
        r0 = 112 if k % 3 == 0 else 116
        d.line((c + math.cos(a) * r0, c + math.sin(a) * r0, c + math.cos(a) * 122, c + math.sin(a) * 122), fill=col, width=2)
    d.polygon([(c, c - 8), (c + 8, c), (c, c + 8), (c - 8, c)], outline=col, width=2)
    im = im.filter(ImageFilter.GaussianBlur(0.6))
    p = A / "hud/reticle.png"
    p.parent.mkdir(parents=True, exist_ok=True)
    im.save(p)
    print("wrote", p.relative_to(ROOT))


def station_schematic():
    n = 256
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = n / 2
    col = (255, 255, 255, 230)
    d.ellipse((c - 104, c - 104, c + 104, c + 104), outline=col, width=5)
    d.ellipse((c - 92, c - 92, c + 92, c + 92), outline=(255, 255, 255, 120), width=2)
    for k in range(36):
        a = math.radians(k * 10)
        d.line((c + math.cos(a) * 98, c + math.sin(a) * 98, c + math.cos(a) * 110, c + math.sin(a) * 110), fill=col, width=3)
    for k in range(6):
        a = math.radians(k * 60 + 30)
        d.line((c + math.cos(a) * 20, c + math.sin(a) * 20, c + math.cos(a) * 92, c + math.sin(a) * 92), fill=(255, 255, 255, 150), width=3)
    d.ellipse((c - 20, c - 20, c + 20, c + 20), outline=col, width=4)
    d.ellipse((c - 8, c - 8, c + 8, c + 8), fill=col)
    d.line((c - 30, c - 124, c + 30, c - 124), fill=col, width=4)
    p = A / "hud/station.png"
    im.save(p)
    print("wrote", p.relative_to(ROOT))


def jupiter_ring():
    n = 1024
    u = (np.arange(n) + 0.5) / n
    # main ring, a brighter halo inside it, gossamer rings fading outward, fine ringlets everywhere
    main = np.exp(-((u - 0.47) / 0.07) ** 2) * 0.95
    halo = np.exp(-((u - 0.18) / 0.12) ** 2) * 0.35
    gossamer = np.clip(1 - np.abs(u - 0.72) / 0.26, 0, 1) ** 1.5 * 0.28
    lets = 0.75 + 0.25 * np.sin(u * 310) * np.sin(u * 97 + 1.3)
    a = np.clip((main + halo + gossamer) * lets, 0, 1)
    a *= np.clip(u / 0.05, 0, 1) * np.clip((1 - u) / 0.08, 0, 1)
    im = np.zeros((8, n, 4))
    im[..., 0] = 0.78 + 0.12 * main
    im[..., 1] = 0.55 + 0.08 * main
    im[..., 2] = 0.36
    im[..., 3] = a * 0.85
    save_rgba(im, A / "fx/jupiter_ring.png")


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1:
        for f in sys.argv[1:]:
            globals()[f]()
        raise SystemExit
    jupiter_ring()
    orb()
    flare()
    streak()
    shock()
    scorch()
    dust()
    beam()
    lens()
    reticle()
    station_schematic()
