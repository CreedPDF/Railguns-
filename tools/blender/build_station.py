"""
Space Command station modules, modelled and baked in headless Blender (run by tools/blender/build.sh).

  hub        Command Hub, a 3 x 3 x 3 block pressurised module: an armoured drum with a ring of cupola windows, four
             docking ports (hatch, collar, guide pins, lit rim), radiator fins, handrails, a truss mast with a slowly
             turning comms dish (part "dish") and a red beacon. Parts: body, dish.
  solar      Solar Array: a gimballed mast on a one-block pedestal carrying two roll-out solar blankets (~11 blocks tip
             to tip). Parts: base (fixed), mast (turns to the sun), wing_l / wing_r (tilt; scaled along X to roll out),
             roll_l / roll_r (the blanket drums, carried out to the wing tips).
  turret     Station Railgun: a point-defence turret, pedestal (base), turning housing (yaw) and twin-rail barrel with
             capacitor coils (barrel, elevates). The coils glow with charge.
  satellite  Strike Satellite: a docking cradle on a pylon (mount) holding the satellite: a gold-foil hexagonal bus
             (sat), two solar panels that fold against it (panel_l / panel_r), and a focusing emitter (emitter).

Axes as in build_krl.py: Blender x right, y forward (the model's front is -Y, south in game), z up; one block = 1.
"""
import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_krl as K  # noqa: E402
import build_ship as S  # noqa: E402

import bpy  # noqa: E402
from mathutils import Matrix, Vector  # noqa: E402

RED, HOT, AMBER, WARM = S.RED, S.HOT, S.AMBER, S.WARM


def gold_mat(name):
    """Crinkled gold multi-layer insulation foil."""
    m = K.Mat(name)
    cr = m.voronoi_edge(6.0)
    n = m.noise(14.0, 4, 0.7, 1.5)
    col = m.ramp(n, [(0.25, (0.32, 0.2, 0.04)), (0.55, (0.62, 0.42, 0.1)), (0.8, (0.85, 0.66, 0.26))])
    col = m.mix(col, (0.22, 0.13, 0.02), m.math("MULTIPLY", m.math("LESS_THAN", cr, 0.02), 0.7))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.2)), 0.5, True))
    m.color(col)
    return m


def cells_mat(name):
    """Solar cells: near-black charcoal with a bronze sheen, silver bus bars and a fine cell grid."""
    m = K.Mat(name)
    x, y = m.axis("X"), m.axis("Y")
    gx = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", x, 4.0)), 0.04)
    gy = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", y, 4.0)), 0.05)
    bus = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", x, 16.0)), 0.06)
    col = m.ramp(m.noise(1.5, 4, 0.5), [(0.3, (0.018, 0.014, 0.012)), (0.75, (0.07, 0.045, 0.025))])
    col = m.mix(col, (0.3, 0.29, 0.27), m.math("MULTIPLY", bus, 0.35))
    col = m.mix(col, (0.45, 0.44, 0.42), m.math("MAXIMUM", gx, gy))
    m.color(col)
    m.glow(m.ramp(m.math("MAXIMUM", gx, gy), [(0.0, (0, 0, 0)), (1.0, (0.08, 0.03, 0.01))]))
    return m


def white_mat(name):
    """Light thermal paint, as on station modules: off-white with soft grime and panel seams."""
    return K.mil_mat(name, (0.62, 0.61, 0.58), seams=1.6, wear=0.1, grime=0.18, scale=3.0)


