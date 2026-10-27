"""
The ARES Kestrel starship, modelled and textured in headless Blender (run by tools/blender/build.sh).

A smooth, realistic gunship built from lofted cross-sections instead of cubes: a superellipse fuselage that runs
into a long drooping nose under a smoked bubble canopy, a dorsal engine boom carrying twin main engines and canted
tail fins, short swept wings with VTOL engine pods at their tips (they tilt nozzle-down to hover), canards, a chin
sensor, tripod landing gear and a rear hatch that drops into a ramp. Inside: a walk-in troop cabin (deck plating,
ribbed walls, fold-down seats, lockers, ceiling lights) and, through a window in the front bulkhead, a two-seat
cockpit under the canopy. Gunmetal armour, red trim and red / amber / warm-white lights. No blue anywhere.

Exported as "krl_mesh" (see client/krl/KrlMesh.java) with these parts, in blocks, Minecraft axes:
  hull     everything fixed to the airframe
  main     the two main-engine nozzles (their glow follows thrust)
  glass    canopy and the cabin window (drawn translucent)
  eng_l / eng_r      the VTOL pods, about their tilt shafts
  gear_f / gear_l / gear_r   the landing gear legs, about their knuckles
  ramp     the rear hatch, about its hinge

Blender axes here: x = right, y = forward (the nose), z = up, ground at z = 0. The game maps them to model space
exactly like ShipEntity.modelToWorld (px / 16, front = -Z in Minecraft). Layout constraints from the game:
  cabin (walk-in, matches the collision blocks): x [-2.5, 2.5], y [-5.5, 5.5], floor top z 2, ceiling z 5;
  rear door x [-1.5, 1.5], z [2, 4]; ramp hinge (0, -5.5, 2); gear knuckles (0, 6, 0.875) and (+-2.75, -3.75, 0.875);
  VTOL pivots (+-6.25, -1.25, 3.125); VTOL nozzles end at y -4; main nozzles at (+-1.25, -10.875, 6.9375).
"""
import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_krl as K  # noqa: E402  (shared modelling / baking / export toolkit)

import bpy  # noqa: E402
import bmesh  # noqa: E402
from mathutils import Matrix, Vector  # noqa: E402

RED = (1.0, 0.07, 0.03)
HOT = (1.0, 0.32, 0.12)
AMBER = (1.0, 0.55, 0.08)
WARM = (1.0, 0.8, 0.55)

PIV_RAMP = (0.0, -5.5, 2.0)
PIV_GEAR = {"gear_f": (0.0, 6.0, 0.875), "gear_l": (-2.75, -3.75, 0.875), "gear_r": (2.75, -3.75, 0.875)}
PIV_ENG = {"eng_l": (-6.25, -1.25, 3.125), "eng_r": (6.25, -1.25, 3.125)}
NAC_FRONT, NAC_BACK, NAC_R = 2.3, -4.0, 1.15
MAIN_X, MAIN_Z, MAIN_EXIT = 1.25, 6.9375, -10.875
PARTS = ["hull", "main", "glass", "eng_l", "eng_r", "gear_f", "gear_l", "gear_r", "ramp"]


# ================================================================================================ shape maths

