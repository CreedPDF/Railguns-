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
}
KINDS = list(PALETTE)
GLOW_KINDS = {"glow", "amber"}


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


def make_rot_part(name, bone, kind, center_xz, y0, size_x, size_z, height, angle):
    """
    A box rotated around the vertical axis (Bedrock cube "rotation"), centred on center_xz.
    Used in pairs at +45/-45 (= 135) degrees, a set that is identical whichever sign convention
    the renderer uses for Y rotation.
    """
    pivot = Vector(bone["pivot_mc"])
    hx, hz = size_x / 2.0, size_z / 2.0
    bm = bmesh.new()
    lo, hi = Vector((-hx / 16, -hz / 16, 0)), Vector((hx / 16, hz / 16, height / 16))
    v = [bm.verts.new((x, y, z)) for x in (lo.x, hi.x) for y in (lo.y, hi.y) for z in (lo.z, hi.z)]
    for quad in ((0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)):
        bm.faces.new([v[i] for i in quad])
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    mesh.materials.append(viewport_material(kind))
    obj = link(bpy.data.objects.new(name, mesh))
    obj.parent = bone
    obj.location = mc_to_b([center_xz[0], y0, center_xz[1]]) - mc_to_b(list(pivot))
    obj.rotation_euler = (0, 0, math.radians(angle))
    obj["kind"] = kind
    obj["export"] = True
    obj["rot_y"] = angle
    obj["cube_mc"] = [center_xz[0] - hx, y0, center_xz[1] - hz, size_x, height, size_z]
    return obj


TAN22 = math.tan(math.radians(22.5))


def octagon(name, bone, kind, y0, width, height, center_xz=(0, 0)):
    """
    Regular octagon slab of the given flat-to-flat width, built as the union of four rectangles
    (width x width*tan(22.5)) at 0, 90, 45 and 135 degrees. Their top faces are staggered by
    0.15 px so overlapping tops never z-fight.
    """
    cx, cz = center_xz
    a = width / 2.0
    b = round(a * TAN22, 3)
    make_part(f"{name}_a", bone, kind, [([cx - a, y0, cz - b], [width, height, 2 * b])])
    make_part(f"{name}_b", bone, kind, [([cx - b, y0, cz - a], [2 * b, height + 0.15, width])])
    make_rot_part(f"{name}_c", bone, kind, center_xz, y0, width, 2 * b, height + 0.3, 45.0)
    make_rot_part(f"{name}_d", bone, kind, center_xz, y0, width, 2 * b, height + 0.45, -45.0)


def rot4(boxes, direction):
    """Rotates boxes authored for the north side (-Z) to n/s/e/w in 90-degree steps around Y."""
    out = []
    for kind, (o, sz) in boxes:
        x0, y0, z0 = o
        x1, z1 = x0 + sz[0], z0 + sz[2]
        if direction == "n":
            out.append((kind, (o, sz)))
        elif direction == "s":
            out.append((kind, ([x0, y0, -z1], sz)))
        elif direction == "e":
            out.append((kind, ([-z1, y0, x0], [sz[2], sz[1], sz[0]])))
        else:
            out.append((kind, ([z0, y0, -x1], [sz[2], sz[1], sz[0]])))
    return out


def parts4(prefix, bone_for, boxes):
    """Creates one part per box for each of the four sides."""
    for d in ("n", "s", "e", "w"):
        bone = bone_for(d)
        for i, (kind, (o, sz)) in enumerate(rot4(boxes, d)):
            make_part(f"{prefix}_{d}_{i}", bone, kind, [(o, sz)])