class Builder:
    def __init__(self, parts):
        self.parts = parts
        self.objs = []

    def add(self, o, mats, part, smooth=False):
        self.objs.append(K.tag(o, part, mats, smooth=smooth))
        return o

    def bx(self, name, c, s, mats, part, bevel=0.0, rot=None):
        o = K.box(name, c, s, bevel=bevel)
        if rot:
            for ax, deg, at in rot if isinstance(rot, list) else [rot]:
                S.rot_about(o, ax, deg, at if at is not None else c)
        return self.add(o, mats, part)

    def rod(self, name, a, b, r, mats, part, segs=12, smooth=True):
        return self.add(K.rod(name, a, b, r, segs), mats, part, smooth)

    def lathe(self, name, prof, mats, part, axis=(0, 0, 1), at=(0, 0, 0), segs=40, mat_of_edge=None, cap=False, closed=False):
        o = K.lathe(name, prof, segs, mat_of_edge=mat_of_edge, cap=cap, closed=closed)
        if tuple(axis) != (0, 0, 1) or tuple(at) != (0, 0, 0):
            K.orient(o, axis, at)
        return self.add(o, mats, part, smooth=True)

    def finish(self, name, mats, size, glow_size, assets, pivots):
        K.stamp(self.objs, self.parts)
        o = K.join(self.objs, name)
        K.unwrap(o, 0.002)
        col = K.bake_one(o, mats, size, f"{assets}/textures/station/{name}.png")
        glow = K.bake_one(o, mats, glow_size, f"{assets}/textures/station/{name}_glow.png", "glow")
        K.export(o, self.parts, pivots, f"{assets}/models/station/{name}.json")
        return o, col, glow


def common_mats(prefix):
    return dict(
        hull=S.ship_hull_mat(prefix + "_hull", (0.085, 0.083, 0.08)),
        white=white_mat(prefix + "_white"),
        paint=K.paint_mat(prefix + "_paint", (0.42, 0.03, 0.022)),
        dark=K.metal_mat(prefix + "_dark", (0.03, 0.029, 0.028), 7.0),
        steel=K.metal_mat(prefix + "_steel", (0.3, 0.295, 0.285), 9.0),
        chrome=K.metal_mat(prefix + "_chrome", (0.62, 0.6, 0.58), 14.0),
        glass=S.glass_mat(prefix + "_glass"),
        haz=K.hazard_mat(prefix + "_haz", 6.0),
        gold=gold_mat(prefix + "_gold"),
        cells=cells_mat(prefix + "_cells"),
        lred=K.glow_mat(prefix + "_lred", RED, 1.0),
        lamb=K.glow_mat(prefix + "_lamb", AMBER, 1.0),
        lwarm=K.glow_mat(prefix + "_lwarm", WARM, 0.9),
        lhot=K.glow_mat(prefix + "_lhot", HOT, 1.0, pulse_scale=4.0),
        lbl=K.flat_mat(prefix + "_lbl", (0.8, 0.78, 0.74)),
    )


def ring_y(name, b, at, r0, r1, depth, mats, part, segs=40):
    """A flat annulus (washer) facing along Y at `at`."""
    prof = [(r0, -depth / 2), (r1, -depth / 2), (r1, depth / 2), (r0, depth / 2)]
    return b.lathe(name, prof, mats, part, axis=(0, 1, 0), at=at, segs=segs, closed=True)


# ================================================================================================ command hub

