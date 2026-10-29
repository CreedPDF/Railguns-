"""
HANGEKI // Reversal Edge, modelled and baked in headless Blender (run by tools/blender/build.sh, ONLY=hangeki).

  hangeki   orbital_railgun:models/hangeki/hangeki - a long, slightly curved sci-fi katana. Blender +Z is the blade
            axis (Minecraft +Y), the flat of the blade faces Blender +-Y (Minecraft +-Z, like an item sprite), the
            cutting edge is on +X. The grip's centre sits at the origin.

            parts  "body"  the blade (gunmetal flats, polished bevels, a dark spine), the reactor fuller (an amber
                           channel down each flat), the habaki collar, the hex tsuba, the wrapped grip and the pommel
                   "edge"  the cutting edge: a hairline of red-hot plasma from the collar to the tip (the game makes
                           it flare white during a parry)
                   "ring"  the parry ring: a halo of gold and red beads floating over the tsuba (spins in the game)

Colours (v4.19.1 theme): black lacquered steel, polished silver bevels with a white temper line, a white-hot edge,
crimson reactor glow, white silk wrap on black; silver fittings (no blue, no gold).

  hangeki_ward  orbital_railgun:models/hangeki/hangeki_ward - the small samurai ward that appears in front of the blade
            during a parry: a curved sode-style shield of six rows of black lacquered lamellae laced with crimson cord,
            a silver crown plate, and a glowing three-tomoe crest (part "mon", turns) in a silver ring ("halo").
            Faces Blender -Y (Minecraft +Z). Exported as krl_mesh
(see client/krl/KrlMesh.java).
"""
import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_krl as K  # noqa: E402
import build_ship as S  # noqa: E402

import bpy  # noqa: E402

RED, HOT, AMBER = S.RED, S.HOT, S.AMBER
WARM = S.WARM

# blade geometry
Z0, Z1 = 0.08, 1.66          # collar top to the tip (a long nodachi blade)
W0, W1 = 0.08, 0.058         # width at the collar and just before the kissaki
T0, T1 = 0.032, 0.02         # spine thickness
SORI = 0.11                  # how far the curve sweeps back (-X) at the tip


def curve(z):
    u = (z - Z0) / (Z1 - Z0)
    return -SORI * u * u


def gold_mat(name):
    m = K.Mat(name)
    n = m.noise(7.0, 5, 0.55)
    col = m.ramp(n, [(0.3, (0.4, 0.25, 0.05)), (0.7, (0.78, 0.55, 0.17))])
    col = m.mix(col, (0.12, 0.07, 0.02), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.06)), 0.6, True))
    m.color(col)
    return m


def wrap_mat(name):
    """Black cord wrapped in a diamond lattice, the gaps between the turns lit faint ember."""
    m = K.Mat(name)
    z = m.axis("Z")
    x = m.axis("X")
    a = m.math("FRACT", m.math("MULTIPLY", m.math("ADD", z, m.math("MULTIPLY", x, 0.6)), 26.0))
    b = m.math("FRACT", m.math("MULTIPLY", m.math("SUBTRACT", z, m.math("MULTIPLY", x, 0.6)), 26.0))
    seam = m.math("MAXIMUM", m.math("LESS_THAN", a, 0.12), m.math("LESS_THAN", b, 0.12))
    col = m.ramp(m.noise(30.0, 3, 0.5), [(0.3, (0.62, 0.6, 0.57)), (0.7, (0.86, 0.84, 0.8))])
    col = m.mix(col, (0.008, 0.007, 0.007), seam)
    m.color(col)
    m.glow(m.ramp(seam, [(0.0, (0, 0, 0)), (1.0, (0.5, 0.02, 0.04))]))
    return m


def blade_mat(name, base, scale, hamon=False):
    """Machined gunmetal; the polished bevel gets a wavy lighter temper line (hamon) near the edge."""
    m = K.Mat(name)
    n1 = m.noise(scale, 6, 0.55)
    col = m.ramp(n1, [(0.3, tuple(c * 0.78 for c in base)), (0.7, base)])
    if hamon:
        z = m.axis("Z")
        x = m.axis("X")
        wave = m.math("ADD", m.math("MULTIPLY", m.math("SINE", m.math("MULTIPLY", z, 55.0)), 0.006), 0.012)
        band = m.math("LESS_THAN", m.math("ABSOLUTE", m.math("SUBTRACT", x, wave)), 0.006)
        col = m.mix(col, tuple(min(1, c * 1.9) for c in base), m.math("MULTIPLY", band, 0.7))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.05)), 0.4, True))
    m.color(col)
    return m


