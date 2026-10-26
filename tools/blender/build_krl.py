"""
KRL - the Kinetic Rail Launcher - built and textured in headless Blender (run by tools/blender/build.sh).

Every model is modelled from scratch out of lathed profiles, lofted cross-sections, extruded outlines and boxes,
unwrapped, and its textures are BAKED in Cycles from procedural materials (an albedo atlas and a red glow mask).
Meshes are exported as "krl_mesh": named parts with per-corner [x, y, z, u, v, nx, ny, nz] in Minecraft axes (y up),
in blocks, with the front of each model facing south (+Z).

  launcher  KRL-1: a 3 x 3 scorched concrete pad with a flame duct, a 9.5-block lattice launch tower with its rail
            spine, three clamp arms (part "clamps"), an umbilical arm (part "umbilical"), struts, equipment boxes and
            a red beacon (part "beacon")
  spear     the kinetic spear: 7 blocks, a solid-rocket motor with bell nozzle (part "nozzle" glows), cruciform fins,
            a black rod with red bands and strakes, and a long tungsten ogive (part "nose" - the game wraps its plasma
            sheath round this part)
  console   the fire-control console, 3 blocks wide and 1 deep: a slanted control deck with gauges, guarded toggles,
            push-button matrix, faders, key switch and a launch button under a flip cover (part "cover"), and an
            upright board with three CRTs and a strip of annunciator tiles (part "annunciators")
"""
import argparse
import json
import math
import os
import sys

import bpy
import bmesh
from mathutils import Vector

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



# ================================================================================================ KRL materials

def steel_mat(name, scale=1.4, stripes=0.0):
    """Black blast-treated steel: plate seams, grime, worn bright edges, optional red hazard bands."""
    m = Mat(name)
    seams = m.brick(scale, 0.012, 0.6, 0.3)
    n1 = m.noise(scale * 2.0, 6, 0.6)
    n2 = m.noise(scale * 14, 3, 0.5)
    col = m.ramp(n1, [(0.25, (0.016, 0.015, 0.016)), (0.6, (0.034, 0.032, 0.033)), (0.85, (0.055, 0.05, 0.05))])
    col = m.mix(col, m.ramp(n2, [(0.45, (0, 0, 0)), (0.75, (0.022, 0.02, 0.02))]), 0.5, "ADD")
    col = m.mix((0.003, 0.003, 0.003), col, seams)
    edge = m.math("POWER", m.math("SUBTRACT", 1.0, m.ao(0.08)), 2.0)
    col = m.mix(col, (0.2, 0.19, 0.19), m.math("MULTIPLY", edge, 0.45, True))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.6)), 0.55, True))
    if stripes > 0:
        z = m.axis("Z")
        band = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", m.math("ADD", z, m.axis("X")), 3.0)), 0.5)
        col = m.mix(col, (0.42, 0.02, 0.015), m.math("MULTIPLY", band, stripes))
    m.color(col)
    return m


def red_mat(name, base=(0.42, 0.018, 0.014)):
    m = Mat(name)
    n1 = m.noise(3.0, 6, 0.6)
    n2 = m.noise(22.0, 3, 0.5)
    col = m.ramp(n1, [(0.3, tuple(c * 0.6 for c in base)), (0.7, base)])
    col = m.mix(col, (0.03, 0.02, 0.02), m.math("MULTIPLY", m.math("GREATER_THAN", n2, 0.72), 0.55))
    edge = m.math("POWER", m.math("SUBTRACT", 1.0, m.ao(0.06)), 2.0)
    col = m.mix(col, (0.22, 0.2, 0.2), m.math("MULTIPLY", edge, 0.35, True))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.4)), 0.5, True))
    m.color(col)
    return m


def tungsten_mat(name):
    """The penetrator: dark tungsten grey with machining rings and heat bluing burned to bronze."""
    m = Mat(name)
    z = m.axis("Z")
    rings = m.math("SINE", m.math("MULTIPLY", z, 260.0))
    n1 = m.noise(4.0, 5, 0.5)
    col = m.ramp(n1, [(0.3, (0.07, 0.068, 0.066)), (0.7, (0.12, 0.115, 0.11))])
    col = m.mix(col, (0.16, 0.15, 0.14), m.math("MULTIPLY", m.math("GREATER_THAN", rings, 0.85), 0.25))
    col = m.mix(col, (0.18, 0.1, 0.05), m.math("MULTIPLY", m.math("GREATER_THAN", m.noise(1.3, 3, 0.5), 0.62), 0.35))
    m.color(col)
    return m