def build_hub(assets, out):
    K.reset()
    M = common_mats("hb")
    b = Builder(["body", "dish"])
    P = "body"
    R = 1.38
    # pedestal ring and feet on the floor block
    b.lathe("foot", [(0.0, 0.0), (1.42, 0.0), (1.42, 0.12), (1.2, 0.2), (0.0, 0.2)], M["dark"], P, segs=48)
    for k in range(8):
        a = k * math.tau / 8 + math.tau / 16
        b.bx("foot_pad", (1.32 * math.cos(a), 1.32 * math.sin(a), 0.06), (0.22, 0.22, 0.12), M["haz"], P, rot=("Z", math.degrees(a), None))
    # the drum: armoured lower ring, white upper body, cupola dome
    prof = [(1.2, 0.2), (R, 0.3), (R, 1.05), (R + 0.02, 1.1), (R + 0.02, 1.2), (R, 1.25), (R, 2.15), (1.3, 2.3), (1.1, 2.48),
            (0.8, 2.58), (0.0, 2.62)]
    mats_e = [1, 0, 2, 2, 2, 3, 3, 3, 3, 3]
    b.lathe("drum", prof, [M["dark"], M["hull"], M["paint"], M["white"]], P, segs=64,
            mat_of_edge=lambda i: mats_e[min(i, len(mats_e) - 1)])
    # cupola windows: eight panes round the dome shoulder, with frames
    for k in range(8):
        a = k * math.tau / 8
        d = Vector((math.cos(a), math.sin(a), 0))
        c = d * 1.235 + Vector((0, 0, 2.33))
        o = K.box("pane", (0, 0, 0), (0.42, 0.02, 0.22))
        o.rotation_euler = (math.radians(-38), 0, a + math.pi / 2)
        o.location = c
        K.active(o)
        bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
        b.add(o, M["glass"], P)
        o = K.box("pane_glow", (0, 0, 0), (0.36, 0.012, 0.14))
        o.rotation_euler = (math.radians(-38), 0, a + math.pi / 2)
        o.location = c - d * 0.012
        K.active(o)
        bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
        b.add(o, M["lwarm"], P)
    # four docking ports
    for k in range(4):
        a = k * math.pi / 2
        d = (math.cos(a), math.sin(a), 0.0)
        at = (d[0] * R, d[1] * R, 1.65)
        port = [(0.0, 0.0), (0.52, 0.0), (0.52, 0.18), (0.56, 0.2), (0.56, 0.3), (0.5, 0.32), (0.42, 0.32), (0.42, 0.28), (0.0, 0.28)]
        pm = [0, 0, 1, 1, 1, 1, 2, 3]
        b.lathe(f"port{k}", port, [M["white"], M["steel"], M["dark"], M["dark"]], P, axis=d, at=at, segs=40,
                mat_of_edge=lambda i: pm[min(i, len(pm) - 1)])
        tip = Vector(at) + Vector(d) * 0.33
        o = K.lathe(f"rim{k}", [(0.46, -0.008), (0.5, -0.008), (0.5, 0.008), (0.46, 0.008)], 40, closed=True)
        b.add(K.orient(o, d, tip), M["lred"], P)
        for s in (-1, 1):
            side = Vector((-d[1], d[0], 0)) * 0.36 * s
            b.rod("pin", tip - Vector(d) * 0.02 + side, tip + Vector(d) * 0.1 + side, 0.03, M["chrome"], P, 8)
        b.bx("hatch_bar", tuple(tip - Vector(d) * 0.035), (0.6 if abs(d[0]) < 0.5 else 0.02, 0.6 if abs(d[1]) < 0.5 else 0.02, 0.06), M["haz"], P)
    # radiator fins between the ports, handrails, labels, lights
    for k in range(4):
        a = k * math.pi / 2 + math.pi / 4
        d = Vector((math.cos(a), math.sin(a), 0))
        for j in range(5):
            z = 0.55 + j * 0.12
            o = K.box("rad", (0, 0, 0), (0.38, 0.03, 0.06))
            o.rotation_euler = (0, 0, a)
            o.location = d * (R + 0.15) + Vector((0, 0, z))
            K.active(o)
            bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
            b.add(o, M["dark"], P)
        p0 = d * (R + 0.08) + Vector((0, 0, 1.35))
        p1 = d * (R + 0.08) + Vector((0, 0, 2.05))
        b.rod("rail", p0, p1, 0.025, M["paint"], P, 8)
        for z in (1.35, 2.05):
            b.rod("rail_mount", d * R + Vector((0, 0, z)), d * (R + 0.08) + Vector((0, 0, z)), 0.02, M["steel"], P, 6)
        b.bx("strip", tuple(d * (R + 0.012) + Vector((0, 0, 1.15))), (0.2, 0.02, 0.04), M["lamb"], P, rot=("Z", math.degrees(a) + 90, None))
    for k, a in enumerate((math.pi / 4 + math.pi, -math.pi / 4)):
        d = Vector((math.cos(a), math.sin(a), 0))
        loc = d * (R + 0.012) + Vector((0, 0, 0.72))
        b.add(K.label("hub_lbl", "SPACE COMMAND", 0.075, tuple(loc), (math.radians(90), 0, a + math.pi / 2), [M["lbl"]], P), M["lbl"], P)
    # truss mast, beacon and comms dish (part "dish" turns about its mast)
    for k in range(3):
        a = k * math.tau / 3
        b.rod("truss", (0.28 * math.cos(a), 0.28 * math.sin(a), 2.55), (0.06 * math.cos(a), 0.06 * math.sin(a), 3.35), 0.03, M["steel"], P, 8)
    b.rod("mast", (0, 0, 2.55), (0, 0, 3.55), 0.05, M["steel"], P, 10)
    b.add(K.cyl("beacon", 0.07, 3.55, 3.68, 14), M["lred"], P)
    dish = [(0.0, 0.0), (0.18, 0.01), (0.36, 0.05), (0.5, 0.11), (0.52, 0.13), (0.5, 0.135), (0.35, 0.07), (0.17, 0.025), (0.0, 0.015)]
    b.lathe("dish", dish, [M["white"]], "dish", axis=(0, -0.6, 0.8), at=(0.0, 0.0, 3.1), segs=40, cap=False)
    b.rod("feed", (0, 0, 3.1), (0, -0.21, 3.38), 0.015, M["steel"], "dish", 6)
    b.add(K.cyl("feed_tip", 0.035, -0.03, 0.03, 10), M["lamb"], "dish")
    b.objs[-1].location = (0, -0.21, 3.38)
    K.active(b.objs[-1])
    bpy.ops.object.transform_apply(location=True)
    b.rod("dish_arm", (0, 0, 3.1), (0, 0.06, 3.0), 0.03, M["steel"], "dish", 8)
    mats = list(M.values())
    o, col, glow = b.finish("hub", mats, 1024, 512, assets, {"dish": (0, 0, 3.1)})
    pm = K.preview_material("hb_prev", col, glow, 3.0)
    K.preview(o, pm, f"{out}/station_hub.png", (5.2, -6.0, 4.4), (0, 0, 1.6), lens=40, bg=(0.012, 0.011, 0.012))
    K.preview(o, pm, f"{assets}/textures/item/sc_hub.png", (5.5, -6.0, 4.6), (0, 0, 1.75), lens=46, res=64, samples=24, transparent=True)