# Ground clamps (north side, two per side at x = +/-32 so the south stairs fit between them).
# The leg bone pivots at the fortress edge: the deploy animation extends them from 12% length,
# lifts them, then slams them down into the ground.
def _clamp(cx):
    return [
        ("steel", [[cx - 5, 4, -112], [10, 7, 26]]),
        ("paint", [[cx - 6, 11, -110], [12, 2, 20]]),
        ("dark", [[cx - 2, 13, -106], [4, 4, 14]]),
        ("steel", [[cx - 3, 13, -92], [6, 6, 5]]),
        ("steel", [[cx - 9, 0, -124], [18, 4, 14]]),
        ("dark", [[cx - 7, 4, -122], [14, 3, 10]]),
        ("hazard", [[cx - 9, 4, -125], [18, 1.5, 1.5]]),
        ("dark", [[cx - 8, -3, -123], [3, 3, 3]]),
        ("dark", [[cx + 5, -3, -123], [3, 3, 3]]),
        ("dark", [[cx - 8, -3, -114], [3, 3, 3]]),
        ("dark", [[cx + 5, -3, -114], [3, 3, 3]]),
        ("amber", [[cx - 1.5, 11, -113], [3, 2, 2]]),
        ("glow", [[cx - 5.6, 6, -110], [0.6, 1, 16]]),
        ("glow", [[cx + 5, 6, -110], [0.6, 1, 16]]),
    ]


OUTRIGGER = _clamp(-32) + _clamp(32)

# Deploy stairs on the south face, from the ground up to the middle tier (y 36, apothem 75).
STAIRS = [("concrete", [[-12, 0, 75 + 6 * k], [24, 35 - 5 * k, 6]]) for k in range(7)] + [
    ("hazard", [[-12, 35 - 5 * k - 1, 80.5 + 6 * k], [24, 1, 0.5]]) for k in range(7)] + [
    ("steel", [[-13, 0, 76], [1, 44, 1.5]]),
    ("steel", [[12, 0, 76], [1, 44, 1.5]]),
    ("steel", [[-13, 0, 118], [1, 12, 1.5]]),
    ("steel", [[12, 0, 118], [1, 12, 1.5]]),
]

# Access console at the foot of the stairs (the deployed railgun's control panel).
CONSOLE = [
    ("steel", [[13, 0, 92], [7, 14, 7]]),
    ("dark", [[12, 14, 90], [9, 4, 10]]),
    ("glow", [[12.5, 18, 90.5], [8, 0.6, 9]]),
    ("amber", [[15.5, 18.6, 98], [2, 1, 1]]),
    ("hazard", [[12, 0, 99], [9, 2, 1]]),
]

# Blast door + frame on each face of the middle tier (apothem 75).
BLAST_DOOR = [
    ("dark", [[-16, 14, -76.5], [32, 19, 1.5]]),
    ("steel", [[-0.5, 14, -77], [1, 19, 0.5]]),
    ("steel", [[-20, 33, -77.5], [40, 3, 2.5]]),
    ("steel", [[-20, 14, -77.5], [4, 19, 2.5]]),
    ("steel", [[16, 14, -77.5], [4, 19, 2.5]]),
    ("hazard", [[-16, 14, -79], [32, 1.5, 3]]),
    ("amber", [[-3, 36, -76.5], [6, 1.5, 2]]),
    ("glow", [[-12, 28, -77.1], [8, 1, 0.6]]),
    ("glow", [[4, 28, -77.1], [8, 1, 0.6]]),
]

# Vent bank on each face of the upper tier (apothem 61) and pipes on the foundation ledge.
TIER3_VENTS = [
    ("vent", [[-22, 38, -62], [44, 7, 1]]),
    ("steel", [[-23, 37, -62.5], [46, 1, 1.5]]),
    ("steel", [[-23, 45, -62.5], [46, 1, 1.5]]),
]
LEDGE_PIPES = [
    ("steel", [[-34, 14, -86], [68, 4, 4]]),
    ("dark", [[-30, 14, -87], [3, 6, 6]]),
    ("dark", [[-1.5, 14, -87], [3, 6, 6]]),
    ("dark", [[27, 14, -87], [3, 6, 6]]),
]


