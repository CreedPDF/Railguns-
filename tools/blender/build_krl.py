"""
KRL - the Kinetic Rail Launcher - built and textured in headless Blender (run by tools/blender/build.sh).

Every model is modelled from scratch out of lathed profiles, lofted cross-sections, extruded outlines and boxes,
unwrapped, and its textures are BAKED in Cycles from procedural materials (an albedo atlas and a glow mask).
Meshes are exported as "krl_mesh": named parts with per-corner [x, y, z, u, v, nx, ny, nz] in Minecraft axes (y up),
in blocks, with the front of each model facing south (+Z). Everything is in a grey military finish.

  launcher  KRL-1: a steel launch platform on outrigger jacks (part "base") carrying a turntable with an inclined
            launch rail at 60 degrees, elevation rams, a control box and counterweight (part "turret", slews to the
            target), two latch straps over the missile (part "clamps"), an umbilical boom (part "umbilical") and an
            amber warning beacon (part "beacon")
  spear     the kinetic missile: 7 blocks, solid-rocket motor and nozzle (part "nozzle" glows), clipped-delta tail fins,
            canards, raceway, launch lugs, ID bands and stencils; tangent-ogive tungsten nose (part "nose" - the game
            wraps its plasma sheath round this part). Baked once per livery: textures/krl/spear_<livery>.png
  console   the fire-control console, 3 blocks wide and 1 deep: a slanted control deck with gauges, toggles,
            push-button matrix, faders, key switch and a launch button under a flip cover (part "cover"), and an
            upright board with three green CRTs and a strip of annunciator tiles (part "annunciators")
"""
import argparse
import json
import math
import os
import sys

import bpy
import bmesh
from mathutils import Matrix, Vector

ARGS = None

RED = (1.0, 0.07, 0.03)
HOT = (1.0, 0.32, 0.18)


# ------------------------------------------------------------------------------------------------ scene utils

def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.device = "CPU"
    sc.cycles.samples = 6
    sc.cycles.use_denoising = False
    sc.view_settings.view_transform = "Standard"
    sc.render.bake.margin = 6


def active(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def last():
    return bpy.context.active_object


def apply_mods(obj):
    active(obj)
    for m in list(obj.modifiers):
        bpy.ops.object.modifier_apply(modifier=m.name)


def link_obj(name, bm):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(o)
    o["krl_fix"] = 1
    return o


def tag(obj, part, mats, smooth=False):
    """Remember which part a primitive belongs to and give it its material slots."""
    obj["krl_part"] = part
    obj.data.materials.clear()
    for m in (mats if isinstance(mats, (list, tuple)) else [mats]):
        obj.data.materials.append(m.m)
    # recalculating normals goes through edit mode, which clamps face material indices to the slots that exist -
    # so it must happen only once the slots are assigned
    if obj.get("krl_fix"):
        fix_normals(obj)
    active(obj)
    if smooth:
        bpy.ops.object.shade_smooth()
    else:
        bpy.ops.object.shade_flat()
    return obj


def stamp(objs, parts):
    for o in objs:
        me = o.data
        me.attributes.new("krl_part", "INT", "FACE")
        pa = me.attributes["krl_part"]
        pi = parts.index(o["krl_part"])
        for k in range(len(me.polygons)):
            pa.data[k].value = pi


def join(objs, name):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    o = bpy.context.active_object
    o.name = name
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    # n-gons (text, caps) become triangles so the game only ever sees quads/tris
    bm = bmesh.new()
    bm.from_mesh(o.data)
    big = [f for f in bm.faces if len(f.verts) > 4]
    if big:
        bmesh.ops.triangulate(bm, faces=big, quad_method="BEAUTY", ngon_method="BEAUTY")
    bm.to_mesh(o.data)
    bm.free()
    return o


def unwrap(obj, margin=0.002):
    active(obj)
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=math.radians(55), island_margin=margin)
    bpy.ops.object.mode_set(mode="OBJECT")


# ------------------------------------------------------------------------------------------------ geometry builders

def lathe(name, profile, segs=48, a0=0.0, a1=math.tau, closed=False, mat_of_edge=None, offset=(0, 0, 0), cap=False):
    """Revolve a (radius, z) profile about Z. mat_of_edge(i) gives the material index of profile edge i."""
    full = abs((a1 - a0) - math.tau) < 1e-6
    bm = bmesh.new()
    cols = segs if full else segs + 1
    rings = []
    for k in range(cols):
        a = a0 + (a1 - a0) * k / segs
        ca, sa = math.cos(a), math.sin(a)
        rings.append([bm.verts.new((offset[0] + r * ca, offset[1] + r * sa, offset[2] + z)) for r, z in profile])
    n = len(profile)
    edges = n if closed else n - 1
    for k in range(segs):
        A, B = rings[k], rings[(k + 1) % cols]
        for i in range(edges):
            j = (i + 1) % n
            try:
                f = bm.faces.new((A[i], B[i], B[j], A[j]))
            except ValueError:
                continue
            if mat_of_edge:
                f.material_index = mat_of_edge(i)
    if cap and not full:
        for ring in (rings[0], rings[-1]):
            try:
                bm.faces.new(ring)
            except ValueError:
                pass
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    bm.normal_update()
    return link_obj(name, bm)


def fix_normals(o):
    active(o)
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.mesh.normals_make_consistent(inside=False)
    bpy.ops.object.mode_set(mode="OBJECT")


def loft(name, sections, closed_caps=True, mat_of_face=None):
    """sections: list of (z, [(x, y), ...]) cross-sections with equal point counts."""
    bm = bmesh.new()
    rings = [[bm.verts.new((x, y, z)) for x, y in pts] for z, pts in sections]
    n = len(sections[0][1])
    for s in range(len(rings) - 1):
        A, B = rings[s], rings[s + 1]
        for i in range(n):
            j = (i + 1) % n
            vs = [A[i], A[j], B[j], B[i]]
            uniq = []
            for v in vs:
                if v not in uniq:
                    uniq.append(v)
            if len(uniq) < 3:
                continue
            try:
                f = bm.faces.new(uniq)
            except ValueError:
                continue
            if mat_of_face:
                f.material_index = mat_of_face(s, i, sections[s][0])
    if closed_caps:
        for ring in (rings[0], rings[-1]):
            try:
                bm.faces.new(ring)
            except ValueError:
                pass
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-4)
    bm.normal_update()
    return link_obj(name, bm)


def extrude_outline(name, pts_xz, thickness, rim_mat=1, face_mat=0, y=0.0):
    """A flat outline in the XZ plane, extruded along Y; rim faces get rim_mat."""
    bm = bmesh.new()
    front = [bm.verts.new((x, y - thickness / 2, z)) for x, z in pts_xz]
    back = [bm.verts.new((x, y + thickness / 2, z)) for x, z in pts_xz]
    f1 = bm.faces.new(front)
    f1.material_index = face_mat
    f2 = bm.faces.new(list(reversed(back)))
    f2.material_index = face_mat
    n = len(pts_xz)
    for i in range(n):
        j = (i + 1) % n
        f = bm.faces.new((front[i], front[j], back[j], back[i]))
        f.material_index = rim_mat
    bm.normal_update()
    return link_obj(name, bm)


def beam(name, p0, p1, w, h, up=(0, 0, 1)):
    """A box from p0 to p1 with cross-section w x h."""
    p0, p1 = Vector(p0), Vector(p1)
    d = p1 - p0
    bpy.ops.mesh.primitive_cube_add(size=1, location=(p0 + p1) / 2)
    o = last()
    o.name = name
    o.scale = (w, h, d.length)
    o.rotation_mode = "QUATERNION"
    o.rotation_quaternion = d.to_track_quat("Z", "Y")
    active(o)
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
    return o


def box(name, center, size, rot_z=0.0, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center, rotation=(0, 0, rot_z))
    o = last()
    o.name = name
    o.scale = size
    active(o)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        b = o.modifiers.new("b", "BEVEL")
        b.width = bevel
        b.segments = 1
        apply_mods(o)
    return o


def text_mesh(name, s, size, loc, rot, extrude=0.05):
    bpy.ops.object.text_add(location=loc, rotation=rot)
    t = last()
    t.data.body = s
    t.data.size = size
    t.data.extrude = extrude
    t.data.align_x = "CENTER"
    t.data.align_y = "CENTER"
    active(t)
    bpy.ops.object.convert(target="MESH")
    o = last()
    o.name = name
    return o


# ------------------------------------------------------------------------------------------------ materials

