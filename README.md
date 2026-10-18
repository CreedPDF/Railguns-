# Orbital Railgun (Fabric 1.20.1)

Call an autonomous railgun platform down from orbit, lock a target from a sci-fi fire-control
console, and fire a volumetric red beam.

## Requirements
| | |
|---|---|
| Minecraft | 1.20.1 |
| Fabric Loader | ≥ 0.15 |
| Fabric API | 0.92.x+1.20.1 |
| GeckoLib | 4.2 – 4.4.x (Fabric, 1.20.1) |

No mixins, no owo-lib, no Cloth Config. The beam uses a vanilla shader program in a custom
RenderLayer, so it works alongside Sodium; under Iris shader packs it renders through the pack's
lightning program.

## Gameplay
1. Craft the **Orbital Uplink Remote** (lightning rod, iron, eye of ender, netherite ingot).
2. Right-click a block with clear sky above it. A HUD countdown starts with an uplink sound.
3. The platform re-enters, slams down, and plays its deploy animation.
4. Right-click it to open **Fire Control**: type X/Y/Z (or **PAINT** the block under your
   crosshair), **SET TARGET**, then **FIRE**. It charges for 6 s, then fires.
5. **ABORT** cancels a charge. **RECALL** sends it back to orbit. Creative players can also
   sneak-punch it to recall.

Tuning (ranges, timings, explosion power, block damage) lives in `RailgunConfig.java`.

## Building
```bash
./gradlew build          # jar lands in build/libs/orbital-railgun-1.0.0.jar
./gradlew runClient      # dev client
```
Pushing to GitHub runs `.github/workflows/build.yml`, which builds the jar and attaches it to the
run (Actions tab → latest run → Artifacts). Pushing a tag like `v1.0.0` also creates a GitHub
Release with the jar.

## Architecture
```
common (src/main)                          client (src/client)
├─ RailgunMod            registration      ├─ RailgunModClient     renderer, HUD, bridge
├─ RailgunClientBridge   side-safe hooks   ├─ render/
├─ item/RailgunRemoteItem                  │   ├─ RailgunEntityRenderer  GeckoLib + beam
├─ entity/RailgunEntity  state machine     │   ├─ RailgunModel           procedural aim
├─ entity/RailgunState                     │   ├─ BeamRenderLayers       custom RenderLayer
├─ network/ModPackets    C2S/S2C + checks  │   └─ BeamRenderer           3D beam geometry
├─ util/DeploymentScheduler                ├─ screen/RailgunControlScreen
└─ util/RailgunConfig                      ├─ hud/RailgunHud
                                           ├─ sound/RailgunChargeSoundInstance
                                           └─ network/ClientPackets
```

**State machine:** `FALLING → DEPLOYING → IDLE ⇄ CHARGING → FIRING → COOLDOWN → IDLE`.
The server owns it; state, charge and target are `TrackedData`, so clients get them through the
vanilla entity tracker with no extra packets.

### Networking
| Dir | Channel | Payload | Purpose |
|---|---|---|---|
| C2S | `request_deploy` | BlockPos | Remote right-click. Server checks item, cooldown, range, sky. |
| C2S | `set_target` | entityId, BlockPos | Menu target. Range & world-bounds checked. |
| C2S | `start_charge` | entityId | FIRE button. IDLE → CHARGING, auto-fires at 100 %. |
| C2S | `abort` | entityId | Cancel charge. |
| C2S | `recall` | entityId | Remove platform. |
| S2C | `drop_countdown` | BlockPos, ticks | Start HUD countdown + local uplink sound. |

Every C2S handler runs on the server thread and checks the entity exists, is a railgun, and is
within 12 blocks of the sender.

### Beam rendering
`BeamRenderLayers.BEAM` = POSITION_COLOR + vanilla lightning shader + additive blending + no
depth write + no culling. `BeamRenderer` emits four concentric N-sided tubes (white core → red
body → two halos) plus spheres and rings. Additive blending sums the shells into a bright core
with soft falloff, which reads as a volumetric glow without post-processing. See the Javadoc in
both classes for the full explanation.

## Models (built in Blender, headless)
Every model, texture and animation is produced by `tools/blender/build_models.py`, run in
Blender 4.2 LTS with no UI:

```bash
tools/blender/build.sh /path/to/blender     # or just `tools/blender/build.sh` if blender is on PATH
```

What the script does:
1. **Models** the railgun emplacement (octagonal concrete bunker with anchor blocks, turntable,
   white armoured yoke and slotted barrel housing, after the concept sketch) and the uplink
   remote as box geometry parented to Empties
   (the Empties become GeckoLib bones). Symmetric and repeated parts use **Mirror** and
   **Array** modifiers (vents, capacitor banks, rails, coil frames, radiator fins).
2. **Bakes textures** with Cycles: procedural materials (noise + Voronoi panel seams + glow
   gradients) are baked into a pixel atlas with per-face UVs, plus a glowmask for the emissive
   coils and lights.
3. **Animates** `falling`, `deploy`, `idle`, `charge`, `fire` as Blender Actions on the bone
   Empties, then exports them by reading the Actions back.
4. **Exports** `geo/railgun.geo.json`, `animations/railgun.animation.json`, the entity texture
   and glowmask, a **3D item model** for the remote, and renders `icon.png`.
5. Saves `build/blender/orbital_railgun_models.blend` and preview renders
   (`railgun_deployed.png`, `railgun_folded.png`, `remote.png`).

CI runs this before building the jar. On `main` it commits the regenerated assets back to the
repo, and the renders and `.blend` are attached to every run as the `blender-output` artifact.
If the Blender step fails, the build continues with the assets already in the repo and shows a
warning.

To edit by hand, open the `.blend`, change the modelling objects or Actions, and port the change
into `build_models.py` (the script is the source of truth). Contracts the Java code relies on:
- Bone **`turret`**: yaw pivot, at `PIVOT_Y` (2.5 blocks = 40 px) above the origin.
- Bone **`barrel`**: child of turret, pitch pivot, pointing toward −Z (north), muzzle
  `BARREL_LENGTH` (4.0 blocks) from the pivot.
- Animation names as above; `deploy` is 3 s and holds its last frame.
- Glowing parts must exist: `tools/validate_assets.py` fails the build if the glowmask is
  empty, because GeckoLib crashes on an empty glow layer.
- Only boxes (optionally rotated around Y via `make_rot_part`): GeckoLib and vanilla item models can't use arbitrary meshes. The
  exporter refuses anything else.

### Custom sounds
`sounds.json` points at vanilla files so it works out of the box. Put `.ogg` files in
`assets/orbital_railgun/sounds/` and change e.g. `"minecraft:block/beacon/ambient"` to
`"orbital_railgun:charge_loop"`.

## License
MIT