def build_railgun():
    """
    Heavy orbital railgun emplacement (roughly 11 blocks across, barrel ~19 blocks long).

      base      three-tier octagonal concrete fortress, hazard deck, blast doors, vent banks,
                ledge pipes, four floodlight towers
      leg_*     hydraulic outriggers with foot pads (deploy outward)
      turret    turntable with glowing ring, armoured hull, side skirts, control cab, exhaust
                stacks, ladder, rails, capacitor tanks, massive yoke cheeks with trunnion hubs
      barrel    segmented white armour walls, glowing accelerator rail core, conductor rails,
                energy lines, roof, breech block with fins and couplings, muzzle brake
      vents     cooling fins on the barrel roof (rise when charging / firing)
      coils     glowing accelerator coil frames around the open front of the barrel

    Returns {bone_name: empty} in parent-before-child order.
    """
    bones = {}

    def bone(name, parent, pivot):
        p = bones.get(parent)
        bones[name] = make_bone(name, p, pivot, p["pivot_mc"] if p else None)

    bone("root", None, [0, 0, 0])
    bone("base", "root", [0, 0, 0])
    for d, pivot in (("n", [0, 6, -88]), ("s", [0, 6, 88]), ("e", [88, 6, 0]), ("w", [-88, 6, 0])):
        bone(f"leg_{d}", "base", pivot)
    bone("stairs", "base", [0, 0, 75])
    bone("pod", "root", [0, 0, 0])
    bone("pod_panel", "pod", [0, 40, 28])
    bone("turret", "root", [0, 56, 0])
    bone("hull", "turret", [0, 56, 0])
    bone("barrel", "turret", [0, 120, 0])
    bone("vents", "barrel", [0, 120, 0])
    bone("coils", "barrel", [0, 120, 0])
    B = bones
    base, bar = B["base"], B["barrel"]
    tur = B["hull"]  # turret geometry lives on "hull" so it can grow independently of the barrel

    # ---- Fortress ---------------------------------------------------------------------------
    octagon("found", base, "concrete", 0, 176, 14)
    octagon("tier2", base, "concrete", 14, 150, 22)
    octagon("tier2_band", base, "dark", 27, 152, 2)
    octagon("tier3", base, "concrete", 36, 122, 10)
    octagon("tier3_cap", base, "steel", 46, 125, 2)
    octagon("deck", base, "hazard", 48, 100, 4)
    parts4("door", lambda d: base, BLAST_DOOR)
    parts4("t3vent", lambda d: base, TIER3_VENTS)
    parts4("pipes", lambda d: base, LEDGE_PIPES)

    # Floodlight towers on the diagonal ledges.
    make_part("tower_foot", base, "dark", [([55, 14, 55], [7, 2, 7])], mirror=("x", "z"))
    make_part("tower_pole", base, "steel", [([57, 16, 57], [3, 46, 3])], mirror=("x", "z"))
    make_part("tower_brace", base, "steel", [([56, 30, 56], [5, 1.5, 5])], mirror=("x", "z"))
    make_part("tower_head", base, "dark", [([53, 62, 53], [9, 5, 9])], mirror=("x", "z"))
    make_part("tower_lamp", base, "amber", [([54, 61, 54], [7, 1, 7])], mirror=("x", "z"))
    make_part("tower_beacon", base, "glow", [([57.5, 67, 57.5], [2, 2, 2])], mirror=("x", "z"))

    # ---- Ground clamps, stairs, console --------------------------------------------------------
    parts4("clamp", lambda d: B[f"leg_{d}"], OUTRIGGER)
    for i, (kind, (o, sz)) in enumerate(STAIRS):
        make_part(f"stairs_{i}", B["stairs"], kind, [(o, sz)])
    for i, (kind, (o, sz)) in enumerate(CONSOLE):
        make_part(f"console_{i}", base, kind, [(o, sz)])

    # ---- Drop pod (compact pillar form; the rail pokes out of its top collar) -------------------
    pod = B["pod"]
    octagon("pod_skirt", pod, "dark", 0, 62, 6)
    octagon("pod_hazard", pod, "hazard", 6, 59, 3)
    octagon("pod_body", pod, "paint", 9, 56, 71)
    octagon("pod_band1", pod, "glow", 30, 57, 1.5)
    octagon("pod_band2", pod, "glow", 62, 57, 1.5)
    octagon("pod_collar", pod, "steel", 80, 50, 8)
    octagon("pod_lip", pod, "dark", 88, 42, 3)
    parts4("pod_fin", lambda d: pod, [
        ("steel", [[-2, 6, -40], [4, 52, 12]]),
        ("dark", [[-2.5, 6, -42], [5, 8, 4]]),
        ("glow", [[-0.5, 20, -40.6], [1, 30, 0.6]]),
    ])
    make_part("pod_screen", pod, "glow", [([-8, 34, 28], [16, 14, 0.4])])
    make_part("pod_screen_frame", pod, "dark", [([-10, 32, 27.9], [20, 18, 0.3])])
    make_part("pod_panel_cover", B["pod_panel"], "paint", [([-10, 31, 28.4], [20, 20, 2])])
    make_part("pod_panel_stripe", B["pod_panel"], "hazard", [([-10, 31, 30.4], [20, 2.5, 0.4])])
    make_part("pod_panel_light", B["pod_panel"], "amber", [([-2, 47, 30.4], [4, 2, 0.6])])
    make_part("pod_panel_handle", B["pod_panel"], "dark", [([-5, 38, 30.4], [10, 2, 1.2])])

    # ---- Turret -----------------------------------------------------------------------------
    octagon("tt_ring", tur, "dark", 52, 88, 6)
    octagon("tt_glow", tur, "glow", 54.5, 90, 1.5)
    octagon("tt_plate", tur, "steel", 58, 80, 4)
    make_part("hull_low", tur, "paint", [([-34, 62, -40], [68, 22, 80])])
    make_part("hull_mid", tur, "paint", [([-30, 84, -34], [60, 8, 68])])
    make_part("hull_top", tur, "paint", [([-26, 92, -28], [52, 3, 56])])
    make_part("hull_front_vent", tur, "vent", [([-24, 66, -40.6], [48, 12, 0.6])])
    make_part("hull_front_trim", tur, "hazard", [([-34, 62, -41], [68, 2.5, 1])])
    make_part("hull_rear_grille", tur, "vent", [([-22, 66, 40], [44, 14, 1])])
    make_part("skirts", tur, "paint", [([-38, 64, -36], [4, 18, 72])], mirror=("x",))
    make_part("skirt_trim", tur, "dark", [([-38.5, 62, -36], [4.5, 2, 72])], mirror=("x",))
    make_part("skirt_plates", tur, "steel", [([-38.6, 67, -32], [0.6, 11, 26])], mirror=("x",), array=(2, [0, 0, 36]))
    make_part("skirt_glow", tur, "glow", [([-38.7, 80, -30], [0.7, 1, 60])], mirror=("x",))
    make_part("exhaust", tur, "dark", [([-18, 84, 34], [8, 16, 6])], array=(3, [14, 0, 0]))
    make_part("exhaust_heat", tur, "glow", [([-17, 99.9, 35], [6, 0.4, 4])], array=(3, [14, 0, 0]))
    make_part("cab", tur, "paint", [([-30, 95, -32], [16, 11, 14])])
    make_part("cab_roof", tur, "dark", [([-31, 106, -33], [18, 2, 16])])
    make_part("cab_window_front", tur, "glass", [([-28, 99, -32.5], [12, 5, 0.5])])
    make_part("cab_window_side", tur, "glass", [([-30.5, 99, -30], [0.5, 5, 10])])
    make_part("cab_light", tur, "amber", [([-23, 108, -26], [2, 1.5, 2])])
    make_part("ladder_rails", tur, "steel", [([38, 62, 18], [1.2, 30, 1]), ([38, 62, 27], [1.2, 30, 1])])
    make_part("ladder_rungs", tur, "steel", [([38, 64, 19], [1.2, 1, 8])], array=(7, [0, 4, 0]))
    make_part("rail_bars", tur, "steel", [([-27, 100, -12], [1, 1, 40])], mirror=("x",))
    make_part("rail_posts", tur, "steel", [([-27, 95, -12], [1, 5, 1])], mirror=("x",), array=(3, [0, 0, 19.5]))
    # Capacitor tanks (vertical octagonal prisms) behind the yoke.
    for side in (-1, 1):
        cx = side * 20
        octagon(f"tank{side}", tur, "steel", 95, 12, 20, (cx, 22))
        octagon(f"tank_band{side}", tur, "glow", 101, 12.8, 1.5, (cx, 22))
        octagon(f"tank_band2{side}", tur, "glow", 108, 12.8, 1.5, (cx, 22))
        octagon(f"tank_cap{side}", tur, "dark", 115, 9, 2, (cx, 22))
    # Yoke
    make_part("cheeks", tur, "paint", [([-30, 95, -18], [10, 41, 36])], mirror=("x",))
    make_part("cheek_bevel", tur, "paint", [([-29, 136, -14], [8, 4, 28])], mirror=("x",))
    make_part("cheek_plates", tur, "steel", [([-30.6, 99, -14], [0.6, 10, 28])], mirror=("x",))
    make_part("cheek_hazard", tur, "hazard", [([-30, 128, -18.6], [10, 4, 0.6])], mirror=("x",))
    make_part("hubs", tur, "dark", [([-33, 112, -8], [3, 16, 16])], mirror=("x",))
    make_part("hub_caps", tur, "steel", [([-34, 115, -5], [1, 10, 10])], mirror=("x",))
    make_part("hub_glow", tur, "glow", [([-34.6, 118, -2], [0.6, 4, 4])], mirror=("x",))
    make_part("cables", tur, "dark", [([-12, 95, 26], [6, 19, 6])], mirror=("x",))

    # ---- Barrel (points to -Z / north) ---------------------------------------------------------
    make_part("wall_under", bar, "dark", [([-17, 109, -300], [9, 24, 322])], mirror=("x",))
    make_part("wall_armour", bar, "paint", [([-18, 108, -300], [10, 26, 34])], mirror=("x",), array=(9, [0, 0, 36]))
    make_part("floor", bar, "paint", [([-16, 104, -296], [32, 4, 318])])
    make_part("keel", bar, "dark", [([-6, 100, -280], [12, 4, 296])])
    make_part("slot_bed", bar, "dark", [([-8, 108, -300], [16, 6, 322])])
    make_part("rail_core", bar, "glow", [([-3, 117, -298], [6, 6, 318])])
    make_part("conductors", bar, "steel", [([-8, 114, -298], [3, 12, 318])], mirror=("x",))
    make_part("roof", bar, "paint", [([-18, 134, -150], [36, 4, 172])])
    make_part("conduit", bar, "steel", [([-18.8, 127, -292], [0.8, 3, 312])], mirror=("x",))
    make_part("energy_line", bar, "glow", [([-18.6, 122, -282], [0.6, 1, 270])], mirror=("x",))
    make_part("groove", bar, "dark", [([-18.4, 113, -296], [0.4, 1, 316])], mirror=("x",))
    make_part("sensor", bar, "dark", [([-6, 138, -24], [12, 6, 14])])
    make_part("sensor_lens", bar, "glow", [([-3, 140, -24.6], [6, 3, 0.6])])
    # Muzzle brake
    make_part("muzzle_top", bar, "dark", [([-22, 134, -312], [44, 6, 14])])
    make_part("muzzle_bottom", bar, "dark", [([-22, 100, -312], [44, 8, 14])])
    make_part("muzzle_sides", bar, "dark", [([-22, 108, -312], [8, 26, 14])], mirror=("x",))
    make_part("muzzle_vents", bar, "vent", [([-22.6, 112, -310], [0.6, 18, 10])], mirror=("x",))
    make_part("muzzle_rim_tb", bar, "glow", [([-14, 108, -312.6], [28, 1.5, 0.6]), ([-14, 132.5, -312.6], [28, 1.5, 0.6])])
    make_part("muzzle_rim_s", bar, "glow", [([-14, 109.5, -312.6], [1.5, 23, 0.6])], mirror=("x",))
    make_part("muzzle_hazard", bar, "hazard", [([-22, 140, -312], [44, 1, 14])])
    # Breech
    make_part("breech", bar, "paint", [([-24, 100, 22], [48, 40, 34])])
    make_part("breech_cap", bar, "dark", [([-20, 104, 56], [40, 32, 4])])
    make_part("breech_fins", bar, "steel", [([-18, 106, 60], [36, 2, 3])], array=(7, [0, 4, 0]))
    make_part("breech_lights", bar, "amber", [([-22, 140, 26], [4, 2, 4])], mirror=("x",))
    make_part("breech_hazard", bar, "hazard", [([-24.6, 128, 24], [0.6, 6, 30])], mirror=("x",))
    make_part("couplings", bar, "dark", [([-28, 108, 30], [4, 12, 18])], mirror=("x",))
    make_part("coupling_glow", bar, "glow", [([-28.6, 112, 36], [0.6, 4, 6])], mirror=("x",))

    # ---- Cooling fins (vents bone) and accelerator coils (coils bone) -------------------------
    make_part("fins", B["vents"], "steel", [([-16, 138, -140], [32, 6, 2])], array=(12, [0, 0, 10]))
    make_part("fin_tips", B["vents"], "glow", [([-14, 144, -140], [28, 0.5, 2])], array=(12, [0, 0, 10]))
    for i in range(5):
        z = -280 + i * 30
        make_part(f"coil{i}_tb", B["coils"], "glow", [([-21, 134, z], [42, 3, 4]), ([-21, 101, z], [42, 3, 4])])
        make_part(f"coil{i}_s", B["coils"], "glow", [([-21, 104, z], [3, 30, 4])], mirror=("x",))
        make_part(f"coil{i}_mount", B["coils"], "dark", [([-23, 114, z + 0.5], [2, 10, 3])], mirror=("x",))
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
    make_part("tab_screen_f", R, "glow", [([2.2, 4.2, 7.1], [11.6, 7.6, 0.08])])
    make_part("tab_screen_b", R, "glow", [([2.2, 4.2, 8.82], [11.6, 7.6, 0.08])])
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
        if obj.get("rot_y") is not None:
            x, y, z, sx, sy, sz = list(obj["cube_mc"])
            boxes.append({"bone": obj.parent.name, "kind": obj["kind"],
                          "min": [x, y, z], "max": [x + sx, y + sy, z + sz],
                          "rot_y": float(obj["rot_y"]), "rot_pivot": [x + sx / 2, y, z + sz / 2]})
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
                    if "rot_y" in box:
                        px, _, pz = box["rot_pivot"]
                        a = math.radians(box["rot_y"])
                        dx, dz = c[0] - px, c[2] - pz
                        c = (px + dx * math.cos(a) + dz * math.sin(a), c[1],
                             pz - dx * math.sin(a) + dz * math.cos(a))
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

    glow_px = color[mask]
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
            if "rot_y" in b:
                cube["pivot"] = b["rot_pivot"]
                cube["rotation"] = [0, b["rot_y"], 0]
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
# Animation: authored as Blender Actions, exported to GeckoLib JSON
# =============================================================================================

