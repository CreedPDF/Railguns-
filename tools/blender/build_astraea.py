"""
ASTRAEA // The Worldsplitter, modelled and baked in headless Blender (run by tools/blender/build.sh, ONLY=astraea).

  astraea        orbital_railgun:models/astraea/astraea - the divine lance. Blender +Z is the lance's axis (Minecraft +Y),
                 the butt at z -1.05, the point at z +1.78; the hand holds it at the origin.
                   "body"   white enamel shaft with a spiral pale-gold inlay and a glowing rune line, gold grip bands,
                            an ornate pommel with a white crystal, a flared collar, a pair of swept angelic wings of
                            layered feather blades, and a long leaf blade (polished silver, a white-hot fuller) with two
                            small hooked side prongs
                   "halo"   a gold ring with glowing nodes round the collar (spins)
                   "shards" four crystal shards floating round the blade (turn the other way)
  earth_cut      orbital_railgun:models/astraea/earth_cut - the face Earth shows where it splits, a unit disk in the
                 XY plane facing +Z: a dark crust rim, a molten mantle streaked with convection, a yellow outer core and
                 a white-hot inner core, the whole face jagged (deep rough rock near the rim, smoother melt inward).
  earth_chunks   orbital_railgun:models/astraea/earth_chunks - eight torn-off chunks of planet (parts c0..c7), crust on
                 one side and glowing molten rock where they broke.

Colours: white, silver, pale gold and warm white light for the lance; rock, ember red, orange and white heat for the
planet (no blue). Exported as krl_mesh (client/krl/KrlMesh.java).
"""
import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_krl as K  # noqa: E402
import build_ship as S  # noqa: E402

import bmesh  # noqa: E402
import bpy  # noqa: E402
from mathutils import Vector, noise as mnoise  # noqa: E402

WARM = (1.0, 0.95, 0.86)
PALEGOLD = (1.0, 0.86, 0.55)


# ------------------------------------------------------------------------------------------------ materials

def enamel_mat(name):
    """White enamel with a faint pearl variation and a spiral pale-gold inlay up the shaft."""
    m = K.Mat(name)
    n = m.noise(18.0, 4, 0.5)
    col = m.ramp(n, [(0.3, (0.82, 0.8, 0.76)), (0.7, (0.95, 0.93, 0.9))])
    z = m.axis("Z")
    x = m.axis("X")
    y = m.axis("Y")
    ang = m.math("ARCTAN2", y, x)
    spiral = m.math("SINE", m.math("ADD", m.math("MULTIPLY", z, 34.0), ang))
    inlay = m.math("GREATER_THAN", spiral, 0.86)
    col = m.mix(col, (0.86, 0.68, 0.32), inlay)
    col = m.mix(col, (0.3, 0.28, 0.26), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.05)), 0.5, True))
    m.color(col)
    m.glow(m.ramp(inlay, [(0.0, (0, 0, 0)), (1.0, (0.35, 0.28, 0.14))]))
    return m


def silver_mat(name, base=(0.82, 0.82, 0.8)):
    m = K.Mat(name)
    n = m.noise(26.0, 5, 0.55)
    col = m.ramp(n, [(0.3, tuple(c * 0.82 for c in base)), (0.7, base)])
    col = m.mix(col, (0.1, 0.1, 0.1), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.05)), 0.55, True))
    m.color(col)
    return m


def gold_mat(name):
    m = K.Mat(name)
    n = m.noise(9.0, 5, 0.55)
    col = m.ramp(n, [(0.3, (0.62, 0.48, 0.2)), (0.7, (0.92, 0.78, 0.42))])
    col = m.mix(col, (0.18, 0.12, 0.05), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.05)), 0.6, True))
    m.color(col)
    return m


def light_mat(name, color, pulse=0.0):
    return K.glow_mat(name, color, 1.0, pulse_scale=pulse)


