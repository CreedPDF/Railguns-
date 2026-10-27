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
- `tools/blender/build.sh` runs `build_models.py` (railgun GeckoLib models), `build_krl.py` (KRL), `build_ship.py`
  (starship) and `build_station.py` (Space Command). Dev branches build one set: `dev-krl*` -> ONLY=krl,
  `dev-ship*` -> ONLY=ship, `dev-station*` -> ONLY=station; renders are committed to `docs/renders/`.
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

## Done (v4.15)
- **Starship redesign**: smooth Blender mesh (`build_ship.py`) drawn with KrlMesh by `client/ship/ShipRenderer`
  (posed exactly like `ShipEntity.modelToWorld`). Parts: hull, main, glass (translucent), eng_l/eng_r (pivots
  +-6.25, -1.25, 3.125 blender), gear_f/l/r, ramp. VTOL nozzles end at y -4 (`NOZZLE_Z` = -64 px in the renderer).
- **Space Command** (`station/` server, `client/station/`): hub grid (`ScHubBlockEntity`, `ScGrid`), solar
  (`ScSolarBlockEntity`), point-defence railgun (`ScRailgunBlockEntity`), strike satellite (`ScSatelliteBlockEntity`,
  charge persisted in `ScSatellites`), debris (`ScDebris`), strikes (`ScStrike`, timeline FIRE 50 / IMPACT 62 /
  BEAM_END 112), effects `ScFx`, cutscene `ScCine`, hub console `ScHubScreen`, tablet page `SAT_STRIKE`.

## Other ideas discussed
- Space Command: more station modules (docking port for the starship, habitat ring, cargo bay); let the strike
  satellite target other dimensions / planets.
- Military tab: a drivable mobile launcher truck for the KRL rails; a low-flying cruise missile; a bunker block set
  (reinforced concrete, blast door, periscope camera).
- Railguns tab: an ARES-9 overwatch mode that fires on hostiles the KRL radar marks inside a painted zone.