class Mat:
    """A bake material: an albedo branch and a glow branch, each feeding its own emission node."""

    def __init__(self, name):
        self.m = bpy.data.materials.new(name)
        self.m.use_nodes = True
        self.nt = self.m.node_tree
        self.nt.nodes.clear()
        self.out = self.nt.nodes.new("ShaderNodeOutputMaterial")
        self.e_col = self.nt.nodes.new("ShaderNodeEmission")
        self.e_glow = self.nt.nodes.new("ShaderNodeEmission")
        self.coord = self.nt.nodes.new("ShaderNodeTexCoord")
        self.e_glow.inputs["Color"].default_value = (0, 0, 0, 1)
        self.mode("color")

    def n(self, kind, **inputs):
        node = self.nt.nodes.new(kind)
        for k, v in inputs.items():
            if k.startswith("_"):
                setattr(node, k[1:], v)
            else:
                node.inputs[k].default_value = v
        return node

    def link(self, a, b):
        self.nt.links.new(a, b)

    def color(self, sock):
        if isinstance(sock, tuple):
            self.e_col.inputs["Color"].default_value = (*sock, 1)
        else:
            self.link(sock, self.e_col.inputs["Color"])

    def glow(self, sock):
        if isinstance(sock, tuple):
            self.e_glow.inputs["Color"].default_value = (*sock, 1)
        else:
            self.link(sock, self.e_glow.inputs["Color"])

    def mode(self, which):
        for l in list(self.out.inputs["Surface"].links):
            self.nt.links.remove(l)
        self.link((self.e_col if which == "color" else self.e_glow).outputs[0], self.out.inputs["Surface"])

    def vec(self, scale=1.0):
        if scale == 1.0:
            return self.coord.outputs["Object"]
        m = self.n("ShaderNodeVectorMath", _operation="SCALE")
        self.link(self.coord.outputs["Object"], m.inputs[0])
        m.inputs["Scale"].default_value = scale
        return m.outputs[0]

    def noise(self, scale, detail=6, rough=0.6, dist=0.0):
        nz = self.n("ShaderNodeTexNoise", Scale=scale, Detail=detail, Roughness=rough, Distortion=dist)
        self.link(self.coord.outputs["Object"], nz.inputs["Vector"])
        return nz.outputs["Fac"]

    def voronoi_edge(self, scale):
        v = self.n("ShaderNodeTexVoronoi", Scale=scale, _feature="DISTANCE_TO_EDGE")
        self.link(self.coord.outputs["Object"], v.inputs["Vector"])
        return v.outputs["Distance"]

    def brick(self, scale, mortar=0.02, w=0.5, h=0.25):
        b = self.n("ShaderNodeTexBrick", Scale=scale, **{"Mortar Size": mortar, "Brick Width": w, "Row Height": h})
        self.link(self.coord.outputs["Object"], b.inputs["Vector"])
        return b.outputs["Fac"]

    def ramp(self, fac, stops):
        r = self.n("ShaderNodeValToRGB")
        cr = r.color_ramp
        cr.elements[0].position, cr.elements[0].color = stops[0][0], (*stops[0][1], 1)
        cr.elements[1].position, cr.elements[1].color = stops[1][0], (*stops[1][1], 1)
        for p, c in stops[2:]:
            e = cr.elements.new(p)
            e.color = (*c, 1)
        self.link(fac, r.inputs["Fac"])
        return r.outputs["Color"]

    def mix(self, a, b, fac, blend="MIX"):
        m = self.n("ShaderNodeMix", _data_type="RGBA", _blend_type=blend)
        if isinstance(fac, (int, float)):
            m.inputs[0].default_value = fac
        else:
            self.link(fac, m.inputs[0])
        for idx, v in ((6, a), (7, b)):
            if isinstance(v, tuple):
                m.inputs[idx].default_value = (*v, 1)
            else:
                self.link(v, m.inputs[idx])
        return m.outputs[2]

    def math(self, op, a, b=None, clamp=False):
        m = self.n("ShaderNodeMath", _operation=op, _use_clamp=clamp)
        for i, v in enumerate((a, b)):
            if v is None:
                continue
            if isinstance(v, (int, float)):
                m.inputs[i].default_value = v
            else:
                self.link(v, m.inputs[i])
        return m.outputs[0]

    def ao(self, distance=0.5):
        a = self.n("ShaderNodeAmbientOcclusion", Distance=distance)
        a.samples = 12
        return a.outputs["AO"]

    def axis(self, which):
        sep = self.n("ShaderNodeSeparateXYZ")
        self.link(self.coord.outputs["Object"], sep.inputs[0])
        return sep.outputs[which]


def hull_mat(name, scale=0.35, lights=0.0, stripes=0.0):
    """Black armoured plating: panel seams, grime, worn edges, a few orange hazard stripes and lit windows."""
    m = Mat(name)
    seams = m.brick(scale, 0.015, 0.55, 0.3)
    n1 = m.noise(scale * 2.2, 6, 0.6)
    n2 = m.noise(scale * 18, 3, 0.5)
    col = m.ramp(n1, [(0.25, (0.018, 0.017, 0.018)), (0.6, (0.045, 0.042, 0.042)), (0.85, (0.075, 0.07, 0.068))])
    col = m.mix(col, m.ramp(n2, [(0.45, (0, 0, 0)), (0.75, (0.03, 0.028, 0.026))]), 0.5, "ADD")
    col = m.mix((0.004, 0.004, 0.004), col, seams)
    edge = m.math("POWER", m.math("SUBTRACT", 1.0, m.ao(0.12)), 2.0)
    col = m.mix(col, (0.16, 0.15, 0.14), m.math("MULTIPLY", edge, 0.35, True))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(1.5)), 0.6, True))
    glow = None
    if stripes > 0:
        z = m.axis("Z")
        band = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", m.math("ADD", z, m.axis("X")), 0.5)), 0.5)
        hz = m.math("MULTIPLY", m.math("GREATER_THAN", m.noise(0.08, 2, 0.5), 0.62), band)
        col = m.mix(col, (0.5, 0.025, 0.018), m.math("MULTIPLY", hz, stripes))
    if lights > 0:
        win = m.math("GREATER_THAN", m.noise(scale * 9, 1, 0.3), 0.74)
        win = m.math("MULTIPLY", win, m.math("SUBTRACT", 1.0, seams))
        win = m.math("MULTIPLY", win, m.math("GREATER_THAN", m.noise(0.6, 2, 0.5), 0.5))
        col = m.mix(col, (0.9, 0.1, 0.05), m.math("MULTIPLY", win, lights))
        glow = m.ramp(m.math("MULTIPLY", win, lights), [(0.0, (0, 0, 0)), (1.0, HOT)])
    m.color(col)
    if glow is not None:
        m.glow(glow)
    return m


def paint_mat(name, base=(0.5, 0.025, 0.02)):
    m = Mat(name)
    n1 = m.noise(1.2, 6, 0.6)
    n2 = m.noise(9.0, 3, 0.5)
    col = m.ramp(n1, [(0.3, tuple(c * 0.55 for c in base)), (0.7, base)])
    col = m.mix(col, (0.03, 0.02, 0.015), m.math("MULTIPLY", m.math("GREATER_THAN", n2, 0.7), 0.6))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.8)), 0.55, True))
    m.color(col)
    return m


def glow_mat(name, color=RED, k=1.0, pulse_scale=0.0):
    m = Mat(name)
    if pulse_scale:
        f = m.noise(pulse_scale, 3, 0.5)
        m.color(m.ramp(f, [(0.3, tuple(c * 0.7 for c in color)), (0.8, color)]))
        m.glow(m.ramp(f, [(0.3, tuple(c * k * 0.65 for c in color)), (0.8, tuple(min(1, c * k) for c in color))]))
    else:
        m.color(color)
        m.glow(tuple(min(1, c * k) for c in color))
    return m


def obsidian_mat(name):
    """The spear's metal: glossy black with faint ember marbling and fine engraved lines that smoulder."""
    m = Mat(name)
    n1 = m.noise(0.15, 8, 0.7, 2.5)
    n2 = m.noise(1.4, 4, 0.55)
    col = m.ramp(n1, [(0.3, (0.012, 0.01, 0.01)), (0.55, (0.03, 0.022, 0.02)), (0.7, (0.07, 0.035, 0.02))])
    col = m.mix(col, m.ramp(n2, [(0.5, (0, 0, 0)), (0.8, (0.02, 0.014, 0.012))]), 0.6, "ADD")
    edge = m.math("POWER", m.math("SUBTRACT", 1.0, m.ao(0.4)), 2.0)
    col = m.mix(col, (0.12, 0.1, 0.09), m.math("MULTIPLY", edge, 0.3, True))
    vor = m.voronoi_edge(0.22)
    lines = m.math("LESS_THAN", vor, 0.007)
    ember = m.math("MULTIPLY", lines, m.math("GREATER_THAN", n1, 0.6))
    col = m.mix(col, (0.5, 0.17, 0.03), ember)
    m.color(col)
    m.glow(m.ramp(ember, [(0.0, (0, 0, 0)), (1.0, (0.55, 0.2, 0.03))]))
    return m