def concrete_mat(name):
    """Launch pad: dark scorched concrete with expansion joints and red-and-black hazard chevrons at the edge."""
    m = Mat(name)
    n1 = m.noise(2.0, 7, 0.65)
    n2 = m.noise(18.0, 4, 0.6)
    col = m.ramp(n1, [(0.2, (0.03, 0.029, 0.028)), (0.6, (0.07, 0.066, 0.063)), (0.9, (0.1, 0.095, 0.09))])
    col = m.mix(col, m.ramp(n2, [(0.4, (0, 0, 0)), (0.8, (0.03, 0.03, 0.03))]), 0.5, "ADD")
    joints = m.brick(0.7, 0.01, 0.5, 0.5)
    col = m.mix((0.01, 0.01, 0.01), col, joints)
    # soot burned round the flame duct
    r = m.math("SQRT", m.math("ADD", m.math("POWER", m.axis("X"), 2.0), m.math("POWER", m.axis("Y"), 2.0)))
    soot = m.math("SUBTRACT", 1.0, m.math("MULTIPLY", r, 0.85), True)
    col = m.mix(col, (0.004, 0.004, 0.004), m.math("MULTIPLY", soot, 0.85, True))
    # chevrons along the outer edge
    edge = m.math("GREATER_THAN", m.math("MAXIMUM", m.math("ABSOLUTE", m.axis("X")), m.math("ABSOLUTE", m.axis("Y"))), 1.3)
    chev = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", m.math("ADD", m.axis("X"), m.axis("Y")), 2.5)), 0.5)
    col = m.mix(col, (0.42, 0.02, 0.015), m.math("MULTIPLY", edge, chev))
    m.color(col)
    return m


def screen_mat(name):
    """A fire-control CRT: dark red phosphor with scan lines, a grid, traces and glowing readouts."""
    m = Mat(name)
    x, z = m.axis("X"), m.axis("Z")
    scan = m.math("GREATER_THAN", m.math("SINE", m.math("MULTIPLY", z, 900.0)), 0.2)
    gx = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", x, 18.0)), 0.06)
    gz = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", z, 18.0)), 0.06)
    grid = m.math("MAXIMUM", gx, gz)
    trace = m.math("LESS_THAN", m.math("ABSOLUTE", m.math("SUBTRACT", m.math("FRACT", m.math("MULTIPLY", z, 3.0)),
                                                            m.math("MULTIPLY", m.noise(2.5, 2, 0.4), 0.9))), 0.02)
    v = m.math("ADD", m.math("MULTIPLY", grid, 0.25), m.math("MULTIPLY", trace, 1.0))
    v = m.math("MULTIPLY", v, m.math("ADD", 0.6, m.math("MULTIPLY", scan, 0.4)))
    col = m.ramp(v, [(0.0, (0.02, 0.004, 0.004)), (1.0, (0.9, 0.08, 0.04))])
    m.color(col)
    m.glow(m.ramp(v, [(0.0, (0.03, 0.002, 0.002)), (1.0, (1.0, 0.1, 0.05))]))
    return m


def panel_mat(name):
    """Console deck: matte black-grey enamel with engraved white legend strips."""
    m = Mat(name)
    n1 = m.noise(6.0, 5, 0.5)
    col = m.ramp(n1, [(0.3, (0.035, 0.034, 0.034)), (0.7, (0.055, 0.053, 0.053))])
    lines = m.math("LESS_THAN", m.math("FRACT", m.math("MULTIPLY", m.axis("X"), 9.0)), 0.04)
    col = m.mix(col, (0.5, 0.48, 0.46), m.math("MULTIPLY", lines, m.math("GREATER_THAN", m.noise(30.0, 1, 0.3), 0.55)))
    edge = m.math("POWER", m.math("SUBTRACT", 1.0, m.ao(0.03)), 2.0)
    col = m.mix(col, (0.18, 0.17, 0.17), m.math("MULTIPLY", edge, 0.4, True))
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
    col = m.mix((0.78, 0.75, 0.7), (0.02, 0.02, 0.02), ticks)
    danger = m.math("LESS_THAN", ang, -0.6)
    col = m.mix(col, (0.6, 0.03, 0.02), m.math("MULTIPLY", danger, 0.8))
    m.color(col)
    m.glow(m.ramp(danger, [(0.0, (0.05, 0.04, 0.035)), (1.0, (0.3, 0.02, 0.01))]))
    return m