FOLD = 0.12
LEGS = ("leg_n", "leg_s", "leg_e", "leg_w")
LEG_FOLD = {"leg_n": [1, 1, FOLD], "leg_s": [1, 1, FOLD], "leg_e": [FOLD, 1, 1], "leg_w": [FOLD, 1, 1]}
LEG_OVER = {"leg_n": [1, 1, 1.06], "leg_s": [1, 1, 1.06], "leg_e": [1.06, 1, 1], "leg_w": [1.06, 1, 1]}
ONE = [1, 1, 1]
HIDE = [0.001, 0.001, 0.001]  # "invisible" (true zero would make GeckoLib's normal matrix singular)
POD_TURRET = [0, -34, 0]       # lowers the barrel pivot into the pod
POD_BARREL = [1, 1, 0.15]      # rail retracted; pitched straight up procedurally
PANEL_OPEN = [0, -18, -1.5]    # pod panel cover slid down and in

# Pod form, shared by falling / landed / packed / launching.
POD_POSE = {
    "base": {"scale": {0.0: HIDE, 1.0: HIDE}},
    "hull": {"scale": {0.0: HIDE, 1.0: HIDE}},
    "pod": {"scale": {0.0: ONE, 1.0: ONE}, "position": {0.0: [0, 0, 0], 1.0: [0, 0, 0]}},
    "pod_panel": {"position": {0.0: [0, 0, 0], 1.0: [0, 0, 0]}},
    "turret": {"position": {0.0: POD_TURRET, 1.0: POD_TURRET}},
    "barrel": {"scale": {0.0: POD_BARREL, 1.0: POD_BARREL}},
    "vents": {"position": {0.0: [0, -4, 0], 1.0: [0, -4, 0]}},
    "coils": {"scale": {0.0: [0.9, 0.9, 1], 0.5: [0.97, 0.97, 1], 1.0: [0.9, 0.9, 1]}},
}

