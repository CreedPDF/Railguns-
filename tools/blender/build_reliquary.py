"""
The Cosmic Reliquary handheld arsenal, modelled and baked in headless Blender (run by tools/blender/build.sh).

  star_eater_gaze    a gyroscopic stellar apparatus on a pistol grip: a gunmetal spine and gimbal fork holding three
                     nested gold hoops round a neon-blue stellar core, three emitter prongs ahead. Parts: body,
                     hoop_outer (spins about the barrel axis), hoop_mid (about the side axis), hoop_inner (about the
                     vertical axis), core. Flat-shaded: every face has its own standalone normals.
  void_carver_blade  a hyper-extended fracture greatsword: wrapped grip, crystal pommel, a jagged split guard, and a
                     long blade whose edges are rows of raw geometric teeth, with violet fracture light running up its
                     core. Parts: hilt, blade.
  core_revolver      a heavy-frame magnum: long ribbed barrel, six-chamber cylinder (part "cylinder", turns after each
                     shot), hammer, and an exposed engine core chamber in the frame (part "core", thermal glow).

Each is exported as a krl_mesh merged INTO its item model file, cosmic_reliquary:models/item/<name>.json: the file is
a normal "builtin/entity" item model (display transforms for hand, GUI, ground, frame) that also carries the mesh's
"parts" (the vanilla model loader ignores them; the item renderer reads them). Blender axes: x right, y forward (the
barrel / the blade's edge direction), z up; the grip is at the origin.
"""
import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_krl as K  # noqa: E402
import build_ship as S  # noqa: E402

import bpy  # noqa: E402
from mathutils import Vector  # noqa: E402

NEON = (0.2, 0.62, 1.0)
VIOLET = (0.62, 0.16, 1.0)
EMBER = (1.0, 0.42, 0.06)


def gold_mat(name):
    m = K.Mat(name)
    n = m.noise(8.0, 5, 0.55)
    col = m.ramp(n, [(0.3, (0.4, 0.26, 0.06)), (0.7, (0.8, 0.56, 0.18))])
    col = m.mix(col, (0.1, 0.06, 0.02), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.05)), 0.6, True))
    m.color(col)
    return m


def wrap_mat(name):
    """Leather grip wrap: dark diagonal bands."""
    m = K.Mat(name)
    band = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", m.math("ADD", m.axis("Z"), m.math("MULTIPLY", m.axis("X"), 0.6)), 22.0)), 0.18)
    col = m.ramp(m.noise(12.0, 4, 0.5), [(0.3, (0.05, 0.03, 0.025)), (0.7, (0.1, 0.06, 0.04))])
    col = m.mix(col, (0.015, 0.01, 0.01), band)
    m.color(col)
    return m


def fracture_mat(name, base=(0.035, 0.03, 0.045)):
    """Black glassy blade metal crazed with glowing violet fractures (voronoi edges)."""
    m = K.Mat(name)
    vor = m.voronoi_edge(5.0)
    cracks = m.math("LESS_THAN", vor, 0.035)
    col = m.ramp(m.noise(3.0, 5, 0.6), [(0.3, base), (0.7, tuple(c * 2.2 for c in base))])
    col = m.mix(col, (0.5, 0.15, 0.85), cracks)
    m.color(col)
    m.glow(m.ramp(cracks, [(0.0, (0, 0, 0)), (1.0, VIOLET)]))
    return m


def thermal_mat(name):
    """The revolver's engine core: banded heat - white-hot centre lines through orange to dull red."""
    m = K.Mat(name)
    bands = m.math("SINE", m.math("MULTIPLY", m.axis("Y"), 90.0))
    heat = m.math("ADD", m.math("MULTIPLY", bands, 0.25), m.noise(18.0, 3, 0.5))
    col = m.ramp(heat, [(0.2, (0.45, 0.05, 0.01)), (0.55, EMBER), (0.85, (1.0, 0.85, 0.55))])
    m.color(col)
    m.glow(col)
    return m


