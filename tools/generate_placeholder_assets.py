#!/usr/bin/env python3
"""
Generates the placeholder GeckoLib assets for the Orbital Railgun:

  geo/railgun.geo.json              Bedrock geometry (box-UV, auto-packed)
  animations/railgun.animation.json falling / deploy / idle / charge / fire
  textures/entity/railgun.png       hull texture painted per-material into each cube's UV box
  textures/entity/railgun_glowmask.png   emissive pixels for GeckoLib's AutoGlowingGeoLayer
  textures/item/railgun_remote.png  16x16 item sprite
  icon.png                          mod icon

Replace these with your Blender/Blockbench export when ready. Keep the bone names `turret`
(yaw pivot at y=40 units) and `barrel` (pitch pivot, pointing -Z), and the animation names, or
update RailgunModel / RailgunEntity accordingly.

Usage: python3 tools/generate_placeholder_assets.py   (needs Pillow)
"""
import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/orbital_railgun"
TEX = 256
random.seed(7)

# ---------------------------------------------------------------------------------------------
# Geometry: (bone, parent, pivot, [ (origin, size, material) ... ])
# Units are 1/16 block. Model is symmetric in X (GeckoLib mirrors X on import).
# ---------------------------------------------------------------------------------------------
BONES = [
    ("root", None, [0, 0, 0], []),
    ("base", "root", [0, 0, 0], [
        ([-17, 0, -17], [34, 3, 34], "dark"),
        ([-14, 3, -14], [28, 7, 28], "hull"),
        ([-15, 10, -15], [30, 1, 30], "glow"),
        ([-9, 11, -9], [18, 5, 18], "dark"),
        ([-5, 16, -5], [10, 14, 10], "dark"),
    ]),
    ("leg_n", "base", [0, 2, -14], [
        ([-3, 0, -34], [6, 5, 20], "hull"),
        ([-5, 0, -38], [10, 2, 6], "dark"),
        ([-1, 5, -30], [2, 1, 14], "glow"),
    ]),
    ("leg_s", "base", [0, 2, 14], [
        ([-3, 0, 14], [6, 5, 20], "hull"),
        ([-5, 0, 32], [10, 2, 6], "dark"),
        ([-1, 5, 16], [2, 1, 14], "glow"),
    ]),
    ("leg_e", "base", [14, 2, 0], [
        ([14, 0, -3], [20, 5, 6], "hull"),
        ([32, 0, -5], [6, 2, 10], "dark"),
        ([16, 5, -1], [14, 1, 2], "glow"),
    ]),
    ("leg_w", "base", [-14, 2, 0], [
        ([-34, 0, -3], [20, 5, 6], "hull"),
        ([-38, 0, -5], [6, 2, 10], "dark"),
        ([-30, 5, -1], [14, 1, 2], "glow"),
    ]),
    ("turret", "root", [0, 40, 0], [
        ([-10, 30, -10], [20, 12, 20], "hull"),
        ([-13, 32, -6], [3, 10, 12], "dark"),
        ([10, 32, -6], [3, 10, 12], "dark"),
        ([-8, 42, -8], [16, 2, 16], "dark"),
        ([-3, 44, 3], [6, 3, 5], "glow"),
    ]),
    ("barrel", "turret", [0, 40, 0], [
        ([-6, 35, -4], [12, 10, 14], "dark"),
        ([-5, 37, -52], [3, 6, 48], "hull"),
        ([2, 37, -52], [3, 6, 48], "hull"),
        ([-2, 38, -50], [4, 4, 46], "glow"),
        ([-7, 34, -56], [14, 12, 4], "dark"),
    ]),
    ("coils", "barrel", [0, 40, 0], []),
]

# Accelerator coil frames around the rails (square frames centred on the barrel axis).
coil_cubes = []
for z in (-16, -26, -36, -46):
    coil_cubes += [
        ([-7, 45, z], [14, 2, 3], "glow"),
        ([-7, 33, z], [14, 2, 3], "glow"),
        ([-7, 35, z], [2, 10, 3], "glow"),
        ([5, 35, z], [2, 10, 3], "glow"),
    ]
BONES[-1] = ("coils", "barrel", [0, 40, 0], coil_cubes)


def box_uv_size(size):
    sx, sy, sz = (max(1, math.ceil(v)) for v in size)
    return 2 * (sx + sz), sz + sy