def crust_mat(name):
    m = K.Mat(name)
    n = m.noise(14.0, 6, 0.6)
    v = m.voronoi_edge(9.0)
    col = m.ramp(n, [(0.25, (0.06, 0.055, 0.05)), (0.75, (0.2, 0.18, 0.16))])
    crack = m.math("LESS_THAN", v, 0.035)
    col = m.mix(col, (0.5, 0.12, 0.03), crack)
    m.color(col)
    m.glow(m.ramp(crack, [(0.0, (0, 0, 0)), (1.0, (0.7, 0.16, 0.03))]))
    return m


def mantle_mat(name):
    """Molten rock: convection streaks swirling round the centre, dark crusted plates floating on it."""
    m = K.Mat(name)
    n = m.noise(5.0, 7, 0.65, 2.5)
    plates = m.voronoi_edge(12.0)
    heat = m.math("ADD", m.math("MULTIPLY", n, 1.1), m.math("MULTIPLY", m.math("LESS_THAN", plates, 0.05), 0.35))
    col = m.ramp(heat, [(0.2, (0.18, 0.03, 0.01)), (0.5, (0.6, 0.12, 0.02)), (0.8, (0.95, 0.42, 0.08))])
    m.color(col)
    m.glow(m.ramp(heat, [(0.15, (0.12, 0.01, 0.0)), (0.5, (0.85, 0.18, 0.02)), (0.85, (1.0, 0.55, 0.12))]))
    return m


def core_mat(name, inner=False):
    m = K.Mat(name)
    n = m.noise(8.0, 5, 0.5, 1.2)
    if inner:
        stops = [(0.2, (1.0, 0.86, 0.6)), (0.8, (1.0, 0.98, 0.93))]
    else:
        stops = [(0.2, (1.0, 0.5, 0.1)), (0.8, (1.0, 0.8, 0.35))]
    c = m.ramp(n, stops)
    m.color(c)
    m.glow(c)
    return m


# ------------------------------------------------------------------------------------------------ builder

class Build:
    def __init__(self, parts):
        self.parts, self.objs = parts, []

    def add(self, o, m, part, smooth=False):
        self.objs.append(K.tag(o, part, m, smooth=smooth))
        return o

    def lathe(self, name, prof, m, part, segs=32, closed=False):
        return self.add(K.lathe(name, prof, segs, closed=closed), m, part, smooth=True)

    def finish(self, name, mats, size, glow, assets, pivots):
        K.stamp(self.objs, self.parts)
        o = K.join(self.objs, name)
        K.unwrap(o, 0.003)
        col = K.bake_one(o, mats, size, f"{assets}/textures/astraea/{name}.png")
        gl = K.bake_one(o, mats, glow, f"{assets}/textures/astraea/{name}_glow.png", "glow")
        K.export(o, self.parts, pivots, f"{assets}/models/astraea/{name}.json")
        return o, col, gl


def transform_apply(o, loc=None, rot=None, scale=None):
    if loc is not None:
        o.location = loc
    if rot is not None:
        o.rotation_euler = rot
    if scale is not None:
        o.scale = scale
    K.active(o)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return o


def octa(name, length, width):
    """A long crystal: an elongated octahedron along Z."""
    bm = bmesh.new()
    top = bm.verts.new((0, 0, length / 2))
    bot = bm.verts.new((0, 0, -length / 2))
    ring = [bm.verts.new((width * math.cos(a), width * math.sin(a), 0)) for a in (k * math.pi / 2 for k in range(4))]
    for i in range(4):
        j = (i + 1) % 4
        bm.faces.new((ring[i], ring[j], top))
        bm.faces.new((ring[j], ring[i], bot))
    bm.normal_update()
    return K.link_obj(name, bm)


# ------------------------------------------------------------------------------------------------ the lance

