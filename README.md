# Deluxs Armory (Fabric 1.20.1)

*(mod id `orbital_railgun`, kept so existing worlds and items carry over)*

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
| Immersive Portals | 3.3.9 for 1.20.1 — bundled inside the jar since 4.0.1 (with Cloth Config); nothing extra to install |

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
ammunition type and up to four support modules (23 options), then **CONFIRM LOADOUT**.
When the emplacement deploys it enters **ARMING**: every attachment is fired down from orbital
storage on its own. Each part streaks in from ~150 blocks up, lights its retro thrusters,
vectors into a hover point beside its hardpoint (turning itself vertical or horizontal to match),
extends four docking arms, slides in, clamps on and folds the arms away. When the railgun packs
up, the parts undock and boost back to orbit.

| Muzzle | Ammunition | Modules |
|---|---|---|
| Lancer Lens Array, Tri-Prong Compensator, Plasma Shroud, Scatter Choke, Graviton Coil, Ghost Baffle | Tungsten Penetrator, Thermite Incendiary, Cryo-Stasis Slug, Ion Disruptor, Cluster Payload, Singularity Charge, Sunburst Warhead | Capacitor Bank, Cryo Coolant Loop, Overclock Coils, Targeting Array, Aegis Shield Emitters, Sentry Turrets, Vector Thrusters, Seismic Anchors, Autoloader Drum, Plasma Afterburn |

Every option has its own Blender-built model, beam/particle effects and synthesized sounds: each
ammunition has its own flash, smoke and spark sprites, and every muzzle device and module has its
own particles, so no two options share an effect.

## Range
Targets up to **2000 blocks** away. Every shot is broadcast to the whole dimension and the beam
is drawn in its own world-render pass with fog lifted and the far plane pushed out while it is
live, so you see it from anywhere, even when the railgun itself is far beyond render distance.

## Textures
The railgun atlas is painted at 3 texels per model pixel in a clean, smooth style (flat panels,
soft top light, crisp bevels, thin panel grooves, decals and glowing light strips) with gutters
around every face, and is sampled with linear filtering (`railgun.png.mcmeta`), so it reads as
smooth surfaces rather than pixel art.

## Rift
Press **RIFT** on the tablet while the railgun is idle. A crown of six emitter petals blooms
around the muzzle and the rail swings straight up while it charges: every red part of ARES-9,
paint and lights, slowly turns dark purple, the colour running down from the tip of the rail to
the ground. Then a thick violet beam punches **160 blocks** into the sky, holds for one second and cuts out,
leaving the sky ripped open.

The rift is a **real see-through portal into space** (Immersive Portals), shaped like an actual
tear: a long, jagged, bent gash about **50 blocks across** with pointed ends, its rim spiked and
crawling with arcs. Look up through it and you see actual space: stars, the Milky Way, the sun, the planets. Stand on
the railgun while the rift is open and an updraft carries you up and straight through, **no loading screen**: you
are simply floating in space, high above Earth, and looking back down through the hole you can
see your world and the railgun below.

The rift stays open on its own for **30 seconds** after the beam stops. Warnings go out to
everyone in space at 20, 10 and 5 seconds. When it closes the hole slams shut and anyone still up
there is **stranded in space**. The only way home is to fly back down into Earth's atmosphere.

## Space
A realistic solar system in its own dimension: black sky, sharp stars, the Milky Way, a white
sun, and every body as a lit 3D sphere at its true direction and size. Earth fills the sky below
you, with oceans, continents, polar ice, moving clouds and city lights on the night side. The
Moon, Mars, Venus, Jupiter (with Europa) and ringed Saturn are all out there to fly to.
* Entering space puts **DEEP SPACE** across the top of your screen with a line drawing out under
  it; landing on a planet shows its name and gravity the same way.
* Zero gravity. Everything keeps its momentum.
* Vacuum. Without a complete EVA suit you freeze and suffocate in seconds.
* Fly into a planet's atmosphere and you come down to its surface with no loading screen, the
  visor wrapped in re-entry fire. Fly into Earth's and you come out high above your own world,
  above the spot you entered over.
