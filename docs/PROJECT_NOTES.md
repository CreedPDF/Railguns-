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
- Creative tabs: "Deluxs Armory: Railguns", "Deluxs Armory: Military" and "Deluxs Armory: Handhelds".

## Pipelines
- `tools/blender/build.sh` runs `build_models.py` (railgun GeckoLib models), `build_krl.py` (KRL), `build_ship.py`
  (starship) and `build_hangeki.py` (HANGEKI). Dev branches build one set: `dev-krl*` -> ONLY=krl,
  `dev-ship*` -> ONLY=ship, `dev-hangeki*` -> ONLY=hangeki; renders are committed to `docs/renders/`.
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

## Done
- v4.15 **Starship redesign**: smooth Blender mesh (`build_ship.py`) drawn with KrlMesh by `client/ship/ShipRenderer`
  (posed exactly like `ShipEntity.modelToWorld`). Parts: hull, main, glass (translucent), eng_l/eng_r (pivots
  +-6.25, -1.25, 3.125 blender), gear_f/l/r, ramp. VTOL nozzles end at y -4 (`NOZZLE_Z` = -64 px in the renderer).
- v4.15 Space Command was built, then **removed in v4.16** at the owner's request (replaced by Arsenal Legion).
- v4.16 **Arsenal Legion** (namespace `arsenal_legion`, code in `legion/` and `client/legion/`): player swarm
  "capability" (`legion/capability`, stored on the player by `mixin/PlayerEntitySwarmMixin`, attribute
  `arsenal_legion:swarm_capacity` in `SwarmAttributes`), `AutonomousWeaponEntity` (ORBIT / STRIKE / DEFEND),
  `SwarmManager` (entities from the capability, strikes, projectile defence, death drops), Legion Altar block,
  packets `swarm_sync` / `swarm_toggle`, key G. Assets: `build_legion.py`, `legion_textures.py`, `legion_sounds.py`.

- v4.17 Cosmic Reliquary (handheld arsenal) was built, then **removed in v4.16.1** at the owner's request; Arsenal
  Legion kept.

- v4.18 Arsenal Legion upgrade: shared path maths `legion/SwarmPaths` (formations `SwarmFormation`, storm), client
  evaluates per frame (`client/legion/render/LegionVisual`) and ignores position packets in analytic states; focus
  volleys (sneak-attack), Fury + Blade Storm (V), formations (H); VFX in `client/legion/render/LegionFx`.

- v4.19 **Arsenal Legion removed** at the owner's request. Added **HANGEKI // Reversal Edge** (`hangeki/`,
  `client/hangeki/`), first item of the new Handhelds tab: a parry sword. `ParryServer` scans each sword-bearer every
  world tick (projectiles, KRL flights, charging railguns, wardens with SONIC_BOOM_SOUND_DELAY), holds imminent threats
  and slows time globally (`TimeScale`, server tick loop via `mixin/MinecraftServerTimeMixin`, client clock via
  `client/mixin/RenderTickCounterMixin`); parry -> STANCE (cutscene `HangekiCine`, pose `BipedEntityModelMixin`) ->
  impact -> reflect at 2x (railgun hook `RailgunEntity.parried`/`holdsRailgun`, KRL hook `KrlServer.intercept`,
  `KrlImpact.explodeReflected`). Melee: guard window + counter. HUD/impact frames `HangekiHud` (Bangers font,
  `font/impact.json`), world FX `HangekiFx`. Key R (also right-click).

## Other ideas discussed
- Military tab: a drivable mobile launcher truck for the KRL rails; a low-flying cruise missile; a bunker block set
  (reinforced concrete, blast door, periscope camera).
- Railguns tab: an ARES-9 overwatch mode that fires on hostiles the KRL radar marks inside a painted zone.