def mats(p):
    return dict(
        hull=S.ship_hull_mat(p + "_hull", (0.06, 0.058, 0.056)),
        steel=K.metal_mat(p + "_steel", (0.32, 0.31, 0.3), 12.0),
        dark=K.metal_mat(p + "_dark", (0.03, 0.028, 0.028), 9.0),
        gold=gold_mat(p + "_gold"),
        wrap=wrap_mat(p + "_wrap"),
        neon=K.glow_mat(p + "_neon", NEON, 1.0, pulse_scale=9.0),
        violet=K.glow_mat(p + "_violet", VIOLET, 1.0),
        ember=K.glow_mat(p + "_ember", EMBER, 1.0),
        frac=fracture_mat(p + "_frac"),
        thermal=thermal_mat(p + "_thermal"),
    )


class B:
    def __init__(self, parts):
        self.parts, self.objs = parts, []

    def add(self, o, m, part, smooth=False):
        self.objs.append(K.tag(o, part, m if isinstance(m, list) else [m], smooth=smooth))
        return o

    def lathe(self, name, prof, m, part, axis=(0, 0, 1), at=(0, 0, 0), segs=32, closed=False, cap=False, mat_of_edge=None):
        o = K.lathe(name, prof, segs, closed=closed, cap=cap, mat_of_edge=mat_of_edge)
        if tuple(axis) != (0, 0, 1) or tuple(at) != (0, 0, 0):
            K.orient(o, axis, at)
        return self.add(o, m, part)

    def torus(self, name, R, r, m, part, normal, at, segs=48, tube=8):
        prof = [(R + r * math.cos(a), r * math.sin(a)) for a in (k * math.tau / tube for k in range(tube))]
        return self.lathe(name, prof, m, part, axis=normal, at=at, segs=segs, closed=True)

    def box(self, name, c, s, m, part, bevel=0.0, rot=None):
        o = K.box(name, c, s, bevel=bevel)
        if rot:
            S.rot_about(o, rot[0], rot[1], c)
        return self.add(o, m, part)

    def finish(self, name, mlist, assets, pivots, display):
        K.stamp(self.objs, self.parts)
        o = K.join(self.objs, name)
        K.unwrap(o, 0.003)
        col = K.bake_one(o, mlist, 1024, f"{assets}/textures/item/{name}.png")
        gl = K.bake_one(o, mlist, 512, f"{assets}/textures/item/{name}_glow.png", "glow")
        path = f"{assets}/models/item/{name}.json"
        K.export(o, self.parts, pivots, path)
        with open(path) as fh:
            js = json.load(fh)
        model = {"parent": "minecraft:builtin/entity", "gui_light": "front", "display": display}
        model.update(js)
        with open(path, "w") as fh:
            json.dump(model, fh, separators=(",", ":"))
        return o, col, gl


def disp(fp_rot, fp_scale, tp_rot, tp_scale, gui_rot, gui_scale, gui_tr=(0, 0, 0), fp_tr=(0, 0, 0), tp_tr=(0, 0, 0)):
    """Display transforms (vanilla item-model format: degrees, pixels, scale)."""
    def t(r, tr, s):
        return {"rotation": list(r), "translation": list(tr), "scale": [s, s, s]}
    return {
        "firstperson_righthand": t(fp_rot, fp_tr, fp_scale), "firstperson_lefthand": t(fp_rot, fp_tr, fp_scale),
        "thirdperson_righthand": t(tp_rot, tp_tr, tp_scale), "thirdperson_lefthand": t(tp_rot, tp_tr, tp_scale),
        "gui": t(gui_rot, gui_tr, gui_scale), "ground": t((0, 0, 0), (0, 2, 0), gui_scale * 0.45),
        "fixed": t((0, -90, 0), (0, 0, 0), gui_scale * 0.8), "head": t((0, 0, 0), (0, 0, 0), 1.0),
    }


# Hand poses. Guns: barrel along model -Z (blender +Y). In first person the item frame is view space (forward = -Z),
# so no turn is needed; in third person forward is +Y and up is +Z, hence the 90 degree tilt about X.
GUN_FP, GUN_TP, GUN_GUI = (0, 0, 0), (90, 0, 0), (20, -60, 0)


# ================================================================================================ star-eater's gaze

