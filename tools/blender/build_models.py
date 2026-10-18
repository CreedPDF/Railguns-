"""
Orbital Railgun — headless Blender model pipeline.

Builds every model in the mod inside Blender, then exports them for Minecraft:

  Railgun platform (GeckoLib entity)
    * modelled as box geometry under a hierarchy of Empties (= GeckoLib bones),
      using Mirror and Array modifiers for symmetric / repeated parts
    * procedural Cycles materials (noise, Voronoi panel seams, glow gradients)
      baked into a pixel atlas with per-face UVs
    * animations keyframed as Blender Actions on the bone Empties
    -> geo/railgun.geo.json, animations/railgun.animation.json,
       textures/entity/railgun.png, textures/entity/railgun_glowmask.png

  Uplink remote (vanilla 3D item model)
    -> models/item/railgun_remote.json, textures/item/railgun_remote.png

  Renders (Cycles, CPU): icon.png plus preview PNGs and the .blend file.

Run (Blender 4.2 LTS):
  blender -b --factory-startup --python-exit-code 1 -P tools/blender/build_models.py -- \
      --assets src/main/resources/assets/orbital_railgun --out build/blender [--no-render]

Why boxes? GeckoLib (Bedrock geometry) and vanilla item models only support cuboids. Every
mesh island this script exports must therefore be an axis-aligned box; the exporter checks
that and fails loudly otherwise.

Coordinate systems
  Minecraft/Bedrock model space: pixels (16 px = 1 block), +Y up, -Z = north.
  Blender: 1 unit = 1 block, +Z up. Mapping: B = (x, -z, y) / 16.
"""
import argparse
import json
import math
import os
import sys

import bmesh
import bpy
import numpy as np
from mathutils import Vector

FPS = 20  # one Blender frame = one Minecraft tick

# =============================================================================================
# Coordinate helpers
# =============================================================================================


def mc_to_b(p):
    """Minecraft pixel coords -> Blender units."""
    return Vector((p[0] / 16.0, -p[2] / 16.0, p[1] / 16.0))


def b_to_mc(v):
    """Blender units -> Minecraft pixel coords (rounded to 1/100 px)."""
    return [round(v[0] * 16.0, 2), round(v[2] * 16.0, 2), round(-v[1] * 16.0, 2)]


def mc_axis_to_b_index(axis):
    return {"x": 0, "z": 1, "y": 2}[axis]


# =============================================================================================
# Scene setup
# =============================================================================================


def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.fps = FPS
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.view_settings.view_transform = "Standard"
    world = bpy.data.worlds.new("World")
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    bg.inputs["Color"].default_value = (0.012, 0.014, 0.022, 1.0)
    bg.inputs["Strength"].default_value = 1.0
    scene.world = world
    return scene


def link(obj):
    bpy.context.scene.collection.objects.link(obj)
    return obj


def srgb(hex_color):
    """'#rrggbb' -> linear RGBA for shader sockets."""
    h = hex_color.lstrip("#")
    out = []
    for i in (0, 2, 4):
        c = int(h[i:i + 2], 16) / 255.0
        out.append(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4)
    return (*out, 1.0)


# =============================================================================================
# Materials
# =============================================================================================

# Material "kinds" used by the modelling objects. GLOW kinds become emissive (glowmask).
# (seam, shade, light) in sRGB hex. The paint pass below adds the fine detail per kind.
PALETTE = {
    "concrete": ("#55534e", "#86837c", "#a8a49c"),
    "paint": ("#8a8e94", "#c4c8cd", "#e4e6e9"),   # off-white armour panels
    "steel": ("#3b4048", "#5d646e", "#7c838d"),
    "hull": ("#2f343c", "#4c535e", "#68717e"),
    "dark": ("#101215", "#1c1f24", "#2a2e35"),
    "vent": ("#0c0d10", "#181a1f", "#25282e"),
    "glass": ("#0a0f16", "#142030", "#25364c"),
    "hazard": ("#2a2000", "#c89a14", "#e2b628"),
    "glow": ("#5a0000", "#c8120c", "#ff4a30"),
    "amber": ("#5a3000", "#d68a10", "#ffc040"),
    "screen": ("#020407", "#07121c", "#0e2436"),   # display glass; UI painted by paint_faces
}
KINDS = list(PALETTE)
GLOW_KINDS = {"glow", "amber", "screen"}


def viewport_material(kind):
    """Simple material for the editable modelling objects in the .blend."""
    name = f"mdl_{kind}"
    mat = bpy.data.materials.get(name)
    if mat:
        return mat
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = srgb(PALETTE[kind][2])
    if kind in GLOW_KINDS:
        bsdf.inputs["Emission Color"].default_value = srgb(PALETTE[kind][2])
        bsdf.inputs["Emission Strength"].default_value = 4.0
    mat["kind"] = kind
    return mat


def bake_material(kind, image, mask):
    """
    Emission-only material used for baking. Baking EMIT copies the shader colour straight
    into the image (no lighting), so these node trees *are* the texture painter:

      colour = ColorRamp( noise(objectCoords) * (1 - panelSeam) )
      panelSeam = VoronoiDistanceToEdge(objectCoords) < 0.035

    The mask variant emits white for glowing kinds and black otherwise; it becomes the alpha of
    GeckoLib's _glowmask texture.
    """
    mat = bpy.data.materials.new(f"bake_{kind}_{'mask' if mask else 'color'}_{image.name}")
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    emit = nt.nodes.new("ShaderNodeEmission")
    nt.links.new(emit.outputs["Emission"], out.inputs["Surface"])

    target = nt.nodes.new("ShaderNodeTexImage")  # bake target: must be the active node
    target.image = image
    nt.nodes.active = target

    if mask:
        emit.inputs["Color"].default_value = (1, 1, 1, 1) if kind in GLOW_KINDS else (0, 0, 0, 1)
        return mat

    seam, shade, light = PALETTE[kind]
    coords = nt.nodes.new("ShaderNodeTexCoord")
    noise = nt.nodes.new("ShaderNodeTexNoise")
    # Low-frequency noise = subtle weathering, not per-pixel grain.
    noise.inputs["Scale"].default_value = 2.2 if kind == "concrete" else 1.4
    noise.inputs["Detail"].default_value = 2.0
    nt.links.new(coords.outputs["Object"], noise.inputs["Vector"])

    ramp = nt.nodes.new("ShaderNodeValToRGB")
    els = ramp.color_ramp.elements
    if kind in GLOW_KINDS:
        nt.links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
        els[0].position, els[0].color = 0.3, srgb(shade)
        els[1].position, els[1].color = 0.7, srgb(light)
    else:
        voronoi = nt.nodes.new("ShaderNodeTexVoronoi")
        voronoi.feature = "DISTANCE_TO_EDGE"
        voronoi.inputs["Scale"].default_value = 1.6 if kind == "concrete" else 1.1
        nt.links.new(coords.outputs["Object"], voronoi.inputs["Vector"])

        is_seam = nt.nodes.new("ShaderNodeMath")
        is_seam.operation = "LESS_THAN"
        is_seam.inputs[1].default_value = 0.025
        nt.links.new(voronoi.outputs["Distance"], is_seam.inputs[0])

        not_seam = nt.nodes.new("ShaderNodeMath")
        not_seam.operation = "SUBTRACT"
        not_seam.inputs[0].default_value = 1.0
        nt.links.new(is_seam.outputs[0], not_seam.inputs[1])

        value = nt.nodes.new("ShaderNodeMath")
        value.operation = "MULTIPLY"
        nt.links.new(noise.outputs["Fac"], value.inputs[0])
        nt.links.new(not_seam.outputs[0], value.inputs[1])
        nt.links.new(value.outputs[0], ramp.inputs["Fac"])

        els[0].position, els[0].color = 0.04, srgb(seam)
        els[1].position, els[1].color = 0.22, srgb(shade)
        top = els.new(0.55)
        top.color = srgb(light)

    nt.links.new(ramp.outputs["Color"], emit.inputs["Color"])
    return mat