* Jupiter and Saturn have no surface: their atmospheres hurl you back out.

## ARES EVA suit
Four pieces (helmet, suit + jetpack, leggings, boots), a 3D armour model built in Blender. You
need **all four** to survive anywhere off Earth. The boots absorb any fall.
* **Jetpack in space:** full 3D flight with inertia. W/S along your view, A/D strafe, Space up,
  Shift down, Sprint for the main engines (up to 160 m/s). Let go and the stabilisers slowly
  stop you; Space + Shift brakes hard.
* **Jetpack in gravity:** hold Space in the air to fly, Sprint to go faster, Shift to hover down.
  Earth's thrusters can't climb past about Y 450: the rift is the only way to space.
* Twin 3D thruster flames, blue, under the nozzles: they grow with thrust and show shock
  diamonds when you boost.
* Visor HUD: seal status, speed, nearest body and distance, gravity.

Recipes: helmet (iron, tinted glass, netherite, redstone lamp); suit (iron, fire charges,
netherite, elytra); leggings (iron, netherite, white wool); boots (iron, netherite, slime blocks).

## Planets
| | Gravity | Surface |
|---|---|---|
| The Moon | 0.17 g | grey regolith, bright highlands, dark basalt maria, deep basins. Earth hangs huge in a black sky. |
| Mars | 0.38 g | rust-red dunes and plains, terracotta canyon walls, basalt fields, a butterscotch sky with a small, blue-haloed sun. |
| Venus | 0.90 g | black basalt, volcanic peaks, sulphur crusts and seas of lava under an endless yellow overcast. |
| Europa | 0.13 g | a shell of snow and ice cut by rust-brown fractures, with Jupiter filling a third of the sky. |

Where you stand on a planet decides where the sun and the other bodies are, so walking far
enough brings night. Climb above Y 420 with the jetpack and you are back in space above that spot.

## ARES-0
Switch the tablet's drop page to **ARES-0** and call it down like ARES-9. A planet killer: a
black and molten-orange war machine: clawed pylons, taloned legs, horned towers with slit eyes,
and a **31-block tri-rail accelerator** bristling with serrated fins, toothed capacitor rings and
a three-fanged halo. Every light, arc and the beam itself burn orange. Use it to open its console,
pick a planet that is above the horizon, and fire. It charges for nine seconds, then an orange
beam climbs out of your world, through the atmosphere and off into space all the way to the
planet:
1. the beam strikes, a glowing wound opens and fractures race across the crust until the whole
   planet glows from within, lava jets erupting from the cracks;
2. the core collapses, the planet shrinks and dims;
3. it detonates: a blinding flash with an anamorphic lens streak, ~420 tumbling fragments with
   molten faces and glowing trails, a roiling fireball of hundreds of cloud sprites cooling from
   blue-white through yellow and orange to red and then into dark, sun-lit smoke, thousands of
   velocity-stretched ejecta streaks, plasma shells and shockwave rings;
4. what is left is a smouldering cinder inside a debris disk that flattens and shears into rings
   over time. Players standing on it are thrown clear into space a moment before it goes.

The kill is announced across the top of everyone's screen: **GRAVITATIONAL BINDING FAILED** with
*CELLULAR DISRUPTION IMMINENT* in smaller type beneath it. A few seconds later **pieces of the planet start falling to
Earth**: for about five minutes burning fragments streak across the whole sky, and around every
player in the overworld real ones come screaming down, blast craters and leave heaps of that
planet's rock (Martian red rock, lunar basalt, Venusian blackstone and magma, Europan ice,
gas-giant core metal).

The Moon disappears from the night sky once it is destroyed. Earth can't be targeted.

