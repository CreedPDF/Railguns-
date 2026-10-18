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
PALETTE = {
    "concrete": ("#4a4844", "#7d7a74", "#a29e96"),  # seam, shade, light
    "paint": ("#7c8086", "#bfc3c8", "#e6e8eb"),    # off-white armour panels
    "hull": ("#2f343c", "#4c535e", "#68717e"),
    "dark": ("#101215", "#1e2126", "#2c3038"),
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
    bsdf.inputs["Metallic"].default_value = 0.55
    bsdf.inputs["Roughness"].default_value = 0.45
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


def make_rot_part(name, bone, kind, center_xz, y0, side, height, angle=45.0):
    """
    A square box rotated around the vertical axis (Bedrock cube "rotation"). Used for the
    octagonal concrete tiers and turntable: an upright square plus a 45-degree square = an
    octagon. Only squares are rotated, which keeps the result identical whichever sign convention
    the renderer uses for Y rotation.
    """
    pivot = Vector(bone["pivot_mc"])
    h = side / 2.0
    bm = bmesh.new()
    lo, hi = Vector((-h / 16, -h / 16, 0)), Vector((h / 16, h / 16, height / 16))
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
    obj["cube_mc"] = [center_xz[0] - h, y0, center_xz[1] - h, side, height, side]
    return obj


def octagon(name, bone, kind, y0, width, height, center_xz=(0, 0)):
    """Chamfered octagon slab = square + 45-degree square (82% size so corners are cut, not pointy)."""
    make_part(f"{name}_sq", bone, kind, [([center_xz[0] - width / 2, y0, center_xz[1] - width / 2],
                                          [width, height, width])])
    make_rot_part(f"{name}_rot", bone, kind, center_xz, y0, round(width * 0.82, 2), height)


def leg_boxes(direction):
    """
    Perimeter anchor block cluster (the chunky concrete blocks around the bunker in the
    reference), authored for the north side and rotated in 90-degree steps around Y.
    """
    north = [
        ("concrete", [[-25, 0, -40], [21, 8, 13]]),
        ("concrete", [[4, 0, -40], [21, 8, 13]]),
        ("concrete", [[-22, 8, -37], [15, 3, 8]]),
        ("concrete", [[7, 8, -37], [15, 3, 8]]),
        ("concrete", [[-4, 0, -36], [8, 5, 8]]),
        ("dark", [[-3, 0, -43], [6, 3, 4]]),
        ("amber", [[-1, 5, -36.5], [2, 1, 1]]),
    ]

    def rot(o, s):
        x0, y0, z0 = o
        x1, z1 = x0 + s[0], z0 + s[2]
        if direction == "n":
            return o, s
        if direction == "s":
            return [x0, y0, -z1], s
        if direction == "e":
            return [-z1, y0, x0], [s[2], s[1], s[0]]
        return [z0, y0, x0], [s[2], s[1], s[0]]  # west

    return [(kind, rot(o, s)) for kind, (o, s) in north]


def build_railgun():
    """
    Railgun emplacement modelled after the concept sketch: an octagonal two-tier concrete bunker
    ringed by chunky anchor blocks, a dark turntable ring, a yoke with white armoured cheek
    plates, and a long rectangular white barrel housing with an open slot exposing the glowing
    accelerator core, plus a boxy breech at the rear.

    Returns {bone_name: empty} in parent-before-child order.
    """
    bones = {}

    def bone(name, parent, pivot):
        p = bones.get(parent)
        bones[name] = make_bone(name, p, pivot, p["pivot_mc"] if p else None)

    bone("root", None, [0, 0, 0])
    bone("base", "root", [0, 0, 0])
    for d, pivot in (("n", [0, 2, -27]), ("s", [0, 2, 27]), ("e", [27, 2, 0]), ("w", [-27, 2, 0])):
        bone(f"leg_{d}", "base", pivot)
    bone("turret", "root", [0, 40, 0])
    bone("barrel", "turret", [0, 40, 0])
    bone("coils", "barrel", [0, 40, 0])
    B = bones

    # ---- Concrete bunker -------------------------------------------------------------------
    octagon("bunker_low", B["base"], "concrete", 0, 54, 8)
    octagon("bunker_high", B["base"], "concrete", 8, 42, 6)
    octagon("bunker_deck", B["base"], "dark", 14, 32, 2)
    make_part("bunker_lip", B["base"], "concrete", [([-27.5, 7, -27.5], [55, 1, 2])], mirror=("z",))
    make_part("bunker_lip_side", B["base"], "concrete", [([-27.5, 7, -25.5], [2, 1, 51])], mirror=("x",))
    make_part("bunker_cracks", B["base"], "dark", [([-13, 13.9, -21.1], [6, 0.2, 0.2])], mirror=("x", "z"))
    make_part("bunker_lights", B["base"], "glow", [([-1, 10, -21.2], [2, 2, 0.2])], mirror=("z",))
    make_part("bunker_lights_side", B["base"], "glow", [([-21.2, 10, -1], [0.2, 2, 2])], mirror=("x",))

    # ---- Anchor block clusters (deploy outward) ----------------------------------------------
    for d in ("n", "s", "e", "w"):
        for i, (kind, (o, s)) in enumerate(leg_boxes(d)):
            make_part(f"leg_{d}_{i}", B[f"leg_{d}"], kind, [(o, s)])

    # ---- Turret: turntable + yoke ------------------------------------------------------------
    octagon("turntable_ring", B["turret"], "dark", 16, 30, 3)
    octagon("turntable_top", B["turret"], "paint", 19, 25, 2)
    make_part("turntable_glow", B["turret"], "glow", [([-1, 17, -15.2], [2, 1, 0.2])], mirror=("z",))
    make_part("yoke_column", B["turret"], "dark", [([-7, 21, -7], [14, 10, 14])])
    make_part("yoke_core", B["turret"], "hull", [([-5, 31, -5], [10, 4, 10])])
    make_part("yoke_cheeks", B["turret"], "paint", [([-14, 23, -9], [5, 22, 18])], mirror=("x",))
    make_part("yoke_cheek_bevel", B["turret"], "paint", [([-13, 21, -7], [4, 2, 14])], mirror=("x",))
    make_part("yoke_hubs", B["turret"], "dark", [([-15, 36, -4], [1, 8, 8])], mirror=("x",))
    make_part("yoke_hub_glow", B["turret"], "glow", [([-15.5, 39, -1], [0.5, 2, 2])], mirror=("x",))
    make_part("yoke_pistons", B["turret"], "hull", [([-12, 21, 9], [3, 12, 3])], mirror=("x",))

    # ---- Barrel housing (points to -Z / north): long, slim, slotted -------------------------
    make_part("barrel_walls", B["barrel"], "paint", [([-9, 34, -62], [5, 12, 70])], mirror=("x",))
    make_part("barrel_floor", B["barrel"], "paint", [([-9, 32, -60], [18, 2, 68])])
    make_part("barrel_roof", B["barrel"], "paint", [([-9, 46, -24], [18, 2, 32])])
    make_part("barrel_roof_vents", B["barrel"], "dark", [([-6, 47.6, -20], [12, 0.5, 2])], array=(4, [0, 0, 6]))
    make_part("barrel_slot_core", B["barrel"], "dark", [([-4, 34, -62], [8, 3, 38])])
    make_part("barrel_rail_core", B["barrel"], "glow", [([-1.5, 38.5, -61], [3, 3, 56])])
    make_part("barrel_wall_grooves", B["barrel"], "dark", [([-9.2, 42, -58], [0.2, 1, 32])], mirror=("x",))
    make_part("barrel_wall_panels", B["barrel"], "hull", [([-9.4, 36, -20], [0.4, 7, 14])], mirror=("x",))
    make_part("barrel_wall_glow", B["barrel"], "glow", [([-9.5, 44, -18], [0.5, 1, 10])], mirror=("x",))
    make_part("barrel_muzzle_top", B["barrel"], "dark", [([-10, 46, -66], [20, 3, 4])])
    make_part("barrel_muzzle_bottom", B["barrel"], "dark", [([-10, 31, -66], [20, 3, 4])])
    make_part("barrel_muzzle_sides", B["barrel"], "dark", [([-10, 34, -66], [4, 12, 4])], mirror=("x",))
    make_part("barrel_breech", B["barrel"], "paint", [([-11, 32, 8], [22, 15, 10])])
    make_part("barrel_breech_cap", B["barrel"], "dark", [([-9, 34, 18], [18, 11, 3])])
    make_part("barrel_breech_fins", B["barrel"], "hull", [([-8, 35, 21], [16, 1, 2])], array=(4, [0, 3, 0]))
    make_part("barrel_breech_light", B["barrel"], "amber", [([-1, 47, 10], [2, 1, 2])])
    make_part("barrel_sensor", B["barrel"], "dark", [([-3, 48, -8], [6, 3, 8])])
    make_part("barrel_sensor_eye", B["barrel"], "glow", [([-2, 49, -8.5], [4, 1, 0.5])])

    # ---- Accelerator coils: square frames around the core, spin about the barrel axis --------
    for z in (-56, -48, -40):
        make_part(f"coil_{-z}_tb", B["coils"], "glow", [([-3, 43, z], [6, 1, 2]), ([-3, 36, z], [6, 1, 2])])
        make_part(f"coil_{-z}_s", B["coils"], "glow", [([-3, 37, z], [1, 6, 2])], mirror=("x",))
    return bones


def build_remote(offset):
    """The handheld uplink remote, in vanilla item space (0..16 px, centred on 8,8)."""
    bones = {"remote": make_bone("remote", None, [8, 0, 8], None, offset=offset)}
    R = bones["remote"]
    make_part("remote_body", R, "paint", [([5, 0, 7], [6, 11, 2])])
    make_part("remote_bezel", R, "dark", [([5.5, 5.5, 6.5], [5, 4.5, 0.5])])
    make_part("remote_screen", R, "glow", [([6, 6, 6.25], [4, 3.5, 0.25])])
    make_part("remote_button_fire", R, "glow", [([6, 2, 6.5], [1.5, 1.5, 0.5])])
    make_part("remote_button_arm", R, "amber", [([8.5, 2, 6.5], [1.5, 1.5, 0.5])])
    make_part("remote_grips", R, "dark", [([4.5, 1, 7.25], [0.5, 8, 1.5])], mirror=("x",))
    make_part("remote_antenna", R, "dark", [([9, 11, 7.5], [1, 4, 1])])
    make_part("remote_antenna_tip", R, "glow", [([9, 15, 7.5], [1, 1, 1])])
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
    """Shelf-packs every face of every box. Adds box['rects'][face] = (u, v, w, h) in texels."""
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
        x = y = shelf = 0
        placed = {}
        fits = True
        for th, tw, bi, face in items:
            if x + tw > tex:
                x, y, shelf = 0, y + shelf, 0
            if y + th > tex or tw > tex:
                fits = False
                break
            placed[(bi, face)] = (x, y, tw, th)
            x += tw
            shelf = max(shelf, th)
        if fits:
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
                "visible_bounds_width": 8, "visible_bounds_height": 6,
                "visible_bounds_offset": [0, 2, 0],
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
            "gui": {"rotation": [20, 200, 0], "translation": [0, -0.5, 0], "scale": [1.1, 1.1, 1.1]},
            "ground": {"translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed": {"rotation": [0, 180, 0], "scale": [1.1, 1.1, 1.1]},
            "head": {"translation": [0, 13, 7]},
            "thirdperson_righthand": {"rotation": [-15, 180, 0], "translation": [0, 2.5, 1.5], "scale": [0.6, 0.6, 0.6]},
            "thirdperson_lefthand": {"rotation": [-15, 180, 0], "translation": [0, 2.5, 1.5], "scale": [0.6, 0.6, 0.6]},
            "firstperson_righthand": {"rotation": [5, 160, -5], "translation": [1, 3, 1], "scale": [0.7, 0.7, 0.7]},
            "firstperson_lefthand": {"rotation": [5, 200, 5], "translation": [1, 3, 1], "scale": [0.7, 0.7, 0.7]},
        },
    }
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(model, f, indent=1)