def solar_mat(name):
    m = Mat(name)
    cells = m.brick(0.9, 0.03, 0.5, 0.5)
    n1 = m.noise(0.2, 4, 0.5)
    col = m.ramp(n1, [(0.3, (0.025, 0.015, 0.008)), (0.7, (0.07, 0.035, 0.012))])
    col = m.mix((0.32, 0.16, 0.05), col, cells)
    m.color(col)
    m.glow(m.ramp(m.math("SUBTRACT", 1.0, cells), [(0.0, (0, 0, 0)), (1.0, (0.18, 0.07, 0.01))]))
    return m


# ------------------------------------------------------------------------------------------------ bake + export

def bake(obj, mats, size, path_color, path_glow):
    imgs = {}
    for which, path in (("color", path_color), ("glow", path_glow)):
        img = bpy.data.images.new(os.path.basename(path), size, size, alpha=False)
        img.colorspace_settings.name = "Non-Color"
        for mt in mats:
            mt.mode(which)
            tex = mt.nt.nodes.get("krl_bake") or mt.nt.nodes.new("ShaderNodeTexImage")
            tex.name = "krl_bake"
            tex.image = img
            mt.nt.nodes.active = tex
        active(obj)
        bpy.ops.object.bake(type="EMIT", margin=6)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.filepath_raw = path
        img.file_format = "PNG"
        img.save()
        imgs[which] = img
        print(f"[bake] {os.path.basename(path)} {size}px")
    for mt in mats:
        mt.mode("color")
    return imgs


def export(obj, parts, pivots, path):
    """krl_mesh: parts with per-corner [x,y,z,u,v,nx,ny,nz] in Minecraft axes ((x,y,z) -> (x,z,-y))."""
    me = obj.data
    uv = me.uv_layers.active.data
    pa = me.attributes["krl_part"].data
    out = {p: {"verts": [], "quads": []} for p in parts}
    for poly in me.polygons:
        dst = out[parts[pa[poly.index].value]]
        idx = []
        for li in poly.loop_indices:
            vi = me.loops[li].vertex_index
            co = me.vertices[vi].co
            n = (me.vertices[vi].normal if poly.use_smooth else poly.normal).normalized()
            u, v = uv[li].uv
            dst["verts"] += [round(co.x, 3), round(co.z, 3), round(-co.y, 3), round(u, 5), round(1 - v, 5),
                             round(n.x, 2), round(n.z, 2), round(-n.y, 2)]
            idx.append(len(dst["verts"]) // 8 - 1)
        if len(idx) == 3:
            idx.append(idx[2])
        if len(idx) == 4:
            dst["quads"] += idx
    js = {"format": "krl_mesh", "stride": 8, "parts": []}
    for p in parts:
        pv = pivots.get(p, (0, 0, 0))
        js["parts"].append({"name": p, "pivot": [pv[0], pv[2], -pv[1]], **out[p]})
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(js, fh, separators=(",", ":"))
    print(f"[mesh] {os.path.basename(path)}: " + ", ".join(f"{p}={len(out[p]['quads']) // 4}q" for p in parts))


def preview_material(name, color_img, glow_img, strength=6.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    b = nt.nodes["Principled BSDF"]
    t = nt.nodes.new("ShaderNodeTexImage")
    t.image = color_img
    nt.links.new(t.outputs["Color"], b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = 0.42
    b.inputs["Metallic"].default_value = 0.6
    g = nt.nodes.new("ShaderNodeTexImage")
    g.image = glow_img
    nt.links.new(g.outputs["Color"], b.inputs["Emission Color"])
    b.inputs["Emission Strength"].default_value = strength
    return m


def preview(obj, mat, path, cam_loc, target, lens=50, res=900, light=3.5, transparent=False, samples=24, bg=(0.004, 0.003, 0.003)):
    if ARGS.no_render and not transparent:
        return
    sc = bpy.context.scene
    obj.data.materials.clear()
    obj.data.materials.append(mat)
    w = bpy.data.worlds.new("w")
    w.use_nodes = True
    w.node_tree.nodes["Background"].inputs[0].default_value = (*bg, 1)
    sc.world = w
    if "key" not in bpy.data.objects:
        l = bpy.data.lights.new("key", "SUN")
        l.energy = light
        lo = bpy.data.objects.new("key", l)
        lo.rotation_euler = (math.radians(55), math.radians(15), math.radians(35))
        sc.collection.objects.link(lo)
        l2 = bpy.data.lights.new("rim", "SUN")
        l2.energy = light * 0.4
        l2.color = (1.0, 0.6, 0.35)
        lo2 = bpy.data.objects.new("rim", l2)
        lo2.rotation_euler = (math.radians(-60), math.radians(0), math.radians(200))
        sc.collection.objects.link(lo2)
    cam = bpy.data.cameras.new("cam")
    co = bpy.data.objects.new("cam", cam)
    sc.collection.objects.link(co)
    sc.camera = co
    co.location = Vector(cam_loc)
    co.rotation_euler = (Vector(target) - co.location).to_track_quat("-Z", "Y").to_euler()
    cam.lens = lens
    cam.clip_end = 2000
    sc.cycles.samples = samples
    sc.render.film_transparent = transparent
    sc.render.resolution_x = res
    sc.render.resolution_y = res
    sc.render.resolution_percentage = 100
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA" if transparent else "RGB"
    sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
    sc.cycles.samples = 6
    sc.render.film_transparent = False
    print(f"[render] {os.path.basename(path)}")


# ------------------------------------------------------------------------------------------------ the station



# ================================================================================================ KRL materials (military)

AMBER = (1.0, 0.55, 0.08)
GREEN = (0.25, 0.95, 0.35)


def bake_one(obj, mats, size, path, which="color"):
    """Bake a single pass (colour or glow) to a PNG."""
    img = bpy.data.images.new(os.path.basename(path), size, size, alpha=False)
    img.colorspace_settings.name = "Non-Color"
    for mt in mats:
        mt.mode(which)
        tex = mt.nt.nodes.get("krl_bake") or mt.nt.nodes.new("ShaderNodeTexImage")
        tex.name = "krl_bake"
        tex.image = img
        mt.nt.nodes.active = tex
    active(obj)
    bpy.ops.object.bake(type="EMIT", margin=6)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.filepath_raw = path
    img.file_format = "PNG"
    img.save()
    for mt in mats:
        mt.mode("color")
    print(f"[bake] {os.path.basename(path)} {size}px")
    return img


def _stretch(m, sx, sy, sz):
    v = m.n("ShaderNodeVectorMath", _operation="MULTIPLY")
    m.link(m.coord.outputs["Object"], v.inputs[0])
    v.inputs[1].default_value = (sx, sy, sz)
    return v.outputs[0]


def _noise_at(m, vec, scale, detail=4, rough=0.55):
    nz = m.n("ShaderNodeTexNoise", Scale=scale, Detail=detail, Roughness=rough)
    m.link(vec, nz.inputs["Vector"])
    return nz.outputs["Fac"]


def _seams(m, scale, mortar=0.008, w=0.7, h=0.35, dark=0.45):
    b = m.n("ShaderNodeTexBrick", Scale=scale, **{"Mortar Size": mortar, "Brick Width": w, "Row Height": h})
    b.inputs["Color1"].default_value = (1, 1, 1, 1)
    b.inputs["Color2"].default_value = (1, 1, 1, 1)
    b.inputs["Mortar"].default_value = (dark, dark, dark, 1)
    m.link(m.coord.outputs["Object"], b.inputs["Vector"])
    return b.outputs["Color"]


def mil_mat(name, color, seams=0.0, wear=0.3, grime=0.35, scale=2.0, checker=False):
    """Military paint: a flat colour (changeable through m.rgb for liveries) with subtle mottling, panel seams, vertical
    weathering streaks, chipped edges showing bare metal, and dirt in the crevices. checker adds the black-and-white
    quadrant pattern of a test round (amount in m.check)."""
    m = Mat(name)
    rgb = m.n("ShaderNodeRGB")
    rgb.outputs[0].default_value = (*color, 1)
    m.rgb = rgb
    col = rgb.outputs[0]
    if checker:
        x, y, z = m.axis("X"), m.axis("Y"), m.axis("Z")
        q = m.math("ADD", m.math("GREATER_THAN", x, 0.0), m.math("GREATER_THAN", y, 0.0))
        q = m.math("ADD", q, m.math("GREATER_THAN", m.math("FRACT", m.math("MULTIPLY", z, 0.8)), 0.5))
        blk = m.math("MODULO", q, 2.0)
        amt = m.n("ShaderNodeValue")
        amt.outputs[0].default_value = 0.0
        m.check = amt
        col = m.mix(col, (0.035, 0.035, 0.035), m.math("MULTIPLY", blk, amt.outputs[0]))
    # gentle colour mottling
    mot = m.ramp(m.noise(scale, 5, 0.55), [(0.3, (0.9, 0.9, 0.9)), (0.7, (1.0, 1.0, 1.0))])
    col = m.mix(col, mot, 1.0, "MULTIPLY")
    if seams > 0:
        col = m.mix(col, _seams(m, seams), 1.0, "MULTIPLY")
    # vertical rain streaks / exhaust grime
    streak = _noise_at(m, _stretch(m, 6.0, 6.0, 0.35), 3.0, 3, 0.5)
    col = m.mix(col, m.mix(col, (0.12, 0.115, 0.1), 1.0, "MULTIPLY"),
                m.math("MULTIPLY", m.math("GREATER_THAN", streak, 0.62), grime * 0.5))
    # chipped paint: little islands of worn bare steel
    chips = m.math("GREATER_THAN", m.noise(scale * 22, 3, 0.6), 0.74)
    col = m.mix(col, (0.36, 0.36, 0.35), m.math("MULTIPLY", chips, wear * 0.6))
    # dirt in corners and crevices
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.35)), grime + 0.15, True))
    m.color(col)
    return m


