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

## Replacing the placeholder model
The repo ships generated placeholder assets (`tools/generate_placeholder_assets.py`). To use your
Blender/Blockbench export, replace:
- `assets/orbital_railgun/geo/railgun.geo.json`
- `assets/orbital_railgun/animations/railgun.animation.json`
- `assets/orbital_railgun/textures/entity/railgun.png` (+ optional `railgun_glowmask.png`)

Keep these contracts or update the code:
- Bone **`turret`**: yaw pivot, at `PIVOT_Y` (2.5 blocks = 40 px) above the origin.
- Bone **`barrel`**: child of turret, pitch pivot, barrel pointing toward −Z (north in Blockbench),
  muzzle `BARREL_LENGTH` (3.5 blocks) from the pivot.
- Animations: `animation.railgun.falling`, `.deploy` (3 s, hold last frame), `.idle`, `.charge`,
  `.fire`.
- If the barrel aims mirrored, flip `YAW_SIGN` / `PITCH_SIGN` in `RailgunModel`.

### Custom sounds
`sounds.json` points at vanilla files so it works out of the box. Put `.ogg` files in
`assets/orbital_railgun/sounds/` and change e.g. `"minecraft:block/beacon/ambient"` to
`"orbital_railgun:charge_loop"`.

## License
MIT
