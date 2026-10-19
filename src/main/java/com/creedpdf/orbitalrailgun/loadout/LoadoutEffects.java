package com.creedpdf.orbitalrailgun.loadout;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.loadout.Loadout.Option;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Server-side gameplay and effects of every loadout option. The railgun calls in at fixed
 * points of its state machine; each option only does something when it is installed.
 */
public final class LoadoutEffects {
    private LoadoutEffects() {}

    // Sentry turret mounts in world space relative to the entity (bulwark tops, see the model).
    private static final Vec3d[] SENTRY_MOUNTS = {new Vec3d(4.85, 7.9, -4.85), new Vec3d(-4.85, 7.9, 4.85)};
    public static final double SHIELD_RADIUS = 10.0;
    public static final double AFTERBURN_RADIUS = 7.0;
    public static final int AFTERBURN_TICKS = 240;

    public static Vec3d[] sentryMounts() {
        return SENTRY_MOUNTS;
    }

    // =========================================================================================
    // Charging
    // =========================================================================================

    public static void chargingTick(RailgunEntity rg, ServerWorld w, int t) {
        Loadout l = rg.getLoadout();
        Vec3d target = Vec3d.ofCenter(rg.getTarget());
        if (t == 0) {
            if (l.has(Option.CAPACITOR)) play(w, rg.getPos().add(0, 6, 0), ModSounds.signature(Option.CAPACITOR), 3f, 1f);
            if (l.has(Option.OVERCLOCK)) play(w, rg.getPos().add(0, 6, 0), ModSounds.signature(Option.OVERCLOCK), 3f, 1f);
            if (l.has(Option.GRAVITY)) play(w, target, ModSounds.signature(Option.GRAVITY), 8f, 0.8f);
        }
        if (l.has(Option.GRAVITY)) {
            // Graviton field: drag everything near the target inward.
            for (Entity e : w.getOtherEntities(rg, new Box(target, target).expand(20), Entity::isAlive)) {
                Vec3d d = target.subtract(e.getPos());
                double len = d.length();
                if (len < 1.5 || len > 20) continue;
                e.addVelocity(d.multiply(0.06 / len));
                e.velocityModified = true;
            }
            if (t % 4 == 0) rg.ring(w, ModParticles.GRAVITON_RIPPLE, target, 14 + w.random.nextDouble() * 4, 24, -0.6);
        }
        if (l.has(Option.CAPACITOR) && t % 3 == 0) {
            Vec3d hull = rg.getPos().add(0, 5.6, 0);
            for (int s = -1; s <= 1; s += 2) {
                Vec3d side = new Vec3d(Math.cos(rg.getAimYawRad(1f)) * s * 2.4, 0, -Math.sin(rg.getAimYawRad(1f)) * s * 2.4);
                RailgunFx.burst(w, ModParticles.CAPACITOR_ARC, hull.add(side), 2, 0.4, 0.5, 0.0);
            }
        }
        if (l.has(Option.OVERCLOCK) && t % 2 == 0) {
            Vec3d muzzle = rg.getMuzzlePos(1f);
            Vec3d pivot = rg.getPos().add(0, RailgunConfig.PIVOT_Y, 0);
            for (int i = 0; i < 3; i++) {
                Vec3d p = pivot.lerp(muzzle, w.random.nextDouble());
                double a = t * 0.4 + i * 2.1;
                RailgunFx.launch(w, ModParticles.OVERCLOCK_SPARK, p.add(Math.cos(a) * 1.8, Math.sin(a) * 1.8, 0),
                        new Vec3d(-Math.sin(a) * 0.2, Math.cos(a) * 0.2, 0));
            }
        }
    }

    // =========================================================================================
    // The shot
    // =========================================================================================