def build_lance(assets, out):
    K.reset()
    M = dict(enamel=enamel_mat("as_enamel"), silver=silver_mat("as_silver"), bright=silver_mat("as_bright", (0.96, 0.95, 0.93)),
             gold=gold_mat("as_gold"), white=light_mat("as_white", WARM, 7.0), pgold=light_mat("as_pgold", PALEGOLD))
    b = Build(["body", "halo", "shards"])

    # shaft
    b.lathe("shaft", [(0.0, -1.0), (0.027, -1.0), (0.03, -0.5), (0.028, 0.5), (0.032, 0.92), (0.0, 0.92)], [M["enamel"]], "body", segs=16)
    # a glowing rune line running up the shaft (four thin ribs)
    for k in range(4):
        a = k * math.pi / 2 + math.pi / 4
        rib = K.beam("rib", (0.029 * math.cos(a), 0.029 * math.sin(a), -0.85), (0.031 * math.cos(a), 0.031 * math.sin(a), 0.85), 0.006, 0.004)
        b.add(rib, [M["white"]], "body")
    # grip bands
    for z in (-0.18, 0.0, 0.18, -0.62):
        b.lathe("band", [(0.0, z - 0.03), (0.036, z - 0.03), (0.04, z), (0.036, z + 0.03), (0.0, z + 0.03)], [M["gold"]], "body", segs=16)
    # pommel: gold cup, silver cage, white crystal
    b.lathe("pommel", [(0.0, -1.12), (0.03, -1.12), (0.055, -1.07), (0.05, -1.02), (0.032, -0.99), (0.0, -0.99)], [M["gold"]], "body", segs=12)
    b.lathe("pommel_gem", [(0.0, -1.24), (0.022, -1.18), (0.026, -1.14), (0.0, -1.11)], [M["white"]], "body", segs=6)
    for k in range(6):
        a = k * math.tau / 6
        cage = K.beam("cage", (0.05 * math.cos(a), 0.05 * math.sin(a), -1.07), (0.018 * math.cos(a), 0.018 * math.sin(a), -1.2), 0.008, 0.008)
        b.add(cage, [M["silver"]], "body")
    # collar
    b.lathe("collar", [(0.0, 0.88), (0.034, 0.88), (0.05, 0.93), (0.07, 0.99), (0.06, 1.05), (0.03, 1.08), (0.0, 1.08)], [M["gold"]], "body", segs=16)
    b.lathe("collar_ring", [(0.07, 0.97), (0.078, 0.975), (0.078, 1.005), (0.07, 1.01)], [M["pgold"]], "body", segs=24, closed=True)
    # wings: five feather blades per side, fanned back and out from the collar
    for sx in (-1, 1):
        for f in range(5):
            L = 0.36 - f * 0.045
            spread = math.radians(18 + f * 13)
            feather = S.prism("feather", [(0.0, -0.012), (L * 0.55, -0.026), (L, -0.004), (L * 0.97, 0.012), (L * 0.5, 0.022), (0.0, 0.012)],
                              "y", -0.006, 0.006, rim_mat=1)
            # lay it out along +X, then swing it down and back, mirror for the other side
            S.rot_about(feather, "Y", math.degrees(spread), (0, 0, 0))
            feather.scale = (sx, 1, 1)
            transform_apply(feather)
            K.fix_normals(feather)
            transform_apply(feather, loc=(sx * 0.05, 0.0, 1.0 - f * 0.004))
            b.add(feather, [M["bright"], M["white"]], "body")
    # the blade: a long leaf, diamond section, polished silver with a white-hot fuller
    secs = []
    n = 22
    for i in range(n + 1):
        u = i / n
        z = 1.06 + u * 0.72
        w = 0.085 * math.sin(math.pi * min(1.0, u * 1.35 + 0.06)) ** 0.8 if u < 0.97 else 0.004
        w = max(w, 0.004)
        t = max(0.004, 0.022 * (1 - u * 0.85))
        secs.append((z, [(-w, 0.0), (0.0, t), (w, 0.0), (0.0, -t)]))
    secs.append((1.8, [(0.0, 0.0)] * 4))
    blade = K.loft("blade", secs, closed_caps=True)
    K.fix_normals(blade)
    b.add(blade, [M["bright"]], "body")
    for sy in (-1, 1):
        fsecs = []
        for i in range(15):
            u = i / 14
            z = 1.1 + u * 0.52
            y = sy * (0.022 * (1 - (z - 1.06) / 0.72 * 0.85) * 0.55 + 0.002)
            hw = 0.008 * (1 - u * 0.7)
            fsecs.append((z, [(-hw, y), (hw, y), (hw, y + sy * 0.002), (-hw, y + sy * 0.002)]))
        ful = K.loft("fuller", fsecs, closed_caps=True)
        K.fix_normals(ful)
        b.add(ful, [M["white"]], "body")
    # hooked side prongs at the blade's root
    for sx in (-1, 1):
        prong = S.prism("prong", [(0.0, -0.01), (0.09, 0.02), (0.13, 0.09), (0.11, 0.1), (0.07, 0.05), (0.0, 0.02)], "y", -0.008, 0.008, rim_mat=1)
        prong.scale = (sx, 1, 1)
        transform_apply(prong)
        K.fix_normals(prong)
        transform_apply(prong, loc=(sx * 0.03, 0, 1.06))
        b.add(prong, [M["silver"], M["pgold"]], "body")

    # halo
    halo = K.lathe("halo", [(0.17 + 0.008 * math.cos(a), 0.008 * math.sin(a)) for a in (k * math.tau / 8 for k in range(8))], 48, closed=True)
    transform_apply(halo, loc=(0, 0, 1.0))
    b.add(halo, [M["gold"]], "halo", smooth=True)
    for k in range(12):
        a = k * math.tau / 12
        node = K.box("node", (0.17 * math.cos(a), 0.17 * math.sin(a), 1.0), (0.02, 0.02, 0.02), rot_z=a)
        b.add(node, [M["white"] if k % 3 else M["pgold"]], "halo")
    # floating shards
    for k in range(4):
        a = k * math.tau / 4 + math.pi / 4
        sh = octa("shard", 0.13, 0.018)
        transform_apply(sh, loc=(0.14 * math.cos(a), 0.14 * math.sin(a), 1.32 + 0.05 * (k % 2)))
        b.add(sh, [M["white"]], "shards")

    mlist = list(M.values())
    o, col, gl = b.finish("astraea", mlist, 1024, 512, assets, {"halo": (0, 0, 1.0), "shards": (0, 0, 1.33)})
    pm = K.preview_material("as_prev", col, gl, 5.0)
    K.preview(o, pm, f"{out}/astraea.png", (2.4, -3.0, 1.0), (0, 0, 0.3), lens=50, bg=(0.012, 0.011, 0.012))
    K.preview(o, pm, f"{out}/astraea_head.png", (0.55, -0.75, 1.55), (0, 0, 1.25), lens=50, bg=(0.012, 0.011, 0.012))