def metal_mat(name, base=(0.3, 0.3, 0.3), scale=6.0, rings=0.0):
    """Bare machined steel: brushed noise, optional machining rings along Z, dark crevices."""
    m = Mat(name)
    n1 = m.noise(scale, 6, 0.55)
    col = m.ramp(n1, [(0.3, tuple(c * 0.75 for c in base)), (0.7, base)])
    if rings > 0:
        r = m.math("GREATER_THAN", m.math("SINE", m.math("MULTIPLY", m.axis("Z"), 240.0)), 0.8)
        col = m.mix(col, tuple(min(1, c * 1.3) for c in base), m.math("MULTIPLY", r, rings))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.25)), 0.5, True))
    m.color(col)
    return m


def tread_mat(name):
    """Anti-slip diamond tread deck plate, painted grey and worn shiny along the walkways."""
    m = Mat(name)
    x, y = m.axis("X"), m.axis("Y")
    a = m.math("FRACT", m.math("MULTIPLY", m.math("ADD", x, y), 9.0))
    b = m.math("FRACT", m.math("MULTIPLY", m.math("SUBTRACT", x, y), 9.0))
    ridge = m.math("MULTIPLY", m.math("LESS_THAN", m.math("ABSOLUTE", m.math("SUBTRACT", a, 0.5)), 0.12),
                   m.math("LESS_THAN", m.math("ABSOLUTE", m.math("SUBTRACT", b, 0.5)), 0.32))
    col = m.ramp(m.noise(2.5, 5, 0.55), [(0.3, (0.2, 0.205, 0.2)), (0.7, (0.25, 0.255, 0.25))])
    col = m.mix(col, (0.4, 0.4, 0.39), m.math("MULTIPLY", ridge, 0.6))
    col = m.mix(col, (0.06, 0.06, 0.06), m.math("MULTIPLY", m.math("GREATER_THAN", m.noise(1.2, 4, 0.6), 0.66), 0.35))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.3)), 0.45, True))
    m.color(col)
    return m


def hazard_mat(name, k=5.0):
    """Yellow-and-black diagonal hazard stripes, scuffed."""
    m = Mat(name)
    s = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", m.math("ADD", m.math("ADD", m.axis("X"), m.axis("Y")), m.axis("Z")), k)), 0.5)
    col = m.mix((0.03, 0.03, 0.03), (0.78, 0.6, 0.06), s)
    col = m.mix(col, (0.25, 0.24, 0.22), m.math("MULTIPLY", m.math("GREATER_THAN", m.noise(30.0, 3, 0.6), 0.72), 0.5))
    m.color(col)
    return m


def stencil_mat(name, ink=(0.03, 0.03, 0.03), ground=None):
    """A stencilled data block: rows of short dashes reading as text, in ink on the paint (ground) colour."""
    m = Mat(name)
    rgb = m.n("ShaderNodeRGB")
    rgb.outputs[0].default_value = (*(ground or (0.5, 0.5, 0.5)), 1)
    m.rgb = rgb
    ink_n = m.n("ShaderNodeRGB")
    ink_n.outputs[0].default_value = (*ink, 1)
    m.ink = ink_n
    z = m.axis("Z")
    rows = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", z, 26.0)), 0.55)
    words = m.math("GREATER_THAN", _noise_at(m, _stretch(m, 30.0, 30.0, 4.0), 1.0, 2, 0.5), 0.45)
    t = m.math("MULTIPLY", rows, words)
    m.color(m.mix(rgb.outputs[0], ink_n.outputs[0], t))
    return m


def mil_screen_mat(name, color=GREEN):
    """A fire-control CRT: dark green phosphor with scan lines, a grid and glowing traces."""
    m = Mat(name)
    x, z = m.axis("X"), m.axis("Z")
    scan = m.math("GREATER_THAN", m.math("SINE", m.math("MULTIPLY", z, 900.0)), 0.2)
    gx = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", x, 18.0)), 0.06)
    gz = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", z, 18.0)), 0.06)
    grid = m.math("MAXIMUM", gx, gz)
    trace = m.math("LESS_THAN", m.math("ABSOLUTE", m.math("SUBTRACT", m.math("FRACT", m.math("MULTIPLY", z, 3.0)),
                                                            m.math("MULTIPLY", m.noise(2.5, 2, 0.4), 0.9))), 0.02)
    v = m.math("ADD", m.math("MULTIPLY", grid, 0.25), trace)
    v = m.math("MULTIPLY", v, m.math("ADD", 0.6, m.math("MULTIPLY", scan, 0.4)))
    dim = tuple(c * 0.03 for c in color)
    m.color(m.ramp(v, [(0.0, dim), (1.0, tuple(c * 0.85 for c in color))]))
    m.glow(m.ramp(v, [(0.0, tuple(c * 0.04 for c in color)), (1.0, color)]))
    return m


def mil_panel_mat(name):
    """Console deck: matte dark grey enamel with engraved white legend strips."""
    m = Mat(name)
    col = m.ramp(m.noise(6.0, 5, 0.5), [(0.3, (0.1, 0.104, 0.1)), (0.7, (0.135, 0.14, 0.135))])
    lines = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", m.axis("X"), 9.0)), 0.04)
    col = m.mix(col, (0.78, 0.78, 0.74), m.math("MULTIPLY", lines, m.math("GREATER_THAN", m.noise(30.0, 1, 0.3), 0.55)))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.05)), 0.4, True))
    m.color(col)
    return m


def flat_mat(name, color, glow=None):
    m = Mat(name)
    m.color(color)
    if glow:
        m.glow(glow)
    return m


def dial_mat(name):
    """Analog gauge face: off-white dial with black ticks and a red danger arc."""
    m = Mat(name)
    x, y = m.axis("X"), m.axis("Z")
    ang = m.math("ARCTAN2", y, x)
    ticks = m.math("GREATER_THAN", m.math("SINE", m.math("MULTIPLY", ang, 24.0)), 0.92)
    col = m.mix((0.84, 0.82, 0.76), (0.02, 0.02, 0.02), ticks)
    danger = m.math("LESS_THAN", ang, -0.6)
    col = m.mix(col, (0.6, 0.05, 0.03), m.math("MULTIPLY", danger, 0.8))
    m.color(col)
    m.glow(m.ramp(danger, [(0.0, (0.04, 0.04, 0.035)), (1.0, (0.15, 0.02, 0.01))]))
    return m


def cyl(name, r, z0, z1, segs=24, x=0.0, y=0.0):
    bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=z1 - z0, vertices=segs, location=(x, y, (z0 + z1) / 2))
    o = last()
    o.name = name
    return o


def rod(name, p0, p1, r, segs=16):
    """A cylinder from p0 to p1."""
    p0, p1 = Vector(p0), Vector(p1)
    d = p1 - p0
    bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=d.length, vertices=segs, location=(p0 + p1) / 2)
    o = last()
    o.name = name
    o.rotation_mode = "QUATERNION"
    o.rotation_quaternion = d.to_track_quat("Z", "Y")
    active(o)
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
    return o


def orient(o, axis, at):
    """Turn an object built along +Z so its Z runs along `axis`, and move its origin to `at`."""
    o.rotation_mode = "QUATERNION"
    o.rotation_quaternion = Vector(axis).to_track_quat("Z", "Y")
    o.location = Vector(at)
    active(o)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return o


def label(name, s, size, loc, rot, mat_list, part, extrude=0.002):
    t = text_mesh(name, s, size, loc, rot, extrude=extrude)
    return tag(t, part, mat_list)


# ================================================================================================ shared launcher geometry
# (mirrored in KrlFlight on the Java side: keep them in step)

EL = math.radians(60)
SE, CE = math.sin(EL), math.cos(EL)
PIV = Vector((0, 0.6, 1.25))          # trunnion (rail pivot), Blender axes; front of the launcher is -Y
RD = Vector((0, -CE, SE))             # rail direction: forward and up
RN = Vector((0, SE, CE))              # rail "top": the side the missile rides on
SPEAR_UP = 0.42                       # missile axis above the rail centreline
NOZZLE_K = 0.3                        # missile nozzle this far along the rail from the pivot