def hermite(table, y, col):
    """Smooth (monotone-ish cubic) interpolation of column `col` of a station table sorted by y."""
    ys = [r[0] for r in table]
    if y <= ys[0]:
        return table[0][col]
    if y >= ys[-1]:
        return table[-1][col]
    k = max(i for i in range(len(ys) - 1) if ys[i] <= y)
    y0, y1 = ys[k], ys[k + 1]
    p0, p1 = table[k][col], table[k + 1][col]

    def slope(i):
        if i == 0 or i == len(ys) - 1:
            return 0.0
        a = (table[i][col] - table[i - 1][col]) / (ys[i] - ys[i - 1])
        b = (table[i + 1][col] - table[i][col]) / (ys[i + 1] - ys[i])
        return 0.0 if a * b <= 0 else 2 * a * b / (a + b)

    h = y1 - y0
    t = (y - y0) / h
    m0, m1 = slope(k) * h, slope(k + 1) * h
    t2, t3 = t * t, t * t * t
    return (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * p1 + (t3 - t2) * m1


# fuselage stations: y, half-width, belly z, roof z, top exponent, bottom exponent
FUS = [
    (-5.62, 3.56, 0.95, 6.15, 3.2, 5.0),
    (-4.6, 3.65, 0.82, 6.25, 3.2, 5.0),
    (4.0, 3.65, 0.80, 6.25, 3.2, 5.0),
    (6.5, 3.62, 0.82, 6.2, 3.1, 4.8),
    (8.0, 3.45, 0.9, 5.85, 2.9, 4.4),
    (9.5, 3.0, 1.08, 5.1, 2.7, 4.0),
    (11.0, 2.2, 1.4, 4.0, 2.5, 3.5),
    (12.3, 1.25, 1.78, 3.05, 2.3, 3.0),
    (13.2, 0.5, 2.05, 2.55, 2.2, 2.6),
    (13.55, 0.12, 2.22, 2.38, 2.1, 2.3),
]
TUMBLE = 0.1
N_RING = 44


def fus_params(y):
    return [hermite(FUS, y, c) for c in range(1, 6)]


def se_point(hw, zb, zt, nt, nb, a, tumble=TUMBLE):
    c, s = math.cos(a), math.sin(a)
    n = nt if s > 0 else nb
    x = hw * math.copysign(abs(c) ** (2 / n), c)
    zc, hz = (zb + zt) / 2, (zt - zb) / 2
    z = zc + hz * math.copysign(abs(s) ** (2 / n), s)
    if s > 0:
        x *= 1 - tumble * s * s
    return x, z


def ring_angle(k, n=N_RING):
    return -math.pi / 2 + math.tau * k / n


def fus_point(y, a):
    hw, zb, zt, nt, nb = fus_params(y)
    x, z = se_point(hw, zb, zt, nt, nb, a)
    return Vector((x, y, z))


def fus_normal(y, a):
    e = 1e-3
    p = fus_point(y, a)
    da = fus_point(y, a + e) - fus_point(y, a - e)
    dy = fus_point(y + e, a) - fus_point(y - e, a)
    n = da.cross(dy).normalized()
    hw, zb, zt, nt, nb = fus_params(y)
    if n.dot(p - Vector((0, y, (zb + zt) / 2))) < 0:
        n = -n
    return n


def fus_half_width_at(y, z):
    """Half-width of the fuselage at height z (0 outside it)."""
    hw, zb, zt, nt, nb = fus_params(y)
    zc, hz = (zb + zt) / 2, (zt - zb) / 2
    if z <= zb or z >= zt:
        return 0.0
    u = (z - zc) / hz
    n = nt if u > 0 else nb
    s = abs(u) ** (n / 2)
    c = math.sqrt(max(0.0, 1 - s * s))
    x = hw * c ** (2 / n)
    if u > 0:
        x *= 1 - TUMBLE * s * s
    return x


# ================================================================================================ mesh builders

def skin(name, rings, mat_fn=None, keep=None, cap_first=False, cap_last=False, closed=True):
    """Quad skin through rings of equal point count. mat_fn(s, i) -> material index; keep(s, i) -> build this face."""
    bm = bmesh.new()
    vs = [[bm.verts.new(tuple(p)) for p in r] for r in rings]
    n = len(rings[0])
    span = n if closed else n - 1
    for s in range(len(rings) - 1):
        for i in range(span):
            if keep is not None and not keep(s, i):
                continue
            j = (i + 1) % n
            q = [vs[s][i], vs[s][j], vs[s + 1][j], vs[s + 1][i]]
            try:
                f = bm.faces.new(q)
            except ValueError:
                continue
            if mat_fn:
                f.material_index = mat_fn(s, i)
    for flag, ring in ((cap_first, vs[0]), (cap_last, vs[-1])):
        if flag:
            try:
                bm.faces.new(ring)
            except ValueError:
                pass
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    bm.normal_update()
    return K.link_obj(name, bm)


def prism(name, pts, axis, a0, a1, rim_mat=1, face_mat=0):
    """A flat outline extruded along `axis` ('x', 'y' or 'z') from a0 to a1. pts are 2D in the other two axes, in
    order (y, z) for x, (x, z) for y, (x, y) for z."""
    bm = bmesh.new()

    def v3(p, a):
        u, v = p
        return {"x": (a, u, v), "y": (u, a, v), "z": (u, v, a)}[axis]

    lo = [bm.verts.new(v3(p, a0)) for p in pts]
    hi = [bm.verts.new(v3(p, a1)) for p in pts]
    f = bm.faces.new(lo)
    f.material_index = face_mat
    f = bm.faces.new(list(reversed(hi)))
    f.material_index = face_mat
    n = len(pts)
    for i in range(n):
        j = (i + 1) % n
        f = bm.faces.new((lo[i], lo[j], hi[j], hi[i]))
        f.material_index = rim_mat
    bm.normal_update()
    return K.link_obj(name, bm)


def transform(o, mat):
    o.matrix_world = mat @ o.matrix_world
    K.active(o)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return o


def rot_about(o, axis, deg, origin):
    c = Vector(origin)
    return transform(o, Matrix.Translation(c) @ Matrix.Rotation(math.radians(deg), 4, axis) @ Matrix.Translation(-c))


def airfoil(x, y_le, y_te, zc, thick, n=14):
    """Closed airfoil ring at span station x: upper surface trailing edge -> leading edge, then lower back."""
    def half(xi):
        b = 0.2969 * math.sqrt(xi) - 0.126 * xi - 0.3516 * xi ** 2 + 0.2843 * xi ** 3 - 0.1036 * xi ** 4
        return thick / 2 * b / 0.1
    xis = [(1 - math.cos(math.pi * k / n)) / 2 for k in range(n + 1)]   # 0 .. 1, clustered at both ends
    pts = []
    for xi in reversed(xis):                                             # upper: te -> le
        pts.append(Vector((x, y_le - xi * (y_le - y_te), zc + half(xi))))
    for xi in xis[1:-1]:                                                 # lower: le -> te (te shared)
        pts.append(Vector((x, y_le - xi * (y_le - y_te), zc - half(xi))))
    return pts


def ribbon(name, path, normals, w, h):
    """A small rectangular bar running along a path of surface points, standing h off the surface."""
    rings = []
    for k, (p, n) in enumerate(zip(path, normals)):
        t = (path[min(k + 1, len(path) - 1)] - path[max(k - 1, 0)]).normalized()
        side = t.cross(n).normalized() * (w / 2)
        base = p - n * 0.02
        top = p + n * h
        rings.append([base - side, base + side, top + side, top - side])
    return skin(name, rings, cap_first=True, cap_last=True)


# ================================================================================================ materials

def ship_hull_mat(name, base=(0.085, 0.083, 0.08), decals=False):
    """Gunmetal armour: tri-planar staggered panel seams, mottling, worn edges, crevice grime, exhaust streaks.
    decals adds the red flank stripe with a lit pin-line and the lit cabin portholes (positions in blocks)."""
    m = K.Mat(name)
    sep = m.n("ShaderNodeSeparateXYZ")
    m.link(m.coord.outputs["Object"], sep.inputs[0])
    X, Y, Z = sep.outputs[0], sep.outputs[1], sep.outputs[2]
    geo = m.n("ShaderNodeNewGeometry")
    ns = m.n("ShaderNodeSeparateXYZ")
    m.link(geo.outputs["Normal"], ns.inputs[0])
    ax, ay, az = (m.math("ABSOLUTE", ns.outputs[i]) for i in range(3))

    def lines(u, v, pu, pv, w):
        row = m.math("FLOOR", m.math("DIVIDE", v, pv))
        lu = m.math("LESS_THAN", m.math("FRACT", m.math("ADD", m.math("DIVIDE", u, pu), m.math("MULTIPLY", row, 0.5))), w / pu)
        lv = m.math("LESS_THAN", m.math("FRACT", m.math("DIVIDE", v, pv)), w / pv)
        return m.math("MAXIMUM", lu, lv)

    seam = m.math("ADD", m.math("MULTIPLY", ax, lines(Y, Z, 1.7, 0.9, 0.025)),
                  m.math("MULTIPLY", ay, lines(X, Z, 1.2, 0.9, 0.025)))
    seam = m.math("ADD", seam, m.math("MULTIPLY", az, lines(Y, X, 1.7, 1.1, 0.025)), clamp=True)
    col = m.ramp(m.noise(0.7, 5, 0.55), [(0.3, tuple(c * 0.82 for c in base)), (0.7, tuple(c * 1.18 for c in base))])
    col = m.mix(col, m.ramp(m.noise(6.0, 3, 0.5), [(0.4, (0, 0, 0)), (0.8, (0.025, 0.024, 0.022))]), 0.6, "ADD")
    col = m.mix(col, tuple(c * 0.22 for c in base), m.math("MULTIPLY", seam, 0.85))
    # exhaust / rain streaks running down the flanks
    streak = K._noise_at(m, K._stretch(m, 5.0, 1.2, 0.18), 2.5, 3, 0.5)
    col = m.mix(col, tuple(c * 0.55 for c in base), m.math("MULTIPLY", m.math("GREATER_THAN", streak, 0.6), 0.35))
    glow = None
    if decals:
        def between(v, a, b):
            return m.math("MULTIPLY", m.math("GREATER_THAN", v, a), m.math("LESS_THAN", v, b))
        flank = m.math("MULTIPLY", between(m.math("ABSOLUTE", X), 2.4, 3.8), between(Y, -5.5, 9.2))
        stripe = m.math("MULTIPLY", flank, between(Z, 4.42, 4.62))
        pin = m.math("MULTIPLY", flank, between(Z, 4.66, 4.7))
        col = m.mix(col, (0.42, 0.03, 0.022), stripe)
        col = m.mix(col, (1.0, 0.2, 0.1), pin)
        port = m.math("MULTIPLY", between(m.math("ABSOLUTE", X), 3.2, 3.8), between(Z, 3.72, 4.12))
        port = m.math("MULTIPLY", port, between(Y, -3.6, 1.6))
        port = m.math("MULTIPLY", port, m.math("LESS_THAN", m.math("FRACT", m.math("DIVIDE", m.math("ADD", Y, 3.6), 1.3)), 0.42))
        col = m.mix(col, (0.9, 0.5, 0.15), port)
        g = m.mix((0, 0, 0), (0.9, 0.08, 0.03), pin)
        glow = m.mix(g, (0.85, 0.42, 0.1), port)
    edge = m.math("POWER", m.math("SUBTRACT", 1.0, m.ao(0.12)), 2.0)
    col = m.mix(col, (0.24, 0.23, 0.22), m.math("MULTIPLY", edge, 0.45, True))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(1.2)), 0.55, True))
    m.color(col)
    if glow is not None:
        m.glow(glow)
    return m