    /** Sound for the shot itself (ammunition replaces the standard fire sound) and muzzle layer. */
    public static void fireSounds(RailgunEntity rg, ServerWorld w, Vec3d muzzle, Vec3d impact) {
        Loadout l = rg.getLoadout();
        float vol = 16f * l.fireVolume();
        Option ammo = l.ammo();
        play(w, muzzle, ammo == null ? ModSounds.RAILGUN_FIRE : ModSounds.signature(ammo), vol, 1f);
        if (l.muzzle() != null) play(w, muzzle, ModSounds.signature(l.muzzle()), Math.max(4f, vol), 1f);
        play(w, impact, ammo == null ? ModSounds.RAILGUN_IMPACT : ModSounds.impact(ammo), 16f, 1f);
        if (l.has(Option.OVERCLOCK)) RailgunFx.burst(w, ModParticles.OVERCLOCK_SPARK, muzzle, 60, 1.5, 1.5, 0.9);
    }

    /** Right after the main shot lands. */
    public static void onImpact(RailgunEntity rg, ServerWorld w, Vec3d muzzle, Vec3d impact) {
        Loadout l = rg.getLoadout();
        Option ammo = l.ammo();
        Option mz = l.muzzle();
        Random r = w.random;
        Vec3d dir = impact.subtract(muzzle).normalize();

        // ---- muzzle devices ----
        if (mz == Option.LANCER) {
            for (int i = 0; i < 6; i++) RailgunFx.burst(w, ModParticles.LENS_GLINT, muzzle.add(dir.multiply(i * 1.5)), 3, 0.2, 0.2, 0);
        } else if (mz == Option.TRIPRONG) {
            for (int i = 0; i < 3; i++) {
                double a = i * Math.PI * 2 / 3;
                Vec3d jet = new Vec3d(Math.cos(a), Math.sin(a), 0);
                for (int k = 1; k < 6; k++) RailgunFx.burst(w, ModParticles.PRONG_ARC, muzzle.add(jet.multiply(k * 0.8)), 1, 0.1, 0.1, 0);
            }
        } else if (mz == Option.SHROUD) {
            for (int i = 0; i < 3; i++) rg.ring(w, ModParticles.SHROUD_FLAME, muzzle, 1.5 + i, 30, 0.4 + i * 0.2);
        } else if (mz == Option.SCATTER) {
            for (Vec3d sat : scatterPoints(w, rg.getTarget())) {
                rg.damageAlongBeam(w, muzzle, sat, 0.5f);
                if (l.breaksBlocks() && RailgunConfig.BREAK_BLOCKS) rg.carveLine(w, sat.add(0, 1, 0), sat.subtract(0, 6, 0), 2.0);
                RailgunFx.detonate(w, rg, sat, RailgunConfig.IMPACT_EXPLOSION_POWER * 0.45f * l.blastMul(), l.breaksBlocks() && RailgunConfig.BREAK_BLOCKS);
                RailgunFx.burst(w, ModParticles.SCATTER_FLASH, sat.add(0, 1, 0), 3, 1, 1, 0);
                RailgunFx.burst(w, AmmoFx.smoke(l), sat, 60, 3, 2, 0.12);
            }
        } else if (mz == Option.GRAVITY) {
            for (Entity e : w.getOtherEntities(rg, new Box(impact, impact).expand(20), Entity::isAlive)) {
                Vec3d d = impact.subtract(e.getPos());
                e.addVelocity(d.normalize().multiply(1.4).add(0, 0.4, 0));
                e.velocityModified = true;
            }
            rg.ring(w, ModParticles.SHOCK_DUST, impact, 18, 96, -1.6);
        }

        // ---- ammunition ----
        if (ammo == Option.TUNGSTEN) {
            RailgunFx.burst(w, ModParticles.SPALL, impact.add(0, 1, 0), 260, 2.5, 2.0, 1.6);
        } else if (ammo == Option.THERMITE) {
            ignite(w, impact, 14);
            if (RailgunConfig.BREAK_BLOCKS) magma(w, impact, 7);
            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(impact, impact).expand(14), LivingEntity::isAlive)) {
                e.setOnFireFor(12);
            }
            RailgunFx.burst(w, ModParticles.THERMITE_FLAME, impact.add(0, 1, 0), 300, 8, 3, 0.25);
        } else if (ammo == Option.CRYO) {
            freeze(w, impact, 16);
            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(impact, impact).expand(16), LivingEntity::isAlive)) {
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 240, 3));
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 240, 2));
                e.setFrozenTicks(Math.max(e.getFrozenTicks(), 400));
                e.extinguish();
            }
            RailgunFx.burst(w, ModParticles.FROST, impact.add(0, 2, 0), 400, 9, 3, 0.35);
        } else if (ammo == Option.ION) {
            chainLightning(rg, w, impact);
        } else if (ammo == Option.SINGULARITY) {
            RailgunFx.burst(w, ModParticles.SINGULARITY_MOTE, impact.add(0, 2, 0), 60, 1, 1, 0.05);
        } else if (ammo == Option.SUNBURST) {
            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(impact, impact).expand(40), LivingEntity::isAlive)) {
                Vec3d d = e.getPos().subtract(impact);
                double len = Math.max(1, d.length());
                e.addVelocity(d.multiply(2.5 / len / Math.max(1, len / 10)).add(0, 0.8, 0));
                e.velocityModified = true;
                e.setOnFireFor(8);
            }
        }

        // ---- modules ----
        if (l.has(Option.AFTERBURN)) rg.startAfterburn(impact);
        if (l.has(Option.SEISMIC)) play(w, impact, ModSounds.signature(Option.SEISMIC), 14f, 1f);
    }

    /** Every tick while firing (t >= 1). */
    public static void firingTick(RailgunEntity rg, ServerWorld w, int t, Vec3d tip) {
        Loadout l = rg.getLoadout();
        Option ammo = l.ammo();
        Vec3d impact = Vec3d.ofCenter(rg.getTarget());
        Random r = w.random;

        if (ammo == Option.TUNGSTEN) RailgunFx.burst(w, ModParticles.SPALL, tip, 20, 1.2, 1.2, 1.0);
        if (ammo == Option.THERMITE && t % 2 == 0) {
            RailgunFx.burst(w, ModParticles.THERMITE_FLAME, impact.add(0, 1, 0), 30, 6, 1.5, 0.15);
            if (t % 10 == 0) ignite(w, impact, 10 + t / 5);
        }
        if (ammo == Option.CRYO && t % 2 == 0) {
            RailgunFx.burst(w, ModParticles.FROST, impact.add(0, 1.5, 0), 30, 8, 2, 0.2);
            if (t % 10 == 0) freeze(w, impact, 10 + t / 4);
        }
        if (ammo == Option.ION && t % 5 == 0) {
            for (int i = 0; i < 4; i++) {
                Vec3d a = impact.add((r.nextDouble() - 0.5) * 16, r.nextDouble() * 6, (r.nextDouble() - 0.5) * 16);
                arc(w, impact.add(0, 1, 0), a);
            }
            if (t % 15 == 0) chainLightning(rg, w, impact);
        }
        if (ammo == Option.CLUSTER) clusterTick(rg, w, t, impact);
        if (ammo == Option.SINGULARITY) singularityTick(rg, w, t, impact);
        if (ammo == Option.SUNBURST) sunburstTick(w, t, impact);
        if (l.has(Option.SEISMIC) && t >= 3 && t <= 24) seismicTick(rg, w, t, impact);
    }

    // ---- cluster ----

    private static void clusterTick(RailgunEntity rg, ServerWorld w, int t, Vec3d impact) {
        // 16 bomblets: each falls for 10 ticks (visible trail) and detonates. Deterministic per shot.
        Random r = Random.create(rg.getId() * 7919L + rg.getTarget().asLong());
        for (int i = 0; i < 16; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 5 + r.nextDouble() * 15;
            int due = 12 + i * 2 + r.nextInt(3);
            Vec3d spot = surface(w, impact.add(Math.cos(a) * d, 0, Math.sin(a) * d));
            if (t == due - 10) RailgunFx.launch(w, ModParticles.BOMBLET, spot.add(0, 15, 0), new Vec3d(0, -1.5, 0));
            if (t == due) {
                RailgunFx.detonate(w, rg, spot, 5.5f, RailgunConfig.BREAK_BLOCKS);
                Loadout l = rg.getLoadout();
                RailgunFx.burst(w, AmmoFx.flash(l), spot.add(0, 1, 0), 1, 0.5, 0.5, 0);
                RailgunFx.burst(w, AmmoFx.smoke(l), spot, 30, 2, 1.5, 0.1);
                RailgunFx.burst(w, AmmoFx.spark(l), spot, 20, 1.5, 1, 0.5);
            }
        }
    }

    // ---- singularity ----

    private static void singularityTick(RailgunEntity rg, ServerWorld w, int t, Vec3d impact) {
        Vec3d core = impact.add(0, 3, 0);
        Random r = w.random;
        if (t < 50) {
            for (Entity e : w.getOtherEntities(rg, new Box(core, core).expand(26), Entity::isAlive)) {
                Vec3d d = core.subtract(e.getPos());
                double len = Math.max(1, d.length());
                e.addVelocity(d.multiply(0.12 / len * (1 + t / 25.0)));
                e.velocityModified = true;
            }
            for (int i = 0; i < 10; i++) {
                double a = r.nextDouble() * Math.PI * 2, rad = 6 + r.nextDouble() * 14, y = (r.nextDouble() - 0.3) * 8;
                Vec3d p = core.add(Math.cos(a) * rad, y, Math.sin(a) * rad);
                Vec3d v = core.subtract(p).multiply(0.06).add(-Math.sin(a) * 0.3, 0, Math.cos(a) * 0.3);
                RailgunFx.launch(w, i % 3 == 0 ? ModParticles.DEBRIS : ModParticles.SINGULARITY_MOTE, p, v);
            }
        } else if (t == 50) {
            if (RailgunConfig.BREAK_BLOCKS) carveSphere(w, impact, 9);
            RailgunFx.detonate(w, rg, impact, 12f, RailgunConfig.BREAK_BLOCKS);
            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(core, core).expand(10), LivingEntity::isAlive)) {
                e.damage(rg.beamDamage(w), 60f);
            }
            Loadout l = rg.getLoadout();
            RailgunFx.burst(w, AmmoFx.flash(l), core, 6, 2, 2, 0);
            RailgunFx.burst(w, ModParticles.SINGULARITY_MOTE, core, 300, 2, 2, 1.6);
            RailgunFx.burst(w, AmmoFx.spark(l), core, 160, 2, 2, 1.2);
            rg.ring(w, AmmoFx.smoke(l), impact, 4, 128, 2.2);
        }
    }

    // ---- sunburst ----

    public static void sunburstTick(ServerWorld w, int t, Vec3d impact) {
        if (t > RailgunConfig.FIRE_TICKS) return;
        // Mushroom cloud: a rising stem that spreads into a cap.
        double h = Math.min(45, t * 0.7);
        RailgunFx.burst(w, ModParticles.FALLOUT, impact.add(0, h * 0.6, 0), 4, 2.5, h * 0.25 + 1, 0.02);
        if (t > 20) {
            for (int i = 0; i < 6; i++) {
                double a = w.random.nextDouble() * Math.PI * 2, rad = (t - 20) * 0.35 * w.random.nextDouble();
                RailgunFx.launch(w, ModParticles.FALLOUT, impact.add(Math.cos(a) * rad, h, Math.sin(a) * rad),
                        new Vec3d(Math.cos(a) * 0.15, 0.03, Math.sin(a) * 0.15));
            }
        }
        if (t < 30 && t % 3 == 0) RailgunFx.burst(w, ModParticles.AMMO_SPARK.get(Option.SUNBURST), impact.add(0, h * 0.5, 0), 20, 3, h * 0.3 + 1, 0.1);
        if (t < 30 && t % 6 == 0) RailgunFx.burst(w, ModParticles.AMMO_FLASH.get(Option.SUNBURST), impact.add(0, h, 0), 1, 1, 1, 0);
    }

    // ---- seismic ----

    private static void seismicTick(RailgunEntity rg, ServerWorld w, int t, Vec3d impact) {
        double rad = (t - 3) * 1.35;
        int n = 8 + (int) (rad * 3);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            Vec3d p = surface(w, impact.add(Math.cos(a) * rad, 0, Math.sin(a) * rad));
            RailgunFx.burst(w, ModParticles.SEISMIC_CRACK, p.add(0, 0.1, 0), 1, 0.3, 0.05, 0);
            if (w.random.nextFloat() < 0.15f && RailgunConfig.BREAK_BLOCKS) crack(w, BlockPos.ofFloored(p).down());
        }
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(impact, impact).expand(rad + 1, 4, rad + 1), LivingEntity::isAlive)) {
            double d = Math.sqrt(e.squaredDistanceTo(impact.x, e.getY(), impact.z));
            if (Math.abs(d - rad) < 1.4 && e.isOnGround()) {
                e.addVelocity(0, 0.9, 0);
                e.velocityModified = true;
                e.damage(rg.beamDamage(w), 6f);
            }
        }
    }

    private static void crack(ServerWorld w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        if (s.isIn(BlockTags.DIRT) || s.isOf(Blocks.GRASS_BLOCK)) w.setBlockState(pos, Blocks.COARSE_DIRT.getDefaultState());
        else if (s.isIn(BlockTags.BASE_STONE_OVERWORLD)) w.setBlockState(pos, Blocks.GRAVEL.getDefaultState());
    }

    // =========================================================================================
    // After the shot / burst
    // =========================================================================================

    public static void onShotEnded(RailgunEntity rg, ServerWorld w, boolean rechambering) {
        if (!rechambering) return;
        Vec3d pivot = rg.getPos().add(0, RailgunConfig.PIVOT_Y, 0);
        Vec3d back = rg.getAimDirection(1f).multiply(-1.6);
        play(w, pivot, ModSounds.signature(Option.AUTOLOADER), 4f, 1f);
        for (int i = 0; i < 3; i++) {
            RailgunFx.launch(w, ModParticles.SHELL_CASING, pivot.add(back).add(0, 1, 0),
                    new Vec3d((w.random.nextDouble() - 0.5) * 0.4, 0.5 + w.random.nextDouble() * 0.3, (w.random.nextDouble() - 0.5) * 0.4));
        }
    }

    public static void cooldownTick(RailgunEntity rg, ServerWorld w, int t) {
        if (!rg.getLoadout().has(Option.COOLANT)) return;
        if (t == 0) play(w, rg.getPos().add(0, RailgunConfig.PIVOT_Y, 0), ModSounds.signature(Option.COOLANT), 4f, 1f);
        if (t % 2 == 0 && t < 40) {
            Vec3d muzzle = rg.getMuzzlePos(1f);
            Vec3d pivot = rg.getPos().add(0, RailgunConfig.PIVOT_Y, 0);
            Vec3d dir = rg.getAimDirection(1f);
            Vec3d side = new Vec3d(dir.z, 0, -dir.x).normalize();
            for (int s = -1; s <= 1; s += 2) {
                Vec3d p = pivot.lerp(muzzle, 0.1 + w.random.nextDouble() * 0.5).add(side.multiply(s * 1.4));
                RailgunFx.launch(w, ModParticles.COOLANT_VAPOR, p, side.multiply(s * 0.35).add(0, 0.05, 0));
            }
        }
    }

    // =========================================================================================
    // While deployed: shield, sentries, afterburn pool
    // =========================================================================================

    public static void deployedTick(RailgunEntity rg, ServerWorld w, int age) {
        Loadout l = rg.getLoadout();
        if (l.has(Option.SHIELD)) shieldTick(rg, w, age);
        if (l.has(Option.SENTRY) && age % 12 == 0) sentryTick(rg, w);
    }

    private static void shieldTick(RailgunEntity rg, ServerWorld w, int age) {
        Vec3d c = rg.getPos().add(0, 2, 0);
        Box box = new Box(c, c).expand(SHIELD_RADIUS);
        UUID owner = rg.getOwnerUuid();
        for (ProjectileEntity p : w.getEntitiesByClass(ProjectileEntity.class, box, Entity::isAlive)) {
            if (p.getPos().distanceTo(c) > SHIELD_RADIUS) continue;
            Entity shooter = p.getOwner();
            if (shooter != null && owner != null && owner.equals(shooter.getUuid())) continue;
            RailgunFx.burst(w, ModParticles.SHIELD_SPARK, p.getPos(), 6, 0.3, 0.3, 0.05);
            play(w, p.getPos(), ModSounds.MODULE_SHIELD_BLOCK, 2f, 0.9f + w.random.nextFloat() * 0.2f);
            p.discard();
        }
        if (age % 5 == 0) {
            for (HostileEntity m : w.getEntitiesByClass(HostileEntity.class, box, Entity::isAlive)) {
                Vec3d d = m.getPos().subtract(c);
                double len = d.length();
                if (len > SHIELD_RADIUS) continue;
                Vec3d push = new Vec3d(d.x, 0, d.z).normalize().multiply(0.9);
                m.addVelocity(push.x, 0.3, push.z);
                m.velocityModified = true;
                m.damage(rg.beamDamage(w), 2f);
                RailgunFx.burst(w, ModParticles.SHIELD_SPARK, c.add(d.normalize().multiply(SHIELD_RADIUS)), 8, 0.6, 0.6, 0.05);
                play(w, m.getPos(), ModSounds.MODULE_SHIELD_BLOCK, 2f, 0.7f);
            }
        }
    }

    private static void sentryTick(RailgunEntity rg, ServerWorld w) {
        for (Vec3d mount : SENTRY_MOUNTS) {
            Vec3d gun = rg.getPos().add(mount);
            HostileEntity target = w.getEntitiesByClass(HostileEntity.class, new Box(gun, gun).expand(32), Entity::isAlive).stream()
                    .filter(m -> canSee(w, gun, m.getEyePos(), rg))
                    .min(Comparator.comparingDouble(m -> m.squaredDistanceTo(gun))).orElse(null);
            if (target == null) continue;
            Vec3d aim = target.getBoundingBox().getCenter();
            Vec3d d = aim.subtract(gun);
            double len = d.length();
            Vec3d step = d.multiply(1 / len);
            for (double k = 0.8; k < len; k += 0.7) RailgunFx.burst(w, ModParticles.SENTRY_BOLT, gun.add(step.multiply(k)), 1, 0, 0, 0);
            RailgunFx.burst(w, ModParticles.SENTRY_FLASH, gun.add(step.multiply(0.6)), 1, 0, 0, 0);
            target.damage(rg.beamDamage(w), 7f);
            play(w, gun, ModSounds.signature(Option.SENTRY), 2.5f, 0.9f + w.random.nextFloat() * 0.2f);
        }
    }

    private static boolean canSee(ServerWorld w, Vec3d from, Vec3d to, Entity rg) {
        return w.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, rg))
                .getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    public static void afterburnTick(RailgunEntity rg, ServerWorld w, Vec3d at, int left) {
        Random r = w.random;
        if (left % 2 == 0) {
            for (int i = 0; i < 6; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * AFTERBURN_RADIUS;
                Vec3d p = surface(w, at.add(Math.cos(a) * d, 0, Math.sin(a) * d));
                RailgunFx.launch(w, ModParticles.PLASMA_POOL, p.add(0, 0.2, 0), new Vec3d(0, 0.03 + r.nextDouble() * 0.05, 0));
            }
        }
        if (left % 10 == 0) {
            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(at, at).expand(AFTERBURN_RADIUS, 3, AFTERBURN_RADIUS), LivingEntity::isAlive)) {
                e.damage(rg.beamDamage(w), 4f);
                e.setOnFireFor(3);
            }
        }
        if (left % 50 == 0) play(w, at, ModSounds.signature(Option.AFTERBURN), 6f, 0.9f + r.nextFloat() * 0.2f);
    }

    // =========================================================================================
    // Launch (vector thrusters)
    // =========================================================================================

    public static double launchAccelMul(RailgunEntity rg) {
        return rg.getLoadout().has(Option.THRUSTER) ? 2.5 : 1.0;
    }

    public static void launchStart(RailgunEntity rg, ServerWorld w) {
        if (!rg.getLoadout().has(Option.THRUSTER)) return;
        play(w, rg.getPos(), ModSounds.signature(Option.THRUSTER), 10f, 1f);
        rg.ring(w, ModParticles.THRUSTER_FLAME, rg.getPos().add(0, 0.3, 0), 1.5, 48, 1.2);
        rg.ring(w, ModParticles.THRUSTER_SHOCK, rg.getPos().add(0, 0.3, 0), 2, 64, 1.5);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, rg.getBoundingBox().expand(8, 2, 8), LivingEntity::isAlive)) {
            Vec3d d = e.getPos().subtract(rg.getPos());
            e.addVelocity(new Vec3d(d.x, 0, d.z).normalize().multiply(1.2).add(0, 0.5, 0));
            e.velocityModified = true;
        }
    }

    // =========================================================================================
    // Terrain helpers
    // =========================================================================================

    /** The scatter choke's four satellite impact points (deterministic, so the client draws the same beams). */
    public static List<Vec3d> scatterPoints(World w, BlockPos target) {
        Vec3d impact = Vec3d.ofCenter(target);
        double base = Math.floorMod(target.asLong() * 2654435761L, 1000L) / 1000.0 * Math.PI;
        List<Vec3d> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double a = base + i * Math.PI / 2;
            out.add(surface(w, impact.add(Math.cos(a) * 11, 0, Math.sin(a) * 11)));
        }
        return out;
    }

    public static Vec3d surface(World w, Vec3d p) {
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(p.x), MathHelper.floor(p.z));
        if (Math.abs(y - p.y) > 24) y = (int) Math.floor(p.y);
        return new Vec3d(p.x, y, p.z);
    }

    public static void ignite(ServerWorld w, Vec3d c, int radius) {
        if (!RailgunConfig.BREAK_BLOCKS) return;
        Random r = w.random;
        for (int i = 0; i < radius * radius; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * radius;
            BlockPos top = BlockPos.ofFloored(surface(w, c.add(Math.cos(a) * d, 0, Math.sin(a) * d)));
            if (w.getBlockState(top).isAir() && w.getBlockState(top.down()).isSolidBlock(w, top.down())) {
                w.setBlockState(top, net.minecraft.block.AbstractFireBlock.getState(w, top));
            }
        }
    }

    public static void magma(ServerWorld w, Vec3d c, int radius) {
        BlockPos center = BlockPos.ofFloored(c);
        for (BlockPos p : BlockPos.iterate(center.add(-radius, -radius, -radius), center.add(radius, 2, radius))) {
            if (p.getSquaredDistance(center) > radius * radius) continue;
            BlockState s = w.getBlockState(p);
            if (s.isAir() || s.getHardness(w, p) < 0) continue;
            if (!w.getBlockState(p.up()).isAir() && w.random.nextFloat() > 0.3f) continue;
            if (s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.DIRT) || s.isOf(Blocks.SAND) || s.isOf(Blocks.GRAVEL)
                    || s.isOf(Blocks.COBBLESTONE)) {
                w.setBlockState(p, w.random.nextFloat() < 0.2f ? Blocks.LAVA.getDefaultState() : Blocks.MAGMA_BLOCK.getDefaultState());
            }
        }
    }

    public static void freeze(ServerWorld w, Vec3d c, int radius) {
        BlockPos center = BlockPos.ofFloored(c);
        Random r = w.random;
        for (BlockPos p : BlockPos.iterate(center.add(-radius, -4, -radius), center.add(radius, 6, radius))) {
            double d2 = p.getSquaredDistance(center);
            if (d2 > radius * radius) continue;
            BlockState s = w.getBlockState(p);
            if (s.getFluidState().isOf(Fluids.WATER) && s.getFluidState().isStill()) {
                w.setBlockState(p, d2 < 25 ? Blocks.BLUE_ICE.getDefaultState() : Blocks.PACKED_ICE.getDefaultState());
            } else if (s.getFluidState().isOf(Fluids.LAVA)) {
                w.setBlockState(p, Blocks.OBSIDIAN.getDefaultState());
            } else if (s.isOf(Blocks.FIRE)) {
                w.setBlockState(p, Blocks.AIR.getDefaultState());
            } else if (s.isAir() && w.getBlockState(p.down()).isSolidBlock(w, p.down()) && r.nextFloat() < 0.8f) {
                w.setBlockState(p, d2 < 36 && r.nextFloat() < 0.4f ? Blocks.POWDER_SNOW.getDefaultState()
                        : Blocks.SNOW.getDefaultState());
            } else if (d2 < 49 && (s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.DIRT)) && w.getBlockState(p.up()).isAir()
                    && r.nextFloat() < 0.5f) {
                w.setBlockState(p, Blocks.PACKED_ICE.getDefaultState());
            }
        }
    }

    public static void carveSphere(ServerWorld w, Vec3d c, int radius) {
        BlockPos center = BlockPos.ofFloored(c);
        for (BlockPos p : BlockPos.iterate(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))) {
            if (p.getSquaredDistance(center) > radius * radius) continue;
            BlockState s = w.getBlockState(p);
            if (s.isAir() || s.getHardness(w, p) < 0) continue;
            w.setBlockState(p, Blocks.AIR.getDefaultState(), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.SKIP_DROPS);
        }
    }

    // =========================================================================================
    // Ion chain lightning
    // =========================================================================================

    private static void chainLightning(RailgunEntity rg, ServerWorld w, Vec3d impact) {
        List<LivingEntity> pool = new ArrayList<>(w.getEntitiesByClass(LivingEntity.class, new Box(impact, impact).expand(28),
                e -> e.isAlive() && !(e instanceof PlayerEntity p && p.getUuid().equals(rg.getOwnerUuid()))));
        Vec3d from = impact.add(0, 1, 0);
        float dmg = 30f * rg.getLoadout().damageMul();
        for (int hop = 0; hop < 8 && !pool.isEmpty(); hop++) {
            final Vec3d f = from;
            LivingEntity next = pool.stream().min(Comparator.comparingDouble(e -> e.getPos().squaredDistanceTo(f))).get();
            pool.remove(next);
            Vec3d to = next.getBoundingBox().getCenter();
            arc(w, from, to);
            next.damage(rg.beamDamage(w), dmg);
            RailgunFx.burst(w, ModParticles.ION_ARC, to, 6, 0.6, 0.6, 0.1);
            from = to;
        }
    }

    public static void arc(ServerWorld w, Vec3d a, Vec3d b) {
        Vec3d d = b.subtract(a);
        double len = d.length();
        Random r = w.random;
        for (double k = 0; k < len; k += 0.9) {
            Vec3d p = a.add(d.multiply(k / len)).add((r.nextDouble() - 0.5) * 0.6, (r.nextDouble() - 0.5) * 0.6, (r.nextDouble() - 0.5) * 0.6);
            RailgunFx.burst(w, ModParticles.ION_ARC, p, 1, 0, 0, 0);
        }
    }

    private static void play(ServerWorld w, Vec3d p, SoundEvent s, float vol, float pitch) {
        if (s != null) w.playSound(null, p.x, p.y, p.z, s, SoundCategory.BLOCKS, vol, pitch);
    }
}
