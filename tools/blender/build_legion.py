"""
Arsenal Legion assets, modelled and baked in headless Blender (run by tools/blender/build.sh).

  floating_blade  arsenal_legion:models/entity/floating_blade - the telekinetic harness every animated weapon flies in:
                  two counter-rotating gyroscope rings round the blade (parts "ring_outer", "ring_inner"), a pair of
                  clamp jaws gripping the weapon at its guard and a glowing focus crystal at the pommel ("frame").
                  Built along +Z (Minecraft +Y): the game aligns that axis with the weapon's flight direction.
  legion_altar    the Legion Altar block: an obsidian pedestal with gilded bands and smouldering runes, a hovering
                  focus crystal ("crystal", bobs) inside a ring of six blade-shaped vanes ("vanes", turns).

Colours: obsidian black, gunmetal, gold and ember red / amber glows (no blue). Exported as krl_mesh (see
client/krl/KrlMesh.java) under assets/arsenal_legion/.
"""
import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_krl as K  # noqa: E402
import build_ship as S  # noqa: E402

import bpy  # noqa: E402
from mathutils import Vector  # noqa: E402

RED, HOT, AMBER = S.RED, S.HOT, S.AMBER


def gold_mat(name):
    m = K.Mat(name)
    n = m.noise(6.0, 5, 0.55)
    col = m.ramp(n, [(0.3, (0.38, 0.24, 0.05)), (0.7, (0.75, 0.52, 0.16))])
    col = m.mix(col, (0.12, 0.07, 0.02), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.08)), 0.6, True))
    m.color(col)
    return m


def rune_mat(name):
    """Obsidian carved with glowing ember runes (a grid of short strokes)."""
    m = K.obsidian_mat(name)
    x, z = m.axis("X"), m.axis("Z")
    y = m.axis("Y")
    u = m.math("ADD", x, y)
    cell = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", u, 9.0)), 0.18)
    row = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", z, 7.0)), 0.55)
    pick = m.math("GREATER_THAN", m.noise(14.0, 1, 0.3), 0.5)
    rune = m.math("MULTIPLY", m.math("MULTIPLY", cell, row), pick)
    m.glow(m.ramp(rune, [(0.0, (0, 0, 0)), (1.0, (0.9, 0.18, 0.04))]))
    return m


def mats(prefix):
    return dict(
        obs=K.obsidian_mat(prefix + "_obs"),
        rune=rune_mat(prefix + "_rune"),
        gold=gold_mat(prefix + "_gold"),
        steel=K.metal_mat(prefix + "_steel", (0.3, 0.295, 0.285), 9.0),
        dark=K.metal_mat(prefix + "_dark", (0.035, 0.033, 0.032), 7.0),
        red=K.glow_mat(prefix + "_red", RED, 1.0),
        hot=K.glow_mat(prefix + "_hot", HOT, 1.0, pulse_scale=6.0),
        amber=K.glow_mat(prefix + "_amber", AMBER, 1.0),
    )


class B:
    def __init__(self, parts):
        self.parts, self.objs = parts, []

    def add(self, o, m, part, smooth=False):
        self.objs.append(K.tag(o, part, m, smooth=smooth))
        return o

    def lathe(self, name, prof, m, part, at=(0, 0, 0), segs=32, closed=False, cap=False, mat_of_edge=None):
        o = K.lathe(name, prof, segs, closed=closed, cap=cap, mat_of_edge=mat_of_edge)
        if tuple(at) != (0, 0, 0):
            o.location = at
            K.active(o)
            bpy.ops.object.transform_apply(location=True)
        return self.add(o, m, part, smooth=True)

    def torus(self, name, R, r, m, part, z=0.0, segs=40, tube=8):
        prof = [(R + r * math.cos(a), z + r * math.sin(a)) for a in (k * math.tau / tube for k in range(tube))]
        return self.lathe(name, prof, m, part, segs=segs, closed=True)

    def finish(self, name, mlist, size, glow, assets, pivots):
        K.stamp(self.objs, self.parts)
        o = K.join(self.objs, name)
        K.unwrap(o, 0.004)
        col = K.bake_one(o, mlist, size, f"{assets}/textures/entity/{name}.png")
        gl = K.bake_one(o, mlist, glow, f"{assets}/textures/entity/{name}_glow.png", "glow")
        K.export(o, self.parts, pivots, f"{assets}/models/entity/{name}.json")
        return o, col, gl


