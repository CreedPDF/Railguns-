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
RG_TPP = 3   # texels per model pixel on the railgun atlas (smooth, high-res look)
RG_PAD = 2   # gutter texels around every face (bilinear filtering)

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
    # loadout attachments, supply capsule, tablet
    "cyan": ("#003a4a", "#10b8e0", "#8af0ff"),
    "violet": ("#25003a", "#8a30e0", "#d8a0ff"),
    "lime": ("#203a00", "#88d820", "#e0ff90"),
    "orange": ("#4a1400", "#e0600c", "#ffb060"),
    "chrome": ("#5a6068", "#a8b0ba", "#e8edf2"),
    "brass": ("#4a3008", "#a8781c", "#e0b85a"),
    "white": ("#8a929c", "#d8dee6", "#f6f8fa"),
}
KINDS = list(PALETTE)
GLOW_KINDS = {"glow", "amber", "screen", "cyan", "violet", "lime", "orange"}


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

    # Flat base colour: all surface detail comes from the clean paint pass.
    rgb = srgb(CLEAN.get(kind, PALETTE[kind][1]))
    emit.inputs["Color"].default_value = rgb
    return mat



def preview_material(name, atlas, glow):
    """Principled material for renders: baked atlas as albedo, glowmask as emission."""
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes.get("Principled BSDF")
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = atlas
    tex.interpolation = "Linear"
    gtex = nt.nodes.new("ShaderNodeTexImage")
    gtex.image = glow
    gtex.interpolation = "Linear"
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


def ring_z(bone, kind, cx, cy, z0, z1, radius, thick, off=(0, 0, 0)):
    """Hollow octagonal ring around an axis parallel to Z: eight tangent slabs rotated about Z."""
    seg = 2 * (radius + thick) * TAN22 + 0.6
    for k in range(8):
        rbox(bone, kind, [cx - seg / 2, cy + radius, z0], [seg, thick, z1 - z0], [cx, cy, (z0 + z1) / 2], "z", 45.0 * k,
             off=off, name="ring")


def ring_y(bone, kind, cx, cz, y0, y1, radius, thick):
    """Hollow octagonal ring around a vertical axis."""
    seg = 2 * (radius + thick) * TAN22 + 0.6
    for k in range(8):
        rbox(bone, kind, [cx - seg / 2, y0, cz + radius], [seg, y1 - y0, thick], [cx, y0, cz], "y", 45.0 * k, name="ringy")


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
    # Armoured field console: back plate, visor hood, side rails, framed display with a glowing
    # border, keypad row and status LEDs.
    box(P, "dark", [-3, 44, 28.6], [6, 8, 1.2])                            # mount arm
    box(P, "dark", [-12, 36, 29.8], [24, 24, 1.4])                         # back plate
    box(P, "steel", [-12.6, 58.2, 31.2], [25.2, 1.8, 2.6])                 # visor hood
    box(P, "steel", [-12.6, 36.2, 31.2], [2.2, 22, 2.0], mirror=("x",))    # side rails
    box(P, "hull", [-12.9, 39, 31.4], [0.4, 16, 1.4], mirror=("x",))       # grip pads
    box(P, "dark", [-10.4, 41.4, 31.2], [20.8, 16.4, 1.2])                 # screen bezel
    box(P, "screen", [-9.5, 42.2, 32.4], [19, 14.8, 0.3])                  # display
    box(P, "cyan", [-10.2, 57.0, 32.42], [20.4, 0.5, 0.25])                # glowing border
    box(P, "cyan", [-10.2, 41.6, 32.42], [20.4, 0.5, 0.25])
    box(P, "cyan", [-10.2, 42.1, 32.42], [0.5, 14.9, 0.25], mirror=("x",))
    for i, (x, k) in enumerate(((-8.2, "glow"), (-4.6, "amber"), (-1.0, "cyan"), (2.6, "cyan"), (6.2, "amber"))):
        box(P, "steel", [x - 0.2, 37.2, 31.2], [2.8, 2.6, 1.6])           # keypad
        box(P, k, [x, 37.5, 32.8], [2.4, 2.0, 0.3])
    box(P, "lime", [10.6, 52.0, 33.2], [1.2, 1.2, 0.3])                    # status LEDs
    box(P, "amber", [10.6, 49.6, 33.2], [1.2, 1.2, 0.3])
    box(P, "glow", [10.6, 47.2, 33.2], [1.2, 1.2, 0.3])

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

    # ---- Loadout attachments (bones "opt_<id>"; the mod shows only the installed ones) ---------
    build_options(bone, B)
    add_docking_arms(bone, B)
    return bones


# Which bones make each option's icon (the first mount of multi-mount options).
OPTION_ICON_BONES = {
    "shield": ["opt_shield__a"], "sentry": ["opt_sentry__a", "opt_sentry__a_head"],
    "seismic": ["opt_seismic__pp"], "targeting": ["opt_targeting", "opt_targeting__laser"],
}
OPTION_IDS = ["lancer", "triprong", "shroud", "scatter", "gravity", "ghost",
              "tungsten", "thermite", "cryo", "ion", "cluster", "singularity", "sunburst",
              "capacitor", "coolant", "overclock", "targeting", "shield", "sentry", "thruster",
              "seismic", "autoloader", "afterburn"]