# ================================================================================================ solar array

WING_ROOT, WING_LEN, WING_W = 0.55, 4.6, 1.35
MAST_TOP = 2.15


def build_solar(assets, out):
    K.reset()
    M = common_mats("sa")
    b = Builder(["base", "mast", "wing_l", "wing_r", "roll_l", "roll_r"])
    # pedestal on the block, slewing ring
    b.bx("plinth", (0, 0, 0.12), (0.96, 0.96, 0.24), M["hull"], "base", bevel=0.03)
    b.bx("plinth_haz", (0, 0, 0.245), (0.9, 0.9, 0.01), M["haz"], "base")
    b.lathe("slew", [(0.0, 0.25), (0.36, 0.25), (0.38, 0.3), (0.38, 0.42), (0.0, 0.42)], M["steel"], "base", segs=36)
    b.bx("plinth_lamp", (0, -0.485, 0.14), (0.3, 0.02, 0.05), M["lamb"], "base")
    # mast (turns about Z), gimbal head and boom along X
    b.lathe("mast", [(0.0, 0.42), (0.16, 0.42), (0.14, 0.6), (0.11, MAST_TOP - 0.12), (0.18, MAST_TOP - 0.08), (0.18, MAST_TOP + 0.08), (0.0, MAST_TOP + 0.12)],
            M["white"], "mast", segs=24)
    b.bx("mast_band", (0, 0, 0.8), (0.24, 0.24, 0.06), M["paint"], "mast")
    b.rod("boom", (-WING_ROOT, 0, MAST_TOP), (WING_ROOT, 0, MAST_TOP), 0.11, M["steel"], "mast", 20)
    b.bx("gimbal", (0, 0, MAST_TOP), (0.34, 0.3, 0.3), M["hull"], "mast", bevel=0.04)
    b.bx("gimbal_lamp", (0, -0.152, MAST_TOP + 0.06), (0.12, 0.01, 0.04), M["lred"], "mast")
    for sx, wing, roll in ((-1, "wing_l", "roll_l"), (1, "wing_r", "roll_r")):
        x0 = sx * WING_ROOT
        # root fitting (part of the wing: tilts with it)
        b.bx("root", (x0 + sx * 0.06, 0, MAST_TOP), (0.14, WING_W + 0.08, 0.12), M["steel"], wing)
        # blanket: cells on top, foil underneath, a red edge stripe; length 1 at rest - scaled out in game
        x1 = x0 + sx * WING_LEN
        cx = (x0 + x1) / 2
        b.bx("blanket", (cx, 0, MAST_TOP), (WING_LEN, WING_W, 0.02), M["cells"], wing)
        b.bx("blanket_back", (cx, 0, MAST_TOP - 0.013), (WING_LEN, WING_W, 0.006), M["gold"], wing)
        for sy in (-1, 1):
            b.bx("edge", (cx, sy * (WING_W / 2 + 0.01), MAST_TOP), (WING_LEN, 0.03, 0.03), M["paint"], wing)
        for k in range(1, 6):
            xx = x0 + sx * WING_LEN * k / 6
            b.bx("batten", (xx, 0, MAST_TOP + 0.013), (0.03, WING_W, 0.008), M["steel"], wing)
        # the blanket drum rides at the tip
        b.rod("drum", (x1, -WING_W / 2 - 0.04, MAST_TOP), (x1, WING_W / 2 + 0.04, MAST_TOP), 0.07, M["white"], roll, 16)
        for sy in (-1, 1):
            b.rod("drum_cap", (x1, sy * (WING_W / 2 + 0.04), MAST_TOP), (x1, sy * (WING_W / 2 + 0.07), MAST_TOP), 0.085, M["steel"], roll, 16)
        b.bx("tip_lamp", (x1, -WING_W / 2 - 0.075, MAST_TOP), (0.06, 0.01, 0.06), M["lred"], roll)
    mats = list(M.values())
    pivots = {"mast": (0, 0, 0), "wing_l": (-WING_ROOT, 0, MAST_TOP), "wing_r": (WING_ROOT, 0, MAST_TOP),
              "roll_l": (-WING_ROOT, 0, MAST_TOP), "roll_r": (WING_ROOT, 0, MAST_TOP)}
    o, col, glow = b.finish("solar", mats, 1024, 512, assets, pivots)
    pm = K.preview_material("sa_prev", col, glow, 3.0)
    K.preview(o, pm, f"{out}/station_solar.png", (5.0, -8.5, 5.5), (0, 0, 1.6), lens=30, bg=(0.012, 0.011, 0.012))
    K.preview(o, pm, f"{assets}/textures/item/sc_solar.png", (6.5, -8.0, 7.5), (0, 0, 1.4), lens=30, res=64, samples=24, transparent=True)