# ------------------------------------------------------------------------------------------------ the split planet

def jag(x, y, scale, amp, seed=0.0):
    return mnoise.fractal(Vector((x * scale + seed, y * scale - seed, seed * 0.37)), 0.55, 2.0, 5) * amp


def build_cut(assets, out):
    """The cut face: concentric material bands on a jagged disk (unit radius, XY plane, facing +Z)."""
    K.reset()
    M = [crust_mat("ec_crust"), mantle_mat("ec_mantle"), core_mat("ec_outer"), core_mat("ec_inner", True)]
    bm = bmesh.new()
    rings = [0.0, 0.05, 0.1, 0.15, 0.2, 0.26, 0.32, 0.38, 0.44, 0.5, 0.55, 0.6, 0.66, 0.72, 0.78, 0.84, 0.89, 0.93, 0.96, 0.98, 1.0]
    seg = 128
    grid = []
    for ri, r in enumerate(rings):
        row = []
        for j in range(seg if r > 0 else 1):
            a = j * math.tau / seg
            x, y = r * math.cos(a), r * math.sin(a)
            # relief: rough, deep rock at the rim; a gentler melt toward the core; the core itself bulges
            rough = 0.06 * max(0.0, r - 0.5) / 0.5 + 0.012
            zr = jag(x, y, 6.0, rough, 3.1) + jag(x, y, 22.0, rough * 0.35, 7.7)
            zr += 0.05 * max(0.0, 1 - r / 0.22) ** 2
            if r >= 0.999:
                zr = -0.01 + jag(x, y, 9.0, 0.02, 1.3)
            row.append(bm.verts.new((x, y, zr)))
        grid.append(row)

    def band(r):
        return 0 if r > 0.955 else 1 if r > 0.55 else 2 if r > 0.2 else 3

    for ri in range(len(rings) - 1):
        a_row, b_row = grid[ri], grid[ri + 1]
        rmid = (rings[ri] + rings[ri + 1]) / 2
        for j in range(seg):
            jn = (j + 1) % seg
            if len(a_row) == 1:
                f = bm.faces.new((a_row[0], b_row[j], b_row[jn]))
            else:
                f = bm.faces.new((a_row[j], b_row[j], b_row[jn], a_row[jn]))
            f.material_index = band(rmid)
    # the crust wall: a short skirt down from the rim
    skirt = [bm.verts.new((v.co.x * 1.0, v.co.y * 1.0, -0.06 + jag(v.co.x, v.co.y, 7.0, 0.015, 9.1))) for v in grid[-1]]
    for j in range(seg):
        jn = (j + 1) % seg
        f = bm.faces.new((grid[-1][j], grid[-1][jn], skirt[jn], skirt[j]))
        f.material_index = 0
    bm.normal_update()
    o = K.link_obj("earth_cut", bm)
    K.fix_normals(o)
    b = Build(["face"])
    b.add(o, M, "face", smooth=True)
    o, col, gl = b.finish("earth_cut", M, 2048, 1024, assets, {})
    pm = K.preview_material("ec_prev", col, gl, 4.0)
    K.preview(o, pm, f"{out}/earth_cut.png", (0.0, -1.7, 1.9), (0, 0, 0), lens=45, bg=(0.012, 0.011, 0.012))


