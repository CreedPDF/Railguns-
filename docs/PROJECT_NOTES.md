# Deluxs Armory - project notes (for picking work back up)

Fabric 1.20.1 mod, yarn mappings, mod id `orbital_railgun` (kept for world compatibility; the display name is
"Deluxs Armory"). Repo: CreedPDF/Railguns-.

## Owner preferences
- Deliver a ready .jar built by GitHub Actions (push to `main`, the Build workflow publishes release `v<mod_version>`).
- No in-game / client gametest runs in CI unless asked.
- Plays with shaders (Iris 1.6 + Sodium), render distance 32. Keep everything shader-compatible.
- No blue anywhere in models or UI; red / amber / warm white accents.
- Models are built in headless Blender (scripts in `tools/blender/`), not hand-made.
- New sounds are synthesised from scratch (`tools/sfx/`), never reused or near-copies of existing ones.
- Creative tabs: "Deluxs Armory: Railguns" and "Deluxs Armory: Military".

## Pipelines
- `tools/blender/build.sh` runs `build_models.py` (railgun, ship GeckoLib models) and `build_krl.py` (KRL models).
  `dev-krl*` branches set `ONLY=krl` (skip the railgun models) and commit renders to `docs/renders/`.
- KRL meshes use the "krl_mesh" JSON format (`client/krl/KrlMesh.java`): named parts with pivots, Minecraft axes
  (Blender (x, y, z) -> (x, z, -y)), textures baked from procedural materials. `KrlMesh.prepare` builds distance LODs
  and glow-only subsets; `KrlCompat` detects Iris shadow passes.
- Additive effects go through `BeamRenderLayers.textured`, which swaps in premultiplied textures when a shader pack is
  on (`PremulTextures`), so halos never show as hard discs.
- Custom fonts (Share Tech Mono, Rajdhani) are loaded at several raster sizes (`font/krl_<n>.json`) and picked per
  draw by on-screen size (`KrlText.t(s, px)`, `client/ui/Px`).

## Systems
- ARES-9 orbital railgun, ARES-0, starship, EVA suit, planets and meteor showers (`space/`).
- The railgun is run only from the uplink tablet (`client/tablet/TabletScreen.java`); the old pod panel is hidden.
- KRL: launcher, fire-control console (with air-defence radar), missile integration station, 3x3x2 interceptor
  magazine; flight path in `krl/KrlFlight.java`, impacts in `krl/KrlImpact.java`, air defence in `krl/KrlDefense.java`.

## Open ideas
- Redesign the starship (currently a cube-built GeckoLib model, `geo/ship.geo.json`) as a smooth Blender mesh,
  keeping the cabin box x [-2.5, 2.5], y [-5.5, 5.5], floor z 2, the ramp hinge, gear pivots and engine pods.