def build_star_eater(assets, out):
    K.reset()
    M = mats("se")
    b = B(["body", "hoop_outer", "hoop_mid", "hoop_inner", "core"])
    C = (0.0, 0.62, 0.1)
    # pistol grip (raked back), trigger and guard
    b.box("grip", (0, -0.03, -0.14), (0.07, 0.1, 0.26), M["wrap"], "body", bevel=0.015, rot=("X", -14))
    b.box("trigger", (0, 0.07, -0.04), (0.02, 0.025, 0.07), M["steel"], "body", rot=("X", 20))
    b.box("guard", (0, 0.085, -0.085), (0.03, 0.09, 0.012), M["dark"], "body")
    # the spine: a lathed tube running forward to the gimbal, with gold collars and neon conduits
    b.lathe("spine", [(0.0, -0.18), (0.055, -0.17), (0.06, 0.0), (0.05, 0.32), (0.035, 0.4), (0.0, 0.41)], [M["hull"]], "body",
            axis=(0, 1, 0), at=(0, 0, 0.06), segs=12)
    for y in (-0.1, 0.1, 0.28):
        b.lathe("collar", [(0.062, -0.012), (0.07, -0.01), (0.07, 0.01), (0.062, 0.012)], [M["gold"]], "body", axis=(0, 1, 0),
                at=(0, y, 0.06), segs=12, closed=True)
    for sx in (-1, 1):
        b.box("conduit", (sx * 0.058, 0.12, 0.06), (0.012, 0.36, 0.012), M["neon"], "body")
    # gimbal fork: two arms out to the hoop pivots
    for sx in (-1, 1):
        a0, a1 = Vector((sx * 0.04, 0.36, 0.06)), Vector((sx * 0.31, 0.62, 0.1))
        b.add(K.rod("fork", a0, a1, 0.022, 8), M["dark"], "body", smooth=True)
        b.add(K.cyl("bearing", 0.035, -0.02, 0.02, 12), M["gold"], "body")
        K.orient(b.objs[-1], (1, 0, 0), (sx * 0.305, 0.62, 0.1))
    # three nested hoops, each in its own plane (they spin on independent axes in game)
    b.torus("ho", 0.29, 0.016, [M["gold"]], "hoop_outer", (0, 1, 0), C)
    b.torus("ho_glow", 0.29, 0.006, [M["neon"]], "hoop_outer", (0, 1, 0), (C[0], C[1] - 0.014, C[2]))
    b.torus("hm", 0.22, 0.014, [M["steel"]], "hoop_mid", (1, 0, 0), C)
    for k in range(4):
        a = k * math.pi / 2
        b.box("hm_node", (C[0], C[1] + 0.22 * math.cos(a), C[2] + 0.22 * math.sin(a)), (0.035, 0.035, 0.035), M["neon"], "hoop_mid")
    b.torus("hi", 0.15, 0.012, [M["gold"]], "hoop_inner", (0, 0, 1), C)
    # the stellar core: a faceted star
    b.lathe("core", [(0.0, -0.085), (0.05, -0.06), (0.075, 0.0), (0.05, 0.06), (0.0, 0.085)], [M["neon"]], "core", at=C, segs=8)
    # emitter prongs
    for k in range(3):
        a = k * math.tau / 3 + math.pi / 2
        p0 = Vector((0.06 * math.cos(a), 0.4, 0.06 + 0.06 * math.sin(a)))
        p1 = Vector((0.11 * math.cos(a), 1.02, 0.1 + 0.11 * math.sin(a)))
        b.add(K.rod("prong", p0, p1, 0.014, 6), M["dark"], "body")
        b.box("prong_tip", tuple(p1), (0.03, 0.06, 0.03), M["neon"], "body")
    mlist = list(M.values())
    pv = {"hoop_outer": C, "hoop_mid": C, "hoop_inner": C, "core": C}
    o, col, gl = b.finish("star_eater_gaze", mlist, assets, pv, disp(GUN_FP, 0.6, GUN_TP, 0.55, GUN_GUI, 0.78))
    K.preview(o, K.preview_material("se_prev", col, gl, 4.0), f"{out}/reliquary_star_eater.png", (1.4, -0.3, 0.7), (0, 0.45, 0.05), lens=45,
              bg=(0.012, 0.011, 0.012))


# ================================================================================================ void-carver's edge