def preview_material(name, atlas, glow):
    """Principled material for renders: baked atlas as albedo, glowmask as emission."""
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes.get("Principled BSDF")
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = atlas
    tex.interpolation = "Closest"
    gtex = nt.nodes.new("ShaderNodeTexImage")
    gtex.image = glow
    gtex.interpolation = "Closest"
    mult = nt.nodes.new("ShaderNodeVectorMath")
    mult.operation = "MULTIPLY"
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(gtex.outputs["Color"], mult.inputs[0])
    nt.links.new(gtex.outputs["Alpha"], mult.inputs[1])
    nt.links.new(mult.outputs["Vector"], bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value = 3.0
    bsdf.inputs["Metallic"].default_value = 0.15
    bsdf.inputs["Roughness"].default_value = 0.6
    return mat


# =============================================================================================
# Modelling
# =============================================================================================


def make_bone(name, parent, pivot_mc, parent_pivot_mc, offset=Vector((0, 0, 0))):
    """Bone = Empty at the pivot. Hierarchy uses pure translations, so local loc = pivot delta."""
    empty = link(bpy.data.objects.new(name, None))
    empty.empty_display_type = "PLAIN_AXES"
    empty.empty_display_size = 0.25
    if parent is None:
        empty.location = mc_to_b(pivot_mc) + offset
    else:
        empty.parent = parent
        empty.location = mc_to_b(pivot_mc) - mc_to_b(parent_pivot_mc)
    empty["pivot_mc"] = list(pivot_mc)
    empty["rest_location"] = list(empty.location)
    return empty


def make_part(name, bone, kind, boxes, mirror=(), array=None):
    """
    A modelling object: one or more boxes (Minecraft px, absolute model space) parented to a
    bone Empty, optionally with Mirror (around the bone pivot) and Array modifiers.
    """
    pivot = Vector(bone["pivot_mc"])
    bm = bmesh.new()
    for origin, size in boxes:
        a = mc_to_b([origin[i] - pivot[i] for i in range(3)])
        b = mc_to_b([origin[i] + size[i] - pivot[i] for i in range(3)])
        lo = Vector((min(a.x, b.x), min(a.y, b.y), min(a.z, b.z)))
        hi = Vector((max(a.x, b.x), max(a.y, b.y), max(a.z, b.z)))
        v = [bm.verts.new((x, y, z)) for x in (lo.x, hi.x) for y in (lo.y, hi.y) for z in (lo.z, hi.z)]
        # index = xi*4 + yi*2 + zi
        for quad in ((0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)):
            bm.faces.new([v[i] for i in quad])
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    mesh.materials.append(viewport_material(kind))

    obj = link(bpy.data.objects.new(name, mesh))
    obj.parent = bone
    obj["kind"] = kind
    obj["export"] = True

    if mirror:
        mod = obj.modifiers.new("Mirror", "MIRROR")
        mod.use_axis = [False, False, False]
        for axis in mirror:
            mod.use_axis[mc_axis_to_b_index(axis)] = True
        mod.use_mirror_merge = False
        mod.use_clip = False
    if array:
        count, step_mc = array
        mod = obj.modifiers.new("Array", "ARRAY")
        mod.count = count
        mod.use_relative_offset = False
        mod.use_constant_offset = True
        mod.constant_offset_displace = mc_to_b(step_mc)
        mod.use_merge_vertices = False
    return obj


def make_rcube(name, bone, kind, origin, size, pivot, axis, angle):
    """
    A box rotated about its own pivot around the Y or Z axis (Bedrock cube "rotation").

    Convention (checked against GeckoLib's BakedModelFactory / RenderUtils): GeckoLib mirrors X
    and negates the JSON Y rotation, which together make a JSON rotation of [0, a, 0] rotate the
    cube by R_y(a) in model space, where R_y(a) maps (x, z) -> (x cos a + z sin a, -x sin a +
    z cos a). A JSON [0, 0, c] rotates it by R_z(-c). In Blender that is rotation_euler.z = a
    for Y, and rotation_euler.y = c for Z, so the previews match the game.
    """
    pv = Vector(bone["pivot_mc"])
    lo_mc = [origin[i] - pivot[i] for i in range(3)]
    hi_mc = [origin[i] + size[i] - pivot[i] for i in range(3)]
    a_b, b_b = mc_to_b(lo_mc), mc_to_b(hi_mc)
    lo = Vector((min(a_b.x, b_b.x), min(a_b.y, b_b.y), min(a_b.z, b_b.z)))
    hi = Vector((max(a_b.x, b_b.x), max(a_b.y, b_b.y), max(a_b.z, b_b.z)))
    bm = bmesh.new()
    v = [bm.verts.new((x, y, z)) for x in (lo.x, hi.x) for y in (lo.y, hi.y) for z in (lo.z, hi.z)]
    for quad in ((0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)):
        bm.faces.new([v[i] for i in quad])
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    mesh.materials.append(viewport_material(kind))
    obj = link(bpy.data.objects.new(name, mesh))
    obj.parent = bone
    obj.location = mc_to_b(list(pivot)) - mc_to_b(list(pv))
    obj.rotation_euler = (0, 0, math.radians(angle)) if axis == "y" else (0, math.radians(angle), 0)
    obj["kind"] = kind
    obj["export"] = True
    obj["rot_axis"] = axis
    obj["rot_angle"] = float(angle)
    obj["rot_pivot"] = [float(c) for c in pivot]
    obj["cube_mc"] = [float(c) for c in list(origin) + list(size)]
    return obj


TAN22 = math.tan(math.radians(22.5))
_uid = [0]


def _n(prefix):
    _uid[0] += 1
    return f"{prefix}_{_uid[0]}"


def box(bone, kind, origin, size, off=(0, 0, 0), mirror=(), array=None, name="p"):
    o = [origin[i] + off[i] for i in range(3)]
    return make_part(_n(name), bone, kind, [(o, list(size))], mirror=mirror, array=array)


def rbox(bone, kind, origin, size, pivot, axis, angle, off=(0, 0, 0), name="r"):
    o = [origin[i] + off[i] for i in range(3)]
    pv = [pivot[i] + off[i] for i in range(3)]
    return make_rcube(_n(name), bone, kind, o, list(size), pv, axis, angle)


def octagon(name, bone, kind, y0, width, height, center_xz=(0, 0), off=(0, 0, 0)):
    """
    Vertical regular-octagon prism (flat-to-flat width): two axis rects + two rects at +/-45 deg.
    Overlapping tops are staggered by 0.15 px so they never z-fight.
    """
    cx, cz = center_xz[0] + off[0], center_xz[1] + off[2]
    y0 = y0 + off[1]
    a = width / 2.0
    b = round(a * TAN22, 3)
    make_part(f"{name}_a", bone, kind, [([cx - a, y0, cz - b], [width, height, 2 * b])])
    make_part(f"{name}_b", bone, kind, [([cx - b, y0, cz - a], [2 * b, height + 0.15, width])])
    make_rcube(f"{name}_c", bone, kind, [cx - a, y0, cz - b], [width, height + 0.3, 2 * b], [cx, y0, cz], "y", 45.0)
    make_rcube(f"{name}_d", bone, kind, [cx - a, y0, cz - b], [width, height + 0.45, 2 * b], [cx, y0, cz], "y", -45.0)


def octagon_z(name, bone, kind, cx, cy, z0, z1, width):
    """Horizontal octagonal tube along Z (barrel segments, rings): rects at 0/90 and +/-45 deg about Z."""
    a = width / 2.0
    b = round(a * TAN22, 3)
    L = z1 - z0
    make_part(f"{name}_a", bone, kind, [([cx - a, cy - b, z0], [width, 2 * b, L])])
    make_part(f"{name}_b", bone, kind, [([cx - b, cy - a, z0 - 0.05], [2 * b, width, L + 0.1])])
    make_rcube(f"{name}_c", bone, kind, [cx - a, cy - b, z0 - 0.1], [width, 2 * b, L + 0.2], [cx, cy, (z0 + z1) / 2], "z", 45.0)
    make_rcube(f"{name}_d", bone, kind, [cx - a, cy - b, z0 - 0.15], [width, 2 * b, L + 0.3], [cx, cy, (z0 + z1) / 2], "z", -45.0)


def diag_box(bone, kind, sx, sz, base, r0, r1, width, y0, y1, off=(0, 0, 0)):
    """A box lying along the diagonal (sx, sz) from point `base`, from radius r0 to r1."""
    L = r1 - r0
    rm = (r0 + r1) / 2.0
    k = rm / math.sqrt(2)
    cx, cz = base[0] + sx * k, base[1] + sz * k
    ang = -45.0 * sx * sz   # R_y(ang) maps +X onto the (sx, sz) diagonal (180-deg symmetric)
    rbox(bone, kind, [cx - L / 2, y0, cz - width / 2], [L, y1 - y0, width], [cx, y0, cz], "y", ang, off=off, name="diag")


# ---- Layout constants (px, 16 px = 1 block) ----------------------------------------------------
SHELL_A = 34.0          # pod shell outer apothem
SHELL_T = 5.0           # shell wall thickness
SHELL_TOP = 108.0
BULWARK_D = 62.0        # how far each shell quarter glides out (per axis) to become a bulwark
DECK_OUT = 56.0         # how far deck plates slide out
TURRET_Y = 72.0
BARREL_Y = 118.0
POD_TURRET_DROP = -70.0
SEG1_IN, SEG2_IN = 105.0, 95.0


def build_railgun():
    """
    ARES-9 orbital railgun. The REST pose (modelled geometry) is the deployed emplacement; the
    pod is reached by animation offsets, so everything the emplacement has physically fits inside
    and comes out of the pod:

      core        octagonal pedestal (always visible) carrying the mounted control tablet
      shell_*     the pod's four armour quarters; deployed they stand at the corners as bulwarks
        clamp_*   diagonal ground clamps stowed inside each quarter
        bolt_*    locking bolts on the quarter seams
        beacon_*  rotating warning beacons on top
      strut_*_1..3  telescoping hydraulic arms + floor conduits tying each bulwark to the core
      deck_n/e/w  walkway plates that slide out from under the pod (the +Z side stays open
                  so the tablet is reachable from the ground)
      turret      yaw; hull rises out of the pod
        radar     telescoping mast with a spinning dish; antenna
      barrel      pitch; three telescoping octagonal segments (barrel > seg1 > seg2) with coil
                  rings that light up in sequence and cooling fins (vents)
    """
    bones = {}

    def bone(name, parent, pivot):
        p = bones.get(parent)
        bones[name] = make_bone(name, p, pivot, p["pivot_mc"] if p else None)

    bone("root", None, [0, 0, 0])
    bone("core", "root", [0, 0, 0])
    bone("pod_panel", "core", [0, 48, 30])
    quads = [(1, 1), (1, -1), (-1, 1), (-1, -1)]
    qname = {(1, 1): "pp", (1, -1): "pn", (-1, 1): "np", (-1, -1): "nn"}
    for q in quads:
        sx, sz = q
        n = qname[q]
        D = (sx * BULWARK_D, 0, sz * BULWARK_D)
        bone(f"shell_{n}", "root", [D[0], 0, D[2]])
        bone(f"clamp_{n}", f"shell_{n}", [D[0] + sx * 26, 4, D[2] + sz * 26])
        bone(f"bolt_{n}", f"shell_{n}", [D[0], 60, D[2]])
        bone(f"beacon_{n}", f"shell_{n}", [D[0] + sx * 22, SHELL_TOP + 2, D[2] + sz * 22])
        for k in (1, 2, 3):
            bone(f"strut_{n}_{k}", "core", [0, 0, 0])
    for d in ("n", "e", "w"):
        bone(f"deck_{d}", "root", [0, 0, 0])
    bone("turret", "root", [0, TURRET_Y, 0])
    bone("hull", "turret", [0, TURRET_Y, 0])
    bone("radar", "hull", [15, 107, 16])
    bone("dish", "radar", [15, 121, 16])
    bone("antenna", "hull", [-16, 107, 18])
    bone("barrel", "turret", [0, BARREL_Y, 0])
    bone("vents", "barrel", [0, BARREL_Y, 0])
    bone("coils_a", "barrel", [0, BARREL_Y, -60])
    bone("seg1", "barrel", [0, BARREL_Y, 0])
    bone("coils_b", "seg1", [0, BARREL_Y, -180])
    bone("seg2", "seg1", [0, BARREL_Y, 0])
    bone("coils_c", "seg2", [0, BARREL_Y, -260])
    B = bones
    Y = BARREL_Y

    # ---- Core pedestal + mounted tablet ------------------------------------------------------
    core = B["core"]
    octagon("core_foot", core, "dark", 0, 64, 5)
    octagon("core_hazard", core, "hazard", 5, 59.5, 2)
    octagon("core_body", core, "steel", 7, 58, 61)
    octagon("core_band1", core, "glow", 22, 58.8, 1.2)
    octagon("core_band2", core, "glow", 62, 58.8, 1.2)
    octagon("core_cap", core, "dark", 68, 60, 4)
    box(core, "dark", [-13, 34, 28.4], [26, 28, 1.2])                      # tablet recess frame
    box(core, "vent", [-8, 10, 28.5], [16, 8, 0.8])
    P = B["pod_panel"]
    box(P, "dark", [-3, 44, 29], [6, 8, 2])                                # mount arm
    box(P, "dark", [-11, 37, 31], [22, 22, 1.6])                           # tablet bezel
    box(P, "steel", [-12.5, 39, 30.6], [1.5, 18, 2.4], mirror=("x",))      # rubber grips
    box(P, "screen", [-9, 39, 32.6], [18, 18, 0.3])                        # display
    box(P, "amber", [-6, 37.4, 32.6], [2, 0.9, 0.3])
    box(P, "glow", [4, 37.4, 32.6], [2, 0.9, 0.3])
    box(P, "glow", [-1, 57.3, 32.6], [2, 1, 0.3])

    # ---- Shell quarters (pod armour) -> corner bulwarks, with clamps, bolts, beacons -----------
    A, T = SHELL_A, SHELL_T
    half = A * TAN22                       # half length of an octagon face
    for q in quads:
        sx, sz = q
        n = qname[q]
        sh = B[f"shell_{n}"]
        off = (sx * BULWARK_D, 0, sz * BULWARK_D)
        x0 = 0 if sx > 0 else -half
        z0 = 0 if sz > 0 else -half
        # base ring quarter + hazard band
        box(sh, "dark", [(A - 1) if sx > 0 else -(A + 1), 0, z0], [2, 6, half], off)
        # +/-X face half (full height)
        box(sh, "paint", [(A - T) if sx > 0 else -A, 6, z0], [T, SHELL_TOP - 6, half], off)
        # +/-Z face half: on the +Z (panel) side leave the hatch open (|x| < 12)
        if sz > 0:
            box(sh, "paint", [13 if sx > 0 else -half, 6, A - T], [half - 13, SHELL_TOP - 6, T], off)
            box(sh, "hazard", [13 if sx > 0 else -14, 6, A - T - 0.4], [1, SHELL_TOP - 6, T + 0.8], off)
        else:
            box(sh, "paint", [x0, 6, -A], [half, SHELL_TOP - 6, T], off)
        # diagonal face: centre on the diagonal at the wall's mid-thickness
        cxz = (A - T / 2) / math.sqrt(2)   # mid-wall point on the diagonal
        cx, cz = sx * cxz, sz * cxz
        L = 2 * half + 1.0
        ang = 45.0 * sx * sz                # tangent of the (sx, sz) face
        rbox(sh, "paint", [cx - L / 2, 6, cz - T / 2], [L, SHELL_TOP - 6, T], [cx, 6, cz], "y", ang, off)
        rbox(sh, "hazard", [cx - L / 2, 6, cz - T / 2 - 0.3], [L, 3, T + 0.6], [cx, 6, cz], "y", ang, off)
        rbox(sh, "glow", [cx - L / 2, 40, cz - T / 2 - 0.4], [L, 1.2, T + 0.8], [cx, 40, cz], "y", ang, off)
        rbox(sh, "glow", [cx - L / 2, 86, cz - T / 2 - 0.4], [L, 1.2, T + 0.8], [cx, 86, cz], "y", ang, off)
        # armour panel lines on the axis faces
        box(sh, "steel", [(A - 0.2) if sx > 0 else -(A + 0.2) - 0.4, 30, z0 + (2 if sz > 0 else 2)], [0.6, 40, half - 4], off)
        # top collar quarter
        box(sh, "dark", [(A - T - 2) if sx > 0 else -(A - 0), SHELL_TOP, z0], [T + 2, 4, half], off)
        rbox(sh, "dark", [cx - L / 2, SHELL_TOP, cz - (T + 2) / 2], [L, 4, T + 2], [cx, SHELL_TOP, cz], "y", ang, off)
                # armour rib on the diagonal: shallow, so the pod stays a clean octagonal pillar
        diag_box(sh, "steel", sx, sz, (sx * 20, sz * 20), 4, 9, 3, 14, 96, off)
        diag_box(sh, "glow", sx, sz, (sx * 20, sz * 20), 8.6, 9.4, 1.6, 22, 88, off)
        diag_box(sh, "dark", sx, sz, (sx * 20, sz * 20), 4, 10, 4, 96, 100, off)
        # corner deck slab (bulwark floor)
        box(sh, "steel", [4 if sx > 0 else -30, 0, 4 if sz > 0 else -30], [26, 18, 26], off)
        box(sh, "vent", [6 if sx > 0 else -28, 18, 6 if sz > 0 else -28], [22, 1, 22], off)
        # locking bolts on the seams (bolt bone)
        bb = B[f"bolt_{n}"]
        bz0 = 1.5 if sz > 0 else -4.5
        box(bb, "steel", [(A - 2) if sx > 0 else -(A + 1), 56, bz0], [3, 8, 3], off)
        box(bb, "amber", [(A + 0.4) if sx > 0 else -(A + 1.4), 58, bz0 + 0.5], [1, 3, 2], off)
        # beacon (spins)
        be = B[f"beacon_{n}"]
        bx_, bz_ = sx * 22, sz * 22
        box(be, "dark", [bx_ - 3, SHELL_TOP + 2, bz_ - 3], [6, 2, 6], off)
        box(be, "amber", [bx_ - 2.5, SHELL_TOP + 4, bz_ - 1], [5, 3, 2], off)
        box(be, "glow", [bx_ - 1, SHELL_TOP + 4, bz_ - 2.5], [2, 3, 5], off)
        # ground clamp (stowed inside; slides out along the diagonal and slams down)
        cl = B[f"clamp_{n}"]
        base = (sx * 26, sz * 26)
        diag_box(cl, "steel", sx, sz, base, 0, 34, 9, 4, 12, off)            # arm
        diag_box(cl, "paint", sx, sz, base, 2, 30, 10, 12, 14, off)          # arm cover
        diag_box(cl, "dark", sx, sz, base, 6, 26, 4, 14, 18, off)            # hydraulic ram
        diag_box(cl, "steel", sx, sz, base, 26, 42, 16, 0, 5, off)           # foot pad
        diag_box(cl, "hazard", sx, sz, base, 27, 41, 14, 5, 6, off)
        diag_box(cl, "dark", sx, sz, base, 30, 38, 6, -3, 0, off)            # ground spike
        diag_box(cl, "amber", sx, sz, base, 33, 35, 2, 12, 14, off)

    # ---- Deck plates (slide out from under the pod) + nested stairs -----------------------------
    def deck(bn, rot):
        db = B[bn]
        for kind, o, sz_ in (
            ("steel", [-24, 0, 26], [48, 16, 58]),
            ("vent", [-22, 16, 28], [44, 3.5, 54]),
            ("hazard", [-24, 16, 82], [48, 4, 2]),
            ("dark", [-26, 0, 30], [2, 14, 50]),
            ("dark", [24, 0, 30], [2, 14, 50]),
            ("glow", [-26.4, 10, 34], [0.4, 1, 42]),
            ("glow", [26, 10, 34], [0.4, 1, 42]),
            ("amber", [-22, 16, 80], [3, 1.5, 2]),
            ("amber", [19, 16, 80], [3, 1.5, 2]),
        ):
            x0, y0, z0 = o
            w, h, d = sz_
            if rot == "s":
                oo, ss = [x0, y0, z0], [w, h, d]
            elif rot == "n":
                oo, ss = [x0, y0, -(z0 + d)], [w, h, d]
            elif rot == "e":
                oo, ss = [z0, y0, x0], [d, h, w]
            else:
                oo, ss = [-(z0 + d), y0, x0], [d, h, w]
            box(db, kind, oo, ss, name=f"deck{rot}")
    deck("deck_n", "n")
    deck("deck_e", "e")
    deck("deck_w", "w")

    # ---- Telescoping struts: core <-> bulwark (hydraulic arm up top, cable conduit on the floor)
    # Radii are measured along the diagonal from the core axis. Deployed, the arm spans the core's
    # diagonal face (r 32) to the bulwark's inner wall (r ~117); stowed, every stage slides back
    # inside the core (r <= 31), so nothing pokes out of the pod.
    for q in quads:
        sx, sz = q
        n = qname[q]
        o = (0, 0)
        s1, s2, s3 = B[f"strut_{n}_1"], B[f"strut_{n}_2"], B[f"strut_{n}_3"]
        # stage 1: armoured housing
        diag_box(s1, "dark", sx, sz, o, 30, 60, 12, 54, 66)
        diag_box(s1, "glow", sx, sz, o, 33, 55, 12.4, 59.4, 60.6)
        diag_box(s1, "steel", sx, sz, o, 56, 60, 13, 53.5, 66.5)
        diag_box(s1, "dark", sx, sz, o, 30, 60, 8, 0, 5)                    # floor conduit
        diag_box(s1, "glow", sx, sz, o, 32, 58, 2, 5, 5.4)
        # stage 2: piston
        diag_box(s2, "steel", sx, sz, o, 57, 89, 9, 55.5, 64.5)
        diag_box(s2, "dark", sx, sz, o, 85, 89, 10, 55, 65)
        diag_box(s2, "amber", sx, sz, o, 86, 88, 2, 65, 65.4)
        diag_box(s2, "steel", sx, sz, o, 57, 89, 6, 0, 4)
        diag_box(s2, "hazard", sx, sz, o, 59, 87, 6.4, 3, 3.6)
        # stage 3: polished rod + mounting plate on the bulwark
        diag_box(s3, "paint", sx, sz, o, 86, 118, 6, 57, 63)
        diag_box(s3, "dark", sx, sz, o, 112, 118, 14, 51, 69)
        diag_box(s3, "glow", sx, sz, o, 111.6, 112, 10, 56, 64)
        diag_box(s3, "steel", sx, sz, o, 86, 116, 4, 0, 3)

    # ---- Turret ------------------------------------------------------------------------------------
    H = B["hull"]
    octagon("tt_ring", H, "dark", 72, 54, 4)
    octagon("tt_glow", H, "glow", 73.5, 55.5, 1.2)
    octagon("hull_low", H, "steel", 76, 52, 8)
    octagon("hull_body", H, "paint", 84, 56, 20)
    octagon("hull_roof", H, "dark", 104, 48, 3)
    box(H, "paint", [-22, 84, -34], [44, 7, 6])          # stepped front glacis
    box(H, "paint", [-18, 91, -33], [36, 6, 5])
    box(H, "hazard", [-22, 84, -34.4], [44, 1.6, 0.4])
    box(H, "paint", [-32, 96, -14], [7, 24, 28], mirror=("x",))   # trunnion cheeks
    box(H, "paint", [-31, 120, -10], [5, 3, 20], mirror=("x",))
    box(H, "steel", [-32.3, 100, -10], [0.3, 14, 20], mirror=("x",))
    box(H, "dark", [-35, 112, -6], [3, 12, 12], mirror=("x",))    # trunnion hubs
    box(H, "glow", [-35.5, 116, -2], [0.5, 4, 4], mirror=("x",))
    box(H, "dark", [-30, 86, -26], [6, 8, 10], mirror=("x",))     # sensor pods
    box(H, "screen", [-30.3, 88, -25], [0.3, 4, 8], mirror=("x",))
    box(H, "vent", [-16, 88, 27.5], [32, 12, 1])                  # rear exhaust
    box(H, "steel", [-14, 104, 18], [28, 3, 1], array=(4, [0, 0, -4]))   # heat sink fins
    R = B["radar"]
    box(R, "steel", [13.5, 107, 14.5], [3, 14, 3])
    box(R, "dark", [12.5, 107, 13.5], [5, 2, 5])
    Dh = B["dish"]
    box(Dh, "dark", [7, 121, 12], [16, 1.5, 8])
    box(Dh, "paint", [8, 122.5, 15.5], [14, 4, 1])
    box(Dh, "glow", [14, 122.5, 14], [2, 2, 1.5])
    An = B["antenna"]
    box(An, "steel", [-16.5, 107, 17.5], [1, 22, 1])
    box(An, "glow", [-17, 129, 17], [2, 2, 2])

    # ---- Barrel: three telescoping octagonal segments -----------------------------------------
    b0, s1, s2 = B["barrel"], B["seg1"], B["seg2"]
    octagon_z("breech", b0, "paint", 0, Y, 10, 48, 44)
    octagon_z("breech_cap", b0, "dark", 0, Y, 48, 52, 36)
    for i, z in enumerate((14, 26, 38)):
        octagon_z(f"breech_fin{i}", b0, "dark", 0, Y, z, z + 2, 48)
    box(b0, "dark", [-26, Y - 8, 18], [4, 16, 20], mirror=("x",))           # power couplings
    box(b0, "glow", [-26.4, Y - 2, 24], [0.4, 4, 8], mirror=("x",))
    octagon_z("seg0_tube", b0, "paint", 0, Y, -120, 10, 40)
    box(b0, "steel", [-23, Y - 8, -118], [4, 16, 126], mirror=("x",))      # rails
    box(b0, "glow", [-23.4, Y - 1, -116], [0.4, 2, 120], mirror=("x",))
    box(b0, "glow", [-1, Y + 19.6, -118], [2, 0.6, 122])
    box(b0, "dark", [-14, Y + 19, -118], [3, 1, 122], mirror=("x",))
    octagon_z("seg0_mouth", b0, "dark", 0, Y, -124, -118, 44)
    V = B["vents"]
    box(V, "steel", [-11, Y + 19, -100], [22, 5, 2], array=(8, [0, 0, 10]))
    box(V, "glow", [-9, Y + 24, -100], [18, 0.4, 2], array=(8, [0, 0, 10]))
    for i, z in enumerate((-40, -80)):
        octagon_z(f"coil_a{i}", B["coils_a"], "glow", 0, Y, z - 1.5, z + 1.5, 48)
    octagon_z("seg1_tube", s1, "paint", 0, Y, -220, -110, 34)
    box(s1, "steel", [-19, Y - 6, -216], [3, 12, 104], mirror=("x",))
    box(s1, "glow", [-19.4, Y - 1, -214], [0.4, 2, 100], mirror=("x",))
    box(s1, "glow", [-1, Y + 16.6, -216], [2, 0.6, 104])
    octagon_z("seg1_mouth", s1, "dark", 0, Y, -224, -218, 38)
    for i, z in enumerate((-160, -200)):
        octagon_z(f"coil_b{i}", B["coils_b"], "glow", 0, Y, z - 1.5, z + 1.5, 42)
    octagon_z("seg2_tube", s2, "paint", 0, Y, -300, -210, 28)
    box(s2, "glow", [-1, Y + 13.6, -296], [2, 0.6, 84])
    octagon_z("muzzle", s2, "dark", 0, Y, -316, -300, 39)
    octagon_z("muzzle_ring", s2, "steel", 0, Y, -306, -304, 38.5)
    box(s2, "vent", [-20.6, Y - 5, -314], [0.6, 10, 8], mirror=("x",))
    octagon_z("bore", s2, "glow", 0, Y, -316.6, -316, 22)
    octagon_z("coil_c0", B["coils_c"], "glow", 0, Y, -261.5, -258.5, 36)
    return bones


def build_remote(offset):
    """
    The uplink tablet (item id railgun_remote), in vanilla item space (0..16 px). A rugged
    landscape slate held in both hands: rubber side grips, bezel, a glowing display on BOTH
    faces (so it reads correctly however the hands orient it), status buttons and an antenna.
    """
    bones = {"remote": make_bone("remote", None, [8, 0, 8], None, offset=offset)}
    R = bones["remote"]
    make_part("tab_body", R, "paint", [([1, 2.5, 7.25], [14, 11, 1.5])])
    make_part("tab_bezel_f", R, "dark", [([1.5, 3.2, 7.18], [13, 9.6, 0.07])])
    make_part("tab_bezel_b", R, "dark", [([1.5, 3.2, 8.75], [13, 9.6, 0.07])])
    make_part("tab_screen_f", R, "screen", [([2.2, 4.2, 7.1], [11.6, 7.6, 0.08])])
    make_part("tab_screen_b", R, "screen", [([2.2, 4.2, 8.82], [11.6, 7.6, 0.08])])
    make_part("tab_grips", R, "dark", [([0, 3.5, 6.9], [1.5, 9, 2.2])], mirror=("x",))
    make_part("tab_grip_ribs", R, "steel", [([-0.1, 5, 7.4], [0.1, 0.6, 1.2])], mirror=("x",), array=(4, [0, 2, 0]))
    make_part("tab_bumpers", R, "hazard", [([1, 2, 7], [3, 0.6, 2])], mirror=("x",))
    make_part("tab_btn_fire", R, "glow", [([6.6, 2.75, 7.08], [1.2, 0.5, 0.17])])
    make_part("tab_btn_arm", R, "amber", [([8.2, 2.75, 7.08], [1.2, 0.5, 0.17])])
    make_part("tab_antenna", R, "dark", [([12.4, 13.5, 7.6], [1, 2.4, 0.8])])
    make_part("tab_antenna_tip", R, "glow", [([12.4, 15.9, 7.6], [1, 0.6, 0.8])])
    return bones


# =============================================================================================
# Extraction: evaluated modelling objects -> list of boxes
# =============================================================================================


def extract_boxes(bones, offset=Vector((0, 0, 0))):
    """
    Evaluates every exported object (modifiers applied), splits the mesh into connected
    islands and returns axis-aligned boxes in Minecraft px coordinates:
      [{"bone": name, "kind": kind, "min": [x,y,z], "max": [x,y,z]}]
    """
    bpy.context.view_layer.update()
    depsgraph = bpy.context.evaluated_depsgraph_get()
    bone_names = set(bones)
    boxes = []
    for obj in bpy.context.scene.objects:
        if not obj.get("export") or obj.parent is None or obj.parent.name not in bone_names:
            continue
        if obj.get("rot_axis") is not None:
            x, y, z, sx, sy, sz = list(obj["cube_mc"])
            boxes.append({"bone": obj.parent.name, "kind": obj["kind"],
                          "min": [x, y, z], "max": [x + sx, y + sy, z + sz],
                          "rot_axis": obj["rot_axis"], "rot_angle": float(obj["rot_angle"]),
                          "rot_pivot": list(obj["rot_pivot"])})
            continue
        ev = obj.evaluated_get(depsgraph)
        mesh = ev.to_mesh()
        world = [ev.matrix_world @ v.co - offset for v in mesh.vertices]

        parent = list(range(len(world)))

        def find(i):
            while parent[i] != i:
                parent[i] = parent[parent[i]]
                i = parent[i]
            return i

        for e in mesh.edges:
            a, b = find(e.vertices[0]), find(e.vertices[1])
            if a != b:
                parent[a] = b
        islands = {}
        for i in range(len(world)):
            islands.setdefault(find(i), []).append(i)

        for verts in islands.values():
            pts = [b_to_mc(world[i]) for i in verts]
            lo = [min(p[k] for p in pts) for k in range(3)]
            hi = [max(p[k] for p in pts) for k in range(3)]
            corners = {(round(p[0], 2), round(p[1], 2), round(p[2], 2)) for p in pts}
            ok = len(verts) == 8 and all(
                all(c[k] in (lo[k], hi[k]) for k in range(3)) for c in corners)
            if not ok:
                raise RuntimeError(f"{obj.name}: island is not an axis-aligned box ({len(verts)} verts)")
            boxes.append({"bone": obj.parent.name, "kind": obj["kind"], "min": lo, "max": hi})
        ev.to_mesh_clear()
    return boxes


# =============================================================================================
# Per-face UV atlas
# =============================================================================================

FACES = ("north", "south", "east", "west", "up", "down")


def face_dims(size):
    sx, sy, sz = size
    return {"north": (sx, sy), "south": (sx, sy), "east": (sz, sy), "west": (sz, sy),
            "up": (sx, sz), "down": (sx, sz)}


def face_corners(lo, hi):
    """TL, TR, BR, BL corners of each face as seen from outside the box (MC px)."""
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    return {
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }


def pack_faces(boxes, texels_per_px, start_size):
    """
    Skyline bottom-left packer for every face of every box. For each face (tallest first) it
    finds the x position where the face sits lowest on the current skyline. Much denser than
    shelf packing when there are long thin faces. Adds box['rects'][face] = (u, v, w, h).
    """
    items = []
    for bi, box in enumerate(boxes):
        size = [box["max"][k] - box["min"][k] for k in range(3)]
        for face, (w, h) in face_dims(size).items():
            tw = max(1, math.ceil(w * texels_per_px - 1e-6))
            th = max(1, math.ceil(h * texels_per_px - 1e-6))
            items.append((th, tw, bi, face))
    items.sort(key=lambda t: (-t[0], -t[1]))

    tex = start_size
    while True:
        sky = np.zeros(tex, dtype=np.int64)
        placed = {}
        ok = True
        for th, tw, bi, face in items:
            if tw > tex:
                ok = False
                break
            tops = np.lib.stride_tricks.sliding_window_view(sky, tw).max(axis=1)
            x = int(np.argmin(tops))
            y = int(tops[x])
            if y + th > tex:
                ok = False
                break
            sky[x:x + tw] = y + th
            placed[(bi, face)] = (x, y, tw, th)
        if ok:
            break
        tex *= 2
    for (bi, face), rect in placed.items():
        boxes[bi].setdefault("rects", {})[face] = rect
    return tex


def rotate_point(c, box):
    """Model-space rotation of a cube corner, matching what GeckoLib renders (see make_rcube)."""
    px, py, pz = box["rot_pivot"]
    a = math.radians(box["rot_angle"])
    dx, dy, dz = c[0] - px, c[1] - py, c[2] - pz
    if box["rot_axis"] == "y":       # R_y(a)
        return (px + dx * math.cos(a) + dz * math.sin(a), c[1], pz - dx * math.sin(a) + dz * math.cos(a))
    b = -a                           # JSON z rotation c renders as R_z(-c)
    return (px + dx * math.cos(b) - dy * math.sin(b), py + dx * math.sin(b) + dy * math.cos(b), c[2])


def build_bake_objects(boxes, bones, tex, kinds, offset=Vector((0, 0, 0))):
    """One UV-mapped mesh per bone, parented to the bone Empty, faces mapped into the atlas."""
    objs = []
    for name, empty in bones.items():
        mine = [b for b in boxes if b["bone"] == name]
        if not mine:
            continue
        pivot_world = empty.matrix_world.translation
        verts, faces, uvs, mats = [], [], [], []
        for box in mine:
            for face, corners in face_corners(box["min"], box["max"]).items():
                u, v, w, h = box["rects"][face]
                base = len(verts)
                for c in corners:
                    if "rot_axis" in box:
                        c = rotate_point(c, box)
                    verts.append(mc_to_b(c) + offset - pivot_world)
                faces.append((base, base + 1, base + 2, base + 3))
                # TL, TR, BR, BL -> Blender UV origin is bottom-left.
                uvs += [(u / tex, 1 - v / tex), ((u + w) / tex, 1 - v / tex),
                        ((u + w) / tex, 1 - (v + h) / tex), (u / tex, 1 - (v + h) / tex)]
                mats.append(kinds.index(box["kind"]))
        mesh = bpy.data.meshes.new(f"bake_{name}")
        mesh.from_pydata([tuple(v) for v in verts], [], faces)
        uv_layer = mesh.uv_layers.new(name="atlas")
        # from_pydata creates loops in face order, so loop i matches uvs[i].
        for li in range(len(mesh.loops)):
            uv_layer.data[li].uv = uvs[li]
        mesh.update()
        obj = link(bpy.data.objects.new(f"bake_{name}", mesh))
        obj.parent = empty
        # Material indices are (re)applied after material slots exist: clearing a mesh's
        # material slots resets every face to index 0, which is what broke the first bake.
        obj["mat_indices"] = mats
        objs.append(obj)
    return objs


# =============================================================================================
# Texture paint pass (per face, on top of the Cycles bake)
# =============================================================================================

def _hex01(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)])


