# Orbital Railgun (Fabric 1.20.1)

Call a colossal railgun emplacement down from orbit, lock a target from its fire-control
console, and fire a churning, textured plasma beam. Every model, texture, particle and sound
is made by this repo's own tools (Blender, numpy, synthesized audio). No vanilla effects.

## Requirements
| | |
|---|---|
| Minecraft | 1.20.1 |
| Fabric Loader | ≥ 0.15 |
| Fabric API | 0.92.x+1.20.1 |
| GeckoLib | 4.2 – 4.4.x (Fabric, 1.20.1) |

No mixins, no owo-lib, no Cloth Config. The beam uses vanilla shader programs in custom render
layers, so it works alongside Sodium.

## Gameplay
1. Craft the **Orbital Uplink Remote** (lightning rod, iron, eye of ender, netherite ingot).
2. Right-click a block with open sky above it. A HUD countdown starts with an uplink chime.
3. The platform re-enters in a plasma sheath with a rising roar, smashes down (clearing trees
   and anything in its 10×10 footprint, with a shockwave), then unfolds its outriggers and raises
   the turret.
4. Right-click it to open **Fire Control**: type X/Y/Z (or **PAINT** the block under your
   crosshair), **SET TARGET**, then **FIRE**. It charges for 6 s (energy motes, lightning arcs,
   a swelling orb) and fires once the turret is aligned.
5. **ABORT** cancels a charge. **RECALL** sends it back to orbit. Creative players can also
   sneak-punch it to recall.

Tuning (ranges, timings, blast power, block damage) lives in `RailgunConfig.java`.

## Building
Every push to `main` runs GitHub Actions, which:
1. downloads Blender 4.2 LTS and runs `tools/blender/build_models.py` headless,
2. validates the textures (`tools/validate_assets.py`),
3. commits regenerated assets and preview renders (`docs/renders/`) back to `main`,
4. builds the jar and publishes it to the release for the current `mod_version`.

Locally: `./gradlew build` (jar in `build/libs/`), `./gradlew runClient`.

## Asset pipeline
| Tool | Produces |
|---|---|
| `tools/blender/build_models.py` | The railgun emplacement (~380 cubes, 11-block fortress, 19-block barrel) and the remote as box geometry under bone Empties, with Mirror/Array modifiers; Cycles-baked base materials plus a per-face paint pass (panel seams, rivets, bevels, concrete cracks, brushed steel, vent slats, hazard stripes, grime); the glowmask; Blender Actions exported as GeckoLib animations; Cycles renders and the icon. |
| `tools/textures/build_textures.py` | Tileable beam textures (core, plasma, halo, helix, shockwave), animated particle sprites, fire-control and HUD panel art. |
| `tools/sfx/build_sounds.py` | 12 sounds synthesized from oscillators, filtered noise, FM and convolution reverb, encoded to Ogg Vorbis. |

Model contracts the Java code relies on:
- Bone **`turret`** yaws; bone **`barrel`** (pivot 120 px = `PIVOT_Y` 7.5 blocks) pitches and
  points toward −Z, muzzle 312 px = `BARREL_LENGTH` 19.5 blocks from the pivot.
- Bones `leg_n/s/e/w`, `vents`, `coils` are animated by the keyframes.
- Animations: `falling`, `deploy` (3 s, holds), `idle`, `charge`, `fire`.
- Only boxes (rotated ones only in ±45° pairs). The exporter refuses anything else.
- The glowmask must have lit pixels (GeckoLib crashes on an empty glow layer); CI enforces it.

## Architecture
```
common (src/main)                            client (src/client)
├─ RailgunMod              registration      ├─ RailgunModClient        renderer, particles, HUD, bridge
├─ RailgunClientBridge     side-safe hooks   ├─ render/
├─ item/RailgunRemoteItem                    │   ├─ RailgunEntityRenderer   GeckoLib + beam
├─ entity/RailgunEntity    state machine     │   ├─ RailgunModel            procedural aim
├─ network/ModPackets      C2S/S2C + checks  │   ├─ BeamRenderLayers        additive glow + textured layers
├─ registry/ModSounds                        │   └─ BeamRenderer            3D beam geometry
├─ registry/ModParticles                     ├─ particle/RailgunParticle(s) custom particle behaviour
├─ util/RailgunFx          FX-free explosions├─ screen/RailgunControlScreen
├─ util/DeploymentScheduler                  ├─ hud/RailgunHud          countdown, flash, camera shake
└─ util/RailgunConfig                        └─ sound/                  charge + re-entry loops
```

State machine: `FALLING → DEPLOYING → IDLE ⇄ CHARGING → FIRING → COOLDOWN → IDLE`, owned by the
server; state, charge and target are `TrackedData`, so no extra sync packets are needed.

### Networking
| Dir | Channel | Payload | Purpose |
|---|---|---|---|
| C2S | `request_deploy` | BlockPos | Remote right-click. Server checks item, cooldown, range, sky. |
| C2S | `set_target` | entityId, BlockPos | Menu target. Range & world-bounds checked. |
| C2S | `start_charge` | entityId | FIRE button. IDLE → CHARGING, auto-fires at 100 %. |
| C2S | `abort` | entityId | Cancel charge. |
| C2S | `recall` | entityId | Remove platform. |
| S2C | `drop_countdown` | BlockPos, ticks | Start HUD countdown + local uplink sound. |

### Beam rendering
Two kinds of layer, both additive with no depth write and no culling: an untextured glow layer
(lightning shader) for bloom shells, orbs and arcs, and textured layers (beacon-beam shader,
bilinear) for the plasma. The firing beam is a white-hot core, two counter-scrolling plasma
tubes, a wispy halo, glow shells, three helix ribbons, accelerator rings, muzzle and impact
fireballs, a rising plume and expanding ground shockwaves. See the Javadoc in
`BeamRenderLayers` and `BeamRenderer`.

## License
MIT