## Scan mode
Press **SCAN** on the tablet while the railgun is idle. A sensor mast telescopes up out of the
pod on the turret's flank and its head spins up, then fires a holographic pulse: a glowing wall
races out across the terrain (48 block radius). Every ore it passes lights up as a colour-coded
marker you can see through rock (diamond cyan, emerald green, gold yellow, ancient debris
bronze, redstone red, lapis blue, iron, copper, coal, quartz), rare finds ping as they appear,
and hostile mobs within 64 blocks get red target brackets. Markers hold for about 20 seconds.
The array recharges for 20 seconds between scans.

## Clamp rockets
Once deployed, each of the four ground clamps needs solid blocks to bite into. Any clamp left
hanging over air (a cliff edge, a pit, a bridge) lights its vectoring rockets instead: a plume
under the foot plus short gimballed side bursts that keep the emplacement centred and steady.

## Landing rules
Before a pod is released, an orbital site survey checks the landing zone (a disc of 8 blocks,
12 high). Ordinary blocks are fine: the pod smashes through trees and roofs and clears a flat
platform on touchdown. The drop is refused if the zone holds unbreakable blocks, the ground falls
away under too much of the footprint, the landing block is liquid, another railgun is too close,
or the site is buried too deep. The tablet shows the survey live before you call it down.

## ARES Starship
The ARES Kestrel: a smooth, realistic gunship about 24 blocks long, modelled in Blender from lofted
cross-sections (no cubes): a gunmetal fuselage running into a long drooping nose under a framed
bubble canopy, a dorsal engine boom with twin main engines and canted tail fins, short swept
tiltrotor wings carrying two **VTOL engine pods** that tilt nozzle-down to hover, canards, a chin
sensor, red pin-striping, lit portholes and running lights, all burning with **3D red flames**.
Tripod landing gear folds into the belly and the rear hatch drops into a ramp. The canopy and the
cabin window are real glass: you can see the two-seat cockpit from outside, and out through it
from the cabin.

* **Commission it:** craft the *ARES Starship Command Key* (tinted glass, iron blocks, beacon,
  netherite, elytra) and use it. The ship is now yours and waits in orbit.
* **Call it:** on the uplink tablet open `~/hangar/starship`, aim at the ground first, then
  *CALL TO LANDING ZONE*. It comes in fast, converts to VTOL and stops in a hover; a holographic
  scan sweeps the zone and, if the ground is uneven, a wide laser burns it level row by row
  (cutting trees and bumps, filling holes); then it lowers itself on its thrusters, the gear
  takes the weight and the ramp drops.
* **Walk in:** the cabin is real (collision walls, floor and roof around it): tread-plate deck,
  ribbed walls, fold-down jump seats with harnesses, EVA lockers by the door, wall screens,
  ceiling light panels and a real **Flight Console** block at the front, under the window into
  the cockpit.
* **Fly:** use the console, pick a destination (Moon, Mars, Venus, Europa, or Earth), confirm.
  The flight is a film with letterbox bars and a terminal caption:
  1. VTOL lift-off from a low ground camera, nacelles swinging aft in a close tracking shot;
  2. a **fly-by**: a fixed camera beside the climb-out, the ship screaming past it;
  3. a chase into the sky, then the **jump**: the drive charges, the stars stretch into streaks,
     a sharp sci-fi *zip*, a flash, cut;
  4. deep space: out of the jump, a slow orbit around the ship, a front shot, a low side pass,
     and the next jump toward the destination;
  5. arrival: a dive into the sky, a second fly-by, a wide ground shot of the VTOL conversion,
     the scan and laser, and the touchdown on the tripod gear.
  Flight is a smooth physical model (integrated thrust and climb profiles, curved approach,
  banking into turns, flare, suspension settling on contact). The cabin is pressurised and you
  can't step out mid-flight.
* **Send it back:** *RETURN TO ORBIT* on the console or in the hangar: anyone aboard is put down
  beside it in a flash, the hatch closes and it lifts off and flies away.