# ================================================================================================ station railgun

YAW_Z, PITCH_Z = 0.42, 1.05


def build_turret(assets, out):
    K.reset()
    M = common_mats("tr")
    b = Builder(["base", "yaw", "barrel"])
    b.bx("plinth", (0, 0, 0.14), (0.98, 0.98, 0.28), M["hull"], "base", bevel=0.04)
    b.bx("plinth_haz", (0, 0, 0.285), (0.92, 0.92, 0.01), M["haz"], "base")
    b.lathe("race", [(0.0, 0.28), (0.44, 0.28), (0.46, 0.32), (0.46, YAW_Z), (0.0, YAW_Z)], M["steel"], "base", segs=40)
    for k in range(4):
        a = k * math.pi / 2 + math.pi / 4
        b.bx("bolt", (0.42 * math.cos(a), 0.42 * math.sin(a), 0.29), (0.06, 0.06, 0.03), M["chrome"], "base")
    # yaw housing: an armoured wedge with trunnion cheeks and a sensor head
    b.lathe("turntable", [(0.0, YAW_Z), (0.44, YAW_Z), (0.44, YAW_Z + 0.08), (0.38, YAW_Z + 0.12), (0.0, YAW_Z + 0.12)], M["dark"], "yaw", segs=40)
    for sx in (-1, 1):
        cheek = S.prism("cheek", [(0.36, YAW_Z + 0.1), (-0.42, YAW_Z + 0.1), (-0.3, PITCH_Z + 0.3), (0.12, PITCH_Z + 0.32)], "x",
                        sx * 0.28 - 0.06, sx * 0.28 + 0.06, rim_mat=1)
        b.add(cheek, [M["hull"], M["steel"]], "yaw")
        b.bx("cheek_stripe", (sx * 0.345, -0.05, PITCH_Z - 0.05), (0.01, 0.42, 0.06), M["paint"], "yaw")
    b.bx("back", (0, 0.24, YAW_Z + 0.35), (0.5, 0.36, 0.5), M["hull"], "yaw", bevel=0.04)
    b.bx("vent", (0, 0.425, YAW_Z + 0.35), (0.38, 0.01, 0.34), M["dark"], "yaw")
    b.bx("sensor", (0.0, 0.2, PITCH_Z + 0.3), (0.22, 0.22, 0.16), M["dark"], "yaw", bevel=0.02)
    b.add(K.orient(K.cyl("sensor_lens", 0.06, -0.01, 0.01, 16), (0, -1, 0), (0, 0.088, PITCH_Z + 0.3)), M["lred"], "yaw")
    # barrel: trunnion, breech, twin rails with capacitor coils, muzzle brake (built along -Y, elevates about X)
    b.rod("trunnion", (-0.36, 0, PITCH_Z), (0.36, 0, PITCH_Z), 0.09, M["steel"], "barrel", 16)
    b.bx("breech", (0, 0.05, PITCH_Z), (0.38, 0.5, 0.34), M["hull"], "barrel", bevel=0.04)
    for sx in (-1, 1):
        b.bx("rail", (sx * 0.1, -1.05, PITCH_Z), (0.08, 2.1, 0.16), M["steel"], "barrel", bevel=0.01)
        b.bx("rail_face", (sx * 0.058, -1.1, PITCH_Z), (0.006, 1.9, 0.1), M["lhot"], "barrel")
    for k in range(5):
        y = -0.35 - k * 0.32
        ring = [(0.2, -0.05), (0.24, -0.05), (0.24, 0.05), (0.2, 0.05)]
        b.lathe(f"coil{k}", ring, M["dark"], "barrel", axis=(0, 1, 0), at=(0, y, PITCH_Z), segs=28, closed=True)
        b.lathe(f"coilg{k}", [(0.241, -0.02), (0.245, -0.02), (0.245, 0.02), (0.241, 0.02)], M["lhot"], "barrel", axis=(0, 1, 0),
                at=(0, y, PITCH_Z), segs=28, closed=True)
    b.bx("spine", (0, -1.0, PITCH_Z + 0.13), (0.06, 1.9, 0.05), M["dark"], "barrel")
    b.bx("muzzle", (0, -2.15, PITCH_Z), (0.36, 0.14, 0.3), M["dark"], "barrel", bevel=0.02)
    b.bx("muzzle_glow", (0, -2.221, PITCH_Z), (0.16, 0.004, 0.12), M["lhot"], "barrel")
    mats = list(M.values())
    o, col, glow = b.finish("turret", mats, 1024, 512, assets, {"yaw": (0, 0, 0), "barrel": (0, 0, PITCH_Z)})
    pm = K.preview_material("tr_prev", col, glow, 3.0)
    K.preview(o, pm, f"{out}/station_railgun.png", (2.6, -3.2, 2.2), (0, -0.6, 0.9), lens=38, bg=(0.012, 0.011, 0.012))
    K.preview(o, pm, f"{assets}/textures/item/sc_railgun.png", (2.8, -3.0, 2.4), (0, -0.55, 0.8), lens=40, res=64, samples=24, transparent=True)