# Orbital part drops: every attachment bone flies down from orbit on its own, vectors into a hover
# point on this side of its mount, extends docking arms and clamps on (mirrored in PartDrop.java).
DROP_APPROACH = {
    **{f"opt_{m}": (0, 0, -1) for m in ("lancer", "triprong", "shroud", "scatter", "gravity", "ghost")},
    **{f"opt_{a}": (1, 0, 0) for a in ("tungsten", "thermite", "cryo", "ion", "cluster", "singularity", "sunburst")},
    "opt_capacitor": (0, 1, 0), "opt_coolant": (0, 1, 0), "opt_overclock": (0, 1, 0),
    "opt_targeting": (0, 1, 0), "opt_targeting__laser": (-1, 0, 0),
    "opt_shield__a": (0, 1, 0), "opt_shield__b": (0, 1, 0),
    "opt_sentry__a": (0, 1, 0), "opt_sentry__b": (0, 1, 0),
    "opt_thruster": (0, 1, 0),
    "opt_seismic__pp": (0, 1, 0), "opt_seismic__pn": (0, 1, 0), "opt_seismic__np": (0, 1, 0), "opt_seismic__nn": (0, 1, 0),
    "opt_autoloader": (0, 1, 0), "opt_afterburn": (0, -1, 0),
}


def add_docking_arms(bone, B):
    """
    Four telescoping docking arms per drop part: struts with claws that reach from the part's
    contact face toward its mount. Bone "<part>__arms" (pivot at the contact face centre) is
    scaled 0..1 by the mod while the part docks, so the arms extend out of and fold back into it.
    """
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    for name, A in DROP_APPROACH.items():
        if name not in B:
            continue
        pts = []
        for o in bpy.context.scene.objects:
            if o.parent is B[name] and o.get("export"):
                ev = o.evaluated_get(dg)
                pts += [b_to_mc(ev.matrix_world @ Vector(c)) for c in ev.bound_box]
        if not pts:
            continue
        lo = [min(p[k] for p in pts) for k in range(3)]
        hi = [max(p[k] for p in pts) for k in range(3)]
        k = [i for i in range(3) if A[i] != 0][0]
        dirk = -A[k]                                   # arms reach opposite to the approach
        face = hi[k] if dirk > 0 else lo[k]
        o1, o2 = [i for i in range(3) if i != k]
        c = [(lo[i] + hi[i]) / 2 for i in range(3)]
        c[k] = face
        bone(name + "__arms", name, c)
        Ab = B[name + "__arms"]
        L = 10.0
        for s1 in (-1, 1):
            for s2 in (-1, 1):
                p = list(c)
                p[o1] = c[o1] + s1 * (hi[o1] - lo[o1]) * 0.32
                p[o2] = c[o2] + s2 * (hi[o2] - lo[o2]) * 0.32
                strut_o, strut_s = [0, 0, 0], [0, 0, 0]
                for i, (lo_i, sz) in ((o1, (p[o1] - 0.8, 1.6)), (o2, (p[o2] - 0.8, 1.6)),
                                      (k, (face if dirk > 0 else face - L, L))):
                    strut_o[i], strut_s[i] = sz
                box(Ab, "steel", strut_o, strut_s, name="arm")
                claw_o, claw_s = list(strut_o), list(strut_s)
                claw_o[o1] -= 0.7; claw_s[o1] += 1.4
                claw_o[o2] -= 0.7; claw_s[o2] += 1.4
                claw_o[k] = face + dirk * (L - 2.5) if dirk > 0 else face - L
                claw_s[k] = 2.5
                box(Ab, "dark", claw_o, claw_s, name="claw")
                j_o, j_s = list(strut_o), list(strut_s)
                j_o[k] = face + (0 if dirk > 0 else -1.2)
                j_s[k] = 1.2
                j_o[o1] -= 0.3; j_s[o1] += 0.6
                j_o[o2] -= 0.3; j_s[o2] += 0.6
                box(Ab, "cyan", j_o, j_s, name="armjoint")