def pack(cubes):
    """Simple shelf packer for box-UV footprints. Returns {cube_index: (u, v)}."""
    order = sorted(range(len(cubes)), key=lambda i: -box_uv_size(cubes[i][1])[1])
    x = y = shelf_h = 0
    out = {}
    for i in order:
        w, h = box_uv_size(cubes[i][1])
        if x + w > TEX:
            x, y, shelf_h = 0, y + shelf_h, 0
        if y + h > TEX:
            raise SystemExit("texture atlas overflow; raise TEX")
        out[i] = (x, y)
        x += w
        shelf_h = max(shelf_h, h)
    return out


MATERIALS = {
    "hull": ((78, 84, 94), (58, 63, 72)),
    "dark": ((34, 37, 44), (24, 26, 31)),
    "glow": ((255, 40, 30), (200, 10, 10)),
}


def paint(img, glow, u, v, size, material):
    w, h = box_uv_size(size)
    light, shade = MATERIALS[material]
    d = ImageDraw.Draw(img)
    for py in range(v, v + h):
        for px in range(u, u + w):
            n = random.randint(-6, 6)
            base = light if ((px - u) // 4 + (py - v) // 4) % 7 else shade
            if material == "glow":
                t = 0.5 + 0.5 * math.sin((py - v) * 0.9)
                c = tuple(int(shade[k] + (light[k] - shade[k]) * t) for k in range(3))
            else:
                c = tuple(max(0, min(255, base[k] + n)) for k in range(3))
            img.putpixel((px, py), c + (255,))
            if material == "glow":
                glow.putpixel((px, py), (255, 255, 255, 255))
    if material != "glow":
        # Panel seams + rivets for a machined look.
        d.rectangle([u, v, u + w - 1, v + h - 1], outline=(20, 22, 26, 255))
        for px in range(u + 2, u + w - 1, 6):
            img.putpixel((px, v + 1), (140, 146, 156, 255))
        if material == "hull" and h > 6 and w > 10:
            d.line([u + 1, v + h // 2, u + w - 2, v + h // 2], fill=(120, 20, 20, 255))


def build_geo_and_textures():
    flat = []
    for bi, (_, _, _, cubes) in enumerate(BONES):
        for ci, cube in enumerate(cubes):
            flat.append((bi, ci, cube))
    uv = pack([c for _, _, c in flat])

    img = Image.new("RGBA", (TEX, TEX), (0, 0, 0, 0))
    glow = Image.new("RGBA", (TEX, TEX), (0, 0, 0, 0))
    bones_json = []
    cube_uv = {}
    for idx, (bi, ci, (origin, size, mat)) in enumerate(flat):
        u, v = uv[idx]
        cube_uv[(bi, ci)] = [u, v]
        paint(img, glow, u, v, size, mat)

    for bi, (name, parent, pivot, cubes) in enumerate(BONES):
        bone = {"name": name, "pivot": pivot}
        if parent:
            bone["parent"] = parent
        if cubes:
            bone["cubes"] = [{"origin": o, "size": s, "uv": cube_uv[(bi, ci)]}
                             for ci, (o, s, _) in enumerate(cubes)]
        bones_json.append(bone)

    geo = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry.railgun",
                "texture_width": TEX,
                "texture_height": TEX,
                "visible_bounds_width": 8,
                "visible_bounds_height": 6,
                "visible_bounds_offset": [0, 2, 0],
            },
            "bones": bones_json,
        }],
    }
    (ROOT / "geo").mkdir(parents=True, exist_ok=True)
    (ROOT / "textures/entity").mkdir(parents=True, exist_ok=True)
    (ROOT / "geo/railgun.geo.json").write_text(json.dumps(geo, indent=2))
    img.save(ROOT / "textures/entity/railgun.png")
    glow.save(ROOT / "textures/entity/railgun_glowmask.png")


# ---------------------------------------------------------------------------------------------
# Animations. Only scale, Y-position and Z-rotation are keyframed: these are sign-convention
# safe in GeckoLib. Turret yaw / barrel pitch are applied procedurally in RailgunModel.
# ---------------------------------------------------------------------------------------------
FOLD = 0.12


def legs(scale_value):
    return {
        "leg_n": {"scale": scale_value([1, 1, FOLD], [1, 1, 1])},
        "leg_s": {"scale": scale_value([1, 1, FOLD], [1, 1, 1])},
        "leg_e": {"scale": scale_value([FOLD, 1, 1], [1, 1, 1])},
        "leg_w": {"scale": scale_value([FOLD, 1, 1], [1, 1, 1])},
    }