CRIMSON = (0.9, 0.03, 0.06)
WHITE_HOT = (1.0, 0.94, 0.9)


def silver_mat(name):
    m = K.Mat(name)
    n = m.noise(14.0, 5, 0.55)
    col = m.ramp(n, [(0.3, (0.48, 0.47, 0.46)), (0.7, (0.78, 0.77, 0.75))])
    col = m.mix(col, (0.05, 0.05, 0.05), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.06)), 0.6, True))
    m.color(col)
    return m


def lacquer_mat(name):
    """Black lacquer: deep black with a faint warm sheen."""
    m = K.Mat(name)
    n = m.noise(9.0, 4, 0.5)
    col = m.ramp(n, [(0.35, (0.012, 0.01, 0.01)), (0.75, (0.055, 0.045, 0.045))])
    col = m.mix(col, (0.0, 0.0, 0.0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.05)), 0.5, True))
    m.color(col)
    return m


def mats():
    return dict(
        flat=blade_mat("hk_flat", (0.045, 0.043, 0.043), 18.0),
        bevel=blade_mat("hk_bevel", (0.62, 0.61, 0.6), 30.0, hamon=True),
        spine=K.metal_mat("hk_spine", (0.02, 0.019, 0.019), 9.0),
        gold=silver_mat("hk_silver"),
        obs=lacquer_mat("hk_lacquer"),
        wrap=wrap_mat("hk_wrap"),
        red=K.glow_mat("hk_red", CRIMSON, 1.0),
        hot=K.glow_mat("hk_hot", WHITE_HOT, 1.0, pulse_scale=9.0),
        amber=K.glow_mat("hk_amber", CRIMSON, 1.0, pulse_scale=5.0),
        warm=K.glow_mat("hk_warm", CRIMSON, 1.0),
    )


class Build:
    def __init__(self, parts):
        self.parts, self.objs = parts, []

    def add(self, o, m, part, smooth=False):
        self.objs.append(K.tag(o, part, m, smooth=smooth))
        return o

    def lathe(self, name, prof, m, part, segs=32, closed=False):
        o = K.lathe(name, prof, segs, closed=closed)
        return self.add(o, m, part, smooth=True)


def section(z, scale=1.0):
    """The blade's cross-section at height z: spine (back, -X) to edge (+X), symmetric about the flat (Y)."""
    u = (z - Z0) / (Z1 - Z0)
    w = (W0 + (W1 - W0) * u) * scale
    t = (T0 + (T1 - T0) * u) * scale
    c = curve(z)
    return [(c - w / 2, t / 2), (c + w * 0.12, t * 0.44), (c + w / 2, 0.0), (c + w * 0.12, -t * 0.44), (c - w / 2, -t / 2)]


def blade_sections():
    secs = []
    n = 26
    zk = Z1 - 0.12                     # where the kissaki (the angled tip) begins
    for i in range(n + 1):
        z = Z0 + (zk - Z0) * i / n
        secs.append((z, section(z)))
    # the kissaki: the edge sweeps up to meet the spine at the point
    for k, f in enumerate((0.25, 0.5, 0.72, 0.88, 0.97)):
        z = zk + (Z1 - zk) * f
        s = section(z)
        c = curve(z)
        shrink = 1 - f
        pts = []
        for (x, y) in s:
            # pull every point toward the spine side and in from the flats
            xs = c - (W1 / 2) + (x - (c - W1 / 2)) * shrink
            pts.append((xs, y * (0.4 + 0.6 * shrink)))
        secs.append((z, pts))
    tip = (curve(Z1) - W1 * 0.42, 0.0)
    secs.append((Z1, [tip] * 5))
    return secs