# =============================================================================================
# Animation: authored as Blender Actions, exported to GeckoLib JSON
# =============================================================================================

FOLD = 0.12
ANIMATIONS = {
    # name: (length_s, loop, {bone: {"scale"/"position"/"rotation": {time_s: [x,y,z] in MC space}}})
    "animation.railgun.falling": (1.0, True, {
        **{b: {"scale": {0.0: s, 1.0: s}} for b, s in (
            ("leg_n", [1, 1, FOLD]), ("leg_s", [1, 1, FOLD]), ("leg_e", [FOLD, 1, 1]), ("leg_w", [FOLD, 1, 1]))},
        "turret": {"position": {0.0: [0, -18, 0], 1.0: [0, -18, 0]}},
        "barrel": {"scale": {0.0: [1, 1, 0.35], 1.0: [1, 1, 0.35]}},
        "coils": {"rotation": {0.0: [0, 0, 0], 1.0: [0, 0, 90]}},
    }),
    "animation.railgun.deploy": (3.0, "hold_on_last_frame", {
        **{b: {"scale": {0.0: s, 0.2: s, 0.8: o, 1.0: [1, 1, 1]}} for b, s, o in (
            ("leg_n", [1, 1, FOLD], [1, 1, 1.06]), ("leg_s", [1, 1, FOLD], [1, 1, 1.06]),
            ("leg_e", [FOLD, 1, 1], [1.06, 1, 1]), ("leg_w", [FOLD, 1, 1], [1.06, 1, 1]))},
        "turret": {"position": {0.0: [0, -18, 0], 1.0: [0, -18, 0], 1.8: [0, 1, 0], 2.0: [0, 0, 0]}},
        "barrel": {"scale": {0.0: [1, 1, 0.35], 2.0: [1, 1, 0.35], 2.7: [1, 1, 1.04], 3.0: [1, 1, 1]}},
        "coils": {"rotation": {0.0: [0, 0, 0], 3.0: [0, 0, 270]}},
    }),
    "animation.railgun.idle": (4.0, True, {
        "coils": {"rotation": {0.0: [0, 0, 0], 4.0: [0, 0, 90]},
                  "scale": {0.0: [1, 1, 1], 2.0: [1.03, 1.03, 1], 4.0: [1, 1, 1]}},
    }),
    "animation.railgun.charge": (0.5, True, {
        "coils": {"rotation": {0.0: [0, 0, 0], 0.5: [0, 0, 90]},
                  "scale": {0.0: [1.04, 1.04, 1], 0.25: [1.12, 1.12, 1], 0.5: [1.04, 1.04, 1]}},
        "turret": {"position": {0.0: [0, 0, 0], 0.25: [0, -0.3, 0], 0.5: [0, 0, 0]}},
    }),
    "animation.railgun.fire": (1.5, False, {
        "barrel": {"scale": {0.0: [1, 1, 1], 0.05: [1, 1, 0.82], 0.6: [1, 1, 0.95], 1.5: [1, 1, 1]}},
        "coils": {"rotation": {0.0: [0, 0, 0], 0.5: [0, 0, 270], 1.5: [0, 0, 360]},
                  "scale": {0.0: [1.25, 1.25, 1], 1.5: [1, 1, 1]}},
        "turret": {"position": {0.0: [0, 0, 0], 0.05: [0, -1.5, 0], 0.5: [0, 0, 0]}},
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
    rim = link(bpy.data.objects.new("rim", bpy.data.lights.new("rim", "SUN")))
    rim.data.energy = 1.5
    rim.data.color = (0.65, 0.75, 1.0)
    rim.rotation_euler = (math.radians(70), 0, math.radians(160))

    ground = bpy.data.meshes.new("ground")
    ground.from_pydata([(-30, -30, 0), (30, -30, 0), (30, 30, 0), (-30, 30, 0)], [], [(0, 1, 2, 3)])
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
    offset = Vector((40, 0, 0))  # keep it away from the railgun in the scene
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
        pose(rg_bones, "animation.railgun.deploy", 3.0)
        render(os.path.join(out, "railgun_deployed.png"), cam, target, (8.5, 8.0, 5.4), (0, 0.6, 1.6), 900)
        render(os.path.join(assets, "icon.png"), cam, target, (7.0, 6.8, 4.6), (0, 0.6, 1.7), 128, samples=32)
        pose(rg_bones, "animation.railgun.falling", 0.0)
        render(os.path.join(out, "railgun_folded.png"), cam, target, (8.5, 8.0, 5.4), (0, 0.4, 1.2), 900)
        reset_pose(rg_bones)

        set_visible(rg_bake, False)
        set_visible(rm_bake, True)
        ground.location = (40, 0, -0.01)
        render(os.path.join(out, "remote.png"), cam, target, (41.3, 1.5, 0.9), (40.5, -0.47, 0.5), 700, lens=60)
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