# ================================================================================================ the launcher

def cyl(name, r, z0, z1, segs=24, x=0.0, y=0.0):
    bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=z1 - z0, vertices=segs, location=(x, y, (z0 + z1) / 2))
    o = last()
    o.name = name
    return o


def build_launcher(assets, out):
    reset()
    steel = steel_mat("krl_steel", 1.6)
    steel_r = steel_mat("krl_steel_red", 1.6, stripes=0.9)
    red = red_mat("krl_red")
    conc = concrete_mat("krl_concrete")
    glow = glow_mat("krl_glow", RED, 1.0)
    dark = flat_mat("krl_rubber", (0.008, 0.008, 0.008))
    base, clamps, umb, beacon = [], [], [], []

    # ---- pad: 3 x 3 slab with chamfered edge, a recessed flame duct and grating
    o = box("pad", (0, 0, 0.18), (3.0, 3.0, 0.36), bevel=0.06)
    base.append(tag(o, "base", conc))
    duct = [(0.72, 0.361), (0.62, 0.361), (0.62, 0.12), (0.0, 0.12)]
    o = lathe("duct", duct, 40)
    base.append(tag(o, "base", conc))
    for i in range(-3, 4):
        o = box(f"grate{i}", (i * 0.16, 0, 0.3), (0.05, 1.24, 0.06))
        base.append(tag(o, "base", steel))
    o = lathe("duct_ring", [(0.78, 0.36), (0.78, 0.4), (0.64, 0.4), (0.64, 0.36)], 40, closed=True)
    base.append(tag(o, "base", red))
    # anchor bolts
    for sx in (-1.32, 1.32):
        for sy in (-1.32, 1.32):
            o = cyl(f"bolt{sx}{sy}", 0.06, 0.36, 0.44, 8, sx, sy)
            base.append(tag(o, "base", steel))

    # ---- the tower at the back (+Y): a lattice column with a rail spine facing the spear
    ty = 0.95
    for sx in (-0.28, 0.28):
        for sy in (ty - 0.22, ty + 0.22):
            o = beam(f"leg{sx}{sy}", (sx, sy, 0.36), (sx, sy, 9.4), 0.09, 0.09)
            base.append(tag(o, "base", steel))
    for k in range(18):
        z0, z1 = 0.36 + k * 0.5, 0.36 + (k + 1) * 0.5
        for (a, b) in (((-0.28, ty - 0.22), (0.28, ty - 0.22)), ((-0.28, ty + 0.22), (0.28, ty + 0.22)),
                       ((-0.28, ty - 0.22), (-0.28, ty + 0.22)), ((0.28, ty - 0.22), (0.28, ty + 0.22))):
            p0 = (a[0], a[1], z0) if k % 2 == 0 else (b[0], b[1], z0)
            p1 = (b[0], b[1], z1) if k % 2 == 0 else (a[0], a[1], z1)
            o = beam(f"brace{k}{a}{b}", p0, p1, 0.035, 0.035)
            base.append(tag(o, "base", steel))
        o = box(f"ring{k}", (0, ty, z0), (0.64, 0.52, 0.04))
        base.append(tag(o, "base", steel_r if k % 3 == 0 else steel))
    # rail spine and its guide strip
    o = box("spine", (0, ty - 0.38, 4.9), (0.2, 0.14, 9.0))
    base.append(tag(o, "base", steel))
    o = box("guide", (0, ty - 0.46, 4.9), (0.06, 0.03, 8.8))
    base.append(tag(o, "base", glow))
    # cap and beacon
    o = box("cap", (0, ty, 9.45), (0.7, 0.6, 0.12), bevel=0.02)
    base.append(tag(o, "base", red))
    o = cyl("beacon_post", 0.05, 9.5, 9.8, 8, 0, ty)
    base.append(tag(o, "base", steel))
    o = lathe("beacon_lamp", [(0.0, 9.8), (0.09, 9.82), (0.1, 9.9), (0.07, 9.98), (0.0, 10.0)], 16)
    beacon.append(tag(o, "beacon", glow, smooth=True))
    # hydraulic struts from the pad to the tower
    for sx in (-1.0, 1.0):
        o = beam(f"strut{sx}", (sx * 1.2, -0.9, 0.4), (sx * 0.3, ty - 0.1, 3.4), 0.08, 0.08)
        base.append(tag(o, "base", steel))
        o = beam(f"rod{sx}", (sx * 0.85, -0.25, 1.5), (sx * 0.3, ty - 0.1, 3.4), 0.04, 0.04)
        base.append(tag(o, "base", red))
        o = box(f"foot{sx}", (sx * 1.2, -0.9, 0.42), (0.26, 0.26, 0.1))
        base.append(tag(o, "base", steel))
    # equipment boxes and cable trays on the pad
    for i, (x, y, w, d, h) in enumerate(((-1.1, 1.05, 0.6, 0.5, 0.55), (1.1, 1.05, 0.55, 0.55, 0.7), (1.15, -1.05, 0.45, 0.4, 0.4))):
        o = box(f"equip{i}", (x, y, 0.36 + h / 2), (w, d, h), bevel=0.02)
        base.append(tag(o, "base", steel_r if i == 1 else steel))
        o = box(f"lamp{i}", (x, y - d / 2 - 0.005, 0.36 + h * 0.75), (w * 0.5, 0.02, 0.05))
        base.append(tag(o, "base", glow))
    o = beam("tray", (-1.1, 0.8, 0.42), (-0.25, 0.75, 0.42), 0.18, 0.06)
    base.append(tag(o, "base", dark))

    # ---- three clamp arms holding the spear (they swing open at launch: part "clamps", pivot on the spine)
    for k, z in enumerate((1.7, 4.0, 6.3)):
        o = box(f"arm{k}", (0, 0.32, z), (0.12, 0.5, 0.12))
        clamps.append(tag(o, "clamps", red))
        prof = [(0.33, z - 0.07), (0.38, z - 0.07), (0.38, z + 0.07), (0.33, z + 0.07)]
        o = lathe(f"clampring{k}", prof, 24, a0=math.radians(-200), a1=math.radians(20), closed=True, cap=True)
        clamps.append(tag(o, "clamps", steel))
    # umbilical: an arm from the tower to the spear's motor section with a cable bundle
    o = beam("umb_arm", (0.0, ty - 0.4, 2.6), (0.0, 0.32, 2.4), 0.14, 0.1)
    umb.append(tag(o, "umbilical", steel))
    o = box("umb_plug", (0, 0.27, 2.4), (0.18, 0.08, 0.16))
    umb.append(tag(o, "umbilical", red))
    for j in range(3):
        o = beam(f"cable{j}", (0.05 * (j - 1), ty - 0.4, 2.45 - j * 0.04), (0.05 * (j - 1), 0.3, 2.3 - j * 0.02), 0.025, 0.025)
        umb.append(tag(o, "umbilical", dark))

    parts = ["base", "clamps", "umbilical", "beacon"]
    allo = base + clamps + umb + beacon
    stamp(allo, parts)
    mats = [steel, steel_r, red, conc, glow, dark]
    o = join(allo, "launcher")
    unwrap(o, 0.003)
    imgs = bake(o, mats, 2048, f"{assets}/textures/krl/launcher.png", f"{assets}/textures/krl/launcher_glow.png")
    export(o, parts, {"clamps": (0, ty - 0.38, 0), "umbilical": (0, ty - 0.4, 2.6), "beacon": (0, ty, 9.9)}, f"{assets}/models/krl/launcher.json")
    pm = preview_material("krl_prev_l", imgs["color"], imgs["glow"], 5.0)
    preview(o, pm, f"{out}/krl_launcher.png", (7.5, -9.0, 6.0), (0, 0, 4.2), lens=45)
    return o