def build_blade(assets, out):
    """The harness. Blender Z here is the weapon's long axis (the blade's tip direction), centred on the weapon's grip
    point at the origin; the game draws the weapon itself along the same axis."""
    K.reset()
    M = mats("fb")
    b = B(["frame", "ring_outer", "ring_inner"])
    # gyroscope rings round the middle of the blade (they lie in planes containing the axis, so they read as a gyro)
    o = K.lathe("ro", [(0.36 + 0.022 * math.cos(a), 0.022 * math.sin(a)) for a in (k * math.tau / 8 for k in range(8))], 44, closed=True)
    o.rotation_euler = (math.radians(90), 0, 0)
    o.location = (0, 0, 0.35)
    K.active(o)
    bpy.ops.object.transform_apply(location=True, rotation=True)
    b.add(o, [M["gold"]], "ring_outer", smooth=True)
    o = K.lathe("ro_glow", [(0.362, -0.006), (0.385, -0.006), (0.385, 0.006), (0.362, 0.006)], 44, closed=True)
    o.rotation_euler = (math.radians(90), 0, 0)
    o.location = (0, 0, 0.35)
    K.active(o)
    bpy.ops.object.transform_apply(location=True, rotation=True)
    b.add(o, [M["hot"]], "ring_outer")
    o = K.lathe("ri", [(0.27 + 0.016 * math.cos(a), 0.016 * math.sin(a)) for a in (k * math.tau / 8 for k in range(8))], 40, closed=True)
    o.rotation_euler = (0, math.radians(90), 0)
    o.location = (0, 0, 0.35)
    K.active(o)
    bpy.ops.object.transform_apply(location=True, rotation=True)
    b.add(o, [M["steel"]], "ring_inner", smooth=True)
    for k in range(4):
        a = k * math.pi / 2
        c = Vector((0.27 * math.cos(a), 0, 0.35 + 0.27 * math.sin(a)))
        o = K.box("node", tuple(c), (0.05, 0.05, 0.05))
        b.add(o, [M["amber"]], "ring_inner")
    # clamp jaws at the guard and a focus crystal at the pommel
    for sx in (-1, 1):
        jaw = S.prism("jaw", [(0.0, -0.06), (0.16, -0.02), (0.18, 0.06), (0.05, 0.1), (0.0, 0.06)], "y", -0.03, 0.03, rim_mat=1)
        jaw.scale = (sx, 1, 1)
        K.active(jaw)
        bpy.ops.object.transform_apply(scale=True)
        K.fix_normals(jaw)
        jaw.location = (sx * 0.07, 0, -0.02)
        K.active(jaw)
        bpy.ops.object.transform_apply(location=True)
        b.add(jaw, [M["dark"], M["gold"]], "frame")
        b.add(K.box("jaw_glow", (sx * 0.2, 0, 0.02), (0.012, 0.04, 0.05)), [M["red"]], "frame")
    b.lathe("collar", [(0.05, -0.06), (0.085, -0.05), (0.085, 0.03), (0.05, 0.04)], [M["gold"]], "frame", segs=24, closed=True)
    crystal = [(0.0, -0.62), (0.06, -0.55), (0.08, -0.45), (0.05, -0.36), (0.0, -0.32)]
    b.lathe("crystal", crystal, [M["hot"]], "frame", segs=6)
    b.lathe("cradle", [(0.0, -0.36), (0.07, -0.34), (0.1, -0.3), (0.06, -0.26), (0.0, -0.27)], [M["gold"]], "frame", segs=12)
    mlist = list(M.values())
    o, col, gl = b.finish("floating_blade", mlist, 512, 256, assets, {"ring_outer": (0, 0, 0.35), "ring_inner": (0, 0, 0.35)})
    pm = K.preview_material("fb_prev", col, gl, 4.0)
    K.preview(o, pm, f"{out}/legion_floating_blade.png", (1.2, -1.6, 0.6), (0, 0, 0.1), lens=50, bg=(0.012, 0.011, 0.012))


