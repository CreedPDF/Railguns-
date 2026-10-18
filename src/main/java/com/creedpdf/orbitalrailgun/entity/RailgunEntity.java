package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The deployable orbital railgun emplacement (about 11 blocks across with a 19-block barrel).
 *
 * <h2>Why an Entity and not a GeoBlockEntity?</h2>
 * A block entity is anchored to one block, can't fall from orbit, and a footprint this size would
 * need a multiblock of placeholder blocks. A plain {@link Entity} (no health/AI overhead) can
 * move freely during the drop, has a hitbox for right-click picking, persists itself to NBT,
 * and GeckoLib renders it with {@code GeoEntityRenderer}.
 *
 * <h2>Authority & sync</h2>
 * The server owns the state machine. State, charge and target are {@link TrackedData}, so the
 * vanilla entity tracker syncs them to every client in range. Clients derive cosmetics only.
 *
 * <h2>Effects</h2>
 * Every particle is one of the mod's own {@link ModParticles}, every sound one of
 * {@link ModSounds}, and explosions go through {@link RailgunFx#detonate}, which skips vanilla's
 * explosion particles and sound.
 */
public class RailgunEntity extends Entity implements GeoEntity {

    // ---- Synced data -----------------------------------------------------------------------
    private static final TrackedData<Integer> STATE =
            DataTracker.registerData(RailgunEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> CHARGE =
            DataTracker.registerData(RailgunEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<BlockPos> TARGET =
            DataTracker.registerData(RailgunEntity.class, TrackedDataHandlerRegistry.BLOCK_POS);
    private static final TrackedData<Boolean> HAS_TARGET =
            DataTracker.registerData(RailgunEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // ---- GeckoLib animations (names must match railgun.animation.json) ----------------------
    private static final RawAnimation ANIM_FALLING = RawAnimation.begin().thenLoop("animation.railgun.falling");
    private static final RawAnimation ANIM_DEPLOY = RawAnimation.begin().thenPlayAndHold("animation.railgun.deploy");
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("animation.railgun.idle");
    private static final RawAnimation ANIM_CHARGE = RawAnimation.begin().thenLoop("animation.railgun.charge");
    private static final RawAnimation ANIM_FIRE = RawAnimation.begin()
            .thenPlay("animation.railgun.fire")
            .thenLoop("animation.railgun.idle");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // ---- Local (non-synced) state -----------------------------------------------------------
    /** Ticks spent in the current state. Reset on both sides whenever the state changes. */
    private int stateTicks;
    @Nullable private UUID ownerUuid;
    /** Y the platform touches down at while falling (set by the scheduler; NaN = use collision). */
    private double landY = Double.NaN;
    private boolean clientReentryStarted;

    /** Turret aim in degrees. Yaw 0 = +Z (south), positive pitch = up. Computed on both sides. */
    private float aimYaw, aimPitch, prevAimYaw, prevAimPitch;

    public RailgunEntity(EntityType<? extends RailgunEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(STATE, RailgunState.FALLING.ordinal());
        this.dataTracker.startTracking(CHARGE, 0f);
        this.dataTracker.startTracking(TARGET, BlockPos.ORIGIN);
        this.dataTracker.startTracking(HAS_TARGET, false);
    }

    // =========================================================================================
    // Tick / state machine
    // =========================================================================================

    @Override
    public void tick() {
        super.tick();

        prevAimYaw = aimYaw;
        prevAimPitch = aimPitch;
        updateAim();

        RailgunState state = getState();
        int t = stateTicks++;

        if (getWorld().isClient) {
            clientTick(state, t);
            return;
        }

        switch (state) {
            case FALLING -> tickFalling();
            case DEPLOYING -> {
                if (t >= RailgunConfig.DEPLOY_TICKS) setState(RailgunState.IDLE);
            }
            case IDLE -> { }
            case CHARGING -> tickCharging();
            case FIRING -> tickFiring(t);
            case COOLDOWN -> tickCooldown(t);
        }
    }

    /**
     * The drop ignores block collision (noClip) and touches down exactly at {@link #landY}, so a
     * 10-block-wide pod never snags on trees or a ledge. Whatever is in the footprint is cleared
     * on landing.
     */
    private void tickFalling() {
        this.noClip = true;
        double vy = Math.max(getVelocity().y - RailgunConfig.DROP_ACCEL, -RailgunConfig.DROP_TERMINAL_SPEED);
        setVelocity(0, vy, 0);
        double nextY = getY() + vy;

        if (Double.isNaN(landY)) {
            landY = getWorld().getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    getBlockX(), getBlockZ());
        }
        if (nextY <= landY) {
            setPosition(getX(), landY, getZ());
            land();
        } else {
            setPosition(getX(), nextY, getZ());
        }
    }

    private void land() {
        setVelocity(Vec3d.ZERO);
        this.noClip = false;
        ServerWorld world = (ServerWorld) getWorld();
        clearFootprint(world);

        Vec3d base = getPos().add(0, 0.5, 0);
        RailgunFx.burst(world, ModParticles.FLASH, base.add(0, 2, 0), 2, 1.0, 0.5, 0);
        RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, base, 70, 6.0, 1.0, 0.06);
        RailgunFx.burst(world, ModParticles.DEBRIS, base.add(0, 1, 0), 90, 5.0, 1.0, 0.45);
        RailgunFx.burst(world, ModParticles.EMBER, base.add(0, 1, 0), 50, 5.0, 1.0, 0.3);
        Random r = this.random;
        for (int i = 0; i < 48; i++) {
            double a = i / 48.0 * Math.PI * 2;
            Vec3d dir = new Vec3d(Math.cos(a), 0, Math.sin(a));
            RailgunFx.launch(world, ModParticles.SHOCK_DUST, base.add(dir.multiply(5.5)),
                    dir.multiply(0.55 + r.nextDouble() * 0.25).add(0, 0.02, 0));
        }

        // Landing shockwave: shove and hurt anything standing in the drop zone.
        Box zone = getBoundingBox().expand(6.0, 2.0, 6.0);
        for (LivingEntity victim : world.getEntitiesByClass(LivingEntity.class, zone, LivingEntity::isAlive)) {
            Vec3d push = victim.getPos().subtract(getPos()).multiply(1, 0, 1);
            push = push.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : push.normalize().multiply(2.2);
            victim.addVelocity(push.x, 0.9, push.z);
            victim.velocityModified = true;
            victim.damage(getDamageSources().fallingBlock(this), 14.0f);
        }

        world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_LAND, SoundCategory.BLOCKS, 6.0f, 1.0f);
        world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_DEPLOY, SoundCategory.BLOCKS, 3.0f, 1.0f);
        setState(RailgunState.DEPLOYING);
    }

    /** Removes blocks inside the hitbox (trees, grass, snow...) without drops or vanilla FX. */
    private void clearFootprint(ServerWorld world) {
        Box box = getBoundingBox();
        BlockPos min = BlockPos.ofFloored(box.minX, box.minY + 0.01, box.minZ);
        BlockPos max = BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            float hardness = state.getHardness(world, pos);
            if (hardness < 0 || hardness >= 50) continue; // bedrock, obsidian, end portal frames...
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
        }
    }

    private void tickCharging() {
        if (!hasTarget()) {
            abortCharge();
            return;
        }
        float charge = Math.min(1f, getCharge() + 1f / RailgunConfig.CHARGE_TICKS);
        setCharge(charge);
        // Hold at 100% until the turret has finished slewing onto the target.
        if (charge >= 1f && isAimAligned()) {
            setState(RailgunState.FIRING);
        }
    }

    private void tickFiring(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        if (t == 0) {
            fireMainShot(world);
        } else if (t % 3 == 0 && t / 3 <= RailgunConfig.PENETRATION_STEPS) {
            // The slug keeps going: punch a column of secondary blasts into the ground.
            Vec3d impact = Vec3d.ofCenter(getTarget()).subtract(0, (t / 3) * RailgunConfig.PENETRATION_STEP_DEPTH, 0);
            RailgunFx.detonate(world, this, impact, RailgunConfig.SECONDARY_EXPLOSION_POWER, RailgunConfig.BREAK_BLOCKS);
            RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, impact.add(0, (t / 3) * RailgunConfig.PENETRATION_STEP_DEPTH, 0),
                    20, 3.0, 1.5, 0.08);
        }
        if (t > 0 && t % 4 == 0 && t < RailgunConfig.FIRE_TICKS - 8) {
            // Sustained beam: keep the impact zone boiling.
            Vec3d impact = Vec3d.ofCenter(getTarget());
            RailgunFx.burst(world, ModParticles.EMBER, impact.add(0, 1, 0), 25, 3.0, 1.0, 0.5);
            RailgunFx.burst(world, ModParticles.PLASMA_SPARK, impact.add(0, 1, 0), 30, 2.0, 1.0, 0.7);
            damageAlongBeam(world, getMuzzlePos(1f), impact, 0.25f);
        }

        setCharge(Math.max(0f, 1f - (float) t / RailgunConfig.FIRE_TICKS));

        if (t >= RailgunConfig.FIRE_TICKS) {
            world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_POWER_DOWN, SoundCategory.BLOCKS, 3.0f, 1.0f);
            setState(RailgunState.COOLDOWN);
        }
    }

    private void tickCooldown(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        if (t % 3 == 0 && t < RailgunConfig.COOLDOWN_TICKS - 20) {
            // Heat venting from the cooling fins along the barrel roof.
            Vec3d muzzle = getMuzzlePos(1f);
            Vec3d pivot = getPos().add(0, RailgunConfig.PIVOT_Y, 0);
            for (int i = 0; i < 3; i++) {
                double f = 0.2 + this.random.nextDouble() * 0.5;
                Vec3d p = pivot.lerp(muzzle, f).add(0, 1.5, 0);
                RailgunFx.launch(world, ModParticles.PLASMA_SMOKE, p, new Vec3d(0, 0.12 + this.random.nextDouble() * 0.08, 0));
            }
        }
        if (t >= RailgunConfig.COOLDOWN_TICKS) {
            setCharge(0f);
            setState(RailgunState.IDLE);
        }
    }

    private void fireMainShot(ServerWorld world) {
        BlockPos targetPos = getTarget();
        world.getChunk(targetPos); // force-load the impact chunk so the strike always lands

        Vec3d muzzle = getMuzzlePos(1f);
        Vec3d impact = Vec3d.ofCenter(targetPos);

        damageAlongBeam(world, muzzle, impact, 1f);
        RailgunFx.detonate(world, this, impact, RailgunConfig.IMPACT_EXPLOSION_POWER, RailgunConfig.BREAK_BLOCKS);

        // Impact
        RailgunFx.burst(world, ModParticles.FLASH, impact.add(0, 2, 0), 4, 1.5, 1.0, 0);
        RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, impact, 120, 5.0, 3.0, 0.12);
        RailgunFx.burst(world, ModParticles.DEBRIS, impact.add(0, 1, 0), 140, 2.5, 1.5, 0.9);
        RailgunFx.burst(world, ModParticles.EMBER, impact.add(0, 1, 0), 100, 3.0, 2.0, 0.8);
        RailgunFx.burst(world, ModParticles.PLASMA_SPARK, impact.add(0, 1, 0), 120, 2.0, 2.0, 1.2);
        Random r = this.random;
        for (int i = 0; i < 64; i++) {
            double a = i / 64.0 * Math.PI * 2;
            Vec3d dir = new Vec3d(Math.cos(a), 0, Math.sin(a));
            RailgunFx.launch(world, ModParticles.SHOCK_DUST, impact.add(dir.multiply(3)),
                    dir.multiply(0.9 + r.nextDouble() * 0.4).add(0, 0.05, 0));
        }
        // Muzzle blast + recoil smoke from the breech
        RailgunFx.burst(world, ModParticles.FLASH, muzzle, 2, 0.5, 0.5, 0);
        RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, muzzle, 60, 2.5, 2.5, 0.25);
        RailgunFx.burst(world, ModParticles.PLASMA_SPARK, muzzle, 80, 1.0, 1.0, 1.4);
        RailgunFx.burst(world, ModParticles.SHOCK_DUST, getPos().add(0, 0.3, 0), 60, 6.0, 0.2, 0.25);

        world.playSound(null, muzzle.x, muzzle.y, muzzle.z, ModSounds.RAILGUN_FIRE, SoundCategory.BLOCKS, 16.0f, 1.0f);
        world.playSound(null, impact.x, impact.y, impact.z, ModSounds.RAILGUN_IMPACT, SoundCategory.BLOCKS, 16.0f, 1.0f);
    }

    /**
     * Hits every living entity within {@link RailgunConfig#BEAM_HIT_RADIUS} of the beam segment.
     * The segment can be hundreds of blocks long, so instead of one enormous AABB query we sweep
     * small boxes along it and de-duplicate.
     */
    private void damageAlongBeam(ServerWorld world, Vec3d from, Vec3d to, float scale) {
        Vec3d delta = to.subtract(from);
        double length = delta.length();
        if (length < 1e-3) return;
        Vec3d dir = delta.multiply(1.0 / length);

        DamageSource source = new DamageSource(
                world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(RailgunMod.BEAM_DAMAGE),
                this, getOwner(world));

        double r = RailgunConfig.BEAM_HIT_RADIUS + 1.0;
        double step = 12.0;
        Set<LivingEntity> hit = new HashSet<>();
        for (double d = 0; d < length + step; d += step) {
            Vec3d a = from.add(dir.multiply(Math.min(d, length)));
            Vec3d b = from.add(dir.multiply(Math.min(d + step, length)));
            hit.addAll(world.getEntitiesByClass(LivingEntity.class, new Box(a, b).expand(r), LivingEntity::isAlive));
        }

        for (LivingEntity victim : hit) {
            Vec3d c = victim.getBoundingBox().getCenter();
            double along = MathHelper.clamp(c.subtract(from).dotProduct(dir), 0, length);
            double dist = c.distanceTo(from.add(dir.multiply(along)));
            if (dist <= RailgunConfig.BEAM_HIT_RADIUS + victim.getWidth() * 0.5) {
                victim.damage(source, RailgunConfig.BEAM_DAMAGE * scale);
            }
        }
    }

    // =========================================================================================
    // Client-side cosmetics
    // =========================================================================================

    private void clientTick(RailgunState state, int t) {
        World world = getWorld();
        Random rand = this.random;
        switch (state) {
            case FALLING -> {
                if (!clientReentryStarted) {
                    clientReentryStarted = true;
                    RailgunMod.client().startReentrySound(this);
                }
                // Re-entry plasma sheath + a long smoke trail streaming above the pod.
                for (int i = 0; i < 10; i++) {
                    double a = rand.nextDouble() * Math.PI * 2;
                    double rad = 4.5 + rand.nextDouble() * 1.5;
                    double x = getX() + Math.cos(a) * rad, z = getZ() + Math.sin(a) * rad;
                    world.addParticle(ModParticles.REENTRY_FIRE, x, getY() + rand.nextDouble() * 2, z,
                            Math.cos(a) * 0.15, 0.9 + rand.nextDouble() * 0.6, Math.sin(a) * 0.15);
                }
                for (int i = 0; i < 4; i++) {
                    world.addParticle(ModParticles.PLASMA_SMOKE, getX() + (rand.nextDouble() - 0.5) * 7,
                            getY() + 6 + rand.nextDouble() * 4, getZ() + (rand.nextDouble() - 0.5) * 7, 0, 0.5, 0);
                }
                world.addParticle(ModParticles.PLASMA_SPARK, getX() + (rand.nextDouble() - 0.5) * 9, getY(),
                        getZ() + (rand.nextDouble() - 0.5) * 9, (rand.nextDouble() - 0.5) * 0.6, 0.8, (rand.nextDouble() - 0.5) * 0.6);
            }
            case DEPLOYING -> {
                if (t % 2 == 0 && t < 40) {
                    // Hydraulic steam from the outriggers.
                    for (int i = 0; i < 4; i++) {
                        double a = i * Math.PI / 2;
                        world.addParticle(ModParticles.PLASMA_SMOKE, getX() + Math.cos(a) * 6.2, getY() + 0.8,
                                getZ() + Math.sin(a) * 6.2, Math.cos(a) * 0.05, 0.08, Math.sin(a) * 0.05);
                    }
                }
            }
            case CHARGING -> {
                float charge = getCharge();
                Vec3d muzzle = getMuzzlePos(1f);
                int count = 2 + (int) (charge * 10);
                for (int i = 0; i < count; i++) {
                    // Motes spawn on a shell and get sucked into the muzzle.
                    double rad = 3 + rand.nextDouble() * 3;
                    Vec3d off = new Vec3d(rand.nextDouble() - 0.5, rand.nextDouble() - 0.5, rand.nextDouble() - 0.5)
                            .normalize().multiply(rad);
                    Vec3d p = muzzle.add(off);
                    Vec3d v = off.multiply(-0.06 - charge * 0.06);
                    world.addParticle(ModParticles.ENERGY_MOTE, p.x, p.y, p.z, v.x, v.y, v.z);
                }
                // Sparks crawling along the barrel coils.
                Vec3d pivot = getPos().add(0, RailgunConfig.PIVOT_Y, 0);
                for (int i = 0; i < 1 + (int) (charge * 4); i++) {
                    Vec3d p = pivot.lerp(muzzle, 0.2 + rand.nextDouble() * 0.75)
                            .add((rand.nextDouble() - 0.5) * 3, (rand.nextDouble() - 0.5) * 2.5, (rand.nextDouble() - 0.5) * 3);
                    world.addParticle(ModParticles.PLASMA_SPARK, p.x, p.y, p.z,
                            (rand.nextDouble() - 0.5) * 0.3, rand.nextDouble() * 0.3, (rand.nextDouble() - 0.5) * 0.3);
                }
            }
            case FIRING -> {
                Vec3d muzzle = getMuzzlePos(1f);
                Vec3d impact = Vec3d.ofCenter(getTarget());
                // Embers shed along the beam.
                for (int i = 0; i < 6; i++) {
                    Vec3d p = muzzle.lerp(impact, rand.nextDouble())
                            .add((rand.nextDouble() - 0.5) * 3, (rand.nextDouble() - 0.5) * 3, (rand.nextDouble() - 0.5) * 3);
                    world.addParticle(ModParticles.EMBER, p.x, p.y, p.z, 0, 0.05, 0);
                }
            }
            default -> { }
        }
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (STATE.equals(data) && getWorld().isClient) {
            stateTicks = 0;
            RailgunMod.client().onStateChanged(this, getState());
        }
    }

    // =========================================================================================
    // Aiming
    // =========================================================================================

    private void updateAim() {
        float targetYaw = aimYaw;
        float targetPitch = 0f;
        if (hasTarget() && getState().isOperational()) {
            Vec3d to = Vec3d.ofCenter(getTarget()).subtract(getPos().add(0, RailgunConfig.PIVOT_Y, 0));
            double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
            targetYaw = (float) Math.toDegrees(Math.atan2(to.x, to.z));
            targetPitch = (float) Math.toDegrees(Math.atan2(to.y, horizontal));
        }
        float yawDiff = MathHelper.wrapDegrees(targetYaw - aimYaw);
        aimYaw = MathHelper.wrapDegrees(aimYaw + MathHelper.clamp(yawDiff, -RailgunConfig.SLEW_DEG_PER_TICK, RailgunConfig.SLEW_DEG_PER_TICK));
        aimPitch += MathHelper.clamp(targetPitch - aimPitch, -RailgunConfig.SLEW_DEG_PER_TICK, RailgunConfig.SLEW_DEG_PER_TICK);
        // Keep prev values continuous across the ±180 wrap so render interpolation doesn't spin.
        if (Math.abs(aimYaw - prevAimYaw) > 180f) prevAimYaw = aimYaw;
    }

    private boolean isAimAligned() {
        if (!hasTarget()) return false;
        Vec3d to = Vec3d.ofCenter(getTarget()).subtract(getPos().add(0, RailgunConfig.PIVOT_Y, 0));
        double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
        float yaw = (float) Math.toDegrees(Math.atan2(to.x, to.z));
        float pitch = (float) Math.toDegrees(Math.atan2(to.y, horizontal));
        return Math.abs(MathHelper.wrapDegrees(yaw - aimYaw)) < 1f && Math.abs(pitch - aimPitch) < 1f;
    }

    /** Interpolated turret yaw in radians (0 = +Z). */
    public float getAimYawRad(float tickDelta) {
        return (float) Math.toRadians(MathHelper.lerpAngleDegrees(tickDelta, prevAimYaw, aimYaw));
    }

    /** Interpolated barrel elevation in radians (positive = up). */
    public float getAimPitchRad(float tickDelta) {
        return (float) Math.toRadians(MathHelper.lerp(tickDelta, prevAimPitch, aimPitch));
    }

    /** Unit vector the barrel points along. */
    public Vec3d getAimDirection(float tickDelta) {
        float yaw = getAimYawRad(tickDelta);
        float pitch = getAimPitchRad(tickDelta);
        double cosPitch = Math.cos(pitch);
        return new Vec3d(Math.sin(yaw) * cosPitch, Math.sin(pitch), Math.cos(yaw) * cosPitch);
    }

    /** World-space position of the muzzle: pivot + aim direction * barrel length. */
    public Vec3d getMuzzlePos(float tickDelta) {
        return getLerpedPos(tickDelta).add(0, RailgunConfig.PIVOT_Y, 0)
                .add(getAimDirection(tickDelta).multiply(RailgunConfig.BARREL_LENGTH));
    }

    // =========================================================================================
    // Commands from the control menu (server side, already validated by ModPackets)
    // =========================================================================================

    public void setTarget(BlockPos pos) {
        dataTracker.set(TARGET, pos.toImmutable());
        dataTracker.set(HAS_TARGET, true);
    }

    public boolean tryStartCharge() {
        if (getState() != RailgunState.IDLE || !hasTarget()) return false;
        setCharge(0f);
        setState(RailgunState.CHARGING);
        return true;
    }

    public boolean abortCharge() {
        if (getState() != RailgunState.CHARGING) return false;
        setCharge(0f);
        setState(RailgunState.IDLE);
        getWorld().playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_POWER_DOWN, SoundCategory.BLOCKS, 2.5f, 1.2f);
        return true;
    }

    /** Returns the platform to orbit (removes it with a light show). */
    public void recall() {
        if (getWorld() instanceof ServerWorld world) {
            Vec3d c = getPos().add(0, 3, 0);
            RailgunFx.burst(world, ModParticles.FLASH, c, 2, 1.0, 1.0, 0);
            RailgunFx.burst(world, ModParticles.ENERGY_MOTE, c, 200, 5.0, 3.0, 0.4);
            RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, getPos().add(0, 1, 0), 60, 5.0, 1.0, 0.1);
            world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_POWER_DOWN, SoundCategory.BLOCKS, 3.0f, 0.6f);
        }
        discard();
    }

    // =========================================================================================
    // Interaction & entity behaviour
    // =========================================================================================

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND || getState() == RailgunState.FALLING) return ActionResult.PASS;
        if (getWorld().isClient) {
            RailgunMod.client().openControlScreen(this);
        }
        return ActionResult.success(getWorld().isClient);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Indestructible, but creative players can sneak-punch it to send it back to orbit.
        if (!getWorld().isClient && source.getAttacker() instanceof PlayerEntity player
                && player.isCreative() && player.isSneaking()) {
            recall();
            return true;
        }
        return false;
    }

    @Override
    public boolean isImmuneToExplosion() {
        return true;
    }

    @Override
    public boolean canHit() {
        return !isRemoved();
    }

    @Override
    public boolean isCollidable() {
        return getState() != RailgunState.FALLING;
    }

    @Override
    public boolean shouldRender(double distance) {
        if (isBeamActive()) return true;
        double max = 384.0;
        return distance < max * max;
    }

    /** The model (barrel especially) extends far beyond the hitbox; cull against its real reach. */
    @Override
    public Box getVisibilityBoundingBox() {
        double r = RailgunConfig.RENDER_REACH;
        return getBoundingBox().expand(r, 6.0, r);
    }

    public boolean isBeamActive() {
        RailgunState s = getState();
        return (s == RailgunState.CHARGING || s == RailgunState.FIRING) && hasTarget();
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    // =========================================================================================
    // Accessors
    // =========================================================================================

    public RailgunState getState() { return RailgunState.byId(dataTracker.get(STATE)); }

    public void setState(RailgunState state) {
        dataTracker.set(STATE, state.ordinal());
        stateTicks = 0;
    }

    public int getStateTicks() { return stateTicks; }
    public float getCharge() { return dataTracker.get(CHARGE); }
    public void setCharge(float charge) { dataTracker.set(CHARGE, MathHelper.clamp(charge, 0f, 1f)); }
    public BlockPos getTarget() { return dataTracker.get(TARGET); }
    public boolean hasTarget() { return dataTracker.get(HAS_TARGET); }
    @Nullable public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(@Nullable UUID uuid) { this.ownerUuid = uuid; }
    public void setLandY(double y) { this.landY = y; }

    @Nullable
    private Entity getOwner(ServerWorld world) {
        return ownerUuid == null ? null : world.getEntity(ownerUuid);
    }

    // =========================================================================================
    // Persistence
    // =========================================================================================

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        setState(RailgunState.byId(nbt.getInt("State")));
        stateTicks = nbt.getInt("StateTicks");
        setCharge(nbt.getFloat("Charge"));
        if (nbt.getBoolean("HasTarget")) setTarget(BlockPos.fromLong(nbt.getLong("Target")));
        ownerUuid = nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null;
        aimYaw = prevAimYaw = nbt.getFloat("AimYaw");
        aimPitch = prevAimPitch = nbt.getFloat("AimPitch");
        landY = nbt.contains("LandY") ? nbt.getDouble("LandY") : Double.NaN;
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("State", getState().ordinal());
        nbt.putInt("StateTicks", stateTicks);
        nbt.putFloat("Charge", getCharge());
        nbt.putBoolean("HasTarget", hasTarget());
        nbt.putLong("Target", getTarget().asLong());
        if (ownerUuid != null) nbt.putUuid("Owner", ownerUuid);
        nbt.putFloat("AimYaw", aimYaw);
        nbt.putFloat("AimPitch", aimPitch);
        if (!Double.isNaN(landY)) nbt.putDouble("LandY", landY);
    }

    // =========================================================================================
    // GeckoLib
    // =========================================================================================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 5-tick blend between states so transitions never pop.
        controllers.add(new AnimationController<>(this, "main", 5, this::animationPredicate));
    }

    private PlayState animationPredicate(AnimationState<RailgunEntity> state) {
        return switch (getState()) {
            case FALLING -> state.setAndContinue(ANIM_FALLING);
            case DEPLOYING -> state.setAndContinue(ANIM_DEPLOY);
            case IDLE, COOLDOWN -> state.setAndContinue(ANIM_IDLE);
            case CHARGING -> state.setAndContinue(ANIM_CHARGE);
            case FIRING -> state.setAndContinue(ANIM_FIRE);
        };
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
