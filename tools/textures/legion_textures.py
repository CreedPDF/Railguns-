#!/usr/bin/env python3
"""
Arsenal Legion particle sprites (arsenal_legion:kinetic_gaze), generated from scratch: white with the shape in the
alpha channel (the game tints them).

  kinetic_gaze_0..3  the faint "humming distortion" left behind an orbiting weapon: a thin warped ring of disturbed
                     air with a soft core, four frames that ripple outward (age picks the frame)          32 x 32
  kinetic_streak     the piercing energy streak behind a striking weapon: a bright needle with a soft
                     sheath, its head at the right (stretched along the flight path in game)             64 x 16

Run: python3 tools/textures/legion_textures.py
"""
from pathlib import Path

import numpy as np
from PIL import Image

OUT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/arsenal_legion/textures/particle"


def save(name, a):
    a = np.clip(a, 0, 1)
    img = np.dstack([np.ones_like(a), np.ones_like(a), np.ones_like(a), a])
    OUT.mkdir(parents=True, exist_ok=True)
    Image.fromarray((img * 255 + 0.5).astype(np.uint8), "RGBA").save(OUT / f"{name}.png")
    print(name, a.shape[1], a.shape[0])


def gaze(frame):
    s = 32
    y, x = np.mgrid[0:s, 0:s]
    cx, cy = (x + 0.5) / s * 2 - 1, (y + 0.5) / s * 2 - 1
    r = np.hypot(cx, cy)
    ang = np.arctan2(cy, cx)
    k = frame / 3
    ring_r = 0.35 + 0.45 * k
    warp = 0.06 * np.sin(ang * 5 + frame * 1.7) + 0.035 * np.sin(ang * 9 - frame)
    ring = np.exp(-((r - ring_r - warp) / (0.07 + 0.03 * k)) ** 2) * (0.9 - 0.45 * k)
    core = np.exp(-(r / (0.22 + 0.1 * k)) ** 2) * (0.35 - 0.25 * k)
    return np.clip(ring + core, 0, 1) * (r < 1)


def streak():
    h, w = 16, 64
    y, x = np.mgrid[0:h, 0:w]
    u = (y + 0.5) / h * 2 - 1
    t = (x + 0.5) / w
    needle = np.exp(-(u / 0.16) ** 2) * t ** 1.4
    sheath = np.exp(-(u / 0.55) ** 2) * t ** 2.5 * 0.45
    head = np.exp(-((t - 0.94) / 0.05) ** 2) * np.exp(-(u / 0.35) ** 2) * 0.6
    return np.clip(needle + sheath + head, 0, 1)


if __name__ == "__main__":
    for f in range(4):
        save(f"kinetic_gaze_{f}", gaze(f))
    save("kinetic_streak", streak())