def build(assets, out):
    K.reset()
    M = mats()
    b = Build(["body", "edge", "ring"])

    # ---- the blade: faces 0/3 the flats, 1/2 the polished bevels, 4 the spine
    def blade_face(s, i, z):
        return {0: 0, 1: 1, 2: 1, 3: 0, 4: 2}[i]

    blade = K.loft("blade", blade_sections(), closed_caps=True, mat_of_face=blade_face)
    K.fix_normals(blade)
    b.add(blade, [M["flat"], M["bevel"], M["spine"]], "body")

    # ---- the edge: a hairline of plasma riding the cutting edge, collar to tip
    secs = []
    for (z, pts) in blade_sections()[:-1]:
        ex, ey = pts[2]
        secs.append((z, [(ex - 0.004, 0.0032), (ex + 0.0045, 0.0), (ex - 0.004, -0.0032)]))
    tz, tp = blade_sections()[-1]
    secs.append((tz + 0.004, [(tp[0][0] - 0.002, 0.0), (tp[0][0], 0.0), (tp[0][0] - 0.002, 0.0)]))
    edge = K.loft("edge", secs, closed_caps=True)
    K.fix_normals(edge)
    b.add(edge, [M["hot"]], "edge")

    # ---- the reactor fuller: an amber channel down each flat, with three hot vents near the collar
    for sy in (-1, 1):
        fsecs = []
        for i in range(19):
            z = 0.13 + (1.3 - 0.13) * i / 18
            c = curve(z)
            u = (z - Z0) / (Z1 - Z0)
            t = (T0 + (T1 - T0) * u)
            y = sy * (t * 0.47 + 0.0012)
            fsecs.append((z, [(c - 0.018, y), (c - 0.004, y), (c - 0.004, y + sy * 0.0016), (c - 0.018, y + sy * 0.0016)]))
        ful = K.loft("fuller", fsecs, closed_caps=True)
        K.fix_normals(ful)
        b.add(ful, [M["amber"]], "body")
        for k in range(3):
            z = 0.16 + k * 0.05
            b.add(K.box("vent", (curve(z) + 0.012, sy * (T0 * 0.47 + 0.001), z), (0.012, 0.0024, 0.022)), [M["red"]], "body")

    # ---- habaki collar
    b.lathe("habaki", [(0.0, 0.025), (0.046, 0.025), (0.05, 0.035), (0.046, 0.085), (0.0, 0.09)], [M["gold"]], "body", segs=6)
    # ---- tsuba: a hexagonal guard with a gold rim and six ember slots
    b.lathe("tsuba", [(0.0, -0.005), (0.12, -0.005), (0.13, 0.005), (0.13, 0.02), (0.12, 0.03), (0.0, 0.03)], [M["obs"]], "body", segs=6)
    b.lathe("tsuba_rim", [(0.128, 0.0), (0.142, 0.004), (0.142, 0.022), (0.128, 0.026)], [M["gold"]], "body", segs=6, closed=True)
    for k in range(6):
        a = k * math.tau / 6 + math.tau / 12
        b.add(K.box("slot", (0.085 * math.cos(a), 0.085 * math.sin(a), 0.031), (0.03, 0.009, 0.004), rot_z=a), [M["red"]], "body")
    # ---- grip: a wrapped tsuka with gold menuki and a hot core window
    b.lathe("tsuka", [(0.0, -0.4), (0.034, -0.4), (0.038, -0.24), (0.036, -0.06), (0.04, -0.005), (0.0, -0.005)], [M["wrap"]], "body", segs=10)
    for sz in (-0.14, -0.27):
        b.add(K.box("menuki", (0.0, 0.037, sz), (0.022, 0.008, 0.03)), [M["gold"]], "body")
        b.add(K.box("menuki", (0.0, -0.037, sz), (0.022, 0.008, 0.03)), [M["gold"]], "body")
    b.lathe("fuchi", [(0.0, -0.03), (0.044, -0.03), (0.046, -0.005), (0.0, -0.005)], [M["gold"]], "body", segs=10)
    # ---- pommel: a gold kashira and a red focus crystal
    b.lathe("kashira", [(0.0, -0.45), (0.03, -0.45), (0.044, -0.425), (0.041, -0.4), (0.0, -0.4)], [M["gold"]], "body", segs=10)
    b.lathe("crystal", [(0.0, -0.53), (0.018, -0.5), (0.022, -0.475), (0.012, -0.45), (0.0, -0.448)], [M["warm"]], "body", segs=6)

    # ---- the parry ring: gold hoop with red beads floating over the tsuba
    o = K.lathe("hoop", [(0.17 + 0.006 * math.cos(a), 0.06 + 0.006 * math.sin(a)) for a in (k * math.tau / 8 for k in range(8))], 40, closed=True)
    b.add(o, [M["gold"]], "ring", smooth=True)
    for k in range(8):
        a = k * math.tau / 8
        b.add(K.box("bead", (0.17 * math.cos(a), 0.17 * math.sin(a), 0.06), (0.018, 0.018, 0.018), rot_z=a), [M["hot"] if k % 2 else M["amber"]], "ring")

    K.stamp(b.objs, b.parts)
    o = K.join(b.objs, "hangeki")
    K.unwrap(o, 0.003)
    mlist = list(M.values())
    col = K.bake_one(o, mlist, 512, f"{assets}/textures/hangeki/hangeki.png")
    gl = K.bake_one(o, mlist, 256, f"{assets}/textures/hangeki/hangeki_glow.png", "glow")
    K.export(o, b.parts, {"ring": (0, 0, 0.06)}, f"{assets}/models/hangeki/hangeki.json")
    pm = K.preview_material("hk_prev", col, gl, 5.0)
    K.preview(o, pm, f"{out}/hangeki.png", (2.0, -2.7, 1.1), (0, 0, 0.55), lens=50, bg=(0.012, 0.011, 0.012))