SIDE_FACES = {"north", "south", "east", "west"}


def paint_screen(R, w, h, seed=0):
    """
    A tablet / console display: dark glass with a glowing UI — frame, header bar, radar scope
    with sweep and contacts, status bars and text-like rows. Big faces get the full layout, thin
    faces just a dark display with a scanline glow.
    """
    r = np.random.default_rng(seed)
    bg = np.array([0.02, 0.05, 0.08])
    R[:] = bg
    yy, xx = np.mgrid[0:h, 0:w]
    R[yy % 2 == 0] *= 0.8
    if w < 6 or h < 6:
        R[h // 2, :] = [0.1, 0.55, 0.7]
        return
    cyan, red, amber, green = (np.array(c) for c in ([0.25, 0.85, 1.0], [1.0, 0.25, 0.15], [1.0, 0.7, 0.15], [0.3, 1.0, 0.5]))
    R[0, :] = R[-1, :] = R[:, 0] = R[:, -1] = cyan * 0.6
    hb = max(1, h // 8)
    R[1:1 + hb, 1:-1] = cyan * 0.35
    R[1:1 + hb, 2:min(w - 2, 2 + w // 3)] = cyan * 0.9
    R[1:1 + hb, w - 2 - max(1, w // 8):w - 2] = red
    # radar scope on the left
    rad = max(2, min(h - hb - 4, w // 2 - 3) // 2)
    cy, cx = hb + 2 + rad, 2 + rad
    d = np.hypot(yy - cy, xx - cx)
    R[np.abs(d - rad) < 0.7] = cyan * 0.9
    R[np.abs(d - rad * 0.5) < 0.6] = cyan * 0.45
    R[(np.abs(yy - cy) < 0.5) & (d < rad)] = cyan * 0.35
    R[(np.abs(xx - cx) < 0.5) & (d < rad)] = cyan * 0.35
    ang = np.arctan2(yy - cy, xx - cx)
    sweep = (d < rad) & (((ang - 0.6) % (2 * np.pi)) < 0.9)
    R[sweep] = R[sweep] * 0.5 + green * 0.45
    for _ in range(3):
        a = r.uniform(0, 2 * np.pi)
        rr = r.uniform(0.2, 0.85) * rad
        py, px = int(round(cy + np.sin(a) * rr)), int(round(cx + np.cos(a) * rr))
        if 0 <= py < h and 0 <= px < w:
            R[py, px] = red
    # bars and text rows on the right
    x0 = cx + rad + 3
    if x0 < w - 3:
        rows = range(hb + 2, h - 2, 2)
        for i, y in enumerate(rows):
            L = int((w - 2 - x0) * (0.35 + 0.65 * r.random()))
            col = amber if i % 4 == 0 else (cyan * 0.8 if i % 2 else green * 0.8)
            R[y, x0:x0 + L] = col
    R[-3, 2:w - 2] = cyan * 0.3
    R[-3, 2:2 + int((w - 4) * 0.7)] = red * 0.9


def paint_faces(td, boxes, seed=7):
    """
    Hand-painting in code. td is the atlas as [row(top->bottom), col, rgba] in sRGB 0..1.
    For every face rectangle it adds, depending on the material kind:
      - bevels: dark 1-texel outline + light top/left inner edge
      - armour panel seams with highlight lip, rivets in panel corners, per-panel tint
      - concrete formwork joints, cracks (random walks), speckle and rain streaks
      - brushed-metal rows and corner bolts on steel
      - slatted grilles on vents, reflections on glass, stripes on hazard
      - vertical grime gradient on side faces; hot-centre gradient on glowing parts
    """
    r = np.random.default_rng(seed)
    for bi, b in enumerate(boxes):
        kind = b["kind"]
        for face, (u, v, w, h) in b["rects"].items():
            R = td[v:v + h, u:u + w, :3]
            side = face in SIDE_FACES
            yy, xx = np.mgrid[0:h, 0:w]

            if kind == "screen":
                paint_screen(R, w, h, seed=bi * 7 + len(face))
                continue

            if kind in GLOW_KINDS:
                cy, cx = (h - 1) / 2, (w - 1) / 2
                d = np.maximum(np.abs(yy - cy) / max(cy, 0.5), np.abs(xx - cx) / max(cx, 0.5))
                hot = np.clip(1 - d, 0, 1) ** 1.5
                R[:] = R * (0.75 + 0.25 * hot[..., None]) + hot[..., None] * 0.35 * np.array([1, 0.85, 0.75])
                np.clip(R, 0, 1, out=R)
                continue

            if kind == "hazard":
                stripe = ((xx + yy + u + v) // 4) % 2 == 0
                R[:] = np.where(stripe[..., None], _hex01("#e0b020"), _hex01("#17161a"))
                R[:] *= (0.85 + 0.15 * r.random((h, w)))[..., None]

            if kind == "paint" and w >= 6 and h >= 6:
                P = 16
                ox, oy = (u * 7 + bi) % P, (v * 5 + bi) % P
                pcol = (xx + ox) // P
                prow = (yy + oy) // P
                tint = 1 + (((pcol * 7 + prow * 13 + bi) % 5) - 2) * 0.018
                R[:] *= tint[..., None]
                seam_x = (xx + ox) % P == 0
                seam_y = (yy + oy) % P == 0
                lip_x = (xx + ox) % P == 1
                lip_y = (yy + oy) % P == 1
                R[seam_x | seam_y] *= 0.68
                R[(lip_x | lip_y) & ~(seam_x | seam_y)] = np.minimum(R[(lip_x | lip_y) & ~(seam_x | seam_y)] * 1.07, 1)
                mx, my = (xx + ox) % P, (yy + oy) % P
                rivet = ((mx == 3) | (mx == P - 3)) & ((my == 3) | (my == P - 3))
                R[rivet] *= 0.55
                if side and w >= 20 and h >= 10 and r.random() < 0.35:
                    band = slice(2, 4)
                    R[band, 2:-2] = _hex01("#a3221c")
                for _ in range(max(1, (w * h) // 600)):
                    sx, sy = r.integers(0, w), r.integers(0, h)
                    ln = r.integers(2, 6)
                    R[sy, sx:sx + ln] *= 0.8 if r.random() < 0.5 else 1.1

            if kind == "concrete":
                R[:] *= (0.93 + 0.07 * r.random((h, w)))[..., None]
                R[r.random((h, w)) < 0.025] *= 0.7
                if side:
                    R[(yy + v) % 12 == 0] *= 0.86
                    for _ in range(max(1, w // 10)):
                        sx = r.integers(0, w)
                        ln = r.integers(h // 3, h + 1)
                        R[:ln, sx] *= 0.9
                else:
                    R[((xx + u) % 24 == 0) | ((yy + v) % 24 == 0)] *= 0.84
                for _ in range((w * h) // 900):
                    y0, x0 = r.integers(0, h), r.integers(0, w)
                    for _k in range(r.integers(6, 18)):
                        R[y0, x0] *= 0.55
                        y0 = int(np.clip(y0 + r.integers(-1, 2), 0, h - 1))
                        x0 = int(np.clip(x0 + r.integers(0, 2) * (1 if r.random() < 0.5 else -1), 0, w - 1))

            if kind in ("steel", "hull"):
                R[:] *= (0.94 + 0.12 * r.random((h, 1)))[..., None]  # brushed rows
                if w >= 8 and h >= 8:
                    for (by, bx) in ((2, 2), (2, w - 3), (h - 3, 2), (h - 3, w - 3)):
                        R[by, bx] *= 0.5
                        R[by - 1, bx] = np.minimum(R[by - 1, bx] * 1.3, 1)

            if kind == "vent" and h >= 3:
                R[yy % 3 == 0] = np.minimum(R[yy % 3 == 0] * 1.9 + 0.04, 1)
                R[yy % 3 == 2] *= 0.35

            if kind == "dark" and w * h > 64:
                R[((xx + u) % 8 == 0) & ((yy + v) % 8 == 0)] *= 1.8

            if kind == "glass":
                refl = ((xx - yy * 0.8) % max(w, 6)) < 2
                R[refl] = np.minimum(R[refl] + 0.18, 1)

            # Grime: side faces darken toward the bottom; tops get a faint edge AO.
            if side and h >= 4 and kind not in ("glass",):
                g = 1 - 0.22 * (yy / max(h - 1, 1)) ** 2
                R[:] *= g[..., None]

            # Bevel: dark outline, light inner top/left edge.
            if w >= 3 and h >= 3:
                lift = 1.45 if kind in ("dark", "vent") else 1.12
                R[1, 1:-1] = np.minimum(R[1, 1:-1] * lift, 1)
                R[1:-1, 1] = np.minimum(R[1:-1, 1] * (1 + (lift - 1) * 0.6), 1)
                edge = 1.6 if kind in ("dark", "vent") else 0.7
                for sl in ((0, slice(None)), (-1, slice(None)), (slice(None), 0), (slice(None), -1)):
                    R[sl] = np.clip(R[sl] * edge, 0, 1)
            np.clip(R, 0, 1, out=R)


def assign_materials(objs, mats):
    for o in objs:
        o.data.materials.clear()
        for m in mats:
            o.data.materials.append(m)
        o.data.polygons.foreach_set("material_index", list(o["mat_indices"]))
        o.data.update()


def bake(objs, boxes, kinds, tex, label):
    """
    Bakes the procedural colour into the atlas, then builds the glowmask straight from the UV
    rectangles of glowing boxes (exact, no second bake). Fails loudly if either is wrong, so a
    blank glowmask can never reach the game again (GeckoLib crashes on an empty glow layer).
    """
    atlas = bpy.data.images.new(f"{label}_atlas", tex, tex, alpha=True)
    assign_materials(objs, [bake_material(k, atlas, False) for k in kinds])
    for o in bpy.context.view_layer.objects:
        o.select_set(False)
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.context.scene.cycles.samples = 1
    bpy.ops.object.bake(type="EMIT", margin=0, use_clear=True)

    color = np.empty(tex * tex * 4, dtype=np.float32)
    atlas.pixels.foreach_get(color)
    color = color.reshape(tex, tex, 4)  # row 0 = bottom of the image in Blender
    color[..., 3] = 1.0
    td = np.ascontiguousarray(color[::-1])  # top-down, matching the UV rects
    paint_faces(td, boxes)
    color = np.ascontiguousarray(td[::-1])

    mask = np.zeros((tex, tex), dtype=bool)
    for b in boxes:
        if b["kind"] not in GLOW_KINDS:
            continue
        for (u, v, w, h) in b["rects"].values():
            mask[tex - (v + h):tex - v, u:u + w] = True

    red_mask = np.zeros((tex, tex), dtype=bool)
    for b in boxes:
        if b["kind"] in ("glow", "amber"):
            for (u, v, w, h) in b["rects"].values():
                red_mask[tex - (v + h):tex - v, u:u + w] = True
    glow_px = color[red_mask]
    if not mask.any():
        raise RuntimeError(f"{label}: glowmask is empty")
    reddish = (glow_px[:, 0] > glow_px[:, 2] + 0.15).mean()
    if reddish < 0.8:
        raise RuntimeError(f"{label}: glow faces did not bake with glow colours ({reddish:.0%} red)")

    g = np.zeros_like(color)
    g[mask] = color[mask]
    g[mask, 3] = 1.0
    glow = bpy.data.images.new(f"{label}_glowmask", tex, tex, alpha=True)
    glow.pixels.foreach_set(g.ravel())
    atlas.pixels.foreach_set(color.ravel())
    print(f"[{label}] glowmask: {int(mask.sum())} px, {reddish:.0%} glow-coloured")
    return atlas, glow


def save_png(image, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.filepath_raw = path
    image.file_format = "PNG"
    image.save()


# =============================================================================================
# Exporters
# =============================================================================================


def export_geo(bones, boxes, tex, path):
    out_bones = []
    for name, empty in bones.items():
        entry = {"name": name, "pivot": list(empty["pivot_mc"])}
        if empty.parent:
            entry["parent"] = empty.parent.name
        cubes = []
        for b in boxes:
            if b["bone"] != name:
                continue
            size = [round(b["max"][k] - b["min"][k], 2) for k in range(3)]
            uv = {f: {"uv": [b["rects"][f][0], b["rects"][f][1]],
                      "uv_size": [b["rects"][f][2], b["rects"][f][3]]} for f in FACES}
            cube = {"origin": b["min"], "size": size, "uv": uv}
            if "rot_axis" in b:
                cube["pivot"] = [round(v, 3) for v in b["rot_pivot"]]
                cube["rotation"] = [0, b["rot_angle"], 0] if b["rot_axis"] == "y" else [0, 0, b["rot_angle"]]
            cubes.append(cube)
        if cubes:
            entry["cubes"] = cubes
        out_bones.append(entry)
    geo = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry.railgun",
                "texture_width": tex, "texture_height": tex,
                "visible_bounds_width": 44, "visible_bounds_height": 14,
                "visible_bounds_offset": [0, 6, 0],
            },
            "bones": out_bones,
        }],
    }
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(geo, f, indent=1)


def export_item_model(boxes, tex, path, texture_id):
    elements = []
    for b in boxes:
        faces = {}
        for f in FACES:
            u, v, w, h = b["rects"][f]
            k = 16.0 / tex
            faces[f] = {"uv": [round(u * k, 4), round(v * k, 4), round((u + w) * k, 4), round((v + h) * k, 4)],
                        "texture": "#0"}
        elements.append({"from": b["min"], "to": b["max"], "faces": faces})
    model = {
        "textures": {"0": texture_id, "particle": texture_id},
        "elements": elements,
        "gui_light": "side",
        "display": {
            "gui": {"rotation": [12, 200, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
            "ground": {"translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed": {"rotation": [0, 180, 0], "scale": [1.0, 1.0, 1.0]},
            "head": {"translation": [0, 13, 7]},
            "thirdperson_righthand": {"rotation": [-70, 180, 0], "translation": [-3.5, 2.5, -3], "scale": [0.65, 0.65, 0.65]},
            "thirdperson_lefthand": {"rotation": [-70, 180, 0], "translation": [3.5, 2.5, -3], "scale": [0.65, 0.65, 0.65]},
            "firstperson_righthand": {"rotation": [0, 180, 0], "translation": [-2, 2, 0], "scale": [0.7, 0.7, 0.7]},
            "firstperson_lefthand": {"rotation": [0, 180, 0], "translation": [2, 2, 0], "scale": [0.7, 0.7, 0.7]},
        },
    }
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(model, f, indent=1)


# =============================================================================================
# Collision heightfield (consumed by the mod to place invisible collider blocks)
# =============================================================================================

COLLISION_BONES = {"core", "deck_n", "deck_e", "deck_w",
                   "shell_pp", "shell_pn", "shell_np", "shell_nn"}
TURRET_COLLISION_RADIUS = 28.0   # px, the turret hull footprint (it rotates, so a disc)
TURRET_COLLISION_TOP = 107.0     # px, hull roof


def _inside(box, mx, mz):
    x0, _, z0 = box["min"]
    x1, _, z1 = box["max"]
    if "rot_axis" in box:
        if box["rot_axis"] != "y":
            return False
        px, _, pz = box["rot_pivot"]
        a = math.radians(box["rot_angle"])
        dx, dz = mx - px, mz - pz
        # rotate the point into the box's unrotated frame
        lx = px + dx * math.cos(a) - dz * math.sin(a)
        lz = pz + dx * math.sin(a) + dz * math.cos(a)
        mx, mz = lx, lz
    return x0 <= mx <= x1 and z0 <= mz <= z1


def collision_heightfield(boxes, radius=8):
    """
    Height (px above the entity's feet) of the deployed emplacement at the centre of every block
    column around it. GeckoLib mirrors model X and renders entities rotated 180 degrees about Y,
    which together give world (dx, dz) = (model x, -model z) / 16.
    """
    cols = []
    for dx in range(-radius, radius + 1):
        for dz in range(-radius, radius + 1):
            mx, mz = dx * 16.0, -dz * 16.0
            # Stack upward: a box counts if it starts on the ground or on geometry already counted
            # (foundation -> tier 2 -> tier 3 -> cap -> deck), so floating details are ignored.
            here = [b for b in boxes if b["bone"] in COLLISION_BONES and _inside(b, mx, mz)]
            h = 0.0
            changed = True
            while changed:
                changed = False
                for b in here:
                    if b["min"][1] <= h + 0.5 and b["max"][1] > h:
                        h = b["max"][1]
                        changed = True
            if mx * mx + mz * mz <= TURRET_COLLISION_RADIUS ** 2:
                h = max(h, TURRET_COLLISION_TOP)
            if h >= 3.0:
                cols.append([dx, dz, int(round(h))])
    return cols


def export_collision(boxes, path):
    cols = collision_heightfield(boxes)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({"comment": "dx, dz (blocks from the railgun's block), height in px (16 = 1 block)",
                   "columns": cols}, f)
    return cols


# =============================================================================================
# Animation: authored as Blender Actions, exported to GeckoLib JSON
# =============================================================================================

QUADS = {"pp": (1, 1), "pn": (1, -1), "np": (-1, 1), "nn": (-1, -1)}
DECKS = {"deck_n": (0, -1), "deck_e": (1, 0), "deck_w": (-1, 0)}
ONE = [1, 1, 1]
ZERO = [0, 0, 0]
DEPLOY_LEN = 12.0
PANEL_IN = [0, 0, -4.2]          # tablet slid into the core
STRUT_SHIFT = {1: 29.0, 2: 58.0, 3: BULWARK_D * math.sqrt(2)}   # px along the diagonal

# GeckoLib easings (the easing on a keyframe shapes the segment that ENDS at that keyframe).
IO, OUT, IN, BACK, SLAM, SINE = ("easeInOutCubic", "easeOutCubic", "easeInCubic", "easeOutBack",
                                 "easeInQuart", "easeInOutSine")


def kv(k):
    """Keyframe value: either [x, y, z] or ([x, y, z], easing)."""
    return k[0] if isinstance(k, tuple) else k


def ke(k):
    return k[1] if isinstance(k, tuple) else None


def _q_in(q, f=1.0):
    sx, sz = QUADS[q]
    return [-sx * BULWARK_D * f, 0, -sz * BULWARK_D * f]


def _strut_in(q, k, f=1.0):
    sx, sz = QUADS[q]
    d = STRUT_SHIFT[k] / math.sqrt(2) * f
    return [-sx * d, 0, -sz * d]


def _deck_in(d, f=1.0):
    ax, az = DECKS[d]
    return [-ax * DECK_OUT * f, 0, -az * DECK_OUT * f]


def _clamp_in(q, lift=6.0):
    sx, sz = QUADS[q]
    k = 30 / math.sqrt(2)
    return [-sx * k, lift, -sz * k]


def _pod_pose():
    """Everything stowed inside the pod."""
    t = {}
    for q in QUADS:
        t[f"shell_{q}"] = {"position": {0.0: _q_in(q)}}
        for k in (1, 2, 3):
            t[f"strut_{q}_{k}"] = {"position": {0.0: _strut_in(q, k)}}
        t[f"clamp_{q}"] = {"position": {0.0: _clamp_in(q)}}
        t[f"bolt_{q}"] = {"position": {0.0: ZERO}}
        t[f"beacon_{q}"] = {"rotation": {0.0: ZERO}}
    for d in DECKS:
        t[d] = {"position": {0.0: _deck_in(d)}}
    t["turret"] = {"position": {0.0: [0, POD_TURRET_DROP, 0]}}
    t["radar"] = {"scale": {0.0: [1, 0.08, 1]}}
    t["antenna"] = {"scale": {0.0: [1, 0.08, 1]}}
    t["dish"] = {"rotation": {0.0: ZERO}}
    t["seg1"] = {"position": {0.0: [0, 0, SEG1_IN]}}
    t["seg2"] = {"position": {0.0: [0, 0, SEG2_IN]}}
    for c in ("coils_a", "coils_b", "coils_c"):
        t[c] = {"scale": {0.0: [0.8, 0.8, 1]}}
    t["vents"] = {"position": {0.0: [0, -4, 0]}}
    t["pod_panel"] = {"position": {0.0: ZERO}}
    return t


# Deployment timeline (seconds). RailgunConfig mirrors the ticks marked *.
T_BOLTS = 0.3            # locking bolts pop one after another
T_UNLATCH = 1.8          # shell quarters crack open
T_GLIDE = (2.3, 3.8)     # quarters + struts glide out
T_DECKS = 3.9            # deck plates, staggered 0.25 s, 0.7 s each
T_CLAMP_OUT = (5.0, 5.9)
T_SLAM = 6.5             # * CLAMP_SLAM_TICK = 130
T_TURRET = (6.8, 8.3)
T_RADAR = 8.3
T_RAIL = 8.8             # * RAIL_LOWER_TICK = 176, lowers over 26 ticks (eased, procedural)
T_SEG = (10.2, 10.7)
T_COILS = 11.2
T_PANEL = 11.55


def _deploy():
    """
    12 s, one phase at a time with eased motion and small mechanical details in between:
      0.3-1.75  locking bolts pop out in sequence, then retract; beacons spin throughout
      1.8-2.1   the four armour quarters unlatch (crack open)
      2.3-3.8   quarters glide out along the diagonals; the telescoping struts extend with them
      3.9-5.1   deck plates slide out from under the pod, one after another
      5.0-6.5   ground clamps slide out raised, wind up and SLAM into the ground at 6.5 s
      6.8-8.3   turret rises out of the core
      8.3-9.0   radar mast and antenna telescope up, the dish starts turning
      8.8-10.1  (procedural) the rail lowers from vertical
      10.2-11.25 barrel telescopes: segment 1, then segment 2
      11.2-11.85 coil rings snap on in sequence, cooling fins rise
      11.55-12  the tablet slides back out of the core
    """
    t = {}
    g0, g1 = T_GLIDE
    for i, q in enumerate(QUADS):
        sx, sz = QUADS[q]
        b0 = T_BOLTS + 0.15 * i
        t[f"bolt_{q}"] = {"position": {0.0: ZERO, b0: ZERO, b0 + 0.25: ([sx * 3, 4, 0], BACK),
                                       1.5: [sx * 3, 4, 0], 1.75: (ZERO, IN)}}
        t[f"beacon_{q}"] = {"rotation": {0.0: ZERO, 12.0: [0, 360 * 9, 0]}}
        t[f"shell_{q}"] = {"position": {0.0: _q_in(q), T_UNLATCH: _q_in(q), T_UNLATCH + 0.3: (_q_in(q, 0.88), OUT),
                                        g0: _q_in(q, 0.88), g1: (ZERO, IO)}}
        for k in (1, 2, 3):
            t[f"strut_{q}_{k}"] = {"position": {0.0: _strut_in(q, k), T_UNLATCH: _strut_in(q, k),
                                                T_UNLATCH + 0.3: (_strut_in(q, k, 0.88), OUT),
                                                g0: _strut_in(q, k, 0.88), g1: (ZERO, IO)}}
        c0, c1 = T_CLAMP_OUT
        t[f"clamp_{q}"] = {"position": {0.0: _clamp_in(q), c0: _clamp_in(q), c1: ([0, 10, 0], IO),
                                        T_SLAM - 0.4: [0, 10, 0], T_SLAM - 0.15: ([0, 13, 0], OUT),
                                        T_SLAM: (ZERO, SLAM)}}
    for i, d in enumerate(("deck_e", "deck_n", "deck_w")):
        d0 = T_DECKS + 0.25 * i
        t[d] = {"position": {0.0: _deck_in(d), d0: _deck_in(d), d0 + 0.7: (ZERO, IO)}}
    u0, u1 = T_TURRET
    t["turret"] = {"position": {0.0: [0, POD_TURRET_DROP, 0], u0: [0, POD_TURRET_DROP, 0], u1: (ZERO, IO)}}
    t["radar"] = {"scale": {0.0: [1, 0.08, 1], T_RADAR: [1, 0.08, 1], T_RADAR + 0.5: (ONE, BACK)}}
    t["antenna"] = {"scale": {0.0: [1, 0.08, 1], T_RADAR + 0.2: [1, 0.08, 1], T_RADAR + 0.7: (ONE, BACK)}}
    t["dish"] = {"rotation": {0.0: ZERO, T_RADAR + 0.5: ZERO, 12.0: ([0, 360, 0], "easeInSine")}}
    s0, s1 = T_SEG
    t["seg1"] = {"position": {0.0: [0, 0, SEG1_IN], s0: [0, 0, SEG1_IN], s0 + 0.55: (ZERO, OUT)}}
    t["seg2"] = {"position": {0.0: [0, 0, SEG2_IN], s1: [0, 0, SEG2_IN], s1 + 0.55: (ZERO, OUT)}}
    for i, c in enumerate(("coils_a", "coils_b", "coils_c")):
        k0 = T_COILS + 0.12 * i
        t[c] = {"scale": {0.0: [0.8, 0.8, 1], k0: [0.8, 0.8, 1], k0 + 0.3: (ONE, BACK)}}
    t["vents"] = {"position": {0.0: [0, -4, 0], 11.5: [0, -4, 0], 11.85: (ZERO, BACK)}}
    t["pod_panel"] = {"position": {0.0: PANEL_IN, T_PANEL: PANEL_IN, 12.0: (ZERO, BACK)}}
    return t


_MIRROR = {"easeIn": "easeOut", "easeOut": "easeIn"}


def _mirror_easing(e):
    if e is None:
        return None
    for a, b in _MIRROR.items():
        if e.startswith(a) and not e.startswith("easeInOut"):
            return b + e[len(a):]
    return e


def reverse_tracks(tracks, length):
    """Plays a track backwards. A segment's easing lives on its END key, so when the segment is
    reversed the easing moves to the other key and in/out swap (easeOutBack -> easeInBack)."""
    out = {}
    for bone, chans in tracks.items():
        out[bone] = {}
        for ch, keys in chans.items():
            times = sorted(keys)
            new = {}
            for i, t in enumerate(times):
                nxt = keys[times[i + 1]] if i + 1 < len(times) else None
                e = _mirror_easing(ke(nxt)) if nxt is not None else None
                v = kv(keys[t])
                new[round(length - t, 4)] = (v, e) if e else v
            out[bone][ch] = dict(sorted(new.items()))
    return out


DEPLOY = _deploy()
# Pack-up: the deployment in reverse; the tablet slides in first and back out at the very end.
PACK = reverse_tracks(DEPLOY, DEPLOY_LEN)
PACK["pod_panel"] = {"position": {0.0: ZERO, 0.5: (PANEL_IN, IO), 11.55: PANEL_IN, 12.0: (ZERO, BACK)}}

POD_POSE = _pod_pose()
PANEL_OPEN_ANIM = dict(POD_POSE)
PANEL_OPEN_ANIM["pod_panel"] = {"position": {0.0: ZERO, 0.3: ([0, 0, 0.8], OUT), 1.2: (PANEL_IN, IO)}}
PANEL_OPEN_ANIM.update({f"beacon_{q}": {"rotation": {0.0: ZERO, 1.5: [0, 540, 0]}} for q in QUADS})


def full_tracks(tracks, length):
    """
    Gives every channel explicit keyframes at 0 and at the end of the animation (holding its
    first/last value). GeckoLib interpolates the span before a channel's first keyframe from the
    default value (scale 0!), so a track that starts late makes the bone vanish and pop.
    """
    out = {}
    for bone, chans in tracks.items():
        out[bone] = {}
        for ch, keys in chans.items():
            k = dict(keys)
            times = sorted(k)
            if times[0] > 0:
                k[0.0] = kv(k[times[0]])
            if times[-1] < length:
                k[length] = kv(k[times[-1]])
            out[bone][ch] = dict(sorted(k.items()))
    return out


_RAW_ANIMATIONS = {
    # name: (length_s, loop, {bone: {"scale"/"position"/"rotation": {time_s: [x,y,z] | ([x,y,z], easing)}}})
    # Turret yaw and barrel pitch are procedural (RailgunModel). Rotation keys are only used
    # for spinning parts (beacons, radar dish), where the sign convention does not matter.
    "animation.railgun.pod": (1.0, True, POD_POSE),
    "animation.railgun.panel_open": (1.5, "hold_on_last_frame", PANEL_OPEN_ANIM),
    "animation.railgun.deploy": (DEPLOY_LEN, "hold_on_last_frame", DEPLOY),
    "animation.railgun.pack": (DEPLOY_LEN, "hold_on_last_frame", PACK),
    "animation.railgun.idle": (4.0, True, {
        "coils_a": {"scale": {0.0: ONE, 2.0: ([1.025, 1.025, 1], SINE), 4.0: (ONE, SINE)}},
        "coils_b": {"scale": {0.0: [1.02, 1.02, 1], 2.0: (ONE, SINE), 4.0: ([1.02, 1.02, 1], SINE)}},
        "dish": {"rotation": {0.0: ZERO, 4.0: [0, 360, 0]}},
        "vents": {"position": {0.0: ZERO, 2.0: ([0, 0.4, 0], SINE), 4.0: (ZERO, SINE)}},
    }),
    "animation.railgun.charge": (0.5, True, {
        "coils_a": {"scale": {0.0: [1.04, 1.04, 1], 0.25: ([1.14, 1.14, 1], SINE), 0.5: ([1.04, 1.04, 1], SINE)}},
        "coils_b": {"scale": {0.0: [1.14, 1.14, 1], 0.25: ([1.04, 1.04, 1], SINE), 0.5: ([1.14, 1.14, 1], SINE)}},
        "coils_c": {"scale": {0.0: [1.04, 1.04, 1], 0.25: ([1.14, 1.14, 1], SINE), 0.5: ([1.04, 1.04, 1], SINE)}},
        "vents": {"position": {0.0: [0, 2.5, 0], 0.25: ([0, 3.2, 0], SINE), 0.5: ([0, 2.5, 0], SINE)}},
        "dish": {"rotation": {0.0: ZERO, 0.5: [0, 180, 0]}},
        "turret": {"position": {0.0: ZERO, 0.125: [0, -0.35, 0], 0.25: ZERO, 0.375: [0, -0.2, 0], 0.5: ZERO}},
    }),
    "animation.railgun.fire": (2.0, False, {
        "seg1": {"position": {0.0: ZERO, 0.05: ([0, 0, 14], "easeOutExpo"), 0.5: ([0, 0, 9], OUT), 1.4: (ZERO, IO)}},
        "seg2": {"position": {0.0: ZERO, 0.05: ([0, 0, 12], "easeOutExpo"), 0.6: ([0, 0, 6], OUT), 1.6: (ZERO, IO)}},
        "barrel": {"position": {0.0: ZERO, 0.05: ([0, 0, 9], "easeOutExpo"), 1.1: (ZERO, IO)}},
        "turret": {"position": {0.0: ZERO, 0.05: ([0, -4, 0], "easeOutExpo"), 0.35: ([0, 0.6, 0], OUT), 0.7: (ZERO, IO)}},
        "vents": {"position": {0.0: [0, 3, 0], 0.08: ([0, 7, 0], OUT), 1.4: [0, 7, 0], 2.0: (ZERO, IO)}},
        "coils_a": {"scale": {0.0: [1.4, 1.4, 1], 0.4: ([1.12, 1.12, 1], OUT), 2.0: (ONE, IO)}},
        "coils_b": {"scale": {0.0: [1.4, 1.4, 1], 0.5: ([1.12, 1.12, 1], OUT), 2.0: (ONE, IO)}},
        "coils_c": {"scale": {0.0: [1.4, 1.4, 1], 0.6: ([1.12, 1.12, 1], OUT), 2.0: (ONE, IO)}},
    }),
}

ANIMATIONS = {name: (length, loop, full_tracks(tracks, length))
              for name, (length, loop, tracks) in _RAW_ANIMATIONS.items()}

CHANNEL_PATH = {"position": "location", "rotation": "rotation_euler", "scale": "scale"}


def reset_pose(bones):
    for e in bones.values():
        if e.animation_data:
            e.animation_data.action = None
        e.location = Vector(e["rest_location"])
        e.rotation_euler = (0, 0, 0)
        e.scale = (1, 1, 1)


def author_animations(bones):
    """Keyframes every animation as Actions named '<animation>|<bone>' (linear interpolation)."""
    for anim, (_length, _loop, tracks) in ANIMATIONS.items():
        for bone_name, channels in tracks.items():
            empty = bones[bone_name]
            reset_pose(bones)
            empty.animation_data_create()
            action = bpy.data.actions.new(f"{anim}|{bone_name}")
            action.use_fake_user = True
            empty.animation_data.action = action
            rest = Vector(empty["rest_location"])
            for channel, keys in channels.items():
                for t, key in keys.items():
                    val = kv(key)
                    frame = t * FPS
                    if channel == "position":
                        empty.location = rest + mc_to_b(val)
                    elif channel == "rotation":
                        rx, ry, rz = (math.radians(a) for a in val)
                        empty.rotation_euler = (rx, -rz, ry)
                    else:
                        empty.scale = (val[0], val[2], val[1])
                    empty.keyframe_insert(CHANNEL_PATH[channel], frame=frame)
            for fc in action.fcurves:
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR"
    reset_pose(bones)


def export_animations(bones, path):
    """Reads the Actions back (not the Python table) so edits made in Blender are exported."""
    out = {}
    for anim, (length, loop, _tracks) in ANIMATIONS.items():
        anim_bones = {}
        for bone_name, empty in bones.items():
            action = bpy.data.actions.get(f"{anim}|{bone_name}")
            if not action:
                continue
            rest = Vector(empty["rest_location"])
            channels = {}
            for channel, data_path in CHANNEL_PATH.items():
                curves = {fc.array_index: fc for fc in action.fcurves if fc.data_path == data_path}
                if not curves:
                    continue
                table = _tracks.get(bone_name, {}).get(channel, {})
                easings = {round(t, 3): ke(k) for t, k in table.items() if ke(k)}
                frames = sorted({kp.co.x for fc in curves.values() for kp in fc.keyframe_points})
                default = {"location": rest, "rotation_euler": Vector((0, 0, 0)), "scale": Vector((1, 1, 1))}[data_path]
                keys = {}
                for frame in frames:
                    b = [curves[i].evaluate(frame) if i in curves else default[i] for i in range(3)]
                    if channel == "position":
                        d = Vector(b) - rest
                        val = [d.x * 16, d.z * 16, -d.y * 16]
                    elif channel == "rotation":
                        val = [math.degrees(b[0]), math.degrees(b[2]), -math.degrees(b[1])]
                    else:
                        val = [b[0], b[2], b[1]]
                    vec = [round(c, 4) + 0.0 for c in val]
                    easing = easings.get(round(frame / FPS, 3))
                    keys[f"{frame / FPS:.3f}".rstrip("0").rstrip(".") or "0"] = (
                        {"vector": vec, "easing": easing} if easing else vec)
                channels[channel] = keys
            if channels:
                anim_bones[bone_name] = channels
        entry = {"animation_length": length, "bones": anim_bones}
        if loop:
            entry["loop"] = loop
        out[anim] = entry
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({"format_version": "1.8.0", "animations": out}, f, indent=1)


def pose(bones, anim, time_s):
    reset_pose(bones)
    for name, empty in bones.items():
        action = bpy.data.actions.get(f"{anim}|{name}")
        if action:
            empty.animation_data_create()
            empty.animation_data.action = action
    bpy.context.scene.frame_set(int(round(time_s * FPS)))


# =============================================================================================
# Rendering
# =============================================================================================


def setup_camera_and_lights():
    scene = bpy.context.scene
    target = link(bpy.data.objects.new("cam_target", None))
    cam = link(bpy.data.objects.new("camera", bpy.data.cameras.new("camera")))
    track = cam.constraints.new("TRACK_TO")
    track.target = target
    track.track_axis = "TRACK_NEGATIVE_Z"
    track.up_axis = "UP_Y"
    scene.camera = cam

    key = link(bpy.data.objects.new("key", bpy.data.lights.new("key", "SUN")))
    key.data.energy = 3.5
    key.rotation_euler = (math.radians(50), math.radians(10), math.radians(-35))
    fill = link(bpy.data.objects.new("fill", bpy.data.lights.new("fill", "SUN")))
    fill.data.energy = 1.2
    fill.rotation_euler = (math.radians(60), 0, math.radians(110))
    rim = link(bpy.data.objects.new("rim", bpy.data.lights.new("rim", "SUN")))
    rim.data.energy = 1.5
    rim.data.color = (0.65, 0.75, 1.0)
    rim.rotation_euler = (math.radians(70), 0, math.radians(160))

    ground = bpy.data.meshes.new("ground")
    ground.from_pydata([(-60, -60, 0), (60, -60, 0), (60, 60, 0), (-60, 60, 0)], [], [(0, 1, 2, 3)])
    gobj = link(bpy.data.objects.new("ground", ground))
    gmat = bpy.data.materials.new("ground")
    gmat.use_nodes = True
    gmat.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value = srgb("#1a1c20")
    ground.materials.append(gmat)
    return cam, target, gobj


def render(path, cam, target, cam_loc, look_at, res, samples=48, lens=50):
    scene = bpy.context.scene
    cam.location = cam_loc
    target.location = look_at
    cam.data.lens = lens
    scene.render.resolution_x = scene.render.resolution_y = res
    scene.render.resolution_percentage = 100
    scene.cycles.samples = samples
    scene.cycles.use_denoising = False
    scene.render.image_settings.file_format = "PNG"
    scene.render.filepath = path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    bpy.ops.render.render(write_still=True)


def set_visible(objs, visible):
    for o in objs:
        o.hide_render = not visible


# =============================================================================================
# Main
# =============================================================================================


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True, help="assets/orbital_railgun directory")
    ap.add_argument("--out", required=True, help="directory for renders and the .blend")
    ap.add_argument("--collision", help="output path of the collision heightfield JSON")
    ap.add_argument("--no-render", action="store_true")
    args = ap.parse_args(argv)
    assets, out = os.path.abspath(args.assets), os.path.abspath(args.out)

    reset_scene()
    kinds = KINDS

    # ---------------- Railgun ----------------
    rg_bones = build_railgun()
    rg_boxes = extract_boxes(rg_bones)
    rg_tex = pack_faces(rg_boxes, texels_per_px=1, start_size=256)
    rg_bake = build_bake_objects(rg_boxes, rg_bones, rg_tex, kinds)
    rg_atlas, rg_glow = bake(rg_bake, rg_boxes, kinds, rg_tex, "railgun")
    save_png(rg_atlas, os.path.join(assets, "textures/entity/railgun.png"))
    save_png(rg_glow, os.path.join(assets, "textures/entity/railgun_glowmask.png"))
    export_geo(rg_bones, rg_boxes, rg_tex, os.path.join(assets, "geo/railgun.geo.json"))
    if args.collision:
        cols = export_collision(rg_boxes, os.path.abspath(args.collision))
        print(f"[railgun] collision: {len(cols)} columns")
    author_animations(rg_bones)
    export_animations(rg_bones, os.path.join(assets, "animations/railgun.animation.json"))
    print(f"[railgun] {len(rg_boxes)} cubes, {rg_tex}px atlas, {len(ANIMATIONS)} animations")

    # ---------------- Remote ----------------
    offset = Vector((120, 0, 0))  # keep it away from the railgun in the scene
    rm_bones = build_remote(offset)
    rm_boxes = extract_boxes(rm_bones, offset=offset)
    rm_tex = pack_faces(rm_boxes, texels_per_px=4, start_size=64)
    rm_bake = build_bake_objects(rm_boxes, rm_bones, rm_tex, kinds, offset=offset)
    rm_atlas, rm_glow = bake(rm_bake, rm_boxes, kinds, rm_tex, "remote")
    save_png(rm_atlas, os.path.join(assets, "textures/item/railgun_remote.png"))
    export_item_model(rm_boxes, rm_tex, os.path.join(assets, "models/item/railgun_remote.json"),
                      "orbital_railgun:item/railgun_remote")
    print(f"[remote] {len(rm_boxes)} elements, {rm_tex}px atlas")

    # ---------------- Preview materials + renders ----------------
    for objs, atlas, glow, name in ((rg_bake, rg_atlas, rg_glow, "rg"), (rm_bake, rm_atlas, rm_glow, "rm")):
        mat = preview_material(f"preview_{name}", atlas, glow)
        for o in objs:
            o.data.materials.clear()
            o.data.materials.append(mat)
    modelling = [o for o in bpy.context.scene.objects if o.get("export")]
    set_visible(modelling, False)

    if not args.no_render:
        cam, target, ground = setup_camera_and_lights()
        set_visible(rm_bake, False)
        barrel = rg_bones["barrel"]

        def rail_up(up):
            barrel.rotation_euler = (math.radians(90) if up else 0.0, 0, 0)

        pose(rg_bones, "animation.railgun.deploy", DEPLOY_LEN)
        rail_up(False)
        render(os.path.join(out, "railgun_deployed.png"), cam, target, (21.0, -20.0, 10.0), (0.0, -4.0, 4.5), 1100, lens=30)
        render(os.path.join(assets, "icon.png"), cam, target, (23.0, -21.0, 13.0), (0.0, -4.0, 5.0), 128, samples=32, lens=30)
        render(os.path.join(out, "railgun_front.png"), cam, target, (5.0, -16.0, 5.0), (0.0, 0.0, 3.5), 1100, lens=32)
        pose(rg_bones, "animation.railgun.pod", 0.0)
        rail_up(True)
        render(os.path.join(out, "railgun_pod.png"), cam, target, (9.0, -10.0, 6.0), (0.0, 0.0, 4.5), 900, lens=32)
        render(os.path.join(out, "railgun_pod_panel.png"), cam, target, (0.8, -4.9, 3.3), (0.0, -2.0, 3.0), 900, lens=35)
        for t_s in (2.0, 3.0, 5.0, 6.4, 7.6, 9.5):
            pose(rg_bones, "animation.railgun.deploy", t_s)
            rail_up(t_s < T_RAIL)
            render(os.path.join(out, f"railgun_deploy_{t_s:04.1f}s.png"), cam, target, (15.0, -16.0, 9.0), (0.0, 0.0, 3.5), 800, lens=30)
        reset_pose(rg_bones)
        rail_up(False)

        set_visible(rg_bake, False)
        set_visible(rm_bake, True)
        ground.location = (120, 0, -0.01)
        render(os.path.join(out, "tablet.png"), cam, target, (120.6, 1.4, 1.1), (120.5, -0.5, 0.5), 700, lens=50)
        set_visible(rg_bake, True)
        ground.location = (0, 0, 0)

    # ---------------- Save the editable .blend ----------------
    os.makedirs(out, exist_ok=True)
    for img in (rg_atlas, rg_glow, rm_atlas, rm_glow):
        try:
            img.pack()
        except RuntimeError:
            pass
    reset_pose(rg_bones)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(out, "orbital_railgun_models.blend"), check_existing=False)
    print("[done] assets ->", assets, "| renders + .blend ->", out)


if __name__ == "__main__":
    main()
