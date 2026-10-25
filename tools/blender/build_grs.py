"""
Gungnir Railway Station (GRS) - built and textured in headless Blender (run by tools/blender/build.sh).

Every model is modelled from scratch out of lathed profiles, lofted cross-sections and extruded outlines, unwrapped,
and its textures are BAKED in Cycles from procedural materials (an albedo atlas and an orange glow mask). The meshes
are exported as "grs_mesh": named parts with per-corner [x, y, z, u, v, nx, ny, nz] in Minecraft axes (y up), in blocks.

  station   GRS-01: a black hub with a launch barrel aimed at Earth, the "railway" - a 120-block C-channel ring the spear
            laps around - with 36 accelerator clamps, six truss spokes, radiator fins, habitat modules, solar wings,
            a turning comms dish and running lights
  spear     Gungnir: 179 blocks from tip to pommel. Leaf blade with molten edges and spine, swept crossguard, a fluted
            shaft with glowing channels and bands, a spiked pommel, and three floating rings (part "rings")
  goggles   the Gungnir goggles: strap band, twin orange lenses, bridge sensor, side uplink module and antenna
Also renders the goggles item icon and preview renders into --out.
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

ORANGE = (1.0, 0.42, 0.06)
HOT = (1.0, 0.62, 0.2)


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
    return o


def tag(obj, part, mats, smooth=False):
    """Remember which part a primitive belongs to and give it its material slots."""
    obj["grs_part"] = part
    obj.data.materials.clear()
    for m in (mats if isinstance(mats, (list, tuple)) else [mats]):
        obj.data.materials.append(m.m)
    active(obj)
    if smooth:
        bpy.ops.object.shade_smooth()
    else:
        bpy.ops.object.shade_flat()
    return obj


def stamp(objs, parts):
    for o in objs:
        me = o.data
        me.attributes.new("grs_part", "INT", "FACE")
        pa = me.attributes["grs_part"]
        pi = parts.index(o["grs_part"])
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
    o = link_obj(name, bm)
    fix_normals(o)
    return o


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
    o = link_obj(name, bm)
    fix_normals(o)
    return o


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
    o = link_obj(name, bm)
    fix_normals(o)
    return o


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
        col = m.mix(col, (0.62, 0.22, 0.03), m.math("MULTIPLY", hz, stripes))
    if lights > 0:
        win = m.math("GREATER_THAN", m.noise(scale * 9, 1, 0.3), 0.74)
        win = m.math("MULTIPLY", win, m.math("SUBTRACT", 1.0, seams))
        win = m.math("MULTIPLY", win, m.math("GREATER_THAN", m.noise(0.6, 2, 0.5), 0.5))
        col = m.mix(col, (0.9, 0.5, 0.15), m.math("MULTIPLY", win, lights))
        glow = m.ramp(m.math("MULTIPLY", win, lights), [(0.0, (0, 0, 0)), (1.0, HOT)])
    m.color(col)
    if glow is not None:
        m.glow(glow)
    return m


def paint_mat(name, base=(0.78, 0.27, 0.03)):
    m = Mat(name)
    n1 = m.noise(1.2, 6, 0.6)
    n2 = m.noise(9.0, 3, 0.5)
    col = m.ramp(n1, [(0.3, tuple(c * 0.55 for c in base)), (0.7, base)])
    col = m.mix(col, (0.03, 0.02, 0.015), m.math("MULTIPLY", m.math("GREATER_THAN", n2, 0.7), 0.6))
    col = m.mix(col, (0, 0, 0), m.math("MULTIPLY", m.math("SUBTRACT", 1.0, m.ao(0.8)), 0.55, True))
    m.color(col)
    return m


def glow_mat(name, color=ORANGE, k=1.0, pulse_scale=0.0):
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
    lines = m.math("LESS_THAN", vor, 0.018)
    ember = m.math("MULTIPLY", lines, m.math("GREATER_THAN", n1, 0.5))
    col = m.mix(col, (0.5, 0.17, 0.03), ember)
    m.color(col)
    m.glow(m.ramp(ember, [(0.0, (0, 0, 0)), (1.0, (0.85, 0.32, 0.05))]))
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
            tex = mt.nt.nodes.get("grs_bake") or mt.nt.nodes.new("ShaderNodeTexImage")
            tex.name = "grs_bake"
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
    """grs_mesh: parts with per-corner [x,y,z,u,v,nx,ny,nz] in Minecraft axes ((x,y,z) -> (x,z,-y))."""
    me = obj.data
    uv = me.uv_layers.active.data
    pa = me.attributes["grs_part"].data
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
    js = {"format": "grs_mesh", "stride": 8, "parts": []}
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

RING_R = 56.0


def build_station(assets, out):
    reset()
    hull = hull_mat("grs_hull", 0.3, lights=0.0, stripes=0.0)
    hull_lit = hull_mat("grs_hull_lit", 0.45, lights=1.0, stripes=0.8)
    paint = paint_mat("grs_paint")
    glow = glow_mat("grs_glow", ORANGE, 1.0, pulse_scale=0.4)
    hot = glow_mat("grs_hot", HOT, 1.0)
    solar = solar_mat("grs_solar")
    objs = []

    # ---- the railway: a C-channel ring, open toward Earth, that the spear laps around
    prof = [(52.0, 4.2), (60.0, 4.2), (60.6, 3.4), (60.6, -3.6), (58.2, -4.2), (58.2, 2.6), (53.8, 2.6), (53.8, -4.2), (51.4, -3.6), (51.4, 3.4)]
    o = lathe("rail", prof, 192, closed=True, mat_of_edge=lambda i: 1 if i in (5,) else 0)
    objs.append(tag(o, "body", [hull, glow]))
    # inner guide rails inside the channel (glowing magnetic strips)
    for r in (54.4, 57.6):
        o = lathe("guide", [(r - 0.3, 2.6), (r + 0.3, 2.6), (r + 0.3, 1.9), (r - 0.3, 1.9)], 192, closed=True)
        objs.append(tag(o, "body", [hot]))
    # 36 accelerator clamps around the channel
    for k in range(36):
        a = k / 36 * math.tau
        da = math.radians(1.6)
        cp = [(50.4, 5.2), (61.6, 5.2), (61.6, -5.0), (59.4, -5.0), (59.4, -4.4), (60.9, -4.4), (60.9, 4.6), (51.1, 4.6), (51.1, -4.4),
              (52.6, -4.4), (52.6, -5.0), (50.4, -5.0)]
        o = lathe("clamp", cp, 3, a - da, a + da, closed=True, cap=True,
                  mat_of_edge=lambda i: 0)
        objs.append(tag(o, "body", [paint if k % 3 == 0 else hull]))
        # coil glow band on each clamp
        o = lathe("coil", [(61.7, 1.2), (61.9, 1.2), (61.9, -1.2), (61.7, -1.2)], 2, a - da * 0.8, a + da * 0.8, closed=True, cap=True)
        objs.append(tag(o, "body", [glow]))
    # running lights on the rim
    for k in range(24):
        a = (k + 0.5) / 24 * math.tau
        o = box("lamp", (math.cos(a) * 60.9, math.sin(a) * 60.9, 4.4), (0.7, 0.7, 0.5), a)
        objs.append(tag(o, "body", [hot]))

    # ---- six truss spokes
    for k in range(6):
        a = k / 6 * math.tau + math.pi / 6
        d = Vector((math.cos(a), math.sin(a), 0))
        s = Vector((-d.y, d.x, 0))
        for side in (-1, 1):
            p0 = d * 10.5 + s * side * 1.4
            p1 = d * 51.4 + s * side * 1.4
            objs.append(tag(beam("spar", p0 + Vector((0, 0, 1.0)), p1 + Vector((0, 0, 1.0)), 0.8, 0.8), "body", [hull]))
            objs.append(tag(beam("spar", p0 + Vector((0, 0, -1.0)), p1 + Vector((0, 0, -1.0)), 0.8, 0.8), "body", [hull]))
        # zig-zag bracing
        n = 12
        for i in range(n):
            t0, t1 = i / n, (i + 1) / n
            r0, r1 = 10.5 + (51.4 - 10.5) * t0, 10.5 + (51.4 - 10.5) * t1
            sd = 1 if i % 2 == 0 else -1
            objs.append(tag(beam("brace", d * r0 + s * 1.4 * sd, d * r1 - s * 1.4 * sd, 0.35, 0.35), "body", [hull]))
            objs.append(tag(beam("brace", d * r0 + Vector((0, 0, 1.0 * sd)), d * r1 - Vector((0, 0, 1.0 * sd)), 0.35, 0.35), "body", [hull]))
        # a glowing conduit along the spoke
        objs.append(tag(beam("conduit", d * 11 + Vector((0, 0, -1.25)), d * 51 + Vector((0, 0, -1.25)), 0.3, 0.3), "body", [glow]))

    # ---- the hub, with its launch barrel pointing at Earth (down)
    hp = [(0.0, -27.0), (2.4, -27.0), (2.4, -35.0), (3.6, -35.0), (4.8, -33.4), (4.8, -23.5), (6.4, -22.4), (6.4, -13.6), (9.6, -12.4), (10.8, -9.4),
          (10.8, 5.2), (9.2, 7.6), (9.2, 13.4), (6.8, 15.2), (5.2, 21.0), (2.6, 23.6), (0.0, 24.2)]
    glow_edges = {1}  # the bore of the barrel

    def hub_mat(i):
        if i in glow_edges:
            return 1
        if i in (6, 12):
            return 2
        return 0

    o = lathe("hub", hp, 64, mat_of_edge=hub_mat)
    objs.append(tag(o, "body", [hull_lit, glow, paint]))
    # barrel rings and glowing capacitor bands
    for z, r in ((-33.0, 5.3), (-29.5, 5.3), (-26.0, 5.3), (-19.5, 6.9), (-16.0, 6.9)):
        o = lathe("bring", [(r - 0.8, z - 0.6), (r, z - 0.6), (r, z + 0.6), (r - 0.8, z + 0.6)], 48, closed=True)
        objs.append(tag(o, "body", [hull]))
        o = lathe("bglow", [(r + 0.02, z - 0.18), (r + 0.12, z - 0.18), (r + 0.12, z + 0.18), (r + 0.02, z + 0.18)], 48, closed=True)
        objs.append(tag(o, "body", [glow]))
    # muzzle crown: six fangs around the barrel mouth
    for k in range(6):
        a = k / 6 * math.tau
        pts = [(3.4, 0.0), (5.6, 0.0), (4.6, -5.2), (3.6, -3.6)]
        o = extrude_outline("fang", pts, 0.9, rim_mat=1, face_mat=0)
        o.location = (0, 0, -35.0)
        o.rotation_euler = (0, 0, a)
        active(o)
        bpy.ops.object.transform_apply(location=True, rotation=True, scale=False)
        objs.append(tag(o, "body", [hull, glow]))
    # habitat modules
    for k in range(4):
        a = k / 4 * math.tau + math.pi / 4
        bpy.ops.mesh.primitive_cylinder_add(vertices=16, radius=2.3, depth=9.0, location=(math.cos(a) * 15.0, math.sin(a) * 15.0, -1.5),
                                            rotation=(0, math.pi / 2, a))
        o = last()
        b = o.modifiers.new("b", "BEVEL")
        b.width = 0.35
        b.segments = 2
        apply_mods(o)
        objs.append(tag(o, "body", [hull_lit]))
        objs.append(tag(beam("neck", (math.cos(a) * 10.5, math.sin(a) * 10.5, -1.5), (math.cos(a) * 11.0, math.sin(a) * 11.0, -1.5), 2.0, 2.0),
                        "body", [hull]))
    # radiator fins on the upper drum
    for k in range(4):
        a = k / 4 * math.tau
        d = Vector((math.cos(a), math.sin(a), 0))
        s = Vector((-d.y, d.x, 0))
        o = beam("radiator", d * 9.0 + Vector((0, 0, 10.4)), d * 25.0 + Vector((0, 0, 10.4)), 0.35, 5.2)
        objs.append(tag(o, "body", [hull]))
        for j in range(6):
            r = 11.0 + j * 2.4
            objs.append(tag(beam("heat", d * r + s * 0.2 + Vector((0, 0, 8.0)), d * r + s * 0.2 + Vector((0, 0, 12.8)), 0.18, 0.12), "body", [glow]))
    # solar wings on a mast above the hub
    mast_z = 19.0
    objs.append(tag(beam("mast", (0, -96, mast_z), (0, 96, mast_z), 1.2, 1.2), "body", [hull]))
    for side in (-1, 1):
        for j in range(3):
            y0 = side * (66 + j * 10)
            o = box("panel", (0, y0, mast_z), (26.0, 8.6, 0.25))
            objs.append(tag(o, "body", [solar]))
            o = box("panel_frame", (0, y0, mast_z - 0.25), (26.8, 9.2, 0.18))
            objs.append(tag(o, "body", [hull]))
    # comms dish on top (turns)
    dish = lathe("dish", [(0.0, 0.0), (1.2, 0.2), (4.0, 1.2), (6.4, 3.0), (6.8, 3.4), (6.4, 3.4), (3.8, 1.8), (1.0, 0.8), (0.0, 0.6)], 40, closed=False)
    dish.location = (7.0, 0, 26.0)
    dish.rotation_euler = (0, math.radians(-35), 0)
    active(dish)
    bpy.ops.object.transform_apply(location=True, rotation=True)
    dish_objs = [tag(dish, "dish", [hull])]
    dish_objs.append(tag(beam("dish_arm", (0, 0, 24.0), (7.0, 0, 26.5), 0.8, 0.8), "dish", [hull]))
    dish_objs.append(tag(beam("feed", (7.0, 0, 26.5), (8.8, 0, 30.2), 0.3, 0.3), "dish", [hull]))
    bpy.ops.mesh.primitive_uv_sphere_add(segments=12, ring_count=6, radius=0.5, location=(8.9, 0, 30.4))
    dish_objs.append(tag(last(), "dish", [hot], smooth=True))
    objs += dish_objs
    # antenna mast with a beacon
    objs.append(tag(beam("antenna", (0, 0, 24.0), (0, 0, 36.0), 0.5, 0.5), "body", [hull]))
    bpy.ops.mesh.primitive_uv_sphere_add(segments=12, ring_count=6, radius=0.8, location=(0, 0, 36.4))
    objs.append(tag(last(), "body", [hot], smooth=True))
    # name plates: GRS-01 on two sides of the hub
    for k in range(2):
        a = k * math.pi
        d = Vector((math.cos(a), math.sin(a), 0))
        plate = box("plate", d * 10.95 + Vector((0, 0, -1.5)), (0.3, 9.6, 4.2), a, 0.1)
        objs.append(tag(plate, "body", [paint]))
        t = text_mesh("name", "GRS-01", 2.0, d * 11.15 + Vector((0, 0, -1.5)), (math.pi / 2, 0, a + math.pi / 2), 0.1)
        objs.append(tag(t, "body", [glow]))
        t = text_mesh("name2", "GUNGNIR", 1.1, d * 11.15 + Vector((0, 0, 1.6)), (math.pi / 2, 0, a + math.pi / 2), 0.08)
        objs.append(tag(t, "body", [hot]))

    parts = ["body", "dish"]
    stamp(objs, parts)
    o = join(objs, "station")
    unwrap(o, 0.0015)
    tex = os.path.join(assets, "textures/grs")
    imgs = bake(o, [hull, hull_lit, paint, glow, hot, solar], 4096, os.path.join(tex, "station.png"), os.path.join(tex, "station_glow.png"))
    export(o, parts, {"dish": (0, 0, 24.0)}, os.path.join(assets, "models/grs/station.json"))
    pm = preview_material("pv", imgs["color"], imgs["glow"], 8)
    preview(o, pm, os.path.join(out, "grs_station.png"), (120, -150, -90), (0, 0, -4), 40, 1100)
    preview(o, pm, os.path.join(out, "grs_station_top.png"), (20, -40, 160), (0, 0, 0), 32, 900)


# ------------------------------------------------------------------------------------------------ the spear

def blade_width(z):
    """Leaf blade: 0 at the tip, widest around z=19, narrowing into the socket at z=32."""
    if z <= 0:
        return 0.0
    if z < 20:
        k = z / 20
        return 7.2 * math.sin(k * math.pi / 2) ** 0.8
    k = (z - 20) / 12
    return 7.2 + (2.5 - 7.2) * (k * k * (3 - 2 * k))


def build_spear(assets, out):
    reset()
    black = obsidian_mat("gn_black")
    edge = glow_mat("gn_edge", HOT, 1.0, pulse_scale=0.08)
    vein = glow_mat("gn_vein", ORANGE, 1.0)
    band = paint_mat("gn_band", (0.12, 0.05, 0.02))
    objs = []

    # ---- blade: lofted 10-point cross-sections; molten edges and spine
    secs = []
    z = 0.0
    zs = [0.0, 0.25, 0.6, 1.0, 1.6]
    while zs[-1] < 32:
        zs.append(min(32.0, zs[-1] + 0.8))
    for z in zs:
        w = max(blade_width(z), 0.02)
        t = 0.35 + 0.16 * w
        if z < 0.3:
            t = 0.02
        pts = [(w, 0), (0.88 * w, 0.18 * t), (0.45 * w, 0.75 * t), (0, t), (-0.45 * w, 0.75 * t), (-0.88 * w, 0.18 * t),
               (-w, 0), (-0.88 * w, -0.18 * t), (-0.45 * w, -0.75 * t), (0, -t), (0.45 * w, -0.75 * t), (0.88 * w, -0.18 * t)]
        secs.append((z, pts))

    def blade_mat(s, i, z):
        if i in (0, 5, 6, 11):
            return 1  # the edges
        if i in (2, 3, 8, 9) and 5.0 < z < 27.0:
            return 2  # the spine channel
        return 0

    o = loft("blade", secs, closed_caps=False, mat_of_face=blade_mat)
    objs.append(tag(o, "spear", [black, edge, vein]))

    # ---- socket / collar
    cp = [(2.5, 31.8), (3.5, 32.6), (3.5, 34.8), (2.9, 35.6), (3.3, 36.2), (4.3, 37.2), (4.3, 40.4), (3.4, 41.4), (2.3, 43.0), (2.3, 44.4)]
    o = lathe("collar", cp, 40, mat_of_edge=lambda i: 1 if i in (3,) else (2 if i == 5 else 0))
    objs.append(tag(o, "spear", [black, vein, band]))
    # crossguard: two swept wings with glowing rims
    wing = [(3.0, 34.0), (7.0, 33.4), (12.6, 29.0), (14.2, 26.4), (13.0, 30.8), (10.4, 35.6), (6.0, 39.6), (3.0, 40.2)]
    for side in (1, -1):
        pts = [(x * side, zz) for x, zz in wing]
        if side < 0:
            pts = list(reversed(pts))
        o = extrude_outline("wing", pts, 1.1, rim_mat=1, face_mat=0)
        objs.append(tag(o, "spear", [black, edge]))
        # inner rib
        rib = [(x * side * 0.82 + side * 0.6, zz + 0.2) for x, zz in wing[1:6]]
        o = beam("rib", (rib[0][0], 0, rib[0][1]), (rib[3][0], 0, rib[3][1]), 0.35, 1.3)
        objs.append(tag(o, "spear", [vein]))

    # ---- shaft: a fluted 16-sided column; the narrow recessed flutes glow
    zs = [44.4 + i * 2.5 for i in range(int((166.0 - 44.4) / 2.5) + 1)] + [166.0]
    secs = []
    for z in zs:
        r = 1.75 - 0.22 * (z - 44.4) / (166 - 44.4)
        pts = []
        for k in range(16):
            a = k / 16 * math.tau
            rr = r if k % 2 == 0 else r * 0.9
            pts.append((math.cos(a) * rr, math.sin(a) * rr))
        secs.append((z, pts))

    def shaft_mat(s, i, z):
        return 1 if i % 2 == 1 and i % 4 != 3 else 0

    o = loft("shaft", secs, closed_caps=False, mat_of_face=shaft_mat)
    objs.append(tag(o, "spear", [black, vein]))
    # bands along the shaft, each with a glowing inlay ring
    for z in (52.0, 72.0, 96.0, 122.0, 146.0, 160.0):
        r = 1.75 - 0.22 * (z - 44.4) / (166 - 44.4)
        o = lathe("band", [(r, z - 1.2), (r + 0.55, z - 0.9), (r + 0.55, z + 0.9), (r, z + 1.2)], 32, mat_of_edge=lambda i: 0)
        objs.append(tag(o, "spear", [band]))
        o = lathe("band_glow", [(r + 0.56, z - 0.25), (r + 0.62, z - 0.2), (r + 0.62, z + 0.2), (r + 0.56, z + 0.25)], 32)
        objs.append(tag(o, "spear", [vein]))
    # the name, inlaid along the shaft
    t = text_mesh("name", "GUNGNIR", 1.5, (0, -1.72, 108.0), (math.pi / 2, math.pi / 2, 0), 0.06)
    objs.append(tag(t, "spear", [edge]))

    # ---- pommel spike
    pp = [(1.55, 165.6), (2.4, 166.6), (2.4, 168.8), (1.9, 169.4), (2.2, 170.0), (2.2, 171.0), (1.4, 172.4), (0.9, 176.4), (0.0, 179.0)]
    o = lathe("pommel", pp, 24, mat_of_edge=lambda i: 1 if i == 3 else 0)
    objs.append(tag(o, "spear", [black, vein]))

    # ---- three floating rings around the shaft below the guard (part "rings", they spin)
    rings = []
    for k, (R, zz, tilt) in enumerate(((5.2, 50.0, 0.0), (6.6, 57.0, 0.18), (4.4, 64.0, -0.12))):
        bpy.ops.mesh.primitive_torus_add(major_radius=R, minor_radius=0.28, major_segments=64, minor_segments=6, location=(0, 0, zz), rotation=(tilt, 0, 0))
        rings.append(tag(last(), "rings", [edge if k == 1 else vein], smooth=True))
        for j in range(6):
            a = j / 6 * math.tau + k
            bpy.ops.mesh.primitive_cone_add(vertices=4, radius1=0.5, radius2=0.0, depth=1.6,
                                            location=(math.cos(a) * R, math.sin(a) * R, zz + 0.9), rotation=(tilt, 0, a))
            rings.append(tag(last(), "rings", [black]))
    objs += rings

    parts = ["spear", "rings"]
    stamp(objs, parts)
    o = join(objs, "spear")
    unwrap(o, 0.0015)
    tex = os.path.join(assets, "textures/grs")
    imgs = bake(o, [black, edge, vein, band], 4096, os.path.join(tex, "spear.png"), os.path.join(tex, "spear_glow.png"))
    export(o, parts, {"rings": (0, 0, 57.0)}, os.path.join(assets, "models/grs/spear.json"))
    pm = preview_material("pv", imgs["color"], imgs["glow"], 8)
    preview(o, pm, os.path.join(out, "grs_spear.png"), (90, -210, 70), (0, 0, 88), 35, 1100)
    preview(o, pm, os.path.join(out, "grs_spear_head.png"), (26, -40, 30), (0, 0, 22), 40, 900)


# ------------------------------------------------------------------------------------------------ the goggles

def rounded_loop(hx, hy, r, n=6):
    pts = []
    for cx, cy, a0 in ((hx - r, hy - r, 0), (-hx + r, hy - r, 90), (-hx + r, -hy + r, 180), (hx - r, -hy + r, 270)):
        for i in range(n + 1):
            a = math.radians(a0 + 90 * i / n)
            pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
    return pts


def build_goggles(assets, out):
    reset()
    shell = hull_mat("gg_shell", 9.0)
    strap = paint_mat("gg_strap", (0.05, 0.035, 0.03))
    trim = paint_mat("gg_trim")
    lens = glow_mat("gg_lens", HOT, 1.0, pulse_scale=18.0)
    led = glow_mat("gg_led", ORANGE, 1.0)
    objs = []
    EYE = 0.27
    # strap band around the head
    outer, inner = rounded_loop(0.282, 0.282, 0.07), rounded_loop(0.262, 0.262, 0.06)
    bm = bmesh.new()
    zs = (EYE - 0.035, EYE + 0.035)
    ring = []
    for z in zs:
        ring.append([bm.verts.new((x, y, z)) for x, y in outer])
        ring.append([bm.verts.new((x, y, z)) for x, y in inner])
    n = len(outer)
    lo_o, lo_i, hi_o, hi_i = ring
    for i in range(n):
        j = (i + 1) % n
        bm.faces.new((lo_o[i], lo_o[j], hi_o[j], hi_o[i]))
        bm.faces.new((lo_i[j], lo_i[i], hi_i[i], hi_i[j]))
        bm.faces.new((hi_o[i], hi_o[j], hi_i[j], hi_i[i]))
        bm.faces.new((lo_o[j], lo_o[i], lo_i[i], lo_i[j]))
    bm.normal_update()
    o = link_obj("strap", bm)
    fix_normals(o)
    objs.append(tag(o, "goggles", [strap]))
    # faceplate frame joining the lenses
    objs.append(tag(box("frame", (0, -0.285, EYE), (0.46, 0.05, 0.13), 0, 0.012), "goggles", [shell]))
    # the two lenses (housings lathed along -Y)
    for sx in (-0.115, 0.115):
        hp = [(0.0, 0.0), (0.072, 0.0), (0.082, 0.012), (0.082, 0.062), (0.07, 0.078), (0.058, 0.078), (0.058, 0.066), (0.0, 0.066)]
        o = lathe("lens", hp, 24, mat_of_edge=lambda i: 1 if i == 6 else (2 if i == 4 else 0))
        o.rotation_euler = (math.pi / 2, 0, 0)
        o.location = (sx, -0.3, EYE)
        active(o)
        bpy.ops.object.transform_apply(location=True, rotation=True)
        objs.append(tag(o, "goggles", [shell, lens, trim]))
        # orange reticle ticks in the lens glass
        for k in range(4):
            a = k * math.pi / 2 + math.pi / 4
            objs.append(tag(box("tick", (sx + math.cos(a) * 0.045, -0.37, EYE + math.sin(a) * 0.045), (0.012, 0.004, 0.012), 0), "goggles", [led]))
    # bridge sensor
    objs.append(tag(box("bridge", (0, -0.33, EYE + 0.01), (0.05, 0.06, 0.05), 0, 0.006), "goggles", [trim]))
    objs.append(tag(box("eye", (0, -0.362, EYE + 0.012), (0.024, 0.006, 0.024)), "goggles", [led]))
    # uplink module on the right side, with antenna
    objs.append(tag(box("module", (0.3, -0.06, EYE), (0.05, 0.16, 0.11), 0, 0.01), "goggles", [shell]))
    objs.append(tag(box("module_led", (0.326, -0.1, EYE + 0.02), (0.004, 0.05, 0.012)), "goggles", [led]))
    objs.append(tag(beam("antenna", (0.3, 0.0, EYE + 0.05), (0.3, 0.03, EYE + 0.34), 0.014, 0.014), "goggles", [shell]))
    bpy.ops.mesh.primitive_uv_sphere_add(segments=8, ring_count=4, radius=0.022, location=(0.3, 0.031, EYE + 0.35))
    objs.append(tag(last(), "goggles", [led], smooth=True))
    # small emitter on the left
    objs.append(tag(box("emitter", (-0.295, -0.12, EYE), (0.03, 0.08, 0.06), 0, 0.006), "goggles", [trim]))

    parts = ["goggles"]
    stamp(objs, parts)
    o = join(objs, "goggles")
    unwrap(o, 0.004)
    tex = os.path.join(assets, "textures/grs")
    imgs = bake(o, [shell, strap, trim, lens, led], 1024, os.path.join(tex, "goggles.png"), os.path.join(tex, "goggles_glow.png"))
    export(o, parts, {}, os.path.join(assets, "models/grs/goggles.json"))
    pm = preview_material("pv", imgs["color"], imgs["glow"], 5)
    preview(o, pm, os.path.join(out, "grs_goggles.png"), (0.75, -1.15, 0.7), (0, -0.1, 0.27), 50, 800)
    # the inventory icon (transparent)
    icon = os.path.join(assets, "textures/item/gungnir_goggles.png")
    preview(o, pm, icon, (0.62, -1.05, 0.62), (0, -0.12, 0.27), 55, 64, light=4.0, transparent=True, samples=48)


def main():
    global ARGS
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--no-render", action="store_true")
    ap.add_argument("--only", default="")
    ARGS = ap.parse_args(argv)
    assets, out = os.path.abspath(ARGS.assets), os.path.abspath(ARGS.out)
    os.makedirs(out, exist_ok=True)
    for name, fn in (("goggles", build_goggles), ("spear", build_spear), ("station", build_station)):
        if ARGS.only and name not in ARGS.only.split(","):
            continue
        print(f"===== {name}")
        fn(assets, out)


if __name__ == "__main__":
    main()