def build_animations():
    folded = legs(lambda f, _d: {"0.0": f, "1.0": f})
    folded.update({
        "turret": {"position": {"0.0": [0, -18, 0], "1.0": [0, -18, 0]}},
        "barrel": {"scale": {"0.0": [1, 1, 0.35], "1.0": [1, 1, 0.35]}},
        "coils": {"rotation": {"0.0": [0, 0, 0], "1.0": [0, 0, 90]}},
    })

    deploy = legs(lambda f, d: {"0.0": f, "0.2": f, "0.8": [d[0] * 1.05 if f[0] != 1 else 1, 1, d[2] * 1.05 if f[2] != 1 else 1], "1.0": d})
    deploy.update({
        "turret": {"position": {"0.0": [0, -18, 0], "1.0": [0, -18, 0], "1.8": [0, 1, 0], "2.0": [0, 0, 0]}},
        "barrel": {"scale": {"0.0": [1, 1, 0.35], "2.0": [1, 1, 0.35], "2.7": [1, 1, 1.04], "3.0": [1, 1, 1]}},
        "coils": {"rotation": {"0.0": [0, 0, 0], "3.0": [0, 0, 270]}},
    })

    anims = {
        "format_version": "1.8.0",
        "animations": {
            "animation.railgun.falling": {"loop": True, "animation_length": 1.0, "bones": folded},
            "animation.railgun.deploy": {"loop": "hold_on_last_frame", "animation_length": 3.0, "bones": deploy},
            "animation.railgun.idle": {
                "loop": True, "animation_length": 4.0,
                "bones": {
                    "coils": {"rotation": {"0.0": [0, 0, 0], "4.0": [0, 0, 90]},
                              "scale": {"0.0": [1, 1, 1], "2.0": [1.03, 1.03, 1], "4.0": [1, 1, 1]}},
                },
            },
            "animation.railgun.charge": {
                "loop": True, "animation_length": 0.5,
                "bones": {
                    "coils": {"rotation": {"0.0": [0, 0, 0], "0.5": [0, 0, 90]},
                              "scale": {"0.0": [1.04, 1.04, 1], "0.25": [1.12, 1.12, 1], "0.5": [1.04, 1.04, 1]}},
                    "turret": {"position": {"0.0": [0, 0, 0], "0.25": [0, -0.3, 0], "0.5": [0, 0, 0]}},
                },
            },
            "animation.railgun.fire": {
                "animation_length": 1.5,
                "bones": {
                    "barrel": {"scale": {"0.0": [1, 1, 1], "0.05": [1, 1, 0.82], "0.6": [1, 1, 0.95], "1.5": [1, 1, 1]}},
                    "coils": {"rotation": {"0.0": [0, 0, 0], "0.5": [0, 0, 270], "1.5": [0, 0, 360]},
                              "scale": {"0.0": [1.25, 1.25, 1], "1.5": [1, 1, 1]}},
                    "turret": {"position": {"0.0": [0, 0, 0], "0.05": [0, -1.5, 0], "0.5": [0, 0, 0]}},
                },
            },
        },
    }
    (ROOT / "animations").mkdir(parents=True, exist_ok=True)
    (ROOT / "animations/railgun.animation.json").write_text(json.dumps(anims, indent=2))


def build_item_and_icon():
    (ROOT / "textures/item").mkdir(parents=True, exist_ok=True)
    s = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = s.load()
    body, edge, red, screen = (60, 64, 72, 255), (24, 26, 30, 255), (255, 40, 30, 255), (120, 10, 10, 255)
    for y in range(4, 15):
        for x in range(4, 12):
            px[x, y] = edge if x in (4, 11) or y in (4, 14) else body
    for y in range(6, 9):
        for x in range(6, 10):
            px[x, y] = screen
    px[7, 7] = red; px[8, 7] = red
    px[6, 11] = red; px[9, 11] = (255, 176, 32, 255); px[6, 13] = (90, 96, 104, 255); px[9, 13] = (90, 96, 104, 255)
    for y in range(0, 4):
        px[10, y] = edge
    px[10, 0] = red
    s.save(ROOT / "textures/item/railgun_remote.png")

    icon = Image.new("RGBA", (128, 128), (8, 10, 16, 255))
    d = ImageDraw.Draw(icon)
    for r in range(40, 0, -4):
        a = int(255 * (1 - r / 40))
        d.ellipse([64 - r, 64 - r, 64 + r, 64 + r], fill=(255, 30 + a // 6, 20, a))
    d.rectangle([60, 0, 68, 64], fill=(255, 60, 40, 255))
    d.rectangle([62, 0, 66, 64], fill=(255, 230, 220, 255))
    d.rectangle([20, 100, 108, 112], fill=(60, 64, 72, 255))
    d.rectangle([44, 84, 84, 100], fill=(34, 37, 44, 255))
    icon.save(ROOT / "icon.png")


if __name__ == "__main__":
    build_geo_and_textures()
    build_animations()
    build_item_and_icon()
    print("assets written to", ROOT)