# ================================================================================================ strike satellite

SAT_Z = 1.75


def build_satellite(assets, out):
    K.reset()
    M = common_mats("st")
    b = Builder(["mount", "sat", "panel_l", "panel_r", "emitter"])
    # mount: pylon from the block and a cradle with clamp arms
    b.bx("plinth", (0, 0, 0.12), (0.96, 0.96, 0.24), M["hull"], "mount", bevel=0.03)
    b.bx("plinth_haz", (0, 0, 0.245), (0.9, 0.9, 0.01), M["haz"], "mount")
    b.lathe("pylon", [(0.0, 0.24), (0.26, 0.24), (0.18, 0.5), (0.14, 1.0), (0.24, 1.1), (0.0, 1.12)], M["white"], "mount", segs=28)
    for k in range(3):
        a = k * math.tau / 3 + math.pi / 2
        p0 = Vector((0.12 * math.cos(a), 0.12 * math.sin(a), 1.08))
        p1 = Vector((0.52 * math.cos(a), 0.52 * math.sin(a), 1.32))
        b.rod("clamp", p0, p1, 0.035, M["steel"], "mount", 8)
        b.bx("clamp_pad", tuple(p1 + Vector((0, 0, 0.04))), (0.12, 0.12, 0.05), M["haz"], "mount")
    b.bx("mount_lamp", (0, -0.485, 0.14), (0.3, 0.02, 0.05), M["lred"], "mount")
    # the satellite bus: a hexagonal prism in gold foil with white end decks
    hexp = [(0.55 * math.cos(k * math.tau / 6 + math.pi / 6), 0.55 * math.sin(k * math.tau / 6 + math.pi / 6)) for k in range(6)]
    o = S.prism("bus", hexp, "z", SAT_Z - 0.4, SAT_Z + 0.4, rim_mat=0, face_mat=1)
    b.add(o, [M["gold"], M["white"]], "sat")
    for z in (SAT_Z - 0.42, SAT_Z + 0.42):
        b.add(S.prism("deck", [(x * 1.04, y * 1.04) for x, y in hexp], "z", z - 0.02, z + 0.02, rim_mat=1, face_mat=0), [M["white"], M["steel"]], "sat")
    for k in range(6):
        a = k * math.tau / 6
        d = Vector((math.cos(a), math.sin(a), 0))
        b.bx("bus_rib", tuple(d * 0.49 + Vector((0, 0, SAT_Z))), (0.04, 0.04, 0.8), M["steel"], "sat", rot=("Z", math.degrees(a), None))
    # dish, star tracker, thruster quads, lights, label
    b.lathe("sdish", [(0.0, 0.0), (0.12, 0.01), (0.24, 0.04), (0.3, 0.07), (0.28, 0.075), (0.12, 0.02), (0.0, 0.012)], [M["white"]], "sat",
            axis=(0, 0.5, 0.85), at=(0.0, 0.15, SAT_Z + 0.48), segs=32)
    b.rod("sdish_mast", (0, 0.1, SAT_Z + 0.42), (0, 0.15, SAT_Z + 0.48), 0.02, M["steel"], "sat", 6)
    b.bx("tracker", (0.3, -0.2, SAT_Z + 0.5), (0.1, 0.1, 0.12), M["dark"], "sat")
    b.add(K.orient(K.cyl("tracker_lens", 0.035, -0.005, 0.005, 12), (0, 0, 1), (0.3, -0.2, SAT_Z + 0.563)), M["lamb"], "sat")
    for k in range(6):
        a = k * math.tau / 6 + math.pi / 6
        d = Vector((math.cos(a), math.sin(a), 0))
        for z in (SAT_Z - 0.3, SAT_Z + 0.3):
            b.bx("rcs", tuple(d * 0.6 + Vector((0, 0, z))), (0.06, 0.06, 0.06), M["dark"], "sat")
    b.bx("sat_lamp", (0, 0, SAT_Z + 0.445), (0.08, 0.08, 0.01), M["lred"], "sat")
    # solar panels on hinges at the +-X faces (fold up against the bus: tilt about Y at their hinge)
    for sx, part in ((-1, "panel_l"), (1, "panel_r")):
        hx = sx * 0.58
        b.rod("hinge", (hx, -0.12, SAT_Z), (hx, 0.12, SAT_Z), 0.03, M["steel"], part, 10)
        b.rod("yoke", (hx, 0, SAT_Z), (hx + sx * 0.3, 0, SAT_Z), 0.025, M["steel"], part, 8)
        for k in range(2):
            x0 = hx + sx * (0.3 + k * 0.72)
            b.bx("cells", (x0 + sx * 0.35, 0, SAT_Z), (0.68, 0.62, 0.02), M["cells"], part)
            b.bx("frame", (x0 + sx * 0.35, 0, SAT_Z - 0.012), (0.7, 0.64, 0.006), M["gold"], part)
        b.bx("ptip", (hx + sx * 1.74, 0, SAT_Z), (0.02, 0.2, 0.03), M["lred"], part)
    # the emitter: a focusing horn under the bus with lens rings and a crystal core
    em = [(0.3, 0.0), (0.36, -0.08), (0.42, -0.25), (0.46, -0.3), (0.4, -0.31), (0.24, -0.18), (0.14, -0.1)]
    b.lathe("horn", em, [M["dark"], M["steel"]], "emitter", at=(0, 0, SAT_Z - 0.42), segs=36,
            mat_of_edge=lambda i: 1 if i in (2, 3) else 0)
    for k, (r, z) in enumerate(((0.33, -0.12), (0.39, -0.2))):
        b.lathe(f"lensring{k}", [(r, -0.012), (r + 0.02, -0.012), (r + 0.02, 0.012), (r, 0.012)], M["lhot"], "emitter",
                at=(0, 0, SAT_Z - 0.42 + z), segs=36, closed=True)
    core = [(0.0, -0.05), (0.08, -0.1), (0.12, -0.18), (0.0, -0.28)]
    b.lathe("core", core, M["lhot"], "emitter", at=(0, 0, SAT_Z - 0.42), segs=8)
    mats = list(M.values())
    pivots = {"sat": (0, 0, SAT_Z), "panel_l": (-0.58, 0, SAT_Z), "panel_r": (0.58, 0, SAT_Z), "emitter": (0, 0, SAT_Z - 0.42)}
    o, col, glow = b.finish("satellite", mats, 1024, 512, assets, pivots)
    pm = K.preview_material("st_prev", col, glow, 3.0)
    K.preview(o, pm, f"{out}/station_satellite.png", (3.2, -4.0, 3.0), (0, 0, 1.4), lens=40, bg=(0.012, 0.011, 0.012))
    K.preview(o, pm, f"{assets}/textures/item/sc_satellite.png", (3.4, -4.0, 3.4), (0, 0, 1.3), lens=42, res=64, samples=24, transparent=True)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--no-render", action="store_true")
    K.ARGS = ap.parse_args(argv)
    os.makedirs(K.ARGS.out, exist_ok=True)
    assets, out = os.path.abspath(K.ARGS.assets), os.path.abspath(K.ARGS.out)
    build_hub(assets, out)
    build_solar(assets, out)
    build_turret(assets, out)
    build_satellite(assets, out)
    print("[station] done")


if __name__ == "__main__":
    main()