def build_options(bone, B):
    Y = BARREL_Y
    MZ = -316.0

    # ================= Muzzle devices (children of seg2, at the muzzle) =================
    bone("opt_lancer", "seg2", [0, Y, MZ])
    L = B["opt_lancer"]
    octagon_z("lan_collar", L, "steel", 0, Y, MZ - 6, MZ, 42)
    octagon_z("lan_band", L, "cyan", 0, Y, MZ - 4, MZ - 3, 42.6)
    box(L, "chrome", [-15, Y - 1, MZ - 36], [2, 2, 30], mirror=("x",))
    box(L, "chrome", [-1, Y + 13, MZ - 36], [2, 2, 30])
    box(L, "chrome", [-1, Y - 15, MZ - 36], [2, 2, 30])
    for i, (z, r) in enumerate(((MZ - 14, 16), (MZ - 24, 13), (MZ - 34, 10))):
        ring_z(L, "steel", 0, Y, z - 1.5, z + 1.5, r, 2.5)
        octagon_z(f"lan_lens{i}", L, "cyan", 0, Y, z - 0.8, z + 0.8, r * 2 - 2)
    octagon_z("lan_tip", L, "cyan", 0, Y, MZ - 42, MZ - 37, 7)

    bone("opt_triprong", "seg2", [0, Y, MZ])
    T = B["opt_triprong"]
    octagon_z("tri_collar", T, "dark", 0, Y, MZ - 10, MZ, 46)
    octagon_z("tri_band", T, "amber", 0, Y, MZ - 6, MZ - 5, 46.6)
    for k in range(3):
        a = 120.0 * k
        rbox(T, "paint", [-3, Y + 15, MZ - 46], [6, 6, 38], [0, Y, MZ], "z", a, name="prong")
        rbox(T, "dark", [-3.5, Y + 20.5, MZ - 40], [7, 2, 26], [0, Y, MZ], "z", a, name="prong_spine")
        rbox(T, "amber", [-1, Y + 14.2, MZ - 44], [2, 0.8, 34], [0, Y, MZ], "z", a, name="prong_glow")
        rbox(T, "amber", [-3.2, Y + 14.8, MZ - 50], [6.4, 6.4, 4], [0, Y, MZ], "z", a, name="prong_tip")

    bone("opt_shroud", "seg2", [0, Y, MZ])
    S = B["opt_shroud"]
    octagon_z("shr_a", S, "dark", 0, Y, MZ - 8, MZ, 44)
    octagon_z("shr_b", S, "steel", 0, Y, MZ - 24, MZ - 8, 54)
    octagon_z("shr_c", S, "paint", 0, Y, MZ - 34, MZ - 24, 50)
    octagon_z("shr_d", S, "dark", 0, Y, MZ - 40, MZ - 34, 44)
    octagon_z("shr_g1", S, "orange", 0, Y, MZ - 17, MZ - 15, 54.8)
    octagon_z("shr_g2", S, "orange", 0, Y, MZ - 30, MZ - 29, 50.6)
    box(S, "vent", [-27.4, Y - 6, MZ - 22], [0.6, 12, 12], mirror=("x",))
    box(S, "vent", [-6, Y + 26.8, MZ - 22], [12, 0.6, 12])
    octagon_z("shr_bore", S, "orange", 0, Y, MZ - 40.4, MZ - 40, 26)

    bone("opt_scatter", "seg2", [0, Y, MZ])
    C = B["opt_scatter"]
    octagon_z("sca_block", C, "steel", 0, Y, MZ - 16, MZ, 48)
    octagon_z("sca_haz", C, "hazard", 0, Y, MZ - 4, MZ - 2, 48.6)
    octagon_z("sca_c", C, "dark", 0, Y, MZ - 34, MZ - 16, 14)
    octagon_z("sca_cg", C, "orange", 0, Y, MZ - 34.4, MZ - 34, 7)
    for i, (dx, dy) in enumerate(((-12, -12), (12, -12), (-12, 12), (12, 12))):
        octagon_z(f"sca_p{i}", C, "dark", dx, Y + dy, MZ - 30, MZ - 16, 10)
        octagon_z(f"sca_pg{i}", C, "orange", dx, Y + dy, MZ - 30.4, MZ - 30, 5)

    bone("opt_gravity", "seg2", [0, Y, MZ])
    G = B["opt_gravity"]
    octagon_z("grv_collar", G, "dark", 0, Y, MZ - 8, MZ, 40)
    for dx, dy in ((-17, 0), (17, 0), (0, -17), (0, 17)):
        box(G, "chrome", [dx - 1, Y + dy - 1, MZ - 50], [2, 2, 42])
    for z, r in ((MZ - 16, 24), (MZ - 30, 20), (MZ - 44, 16)):
        ring_z(G, "violet", 0, Y, z - 1.5, z + 1.5, r, 2.5)
        ring_z(G, "dark", 0, Y, z - 2.5, z - 1.5, r - 0.5, 3.5)

    bone("opt_ghost", "seg2", [0, Y, MZ])
    H = B["opt_ghost"]
    octagon_z("gh_can", H, "dark", 0, Y, MZ - 68, MZ, 32)
    for i in range(6):
        z = MZ - 10 - i * 10
        octagon_z(f"gh_rib{i}", H, "steel", 0, Y, z - 2, z, 34)
    octagon_z("gh_cap", H, "steel", 0, Y, MZ - 72, MZ - 68, 30)
    octagon_z("gh_bore", H, "vent", 0, Y, MZ - 72.4, MZ - 72, 10)

    # ================= Ammunition racks (children of barrel, on the breech's right side) =================
    BZ = 28.0
    bone("opt_tungsten", "barrel", [28, Y, BZ])
    A = B["opt_tungsten"]
    box(A, "dark", [22, Y - 10, 12], [2, 22, 34])
    box(A, "steel", [22, Y - 11, 12], [10, 1, 34])
    for k in range(3):
        y = Y - 7 + k * 6
        box(A, "chrome", [25, y, 12], [3, 3, 30])
        box(A, "cyan", [25.5, y + 0.5, 8], [2, 2, 4])
        box(A, "dark", [24.5, y - 0.5, 38], [4, 4, 4])

    bone("opt_thermite", "barrel", [28, Y, BZ])
    A = B["opt_thermite"]
    box(A, "dark", [22, Y - 10, 12], [12, 2, 34])
    for i, z in enumerate((18, 29, 40)):
        octagon(f"th_can{i}", A, "steel", Y - 8, 10, 18, center_xz=(29, z))
        octagon(f"th_band{i}", A, "orange", Y, 10.6, 2, center_xz=(29, z))
        octagon(f"th_cap{i}", A, "dark", Y + 10, 8, 2, center_xz=(29, z))

    bone("opt_cryo", "barrel", [28, Y, BZ])
    A = B["opt_cryo"]
    box(A, "dark", [22, Y - 12, 14], [3, 24, 30])
    for i, cy in enumerate((Y + 5, Y - 6)):
        octagon_z(f"cr_tank{i}", A, "white", 31, cy, 12, 46, 10)
        for j, z in enumerate((18, 26, 34, 42)):
            octagon_z(f"cr_coil{i}{j}", A, "cyan", 31, cy, z - 1, z + 1, 11)
        octagon_z(f"cr_capa{i}", A, "chrome", 31, cy, 10, 12, 8)

    bone("opt_ion", "barrel", [28, Y, BZ])
    A = B["opt_ion"]
    for i, z in enumerate((13, 21, 29, 37)):
        box(A, "dark", [24, Y - 8, z], [10, 20, 6])
        box(A, "violet", [34, Y - 6, z + 1.5], [0.4, 16, 3])
        box(A, "chrome", [26, Y + 12, z + 2], [2, 2, 2])
        box(A, "chrome", [30, Y + 12, z + 2], [2, 2, 2])

    bone("opt_cluster", "barrel", [28, Y, BZ])
    A = B["opt_cluster"]
    octagon_z("cl_drum", A, "steel", 33, Y + 2, 12, 44, 22)
    octagon_z("cl_h1", A, "hazard", 33, Y + 2, 12, 14, 22.6)
    octagon_z("cl_h2", A, "hazard", 33, Y + 2, 42, 44, 22.6)
    box(A, "amber", [43.6, Y - 6, 16], [0.8, 2, 2.4], array=(4, [0, 0, 6]))
    box(A, "amber", [43.6, Y - 1, 16], [0.8, 2, 2.4], array=(4, [0, 0, 6]))
    box(A, "amber", [43.6, Y + 4, 16], [0.8, 2, 2.4], array=(4, [0, 0, 6]))
    box(A, "dark", [22, Y - 3, 20], [2, 10, 16])

    bone("opt_singularity", "barrel", [28, Y, BZ])
    A = B["opt_singularity"]
    box(A, "steel", [23, Y - 12, 18], [16, 3, 22])
    for i, (y, w) in enumerate(((Y - 9, 10), (Y - 6, 16), (Y - 3, 18), (Y, 18), (Y + 3, 16), (Y + 6, 10))):
        octagon(f"sg_s{i}", A, "dark", y, w, 3, center_xz=(31, 29))
    octagon("sg_slit", A, "violet", Y - 0.5, 18.6, 1, center_xz=(31, 29))
    ring_y(A, "chrome", 31, 29, Y - 4, Y - 2, 9.5, 1.5)

    bone("opt_sunburst", "barrel", [28, Y, BZ])
    A = B["opt_sunburst"]
    box(A, "steel", [23, Y - 10, 14], [15, 22, 30])
    box(A, "dark", [22.6, Y - 10.4, 13.6], [2, 23, 2], array=(2, [0, 0, 28.8]))
    box(A, "hazard", [38, Y - 3, 16], [0.4, 4, 26])
    box(A, "lime", [38, Y + 4, 22], [0.6, 5, 14])
    box(A, "dark", [30, Y + 12, 20], [4, 2, 18])

    # ================= Support modules =================
    bone("opt_capacitor", "hull", [0, TURRET_Y, 0])
    M = B["opt_capacitor"]
    for sgn in (-1, 1):
        box(M, "dark", [sgn * 34 - 5, 74, -21], [10, 2, 30])
        for i, z in enumerate((-16, -6, 4)):
            octagon(f"cap{sgn}{i}", M, "steel", 76, 7, 16, center_xz=(sgn * 34, z))
            octagon(f"capg{sgn}{i}", M, "amber", 92, 5, 2, center_xz=(sgn * 34, z))
            octagon(f"capb{sgn}{i}", M, "dark", 82, 7.4, 1.5, center_xz=(sgn * 34, z))

    bone("opt_coolant", "barrel", [0, Y, 0])
    M = B["opt_coolant"]
    box(M, "chrome", [-23, Y + 8, -112], [2.5, 3, 120], mirror=("x",))
    box(M, "cyan", [-23.3, Y + 8.5, -100], [0.3, 2, 80], mirror=("x",))
    octagon_z("cool_tank", M, "white", 0, Y - 28, 14, 40, 10)
    for z in (18, 26, 34):
        octagon_z(f"cool_band{z}", M, "cyan", 0, Y - 28, z, z + 1.5, 10.6)
    box(M, "dark", [-2, Y - 24, 20], [4, 3, 14])

    bone("opt_overclock", "barrel", [0, Y, 0])
    M = B["opt_overclock"]
    for z in (-20, -60, -100):
        ring_z(M, "dark", 0, Y, z - 2, z + 2, 23, 3)
        ring_z(M, "glow", 0, Y, z - 0.8, z + 0.8, 26, 0.8)
    box(M, "steel", [-1, Y + 25, -102], [2, 2, 84])

    bone("opt_targeting", "hull", [0, TURRET_Y, 0])
    M = B["opt_targeting"]
    tx, tz = -12, -12
    octagon("tg_base", M, "dark", 107, 16, 2, center_xz=(tx, tz))
    octagon("tg_d1", M, "white", 109, 14, 3, center_xz=(tx, tz))
    octagon("tg_d2", M, "white", 112, 11, 2, center_xz=(tx, tz))
    octagon("tg_d3", M, "white", 114, 7, 1.5, center_xz=(tx, tz))
    octagon("tg_ring", M, "glow", 110, 14.6, 0.8, center_xz=(tx, tz))
    bone("opt_targeting__laser", "barrel", [-26, Y - 11, -50])
    M = B["opt_targeting__laser"]
    box(M, "dark", [-29, Y - 14, -70], [5, 6, 40])
    box(M, "steel", [-24, Y - 12, -60], [4, 2, 20])
    box(M, "glow", [-28.5, Y - 13.5, -70.6], [4, 5, 0.6])
    box(M, "steel", [-29.4, Y - 14.4, -72], [5.8, 6.8, 2])

    for tag, q in (("a", "pn"), ("b", "np")):
        sx, sz = {"pn": (1, -1), "np": (-1, 1)}[q]
        cx, cz = sx * (BULWARK_D + 14), sz * (BULWARK_D + 14)
        bone(f"opt_shield__{tag}", f"shell_{q}", [cx, SHELL_TOP + 4, cz])
        M = B[f"opt_shield__{tag}"]
        octagon(f"sh_base{tag}", M, "dark", SHELL_TOP + 4, 10, 4, center_xz=(cx, cz))
        octagon(f"sh_mast{tag}", M, "steel", SHELL_TOP + 8, 4, 18, center_xz=(cx, cz))
        octagon(f"sh_ring{tag}", M, "cyan", SHELL_TOP + 16, 7, 1, center_xz=(cx, cz))
        octagon(f"sh_crys{tag}", M, "cyan", SHELL_TOP + 26, 6, 6, center_xz=(cx, cz))
        octagon(f"sh_cap{tag}", M, "chrome", SHELL_TOP + 32, 3, 2, center_xz=(cx, cz))
        box(M, "steel", [cx - 5, SHELL_TOP + 22, cz - 0.5], [10, 1, 1])
        box(M, "steel", [cx - 0.5, SHELL_TOP + 22, cz - 5], [1, 1, 10])

    for tag, q in (("a", "pp"), ("b", "nn")):
        sx, sz = QUADS[q]
        cx, cz = sx * (BULWARK_D + 14), sz * (BULWARK_D + 14)
        bone(f"opt_sentry__{tag}", f"shell_{q}", [cx, SHELL_TOP + 4, cz])
        M = B[f"opt_sentry__{tag}"]
        octagon(f"se_base{tag}", M, "dark", SHELL_TOP + 4, 11, 4, center_xz=(cx, cz))
        octagon(f"se_ring{tag}", M, "orange", SHELL_TOP + 7, 11.6, 0.8, center_xz=(cx, cz))
        bone(f"opt_sentry__{tag}_head", f"opt_sentry__{tag}", [cx, SHELL_TOP + 10, cz])
        Hd = B[f"opt_sentry__{tag}_head"]
        box(Hd, "paint", [cx - 4.5, SHELL_TOP + 8, cz - 4.5], [9, 8, 9])
        box(Hd, "dark", [cx - 4.8, SHELL_TOP + 13, cz - 3], [9.6, 2, 6])
        box(Hd, "steel", [cx - 3.5, SHELL_TOP + 11, cz - 15], [2, 2, 11])
        box(Hd, "steel", [cx + 1.5, SHELL_TOP + 11, cz - 15], [2, 2, 11])
        box(Hd, "orange", [cx - 3.5, SHELL_TOP + 11, cz - 15.4], [2, 2, 0.4])
        box(Hd, "orange", [cx + 1.5, SHELL_TOP + 11, cz - 15.4], [2, 2, 0.4])
        box(Hd, "glow", [cx - 1, SHELL_TOP + 14, cz - 4.7], [2, 1, 0.3])

    bone("opt_thruster", "core", [0, 0, 0])
    M = B["opt_thruster"]
    for sx, sz in QUADS.values():
        diag_box(M, "dark", sx, sz, (0, 0), 27, 36, 10, 10, 26)
        diag_box(M, "steel", sx, sz, (0, 0), 30, 37, 8, 6, 10)
        diag_box(M, "cyan", sx, sz, (0, 0), 31, 36, 5, 5.6, 6)
        diag_box(M, "steel", sx, sz, (0, 0), 35.5, 36.3, 8, 12, 24)

    for q, (sx, sz) in QUADS.items():
        D = (sx * BULWARK_D, 0, sz * BULWARK_D)
        bone(f"opt_seismic__{q}", f"clamp_{q}", [D[0] + sx * 26, 4, D[2] + sz * 26])
        M = B[f"opt_seismic__{q}"]
        base = (sx * 26, sz * 26)
        diag_box(M, "steel", sx, sz, base, 31, 37, 6, 14, 34, off=D)
        diag_box(M, "dark", sx, sz, base, 30, 38, 8, 34, 38, off=D)
        diag_box(M, "hazard", sx, sz, base, 30.8, 37.2, 6.6, 24, 26, off=D)
        diag_box(M, "brass", sx, sz, base, 33, 35, 3, -9, 0, off=D)

    bone("opt_autoloader", "barrel", [0, Y + 31, 28])
    M = B["opt_autoloader"]
    octagon_z("al_drum", M, "steel", 0, Y + 31, 14, 42, 20)
    octagon_z("al_p1", M, "dark", 0, Y + 31, 12, 14, 22)
    octagon_z("al_p2", M, "dark", 0, Y + 31, 42, 44, 22)
    octagon_z("al_hub", M, "brass", 0, Y + 31, 44, 45, 8)
    box(M, "dark", [-4, Y + 19, 20], [8, 3, 16])
    box(M, "brass", [-10.6, Y + 26, 16], [0.6, 2, 3], array=(4, [0, 0, 6.5]))

    bone("opt_afterburn", "seg1", [0, Y, -180])
    M = B["opt_afterburn"]
    for sgn in (-1, 1):
        octagon_z(f"ab_tank{sgn}", M, "steel", sgn * 14, Y - 18, -197, -163, 9)
        octagon_z(f"ab_win{sgn}", M, "orange", sgn * 14, Y - 18, -186, -174, 9.6)
        box(M, "dark", [sgn * 14 - 1, Y - 14, -190], [2, 4, 20])