def rail_pt(k, n=0.0, x=0.0):
    return PIV + RD * k + RN * n + Vector((x, 0, 0))


# ================================================================================================ the missile

SPEAR_L = 7.0
NOSE_Z = 4.95

LIVERIES = [
    # name, primary, accent (fins), stencil ink, band (motor), band (warhead), checker
    ("standard", (0.6, 0.61, 0.6), (0.44, 0.45, 0.44), (0.04, 0.04, 0.04), (0.33, 0.2, 0.09), (0.8, 0.62, 0.06), False),
    ("olive", (0.25, 0.27, 0.16), (0.19, 0.2, 0.13), (0.82, 0.8, 0.72), (0.33, 0.2, 0.09), (0.8, 0.62, 0.06), False),
    ("arctic", (0.85, 0.85, 0.83), (0.66, 0.66, 0.64), (0.04, 0.04, 0.04), (0.33, 0.2, 0.09), (0.8, 0.62, 0.06), False),
    ("desert", (0.63, 0.53, 0.37), (0.52, 0.43, 0.3), (0.06, 0.05, 0.04), (0.33, 0.2, 0.09), (0.8, 0.62, 0.06), False),
    ("darkgrey", (0.2, 0.205, 0.21), (0.12, 0.122, 0.125), (0.82, 0.82, 0.8), (0.3, 0.18, 0.08), (0.7, 0.55, 0.06), False),
    ("test", (0.86, 0.86, 0.84), (0.85, 0.36, 0.05), (0.04, 0.04, 0.04), (0.85, 0.36, 0.05), (0.85, 0.36, 0.05), True),
]


def spear_profile():
    """(radius, z) from the nozzle exit to the tip."""
    pts = [(0.0, 0.0), (0.22, 0.0), (0.2, 0.08), (0.145, 0.25), (0.13, 0.3), (0.26, 0.34), (0.28, 0.42),
           (0.28, 3.3), (0.287, 3.33), (0.287, 3.45), (0.272, 3.48), (0.265, 3.5), (0.265, NOSE_Z)]
    # a tangent ogive to a small blunted tip
    L = SPEAR_L - NOSE_Z
    R = 0.265
    rho = (R * R + L * L) / (2 * R)
    for k in range(1, 17):
        f = k / 16
        x = f * L
        r = math.sqrt(max(0.0, rho * rho - (L - x) ** 2)) + R - rho
        pts.append((max(r, 0.012 if k < 16 else 0.0), NOSE_Z + x))
    return pts


def spear_objects(m):
    """The missile's primitives; m maps roles to materials. Returns (body, nose, nozzle) object lists."""
    body, nose, nozzle = [], [], []
    prof = spear_profile()
    nose_from = next(i for i, p in enumerate(prof) if p[1] >= NOSE_Z)

    def mat_of(i):
        z = prof[i][1]
        if z < 0.34:
            return 0
        if z < 0.42:
            return 1
        if 3.3 <= z <= 3.48:
            return 1
        return 2

    o = lathe("m_body", prof[:nose_from + 1], 32, mat_of_edge=mat_of)
    body.append(tag(o, "body", [m["nozzle"], m["dark"], m["paint"]], smooth=True))
    o = lathe("m_nose", prof[nose_from:], 32)
    nose.append(tag(o, "nose", m["tung"], smooth=True))
    o = lathe("m_throat", [(0.0, 0.03), (0.19, 0.03), (0.135, 0.25), (0.0, 0.25)], 24)
    nozzle.append(tag(o, "nozzle", m["glow"]))
    # identification bands: brown round the motor, yellow round the warhead section
    for (z0, z1, key) in ((2.88, 3.04, "band1"), (4.32, 4.44, "band2")):
        r = 0.281 if z0 < 3.5 else 0.266
        o = lathe(f"m_band{z0}", [(r, z0), (r + 0.002, z0 + 0.005), (r + 0.002, z1 - 0.005), (r, z1)], 32)
        body.append(tag(o, "body", m[key], smooth=True))
    # clipped-delta tail fins and small canards (both in an X)
    tail = [(0.27, 0.46), (0.8, 0.52), (0.8, 0.74), (0.27, 1.42)]
    can = [(0.26, 4.42), (0.5, 4.52), (0.5, 4.64), (0.26, 4.84)]
    for k in range(4):
        for nm, outline, th in (("fin", tail, 0.03), ("can", can, 0.024)):
            o = extrude_outline(f"m_{nm}{k}", outline, th)
            o.rotation_euler = (0, 0, math.radians(45 + 90 * k))
            active(o)
            bpy.ops.object.transform_apply(rotation=True)
            body.append(tag(o, "body", [m["accent"], m["accent"]]))
    # cable raceway along the top, launch lugs underneath (+X rides on the rail)
    o = box("m_raceway", (-0.292, 0, 2.55), (0.035, 0.075, 3.5), bevel=0.01)
    body.append(tag(o, "body", m["paint"]))
    for z in (1.0, 3.95):
        o = box(f"m_lug{z}", (0.305, 0, z), (0.06, 0.09, 0.15), bevel=0.01)
        body.append(tag(o, "body", m["metal"]))
    # stencilled data blocks on both flanks
    for sy in (-1, 1):
        o = box(f"m_sten{sy}", (0, sy * 0.281, 2.05), (0.17, 0.006, 0.55))
        body.append(tag(o, "body", m["stencil"]))
        o = box(f"m_sten2{sy}", (0, sy * 0.267, 3.95), (0.13, 0.006, 0.22))
        body.append(tag(o, "body", m["stencil"]))
    # aft closure bolt ring
    for k in range(12):
        a = k * math.tau / 12
        o = cyl(f"m_bolt{k}", 0.013, 0.395, 0.43, 6, math.cos(a) * 0.27, math.sin(a) * 0.27)
        body.append(tag(o, "body", m["metal"]))
    return body, nose, nozzle


def spear_mats(prefix):
    lv = LIVERIES[0]
    return {
        "paint": mil_mat(f"{prefix}_paint", lv[1], seams=0.0, wear=0.15, grime=0.18, scale=3.0, checker=True),
        "accent": mil_mat(f"{prefix}_accent", lv[2], wear=0.25, grime=0.2, scale=4.0),
        "band1": mil_mat(f"{prefix}_band1", lv[4], wear=0.1, grime=0.15, scale=6.0),
        "band2": mil_mat(f"{prefix}_band2", lv[5], wear=0.1, grime=0.15, scale=6.0),
        "stencil": stencil_mat(f"{prefix}_sten", lv[3], lv[1]),
        "dark": metal_mat(f"{prefix}_dark", (0.11, 0.11, 0.11), 8.0),
        "metal": metal_mat(f"{prefix}_metal", (0.34, 0.34, 0.33), 8.0),
        "nozzle": flat_mat(f"{prefix}_noz", (0.05, 0.048, 0.046), glow=(0.08, 0.03, 0.01)),
        "tung": metal_mat(f"{prefix}_tung", (0.27, 0.27, 0.26), 5.0, rings=0.25),
        "glow": glow_mat(f"{prefix}_glow", (1.0, 0.45, 0.12), 1.0),
    }


def set_livery(m, lv):
    name, prim, acc, ink, b1, b2, chk = lv
    m["paint"].rgb.outputs[0].default_value = (*prim, 1)
    m["paint"].check.outputs[0].default_value = 1.0 if chk else 0.0
    m["accent"].rgb.outputs[0].default_value = (*acc, 1)
    m["band1"].rgb.outputs[0].default_value = (*b1, 1)
    m["band2"].rgb.outputs[0].default_value = (*b2, 1)
    m["stencil"].rgb.outputs[0].default_value = (*prim, 1)
    m["stencil"].ink.outputs[0].default_value = (*ink, 1)


def build_spear(assets, out):
    reset()
    m = spear_mats("ks")
    body, nose, nozzle = spear_objects(m)
    parts = ["body", "nose", "nozzle"]
    allo = body + nose + nozzle
    stamp(allo, parts)
    o = join(allo, "spear")
    unwrap(o, 0.003)
    mats = list(m.values())
    imgs = []
    for lv in LIVERIES:
        set_livery(m, lv)
        imgs.append(bake_one(o, mats, 1024, f"{assets}/textures/krl/spear_{lv[0]}.png"))
    glow = bake_one(o, mats, 512, f"{assets}/textures/krl/spear_glow.png", "glow")
    export(o, parts, {}, f"{assets}/models/krl/spear.json")
    if not ARGS.no_render:
        # all the liveries side by side
        copies = []
        for i, img in enumerate(imgs):
            c = o.copy()
            c.data = o.data.copy()
            bpy.context.scene.collection.objects.link(c)
            c.location = ((i - (len(imgs) - 1) / 2) * 1.05, 0, 0)
            c.data.materials.clear()
            c.data.materials.append(preview_material(f"krl_prev_lv{i}", img, glow, 2.0))
            copies.append(c)
        o.hide_render = True
        preview(copies[0], copies[0].data.materials[0], f"{out}/krl_liveries.png", (0.6, -9.5, 4.2), (0, 0, 3.4), lens=38)
        for c in copies:
            bpy.data.objects.remove(c)
        o.hide_render = False
    pm = preview_material("krl_prev_s", imgs[0], glow, 2.0)
    preview(o, pm, f"{out}/krl_spear.png", (2.6, -3.4, 2.2), (0, 0, 1.6), lens=40)
    return o


