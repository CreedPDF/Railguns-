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
3. Walk up to the pod and right-click the **tablet mounted in its hatch**: the camera flies up to
   it and its screen becomes the UI. Press **DEPLOY RAILGUN**. The tablet slides into the core and
   the pod unfolds over 12 seconds, one clearly eased phase at a time: locking bolts pop in
   sequence, the four armour quarters unlatch and glide out along the diagonals on telescoping
   hydraulic struts (which keep them tied to the core as corner bulwarks), deck plates slide out
   from under the pod, the ground clamps slide out, wind up and **slam** down, the turret rises out
   of the core, the radar mast and antenna telescope up, the rail swings level, the barrel
   telescopes out segment by segment and the coil rings snap on. Every part was stowed inside the
   pod; the front stays open so the tablet is reachable from the ground.
4. Raise the tablet again for *Fire Control*: type X/Y/Z or **PAINT** the block you were aiming
   at, **SET**, then **FIRE** (from any distance; deployed railguns keep their chunk loaded). The
   tablet lowers so you can watch. No beam is visible until the shot.
5. To pack up, use the same tablet in the core (it slides back out at the end of the deployment):
   **RETRACT RAILGUN**, then **CONFIRM RETRACT**. Everything folds back into the pod (the
   deployment in reverse). Use the tablet again, **RETURN TO ORBIT**, and it **launches back into
   the sky** on its thrusters.

The deployed emplacement has real collision: when it finishes deploying it fills its footprint
with invisible collider blocks following a heightfield exported from the Blender model
(`orbital_railgun_collision.json`), so you can climb onto the decks and walk around the emplacement. They are
removed when it packs up.

One railgun per player. Creative players can sneak-punch a railgun to remove it instantly.
Tuning (ranges, timings, blast power, block damage) lives in `RailgunConfig.java`.

### Penetration
The shot cuts straight through anything between the muzzle and the target, then keeps boring on
along the beam: a tunnel 8 blocks wide up to 96 blocks deep, with blasts at the bore tip, on top of
the main blast and two rings of secondary blasts around the crater. Only unbreakable blocks stop
it. All of it is tunable in `RailgunConfig` (`BORE_*`, `PATH_RADIUS`, `*_POWER`, `BREAK_BLOCKS`).

## Loadouts and orbital storage
Before deploying, open the pod's console and switch to **LOADOUT**. Pick one muzzle device, one
ammunition type and up to four support modules (23 options), then **TRANSMIT TO ORBITAL STORAGE**:
a supply capsule re-enters beside the pod, lands on its retro thrusters, opens and streams the
attachments into the pod, then returns to orbit. Attachments unfold onto the emplacement during
deployment and fold away first when packing.

| Muzzle | Ammunition | Modules |
|---|---|---|
| Lancer Lens Array, Tri-Prong Compensator, Plasma Shroud, Scatter Choke, Graviton Coil, Ghost Baffle | Tungsten Penetrator, Thermite Incendiary, Cryo-Stasis Slug, Ion Disruptor, Cluster Payload, Singularity Charge, Sunburst Warhead | Capacitor Bank, Cryo Coolant Loop, Overclock Coils, Targeting Array, Aegis Shield Emitters, Sentry Turrets, Vector Thrusters, Seismic Anchors, Autoloader Drum, Plasma Afterburn |

Every option has its own Blender-built model, beam/particle effects and synthesized sounds.

## Landing rules
Before a pod is released, an orbital site survey checks the landing zone (a disc of 8 blocks,
12 high). Ordinary blocks are fine: the pod smashes through trees and roofs and clears a flat
platform on touchdown. The drop is refused if the zone holds unbreakable blocks, the ground falls
away under too much of the footprint, the landing block is liquid, another railgun is too close,
or the site is buried too deep. The tablet shows the survey live before you call it down.

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
| `tools/blender/build_models.py` | The railgun (~380 cubes: an octagonal drop pod whose armour quarters, telescoping struts, decks, clamps, turret and telescoping 20-block barrel all fold out of it) and the two-handed tablet as box geometry under bone Empties, with Mirror/Array modifiers; Cycles-baked base materials plus a per-face paint pass (panel seams, rivets, bevels, concrete cracks, brushed steel, vent slats, hazard stripes, grime); the glowmask; Blender Actions exported as GeckoLib animations; Cycles renders and the icon. |
| `tools/textures/build_textures.py` | Tileable beam textures (core, plasma, halo, helix, shockwave), animated particle sprites, fire-control and HUD panel art. |
| `tools/sfx/build_sounds.py` | 18 sounds synthesized from scratch: layered sub kicks, modal (struck-metal) synthesis, detuned unison oscillators, FM, electric crackle, servo/hydraulic/latch machine parts timed to the animation, multiband saturation, a soft limiter and a synthetic room; encoded to Ogg Vorbis. |

Model contracts the Java code relies on:
- Bone **`turret`** yaws; bone **`barrel`** (pivot 118 px = `PIVOT_Y` 7.375 blocks) pitches and
  points toward −Z, muzzle 316 px = `BARREL_LENGTH` 19.75 blocks from the pivot.
- The rest pose is the deployed emplacement; the pod is a pose where everything is stowed:
  `shell_*` quarters (with `clamp_*`, `bolt_*`, `beacon_*`), `strut_*_1..3`, `deck_n/e/w`, `turret`
  (`hull`, `radar` → `dish`, `antenna`), `barrel` (`vents`, `coils_a`, `seg1` → `coils_b`,
  `seg2` → `coils_c`) and `pod_panel` (the tablet in the core's hatch).
- Animations: `pod`, `panel_open` (1.5 s), `deploy` (12 s), `pack` (12 s, deploy reversed with mirrored easings), `idle`, `charge`, `fire`. Keyframes carry easings (`easeInOutCubic`, `easeOutBack`, `easeInQuart` for the clamp slam...). The client plays them itself (`RailgunAnimator`) from the synced state and how long the railgun has been in it, rather than through GeckoLib controllers, so the animation always matches the server timeline.
- Boxes only, optionally rotated about Y or Z around their own pivot (octagonal hull, barrel and diagonal bulwarks).
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

Tablet commands resolve the sender's railgun by owner UUID, so nobody can command someone else's. The tablet panel is a plain entity interaction.

### Beam rendering
Two kinds of layer, both additive with no depth write and no culling: an untextured glow layer
(lightning shader) for bloom shells, orbs and arcs, and textured layers (beacon-beam shader,
bilinear) for the plasma. The firing beam is a white-hot core, two counter-scrolling plasma
tubes, a wispy halo, glow shells, three helix ribbons, accelerator rings, muzzle and impact
fireballs, a rising plume and expanding ground shockwaves. See the Javadoc in
`BeamRenderLayers` and `BeamRenderer`.

## License
MIT
