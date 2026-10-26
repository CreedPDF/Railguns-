#!/usr/bin/env python3
"""
Textures for the Gungnir ring round Jupiter and the Gungnir cutscene (numpy + Pillow, all procedural; the words are
set in Inter from the build machine's fonts, the GUNGNIR title is drawn from its own stroke geometry).

  textures/grs/ring_hull.png        tileable hull plating (gunmetal and black panels, seams, greebles)
  textures/grs/ring_hull_glow.png   its additive light: orange seam lines, window rows, beacons
  textures/grs/ring_gate.png        coil-gate metal (u round the gate x8, v: rims 0-0.5, faces 0.5-1)
  textures/grs/ring_gate_glow.png   the gates' orange panels and rim lights
  textures/grs/fx/white.png         plain white (tinted additive strips)
  textures/grs/fx/rock.png          2x2 atlas of tumbling rocks (translucent)
  textures/grs/fx/circuit.png       the target designator's orange circuit traces (additive, tileable)
  textures/grs/fx/warp.png          a warp streak (additive; u along, bright head at u=1)
  textures/grs/cine/title.png       the GUNGNIR title: hollow bevelled copper letters
  textures/grs/cine/release.png     "RELEASE" with its sub line
  textures/grs/cine/impact.png      "IMPACT" with its sub line
  textures/grs/cine/jupiter.png     "JUPITER" with its sub line
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
A = ROOT / "src/main/resources/assets/orbital_railgun/textures/grs"
FONT_DIRS = [Path("/usr/share/fonts/opentype/inter"), Path("/usr/share/fonts/truetype/inter")]


def font(name, size):
    for d in FONT_DIRS:
        p = d / name
        if p.exists():
            return ImageFont.truetype(str(p), size)
    for p in Path("/usr/share/fonts").rglob("*.ttf"):
        if "DejaVuSans" in p.name:
            return ImageFont.truetype(str(p), size)
    return ImageFont.load_default()


def save(im, rel):
    p = A / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(im, np.ndarray):
        im = Image.fromarray((np.clip(im, 0, 1) * 255).astype(np.uint8))
    im.save(p)
    print("wrote", p.relative_to(ROOT))


def fnoise(n, scale, seed):
    r = np.random.default_rng(seed)
    w = r.standard_normal((n, n))
    k = np.fft.fftfreq(n)
    f = np.exp(-(k[:, None] ** 2 + k[None, :] ** 2) * (scale ** 2) * 2 * math.pi ** 2)
    out = np.real(np.fft.ifft2(np.fft.fft2(w) * f))
    return out / (out.std() + 1e-9)


def hull():
    n = 1024
    r = np.random.default_rng(4242)
    base = np.full((n, n), 0.20)
    lines = np.zeros((n, n))
    # big plates: a 3 x 6 grid with offsets, each its own shade; sub-plates inside
    for gy in range(6):
        off = (gy % 2) * n // 6
        for gx in range(3):
            y0 = gy * n // 6
            x0 = (gx * n // 3 + off) % n
            h, w = n // 6, n // 3
            xs = (np.arange(x0, x0 + w)) % n
            base[y0:y0 + h][:, xs] += r.uniform(-0.05, 0.05)
            lines[y0:y0 + 3][:, xs] = 1
            lines[y0:y0 + h][:, xs[:3]] = 1
            for _ in range(r.integers(2, 5)):
                sh, sw = r.integers(20, h - 10), r.integers(30, w - 10)
                yy, xx = y0 + r.integers(4, h - sh), r.integers(4, w - sw)
                xs2 = (np.arange(x0 + xx, x0 + xx + sw)) % n
                base[yy:yy + sh][:, xs2] += r.uniform(-0.04, 0.04)
                lines[yy:yy + 2][:, xs2] = np.maximum(lines[yy:yy + 2][:, xs2], 0.5)
    grime = fnoise(n, 30, 1) * 0.025 + fnoise(n, 4, 2) * 0.015
    v = base + grime
    v = v * (1 - lines * 0.45)
    # greebles
    for _ in range(900):
        h, w = r.integers(4, 16), r.integers(4, 26)
        y, x = r.integers(0, n - h), r.integers(0, n - w)
        g = r.uniform(0.08, 0.3)
        v[y:y + h, x:x + w] = g
        v[y:y + 1, x:x + w] = g + 0.1
        v[y + h - 1:y + h, x:x + w] = g - 0.06
    col = np.stack([v * 1.0, v * 0.98, v * 0.97, np.ones_like(v)], -1)
    save(col, "ring_hull.png")
    # glow: orange seams along u at a few panel rows, window rows, beacons
    g = np.zeros((n, n))
    for y0 in (0, n // 2):
        g[y0:y0 + 4] = 1.0
    for x0 in range(0, n, n // 3):
        g[:, x0:x0 + 3] = np.maximum(g[:, x0:x0 + 3], 0.7)
    for row in (n // 6 + 30, n // 6 * 4 + 30):
        for x in range(0, n, 10):
            if r.random() < 0.6:
                g[row:row + 5, x:x + 5] = r.uniform(0.35, 0.9)
    for _ in range(40):
        y, x = r.integers(0, n - 8), r.integers(0, n - 8)
        g[y:y + 8, x:x + 8] = 1
    blur = np.asarray(Image.fromarray((g * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3))) / 255
    a = np.clip(g + blur * 1.2, 0, 1)
    im = np.zeros((n, n, 4))
    im[..., 0], im[..., 1], im[..., 2] = 1.0, 0.55, 0.18
    im[..., 3] = a
    save(im, "ring_hull_glow.png")


def gate():
    W, H = 512, 256
    r = np.random.default_rng(77)
    v = np.full((H, W), 0.16) + fnoise(512, 6, 3)[:H, :W] * 0.02
    for x in range(0, W, W // 8):
        v[:, x:x + 3] *= 0.5
    v[H // 2:H // 2 + 3] *= 0.5
    col = np.stack([v, v * 0.98, v * 0.96, np.ones_like(v)], -1)
    save(col, "ring_gate.png")
    g = np.zeros((H, W))
    # face (v 0.5..1): one orange panel per eighth, with a thin frame
    for k in range(8):
        x0 = k * W // 8 + 10
        g[H // 2 + 30:H - 30, x0:x0 + W // 8 - 26] = 0.85
        g[H // 2 + 40:H - 40, x0 + 6:x0 + W // 8 - 32] = 1.0
    # rims (v 0..0.5): a row of small lights and a line
    for x in range(4, W, 16):
        g[50:62, x:x + 8] = 1.0
    g[H // 4:H // 4 + 4] = 0.8
    blur = np.asarray(Image.fromarray((g * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2))) / 255
    im = np.zeros((H, W, 4))
    im[..., :3] = 1
    im[..., 3] = np.clip(g + blur, 0, 1)
    save(im, "ring_gate_glow.png")


def white():
    save(np.ones((16, 16, 4)), "fx/white.png")


def rock():
    n = 256
    r = np.random.default_rng(9)
    im = np.zeros((n * 2, n * 2, 4))
    for k in range(4):
        y, x = (np.mgrid[0:n, 0:n] + 0.5) / n * 2 - 1
        ang = np.arctan2(y, x)
        rad = np.sqrt(x * x + y * y)
        edge = 0.62 + sum(r.uniform(-0.045, 0.045) * np.cos(ang * f + r.uniform(0, 6.3)) for f in range(2, 7))
        m = rad < edge
        nz = fnoise(n, 3, 10 + k) * 0.12 + fnoise(n, 12, 20 + k) * 0.08
        z = np.sqrt(np.clip(1 - (rad / edge) ** 2, 0, 1))
        light = np.clip(-x * 0.5 - y * 0.6 + z * 0.6, 0, 1)
        v = np.clip(0.18 + light * 0.45 + nz, 0, 1)
        tile = np.zeros((n, n, 4))
        tile[..., 0] = v * 1.0
        tile[..., 1] = v * 0.92
        tile[..., 2] = v * 0.85
        tile[..., 3] = m * np.clip((edge - rad) * 30, 0, 1)
        oy, ox = (k // 2) * n, (k % 2) * n
        im[oy:oy + n, ox:ox + n] = tile
    save(im, "fx/rock.png")


def circuit():
    n = 512
    r = np.random.default_rng(31)
    im = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(im)
    for _ in range(20):
        x, y = r.integers(0, n // 16) * 16, r.integers(0, n // 16) * 16
        pts = [(x, y)]
        for s in range(r.integers(3, 8)):
            dirn = r.integers(0, 4)
            L = r.integers(2, 9) * 16
            dx, dy = [(1, 0), (0, 1), (-1, 0), (0, -1)][dirn]
            if r.random() < 0.3:
                dx, dy = dx + dy, dy - dx
            x, y = x + dx * L, y + dy * L
            pts.append((x, y))
        w = int(r.choice([3, 4, 6]))
        for k in range(len(pts) - 1):
            a, b = pts[k], pts[k + 1]
            for ox in (-n, 0, n):
                for oy in (-n, 0, n):
                    d.line((a[0] + ox, a[1] + oy, b[0] + ox, b[1] + oy), fill=255, width=w)
        ex, ey = pts[-1]
        for ox in (-n, 0, n):
            for oy in (-n, 0, n):
                d.ellipse((ex - 7 + ox, ey - 7 + oy, ex + 7 + ox, ey + 7 + oy), outline=255, width=3)
    a = np.asarray(im) / 255
    glow = np.asarray(im.filter(ImageFilter.GaussianBlur(5))) / 255
    out = np.zeros((n, n, 4))
    out[..., :3] = 1
    out[..., 3] = np.clip(a + glow * 1.4, 0, 1)
    save(out, "fx/circuit.png")


def warp():
    w, h = 256, 16
    u = (np.arange(w) + 0.5) / w
    v = (np.arange(h) + 0.5) / h * 2 - 1
    a = (u[None, :] ** 2.2) * np.exp(-(v[:, None] / 0.45) ** 2)
    im = np.zeros((h, w, 4))
    im[..., :3] = 1
    im[..., 3] = np.clip(a * 1.2, 0, 1)
    save(im, "fx/warp.png")


# ---------------------------------------------------------------- the title: GUNGNIR in hollow bevelled letters
def glyph(ch):
    """Stroke paths (lists of points) for a chamfered stencil-tech face on a 0..1 x 0..1.4 box."""
    c = 0.18
    if ch == "G":
        return [[(1, 0.32), (1, c), (1 - c, 0), (c, 0), (0, c), (0, 1.4 - c), (c, 1.4), (1 - c, 1.4), (1, 1.4 - c), (1, 0.78), (0.55, 0.78)]]
    if ch == "U":
        return [[(0, 0), (0, 1.4 - c), (c, 1.4), (1 - c, 1.4), (1, 1.4 - c), (1, 0)]]
    if ch == "N":
        return [[(0, 1.4), (0, 0), (0.12, 0), (0.88, 1.4), (1, 1.4), (1, 0)]]
    if ch == "I":
        return [[(0.5, 0), (0.5, 1.4)], [(0.2, 0), (0.8, 0)], [(0.2, 1.4), (0.8, 1.4)]]
    if ch == "R":
        return [[(0, 1.4), (0, 0), (1 - c, 0), (1, c), (1, 0.62 - c), (1 - c, 0.62), (0, 0.62)], [(0.45, 0.62), (1, 1.4)]]
    return []


def title():
    word = "GUNGNIR"
    H = 512
    lw, gap, pad = 230, 70, 60
    W = pad * 2 + len(word) * lw + (len(word) - 1) * gap
    W = 1 << (W - 1).bit_length()
    scale = 300 / 1.4
    top = (H - 300) / 2

    def path(i, ch):
        x0 = pad + i * (lw + gap) + (W - (pad * 2 + len(word) * lw + (len(word) - 1) * gap)) / 2
        return [[(x0 + p[0] * lw, top + p[1] * scale) for p in s] for s in glyph(ch)]

    def stroke(width, blur=0):
        im = Image.new("L", (W, H), 0)
        d = ImageDraw.Draw(im)
        for i, ch in enumerate(word):
            for s in path(i, ch):
                d.line(s, fill=255, width=width, joint="curve")
                for p in (s[0], s[-1]):
                    d.ellipse((p[0] - width / 2, p[1] - width / 2, p[0] + width / 2, p[1] + width / 2), fill=255)
        if blur:
            im = im.filter(ImageFilter.GaussianBlur(blur))
        return np.asarray(im) / 255

    outer = stroke(64)
    inner = stroke(36)
    core = stroke(12)
    halo = stroke(64, 18)
    yy = np.linspace(0, 1, H)[:, None] * np.ones((1, W))
    # copper rim, darker hollow, bright gold core line, a soft glow
    rim = np.clip(outer - inner, 0, 1)
    grad = 0.75 + 0.35 * (1 - yy)
    rgb = np.zeros((H, W, 3))
    rgb += rim[..., None] * np.array([0.86, 0.52, 0.22]) * grad[..., None]
    rgb += (inner - core).clip(0, 1)[..., None] * np.array([0.22, 0.11, 0.05])
    rgb += core[..., None] * np.array([1.0, 0.78, 0.45])
    alpha = np.clip(outer * 0.97 + halo * 0.35, 0, 1)
    glow = halo[..., None] * np.array([1, 0.5, 0.15]) * 0.4 * (1 - outer[..., None])
    rgb = rgb + glow
    im = np.concatenate([np.clip(rgb, 0, 1), alpha[..., None]], -1)
    save(im, "cine/title.png")


def word(text, sub, name, tint=(1.0, 0.86, 0.78)):
    W, H = 1024, 256
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    f = font("Inter-Light.otf", 96)
    fs = font("Inter-Regular.otf", 22)
    # wide tracking
    track = 34
    widths = [d.textlength(ch, font=f) for ch in text]
    total = sum(widths) + track * (len(text) - 1)
    x = (W - total) / 2
    col = tuple(int(c * 255) for c in tint) + (255,)
    for ch, w in zip(text, widths):
        d.text((x, 54), ch, font=f, fill=col)
        x += w + track
    d.line(((W - total) / 2 + 8, 176, (W + total) / 2 - 8, 176), fill=(255, 160, 110, 150), width=2)
    st = d.textlength(sub, font=fs)
    sx = (W - st - 6 * (len(sub) - 1)) / 2
    for ch in sub:
        d.text((sx, 188), ch, font=fs, fill=(255, 200, 170, 190))
        sx += d.textlength(ch, font=fs) + 6
    save(im, f"cine/{name}.png")


if __name__ == "__main__":
    hull()
    gate()
    white()
    rock()
    circuit()
    warp()
    title()
    word("RELEASE", "GUNGNIR  LEAVES  THE  RING", "release")
    word("IMPACT", "GROUND  ZERO  ·  SPEAR  PLANTED", "impact")
    word("JUPITER", "GUNGNIR  RAILWAY  STATION", "jupiter")