# The transformation: pod sinks, fortress rises, stairs extend, turret grows, rail lowers and
# extends (pitch is procedural), clamps extend, lift, SLAM into the ground at 6.5 s, power-up.
DEPLOY_LEN = 8.0
DEPLOY = {
    "pod": {"position": {0.0: [0, 0, 0], 0.3: [0, 0, 0], 1.8: [0, -92, 0]},
            "scale": {0.0: ONE, 1.8: ONE, 1.85: HIDE, 8.0: HIDE}},
    "pod_panel": {"position": {0.0: PANEL_OPEN, 8.0: PANEL_OPEN}},
    "base": {"scale": {0.0: HIDE, 0.8: HIDE, 0.85: [0.85, 1, 0.85], 2.6: ONE},
             "position": {0.0: [0, -54, 0], 0.85: [0, -54, 0], 2.6: [0, 0, 0]}},
    "stairs": {"scale": {0.0: [1, 1, 0.05], 2.6: [1, 1, 0.05], 3.4: [1, 1, 1.04], 3.6: ONE}},
    **{leg: {"scale": {0.0: LEG_FOLD[leg], 4.6: LEG_FOLD[leg], 5.3: LEG_OVER[leg], 5.5: ONE},
             "position": {0.0: [0, 14, 0], 5.5: [0, 14, 0], 6.2: [0, 17, 0], 6.45: [0, -1.5, 0], 6.7: [0, 0, 0]}}
       for leg in LEGS},
    "turret": {"position": {0.0: POD_TURRET, 2.4: POD_TURRET, 3.8: [0, 2, 0], 4.0: [0, 0, 0]}},
    "hull": {"scale": {0.0: HIDE, 2.2: HIDE, 2.25: [0.4, 0.4, 0.4], 3.6: [1.03, 1.03, 1.03], 3.8: ONE}},
    "barrel": {"scale": {0.0: POD_BARREL, 4.6: POD_BARREL, 6.0: [1, 1, 1.03], 6.2: ONE}},
    "vents": {"position": {0.0: [0, -4, 0], 6.8: [0, -4, 0], 7.3: [0, 1.5, 0], 7.6: [0, 0, 0]}},
    "coils": {"scale": {0.0: [0.9, 0.9, 1], 6.8: [0.9, 0.9, 1], 7.4: [1.15, 1.15, 1], 8.0: ONE}},
}