def build_remote(offset):
    """
    The ARES-9 uplink tablet (item id railgun_remote), in vanilla item space (0..16 px): a
    carbon-black military slate with a raised armour frame, cyan-rimmed displays on BOTH faces,
    ribbed side handles, a sensor bar with twin antennas, a control strip and rear heat sinks.
    """
    bones = {"remote": make_bone("remote", None, [8, 0, 8], None, offset=offset)}
    R = bones["remote"]
    make_part("tab_body", R, "dark", [([0.8, 2.4, 7.25], [14.4, 11.2, 1.5])])
    # raised armour frame, both faces
    for z in (7.0, 8.75):
        make_part(f"tab_ftop{z}", R, "paint", [([1.0, 12.3, z], [14.0, 1.1, 0.25])])
        make_part(f"tab_fbot{z}", R, "paint", [([1.0, 2.5, z], [14.0, 1.5, 0.25])])
        make_part(f"tab_fside{z}", R, "paint", [([1.0, 4.0, z], [1.0, 8.3, 0.25])], mirror=("x",))
        make_part(f"tab_corner{z}", R, "hazard", [([1.0, 12.3, z - 0.02], [1.6, 1.1, 0.29])], mirror=("x",))
    make_part("tab_screen_f", R, "screen", [([2.2, 4.2, 6.95], [11.6, 7.9, 0.05])])
    make_part("tab_screen_b", R, "screen", [([2.2, 4.2, 9.0], [11.6, 7.9, 0.05])])
    make_part("tab_rim_f", R, "cyan", [([2.2, 4.0, 6.97], [11.6, 0.2, 0.04])])
    make_part("tab_rim_b", R, "cyan", [([2.2, 4.0, 9.0], [11.6, 0.2, 0.04])])
    # handles with ribs
    make_part("tab_grips", R, "hull", [([-0.4, 3.4, 6.8], [1.4, 9.2, 2.4])], mirror=("x",))
    make_part("tab_grip_ribs", R, "dark", [([-0.5, 4.2, 7.1], [0.1, 0.5, 1.8])], mirror=("x",), array=(5, [0, 1.7, 0]))
    # sensor bar + antennas
    make_part("tab_sensor", R, "steel", [([4.0, 13.6, 7.4], [8.0, 0.8, 1.2])])
    make_part("tab_lens", R, "glow", [([7.4, 13.75, 7.32], [1.2, 0.5, 0.08])])
    make_part("tab_ant1", R, "dark", [([12.6, 14.4, 7.7], [0.6, 1.8, 0.6])])
    make_part("tab_ant1_tip", R, "amber", [([12.6, 16.2, 7.7], [0.6, 0.4, 0.6])])
    make_part("tab_ant2", R, "dark", [([2.8, 14.4, 7.75], [0.5, 1.1, 0.5])])
    # control strip
    for x, k in ((3.0, "glow"), (4.8, "amber"), (9.8, "cyan"), (11.6, "cyan")):
        make_part(f"tab_btn{x}", R, k, [([x, 2.85, 6.9], [1.4, 0.7, 0.1])])
    # rear heat sinks
    make_part("tab_fins", R, "steel", [([3.0, 5.0, 9.05], [10.0, 0.6, 0.4])], array=(4, [0, 1.8, 0]))
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


