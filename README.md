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

Three small client mixins (two-handed tablet in first and third person, camera fly-in to the control panel); no owo-lib, no Cloth Config. The beam uses vanilla shader programs in custom render
layers, so it works alongside Sodium.

## Gameplay
1. Craft the **ARES-9 Uplink Tablet** (lightning rod, iron, eye of ender, netherite ingot). You
   hold it in **both hands**.
2. Aim at open ground and right-click: the tablet rises and the view **zooms into its display**.
   On the *Orbital Drop* page press **CALL DOWN**. A HUD countdown runs, then a compact pod
   re-enters in a plasma sheath and lands with its rail pointing straight up, retracted.
3. Walk up to the pod and right-click its **panel**: the camera flies up to the physical panel on
   the pod and its screen becomes the UI. Press **DEPLOY RAILGUN**; the cover slides away and the
   pod transforms: it sinks while the fortress rises, the stairs fold out, the turret
   grows, the rail lowers and extends, and the ground clamps lift and **slam** into the ground.
4. Raise the tablet again for *Fire Control*: type X/Y/Z or **PAINT** the block you were aiming
   at, **SET**, then **FIRE** (from any distance; deployed railguns keep their chunk loaded). The
   tablet lowers so you can watch. No beam is visible until the shot.
5. To pack up, right-click the console at the foot of the stairs (the camera zooms into it):
   **RETRACT RAILGUN**, then **CONFIRM RETRACT**. It folds back into its pod (the transformation
   in reverse). Use the pod's panel again, **RETURN TO ORBIT**, and it **launches back into the
   sky** on its thrusters.

The deployed emplacement has real collision: when it finishes deploying it fills its footprint
with invisible collider blocks following a heightfield exported from the Blender model
(`orbital_railgun_collision.json`), so you can climb the stairs and stand on each tier. They are
removed when it packs up.

One railgun per player. Creative players can sneak-punch a railgun to remove it instantly.
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
| `tools/blender/build_models.py` | The railgun (~510 cubes: drop pod with sliding panel, 11-block fortress with stairs, console and ground clamps, 19-block barrel) and the two-handed tablet as box geometry under bone Empties, with Mirror/Array modifiers; Cycles-baked base materials plus a per-face paint pass (panel seams, rivets, bevels, concrete cracks, brushed steel, vent slats, hazard stripes, grime); the glowmask; Blender Actions exported as GeckoLib animations; Cycles renders and the icon. |
| `tools/textures/build_textures.py` | Tileable beam textures (core, plasma, halo, helix, shockwave), animated particle sprites, fire-control and HUD panel art. |
| `tools/sfx/build_sounds.py` | 18 sounds synthesized from oscillators, filtered noise, FM and convolution reverb, encoded to Ogg Vorbis. |

Model contracts the Java code relies on:
- Bone **`turret`** yaws; bone **`barrel`** (pivot 120 px = `PIVOT_Y` 7.5 blocks) pitches and
  points toward −Z, muzzle 312 px = `BARREL_LENGTH` 19.5 blocks from the pivot.
- Bones `pod`, `pod_panel`, `base` (with `leg_*` clamps and `stairs`), `hull`, `vents`, `coils` are animated by keyframes.
- Animations: `pod`, `panel_open` (1.5 s), `deploy` (8 s), `pack` (8 s, deploy reversed), `idle`, `charge`, `fire`.
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
├─ util/RailgunFx          FX-free explosions├─ tablet/TabletScreen       zoom-in tablet UI
│                                            ├─ mixin/                   two-handed tablet render + pose
├─ util/DeploymentScheduler                  ├─ hud/RailgunHud          countdown, flash, camera shake
└─ util/RailgunConfig                        └─ sound/                  charge + re-entry loops
```

State machine: `FALLING → POD → PANEL_OPEN → TRANSFORMING → IDLE ⇄ CHARGING → FIRING → COOLDOWN → IDLE → PACKING → POD_READY → LAUNCHING`, owned by the
server; state, charge and target are `TrackedData`, so no extra sync packets are needed.

### Networking
| Dir | Channel | Payload | Purpose |
|---|---|---|---|
| C2S | `request_deploy` | BlockPos | Tablet CALL DOWN. Server checks item, cooldown, one-per-player, range, sky. |
| C2S | `tablet_status` | – | Tablet poll; server replies with `tablet_info`. |
| C2S | `tablet_target` | BlockPos | SET / PAINT. Owner's railgun only; range & bounds checked. |
| C2S | `tablet_fire` | – | Remote FIRE. IDLE → CHARGING, auto-fires at 100 %. |
| C2S | `tablet_abort` | – | Cancel charge. |
| S2C | `drop_countdown` | BlockPos, ticks | HUD countdown + uplink sound. |
| S2C | `tablet_info` | status snapshot | What the tablet displays. |

Tablet commands resolve the sender's railgun by owner UUID, so nobody can command someone else's. The pod panel / console are plain entity interactions.

### Beam rendering
Two kinds of layer, both additive with no depth write and no culling: an untextured glow layer
(lightning shader) for bloom shells, orbs and arcs, and textured layers (beacon-beam shader,
bilinear) for the plasma. The firing beam is a white-hot core, two counter-scrolling plasma
tubes, a wispy halo, glow shells, three helix ribbons, accelerator rings, muzzle and impact
fireballs, a rising plume and expanding ground shockwaves. See the Javadoc in
`BeamRenderLayers` and `BeamRenderer`.

## License
MIT