def tomoe(name, a0, size=0.075, n=14):
    """One comma of the three-tomoe crest, as an outline in the XZ plane (extruded along Y): a round head with a
    tail that sweeps round the centre and tapers to a point."""
    pts = []
    hc = (0.085 * math.cos(a0), 0.085 * math.sin(a0))
    # head: the outer three-quarters of a circle
    for i in range(n + 1):
        t = a0 - math.pi * 0.5 + i * (math.pi * 1.5) / n
        pts.append((hc[0] + size * math.cos(t), hc[1] + size * math.sin(t)))
    # tail: sweeping back round the centre, tapering
    m = 10
    for i in range(1, m + 1):
        f = i / m
        ang = a0 + math.pi + f * math.pi * 0.85
        r = 0.085 + size * (1 - f) * 0.9 + 0.02 * f
        pts.append((r * math.cos(ang - math.pi) * (1 - 0.15 * f), r * math.sin(ang - math.pi) * (1 - 0.15 * f)))
    for i in range(m, 0, -1):
        f = i / m
        ang = a0 + math.pi + f * math.pi * 0.85
        r = 0.085 - size * (1 - f) * 0.6 + 0.02 * f
        pts.append((max(0.01, r) * math.cos(ang - math.pi), max(0.01, r) * math.sin(ang - math.pi)))
    return K.extrude_outline(name, pts, 0.03, rim_mat=1, face_mat=0)