def pack_faces(boxes, texels_per_px, start_size, pad=0):
    """
    Skyline bottom-left packer for every face of every box. For each face (tallest first) it
    finds the x position where the face sits lowest on the current skyline. Adds
    box['rects'][face] = (u, v, w, h). With pad > 0 every face gets a gutter of that many texels
    (filled later by edge extrusion) so bilinear filtering never bleeds neighbouring faces.
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
            W, H = tw + 2 * pad, th + 2 * pad
            if W > tex:
                ok = False
                break
            tops = np.lib.stride_tricks.sliding_window_view(sky, W).max(axis=1)
            x = int(np.argmin(tops))
            y = int(tops[x])
            if y + H > tex:
                ok = False
                break
            sky[x:x + W] = y + H
            placed[(bi, face)] = (x + pad, y + pad, tw, th)
        if ok:
            break
        tex *= 2
    for (bi, face), rect in placed.items():
        boxes[bi].setdefault("rects", {})[face] = rect
    return tex


def extrude_gutters(td, boxes, pad):
    """Copies every face's border texels outward into its gutter (top-down atlas array)."""
    if pad <= 0:
        return
    for b in boxes:
        for (u, v, w, h) in b["rects"].values():
            for k in range(1, pad + 1):
                if v - k >= 0:
                    td[v - k, u:u + w] = td[v, u:u + w]
                if v + h - 1 + k < td.shape[0]:
                    td[v + h - 1 + k, u:u + w] = td[v + h - 1, u:u + w]
            x0, x1 = max(0, u - pad), min(td.shape[1], u + w + pad)
            y0, y1 = max(0, v - pad), min(td.shape[0], v + h + pad)
            for k in range(1, pad + 1):
                if u - k >= 0:
                    td[y0:y1, u - k] = td[y0:y1, u]
                if u + w - 1 + k < td.shape[1]:
                    td[y0:y1, u + w - 1 + k] = td[y0:y1, u + w - 1]


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