def glass_mat(name):
    m = K.Mat(name)
    n1 = m.noise(0.35, 4, 0.5)
    col = m.ramp(n1, [(0.3, (0.03, 0.026, 0.024)), (0.75, (0.1, 0.085, 0.075))])
    sheen = m.math("GREATER_THAN", m.math("FRACT", m.math("MULTIPLY", m.math("ADD", m.axis("Y"), m.axis("Z")), 0.6)), 0.93)
    col = m.mix(col, (0.3, 0.25, 0.22), m.math("MULTIPLY", sheen, 0.4))
    m.color(col)
    return m


def fabric_mat(name, base=(0.06, 0.055, 0.05)):
    m = K.Mat(name)
    weave = m.math("MULTIPLY", m.math("SINE", m.math("MULTIPLY", m.axis("X"), 300.0)), m.math("SINE", m.math("MULTIPLY", m.axis("Z"), 300.0)))
    col = m.ramp(m.noise(4.0, 4, 0.5), [(0.3, tuple(c * 0.8 for c in base)), (0.7, base)])
    col = m.mix(col, tuple(c * 1.5 for c in base), m.math("MULTIPLY", m.math("GREATER_THAN", weave, 0.3), 0.4))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.15)), 0.5, True))
    m.color(col)
    return m


# ================================================================================================ the build