def build_ward(assets, out):
    """The parry ward: a small curved sode shield (Blender XZ plane, curving back toward +Y), facing -Y."""
    K.reset()
    M = mats()
    b = Build(["plate", "mon", "halo"])
    W, H, rows = 0.78, 0.86, 6
    bend = 0.16
    # six rows of lamellae, each row overlapping the one below and tilted out at its lower edge
    for r in range(rows):
        z1 = H / 2 - r * (H / rows)
        z0 = z1 - H / rows - 0.03
        secs = []
        cols = 14
        for c in range(cols + 1):
            x = -W / 2 + W * c / cols
            y = bend * (2 * x / W) ** 2
            secs.append((x, y))
        # build the strip as a loft along X: sections are (x, [(y, z)...]) - use a custom bmesh via prism per segment
        for c in range(cols):
            xa, ya = secs[c]
            xb, yb = secs[c + 1]
            seg = K.loft("lam", [(0, [(xa, ya - 0.012), (xb, yb - 0.012), (xb, yb + 0.012), (xa, ya + 0.012)]),
                                 (1, [(xa, ya - 0.012), (xb, yb - 0.012), (xb, yb + 0.012), (xa, ya + 0.012)])], closed_caps=True)
            # loft runs along Blender Z from 0 to 1: squash it to this row's height, tilt the lower edge outward
            for v in seg.data.vertices:
                zf = v.co.z
                v.co.z = z0 + (z1 - z0) * zf
                v.co.y -= 0.035 * (1 - zf)
            K.fix_normals(seg)
            b.add(seg, [M["obs"]], "plate")
        # the row's lower edge: a thin silver lip
        for c in range(cols):
            xa, ya = secs[c]
            xb, yb = secs[c + 1]
            b.add(K.beam("lip", (xa, ya - 0.05, z0 + 0.004), (xb, yb - 0.05, z0 + 0.004), 0.008, 0.012), [M["gold"]], "plate")
    # crimson lacing: vertical cords down the face, crossing each row
    for c in range(1, 8):
        x = -W / 2 + W * c / 8
        y = bend * (2 * x / W) ** 2 - 0.05
        b.add(K.box("cord", (x, y - 0.006, 0.0), (0.014, 0.008, H * 0.96)), [M["red"]], "plate")
        for r in range(rows):
            z = H / 2 - (r + 0.5) * (H / rows)
            b.add(K.box("knot", (x, y - 0.012, z), (0.03, 0.01, 0.02)), [M["red"]], "plate")
    # the kanmuri-ita: a silver crown plate along the top with flared ends
    b.add(K.beam("crown", (-W / 2 - 0.05, 0.09, H / 2 + 0.03), (W / 2 + 0.05, 0.09, H / 2 + 0.03), 0.04, 0.07), [M["gold"]], "plate")
    for sx in (-1, 1):
        b.add(K.box("flare", (sx * (W / 2 + 0.07), 0.12, H / 2 + 0.07), (0.05, 0.03, 0.08)), [M["gold"]], "plate")
    # crest: a silver ring and three crimson-white tomoe in front of the face
    yc = -0.11
    halo = K.lathe("halo", [(0.2 + 0.014 * math.cos(a), 0.014 * math.sin(a)) for a in (k * math.tau / 8 for k in range(8))], 40, closed=True)
    halo.rotation_euler = (math.radians(90), 0, 0)
    halo.location = (0, yc, 0)
    K.active(halo)
    bpy.ops.object.transform_apply(location=True, rotation=True)
    b.add(halo, [M["gold"]], "halo", smooth=True)
    for k in range(12):
        a = k * math.tau / 12
        b.add(K.box("tick", (0.235 * math.cos(a), yc, 0.235 * math.sin(a)), (0.02, 0.01, 0.02)), [M["red"]], "halo")
    disc = K.lathe("disc", [(0.0, -0.004), (0.185, -0.004), (0.185, 0.004), (0.0, 0.004)], 40)
    disc.rotation_euler = (math.radians(90), 0, 0)
    disc.location = (0, yc + 0.02, 0)
    K.active(disc)
    bpy.ops.object.transform_apply(location=True, rotation=True)
    b.add(disc, [M["obs"]], "mon")
    for k in range(3):
        t = tomoe("tomoe", k * math.tau / 3)
        t.location = (0, yc - 0.012, 0)
        K.active(t)
        bpy.ops.object.transform_apply(location=True)
        K.fix_normals(t)
        b.add(t, [M["red"], M["hot"]], "mon")
    K.stamp(b.objs, b.parts)
    o = K.join(b.objs, "hangeki_ward")
    K.unwrap(o, 0.003)
    mlist = list(M.values())
    col = K.bake_one(o, mlist, 512, f"{assets}/textures/hangeki/hangeki_ward.png")
    gl = K.bake_one(o, mlist, 256, f"{assets}/textures/hangeki/hangeki_ward_glow.png", "glow")
    K.export(o, b.parts, {"mon": (0, yc, 0), "halo": (0, yc, 0)}, f"{assets}/models/hangeki/hangeki_ward.json")
    pm = K.preview_material("hw_prev", col, gl, 5.0)
    K.preview(o, pm, f"{out}/hangeki_ward.png", (0.7, -1.9, 0.35), (0, 0, 0), lens=50, bg=(0.012, 0.011, 0.012))


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--no-render", action="store_true")
    K.ARGS = ap.parse_args(argv)
    os.makedirs(K.ARGS.out, exist_ok=True)
    build(os.path.abspath(K.ARGS.assets), os.path.abspath(K.ARGS.out))
    build_ward(os.path.abspath(K.ARGS.assets), os.path.abspath(K.ARGS.out))
    print("[hangeki] done")


if __name__ == "__main__":
    main()