# Clean, modern base colours (sRGB) per kind. The paint pass below builds everything else.
CLEAN = {
    "paint": "#d9dde2", "steel": "#4b525b", "hull": "#2f353d", "dark": "#17191d", "vent": "#101215",
    "glass": "#0f1b27", "concrete": "#9a9ea3", "chrome": "#b8c1cb", "brass": "#b28b3e", "white": "#eef1f4",
    "hazard": "#e8a317",
    "glow": "#ff3a24", "amber": "#ffad2a", "cyan": "#35dcff", "violet": "#b46cff", "lime": "#b4ff4a", "orange": "#ff7a1c",
}
def _smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def paint_faces(td, boxes, seed=7, tpp=1):
    """
    Modern, minimal, clean surface painting (no pixel noise, no grime): per face a flat material
    colour with soft top-lit gradient, smooth ambient occlusion toward the box edges, a crisp
    bevel highlight, thin recessed panel grooves on large faces, and sparse decals (accent
    stripes, stencil dashes, chevrons). Glow kinds get a hot core line that falls off smoothly.
    td is the atlas [row (top->bottom), col, rgba] in sRGB 0..1; rects are in texels.
    """
    r = np.random.default_rng(seed)
    accent = _hex01("#ff6a1a")
    for bi, b in enumerate(boxes):
        kind = b["kind"]
        size = [b["max"][k] - b["min"][k] for k in range(3)]
        for face, (u, v, w, h) in b["rects"].items():
            R = td[v:v + h, u:u + w, :3]
            side = face in SIDE_FACES
            yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)

            if kind == "screen":
                paint_screen(R, w, h, seed=bi * 7 + len(face))
                continue

            base = _hex01(CLEAN.get(kind, "#808080"))
            if kind in GLOW_KINDS:
                cy, cx = (h - 1) / 2, (w - 1) / 2
                # light strip: bright along its long axis, soft falloff across it
                d = np.abs(yy - cy) / max(cy, 0.5) if w >= h else np.abs(xx - cx) / max(cx, 0.5)
                core = np.clip(1 - d, 0, 1) ** 1.6
                col = base[None, None, :] * (0.78 + 0.22 * core[..., None]) + core[..., None] ** 2 * 0.45
                R[:] = np.clip(col, 0, 1)
                continue

            col = np.broadcast_to(base, (h, w, 3)).astype(np.float32).copy()
            if kind == "hazard":
                period = 6.0 * tpp
                ph = ((xx + yy + u + v) % period) / period
                stripe = _smoothstep(0.46, 0.5, ph) - _smoothstep(0.96, 1.0, ph)
                col = col * (1 - stripe[..., None]) + _hex01("#1b1b1f")[None, None, :] * stripe[..., None]

            if kind == "chrome":
                g = 0.82 + 0.3 * np.exp(-((yy / max(h - 1, 1) - 0.3) / 0.18) ** 2)
                col *= g[..., None]
            elif kind == "vent":
                slat = (yy % (3 * tpp)) < tpp
                col[slat] *= 2.4

            # very low-frequency tonal variation (no grain)
            col *= (0.985 + 0.03 * r.random())

            # top-lit gradient on side faces, slightly lighter top faces
            if side:
                g = 1.05 - 0.13 * (yy / max(h - 1, 1)) ** 1.4
                col *= g[..., None]
            elif face == "up":
                col *= 1.05
            else:
                col *= 0.88

            # panel grooves on large faces
            fw, fh = w / tpp, h / tpp
            if kind in ("paint", "steel", "hull", "dark", "white", "concrete") and (fw >= 18 or fh >= 18):
                P = 24.0 * tpp
                oxp, oyp = (bi * 7.3 + u) % P, (bi * 3.1 + v) % P
                gx = ((xx + oxp) % P) < 1.0
                gy = ((yy + oyp) % P) < 1.0
                hx = (((xx + oxp) % P) >= 1.0) & (((xx + oxp) % P) < 2.0)
                hy = (((yy + oyp) % P) >= 1.0) & (((yy + oyp) % P) < 2.0)
                if fw >= 18:
                    col[gx] *= 0.62
                    col[hx & ~gy] = np.minimum(col[hx & ~gy] * 1.08, 1)
                if fh >= 18:
                    col[gy] *= 0.62
                    col[hy & ~gx] = np.minimum(col[hy & ~gx] * 1.08, 1)

            # decals on big light panels
            if kind in ("paint", "white") and side and fw >= 20 and fh >= 14:
                roll = r.random()
                if roll < 0.35:
                    y0 = int(3 * tpp)
                    col[y0:y0 + max(1, int(1.2 * tpp)), int(2 * tpp):w - int(2 * tpp)] = accent
                elif roll < 0.6:
                    y0, x0 = h - int(5 * tpp), int(3 * tpp)
                    for k in range(int(r.integers(3, 7))):
                        ln = int(r.integers(2, 6) * tpp * 0.6) + 1
                        col[y0:y0 + max(1, tpp // 2 + 1), x0:x0 + ln] = _hex01("#40464e")
                        x0 += ln + max(1, tpp)
                        if x0 > w - 4 * tpp:
                            break
                elif roll < 0.72:
                    cxc, cyc = w - int(6 * tpp), int(5 * tpp)
                    tri = (np.abs(xx - cxc) < (yy - cyc + 3 * tpp) * 0.8) & (yy >= cyc - 3 * tpp) & (yy <= cyc)
                    col[tri] = accent

            # smooth edge occlusion + crisp bevel
            dx = np.minimum(xx, w - 1 - xx)
            dy = np.minimum(yy, h - 1 - yy)
            dmin = np.minimum(dx, dy)
            ao = 0.8 + 0.2 * _smoothstep(0, 3.0 * tpp, dmin)
            col *= ao[..., None]
            if w >= 4 and h >= 4:
                col[0, :] *= 0.55
                col[-1, :] *= 0.5
                col[:, 0] *= 0.6
                col[:, -1] *= 0.55
                hl = min(2, max(1, tpp // 2))
                col[1:1 + hl, 1:-1] = np.minimum(col[1:1 + hl, 1:-1] * 1.22 + 0.03, 1)
                col[1:-1, 1:1 + hl] = np.minimum(col[1:-1, 1:1 + hl] * 1.1, 1)
            R[:] = np.clip(col, 0, 1)


def assign_materials(objs, mats):
    for o in objs:
        o.data.materials.clear()
        for m in mats:
            o.data.materials.append(m)
        o.data.polygons.foreach_set("material_index", list(o["mat_indices"]))
        o.data.update()


def bake(objs, boxes, kinds, tex, label, tpp=1, pad=0):
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
    paint_faces(td, boxes, tpp=tpp)
    extrude_gutters(td, boxes, pad)
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


def export_geo(bones, boxes, tex, path, identifier="geometry.railgun", bounds=(44, 14, 6)):
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
                "identifier": identifier,
                "texture_width": tex, "texture_height": tex,
                "visible_bounds_width": bounds[0], "visible_bounds_height": bounds[1],
                "visible_bounds_offset": [0, bounds[2], 0],
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


CLAMP_STOW = 50.0        # px along the diagonal: stowed fully inside the core


def _clamp_in(q, lift=6.0, extra=0.0):
    """Clamp offset (relative to its shell quarter) that keeps it stowed inside the core.
    `extra` counters the shell's own outward travel, so the clamp stays put inside the core
    while the quarters glide away, and only comes out when it is its turn."""
    sx, sz = QUADS[q]
    k = (CLAMP_STOW + extra) / math.sqrt(2)
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
T_CLAMP_OUT = (4.8, 5.95)
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
      4.8-6.5   ground clamps (stowed in the core) slide out under the struts, wind up and SLAM at 6.5 s
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
        glide = STRUT_SHIFT[3]
        t[f"clamp_{q}"] = {"position": {0.0: _clamp_in(q), T_UNLATCH: _clamp_in(q),
                                        T_UNLATCH + 0.3: (_clamp_in(q, extra=0.12 * glide), OUT),
                                        g0: _clamp_in(q, extra=0.12 * glide), g1: (_clamp_in(q, extra=glide), IO),
                                        c0: _clamp_in(q, extra=glide), c1: ([0, 10, 0], IO),
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


def render_option_icons(rg_bake, rg_bones, cam, target, ground, assets):
    """One transparent 96 px icon per loadout option: its attachment alone, three-quarter view."""
    scene = bpy.context.scene
    reset_pose(rg_bones)
    bpy.context.view_layer.update()
    scene.render.film_transparent = True
    scene.render.image_settings.color_mode = "RGBA"
    ground.hide_render = True
    by_bone = {o.name[len("bake_"):]: o for o in rg_bake}
    for oid in OPTION_IDS:
        names = OPTION_ICON_BONES.get(oid, [f"opt_{oid}"])
        objs = [by_bone[n] for n in names if n in by_bone]
        if not objs:
            continue
        set_visible(rg_bake, False)
        set_visible(objs, True)
        pts = [o.matrix_world @ Vector(c) for o in objs for c in o.bound_box]
        lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
        hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
        c = (lo + hi) / 2
        ext = max((hi - lo).length, 0.4)
        d = Vector((1.0, -1.25, 0.8)).normalized() * ext * 1.55
        render(os.path.join(assets, f"textures/gui/option/{oid}.png"), cam, target, tuple(c + d), tuple(c), 96, samples=24, lens=50)
    scene.render.film_transparent = False
    scene.render.image_settings.color_mode = "RGB"
    ground.hide_render = False
    set_visible(rg_bake, True)


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
    rg_tex = pack_faces(rg_boxes, texels_per_px=RG_TPP, start_size=1024, pad=RG_PAD)
    rg_bake = build_bake_objects(rg_boxes, rg_bones, rg_tex, kinds)
    rg_atlas, rg_glow = bake(rg_bake, rg_boxes, kinds, rg_tex, "railgun", tpp=RG_TPP, pad=RG_PAD)
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
    rm_tex = pack_faces(rm_boxes, texels_per_px=8, start_size=128, pad=2)
    rm_bake = build_bake_objects(rm_boxes, rm_bones, rm_tex, kinds, offset=offset)
    rm_atlas, rm_glow = bake(rm_bake, rm_boxes, kinds, rm_tex, "remote", tpp=8, pad=2)
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
        render_option_icons(rg_bake, rg_bones, cam, target, ground, assets)
        # Preview renders show one sample loadout (in game only installed parts are shown).
        sample = ("opt_shroud", "opt_sunburst", "opt_capacitor", "opt_shield__a", "opt_shield__b",
                  "opt_sentry__a", "opt_sentry__a_head", "opt_sentry__b", "opt_sentry__b_head", "opt_coolant")
        for o in rg_bake:
            bn = o.name[len("bake_"):]
            if bn.startswith("opt_"):
                o.hide_render = bn not in sample
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
        # Orbital part drop: the lancer hovering ahead of the muzzle, docking arms out.
        pose(rg_bones, "animation.railgun.deploy", DEPLOY_LEN)
        lan = rg_bones["opt_lancer"]
        lan.location = Vector(lan["rest_location"]) + mc_to_b([0, 6, -40])
        for o in rg_bake:
            bn = o.name[len("bake_"):]
            if bn.startswith("opt_"):
                o.hide_render = bn not in ("opt_lancer", "opt_lancer__arms", "opt_capacitor", "opt_sunburst")
        render(os.path.join(out, "railgun_part_docking.png"), cam, target, (8.0, 27.0, 10.5), (0.0, 21.5, 7.4), 900, lens=40)
        reset_pose(rg_bones)

        set_visible(rg_bake, False)
        set_visible(rm_bake, True)
        ground.location = (120, 0, -0.01)
        render(os.path.join(out, "tablet.png"), cam, target, (120.6, -1.6, 1.4), (120.5, -0.45, 0.5), 700, lens=50)
        set_visible(rm_bake, False)
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