def build(assets, out):
    K.reset()
    hull = ship_hull_mat("sh_hull", decals=True)
    hull2 = ship_hull_mat("sh_hull2", (0.07, 0.068, 0.066))
    paint = K.paint_mat("sh_paint", (0.42, 0.03, 0.022))
    dark = K.metal_mat("sh_dark", (0.03, 0.029, 0.028), 7.0)
    steel = K.metal_mat("sh_steel", (0.3, 0.295, 0.285), 9.0)
    chrome = K.metal_mat("sh_chrome", (0.62, 0.6, 0.58), 14.0)
    heat = K.metal_mat("sh_heat", (0.2, 0.14, 0.08), 8.0, rings=0.3)
    glass = glass_mat("sh_glass")
    inner = K.mil_mat("sh_inner", (0.23, 0.225, 0.21), seams=2.5, wear=0.12, grime=0.18, scale=3.0)
    innerd = K.mil_mat("sh_innerd", (0.09, 0.088, 0.085), wear=0.15, grime=0.2, scale=4.0)
    tread = K.tread_mat("sh_tread")
    fabric = fabric_mat("sh_fabric")
    haz = K.hazard_mat("sh_haz", 5.0)
    screen = K.mil_screen_mat("sh_screen", AMBER)
    rubber = K.flat_mat("sh_rubber", (0.02, 0.02, 0.02))
    white = K.flat_mat("sh_white", (0.78, 0.76, 0.72))
    l_red = K.glow_mat("sh_lred", RED, 1.0)
    l_hot = K.glow_mat("sh_lhot", HOT, 1.0, pulse_scale=3.0)
    l_amb = K.glow_mat("sh_lamb", AMBER, 1.0)
    l_warm = K.glow_mat("sh_lwarm", WARM, 0.9)
    MATS = [hull, hull2, paint, dark, steel, chrome, heat, glass, inner, innerd, tread, fabric, haz, screen, rubber,
            white, l_red, l_hot, l_amb, l_warm]
    objs = []

    def add(o, mats, part="hull", smooth=False):
        objs.append(K.tag(o, part, mats, smooth=smooth))
        return o

    def bx(name, center, size, mats, part="hull", bevel=0.0, rot=None):
        o = K.box(name, center, size, bevel=bevel)
        if rot:
            rot_about(o, rot[0], rot[1], center)
        return add(o, mats, part)

    def mirror_x(fn):
        for sx in (-1, 1):
            fn(sx)

    # ------------------------------------------------------------------------------------------ fuselage + canopy
    ys = [-5.62, -5.3] + [round(-4.8 + 0.6 * k, 3) for k in range(19)]
    y = ys[-1]
    while y < 13.55 - 1e-6:
        y = min(13.55, y + 0.3)
        ys.append(round(y, 3))
    rings = [[fus_point(yy, ring_angle(k)) for k in range(N_RING)] for yy in ys]

    def canopy_u(k):
        """Angle of ring point k from the top, in degrees."""
        return abs(math.degrees(ring_angle(k + 0.5)) - 90.0)

    def is_glass(s, i):
        u = canopy_u(i)
        if u > 64:
            return False
        ym = (ys[s] + ys[s + 1]) / 2
        r = (u / 64) ** 2
        return 6.75 + 0.55 * r < ym < 10.85 - 1.7 * r

    add(skin("fuselage", rings, keep=lambda s, i: not is_glass(s, i), cap_last=True), hull, smooth=True)
    add(skin("canopy", rings, keep=is_glass), glass, "glass", smooth=True)

    # canopy frame: an outline and two arches, plus a spine bar
    def surface_path(pairs):
        pts = [fus_point(yy, aa) + fus_normal(yy, aa) * 0.005 for yy, aa in pairs]
        nrm = [fus_normal(yy, aa) for yy, aa in pairs]
        return pts, nrm

    top = math.pi / 2
    for side in (-1, 1):
        a_edge = top + side * math.radians(64)
        y0, y1 = 6.75 + 0.55, 10.85 - 1.7
        pts, nrm = surface_path([(y0 + (y1 - y0) * t / 12, a_edge) for t in range(13)])
        add(ribbon(f"cf_side{side}", pts, nrm, 0.09, 0.06), steel)
    for name, f in (("front", lambda r: 10.85 - 1.7 * r), ("back", lambda r: 6.75 + 0.55 * r), ("arch1", lambda r: 8.15 + 0.1 * r),
                    ("arch2", lambda r: 9.45 - 0.5 * r)):
        pairs = []
        for t in range(25):
            u = -64 + 128 * t / 24
            pairs.append((f((u / 64) ** 2), top + math.radians(u)))
        pts, nrm = surface_path(pairs)
        add(ribbon(f"cf_{name}", pts, nrm, 0.1 if name in ("front", "back") else 0.07, 0.06), steel)
    pts, nrm = surface_path([(6.9 + (10.75 - 6.9) * t / 12, top) for t in range(13)])
    add(ribbon("cf_spine", pts, nrm, 0.07, 0.05), steel)

    # bulkheads: the rear one (with the door) and the front one (with the window into the cockpit)
    def bulkhead(name, yb, thick, hole, mats):
        pts = [fus_point(yb, ring_angle(k)) for k in range(N_RING)]
        o = K.extrude_outline(name, [(p.x * 0.995, p.z) for p in reversed(pts)], thick, rim_mat=0, face_mat=0, y=yb)
        cut = K.box(name + "_cut", hole[0], hole[1])
        b = o.modifiers.new("hole", "BOOLEAN")
        b.operation = "DIFFERENCE"
        b.object = cut
        b.solver = "EXACT"
        K.apply_mods(o)
        bpy.data.objects.remove(cut, do_unlink=True)
        return add(o, mats)

    bulkhead("bh_rear", -5.56, 0.12, ((0, -5.56, 3.0), (3.0, 1.0, 2.0)), hull)
    bulkhead("bh_front", 5.55, 0.1, ((0, 5.55, 3.9), (3.1, 1.0, 1.7)), inner)
    bx("bh_window", (0, 5.55, 3.9), (3.12, 0.02, 1.72), glass, "glass")
    for x, z, w, h in ((0, 3.02, 3.3, 0.08), (0, 4.78, 3.3, 0.08), (-1.6, 3.9, 0.1, 1.84), (1.6, 3.9, 0.1, 1.84), ):
        bx("bh_wframe", (x, 5.49, z), (w, 0.08, h), steel)
    # door frame, outside and in
    for yy in (-5.64, -5.48):
        for x in (-1.58, 1.58):
            bx("door_post", (x, yy, 3.0), (0.14, 0.05, 2.0), haz)
        bx("door_lintel", (0, yy, 4.06), (3.3, 0.05, 0.12), haz)
    bx("door_lamp", (0, -5.67, 4.2), (0.5, 0.03, 0.06), l_red)

    # ------------------------------------------------------------------------------------------ cabin interior
    bx("deck", (0, 0, 1.95), (5.1, 11.0, 0.1), tread)
    bx("deck_door", (0, -5.3, 2.003), (3.0, 0.4, 0.006), haz)
    mirror_x(lambda sx: bx("deck_light", (sx * 2.42, 0, 2.006), (0.05, 10.6, 0.012), l_red))
    mirror_x(lambda sx: bx("wall", (sx * 2.55, 0, 3.5), (0.1, 11.0, 3.0), inner))
    bx("ceiling", (0, 0, 5.05), (5.1, 11.0, 0.1), inner)
    ribs = [-4.4, -2.9, -1.4, 0.1, 1.6, 3.1, 4.4]
    for k, yy in enumerate(ribs):
        mirror_x(lambda sx: bx("rib", (sx * 2.46, yy, 3.5), (0.08, 0.14, 3.0), innerd))
        bx("rib_top", (0, yy, 4.96), (5.0, 0.14, 0.08), innerd)
        if k < len(ribs) - 1:
            bx("ceil_light", (0, (yy + ribs[k + 1]) / 2, 4.985), (1.3, 0.8, 0.03), l_warm)
    mirror_x(lambda sx: bx("cove", (sx * 2.44, 0, 4.86), (0.03, 10.8, 0.06), l_amb))
    mirror_x(lambda sx: add(K.rod("grab", (sx * 0.8, -5.0, 4.8), (sx * 0.8, 4.4, 4.8), 0.03, 8), chrome))
    for yy in (-4.4, -1.4, 1.6, 4.4):
        mirror_x(lambda sx: add(K.rod("grab_post", (sx * 0.8, yy, 4.8), (sx * 0.8, yy, 4.96), 0.02, 6), steel))
    # fold-down jump seats along both walls
    for yy in (-3.65, -2.15, -0.65, 0.85):
        for sx in (-1, 1):
            bx("seat_pan", (sx * 2.18, yy, 2.48), (0.56, 0.52, 0.08), fabric, bevel=0.02)
            bx("seat_back", (sx * 2.42, yy, 3.08), (0.1, 0.5, 0.98), fabric, bevel=0.02)
            bx("seat_head", (sx * 2.42, yy, 3.74), (0.12, 0.32, 0.26), fabric, bevel=0.02)
            for dy in (-0.13, 0.13):
                bx("harness", (sx * 2.36, yy + dy, 3.1), (0.02, 0.06, 0.92), paint)
            bx("buckle", (sx * 2.35, yy, 2.85), (0.03, 0.14, 0.08), chrome)
            add(K.rod("seat_leg", (sx * 1.95, yy, 2.44), (sx * 2.45, yy, 2.05), 0.025, 6), steel)
    # lockers either side of the door, extinguisher, wall screens, a strapped cargo crate
    for sx in (-1, 1):
        bx("locker", (sx * 2.07, -5.05, 3.35), (0.85, 0.9, 2.7), innerd, bevel=0.02)
        bx("locker_door", (sx * 2.07, -4.595, 3.35), (0.78, 0.01, 2.55), inner)
        bx("locker_split", (sx * 2.07, -4.59, 3.35), (0.02, 0.01, 2.55), innerd)
        bx("locker_hdl", (sx * 1.86, -4.58, 3.3), (0.04, 0.03, 0.35), chrome)
        bx("locker_lamp", (sx * 2.3, -4.585, 4.5), (0.12, 0.01, 0.05), l_amb)
        bx("locker_band", (sx * 2.07, -4.588, 4.1), (0.78, 0.008, 0.08), haz)
        bx("scr_box", (sx * 2.47, 2.65, 3.7), (0.05, 0.95, 0.6), innerd)
        bx("scr", (sx * 2.443, 2.65, 3.7), (0.01, 0.82, 0.48), screen)
        bx("scr_btn", (sx * 2.445, 2.65, 3.36), (0.01, 0.6, 0.05), l_amb)
        bx("fb_panel", (sx * 2.06, 5.46, 3.6), (0.7, 0.06, 1.3), innerd)
        bx("fb_scr", (sx * 2.06, 5.425, 3.85), (0.56, 0.01, 0.55), screen)
        for k in range(4):
            bx("fb_btn", (sx * 2.06 - 0.22 + k * 0.15, 5.425, 3.25), (0.08, 0.01, 0.06), l_red if k == 3 else l_amb)
    add(K.cyl("extinguisher", 0.1, 2.05, 2.6, 12, 2.36, -4.2), paint, smooth=True)
    bx("ext_bracket", (2.44, -4.2, 2.4), (0.04, 0.12, 0.3), steel)
    bx("crate", (1.95, 3.9, 2.35), (0.95, 1.1, 0.7), innerd, bevel=0.03)
    bx("crate_band", (1.95, 3.9, 2.35), (0.97, 1.12, 0.1), haz)
    bx("crate_strap", (1.95, 3.9, 2.35), (0.97, 0.06, 0.72), paint)
    add(K.label("crate_lbl", "EVA", 0.18, (1.95, 3.34, 2.45), (math.radians(90), 0, 0), [white], "hull"), white)

    # ------------------------------------------------------------------------------------------ cockpit (under the canopy)
    fz = 2.4
    floor = []
    for k in range(13):
        yy = 5.6 + (10.4 - 5.6) * k / 12
        floor.append((max(0.2, fus_half_width_at(yy, fz) - 0.05), yy))
    outline = [(x, yy) for x, yy in floor] + [(-x, yy) for x, yy in reversed(floor)]
    add(prism("ck_floor", outline, "z", fz - 0.08, fz), [innerd, innerd])
    for sx in (-1, 1):
        x = sx * 0.78
        bx("ck_pedestal", (x, 7.6, 2.6), (0.3, 0.4, 0.4), steel)
        bx("ck_pan", (x, 7.65, 2.86), (0.62, 0.6, 0.12), fabric, bevel=0.03)
        bx("ck_back", (x, 7.28, 3.45), (0.62, 0.12, 1.1), fabric, bevel=0.03, rot=("X", 12))
        bx("ck_head", (x, 7.12, 4.12), (0.4, 0.12, 0.3), fabric, bevel=0.03, rot=("X", 12))
        for dx in (-0.36, 0.36):
            bx("ck_arm", (x + dx, 7.6, 3.08), (0.08, 0.5, 0.06), innerd)
        for dx in (-0.12, 0.12):
            bx("ck_harness", (x + dx, 7.355, 3.45), (0.06, 0.02, 1.0), paint, rot=("X", 12))
        add(K.rod("ck_stick", (x, 8.45, fz), (x, 8.5, 3.0), 0.03, 8), steel)
        bx("ck_grip", (x, 8.5, 3.08), (0.07, 0.07, 0.16), rubber, bevel=0.015)
        bx("ck_trig", (x, 8.54, 3.12), (0.03, 0.02, 0.03), l_red)
        xs = sx * 2.0
        bx("ck_side", (xs, 7.8, 2.8), (0.6, 2.2, 0.8), innerd, bevel=0.02)
        for k in range(6):
            bx("ck_side_btn", (xs - sx * 0.1, 7.1 + k * 0.28, 3.205), (0.18, 0.1, 0.01), l_amb if k % 2 else l_red)
    bx("ck_center", (0, 8.0, 2.75), (0.45, 1.4, 0.7), innerd, bevel=0.02)
    for k in range(5):
        bx("ck_c_btn", (0, 7.5 + k * 0.24, 3.105), (0.3, 0.12, 0.01), l_amb if k != 2 else l_red)
    bx("ck_throttle", (0, 8.25, 3.2), (0.08, 0.2, 0.18), chrome)
    dash_c = (0, 9.55, 3.25)
    bx("ck_dash", dash_c, (3.4, 1.0, 0.14), innerd, bevel=0.02, rot=("X", 35))
    for k, x in enumerate((-1.05, 0.0, 1.05)):
        o = K.box("ck_screen", (x, 9.55, 3.33), (0.85, 0.62, 0.01))
        rot_about(o, "X", 35, dash_c)
        add(o, screen)
    for k in range(10):
        o = K.box("ck_d_btn", (-1.5 + k * 0.333, 9.12, 3.3), (0.12, 0.06, 0.01))
        rot_about(o, "X", 35, dash_c)
        add(o, l_red if k in (0, 9) else l_amb)
    bx("ck_glare", (0, 9.95, 3.75), (3.0, 0.5, 0.08), innerd, rot=("X", 12))
    bx("ck_hud", (0, 9.75, 4.05), (0.6, 0.02, 0.4), glass, "glass", rot=("X", 12))
    bx("ck_hud_frame", (0, 9.75, 3.82), (0.64, 0.08, 0.04), steel)

    # ------------------------------------------------------------------------------------------ dorsal engine boom
    BOOM = [
        (-10.05, 2.2, 5.7, 8.0, 3.2),
        (-9.7, 2.35, 5.55, 8.15, 3.4),
        (-8.5, 2.45, 5.45, 8.3, 3.6),
        (-5.0, 2.45, 5.45, 8.3, 3.6),
        (-3.0, 2.25, 5.5, 8.05, 3.4),
        (0.0, 1.7, 5.6, 7.35, 3.0),
        (2.5, 1.1, 5.7, 6.75, 2.6),
        (3.4, 0.55, 5.8, 6.35, 2.2),
    ]
    bys = [-10.05, -9.85, -9.7] + [round(-9.4 + 0.5 * k, 3) for k in range(25) if -9.4 + 0.5 * k < 3.4] + [3.4]
    brings = []
    for yy in bys:
        hw, zb, zt, n = (hermite(BOOM, yy, c) for c in range(1, 5))
        ring = []
        for k in range(36):
            x, z = se_point(hw, zb, zt, n, n, ring_angle(k, 36), 0.06)
            ring.append(Vector((x, yy, z)))
        brings.append(ring)
    add(skin("boom", brings, cap_first=True, cap_last=True), hull, smooth=True)
    # louvred heat exchangers on the boom flanks
    for sx in (-1, 1):
        for k in range(9):
            yy = -8.9 + k * 0.42
            bx("louvre", (sx * 2.47, yy, 6.9), (0.08, 0.06, 1.2), dark)
        bx("louvre_back", (sx * 2.44, -7.22, 6.9), (0.04, 3.6, 1.3), dark)
        bx("boom_stripe", (sx * 2.455, -3.8, 7.1), (0.02, 2.2, 0.1), paint)
    # twin main engines: bells, inner glow, collars
    for sx in (-1, 1):
        cx = sx * MAIN_X
        prof = [(0.5, -9.95), (0.62, -10.1), (0.74, -10.45), (0.82, MAIN_EXIT), (0.76, MAIN_EXIT), (0.6, -10.4), (0.42, -10.15)]
        o = K.lathe(f"bell{sx}", prof, 32, mat_of_edge=lambda i: 0 if i < 3 else 1)
        add(K.orient(o, (0, 1, 0), (cx, 0, MAIN_Z)), [heat, dark], "main", smooth=True)
        o = K.lathe(f"collar{sx}", [(0.66, -9.85), (0.7, -9.85), (0.7, -10.05), (0.66, -10.05)], 32, closed=True)
        add(K.orient(o, (0, 1, 0), (cx, 0, MAIN_Z)), steel, "main")
        o = K.cyl(f"core{sx}", 0.42, -0.02, 0.02, 24)
        add(K.orient(o, (0, 1, 0), (cx, -10.16, MAIN_Z)), l_hot, "main")
    bx("boom_aft_plate", (0, -10.065, 6.85), (3.9, 0.02, 1.9), dark)
    # canted tail fins
    for sx in (-1, 1):
        fin = prism("fin", [(-6.4, 7.9), (-9.9, 7.9), (-10.45, 10.55), (-8.85, 10.55)], "x", -0.07, 0.07, rim_mat=1, face_mat=0)
        transform(fin, Matrix.Translation((sx * 1.55, 0, 0)))
        rot_about(fin, "Y", sx * 20, (sx * 1.55, 0, 7.9))
        add(fin, [hull, steel])
        tip = K.box("fin_tip", (sx * 1.55, -9.65, 10.57), (0.16, 1.6, 0.06))
        rot_about(tip, "Y", sx * 20, (sx * 1.55, 0, 7.9))
        add(tip, l_red)
        band = K.box("fin_band", (sx * 1.55, -9.2, 9.4), (0.16, 2.1, 0.22))
        rot_about(band, "Y", sx * 20, (sx * 1.55, 0, 7.9))
        add(band, paint)
    # sensor mast and beacon on the spine
    add(K.rod("mast", (0, 2.0, 6.6), (0, 2.0, 7.7), 0.05, 10), steel)
    add(K.rod("mast_arm", (-0.35, 2.0, 7.45), (0.35, 2.0, 7.45), 0.025, 8), steel)
    add(K.cyl("beacon", 0.08, 7.7, 7.85, 12, 0, 2.0), l_red)
    add(K.rod("antenna", (0.6, -1.0, 7.2), (0.6, -2.6, 8.4), 0.02, 6), steel)

    # ------------------------------------------------------------------------------------------ wings, pylons, canards
    for sx in (-1, 1):
        secs = []
        for x, le, te, zc, t in ((3.3, 1.7, -4.5, 2.55, 0.6), (3.9, 1.25, -3.95, 2.75, 0.55), (4.5, 0.82, -3.35, 2.95, 0.48),
                                 (5.0, 0.45, -2.9, PIV_ENG["eng_r"][2], 0.44)):
            secs.append(airfoil(sx * x, le, te, zc, t))
        if sx < 0:
            secs = [list(reversed(s)) for s in secs]
        add(skin("wing", secs, cap_last=True), hull2, smooth=True)
        # tilt-shaft fairing at the tip
        o = K.rod("shaft_house", (sx * 4.5, -1.25, PIV_ENG["eng_r"][2]), (sx * 5.0, -1.25, PIV_ENG["eng_r"][2]), 0.5, 24)
        add(o, steel, smooth=True)
        bx("wing_light", (sx * 4.75, -2.88, PIV_ENG["eng_r"][2]), (0.3, 0.05, 0.06), l_red)
        # canards
        cz = 2.05
        can = prism("canard", [(1.9, 11.35), (1.9, 9.55), (3.35, 10.05), (3.42, 10.45)], "z", cz - 0.06, cz + 0.06, rim_mat=1)
        if sx < 0:
            transform(can, Matrix.Scale(-1, 4, (1, 0, 0)))
            K.fix_normals(can)
        add(can, [hull2, steel])
        bx("canard_light", (sx * 3.4, 10.25, cz), (0.06, 0.3, 0.1), l_red)
    # ventral strakes
    for sx in (-1, 1):
        st = prism("strake", [(-3.3, 1.0), (-5.5, 1.0), (-5.5, 0.55), (-4.6, 0.5)], "x", -0.05, 0.05, rim_mat=1)
        transform(st, Matrix.Translation((sx * 2.4, 0, 0)))
        rot_about(st, "Y", -sx * 15, (sx * 2.4, 0, 1.0))
        add(st, [hull2, steel])
    # chin sensor, landing lights, RCS blocks, gear bays, decals
    o = K.lathe("chin", [(0.0, -0.36), (0.25, -0.26), (0.36, 0.0), (0.33, 0.14), (0.2, 0.3)], 24, cap=True)
    add(K.orient(o, (0, 0, -1), (0, 11.0, 1.62)), steel, smooth=True)
    add(K.orient(K.cyl("chin_lens", 0.13, -0.01, 0.01, 16), (0, 1, 0), (0, 11.31, 1.38)), l_red)
    for sx in (-1, 1):
        add(K.orient(K.cyl("land_light", 0.11, -0.01, 0.01, 12), (0, 0, -1), (sx * 0.9, 10.3, 1.27)), l_warm)
        for yy, zz in ((11.6, 2.7), (-4.9, 5.6)):
            xx = fus_half_width_at(yy, zz)
            bx("rcs", (sx * (xx + 0.02), yy, zz), (0.1, 0.35, 0.22), dark)
            bx("rcs_port", (sx * (xx + 0.075), yy, zz), (0.01, 0.2, 0.1), l_hot)
        add(K.label("name", "ARES KESTREL", 0.42, (sx * (fus_half_width_at(2.9, 3.05) + 0.012), 2.9, 3.05),
                    (math.radians(90), 0, math.radians(90 if sx > 0 else -90)), [white], "hull"), white)
        add(K.label("hullno", "AK-07", 0.5, (sx * (fus_half_width_at(5.3, 3.0) + 0.012), 5.3, 3.0),
                    (math.radians(90), 0, math.radians(90 if sx > 0 else -90)), [paint], "hull"), paint)
    for name, (x, y_, _z) in PIV_GEAR.items():
        bx("bay", (x, y_ + (0.2 if name == "gear_f" else 0.0), 0.775), (1.0, 1.5, 0.04), dark)
    bx("nose_light", (0, 13.56, 2.3), (0.12, 0.04, 0.08), l_red)

    # ------------------------------------------------------------------------------------------ VTOL pods (tilting)
    for part, (px, py, pz) in PIV_ENG.items():
        sx = 1 if px > 0 else -1
        R = NAC_R
        prof = [(0.72, 1.9), (0.86, NAC_FRONT), (1.02, 2.28), (1.12, 1.95), (R, 1.3), (R, -1.8), (1.12, -2.8), (1.02, -3.4),
                (0.96, -3.55), (0.96, -3.62), (1.02, NAC_BACK), (0.86, NAC_BACK), (0.66, -3.6)]
        mats_edge = [0, 1, 1, 2, 2, 2, 2, 1, 1, 3, 3, 3]
        o = K.lathe(f"nac{part}", prof, 36, mat_of_edge=lambda i: mats_edge[min(i, len(mats_edge) - 1)])
        add(K.orient(o, (0, 1, 0), (px, 0, pz)), [dark, steel, hull2, heat], part, smooth=True)
        add(K.orient(K.cyl(f"fan{part}", 0.74, -0.02, 0.02, 32), (0, 1, 0), (px, 1.9, pz)), dark, part)
        o = K.lathe(f"spinner{part}", [(0.0, 2.25), (0.16, 2.2), (0.24, 2.05), (0.26, 1.9)], 20, cap=True)
        add(K.orient(o, (0, 1, 0), (px, 0, pz)), chrome, part, smooth=True)
        for k in range(6):
            v = K.box(f"stator{part}", (px, 1.95, pz + 0.45), (0.06, 0.04, 0.6))
            rot_about(v, "Y", k * 60, (px, 1.95, pz))
            add(v, steel, part)
        add(K.orient(K.cyl(f"nglow{part}", 0.64, -0.02, 0.02, 32), (0, 1, 0), (px, -3.62, pz)), l_hot, part)
        o = K.lathe(f"nband{part}", [(R + 0.005, 0.55), (R + 0.02, 0.6), (R + 0.02, 0.92), (R + 0.005, 0.97)], 36)
        add(K.orient(o, (0, 1, 0), (px, 0, pz)), paint, part, smooth=True)
        fin = prism("nac_fin", [(-0.8, pz + 1.05), (-2.9, pz + 1.0), (-3.35, pz + 1.75), (-2.3, pz + 1.75)], "x", px - 0.05, px + 0.05, rim_mat=1)
        add(fin, [hull2, steel], part)
        bx("nac_fin_light", (px, -2.85, pz + 1.77), (0.1, 0.9, 0.04), l_red, part)
        add(K.rod("shaft", (px - sx * 1.35, py, pz), (px - sx * 1.0, py, pz), 0.38, 20), steel, part, smooth=True)
        for k in range(8):
            v = K.box(f"nvent{part}", (px + sx * 1.13, -0.6 - k * 0.22, pz), (0.06, 0.1, 0.9))
            add(v, dark, part)

    # ------------------------------------------------------------------------------------------ landing gear
    for part, (gx, gy, gz) in PIV_GEAR.items():
        big = part != "gear_f"
        r = 0.5 if big else 0.42
        add(K.rod("knuckle", (gx - 0.2, gy, gz), (gx + 0.2, gy, gz), 0.13, 12), steel, part, smooth=True)
        add(K.rod("oleo", (gx, gy, gz), (gx, gy, 0.45), 0.1 if big else 0.08, 12), steel, part, smooth=True)
        add(K.rod("piston", (gx, gy, 0.5), (gx, gy, 0.14), 0.065 if big else 0.055, 12), chrome, part, smooth=True)
        if big:
            add(K.rod("brace", (gx, gy - 0.35, gz - 0.05), (gx, gy, 0.35), 0.04, 8), steel, part)
        add(K.cyl("pad", r, 0.0, 0.1, 24, gx, gy), rubber, part, smooth=True)
        add(K.cyl("pad_top", r * 0.92, 0.1, 0.14, 24, gx, gy), haz, part)
        bx("gear_light", (gx, gy + 0.1, 0.62), (0.05, 0.06, 0.05), l_red, part)

    # ------------------------------------------------------------------------------------------ rear hatch / ramp
    bx("ramp_slab", (0, -5.76, 3.7), (3.24, 0.2, 3.4), hull2, "ramp", bevel=0.03)
    bx("ramp_tread", (0, -5.655, 3.75), (2.9, 0.01, 3.1), tread, "ramp")
    for zz in (2.25, 5.25):
        bx("ramp_haz", (0, -5.652, zz), (2.9, 0.012, 0.22), haz, "ramp")
    for sx in (-1, 1):
        bx("ramp_rail", (sx * 1.56, -5.69, 3.7), (0.1, 0.08, 3.4), steel, "ramp")
        bx("ramp_glow", (sx * 1.5, -5.866, 3.7), (0.04, 0.01, 3.1), l_red, "ramp")
    for x in (-1.15, 0.0, 1.15):
        add(K.rod("hinge", (x - 0.25, -5.62, 2.02), (x + 0.25, -5.62, 2.02), 0.11, 12), steel, "ramp", smooth=True)
    bx("ramp_glow_top", (0, -5.866, 5.25), (2.9, 0.01, 0.04), l_red, "ramp")

    # ------------------------------------------------------------------------------------------ bake + export
    K.stamp(objs, PARTS)
    ship = K.join(objs, "starship")
    K.unwrap(ship, 0.0015)
    col = K.bake_one(ship, MATS, 2048, f"{assets}/textures/ship/starship.png")
    glow = K.bake_one(ship, MATS, 1024, f"{assets}/textures/ship/starship_glow.png", "glow")
    pivots = {"ramp": PIV_RAMP, **PIV_GEAR, **PIV_ENG}
    K.export(ship, PARTS, pivots, f"{assets}/models/ship/starship.json")
    previews(ship, col, glow, assets, out)