def build_void_carver(assets, out):
    K.reset()
    M = mats("vc")
    b = B(["hilt", "blade"])
    # hilt: wrapped grip, crystal pommel, jagged split guard
    b.lathe("grip", [(0.0, -0.38), (0.032, -0.38), (0.03, 0.0), (0.0, 0.0)], [M["wrap"]], "hilt", segs=10)
    b.lathe("pommel", [(0.0, -0.52), (0.045, -0.46), (0.055, -0.41), (0.03, -0.38), (0.0, -0.38)], [M["violet"]], "hilt", segs=5)
    b.lathe("ferrule", [(0.04, -0.4), (0.048, -0.39), (0.048, -0.37), (0.04, -0.36)], [M["gold"]], "hilt", segs=10, closed=True)
    guard = [(0.0, 0.0), (0.12, -0.02), (0.2, 0.05), (0.31, 0.02), (0.26, 0.09), (0.36, 0.12), (0.2, 0.14), (0.1, 0.1), (0.0, 0.12)]
    pts = guard + [(-x, z) for x, z in reversed(guard[1:-1])]
    b.add(K.extrude_outline("guard", pts, 0.06, rim_mat=1, face_mat=0), [M["dark"], M["gold"]], "hilt")
    # blade: a long tapering outline whose edges are rows of raw teeth (asymmetric, jagged), extruded thin
    L, z0 = 2.05, 0.12
    teeth = 26
    right, left = [], []
    for i in range(teeth + 1):
        t = i / teeth
        z = z0 + t * L
        w = 0.15 * (1 - t) ** 0.7 + 0.012
        tooth = 0.045 * (1 - t * 0.7) * (1 if i % 2 else 0) * (1.3 if i % 5 == 0 else 1)
        right.append((w + tooth, z))
        left.append((-(w + tooth * 0.7 + (0.02 if i % 3 == 0 else 0)), z + 0.02))
    tip = (0.0, z0 + L + 0.22)
    outline = right + [tip] + list(reversed(left))
    b.add(K.extrude_outline("blade", outline, 0.032, rim_mat=1, face_mat=0), [M["frac"], M["steel"]], "blade")
    # the fracture core: a thinner glowing spine, standing proud of both faces
    core = [(0.025 * (1 - i / 12) + 0.006, z0 + 0.05 + i / 12 * (L - 0.1)) for i in range(13)]
    cpts = core + [(0.0, z0 + L + 0.05)] + [(-x, z) for x, z in reversed(core)]
    b.add(K.extrude_outline("vein", cpts, 0.038), [M["violet"], M["violet"]], "blade")
    # broken shards floating off the edge near the tip (fixed to the blade)
    for k, (x, z) in enumerate(((0.16, 1.55), (-0.17, 1.75), (0.11, 1.95))):
        shard = [(0.0, 0.0), (0.05, 0.03), (0.02, 0.1), (-0.02, 0.05)]
        o = K.extrude_outline("shard", [(x + a, z + c) for a, c in shard], 0.02)
        b.add(o, [M["frac"], M["violet"]], "blade")
    mlist = list(M.values())
    o, col, gl = b.finish("void_carver_blade", mlist, assets, {},
                          disp((-20, 0, 15), 0.5, (35, 0, 0), 0.5, (0, 0, -45), 0.36, gui_tr=(-3, -3, 0)))
    K.preview(o, K.preview_material("vc_prev", col, gl, 4.0), f"{out}/reliquary_void_carver.png", (2.0, -2.4, 1.6), (0, 0, 0.85), lens=40,
              bg=(0.012, 0.011, 0.012))


# ================================================================================================ core-infused revolver