# ================================================================================================ the launcher

def build_launcher(assets, out):
    reset()
    grey = mil_mat("kl_grey", (0.4, 0.41, 0.39), seams=1.4, wear=0.35, grime=0.35)
    dgrey = mil_mat("kl_dgrey", (0.22, 0.225, 0.215), seams=1.8, wear=0.3, grime=0.3)
    tread = tread_mat("kl_tread")
    steel = metal_mat("kl_steel", (0.32, 0.32, 0.31), 7.0)
    chrome = metal_mat("kl_chrome", (0.62, 0.62, 0.6), 12.0)
    rubber = flat_mat("kl_rubber", (0.025, 0.025, 0.025))
    haz = hazard_mat("kl_haz", 4.0)
    sten = stencil_mat("kl_sten", (0.85, 0.85, 0.8), (0.22, 0.225, 0.215))
    lamp = glow_mat("kl_lamp", AMBER, 1.0)
    grn = glow_mat("kl_green", GREEN, 0.9)
    white = flat_mat("kl_white", (0.86, 0.86, 0.82))
    base, turret, clamps, umb, beacon = [], [], [], [], []

    # ---- base: a steel launch platform on four outrigger jacks
    o = box("deck", (0, 0, 0.22), (2.9, 2.9, 0.3), bevel=0.04)
    base.append(tag(o, "base", dgrey))
    o = box("tread", (0, 0, 0.373), (2.72, 2.72, 0.008))
    base.append(tag(o, "base", tread))
    for i, (cx, cy, sx, sy) in enumerate(((0, -1.4, 2.72, 0.1), (0, 1.4, 2.72, 0.1), (-1.4, 0, 0.1, 2.72), (1.4, 0, 0.1, 2.72))):
        o = box(f"edge{i}", (cx, cy, 0.375), (sx, sy, 0.01))
        base.append(tag(o, "base", haz))
    o = lathe("bearing", [(0.92, 0.37), (1.0, 0.37), (1.0, 0.45), (0.92, 0.45)], 48, closed=True)
    base.append(tag(o, "base", steel, smooth=True))
    for sx in (-1, 1):
        for sy in (-1, 1):
            o = beam(f"outr{sx}{sy}", (sx * 1.15, sy * 1.15, 0.22), (sx * 1.5, sy * 1.5, 0.16), 0.16, 0.12)
            base.append(tag(o, "base", grey))
            o = cyl(f"jack{sx}{sy}", 0.07, 0.06, 0.36, 12, sx * 1.5, sy * 1.5)
            base.append(tag(o, "base", chrome, smooth=True))
            o = cyl(f"jbody{sx}{sy}", 0.1, 0.22, 0.42, 12, sx * 1.5, sy * 1.5)
            base.append(tag(o, "base", grey))
            o = cyl(f"pad{sx}{sy}", 0.22, 0.0, 0.06, 16, sx * 1.5, sy * 1.5)
            base.append(tag(o, "base", steel))
    # generator, battery and cable reel on the deck corners
    o = box("gen", (0.95, 1.0, 0.68), (0.62, 0.62, 0.62), bevel=0.03)
    base.append(tag(o, "base", grey))
    for k in range(6):
        o = box(f"gen_louvre{k}", (0.95, 0.685, 0.5 + k * 0.06), (0.46, 0.012, 0.025))
        base.append(tag(o, "base", rubber))
    base.append(label("gen_lbl", "PWR 28V", 0.08, (0.95, 0.684, 0.88), (math.radians(90), 0, 0), [sten], "base"))
    o = box("batt", (1.05, -1.0, 0.56), (0.45, 0.42, 0.38), bevel=0.02)
    base.append(tag(o, "base", dgrey))
    o = box("batt_lamp", (1.05, -1.212, 0.66), (0.08, 0.01, 0.05))
    base.append(tag(o, "base", grn))
    o = rod("reel", (-1.25, 1.0, 0.62), (-0.75, 1.0, 0.62), 0.24, 20)
    base.append(tag(o, "base", grey, smooth=True))
    o = rod("reel_core", (-1.28, 1.0, 0.62), (-0.72, 1.0, 0.62), 0.1, 12)
    base.append(tag(o, "base", steel))
    o = rod("reel_cable", (-1.2, 1.0, 0.62), (-0.8, 1.0, 0.62), 0.18, 20)
    base.append(tag(o, "base", rubber, smooth=True))
    for sx in (-1.27, -0.73):
        o = box(f"reel_stand{sx}", (sx, 1.0, 0.5), (0.04, 0.3, 0.26))
        base.append(tag(o, "base", grey))
    # access steps at the front
    for k in range(2):
        o = box(f"step{k}", (-0.95, -1.52 - k * 0.12, 0.28 - k * 0.12), (0.5, 0.14, 0.04))
        base.append(tag(o, "base", tread))
    base.append(label("deck_lbl", "DANGER  -  ROCKET BLAST AREA", 0.09, (0, -1.18, 0.379), (0, 0, 0), [white], "base"))
    base.append(label("deck_lbl2", "KRL-1", 0.16, (-0.95, -0.95, 0.379), (0, 0, 0), [white], "base"))

    # ---- turret: turntable, yoke, trunnion, elevation rams and the inclined launch rail
    o = cyl("turntable", 0.9, 0.45, 0.62, 48)
    turret.append(tag(o, "turret", grey))
    o = lathe("tt_rim", [(0.9, 0.6), (0.93, 0.6), (0.93, 0.63), (0.9, 0.63)], 48, closed=True)
    turret.append(tag(o, "turret", haz))
    yoke = [(-0.55, 0.62), (0.88, 0.62), (0.88, 1.02), (0.72, 1.45), (0.46, 1.5), (-0.15, 0.98)]
    for sx in (-1, 1):
        o = extrude_outline(f"yoke{sx}", yoke, 0.07)
        o.rotation_euler = (0, 0, math.radians(90))
        active(o)
        bpy.ops.object.transform_apply(rotation=True)
        o.location = (sx * 0.34, 0, 0)
        active(o)
        bpy.ops.object.transform_apply(location=True)
        turret.append(tag(o, "turret", [grey, dgrey]))
    o = rod("trunnion", PIV + Vector((-0.42, 0, 0)), PIV + Vector((0.42, 0, 0)), 0.11, 16)
    turret.append(tag(o, "turret", steel, smooth=True))
    for sx in (-1, 1):
        o = rod(f"trun_cap{sx}", PIV + Vector((sx * 0.38, 0, 0)), PIV + Vector((sx * 0.43, 0, 0)), 0.16, 16)
        turret.append(tag(o, "turret", dgrey))
    # the rail: a box girder with top guides, a truss underneath and three cradles
    o = beam("rail_main", rail_pt(-0.6), rail_pt(7.7), 0.34, 0.22)
    turret.append(tag(o, "turret", grey))
    for sx in (-0.1, 0.1):
        o = beam(f"rail_guide{sx}", rail_pt(-0.4, 0.14, sx), rail_pt(7.6, 0.14, sx), 0.04, 0.06)
        turret.append(tag(o, "turret", chrome))
    o = beam("rail_chord", rail_pt(0.4, -0.44), rail_pt(6.9, -0.44), 0.12, 0.1)
    turret.append(tag(o, "turret", dgrey))
    for k in range(13):
        k0, k1 = 0.4 + k * 0.5, 0.4 + (k + 1) * 0.5
        for sx in (-0.15, 0.15):
            a = rail_pt(k0, -0.1 if k % 2 == 0 else -0.44, sx)
            b = rail_pt(k1, -0.44 if k % 2 == 0 else -0.1, sx)
            o = beam(f"web{k}{sx}", a, b, 0.035, 0.035)
            turret.append(tag(o, "turret", steel))
    for k in (1.2, 3.6, 6.0):
        o = beam(f"cradle{k}", rail_pt(k - 0.08, 0.17), rail_pt(k + 0.08, 0.17), 0.56, 0.06)
        turret.append(tag(o, "turret", dgrey))
        for sx in (-0.27, 0.27):
            o = beam(f"cradle_side{k}{sx}", rail_pt(k - 0.08, 0.2, sx), rail_pt(k + 0.08, 0.2, sx), 0.04, 0.22)
            turret.append(tag(o, "turret", dgrey))
            o = beam(f"pad{k}{sx}", rail_pt(k - 0.07, 0.22, sx * 0.9), rail_pt(k + 0.07, 0.22, sx * 0.9), 0.02, 0.1)
            turret.append(tag(o, "turret", rubber))
    # rail end stops and the rear blast plate
    o = beam("rail_stop", rail_pt(-0.6, 0.0), rail_pt(-0.45, 0.0), 0.44, 0.5)
    turret.append(tag(o, "turret", dgrey))
    o = beam("rail_tip", rail_pt(7.6, 0.0), rail_pt(7.75, 0.0), 0.4, 0.3)
    turret.append(tag(o, "turret", haz))
    for k, s_ in ((2.0, "KRL-1  RAIL"), (5.2, "NO STEP")):
        # stencilled down the left side of the girder: text X along the rail, text up along the rail top
        t_ = text_mesh(f"rail_lbl{k}", s_, 0.09, (0, 0, 0), (0, 0, 0), extrude=0.002)
        rot = Matrix((RD, RN, RD.cross(RN))).transposed().to_4x4()
        t_.matrix_world = Matrix.Translation(rail_pt(k, 0.0, -0.173)) @ rot
        active(t_)
        bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
        turret.append(tag(t_, "turret", [white]))
    # elevation rams: barrel and chrome rod from the turret front to the rail truss
    for sx in (-0.2, 0.2):
        a = Vector((sx, -0.5, 0.7))
        b = rail_pt(2.7, -0.46, sx)
        mid = a.lerp(b, 0.58)
        o = rod(f"ram_barrel{sx}", a, mid, 0.075, 14)
        turret.append(tag(o, "turret", grey, smooth=True))
        o = rod(f"ram_rod{sx}", mid, b, 0.042, 12)
        turret.append(tag(o, "turret", chrome, smooth=True))
        o = rod(f"ram_eye{sx}", a + Vector((-0.06, 0, 0)), a + Vector((0.06, 0, 0)), 0.09, 12)
        turret.append(tag(o, "turret", dgrey))
    o = box("ram_mount", (0, -0.5, 0.66), (0.56, 0.2, 0.12))
    turret.append(tag(o, "turret", dgrey))
    # the launch control box with its warning beacon
    o = box("ctl", (-0.62, 0.2, 0.86), (0.34, 0.62, 0.48), bevel=0.02)
    turret.append(tag(o, "turret", grey))
    o = box("ctl_door", (-0.792, 0.2, 0.86), (0.01, 0.5, 0.38))
    turret.append(tag(o, "turret", dgrey))
    o = box("ctl_lamp", (-0.798, 0.05, 1.0), (0.01, 0.06, 0.04))
    turret.append(tag(o, "turret", grn))
    o = box("ctl_lamp2", (-0.798, 0.15, 1.0), (0.01, 0.06, 0.04))
    turret.append(tag(o, "turret", lamp))
    o = cyl("beacon_post", 0.035, 1.1, 1.3, 8, -0.62, 0.36)
    turret.append(tag(o, "turret", steel))
    o = lathe("beacon_lamp", [(0.0, 1.3), (0.07, 1.31), (0.075, 1.4), (0.05, 1.45), (0.0, 1.46)], 16)
    beacon.append(tag(o, "beacon", lamp, smooth=True))
    # counterweight at the back
    o = box("cw", (0, 0.72, 0.8), (0.6, 0.3, 0.36), bevel=0.03)
    turret.append(tag(o, "turret", dgrey))
    turret.append(label("cw_lbl", "CW 2.4T", 0.07, (0, 0.871, 0.82), (math.radians(90), 0, math.radians(180)), [sten], "turret"))

    # ---- clamps: two latch straps over the missile, hinged on the rail's right side (swing open before launch)
    hinge = rail_pt(0, SPEAR_UP, 0.31)
    for k in (0.95, 5.3):
        ctr = rail_pt(k, SPEAR_UP)
        prof = [(0.3, -0.05), (0.335, -0.05), (0.335, 0.05), (0.3, 0.05)]
        o = lathe(f"latch{k}", prof, 24, a0=math.radians(-80), a1=math.radians(190), closed=True, cap=True)
        # lathe axis (+Z) along the rail; the strap's open side faces the rail
        o.rotation_mode = "QUATERNION"
        q1 = RD.to_track_quat("Z", "Y")
        o.rotation_quaternion = q1
        o.location = ctr
        active(o)
        bpy.ops.object.transform_apply(location=True, rotation=True)
        clamps.append(tag(o, "clamps", haz))
        o = beam(f"latch_hinge{k}", rail_pt(k - 0.06, SPEAR_UP - 0.12, 0.31), rail_pt(k + 0.06, SPEAR_UP - 0.12, 0.31), 0.07, 0.1)
        clamps.append(tag(o, "clamps", steel))

    # ---- umbilical: a boom from the control box to the missile's motor section
    ua = Vector((-0.62, 0.25, 1.1))
    ub = rail_pt(1.7, SPEAR_UP, -0.3)
    o = beam("umb_boom", ua, ub, 0.06, 0.06)
    umb.append(tag(o, "umbilical", dgrey))
    o = box("umb_plug", tuple(ub), (0.12, 0.12, 0.12), bevel=0.01)
    umb.append(tag(o, "umbilical", haz))
    o = rod("umb_cable", ua + Vector((0, 0, -0.05)), ub + Vector((0, 0, -0.06)), 0.025, 8)
    umb.append(tag(o, "umbilical", rubber))

    parts = ["base", "turret", "clamps", "umbilical", "beacon"]
    allo = base + turret + clamps + umb + beacon
    stamp(allo, parts)
    mats = [grey, dgrey, tread, steel, chrome, rubber, haz, sten, lamp, grn, white]
    o = join(allo, "launcher")
    unwrap(o, 0.003)
    col = bake_one(o, mats, 2048, f"{assets}/textures/krl/launcher.png")
    glow = bake_one(o, mats, 1024, f"{assets}/textures/krl/launcher_glow.png", "glow")
    export(o, parts, {"clamps": tuple(hinge), "umbilical": tuple(ua), "beacon": (-0.62, 0.36, 1.3)}, f"{assets}/models/krl/launcher.json")
    if not ARGS.no_render:
        # a missile on the rail, for the preview only
        sm = spear_mats("pv")
        sb, sn, sz = spear_objects(sm)
        sp = join(sb + sn + sz, "pv_spear")
        sp.data.materials.clear()
        pmat = bpy.data.materials.new("pv_spear_m")
        pmat.use_nodes = True
        pmat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.36, 0.37, 0.36, 1)
        sp.data.materials.append(pmat)
        orient(sp, RD, rail_pt(NOZZLE_K, SPEAR_UP))
    pm = preview_material("krl_prev_l", col, glow, 3.0)
    preview(o, pm, f"{out}/krl_launcher.png", (7.2, -8.6, 5.2), (0, 0.2, 3.0), lens=42, bg=(0.25, 0.27, 0.3))
    preview(o, pm, f"{out}/krl_launcher_side.png", (10.5, 0.8, 3.6), (0, 0.4, 3.0), lens=42, bg=(0.25, 0.27, 0.3))
    return o