# ================================================================================================ previews

def posed_copy(ship, rots, whole=None, drop_glass=False):
    """A copy of the ship with parts turned about their pivots (radians about X, plus a lift in z) and, optionally,
    the whole thing moved."""
    pivots = {"ramp": PIV_RAMP, **PIV_GEAR, **PIV_ENG}
    o = ship.copy()
    o.data = ship.data.copy()
    bpy.context.scene.collection.objects.link(o)
    me = o.data
    bm = bmesh.new()
    bm.from_mesh(me)
    lay = bm.faces.layers.int.get("krl_part")
    drop = []
    for f in bm.faces:
        part = PARTS[f[lay]]
        if drop_glass and part == "glass":
            drop.append(f)
    if drop:
        bmesh.ops.delete(bm, geom=drop, context="FACES")
    for part, (ang, lift) in rots.items():
        c = Vector(pivots[part])
        m = Matrix.Translation((0, 0, lift)) @ Matrix.Translation(c) @ Matrix.Rotation(ang, 4, "X") @ Matrix.Translation(-c)
        vs = {v for f in bm.faces if PARTS[f[lay]] == part for v in f.verts}
        for v in vs:
            v.co = m @ v.co
    bm.to_mesh(me)
    bm.free()
    if whole is not None:
        o.matrix_world = whole
    o.hide_render = False
    return o