def reverse_tracks(tracks, length):
    return {bone: {ch: {round(length - t, 4): v for t, v in keys.items()} for ch, keys in chans.items()}
            for bone, chans in tracks.items()}


# Pack-up: the transformation in reverse, ending with the pod panel closing.
PACK = reverse_tracks(DEPLOY, DEPLOY_LEN)
PACK["pod_panel"] = {"position": {0.0: PANEL_OPEN, 7.0: PANEL_OPEN, 7.5: [0, 0, 0.6], 8.0: [0, 0, 0]}}

ANIMATIONS = {
    # name: (length_s, loop, {bone: {"scale"/"position": {time_s: [x,y,z] in MC space}}})
    # Only position and scale are keyframed (sign-convention safe in GeckoLib); turret yaw and
    # barrel pitch are applied procedurally by RailgunModel.
    "animation.railgun.pod": (1.0, True, POD_POSE),
    "animation.railgun.panel_open": (1.5, "hold_on_last_frame", {
        **{k: v for k, v in POD_POSE.items() if k != "pod_panel"},
        "pod_panel": {"position": {0.0: [0, 0, 0], 0.35: [0, 0, 0.8], 1.0: [0, -18, 0.8], 1.5: PANEL_OPEN}},
    }),
    "animation.railgun.deploy": (DEPLOY_LEN, "hold_on_last_frame", DEPLOY),
    "animation.railgun.pack": (DEPLOY_LEN, "hold_on_last_frame", PACK),
    "animation.railgun.idle": (4.0, True, {
        "coils": {"scale": {0.0: ONE, 2.0: [1.025, 1.025, 1], 4.0: ONE}},
        "vents": {"position": {0.0: [0, 0, 0], 2.0: [0, 0.4, 0], 4.0: [0, 0, 0]}},
    }),
    "animation.railgun.charge": (0.5, True, {
        "coils": {"scale": {0.0: [1.04, 1.04, 1], 0.25: [1.12, 1.12, 1], 0.5: [1.04, 1.04, 1]}},
        "vents": {"position": {0.0: [0, 2.5, 0], 0.25: [0, 3.2, 0], 0.5: [0, 2.5, 0]}},
        "turret": {"position": {0.0: [0, 0, 0], 0.125: [0, -0.35, 0], 0.25: [0, 0, 0], 0.375: [0, -0.2, 0], 0.5: [0, 0, 0]}},
    }),
    "animation.railgun.fire": (1.8, False, {
        "barrel": {"position": {0.0: [0, 0, 0], 0.05: [0, 0, 16], 0.25: [0, 0, 12], 0.9: [0, 0, 0]}},
        "turret": {"position": {0.0: [0, 0, 0], 0.05: [0, -3, 0], 0.3: [0, 0.5, 0], 0.6: [0, 0, 0]}},
        "vents": {"position": {0.0: [0, 3, 0], 0.08: [0, 6, 0], 1.3: [0, 6, 0], 1.8: [0, 0, 0]}},
        "coils": {"scale": {0.0: [1.35, 1.35, 1], 0.4: [1.1, 1.1, 1], 1.8: ONE}},
    }),
}

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
                for t, val in keys.items():
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
                    keys[f"{frame / FPS:.3f}".rstrip("0").rstrip(".") or "0"] = [round(c, 4) + 0.0 for c in val]
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
    author_animations(rg_bones)
    export_animations(rg_bones, os.path.join(assets, "animations/railgun.animation.json"))
    print(f"[railgun] {len(rg_boxes)} cubes, {rg_tex}px atlas, {len(ANIMATIONS)} animations")

    # ---------------- Remote ----------------
    offset = Vector((120, 0, 0))  # keep it away from the railgun in the scene
    rm_bones = build_remote(offset)
    rm_boxes = extract_boxes(rm_bones, offset=offset)
    rm_tex = pack_faces(rm_boxes, texels_per_px=2, start_size=32)
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
        render(os.path.join(out, "railgun_deployed.png"), cam, target, (24.0, 21.0, 11.0), (0.0, 7.0, 5.0), 1100, lens=30)
        render(os.path.join(assets, "icon.png"), cam, target, (26.0, 22.0, 14.0), (0.0, 6.0, 5.0), 128, samples=32, lens=30)
        render(os.path.join(out, "railgun_stairs.png"), cam, target, (6.0, -20.0, 5.0), (0.0, -3.0, 2.5), 1100, lens=30)
        pose(rg_bones, "animation.railgun.pod", 0.0)
        rail_up(True)
        render(os.path.join(out, "railgun_pod.png"), cam, target, (9.0, -9.0, 5.5), (0.0, 0.0, 4.0), 900, lens=32)
        pose(rg_bones, "animation.railgun.panel_open", 1.5)
        rail_up(True)
        render(os.path.join(out, "railgun_pod_panel_open.png"), cam, target, (2.5, -7.0, 3.0), (0.0, -1.7, 2.6), 900, lens=35)
        pose(rg_bones, "animation.railgun.deploy", 3.0)
        rail_up(True)
        render(os.path.join(out, "railgun_transform_3s.png"), cam, target, (20.0, -18.0, 12.0), (0.0, 0.0, 3.0), 900, lens=32)
        pose(rg_bones, "animation.railgun.deploy", 6.3)
        rail_up(False)
        render(os.path.join(out, "railgun_transform_clamps.png"), cam, target, (20.0, -18.0, 12.0), (0.0, 0.0, 3.0), 900, lens=32)
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