# ================================================================================================ the fire-control console

def build_console(assets, out):
    reset()
    steel = mil_mat("kc_grey", (0.42, 0.43, 0.41), seams=2.6, wear=0.25, grime=0.25)
    dgrey = mil_mat("kc_dgrey", (0.18, 0.185, 0.18), wear=0.2, grime=0.2)
    haz = hazard_mat("kc_haz", 9.0)
    panel = mil_panel_mat("kc_panel")
    scr = mil_screen_mat("kc_screen", GREEN)
    dial = dial_mat("kc_dial")
    green = glow_mat("kc_green", GREEN, 0.9)
    amber = glow_mat("kc_amber", AMBER, 1.0)
    white = glow_mat("kc_white", (0.95, 0.93, 0.85), 0.7)
    red = glow_mat("kc_red", (1.0, 0.12, 0.06), 1.0)
    dark = flat_mat("kc_dark", (0.025, 0.025, 0.025))
    glass = flat_mat("kc_glass", (0.32, 0.33, 0.3), glow=(0.03, 0.03, 0.025))
    body, cover, ann = [], [], []
    W = 2.96

    # ---- cabinet: front at -Y, a recessed kick plate, side cheeks
    o = box("cab", (0, 0.02, 0.42), (W, 0.86, 0.72), bevel=0.02)
    body.append(tag(o, "body", steel))
    o = box("kick", (0, -0.36, 0.04), (W - 0.1, 0.08, 0.08))
    body.append(tag(o, "body", dark))
    for sx in (-1, 1):
        o = extrude_outline(f"cheek{sx}", [(-0.45, 0.0), (0.45, 0.0), (0.45, 1.72), (0.2, 1.72), (-0.45, 0.86)], 0.04, rim_mat=1, face_mat=0)
        o.rotation_euler = (0, 0, math.radians(90))
        o.location = (sx * (W / 2 + 0.02), 0, 0)
        active(o)
        bpy.ops.object.transform_apply(location=True, rotation=True)
        body.append(tag(o, "body", [steel, dgrey]))
    for i in range(10):
        o = box(f"louvre{i}", (-1.2 + i * 0.12, -0.415, 0.3), (0.07, 0.02, 0.32))
        body.append(tag(o, "body", dark))
    o = box("band", (0, -0.413, 0.66), (W - 0.06, 0.012, 0.06))
    body.append(tag(o, "body", haz))
    t_ = text_mesh("front_lbl", "KRL FIRE CONTROL  -  UNIT 1", 0.06, (0.85, -0.416, 0.3), (math.radians(90), 0, 0), extrude=0.002)
    body.append(tag(t_, "body", white))

    # ---- the slanted control deck
    tilt = math.radians(24)
    deck_c = Vector((0, -0.05, 0.86))
    o = box("deck", tuple(deck_c), (W - 0.04, 0.8, 0.06))
    o.rotation_euler = (tilt, 0, 0)
    active(o)
    bpy.ops.object.transform_apply(rotation=True)
    body.append(tag(o, "body", panel))

    def on_deck(x, v, h=0.0):
        n = Vector((0, -math.sin(tilt), math.cos(tilt)))
        t = Vector((0, math.cos(tilt), math.sin(tilt)))
        return deck_c + Vector((x, 0, 0)) + t * v + n * (0.03 + h)

    def deck_obj(o, x, v, h=0.0):
        o.rotation_euler = (tilt, 0, 0)
        o.location = on_deck(x, v, h)
        active(o)
        bpy.ops.object.transform_apply(location=True, rotation=True)
        return o

    for i, x in enumerate((-1.0, -0.62, -0.24)):
        bz = lathe(f"bezel{i}", [(0.0, 0.0), (0.15, 0.0), (0.15, 0.03), (0.13, 0.035), (0.13, 0.01), (0.0, 0.01)], 28)
        body.append(tag(deck_obj(bz, x, 0.18), "body", dgrey, smooth=True))
        fc = cyl(f"face{i}", 0.128, 0.0, 0.012, 28)
        body.append(tag(deck_obj(fc, x, 0.18, 0.005), "body", dial))
        nd = box(f"needle{i}", (0.0, 0.05, 0.02), (0.012, 0.1, 0.006))
        nd.rotation_euler = (0, 0, math.radians(-40 + 35 * i))
        active(nd)
        bpy.ops.object.transform_apply(rotation=True)
        body.append(tag(deck_obj(nd, x, 0.18, 0.012), "body", dark))
    for r_ in range(2):
        for i in range(8):
            x = 0.12 + i * 0.1
            v = -0.06 - r_ * 0.15
            b = box(f"tg{r_}{i}", (0, 0, 0.015), (0.05, 0.05, 0.03))
            body.append(tag(deck_obj(b, x, v), "body", dgrey))
            lv = beam(f"lever{r_}{i}", (0, 0, 0.03), (0, -0.03 if (i + r_) % 3 else 0.03, 0.09), 0.012, 0.012)
            body.append(tag(deck_obj(lv, x, v), "body", white))
            lg = box(f"legend{r_}{i}", (0, -0.045, 0.002), (0.06, 0.016, 0.004))
            body.append(tag(deck_obj(lg, x, v), "body", amber if (i * 3 + r_) % 5 == 0 else green))
    for r_ in range(3):
        for i in range(6):
            b = lathe(f"pb{r_}{i}", [(0.0, 0.0), (0.03, 0.0), (0.03, 0.02), (0.026, 0.028), (0.0, 0.03)], 12)
            m_ = green if (r_ + i) % 4 == 0 else amber if (r_ * 2 + i) % 7 == 0 else white
            body.append(tag(deck_obj(b, -1.25 + i * 0.09, -0.08 - r_ * 0.085), "body", m_, smooth=True))
    for i in range(4):
        t_ = box(f"track{i}", (0, 0, 0.004), (0.02, 0.26, 0.008))
        body.append(tag(deck_obj(t_, -0.56 + i * 0.08, -0.17), "body", dark))
        k_ = box(f"knob{i}", (0, 0, 0.02), (0.05, 0.035, 0.03))
        body.append(tag(deck_obj(k_, -0.56 + i * 0.08, -0.25 + i * 0.05), "body", dark))
    ks = lathe("keysw", [(0.0, 0.0), (0.05, 0.0), (0.05, 0.03), (0.0, 0.03)], 16)
    body.append(tag(deck_obj(ks, 0.98, 0.12), "body", dgrey, smooth=True))
    key = box("key", (0, 0, 0.05), (0.012, 0.05, 0.04))
    body.append(tag(deck_obj(key, 0.98, 0.12), "body", white))
    btn = lathe("launch_btn", [(0.0, 0.0), (0.11, 0.0), (0.11, 0.03), (0.09, 0.05), (0.07, 0.065), (0.0, 0.07)], 24)
    body.append(tag(deck_obj(btn, 1.15, -0.12), "body", red, smooth=True))
    ring = lathe("launch_ring", [(0.12, 0.0), (0.15, 0.0), (0.15, 0.015), (0.12, 0.015)], 24, closed=True)
    body.append(tag(deck_obj(ring, 1.15, -0.12), "body", haz))
    cv = box("cover", (0, 0, 0.1), (0.3, 0.3, 0.008))
    cover.append(tag(deck_obj(cv, 1.15, -0.12), "cover", glass))
    for sx in (-1, 1):
        sw = box(f"cover_side{sx}", (sx * 0.15, 0, 0.05), (0.008, 0.3, 0.1))
        cover.append(tag(deck_obj(sw, 1.15, -0.12), "cover", glass))
    for i in range(6):
        st = box(f"haz{i}", (0, 0, 0.001), (0.035, 0.36, 0.002))
        st.rotation_euler = (0, 0, math.radians(35))
        active(st)
        bpy.ops.object.transform_apply(rotation=True)
        body.append(tag(deck_obj(st, 0.96 + i * 0.075, -0.12), "body", haz))

    # ---- the upright board behind the deck
    bz0, bz1 = 1.06, 1.72
    o = box("board", (0, 0.3, (bz0 + bz1) / 2), (W - 0.04, 0.1, bz1 - bz0))
    body.append(tag(o, "body", steel))
    for i, x in enumerate((-0.95, 0.0, 0.95)):
        s_ = box(f"screen{i}", (x, 0.244, 1.36), (0.78, 0.01, 0.42))
        body.append(tag(s_, "body", scr))
        for sz in (1.585, 1.135):
            h = box(f"hood{i}{sz}", (x, 0.2, sz), (0.84, 0.1, 0.03))
            body.append(tag(h, "body", dgrey))
        for sx in (-1, 1):
            h = box(f"hoodside{i}{sx}", (x + sx * 0.41, 0.2, 1.36), (0.03, 0.1, 0.48))
            body.append(tag(h, "body", dgrey))
    for r_ in range(2):
        for i in range(24):
            t_ = box(f"ann{r_}{i}", (-1.38 + i * 0.12, 0.243, 1.64 + r_ * 0.045), (0.1, 0.008, 0.035))
            ann.append(tag(t_, "annunciators", green if (i * 7 + r_ * 3) % 5 else amber))
    t_ = text_mesh("title", "KRL-1  KINETIC RAIL LAUNCHER  //  FIRE CONTROL", 0.05, (0, 0.245, 1.11), (math.radians(90), 0, 0), extrude=0.003)
    body.append(tag(t_, "body", white))
    for i in range(3):
        o = beam(f"conduit{i}", (-1.2 + i * 1.2, 0.38, 0.1), (-1.2 + i * 1.2, 0.38, 1.6), 0.06, 0.06)
        body.append(tag(o, "body", dark))

    parts = ["body", "cover", "annunciators"]
    allo = body + cover + ann
    stamp(allo, parts)
    o = join(allo, "console")
    unwrap(o, 0.002)
    mats = [steel, dgrey, haz, panel, scr, dial, green, amber, white, red, dark, glass]
    col = bake_one(o, mats, 2048, f"{assets}/textures/krl/console.png")
    glow = bake_one(o, mats, 1024, f"{assets}/textures/krl/console_glow.png", "glow")
    hinge = on_deck(1.15, 0.03, 0.0)
    export(o, parts, {"cover": tuple(hinge)}, f"{assets}/models/krl/console.json")
    pm = preview_material("krl_prev_c", col, glow, 3.0)
    preview(o, pm, f"{out}/krl_console.png", (1.6, -3.6, 2.4), (0, 0, 0.9), lens=40, bg=(0.25, 0.27, 0.3))
    return o


def main():
    global ARGS
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--no-render", action="store_true")
    ARGS = ap.parse_args(argv)
    os.makedirs(ARGS.out, exist_ok=True)
    build_spear(ARGS.assets, ARGS.out)
    build_launcher(ARGS.assets, ARGS.out)
    build_console(ARGS.assets, ARGS.out)
    print("[krl] done")


if __name__ == "__main__":
    main()
