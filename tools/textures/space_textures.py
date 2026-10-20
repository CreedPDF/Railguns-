"""
Realistic space textures for ARES-9 / ARES-0 v4 (procedural; run locally, PNGs are committed).

  textures/space/space_sky.png      4096x2048 equirect: Milky Way with dust lanes and a bright core, ~150k stars
  textures/space/sun.png            granulated photosphere      sun_glare.png  additive glare
  textures/space/earth.png          4096x2048 day side          earth_night.png  city lights   earth_clouds.png
  textures/space/moon.png           cratered highlands and maria, ray craters
  textures/space/mars.png           rust plains, dark albedo regions, a great canyon, volcanoes, polar caps
  textures/space/venus.png          sulphuric cloud deck         venus_clouds.png
  textures/space/europa.png         ice shell with lineae and chaos terrain
  textures/space/jupiter.png        belts and zones, festoons, the Great Red Spot, white ovals
  textures/space/saturn.png         saturn_rings.png (radial profile with the Cassini division)
  textures/space/planet_cracks.png  glowing fracture network (blast)    plasma.png  dust.png
  textures/space/fire_cloud.png smoke.png streak.png flare_streak.png  (v4.0.4 blast)
  textures/space/white.png, moon_gone.png
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage

A = Path(__file__).resolve().parents[2] / "src/main/resources/assets/orbital_railgun/textures/space"
A.mkdir(parents=True, exist_ok=True)


# ---------------------------------------------------------------------------------------------- noise

def noise(H, W, cells, seed, octaves=6, persistence=0.5):
    """Tileable (horizontally and vertically) fractal value noise, 0..1, cubic interpolated."""
    r = np.random.default_rng(seed)
    out = np.zeros((H, W), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        gx = int(cells * 2 ** o)
        gy = max(2, int(round(gx * H / W)))
        if gx > W // 2:
            break
        grid = r.random((gy, gx)).astype(np.float32)
        up = ndimage.zoom(grid, (H / gy, W / gx), order=3, mode="grid-wrap", grid_mode=True)[:H, :W]
        out += amp * up
        tot += amp
        amp *= persistence
    out /= tot
    lo, hi = np.percentile(out, 0.5), np.percentile(out, 99.5)
    return np.clip((out - lo) / (hi - lo + 1e-6), 0, 1)


def warp(img, dy, dx, strength):
    H, W = img.shape
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    return ndimage.map_coordinates(img, [(yy + dy * strength * H) % H, (xx + dx * strength * W) % W], order=1, mode="grid-wrap")


def warped(H, W, cells, seed, strength, octaves=6):
    base = noise(H, W, cells, seed, octaves)
    dy = noise(H, W, max(2, cells // 2), seed + 101, 4) - 0.5
    dx = noise(H, W, max(2, cells // 2), seed + 202, 4) - 0.5
    return warp(base, dy, dx, strength)


def ridged(n, sharp=8.0):
    return np.clip(1 - np.abs(n - 0.5) * sharp, 0, 1)


def ramp(v, stops):
    v = np.clip(v, 0, 1)
    pos = [p for p, _ in stops]
    cols = np.array([c for _, c in stops], dtype=np.float32)
    return np.stack([np.interp(v, pos, cols[:, k]) for k in range(3)], -1)


def latlon(H, W):
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    lat = (0.5 - (yy + 0.5) / H) * np.pi
    lon = (xx + 0.5) / W * 2 * np.pi
    return lat, lon


def save(rgb, name, alpha=None):
    rgb = np.clip(rgb, 0, 255).astype(np.uint8)
    if alpha is None:
        alpha = np.full(rgb.shape[:2], 255, np.uint8)
    else:
        alpha = np.clip(alpha, 0, 255).astype(np.uint8)
    Image.fromarray(np.dstack([rgb, alpha]), "RGBA").save(A / f"{name}.png", optimize=True)
    print(f"  {name}.png {rgb.shape[1]}x{rgb.shape[0]}")


def relief(h, strength):
    gy, gx = np.gradient(h)
    return np.clip(1 + (-gx * 0.7 - gy * 0.7) * strength, 0.45, 1.6)


def blur(a, s):
    return ndimage.gaussian_filter(a, s, mode="wrap")


# ---------------------------------------------------------------------------------------------- craters

def stamp_craters(height, n, seed, rmin, rmax, rays=None, ray_count=0):
    """Impact craters stamped on an equirect height map (no pole stretching, local windows only)."""
    H, W = height.shape
    r = np.random.default_rng(seed)
    for k in range(n):
        clat = math.asin(r.uniform(-0.97, 0.97))
        clon = r.uniform(0, 2 * math.pi)
        rad = rmin + (rmax - rmin) * r.random() ** 3.2       # angular radius
        cx, cy, cz = math.cos(clat) * math.cos(clon), math.sin(clat), math.cos(clat) * math.sin(clon)
        span = rad * 2.6
        y0 = max(0, int((0.5 - (clat + span) / math.pi) * H) - 1)
        y1 = min(H, int((0.5 - (clat - span) / math.pi) * H) + 2)
        dlon = span / max(0.08, math.cos(min(abs(clat) + span, 1.5)))
        if dlon > math.pi:
            x0, x1 = 0, W
        else:
            x0 = int((clon - dlon) / (2 * math.pi) * W) - 1
            x1 = int((clon + dlon) / (2 * math.pi) * W) + 2
        xs = np.arange(x0, x1) % W
        ys = np.arange(y0, y1)
        lat = (0.5 - (ys + 0.5) / H)[:, None] * math.pi
        lon = ((xs + 0.5) / W)[None, :] * 2 * math.pi
        px, py, pz = np.cos(lat) * np.cos(lon), np.sin(lat) * np.ones_like(lon), np.cos(lat) * np.sin(lon)
        d = np.arccos(np.clip(px * cx + py * cy + pz * cz, -1, 1)) / rad
        depth = 0.5 + 0.5 * rad / rmax
        bowl = np.where(d < 1, -(1 - d ** 2) * 0.7, 0)
        rim = np.exp(-((d - 1.0) / 0.16) ** 2) * 0.22
        ejecta = np.where(d > 1, np.exp(-(d - 1) * 2.2) * 0.1, 0)
        peak = np.where(d < 0.25, (0.25 - d) * 1.6, 0) if rad > rmax * 0.4 else 0
        height[np.ix_(ys, xs)] += (bowl + rim + ejecta + peak) * depth
        if rays is not None and k < ray_count:
            ang = np.arctan2(pz * cx - px * cz, py - cy)
            streak = np.clip(np.cos(ang * r.integers(9, 16) + r.uniform(0, 6)) ** 12, 0, 1)
            fall = np.where(d > 1, np.exp(-(d - 1) * 0.35), 0)
            rays[np.ix_(ys, xs)] += streak * fall * 0.8
    return height


# ---------------------------------------------------------------------------------------------- textures

def space_sky():
    W, H = 4096, 2048
    lat, lon = latlon(H, W)
    x, y, z = np.cos(lat) * np.cos(lon), np.sin(lat), np.cos(lat) * np.sin(lon)
    gal_n = np.array([0.18, 0.92, 0.35]); gal_n /= np.linalg.norm(gal_n)
    core_dir = np.array([0.9, -0.12, -0.42]); core_dir -= gal_n * core_dir.dot(gal_n); core_dir /= np.linalg.norm(core_dir)
    b = x * gal_n[0] + y * gal_n[1] + z * gal_n[2]            # sin(galactic latitude)
    c = x * core_dir[0] + y * core_dir[1] + z * core_dir[2]   # toward the core
    clouds = warped(H, W, 10, 11, 0.12, 7)
    fine = noise(H, W, 64, 12, 4)
    band = np.exp(-(b / 0.13) ** 2) * (0.45 + 0.55 * clouds) * (0.75 + 0.25 * fine)
    bulge = np.exp(-((1 - c) / 0.35) ** 2) * np.exp(-(b / 0.2) ** 2)
    lanes = ridged(warped(H, W, 7, 13, 0.25, 6), 5.5) * np.exp(-(b / 0.06) ** 2)
    glow = np.clip(band * 0.8 + bulge * 1.1, 0, 2) * (1 - 0.82 * np.clip(lanes * 1.4, 0, 1))
    col = np.array([0.82, 0.86, 1.0]) * (1 - np.clip(bulge, 0, 1))[..., None] + np.array([1.0, 0.86, 0.66]) * np.clip(bulge, 0, 1)[..., None]
    rgb = np.full((H, W, 3), 2.0, np.float32) + glow[..., None] * col * 105
    # sparse H-alpha nebulae along the plane
    neb = np.clip(warped(H, W, 14, 14, 0.3, 5) - 0.72, 0, 1) * 3 * np.exp(-(b / 0.18) ** 2)
    rgb += neb[..., None] * np.array([30, 7, 12])
    # stars: density follows the band
    r = np.random.default_rng(15)
    n = 160000
    sl = np.arcsin(r.uniform(-1, 1, n))
    so = r.uniform(0, 2 * np.pi, n)
    sx, sy, sz = np.cos(sl) * np.cos(so), np.sin(sl), np.cos(sl) * np.sin(so)
    sb = sx * gal_n[0] + sy * gal_n[1] + sz * gal_n[2]
    keep = r.random(n) < 0.35 + 0.65 * np.exp(-(sb / 0.22) ** 2)
    sl, so = sl[keep], so[keep]
    m = r.random(len(sl)) ** 6.5
    temp = r.random(len(sl))
    cols = np.where(temp[:, None] < 0.12, [0.68, 0.78, 1.0], np.where(temp[:, None] < 0.55, [0.95, 0.97, 1.0],
           np.where(temp[:, None] < 0.82, [1.0, 0.93, 0.8], np.where(temp[:, None] < 0.95, [1.0, 0.8, 0.6], [1.0, 0.65, 0.5]))))
    py = ((0.5 - sl / np.pi) * H).astype(int).clip(0, H - 1)
    px = (so / (2 * np.pi) * W).astype(int) % W
    stars = np.zeros((H, W, 3), np.float32)
    v = (40 + 215 * m)[:, None] * cols
    np.add.at(stars, (py, px), v)
    bright = m > 0.45
    for dy, dx, f in ((0, 1, 0.45), (0, -1, 0.45), (1, 0, 0.45), (-1, 0, 0.45), (1, 1, 0.18), (-1, -1, 0.18), (1, -1, 0.18), (-1, 1, 0.18)):
        np.add.at(stars, ((py[bright] + dy).clip(0, H - 1), (px[bright] + dx) % W), v[bright] * f)
    halo = np.stack([blur(stars[..., k], 2.2) for k in range(3)], -1)
    rgb = rgb + stars + halo * 1.6
    save(rgb, "space_sky")


def sun():
    W, H = 512, 256
    g = noise(H, W, 48, 21, 3)
    cells = ridged(noise(H, W, 90, 22, 2), 4)
    v = 0.86 + 0.1 * g - 0.08 * cells
    rgb = ramp(v, [(0.6, (255, 200, 120)), (0.85, (255, 238, 205)), (1.0, (255, 252, 245))])
    save(rgb, "sun")
    S = 512
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    dx, dy = (xx - S / 2) / (S / 2), (yy - S / 2) / (S / 2)
    rr = np.sqrt(dx ** 2 + dy ** 2)
    th = np.arctan2(dy, dx)
    rays = np.zeros_like(rr)
    rng = np.random.default_rng(23)
    for k in range(10):
        a = rng.uniform(0, np.pi)
        rays += np.exp(-((np.sin(th - a)) / 0.012) ** 2) * rng.uniform(0.15, 0.4)
    glare = np.exp(-rr * 7) * 1.0 + np.exp(-rr * 2.6) * 0.32 + rays * np.exp(-rr * 3.2) * 0.7
    glare *= np.clip(1 - rr, 0, 1)
    rgb = np.stack([glare * 255, glare * 246, glare * 232], -1)
    save(rgb, "sun_glare")


def earth():
    W, H = 4096, 2048
    lat, lon = latlon(H, W)
    alat = np.abs(lat)
    cont = warped(H, W, 5, 31, 0.22, 7) * 0.78 + noise(H, W, 22, 32, 5) * 0.22
    sea_level = np.percentile(cont, 70)
    h = cont - sea_level
    land = h > 0
    detail = noise(H, W, 48, 33, 5)
    mountains = ridged(warped(H, W, 12, 34, 0.15, 6), 6) * np.clip(h * 12, 0, 1)
    moist = warped(H, W, 8, 35, 0.2, 5)
    # climate: deserts around 25 deg, rainforest at the equator, boreal and tundra poleward
    dry_belt = np.exp(-((alat - 0.42) / 0.16) ** 2)
    aridity = np.clip(dry_belt * 1.25 - moist * 0.7 + 0.1, 0, 1)
    rain = ramp(detail, [(0, (22, 52, 18)), (1, (48, 84, 30))])
    temperate = ramp(detail, [(0, (60, 82, 38)), (1, (98, 112, 60))])
    desert = ramp(detail, [(0, (178, 142, 96)), (0.6, (206, 172, 120)), (1, (226, 196, 150))])
    boreal = ramp(detail, [(0, (40, 56, 34)), (1, (66, 76, 52))])
    tundra = ramp(detail, [(0, (112, 104, 88)), (1, (142, 134, 116))])
    eq = np.clip(1 - alat / 0.35, 0, 1)
    ground = rain * eq[..., None] + temperate * (1 - eq[..., None])
    ground = ground * (1 - aridity[..., None]) + desert * aridity[..., None]
    north = np.clip((alat - 0.85) * 4, 0, 1)
    ground = ground * (1 - north[..., None]) + boreal * north[..., None]
    tn = np.clip((alat - 1.08) * 5, 0, 1)
    ground = ground * (1 - tn[..., None]) + tundra * tn[..., None]
    ground = ground * (1 - mountains[..., None] * 0.35) + np.array([125, 112, 98]) * mountains[..., None] * 0.35
    snowcap = np.clip((mountains - 0.55) * 4, 0, 1) * np.clip(alat * 1.4 + 0.2, 0, 1)
    ground = ground * (1 - snowcap[..., None]) + 236 * snowcap[..., None]
    ground *= relief(cont * 60 + mountains * 8, 0.8)[..., None]
    depth = np.clip(-h * 7, 0, 1)
    sea = ramp(depth, [(0, (38, 104, 132)), (0.08, (20, 70, 118)), (0.4, (10, 38, 86)), (1, (6, 20, 56))])
    rgb = np.where(land[..., None], ground, sea)
    ice = np.clip((alat - 1.22 + (detail - 0.5) * 0.18) * 9, 0, 1)
    rgb = rgb * (1 - ice[..., None]) + np.array([238, 242, 250]) * ice[..., None]
    save(rgb, "earth")

    # city lights: clustered on habitable land, along coasts, in temperate zones
    W2, H2 = 2048, 1024
    small = lambda a: np.asarray(Image.fromarray(a.astype(np.float32)).resize((W2, H2), Image.BILINEAR))
    land2 = small(land.astype(np.float32)) > 0.5
    coast = small(blur(land.astype(np.float32), 6)) * (1 - small(land.astype(np.float32)))
    lat2, _ = latlon(H2, W2)
    habit = np.exp(-((np.abs(lat2) - 0.62) / 0.35) ** 2) * (1 - small(aridity) * 0.85) * (1 - small(ice))
    clusters = np.clip(noise(H2, W2, 80, 36, 3) - 0.55, 0, 1) * 3
    towns = (np.random.default_rng(37).random((H2, W2)) < 0.012).astype(np.float32)
    lights = (clusters * habit * land2 + towns * habit * land2 * 0.8 + coast * habit * 0.6)
    lights = np.clip(lights + blur(lights, 1.5) * 0.8, 0, 1.4)
    save(np.stack([lights * 255, lights * 196, lights * 120], -1), "earth_night")

    # clouds: storm swirls, the ITCZ band, mid-latitude fronts
    c = warped(H, W, 7, 38, 0.38, 7) * 0.6 + warped(H, W, 20, 39, 0.5, 5) * 0.4
    itcz = np.exp(-(lat / 0.09) ** 2) * 0.25
    fronts = np.exp(-((alat - 0.95) / 0.22) ** 2) * 0.18
    dry = dry_belt * 0.2
    a = np.clip((c + itcz + fronts - dry - 0.5) * 3.2, 0, 1) ** 1.2
    wisps = ridged(warped(H, W, 30, 40, 0.6, 4), 7) * 0.25
    a = np.clip(a + wisps * (a > 0.05), 0, 1)
    save(np.full((H, W, 3), 250.0), "earth_clouds", alpha=a * 255)


def moon():
    W, H = 4096, 2048
    lat, lon = latlon(H, W)
    hgt = noise(H, W, 6, 41, 6) * 0.3
    rays = np.zeros((H, W), np.float32)
    stamp_craters(hgt, 1800, 42, 0.002, 0.035, rays, 0)
    stamp_craters(hgt, 70, 43, 0.035, 0.14, rays, 7)
    maria = np.clip((warped(H, W, 3, 44, 0.25, 5) - 0.6) * 5, 0, 1)
    near = np.clip(np.cos(lon - np.pi) * np.cos(lat), 0, 1)   # maria favour one hemisphere
    maria *= 0.4 + 0.8 * near
    tone = noise(H, W, 40, 45, 4)
    base = 150 + 30 * tone
    alb = base * (1 - np.clip(maria, 0, 1) * 0.55)
    alb += np.clip(rays, 0, 1) * 60
    rgb = np.stack([alb * 1.0, alb * 0.985, alb * 0.96], -1) * relief(hgt * 4, 2.2)[..., None]
    save(rgb, "moon")


def mars():
    W, H = 4096, 2048
    lat, lon = latlon(H, W)
    alat = np.abs(lat)
    hgt = warped(H, W, 5, 51, 0.25, 7) * 0.5
    stamp_craters(hgt, 500, 52, 0.003, 0.06)
    # great canyon along the equator, two big volcanoes
    canyon = np.exp(-(((lat + 0.15) - 0.05 * np.sin(lon * 3)) / 0.025) ** 2) * np.exp(-((lon - 4.6) / 0.6) ** 2)
    hgt -= canyon * 1.4
    for (vl, vo, vr) in ((0.32, 3.9, 0.07), (0.05, 4.1, 0.045), (-0.1, 3.75, 0.04)):
        d = np.sqrt((lat - vl) ** 2 + ((lon - vo) * np.cos(vl)) ** 2) / vr
        hgt += np.exp(-d ** 2) * 1.6 - np.exp(-(d / 0.15) ** 2) * 0.4
    dark = np.clip((warped(H, W, 4, 53, 0.3, 6) - 0.58) * 3.5, 0, 1)
    tone = noise(H, W, 30, 54, 4)
    rgb = ramp(tone * 0.6 + hgt * 0.4, [(0, (140, 70, 40)), (0.5, (188, 104, 58)), (1, (222, 150, 98))])
    rgb = rgb * (1 - dark[..., None] * 0.5) + np.array([88, 56, 42]) * dark[..., None] * 0.5
    rgb = rgb * relief(hgt * 3, 2.4)[..., None]
    cap = np.clip((alat - 1.3 + (tone - 0.5) * 0.12) * 14, 0, 1)
    rgb = rgb * (1 - cap[..., None]) + np.array([240, 236, 232]) * cap[..., None]
    save(rgb, "mars")


def venus():
    W, H = 2048, 1024
    lat, lon = latlon(H, W)
    chev = np.sin(lat * 6 + np.abs(np.sin(lon)) * 1.5)
    c = warped(H, W, 6, 61, 0.35, 6)
    v = 0.6 + 0.2 * c + 0.08 * chev
    rgb = ramp(v, [(0.4, (186, 150, 96)), (0.7, (226, 204, 156)), (1.0, (248, 238, 210))])
    save(rgb, "venus")
    a = np.clip((warped(H, W, 10, 62, 0.5, 5) - 0.45) * 2.4, 0, 1) * 0.8
    save(np.full((H, W, 3), [252, 240, 205], np.float32), "venus_clouds", alpha=a * 255)


def europa():
    W, H = 1024, 512
    base = noise(H, W, 8, 71, 6)
    rgb = ramp(base, [(0, (190, 182, 168)), (0.6, (226, 222, 212)), (1, (246, 244, 238))])
    lines = np.zeros((H, W), np.float32)
    for k in range(9):
        lines = np.maximum(lines, ridged(warped(H, W, 2 + k % 4, 72 + k, 0.55, 4), 110) * (0.6 + 0.4 * (k % 3 == 0)))
    lines += blur(lines, 1.2) * 0.6
    chaos = np.clip((warped(H, W, 5, 81, 0.3, 5) - 0.7) * 4, 0, 1) * noise(H, W, 60, 82, 3)
    brown = np.array([150, 96, 70])
    rgb = rgb * (1 - np.clip(lines, 0, 1)[..., None] * 0.42) + brown * np.clip(lines, 0, 1)[..., None] * 0.42
    rgb = rgb * (1 - chaos[..., None] * 0.4) + np.array([170, 120, 92]) * chaos[..., None] * 0.4
    save(rgb, "europa")


def jupiter():
    W, H = 4096, 2048
    lat, lon = latlon(H, W)
    turb = warped(H, W, 12, 91, 0.06, 6) - 0.5
    shear = noise(H, W, 3, 92, 4) - 0.5
    phase = lat * 10.5 + turb * 1.3 + shear * 0.5
    belts = 0.5 + 0.3 * np.sin(phase * 2.0) + 0.14 * np.sin(phase * 4.7 + 1.1) + 0.08 * np.sin(phase * 9.3 + 0.3)
    belts = np.clip(belts + (noise(H, W, 2, 95, 3) - 0.5) * 0.25, 0, 1)
    fine = warped(H, W, 40, 93, 0.04, 4)
    rgb = ramp(belts, [(0, (140, 96, 66)), (0.3, (176, 128, 92)), (0.5, (212, 182, 146)), (0.7, (234, 220, 196)), (1, (244, 236, 220))])
    rgb *= (0.9 + 0.2 * fine)[..., None]
    # festoons dragging off the equatorial belt
    fest = ridged(warped(H, W, 18, 94, 0.2, 4), 10) * np.exp(-((lat - 0.12) / 0.05) ** 2)
    rgb = rgb * (1 - fest[..., None] * 0.35) + np.array([110, 120, 140]) * fest[..., None] * 0.35
    # Great Red Spot and white ovals
    for (sl, so, a_, b_, col, k) in ((-0.38, 2.1, 0.09, 0.2, (196, 96, 66), 0.85),
                                     (-0.62, 4.0, 0.035, 0.07, (244, 238, 228), 0.7),
                                     (-0.6, 4.6, 0.03, 0.06, (244, 238, 228), 0.7),
                                     (0.5, 1.0, 0.03, 0.065, (240, 232, 220), 0.6)):
        dl = (lat - sl) / a_
        do = np.angle(np.exp(1j * (lon - so))) / b_
        rr = np.sqrt(dl ** 2 + do ** 2)
        swirl = np.sin(np.arctan2(dl, do) * 3 + rr * 6) * 0.08
        m = np.clip(1 - rr, 0, 1) ** 0.7
        rgb = rgb * (1 - m[..., None] * k) + (np.array(col) * (1 + swirl[..., None])) * m[..., None] * k
    polar = np.clip((np.abs(lat) - 1.1) * 2.2, 0, 1)
    rgb = rgb * (1 - polar[..., None] * 0.45) + np.array([110, 106, 110]) * polar[..., None] * 0.45
    save(rgb, "jupiter")


def saturn():
    W, H = 1024, 512
    lat, lon = latlon(H, W)
    turb = warped(H, W, 10, 101, 0.03, 5) - 0.5
    b = 0.5 + 0.5 * np.sin((lat * 8 + turb * 0.6) * 2)
    rgb = ramp(b, [(0, (196, 166, 116)), (0.5, (226, 204, 156)), (1, (240, 228, 196))])
    polar = np.clip((np.abs(lat) - 1.15) * 2.5, 0, 1)
    rgb = rgb * (1 - polar[..., None] * 0.4) + np.array([150, 160, 170]) * polar[..., None] * 0.4
    save(rgb, "saturn")
    # rings: V = inner (1.24 R) -> outer (2.3 R)
    RH, RW = 1024, 64
    v = np.linspace(0, 1, RH)
    R = 1.24 + v * (2.3 - 1.24)
    dens = np.zeros(RH)
    dens += np.where((R > 1.24) & (R < 1.52), 0.18, 0)          # C ring
    dens += np.where((R > 1.52) & (R < 1.95), 0.9, 0)           # B ring
    dens += np.where((R > 2.03) & (R < 2.27), 0.62, 0)          # A ring
    dens *= 1 - np.exp(-((R - 2.215) / 0.006) ** 2)             # Encke gap
    rng = np.random.default_rng(102)
    ripples = np.zeros(RH)
    for k in range(200):
        ripples += rng.uniform(-0.12, 0.12) * np.exp(-((R - rng.uniform(1.24, 2.3)) / rng.uniform(0.002, 0.012)) ** 2)
    dens = np.clip(dens + ripples * (dens > 0), 0, 1)
    col = np.stack([200 + 30 * v, 180 + 30 * v, 150 + 30 * v], -1)
    alpha = np.repeat((dens * 245)[:, None], RW, 1)
    save(np.repeat(col[:, None, :], RW, 1), "saturn_rings", alpha=alpha)


def blast():
    W, H = 2048, 1024
    a = np.zeros((H, W), np.float32)
    for k in range(4):
        a = np.maximum(a, ridged(warped(H, W, 3 + k * 2, 111 + k, 0.4, 5), 26 + k * 6) * (1 - 0.18 * k))
    a = np.clip(a + blur(a, 3) * 0.9 + blur(a, 9) * 0.5, 0, 1.2)
    save(np.stack([a * 255, a * 170, a * 80], -1), "planet_cracks")
    W, H = 512, 256
    p = warped(H, W, 8, 121, 0.4, 6)
    v = np.clip((p - 0.25) * 1.6, 0, 1)
    save(ramp(v, [(0, (40, 6, 2)), (0.4, (200, 60, 12)), (0.7, (255, 170, 60)), (1, (255, 245, 210))]), "plasma")
    S = 128
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    rr = np.sqrt((xx - S / 2) ** 2 + (yy - S / 2) ** 2) / (S / 2)
    puff = np.clip(1 - rr, 0, 1) ** 1.6 * (0.6 + 0.4 * noise(S, S, 6, 131, 4))
    save(np.full((S, S, 3), 255.0), "dust", alpha=puff * 255)


def blast_v2():
    """Fire cloud, smoke puff, ejecta streak and anamorphic flare for the v4.0.4 planet death."""
    S = 256
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    dx, dy = (xx - S / 2) / (S / 2), (yy - S / 2) / (S / 2)
    rr = np.sqrt(dx ** 2 + dy ** 2)
    ang = np.arctan2(dy, dx)
    for name, seed, lumpy in (("fire_cloud", 141, 0.45), ("smoke", 151, 0.6)):
        n = warped(S, S, 5, seed, 0.35, 6)
        lobes = 0.5 + 0.5 * np.sin(ang * 7 + n * 6) * 0.5 + 0.25 * np.sin(ang * 3 + 1.3)
        edge = 1 - rr * (1 + lumpy * (n - 0.5) + 0.18 * (lobes - 0.5))
        shape = np.clip(edge * 2.2, 0, 1) ** 1.3 * np.clip((1 - rr) * 4, 0, 1)
        detail = warped(S, S, 14, seed + 1, 0.25, 5)
        bright = np.clip(0.35 + 0.55 * n + 0.35 * (detail - 0.5), 0, 1)
        if name == "fire_cloud":
            core = np.clip(1 - rr * 1.6, 0, 1) ** 2
            bright = np.clip(bright + core * 0.6, 0, 1)
            a = shape * (0.55 + 0.45 * detail)
        else:
            # billowy, self-shadowed: lit from the top-left
            bright = np.clip(0.45 + 0.5 * n - 0.25 * (dx + dy) * 0.5, 0, 1)
            a = shape * (0.7 + 0.3 * detail)
        save(np.stack([bright * 255] * 3, -1), name, alpha=a * 255)
    # streak: U along the trail (0 tail -> 1 head), V across
    W, H = 256, 32
    u = np.linspace(0, 1, W)[None, :]
    v = np.linspace(-1, 1, H)[:, None]
    taper = 0.25 + 0.75 * u
    across = np.exp(-(v / (0.45 * taper)) ** 2)
    along = u ** 1.6 + 0.9 * np.exp(-((u - 0.94) / 0.05) ** 2)
    a = np.clip(across * along, 0, 1)
    save(np.full((H, W, 3), 255.0), "streak", alpha=a * 255)
    # anamorphic flare: long horizontal line, soft vertical falloff
    W, H = 1024, 64
    u = np.linspace(-1, 1, W)[None, :]
    v = np.linspace(-1, 1, H)[:, None]
    line = np.exp(-(v / 0.08) ** 2) * (1 - np.abs(u)) ** 1.5
    halo = np.exp(-(v / 0.4) ** 2) * np.exp(-(u / 0.25) ** 2) * 0.6
    a = np.clip(line + halo, 0, 1)
    save(np.full((H, W, 3), 255.0), "flare_streak", alpha=a * 255)


def misc():
    save(np.full((16, 16, 3), 255.0), "white")
    save(np.zeros((32, 32, 3)), "moon_gone", alpha=np.zeros((32, 32)))


JOBS = {"space_sky": space_sky, "sun": sun, "earth": earth, "moon": moon, "mars": mars, "venus": venus,
        "europa": europa, "jupiter": jupiter, "saturn": saturn, "blast": blast, "blast_v2": blast_v2, "misc": misc}

if __name__ == "__main__":
    for name in (sys.argv[1:] or JOBS):
        print(name)
        JOBS[name]()
