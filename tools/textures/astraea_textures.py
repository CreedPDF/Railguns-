#!/usr/bin/env python3
"""
Textures for ASTRAEA // The Worldsplitter, generated from scratch (numpy noise, nothing sampled). Written to
assets/orbital_railgun/textures/astraea/. White, silver and pale gold light; no blue.

  rift_void.png      1024^2  inside the Ascension rift: a deep void with a slow vortex of pale-gold nebula filaments,
                             dust lanes and a field of hard white stars, brightening toward the centre
  rift_rim.png       1024x128 the rift's rim, a tileable strip: a white-hot core with crackling filaments either side
  sigil_outer.png    1024^2  the outer ritual circle: double rings, a band of procedurally drawn glyphs, ticks, an
                             eight-point star and crescent arcs
  sigil_inner.png    1024^2  the inner circle: a hexagram, nested rings, radial spokes and a second glyph band
  star_flare.png     512^2   the heavenly star: a hard core, soft corona and six long diffraction spikes
  glow.png           256^2   a soft round glow
  trail.png          512x64  the lance's trail across the sky: a white core fading to a long soft tail
  earth_fracture.png 2048x1024 equirectangular fracture network for Earth's surface as it splits: a molten great
                             circle with branching cracks running off it (alpha = heat)
  ember_haze.png     512^2   tileable dust/ember haze for the debris clouds
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage

sys.path.insert(0, str(Path(__file__).resolve().parent))
import space_textures as S  # noqa: E402  (noise helpers only)

OUT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/orbital_railgun/textures/astraea"
OUT.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(777)

WHITE = np.array([255, 252, 246], np.float32)
GOLD = np.array([255, 226, 168], np.float32)
PALE = np.array([255, 240, 214], np.float32)


def save(rgb, name, alpha):
    rgb = np.clip(rgb, 0, 255).astype(np.uint8)
    a = np.clip(alpha, 0, 255).astype(np.uint8)
    Image.fromarray(np.dstack([rgb, a]), "RGBA").save(OUT / f"{name}.png", optimize=True)
    print(f"  {name}.png {rgb.shape[1]}x{rgb.shape[0]}")


def polar(N):
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    x = (xx + 0.5) / N * 2 - 1
    y = (yy + 0.5) / N * 2 - 1
    return np.sqrt(x * x + y * y), np.arctan2(y, x), x, y


# ---------------------------------------------------------------------------------------------- the rift

def rift_void():
    N = 1024
    r, th, x, y = polar(N)
    # a slow vortex: sample a warped noise field in swirled polar coordinates
    swirl = th + 2.6 * np.exp(-r * 1.6)
    u = (swirl / (2 * np.pi)) % 1.0
    base = S.warped(512, 512, 6, 901, 0.35, 6)
    fil = S.ridged(S.warped(512, 512, 10, 902, 0.5, 6), 7)
    iu = (u * 511).astype(int)
    iv = np.clip((np.sqrt(r) * 511), 0, 511).astype(int)
    neb = base[iv, iu] * 0.7 + fil[iv, iu] * 0.6
    lanes = S.warped(512, 512, 14, 903, 0.4, 5)[iv, iu]
    neb *= np.clip(lanes * 1.6 - 0.2, 0.15, 1.2)
    core = np.exp(-r * 3.2)
    bright = neb * (0.25 + 0.75 * core) + core * 0.9
    rgb = np.zeros((N, N, 3), np.float32)
    rgb += bright[..., None] * np.array([255, 232, 190])
    rgb += (core ** 3)[..., None] * WHITE * 0.8
    # hard white stars, some with tiny crosses
    stars = np.zeros((N, N), np.float32)
    n = 1400
    sx, sy = rng.integers(0, N, n), rng.integers(0, N, n)
    stars[sy, sx] = rng.random(n) ** 3 * 1.5 + 0.2
    stars = np.maximum(stars, ndimage.gaussian_filter(stars, 1.2) * 6)
    rgb += stars[..., None] * WHITE
    edge = np.clip(1 - (r - 0.82) / 0.18, 0, 1)
    a = np.clip(0.94 * edge + 0.0, 0, 1) * 255
    save(rgb, "rift_void", a)


def rift_rim():
    W, H = 1024, 128
    v = (np.arange(H, dtype=np.float32) + 0.5) / H * 2 - 1
    vv = np.repeat(v[:, None], W, 1)
    n1 = S.noise(H, W, 24, 911, 5)
    n2 = S.ridged(S.warped(H, W, 40, 912, 0.6, 5), 9)
    core = np.exp(-(vv / 0.09) ** 2)
    halo = np.exp(-(vv / 0.42) ** 2)
    # crackling filaments either side of the core
    fil = n2 * np.exp(-(np.abs(vv) / 0.6) ** 2) * (0.6 + 0.4 * n1)
    k = core * 1.0 + halo * 0.35 + fil * 0.85
    rgb = np.clip(k, 0, 1.5)[..., None] * (WHITE * 0.6 + PALE * 0.4)
    rgb += (core ** 2)[..., None] * 40
    save(rgb, "rift_rim", np.clip(k, 0, 1) * 255)


# ---------------------------------------------------------------------------------------------- sigils

def glyph(d, cx, cy, s, seed):
    """A procedural rune: 2-4 strokes on a 3x3 lattice, plus sometimes a dot or ring."""
    r = np.random.default_rng(seed)
    pts = [(cx + (i - 1) * s * 0.45, cy + (j - 1) * s * 0.45) for i in range(3) for j in range(3)]
    w = max(2, int(s * 0.12))
    for _ in range(r.integers(2, 5)):
        a, b = r.choice(9, 2, replace=False)
        d.line([pts[a], pts[b]], fill=255, width=w)
    if r.random() < 0.35:
        p = pts[r.integers(0, 9)]
        d.ellipse([p[0] - w * 1.2, p[1] - w * 1.2, p[0] + w * 1.2, p[1] + w * 1.2], fill=255)
    if r.random() < 0.2:
        d.ellipse([cx - s * 0.3, cy - s * 0.3, cx + s * 0.3, cy + s * 0.3], outline=255, width=w)


def glyph_band(d, C, r0, r1, n, seed, rot=0.0):
    s = (r1 - r0) * 0.8
    for k in range(n):
        a = rot + k * 2 * math.pi / n
        rm = (r0 + r1) / 2
        gx, gy = C + rm * math.cos(a), C + rm * math.sin(a)
        # draw each glyph upright in its own tile, then rotate it into place
        tile = Image.new("L", (int(s * 1.6), int(s * 1.6)), 0)
        glyph(ImageDraw.Draw(tile), s * 0.8, s * 0.8, s, seed * 1000 + k)
        tile = tile.rotate(-math.degrees(a) - 90, resample=Image.BICUBIC)
        d.bitmap((gx - tile.width / 2, gy - tile.height / 2), tile, fill=255)


def ring(d, C, r, w):
    d.ellipse([C - r, C - r, C + r, C + r], outline=255, width=w)


def finish_sigil(img, name):
    a = np.asarray(img, np.float32) / 255
    glow = ndimage.gaussian_filter(a, 6) * 1.4 + ndimage.gaussian_filter(a, 2) * 0.6
    k = np.clip(a + glow, 0, 1.6)
    rgb = np.clip(k, 0, 1.2)[..., None] * (WHITE * 0.7 + GOLD * 0.3)
    rgb += (a ** 2)[..., None] * 30
    save(rgb, name, np.clip(k, 0, 1) * 255)


def sigil_outer():
    N = 1024
    C = N / 2
    img = Image.new("L", (N, N), 0)
    d = ImageDraw.Draw(img)
    ring(d, C, 500, 6)
    ring(d, C, 486, 2)
    ring(d, C, 420, 5)
    ring(d, C, 408, 2)
    glyph_band(d, C, 424, 482, 36, 41)
    for k in range(72):
        a = k * 2 * math.pi / 72
        r0, r1 = (392, 404) if k % 6 else (370, 404)
        d.line([(C + r0 * math.cos(a), C + r0 * math.sin(a)), (C + r1 * math.cos(a), C + r1 * math.sin(a))], fill=255, width=3)
    # eight-point star inscribed
    pts = []
    for k in range(16):
        a = k * math.pi / 8 - math.pi / 2
        rr = 360 if k % 2 == 0 else 150
        pts.append((C + rr * math.cos(a), C + rr * math.sin(a)))
    d.line(pts + [pts[0]], fill=255, width=4)
    for k in range(8):
        a = k * math.pi / 4
        cx, cy = C + 300 * math.cos(a), C + 300 * math.sin(a)
        d.arc([cx - 46, cy - 46, cx + 46, cy + 46], start=math.degrees(a) + 40, end=math.degrees(a) + 320, fill=255, width=4)
        d.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill=255)
    ring(d, C, 150, 4)
    finish_sigil(img, "sigil_outer")


def sigil_inner():
    N = 1024
    C = N / 2
    img = Image.new("L", (N, N), 0)
    d = ImageDraw.Draw(img)
    ring(d, C, 470, 5)
    ring(d, C, 400, 3)
    glyph_band(d, C, 404, 466, 24, 57, rot=0.13)
    for tri in (0, 1):
        pts = [(C + 390 * math.cos(math.pi / 2 + tri * math.pi + k * 2 * math.pi / 3),
                C + 390 * math.sin(math.pi / 2 + tri * math.pi + k * 2 * math.pi / 3)) for k in range(3)]
        d.line(pts + [pts[0]], fill=255, width=5)
    for rr in (230, 205, 120, 60):
        ring(d, C, rr, 3 if rr > 100 else 4)
    for k in range(12):
        a = k * math.pi / 6
        d.line([(C + 60 * math.cos(a), C + 60 * math.sin(a)), (C + 205 * math.cos(a), C + 205 * math.sin(a))], fill=255, width=2)
    glyph_band(d, C, 125, 200, 12, 71)
    d.ellipse([C - 22, C - 22, C + 22, C + 22], fill=255)
    finish_sigil(img, "sigil_inner")


# ---------------------------------------------------------------------------------------------- light

def star_flare():
    N = 512
    r, th, x, y = polar(N)
    core = np.exp(-r * 26) * 1.6 + np.exp(-r * 9) * 0.7 + np.exp(-r * 3.5) * 0.22
    spikes = np.zeros_like(r)
    for k in range(6):
        a = k * math.pi / 3 + 0.26
        d = np.abs(np.sin(th - a)) * r
        along = np.cos(th - a) * r
        spikes += np.exp(-(d / 0.006) ** 2) * np.exp(-np.abs(along) * 2.6) * (along > 0)
    spikes += 0.0
    k = core + spikes * 0.9
    k *= np.clip(1 - r, 0, 1) ** 0.5
    rgb = np.clip(k, 0, 1.4)[..., None] * (WHITE * 0.75 + GOLD * 0.25)
    save(rgb, "star_flare", np.clip(k, 0, 1) * 255)


def glow():
    N = 256
    r, *_ = polar(N)
    k = np.exp(-(r / 0.42) ** 2) * np.clip(1 - r, 0, 1)
    save(np.ones((N, N, 3)) * WHITE, "glow", k * 255)


def trail():
    W, H = 512, 64
    u = (np.arange(W, dtype=np.float32) + 0.5) / W
    v = (np.arange(H, dtype=np.float32) + 0.5) / H * 2 - 1
    uu, vv = np.meshgrid(u, v)
    head = np.exp(-uu * 2.4)
    core = np.exp(-(vv / (0.12 + 0.25 * uu)) ** 2)
    halo = np.exp(-(vv / (0.5 + 0.4 * uu)) ** 2) * 0.35
    k = (core + halo) * head
    rgb = np.clip(k, 0, 1.2)[..., None] * (WHITE * 0.8 + GOLD * 0.2)
    save(rgb, "trail", np.clip(k, 0, 1) * 255)


# ---------------------------------------------------------------------------------------------- the split

def earth_fracture():
    W, H = 2048, 1024
    lat, lon = S.latlon(H, W)
    # the split runs round the great circle tilted 70 degrees off the poles (lat measured off that circle)
    tilt = math.radians(70)
    x = np.cos(lat) * np.cos(lon)
    y = np.sin(lat)
    z = np.cos(lat) * np.sin(lon)
    # plane normal
    nx, ny, nz = math.sin(tilt), math.cos(tilt), 0.0
    off = np.abs(x * nx + y * ny + z * nz)   # 0 on the circle
    jag = (S.noise(H, W, 30, 931, 5) - 0.5) * 0.05
    main = np.exp(-((off + jag) / 0.012) ** 2)
    glow_ = np.exp(-((off + jag) / 0.07) ** 2) * 0.45
    # branching cracks: ridged warped noise, strongest near the main split
    br = S.ridged(S.warped(H, W, 18, 932, 0.45, 6), 14)
    br2 = S.ridged(S.warped(H, W, 46, 933, 0.5, 5), 18) * 0.6
    near = np.exp(-(off / 0.35) ** 2)
    cracks = np.clip(br * near * 1.1 + br2 * near * 0.6, 0, 1)
    heat = np.clip(main * 1.3 + glow_ + cracks * 0.85, 0, 1.4)
    # blackbody: white core, gold, deep orange-red at the faint ends
    rgb = S.ramp(np.clip(heat / 1.4, 0, 1), [(0.0, (90, 16, 4)), (0.35, (220, 70, 12)), (0.6, (255, 170, 60)),
                                              (0.85, (255, 236, 190)), (1.0, (255, 255, 250))])
    save(rgb, "earth_fracture", np.clip(heat, 0, 1) * 255)


def ember_haze():
    N = 512
    n = S.warped(N, N, 6, 941, 0.4, 6)
    d = S.ridged(S.warped(N, N, 14, 942, 0.5, 5), 6)
    k = np.clip(n * 0.8 + d * 0.4 - 0.25, 0, 1)
    rgb = S.ramp(k, [(0.0, (40, 30, 26)), (0.5, (120, 84, 60)), (1.0, (255, 170, 90))])
    save(rgb, "ember_haze", k * 200)


if __name__ == "__main__":
    for f in (rift_void, rift_rim, sigil_outer, sigil_inner, star_flare, glow, trail, earth_fracture, ember_haze):
        f()