# ================================================================================================ the spear

SPEAR_L = 7.0


def spear_profile():
    """(radius, z) from the nozzle exit to the tip."""
    pts = [(0.0, 0.0), (0.27, 0.0), (0.24, 0.12), (0.18, 0.3), (0.16, 0.34), (0.3, 0.38), (0.3, 1.5), (0.27, 1.62),
           (0.235, 1.7), (0.235, 4.9), (0.25, 5.0), (0.25, 5.25), (0.23, 5.35)]
    # a long ogive to a sharp tip
    for k in range(1, 15):
        f = k / 14
        z = 5.35 + f * (SPEAR_L - 5.35)
        r = 0.23 * (1 - f) ** 0.62
        pts.append((max(r, 0.0), z))
    return pts


def build_spear(assets, out):
    reset()
    steel = steel_mat("krl_s_steel", 4.0)
    red = red_mat("krl_s_red")
    tung = tungsten_mat("krl_s_tung")
    noz = flat_mat("krl_s_noz", (0.02, 0.018, 0.017), glow=(0.25, 0.02, 0.01))
    glow = glow_mat("krl_s_glow", RED, 1.0)
    body, nose, nozzle = [], [], []
    prof = spear_profile()
    nose_from = next(i for i, p in enumerate(prof) if p[1] >= 5.35)

    def mat_of(i):
        z = prof[i][1]
        if z < 0.36:
            return 0
        if 1.5 <= z <= 1.7 or 4.85 <= z <= 5.3:
            return 2
        return 1

    o = lathe("spear_body", prof[:nose_from + 1], 32, mat_of_edge=mat_of)
    body.append(tag(o, "body", [noz, steel, red], smooth=True))
    o = lathe("spear_nose", prof[nose_from:], 32)
    nose.append(tag(o, "nose", tung, smooth=True))
    # the nozzle throat glows faintly (separate part so the game can fire it up)
    o = lathe("throat", [(0.0, 0.05), (0.2, 0.05), (0.15, 0.26), (0.0, 0.26)], 24)
    nozzle.append(tag(o, "nozzle", glow))
    # red bands
    for z in (2.6, 3.4, 4.2):
        o = lathe(f"band{z}", [(0.237, z), (0.242, z + 0.01), (0.242, z + 0.07), (0.237, z + 0.08)], 32)
        body.append(tag(o, "body", red))
    # cruciform tail fins on the motor section
    fin = [(0.28, 0.42), (0.86, 0.3), (0.86, 0.6), (0.28, 1.35)]
    for k in range(4):
        o = extrude_outline(f"fin{k}", fin, 0.035)
        o.rotation_euler = (0, 0, math.radians(45 + 90 * k))
        active(o)
        bpy.ops.object.transform_apply(rotation=True)
        body.append(tag(o, "body", [steel, red]))
    # four long low strakes along the rod
    strake = [(0.235, 2.2), (0.31, 2.4), (0.31, 4.4), (0.235, 4.7)]
    for k in range(4):
        o = extrude_outline(f"strake{k}", strake, 0.02)
        o.rotation_euler = (0, 0, math.radians(90 * k))
        active(o)
        bpy.ops.object.transform_apply(rotation=True)
        body.append(tag(o, "body", [steel, steel]))
    # glowing seams
    for z in (1.66, 5.02):
        o = lathe(f"seam{z}", [(0.24, z), (0.252, z), (0.252, z + 0.015), (0.24, z + 0.015)], 32, closed=True)
        body.append(tag(o, "body", glow))

    parts = ["body", "nose", "nozzle"]
    allo = body + nose + nozzle
    stamp(allo, parts)
    o = join(allo, "spear")
    unwrap(o, 0.003)
    imgs = bake(o, [steel, red, tung, noz, glow], 1024, f"{assets}/textures/krl/spear.png", f"{assets}/textures/krl/spear_glow.png")
    export(o, parts, {}, f"{assets}/models/krl/spear.json")
    pm = preview_material("krl_prev_s", imgs["color"], imgs["glow"], 4.0)
    preview(o, pm, f"{out}/krl_spear.png", (4.5, -4.5, 4.6), (0, 0, 3.4), lens=40)
    return o


