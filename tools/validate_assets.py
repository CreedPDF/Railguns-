#!/usr/bin/env python3
"""
Fails the build if the entity textures would crash or look wrong in game.
GeckoLib's AutoGlowingGeoLayer throws "Invalid glow layer texture provided, must have at least
one pixel!" when the _glowmask has no non-transparent pixel, so that is checked first.
Uses only the standard library (PNG decode via zlib).
"""
import struct
import sys
import zlib
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/orbital_railgun"


def read_png(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"{path} is not a PNG"
    pos, idat, w = 8, b"", None
    while pos < len(data):
        length, ctype = struct.unpack(">I4s", data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            w, h, depth, color_type = struct.unpack(">IIBB", chunk[:10])
        elif ctype == b"IDAT":
            idat += chunk
        pos += 12 + length
    channels = {6: 4, 2: 3, 0: 1, 4: 2}[color_type]
    bpp = channels * depth // 8
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line)); prev = line
    return w, h, channels, depth, rows


def pixels(path):
    w, h, ch, depth, rows = read_png(path)
    step = ch * depth // 8
    for r in rows:
        for x in range(w):
            px = r[x * step:(x + 1) * step:depth // 8]
            yield tuple(px) + ((255,) if ch in (1, 3) else ())


try:
    import numpy as _np
    from PIL import Image as _Image

    def pixels(path):  # noqa: F811  fast path for large atlases
        a = _np.asarray(_Image.open(path).convert("RGBA")).reshape(-1, 4)
        return (tuple(int(v) for v in p) for p in a[a[:, 3] > 0][::7])
except ImportError:
    pass

errors = []
glow = ASSETS / "textures/entity/railgun_glowmask.png"
lit = sum(1 for p in pixels(glow) if p[-1] > 0)
if lit == 0:
    errors.append(f"{glow.name}: no visible pixels (GeckoLib would crash)")
red = sum(1 for p in pixels(ASSETS / "textures/entity/railgun.png") if len(p) >= 3 and p[0] > p[2] + 40)
if red == 0:
    errors.append("railgun.png: no glow colours baked")
print(f"glowmask lit pixels: {lit}, red texels in atlas: {red}")
for extra in ("textures/entity/ares0_glowmask.png", "textures/armor/eva_suit_glowmask.png"):
    path = ASSETS / extra
    if not path.exists():
        errors.append(f"{extra}: missing")
        continue
    n = sum(1 for p in pixels(path) if p[-1] > 0)
    print(f"{extra}: {n} lit pixels")
    if n == 0:
        errors.append(f"{extra}: no visible pixels (GeckoLib would crash)")
if errors:
    for e in errors:
        print(f"::error title=Asset validation::{e}")
    sys.exit(1)