## Uplink terminal
The tablet (and the railgun's own field terminal and the ARES-0 console) is a classified command
terminal: a file tree on the left, the open file on the right, small bracketed text commands
instead of big buttons, and a two-step confirm on anything destructive.

```
~/ares-9/        status  fire_control  scan  rift  recall
~/ares-0/        targets  recall
~/orbital_drop/  ares-9.pod  ares-0.pod
```

Everything works **remotely, at any distance**: lock coordinates and fire ARES-9, scan, tear a
rift, **recall ARES-9** (it packs itself up and launches back to orbit), and pick a planet and
**fire ARES-0** or recall it. Arrow keys move through the tree.

## HANGEKI // Reversal Edge (Handhelds)
A long black-and-silver nodachi with a white-hot edge and crimson reactor glow, whose one trick is to **parry anything**.

* **Projectiles:** arrows, tridents, ghast and blaze fireballs, wither skulls, crossbow firework rockets, llama spit,
  shulker rounds, a **KRL missile** diving on you, a **railgun beam** about to fire through you, a **warden's sonic
  boom**. While you hold the sword, the threat panel in the bottom-left corner (threat, range, class, response) tracks
  the nearest one as it closes in. About three blocks out **time slows hard** - the whole world - and it hangs in the
  air, creeping in. Press **Parry (R)** or right-click before the ring round the crosshair runs out: the camera swings
  round as your whole body drops into a low lunging parry stance, sword high across your face, and a small 3D samurai
  ward (a laced lamellar shield with a turning three-tomoe crest) snaps up in front of the blade. Then time **surges**
  back, the hit lands on the ward with a deep heavy impact, the screen cuts through real **impact frames** (the actual
  scene burned to grainy monochrome, silhouettes and line art, black and crimson cards, speed lines tearing out of the
  hit, a wireframe globe) and it flies straight back where it came from at **twice the force**. Miss the window and it
  lands.
* **Melee:** with nothing incoming, Parry raises a short guard. A blow that lands in it (a mob, a player, an
  explosion) does nothing to you and is **countered**: twice the damage back, the attacker thrown away and staggered,
  a brief hit-stop and a short burst of impact frames. An arrow caught in a guard is batted straight back.
* Netherite-class sword (10 attack damage), fireproof, enchantable. Recipe: an echo shard at the tip over two
  netherite ingots, a blaze rod grip.
* Slow motion really slows the server's tick, so in multiplayer everyone sees time slow while a parry plays out.

Creative tabs: **Deluxs Armory: Railguns**, **Deluxs Armory: Military** and **Deluxs Armory: Handhelds**.

## Asset pipeline
| Tool | Produces |
|---|---|
| `tools/blender/build_models.py` | The railgun (~380 cubes: an octagonal drop pod whose armour quarters, telescoping struts, decks, clamps, turret and telescoping 20-block barrel all fold out of it) and the two-handed tablet as box geometry under bone Empties, with Mirror/Array modifiers; Cycles-baked base materials plus a per-face paint pass (panel seams, rivets, bevels, concrete cracks, brushed steel, vent slats, hazard stripes, grime); the glowmask; Blender Actions exported as GeckoLib animations; Cycles renders and the icon. |
| `tools/blender/build_ship.py` | The ARES Kestrel starship: lofted superellipse fuselage and engine boom, airfoil wings, lathed engine pods and nozzles, cabin and cockpit interiors; baked albedo + glow atlases; exported as a `krl_mesh` with movable parts (pods, gear, ramp, glass). |
| `tools/blender/build_hangeki.py` | HANGEKI: the curved sci-fi katana (lofted blade with polished bevels and a hamon, plasma edge, amber reactor fuller, hex tsuba, wrapped grip, floating parry ring), same pipeline. |
| `tools/sfx/hangeki_sounds.py` | 8 HANGEKI sounds synthesised from scratch (threat pip, time slowing, stance draw, the impact, the reflection, time resuming, counter, guard). |
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
| S2C | `beam_shot` | entity id, muzzle, target, loadout | Sent to every player in the dimension on each shot: draws the beam at any distance. |

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