# ================================================================================================ the fire-control console

def build_console(assets, out):
    reset()
    steel = steel_mat("krl_c_steel", 3.0)
    red = red_mat("krl_c_red")
    panel = panel_mat("krl_c_panel")
    scr = screen_mat("krl_c_screen")
    dial = dial_mat("krl_c_dial")
    glow = glow_mat("krl_c_glow", RED, 1.0)
    amber = glow_mat("krl_c_amber", (1.0, 0.45, 0.05), 1.0)
    white = glow_mat("krl_c_white", (0.95, 0.9, 0.85), 0.7)
    dark = flat_mat("krl_c_dark", (0.01, 0.01, 0.01))
    glass = flat_mat("krl_c_glass", (0.25, 0.04, 0.03), glow=(0.12, 0.01, 0.005))
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
        body.append(tag(o, "body", [steel, red]))
    # cabinet front: louvres and a stencilled red band
    for i in range(10):
        o = box(f"louvre{i}", (-1.2 + i * 0.12, -0.415, 0.3), (0.07, 0.02, 0.32))
        body.append(tag(o, "body", dark))
    o = box("band", (0, -0.413, 0.66), (W - 0.06, 0.012, 0.06))
    body.append(tag(o, "body", red))

    # ---- the slanted control deck
    tilt = math.radians(24)
    deck_c = Vector((0, -0.05, 0.86))
    o = box("deck", tuple(deck_c), (W - 0.04, 0.8, 0.06))
    o.rotation_euler = (tilt, 0, 0)
    active(o)
    bpy.ops.object.transform_apply(rotation=True)
    body.append(tag(o, "body", panel))

    def on_deck(x, v, h=0.0):
        """A point on the deck: x across, v from front (-0.38) to back (0.38), h above the surface."""
        n = Vector((0, -math.sin(tilt), math.cos(tilt)))
        t = Vector((0, math.cos(tilt), math.sin(tilt)))
        return deck_c + Vector((x, 0, 0)) + t * v + n * (0.03 + h)

    def deck_obj(o, x, v, h=0.0):
        o.rotation_euler = (tilt, 0, 0)
        o.location = on_deck(x, v, h)
        active(o)
        bpy.ops.object.transform_apply(location=True, rotation=True)
        return o

    # three analog gauges (bezel, face, needle)
    for i, x in enumerate((-1.0, -0.62, -0.24)):
        bz = lathe(f"bezel{i}", [(0.0, 0.0), (0.15, 0.0), (0.15, 0.03), (0.13, 0.035), (0.13, 0.01), (0.0, 0.01)], 28)
        body.append(tag(deck_obj(bz, x, 0.18), "body", steel, smooth=True))
        fc = cyl(f"face{i}", 0.128, 0.0, 0.012, 28)
        body.append(tag(deck_obj(fc, x, 0.18, 0.005), "body", dial))
        nd = box(f"needle{i}", (0.0, 0.05, 0.02), (0.012, 0.1, 0.006))
        nd.rotation_euler = (0, 0, math.radians(-40 + 35 * i))
        active(nd)
        bpy.ops.object.transform_apply(rotation=True)
        body.append(tag(deck_obj(nd, x, 0.18, 0.012), "body", glow))
    # rows of guarded toggle switches with lit legends
    for r_ in range(2):
        for i in range(8):
            x = 0.12 + i * 0.1
            v = -0.06 - r_ * 0.15
            b = box(f"tg{r_}{i}", (0, 0, 0.015), (0.05, 0.05, 0.03))
            body.append(tag(deck_obj(b, x, v), "body", steel))
            lv = beam(f"lever{r_}{i}", (0, 0, 0.03), (0, -0.03 if (i + r_) % 3 else 0.03, 0.09), 0.012, 0.012)
            body.append(tag(deck_obj(lv, x, v), "body", white if (i + r_) % 3 else red))
            lg = box(f"legend{r_}{i}", (0, -0.045, 0.002), (0.06, 0.016, 0.004))
            body.append(tag(deck_obj(lg, x, v), "body", amber if (i * 3 + r_) % 5 == 0 else glow))
    # push-button matrix (left front)
    for r_ in range(3):
        for i in range(6):
            b = lathe(f"pb{r_}{i}", [(0.0, 0.0), (0.03, 0.0), (0.03, 0.02), (0.026, 0.028), (0.0, 0.03)], 12)
            m_ = glow if (r_ + i) % 4 == 0 else amber if (r_ * 2 + i) % 7 == 0 else white
            body.append(tag(deck_obj(b, -1.25 + i * 0.09, -0.08 - r_ * 0.085), "body", m_, smooth=True))
    # slide faders
    for i in range(4):
        t_ = box(f"track{i}", (0, 0, 0.004), (0.02, 0.26, 0.008))
        body.append(tag(deck_obj(t_, -0.56 + i * 0.08, -0.17), "body", dark))
        k_ = box(f"knob{i}", (0, 0, 0.02), (0.05, 0.035, 0.03))
        body.append(tag(deck_obj(k_, -0.56 + i * 0.08, -0.25 + i * 0.05), "body", red))
    # the key switch and the big launch button under its flip cover
    ks = lathe("keysw", [(0.0, 0.0), (0.05, 0.0), (0.05, 0.03), (0.0, 0.03)], 16)
    body.append(tag(deck_obj(ks, 0.98, 0.12), "body", steel, smooth=True))
    key = box("key", (0, 0, 0.05), (0.012, 0.05, 0.04))
    body.append(tag(deck_obj(key, 0.98, 0.12), "body", white))
    btn = lathe("launch_btn", [(0.0, 0.0), (0.11, 0.0), (0.11, 0.03), (0.09, 0.05), (0.07, 0.065), (0.0, 0.07)], 24)
    body.append(tag(deck_obj(btn, 1.15, -0.12), "body", glow, smooth=True))
    ring = lathe("launch_ring", [(0.12, 0.0), (0.15, 0.0), (0.15, 0.015), (0.12, 0.015)], 24, closed=True)
    body.append(tag(deck_obj(ring, 1.15, -0.12), "body", red))
    cv = box("cover", (0, 0, 0.1), (0.3, 0.3, 0.008))
    cover.append(tag(deck_obj(cv, 1.15, -0.12), "cover", glass))
    for sx in (-1, 1):
        sw = box(f"cover_side{sx}", (sx * 0.15, 0, 0.05), (0.008, 0.3, 0.1))
        cover.append(tag(deck_obj(sw, 1.15, -0.12), "cover", glass))
    # hazard stripes round the launch area
    for i in range(6):
        st = box(f"haz{i}", (0, 0, 0.001), (0.035, 0.36, 0.002))
        st.rotation_euler = (0, 0, math.radians(35))
        active(st)
        bpy.ops.object.transform_apply(rotation=True)
        body.append(tag(deck_obj(st, 0.96 + i * 0.075, -0.12), "body", red))

    # ---- the upright board behind the deck
    bz0, bz1 = 1.06, 1.72
    o = box("board", (0, 0.3, (bz0 + bz1) / 2), (W - 0.04, 0.1, bz1 - bz0))
    body.append(tag(o, "body", steel))
    # three screens with hoods
    for i, x in enumerate((-0.95, 0.0, 0.95)):
        s_ = box(f"screen{i}", (x, 0.244, 1.36), (0.78, 0.01, 0.42))
        body.append(tag(s_, "body", scr))
        for sz in (1.585, 1.135):
            h = box(f"hood{i}{sz}", (x, 0.2, sz), (0.84, 0.1, 0.03))
            body.append(tag(h, "body", steel))
        for sx in (-1, 1):
            h = box(f"hoodside{i}{sx}", (x + sx * 0.41, 0.2, 1.36), (0.03, 0.1, 0.48))
            body.append(tag(h, "body", steel))
    # annunciator tiles above the screens: a 2 x 24 grid of lit legends
    for r_ in range(2):
        for i in range(24):
            t_ = box(f"ann{r_}{i}", (-1.38 + i * 0.12, 0.243, 1.64 + r_ * 0.045), (0.1, 0.008, 0.035))
            ann.append(tag(t_, "annunciators", glow if (i * 7 + r_ * 3) % 5 else amber))
    # title strip
    t_ = text_mesh("title", "KRL-1  KINETIC RAIL LAUNCHER  //  FIRE CONTROL", 0.05, (0, 0.245, 1.11), (math.radians(90), 0, 0), extrude=0.003)
    body.append(tag(t_, "body", white))
    # cable conduits on the back
    for i in range(3):
        o = beam(f"conduit{i}", (-1.2 + i * 1.2, 0.38, 0.1), (-1.2 + i * 1.2, 0.38, 1.6), 0.06, 0.06)
        body.append(tag(o, "body", dark))

    parts = ["body", "cover", "annunciators"]
    allo = body + cover + ann
    stamp(allo, parts)
    o = join(allo, "console")
    unwrap(o, 0.002)
    imgs = bake(o, [steel, red, panel, scr, dial, glow, amber, white, dark, glass], 2048,
                f"{assets}/textures/krl/console.png", f"{assets}/textures/krl/console_glow.png")
    hinge = on_deck(1.15, 0.03, 0.0)
    export(o, parts, {"cover": tuple(hinge)}, f"{assets}/models/krl/console.json")
    pm = preview_material("krl_prev_c", imgs["color"], imgs["glow"], 5.0)
    preview(o, pm, f"{out}/krl_console.png", (1.6, -3.6, 2.4), (0, 0, 0.9), lens=40)
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
    build_launcher(ARGS.assets, ARGS.out)
    build_spear(ARGS.assets, ARGS.out)
    build_console(ARGS.assets, ARGS.out)
    print("[krl] done")


if __name__ == "__main__":
    main()
