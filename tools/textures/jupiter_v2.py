#!/usr/bin/env python3
"""
Jupiter, painted the way the real one is built: a latitude profile of zones (pale ammonia cloud) and belts (warm,
deeper cloud), sheared into streaks along alternating jets, broken up by turbulence that is strongest in the belts,
then swirled by hundreds of vortices - chains of small eddies along the jets, white ovals, brown barges, the dark
blue-grey festoons trailing off the North Equatorial Belt and the Great Red Spot with its turbulent wake.

  textures/space/jupiter.png      4096 x 2048 equirectangular albedo (u = longitude, v = 0 at the north pole)

All noise is made in the frequency domain so it tiles round the planet seamlessly.
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/assets/orbital_railgun/textures/space/jupiter.png"
W, H = 4096, 2048
rng = np.random.default_rng(1610)


def fnoise(sx, sy, seed, h=H, w=W):
    """Tileable smooth noise with correlation lengths sx (along longitude) and sy (along latitude), in pixels."""
    r = np.random.default_rng(seed)
    white = r.standard_normal((h, w))
    ky = np.fft.fftfreq(h)[:, None]
    kx = np.fft.fftfreq(w)[None, :]
    f = np.exp(-((kx * sx) ** 2 + (ky * sy) ** 2) * 2 * math.pi ** 2)
    n = np.real(np.fft.ifft2(np.fft.fft2(white) * f))
    return n / (n.std() + 1e-9)


def fbm(sx, sy, seed, octaves=4, gain=0.55):
    out = np.zeros((H, W))
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        out += fnoise(sx / 2 ** o, sy / 2 ** o, seed + o) * amp
        tot += amp
        amp *= gain
    return out / tot


# ---------------------------------------------------------------- latitude profile (degrees, + north)
# (lat, rgb): sampled with linear interpolation
PROFILE = [
    (90, (112, 112, 120)), (72, (122, 118, 120)), (62, (140, 128, 118)), (55, (168, 150, 128)), (51, (150, 124, 100)),
    (47, (190, 170, 142)), (42, (162, 128, 98)), (38, (206, 190, 164)), (33, (214, 198, 172)), (30, (168, 122, 88)),
    (26, (160, 112, 80)), (23, (224, 210, 186)), (19, (232, 220, 198)), (17, (150, 96, 64)), (12, (136, 84, 56)),
    (9, (164, 110, 76)), (7, (214, 186, 150)), (3, (236, 222, 196)), (0, (226, 204, 168)), (-3, (238, 226, 202)),
    (-7, (216, 190, 154)), (-9, (150, 98, 66)), (-13, (178, 128, 92)), (-15, (192, 146, 108)), (-17, (146, 94, 62)),
    (-20, (170, 116, 82)), (-22, (230, 214, 188)), (-26, (236, 224, 202)), (-28, (166, 120, 88)), (-32, (176, 132, 100)),
    (-35, (216, 200, 176)), (-39, (204, 186, 160)), (-41, (158, 126, 100)), (-45, (198, 180, 154)), (-50, (180, 160, 136)),
    (-56, (156, 140, 124)), (-64, (132, 124, 122)), (-74, (118, 114, 120)), (-90, (108, 108, 118))]
# turbulence amplitude (0 calm zone .. 1 chaotic belt) and jet speed (east +) by latitude
TURB = [(90, 0.9), (60, 0.8), (45, 0.45), (30, 0.7), (24, 0.25), (17, 0.9), (8, 0.8), (4, 0.15), (-4, 0.15), (-8, 0.9),
        (-19, 0.9), (-24, 0.2), (-30, 0.7), (-40, 0.45), (-60, 0.8), (-90, 0.9)]
JETS = [(90, 0), (55, 0.4), (35, -0.5), (24, 1.6), (17, -0.6), (7, 1.0), (0, 0.6), (-7, 1.0), (-19, -0.7), (-26, 1.2), (-33, -0.4),
        (-45, 0.5), (-60, 0), (-90, 0)]


def interp(table, lat):
    xs = np.array([t[0] for t in table][::-1], float)
    ys = np.array([t[1] for t in table][::-1], float)
    if ys.ndim == 1:
        return np.interp(lat, xs, ys)
    return np.stack([np.interp(lat, xs, ys[:, c]) for c in range(ys.shape[1])], -1)


def lat_of(y):
    return 90 - (y + 0.5) / H * 180


def y_of(lat):
    return (90 - lat) / 180 * H - 0.5


def main():
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    lat0 = lat_of(yy)
    print("noise")
    # ---- turbulence: wobble latitudes (the bands' edges ripple and tear), strongest in the belts
    turb = interp(TURB, lat0)
    n1 = fbm(220, 26, 11, 5)
    n2 = fbm(90, 14, 21, 4)
    Y = yy + (n1 * 9 + n2 * 4) * (0.25 + turb)
    # shear: features lean and smear along the jets
    jet = interp(JETS, lat0)
    X = xx + fbm(400, 60, 31, 3) * 40 * jet

    # ---- vortices: (lat, lon fraction, radius px, strength rad, kind)
    print("vortices")
    vort = []
    # chains of small eddies along every jet boundary
    for lat_c in (36, 31, 24, 18, 8, -8, -19, -27, -33, -40, -45, 50, -50, 58, -58, 66, -66, 72, -72):
        n = int(rng.integers(18, 40))
        for i in range(n):
            vort.append((lat_c + rng.normal(0, 1.2), rng.random(), rng.uniform(6, 22), rng.choice([-1, 1]) * rng.uniform(1.5, 4.5), "eddy"))
    # polar chaos: many small storms
    for i in range(260):
        lat_c = rng.choice([-1, 1]) * rng.uniform(60, 86)
        vort.append((lat_c, rng.random(), rng.uniform(5, 16), rng.choice([-1, 1]) * rng.uniform(2, 5), "eddy"))
    # white ovals in the south temperate belt
    ovals = [(-33.5, 0.08 + i * 0.11 + rng.normal(0, 0.01), rng.uniform(20, 30)) for i in range(7)]
    for la, lo, r in ovals:
        vort.append((la, lo % 1, r, -5.5, "oval"))
    # brown barges in the NEB
    barges = [(14.5, rng.random(), rng.uniform(14, 22)) for _ in range(5)]
    for la, lo, r in barges:
        vort.append((la, lo, r, 4.5, "barge"))
    # the Great Red Spot
    GRS = (-22.0, 0.62, 88.0)
    vort.append((GRS[0], GRS[1], GRS[2], -7.0, "grs"))

    masks = {"oval": np.zeros((H, W)), "barge": np.zeros((H, W)), "grs": np.zeros((H, W))}
    for la, lo, r, s, kind in vort:
        cy, cx = y_of(la), lo * W
        aspect = 1.9 if kind in ("grs", "oval", "barge") else 1.4
        win = int(r * aspect * 3.2) + 4
        y0, y1 = int(max(0, cy - win)), int(min(H, cy + win))
        if y1 <= y0:
            continue
        xs = (np.arange(int(cx - win * aspect), int(cx + win * aspect)) % W)
        sub_X = X[y0:y1][:, xs]
        sub_Y = Y[y0:y1][:, xs]
        dx = (sub_X - cx + W / 2) % W - W / 2
        dx = dx / aspect
        dy = sub_Y - cy
        d = np.sqrt(dx * dx + dy * dy)
        th = s * np.exp(-(d / r) ** 2) * np.minimum(1, d / (r * 0.12) + 0.2)
        c, sn = np.cos(th), np.sin(th)
        ndx = dx * c - dy * sn
        ndy = dx * sn + dy * c
        X[y0:y1, xs] = cx + ndx * aspect
        Y[y0:y1, xs] = cy + ndy
        if kind in masks:
            m = np.clip(1.15 - d / r, 0, 1) ** 0.8
            mm = masks[kind]
            mm[y0:y1, xs] = np.maximum(mm[y0:y1, xs], m)

    print("sample")
    latw = lat_of(Y)
    rgb = interp(PROFILE, latw)
    # streaks along the flow, sampled through the warped coordinates
    streak = fbm(160, 3.5, 41, 4)
    fine = fbm(30, 2.5, 51, 3)
    coords = np.array([np.clip(Y, 0, H - 1), X % W])
    s1 = ndimage.map_coordinates(streak, coords, order=1, mode="wrap")
    s2 = ndimage.map_coordinates(fine, coords, order=1, mode="wrap")
    tw = interp(TURB, latw)
    rgb *= (1 + s1[..., None] * (0.05 + 0.07 * tw[..., None]) + s2[..., None] * 0.035)
    # ammonia-white upwelling plumes in the zones
    plume = np.clip(ndimage.map_coordinates(fbm(60, 10, 61, 3), coords, order=1, mode="wrap") - 0.9, 0, None)
    zone = np.clip(1 - tw / 0.4, 0, 1)
    rgb += plume[..., None] * zone[..., None] * np.array([40, 38, 34])

    # festoons: dark blue-grey plumes dragged south-west off the NEB into the equatorial zone
    print("festoons")
    fest = np.zeros((H, W))
    for i in range(13):
        lo = (i / 13 + rng.normal(0, 0.012)) * W
        cy0 = y_of(7.0)
        length = rng.uniform(150, 260)
        drop = rng.uniform(30, 55)
        t = np.linspace(0, 1, 60)
        for k in t:
            py = cy0 + drop * k ** 1.5
            px = lo - k * length
            rad = 12 * (1 - k * 0.6)
            ys0, ys1 = int(py - rad * 3), int(py + rad * 3)
            xs = np.arange(int(px - rad * 3), int(px + rad * 3)) % W
            gy, gx = np.mgrid[ys0:ys1, 0:len(xs)]
            ddx = (xs[None, :] - px + W / 2) % W - W / 2
            dd = np.sqrt(ddx ** 2 + (gy - py) ** 2)
            fest[ys0:ys1, xs] = np.maximum(fest[ys0:ys1, xs], np.exp(-(dd / rad) ** 2) * (1 - k * 0.5))
    fest = ndimage.map_coordinates(fest, np.array([np.clip(Y, 0, H - 1), X % W]), order=1, mode="wrap")
    rgb = rgb * (1 - fest[..., None] * 0.55) + np.array([84, 96, 118]) * fest[..., None] * 0.55

    # the storms' own colour
    o = masks["oval"][..., None]
    rgb = rgb * (1 - o * 0.75) + np.array([246, 240, 230]) * o * 0.75
    b = masks["barge"][..., None]
    rgb = rgb * (1 - b * 0.7) + np.array([110, 66, 44]) * b * 0.7
    g = masks["grs"]
    # a brick-red core with a paler collar and a darker rim (the "hollow" round the spot)
    cy, cx = y_of(GRS[0]), GRS[1] * W
    dx = ((xx - cx + W / 2) % W - W / 2) / 1.9
    dd = np.sqrt(dx ** 2 + (yy - cy) ** 2) / GRS[2]
    core = np.clip(1.0 - dd, 0, 1) ** 0.6
    swirl = ndimage.map_coordinates(fbm(40, 8, 71, 3), np.array([np.clip(Y, 0, H - 1), X % W]), order=1, mode="wrap")
    red = np.array([182, 82, 56]) * (1 + swirl[..., None] * 0.08)
    rgb = rgb * (1 - core[..., None] * 0.85) + red * core[..., None] * 0.85
    hollow = np.exp(-((dd - 1.12) / 0.12) ** 2)
    rgb = rgb * (1 + hollow[..., None] * 0.12)

    # polar haze: hazy, darker, a little blue
    absl = np.abs(lat0)
    haze = np.clip((absl - 58) / 26, 0, 1)
    rgb = rgb * (1 - haze[..., None] * 0.35) + np.array([96, 98, 112]) * haze[..., None] * 0.35

    rgb = np.clip(rgb, 0, 255).astype(np.uint8)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(rgb).save(OUT)
    print("wrote", OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