def previews(ship, col, glow, assets, out):
    pm = K.preview_material("sh_prev", col, glow, 4.0)
    ship.hide_render = True
    sc = bpy.context.scene
    # ground (added after baking so it never darkens the baked occlusion)
    bpy.ops.mesh.primitive_plane_add(size=200, location=(0, 0, -0.005))
    ground = K.last()
    gm = bpy.data.materials.new("ground")
    gm.use_nodes = True
    gm.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.05, 0.048, 0.046, 1)
    gm.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.8
    ground.data.materials.append(gm)
    ramp_open = math.radians(123.5)
    fold = math.radians(90)
    parked = {"ramp": (ramp_open, 0.0)}
    folded = {g: (fold, 0.32) for g in PIV_GEAR}

    def shot(path, rots, cam, target, whole=None, drop_glass=False, **kw):
        o = posed_copy(ship, rots, whole, drop_glass)
        K.preview(o, pm, path, cam, target, **kw)
        bpy.data.objects.remove(o, do_unlink=True)

    bg = (0.012, 0.011, 0.012)
    shot(f"{out}/ship_parked.png", parked, (19, 25, 10), (0, 1.5, 3.5), lens=30, res=1200, bg=bg)
    shot(f"{out}/ship_rear.png", parked, (-11, -27, 7.5), (0, -4.5, 3.2), lens=30, res=1100, bg=bg)
    shot(f"{out}/ship_side.png", parked, (34, 1.0, 4.0), (0, 1.0, 4.0), lens=32, res=1200, bg=bg)
    shot(f"{out}/ship_top.png", parked, (0.01, 1.0, 46), (0, 1.0, 3.0), lens=35, res=1100, bg=bg)
    shot(f"{out}/ship_canopy.png", parked, (6.5, 15.5, 7.5), (0, 8.8, 3.6), lens=32, res=1000, bg=bg)
    # inside: lit by a couple of warm cabin lights
    lamps = []
    for yy in (-2.5, 2.5):
        ld = bpy.data.lights.new("cab", "POINT")
        ld.energy = 60
        ld.color = (1.0, 0.82, 0.62)
        lo = bpy.data.objects.new("cab", ld)
        lo.location = (0, yy, 4.6)
        sc.collection.objects.link(lo)
        lamps.append(lo)
    shot(f"{out}/ship_cabin.png", parked, (0.6, -4.9, 3.75), (0, 5.5, 3.4), lens=16, res=1000, bg=bg)
    shot(f"{out}/ship_cockpit.png", parked, (0.0, 5.0, 4.4), (0, 10.0, 3.2), lens=20, res=1000, bg=bg, drop_glass=True)
    for lo in lamps:
        bpy.data.objects.remove(lo, do_unlink=True)
    # flying: gear up, banking, nose up a little
    fly = Matrix.Translation((0, 0, 9)) @ Matrix.Rotation(math.radians(-14), 4, "Y") @ Matrix.Rotation(math.radians(8), 4, "X")
    shot(f"{out}/ship_flight.png", dict(folded), (24, 22, 15), (0, 0, 10), whole=fly, lens=30, res=1200, bg=bg)
    # hovering in VTOL mode, gear on its way down
    vt = dict({e: (math.radians(90), 0.0) for e in PIV_ENG}, **{g: (math.radians(40), 0.15) for g in PIV_GEAR})
    shot(f"{out}/ship_vtol.png", vt, (22, 20, 6), (0, 0, 6.5), whole=Matrix.Translation((0, 0, 4.5)), lens=30, res=1200, bg=bg)
    # item icon: transparent, three-quarter view
    ground.hide_render = True
    shot(f"{assets}/textures/item/starship.png", parked, (21, 22, 15), (0, 0.5, 3.5), lens=36, res=64, samples=24,
         transparent=True)
    ground.hide_render = False


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--no-render", action="store_true")
    K.ARGS = ap.parse_args(argv)
    os.makedirs(K.ARGS.out, exist_ok=True)
    build(os.path.abspath(K.ARGS.assets), os.path.abspath(K.ARGS.out))
    print("[ship] done")


if __name__ == "__main__":
    main()