def chunk(name, seed, size):
    """A torn-off piece of planet: a displaced icosphere, flattened on one side (the break)."""
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=3, radius=size)
    o = K.last()
    o.name = name
    me = o.data
    for v in me.vertices:
        p = v.co.copy()
        d = mnoise.fractal(p * (2.4 / size) + Vector((seed, seed * 1.7, -seed)), 0.6, 2.0, 4)
        p *= 1 + 0.35 * d
        if p.z < -size * 0.25:
            p.z = -size * 0.25 + (p.z + size * 0.25) * 0.25
        v.co = p
    me.update()
    # material by facing: the flat broken side glows (index 1), the rest is crust (index 0)
    for poly in me.polygons:
        poly.material_index = 1 if poly.normal.z < -0.55 else 0
    return o


def build_chunks(assets, out):
    K.reset()
    M = [crust_mat("ek_crust"), mantle_mat("ek_mantle")]
    parts = [f"c{i}" for i in range(8)]
    b = Build(parts)
    for i in range(8):
        size = 0.35 + 0.65 * ((i * 37) % 10) / 10
        o = chunk(f"chunk{i}", i * 3.7 + 1.0, size)
        o.location = (0, 0, 0)
        b.add(o, M, parts[i], smooth=False)
    o, col, gl = b.finish("earth_chunks", M, 1024, 512, assets, {})


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--no-render", action="store_true")
    K.ARGS = ap.parse_args(argv)
    os.makedirs(K.ARGS.out, exist_ok=True)
    assets, out = os.path.abspath(K.ARGS.assets), os.path.abspath(K.ARGS.out)
    build_lance(assets, out)
    build_cut(assets, out)
    build_chunks(assets, out)
    print("[astraea] done")


if __name__ == "__main__":
    main()