def build_altar(assets, out):
    K.reset()
    M = mats("la")
    b = B(["body", "vanes", "crystal"])
    # stepped obsidian pedestal with gold bands and rune faces
    b.lathe("plinth", [(0.0, 0.0), (0.5, 0.0), (0.5, 0.1), (0.44, 0.14), (0.0, 0.14)], [M["obs"]], "body", segs=8)
    b.lathe("band0", [(0.505, 0.03), (0.52, 0.04), (0.52, 0.08), (0.505, 0.09)], [M["gold"]], "body", segs=8)
    b.lathe("column", [(0.0, 0.14), (0.34, 0.14), (0.3, 0.22), (0.24, 0.62), (0.3, 0.7), (0.0, 0.7)], [M["rune"]], "body", segs=8)
    b.lathe("band1", [(0.245, 0.45), (0.27, 0.46), (0.27, 0.5), (0.245, 0.51)], [M["gold"]], "body", segs=8)
    b.lathe("bowl", [(0.0, 0.7), (0.32, 0.7), (0.42, 0.8), (0.44, 0.86), (0.4, 0.88), (0.3, 0.8), (0.0, 0.78)], [M["obs"]], "body", segs=8)
    b.lathe("rim", [(0.4, 0.86), (0.455, 0.86), (0.455, 0.885), (0.4, 0.885)], [M["gold"]], "body", segs=8, closed=True)
    b.lathe("ember", [(0.0, 0.79), (0.28, 0.8), (0.0, 0.81)], [M["hot"]], "body", segs=8)
    for k in range(4):
        a = k * math.pi / 2 + math.pi / 4
        b.add(K.box("foot", (0.44 * math.cos(a), 0.44 * math.sin(a), 0.05), (0.14, 0.14, 0.1), rot_z=a, bevel=0.02), [M["gold"]], "body")
    # six blade vanes in a ring (turns), each a slim leaf blade on a gold hilt, tip up
    for k in range(6):
        a = k * math.tau / 6
        d = Vector((math.cos(a), math.sin(a), 0))
        blade = S.prism("vane", [(-0.035, 0.0), (0.035, 0.0), (0.045, 0.22), (0.0, 0.36), (-0.045, 0.22)], "y", -0.008, 0.008, rim_mat=1)
        blade.rotation_euler = (0, math.radians(-12), a + math.pi / 2)
        blade.location = d * 0.42 + Vector((0, 0, 0.98))
        K.active(blade)
        bpy.ops.object.transform_apply(location=True, rotation=True)
        b.add(blade, [M["steel"], M["red"]], "vanes")
        b.add(K.box("hilt", tuple(d * 0.42 + Vector((0, 0, 0.96))), (0.1, 0.03, 0.03), rot_z=a + math.pi / 2), [M["gold"]], "vanes")
    # the focus crystal (bobs)
    b.lathe("focus", [(0.0, 1.0), (0.08, 1.1), (0.1, 1.2), (0.06, 1.32), (0.0, 1.4)], [M["hot"]], "crystal", segs=6)
    for z in (1.08, 1.32):
        o = K.lathe("fring", [(0.13, -0.006), (0.15, -0.006), (0.15, 0.006), (0.13, 0.006)], 24, closed=True)
        o.location = (0, 0, z)
        K.active(o)
        bpy.ops.object.transform_apply(location=True)
        b.add(o, [M["gold"]], "crystal")
    mlist = list(M.values())
    o, col, gl = b.finish("legion_altar", mlist, 512, 256, assets, {"vanes": (0, 0, 0), "crystal": (0, 0, 1.2)})
    pm = K.preview_material("la_prev", col, gl, 4.0)
    K.preview(o, pm, f"{out}/legion_altar.png", (1.6, -2.2, 1.6), (0, 0, 0.7), lens=45, bg=(0.012, 0.011, 0.012))
    K.preview(o, pm, f"{assets}/textures/item/legion_altar.png", (1.7, -2.2, 1.7), (0, 0, 0.7), lens=48, res=64, samples=24, transparent=True)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--no-render", action="store_true")
    K.ARGS = ap.parse_args(argv)
    os.makedirs(K.ARGS.out, exist_ok=True)
    # this set lives in its own namespace: .../assets/orbital_railgun -> .../assets/arsenal_legion
    assets = os.path.join(os.path.dirname(os.path.abspath(K.ARGS.assets)), "arsenal_legion")
    out = os.path.abspath(K.ARGS.out)
    build_blade(assets, out)
    build_altar(assets, out)
    print("[legion] done")


if __name__ == "__main__":
    main()