def build_revolver(assets, out):
    K.reset()
    M = mats("cr")
    b = B(["frame", "cylinder", "core"])
    CY = (0.0, 0.15, 0.1)
    # grip and frame
    b.box("grip", (0, -0.06, -0.1), (0.06, 0.09, 0.22), M["wrap"], "frame", bevel=0.012, rot=("X", -18))
    b.box("grip_cap", (0, -0.1, -0.21), (0.065, 0.1, 0.03), M["gold"], "frame", rot=("X", -18))
    b.box("frame_main", (0, 0.08, 0.06), (0.075, 0.36, 0.1), M["hull"], "frame", bevel=0.012)
    b.box("topstrap", (0, 0.15, 0.175), (0.06, 0.2, 0.025), M["hull"], "frame", bevel=0.006)
    b.box("guard", (0, 0.06, -0.035), (0.025, 0.12, 0.012), M["dark"], "frame")
    b.box("trigger", (0, 0.04, -0.005), (0.016, 0.02, 0.06), M["steel"], "frame", rot=("X", 15))
    b.box("hammer", (0, -0.085, 0.15), (0.025, 0.05, 0.07), M["steel"], "frame", rot=("X", -25))
    # barrel: long and heavy, with a vent rib and a muzzle brake
    b.lathe("barrel", [(0.0, 0.25), (0.05, 0.25), (0.046, 0.3), (0.042, 0.66), (0.05, 0.68), (0.05, 0.73), (0.022, 0.73), (0.022, 0.25)],
            [M["steel"]], "frame", axis=(0, 1, 0), at=(0, 0, 0.1), segs=16, closed=True)
    b.box("rib", (0, 0.49, 0.155), (0.024, 0.44, 0.02), M["hull"], "frame")
    for k in range(5):
        b.box("vent", (0, 0.3 + k * 0.09, 0.166), (0.026, 0.03, 0.006), M["ember"], "frame")
    b.box("sight", (0, 0.7, 0.175), (0.012, 0.02, 0.025), M["ember"], "frame")
    # six-chamber cylinder (turns)
    b.lathe("cyl", [(0.0, -0.075), (0.072, -0.075), (0.078, -0.06), (0.078, 0.06), (0.072, 0.075), (0.0, 0.075)], [M["dark"]], "cylinder",
            axis=(0, 1, 0), at=CY, segs=18)
    for k in range(6):
        a = k * math.tau / 6
        c = (CY[0] + 0.048 * math.cos(a), CY[1], CY[2] + 0.048 * math.sin(a))
        b.lathe("chamber", [(0.0, 0.076), (0.018, 0.076), (0.018, 0.08), (0.0, 0.08)], [M["ember"]], "cylinder", axis=(0, 1, 0), at=c, segs=8)
        flute = (CY[0] + 0.078 * math.cos(a + math.pi / 6), CY[1], CY[2] + 0.078 * math.sin(a + math.pi / 6))
        b.box("flute", flute, (0.012, 0.11, 0.012), M["steel"], "cylinder", rot=("Y", -math.degrees(a + math.pi / 6), None))
    # exposed engine core chamber in the frame behind the cylinder: a window with a glowing core capsule and fins
    b.box("core_window", (0, -0.015, 0.075), (0.078, 0.09, 0.06), M["dark"], "frame")
    b.lathe("core", [(0.0, -0.05), (0.022, -0.045), (0.028, 0.0), (0.022, 0.045), (0.0, 0.05)], [M["thermal"]], "core",
            axis=(1, 0, 0), at=(0, -0.015, 0.075), segs=12)
    for k in range(4):
        b.box("fin", (0, -0.05 + k * 0.023, 0.03), (0.082, 0.006, 0.03), M["gold"], "frame")
    mlist = list(M.values())
    o, col, gl = b.finish("core_revolver", mlist, assets, {"cylinder": CY, "core": (0, -0.015, 0.075)},
                          disp(GUN_FP, 0.75, GUN_TP, 0.7, GUN_GUI, 1.05))
    K.preview(o, K.preview_material("cr_prev", col, gl, 4.0), f"{out}/reliquary_core_revolver.png", (0.9, -0.2, 0.45), (0, 0.25, 0.05), lens=45,
              bg=(0.012, 0.011, 0.012))


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--no-render", action="store_true")
    K.ARGS = ap.parse_args(argv)
    os.makedirs(K.ARGS.out, exist_ok=True)
    assets = os.path.join(os.path.dirname(os.path.abspath(K.ARGS.assets)), "cosmic_reliquary")
    out = os.path.abspath(K.ARGS.out)
    build_star_eater(assets, out)
    build_void_carver(assets, out)
    build_revolver(assets, out)
    print("[reliquary] done")


if __name__ == "__main__":
    main()
