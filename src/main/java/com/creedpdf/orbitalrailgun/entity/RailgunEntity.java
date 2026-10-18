package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.loadout.LoadoutEffects;
import com.creedpdf.orbitalrailgun.util.LandingSurvey;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunCollision;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The orbital railgun: drops as a compact pod, transforms into an 11-block emplacement when its
 * panel is used, fires on command from the owner's uplink tablet, packs itself back into a pod
 * and launches back to orbit.
 *
 * <h2>Why an Entity and not a GeoBlockEntity?</h2>
 * It falls from orbit, changes footprint, and flies away again; a block entity is anchored to one
 * block and would need placeholder multiblocks. A plain {@link Entity} has a movable, resizable
 * hitbox, saves itself to NBT, and GeckoLib renders it with {@code GeoEntityRenderer}.
 *
 * <h2>Authority & sync</h2>
 * The server owns the state machine. State, charge, target, owner and the panel's "confirm"
 * prompt are {@link TrackedData}, so they reach every watching client without custom packets.
 * The hitbox follows the state (small solid pillar in pod form, large walk-through footprint
 * when deployed). While deployed the railgun keeps its chunk loaded so the owner can fire it
 * remotely from anywhere in the dimension.
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
    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(RailgunEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    /** Installed loadout (see {@link Loadout}). */
    private static final TrackedData<Integer> LOADOUT =
            DataTracker.registerData(RailgunEntity.class, TrackedDataHandlerRegistry.INTEGER);
    /** A supply capsule is on its way with a new loadout: deployment waits for it. */
    private static final TrackedData<Boolean> SUPPLY_INBOUND =
            DataTracker.registerData(RailgunEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    /** Pod-form hitbox: the armoured pillar (shell apothem 34 px) with the rail pointing up. */
    private static final EntityDimensions POD_DIMENSIONS = EntityDimensions.fixed(4.4f, 10.75f);

    /*
     * GeckoLib renders entity models rotated 180 degrees about Y, so model +Z (where the tablet
     * panel is modelled) is world -Z: model (x, y, z) px -> world (-x, y, -z) / 16.
     */
    /** Centre of the tablet mounted in the core's hatch (model y 48, front face z 33). */
    public static final Vec3d POD_PANEL = new Vec3d(0.0, 48.0 / 16, -33.0 / 16);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // ---- Local (non-synced) state -----------------------------------------------------------
    /** Ticks spent in the current state. Reset on both sides whenever the state changes. */
    private int stateTicks;
    /** Y the pod touches down at while falling (set by the scheduler; NaN = use the heightmap). */
    private double landY = Double.NaN;
    private boolean clientLoopSoundStarted;
    private boolean chunkForced;
    private double launchSpeed;
    /** Remaining rounds of an autoloader burst after the current one. */
    private int shotsLeft;
    /** Plasma afterburn pool at the last impact. */
    private int afterburnTicks;
    private Vec3d afterburnPos = Vec3d.ZERO;
    /** Safety net: clears SUPPLY_INBOUND if the capsule never arrives (e.g. after a reload). */
    private int inboundTimeout;

    /** Turret aim in degrees. Yaw 0 = +Z (south), positive pitch = up. Computed on both sides. */
    private float aimYaw, aimPitch = 90f, prevAimYaw, prevAimPitch = 90f;

    public RailgunEntity(EntityType<? extends RailgunEntity> type, World world) {
        super(type, world);
        calculateDimensions(); // start with the pod hitbox (the type's size is the deployed one)
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(STATE, RailgunState.FALLING.ordinal());
        this.dataTracker.startTracking(CHARGE, 0f);
        this.dataTracker.startTracking(TARGET, BlockPos.ORIGIN);
        this.dataTracker.startTracking(HAS_TARGET, false);
        this.dataTracker.startTracking(OWNER, Optional.empty());
        this.dataTracker.startTracking(LOADOUT, 0);
        this.dataTracker.startTracking(SUPPLY_INBOUND, false);
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
            case POD, POD_READY -> { }
            case PANEL_OPEN -> {
                if (t >= RailgunConfig.PANEL_TICKS) setState(RailgunState.TRANSFORMING);
            }
            case TRANSFORMING -> tickTransforming(t);
            case IDLE -> { }
            case CHARGING -> tickCharging(t);
            case FIRING -> tickFiring(t);
            case COOLDOWN -> tickCooldown(t);
            case PACKING -> tickPacking(t);
            case LAUNCHING -> tickLaunching(t);
        }

        ServerWorld sw = (ServerWorld) getWorld();
        if (state.isOperational()) LoadoutEffects.deployedTick(this, sw, this.age);
        if (afterburnTicks > 0) LoadoutEffects.afterburnTick(this, sw, afterburnPos, afterburnTicks--);
        if (isSupplyInbound() && --inboundTimeout <= 0) dataTracker.set(SUPPLY_INBOUND, false);
    }

    /**
     * The drop ignores block collision (noClip) and touches down exactly at {@link #landY}, so the
     * pod never snags on trees or a ledge.
     */
    private void tickFalling() {
        this.noClip = true;
        double vy = Math.max(getVelocity().y - RailgunConfig.DROP_ACCEL, -RailgunConfig.DROP_TERMINAL_SPEED);
        setVelocity(0, vy, 0);
        double nextY = getY() + vy;
        if (Double.isNaN(landY)) {
            landY = getWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, getBlockX(), getBlockZ());
        }
        if (nextY <= landY + 12) smashThrough((ServerWorld) getWorld(), Math.max(nextY, landY));
        if (nextY <= landY) {
            setPosition(getX(), landY, getZ());
            land();
        } else {
            setPosition(getX(), nextY, getZ());
        }
    }

    /** The falling pod smashes through whatever is in its way (trees, roofs, a hill top). */
    private void smashThrough(ServerWorld world, double y) {
        BlockPos c = BlockPos.ofFloored(getX(), y, getZ());
        int broken = 0;
        for (BlockPos p : BlockPos.iterate(c.add(-2, 0, -2), c.add(2, 10, 2))) {
            BlockState st = world.getBlockState(p);
            if (st.isAir() || LandingSurvey.unbreakable(world, p, st) || st.isOf(RailgunMod.RAILGUN_COLLIDER)) continue;
            world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.SKIP_DROPS);
            if (broken++ % 3 == 0) RailgunFx.burst(world, ModParticles.DEBRIS, Vec3d.ofCenter(p), 5, 0.5, 0.5, 0.4);
        }
    }

    private void land() {
        setVelocity(Vec3d.ZERO);
        this.noClip = false;
        ServerWorld world = (ServerWorld) getWorld();
        // Smash a flat platform: everything breakable in the deployment volume is cleared.
        int cleared = LandingSurvey.clearPlatform(world, BlockPos.ofFloored(getX(), landY, getZ()));
        if (cleared > 0) {
            RailgunFx.burst(world, ModParticles.DEBRIS, getPos().add(0, 2, 0), Math.min(400, 60 + cleared), 6.0, 2.0, 0.6);
            RailgunFx.burst(world, ModParticles.SHOCK_DUST, getPos().add(0, 1, 0), 80, 6.0, 1.0, 0.2);
        }
        setChunkForced(world, true);

        Vec3d base = getPos().add(0, 0.5, 0);
        RailgunFx.burst(world, ModParticles.FLASH, base.add(0, 2, 0), 1, 0.5, 0.5, 0);
        RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, base, 40, 3.0, 0.6, 0.05);
        RailgunFx.burst(world, ModParticles.DEBRIS, base.add(0, 1, 0), 50, 2.5, 0.8, 0.35);
        ring(world, ModParticles.SHOCK_DUST, base, 2.5, 32, 0.45);

        Box zone = getBoundingBox().expand(3.0, 1.0, 3.0);
        for (LivingEntity victim : world.getEntitiesByClass(LivingEntity.class, zone, LivingEntity::isAlive)) {
            Vec3d push = victim.getPos().subtract(getPos()).multiply(1, 0, 1);
            push = push.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : push.normalize().multiply(1.4);
            victim.addVelocity(push.x, 0.6, push.z);
            victim.velocityModified = true;
            victim.damage(getDamageSources().fallingBlock(this), 10.0f);
        }
        world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_LAND, SoundCategory.BLOCKS, 5.0f, 1.1f);
        setState(RailgunState.POD);
    }

    private void tickTransforming(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        Vec3d base = getPos().add(0, 0.3, 0);
        if (t == 0) {
            // Now grow into the full footprint: clear it and announce the transformation.
            clearFootprint(world, getBoundingBox());
            world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_TRANSFORM, SoundCategory.BLOCKS, 4.0f, 1.0f);
        }
        if (t >= 6 && t <= 21 && t % 3 == 0) {
            // Locking bolts popping one after another: a puff of steam at each seam.
            int i = (t - 6) / 3;
            if (i < 4) {
                double sx = (i & 1) == 0 ? 1 : -1, sz = (i & 2) == 0 ? 1 : -1;
                RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, base.add(sx * 2.2, 3.5, sz * 2.2), 4, 0.1, 0.15, 0.04);
            }
        }
        if (t >= 46 && t <= 76 && t % 3 == 0) {
            // Shell quarters gliding out along the diagonals.
            double r = 2.2 + (t - 46) / 30.0 * 5.4;
            for (int i = 0; i < 4; i++) {
                double sx = (i & 1) == 0 ? 1 : -1, sz = (i & 2) == 0 ? 1 : -1;
                RailgunFx.burst(world, ModParticles.SHOCK_DUST, base.add(sx * r * 0.7071, 0, sz * r * 0.7071), 3, 0.5, 0.05, 0.06);
            }
        }
        if (t >= 80 && t <= 102 && t % 3 == 0) {
            // Deck plates scraping out (east, north, west in model terms).
            int i = Math.min(2, (t - 80) / 5);
            Vec3d dir = i == 0 ? new Vec3d(1, 0, 0) : i == 1 ? new Vec3d(0, 0, 1) : new Vec3d(-1, 0, 0);
            RailgunFx.burst(world, ModParticles.SHOCK_DUST, base.add(dir.multiply(4.8)), 3, 0.8, 0.05, 0.05);
        }
        if (t >= 136 && t <= 166 && t % 4 == 0) {
            // Turret rising out of the core: venting.
            ring(world, ModParticles.PLASMA_SMOKE, base.add(0, 4.4, 0), 2.2, 8, 0.05);
        }
        if (t == RailgunConfig.CLAMP_SLAM_TICK) {
            for (int i = 0; i < 4; i++) {
                // Clamp feet sit on the diagonals, ~6.3 blocks out on each axis.
                double sx = (i & 1) == 0 ? 1 : -1, sz = (i & 2) == 0 ? 1 : -1;
                Vec3d foot = base.add(sx * 6.3, 0, sz * 6.3);
                RailgunFx.burst(world, ModParticles.SHOCK_DUST, foot, 10, 1.0, 0.1, 0.2);
                RailgunFx.burst(world, ModParticles.DEBRIS, foot.add(0, 0.4, 0), 14, 0.9, 0.2, 0.38);
                RailgunFx.burst(world, ModParticles.PLASMA_SPARK, foot.add(0, 0.4, 0), 10, 0.6, 0.2, 0.45);
            }
            for (LivingEntity victim : world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(3, 1, 3), LivingEntity::isAlive)) {
                victim.addVelocity(0, 0.45, 0);
                victim.velocityModified = true;
            }
        }
        if (t >= RailgunConfig.TRANSFORM_TICKS) {
            if (getLoadout().has(Loadout.Option.SHIELD)) {
                world.playSound(null, getX(), getY() + 2, getZ(), ModSounds.signature(Loadout.Option.SHIELD), SoundCategory.BLOCKS, 5f, 1f);
            }
            RailgunCollision.place(world, getBlockPos());
            setState(RailgunState.IDLE);
        }
    }

    private void tickPacking(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        if (t == 0) {
            RailgunCollision.remove(world, getBlockPos());
            world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_PACK, SoundCategory.BLOCKS, 4.0f, 1.0f);
            dataTracker.set(HAS_TARGET, false);
            setCharge(0f);
        }
        int r = RailgunConfig.PACK_TICKS - t; // mirror of the deploy timeline
        if (r == RailgunConfig.CLAMP_SLAM_TICK) {
            for (int i = 0; i < 4; i++) {
                double sx = (i & 1) == 0 ? 1 : -1, sz = (i & 2) == 0 ? 1 : -1;
                RailgunFx.burst(world, ModParticles.SHOCK_DUST, getPos().add(sx * 6.3, 0.3, sz * 6.3), 8, 0.8, 0.1, 0.15);
            }
        }
        if (r >= 46 && r <= 76 && t % 3 == 0) {
            double rad = 2.2 + (r - 46) / 30.0 * 5.4;
            for (int i = 0; i < 4; i++) {
                double sx = (i & 1) == 0 ? 1 : -1, sz = (i & 2) == 0 ? 1 : -1;
                RailgunFx.burst(world, ModParticles.SHOCK_DUST, getPos().add(sx * rad * 0.7071, 0.3, sz * rad * 0.7071), 3, 0.5, 0.05, 0.06);
            }
        }
        if (t >= RailgunConfig.PACK_TICKS) setState(RailgunState.POD_READY);
    }

    private void tickLaunching(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        this.noClip = true;
        if (t == 0) {
            world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_LAUNCH, SoundCategory.BLOCKS, 12.0f, 1.0f);
            RailgunFx.burst(world, ModParticles.FLASH, getPos().add(0, 0.5, 0), 2, 0.8, 0.2, 0);
            launchSpeed = 0.05;
            LoadoutEffects.launchStart(this, world);
        }
        if (t < 30) {
            ring(world, ModParticles.PLASMA_SMOKE, new Vec3d(getX(), landY + 0.4, getZ()), 2.5, 10, 0.25);
            if (t % 3 == 0) RailgunFx.burst(world, ModParticles.EMBER, new Vec3d(getX(), landY + 0.5, getZ()), 10, 2.0, 0.2, 0.3);
        }
        double boost = LoadoutEffects.launchAccelMul(this);
        launchSpeed = Math.min(RailgunConfig.LAUNCH_MAX_SPEED * boost, launchSpeed + RailgunConfig.LAUNCH_ACCEL * boost * (1 + t / 40.0));
        setPosition(getX(), getY() + launchSpeed, getZ());
        if (getY() > landY + RailgunConfig.LAUNCH_REMOVE_HEIGHT || getY() > getWorld().getTopY() + 64 || t > 400) {
            discard();
        }
    }

    /** Removes blocks inside the box (trees, grass, snow...) without drops or vanilla FX. */
    private void clearFootprint(ServerWorld world, Box box) {
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

    public void ring(ServerWorld world, net.minecraft.particle.ParticleEffect particle, Vec3d center, double radius,
                      int count, double speed) {
        Random r = this.random;
        for (int i = 0; i < count; i++) {
            double a = i / (double) count * Math.PI * 2;
            Vec3d dir = new Vec3d(Math.cos(a), 0, Math.sin(a));
            RailgunFx.launch(world, particle, center.add(dir.multiply(radius)),
                    dir.multiply(speed * (0.8 + r.nextDouble() * 0.4)).add(0, 0.02, 0));
        }
    }

    private void tickCharging(int t) {
        if (!hasTarget()) {
            abortCharge();
            return;
        }
        LoadoutEffects.chargingTick(this, (ServerWorld) getWorld(), t);
        float charge = Math.min(1f, getCharge() + 1f / getLoadout().chargeTicks(RailgunConfig.CHARGE_TICKS));
        setCharge(charge);
        // Hold at 100% until the turret has finished slewing onto the target.
        if (charge >= 1f && isAimAligned()) {
            setState(RailgunState.FIRING);
        }
    }

    private void tickFiring(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        Loadout l = getLoadout();
        boolean breaks = RailgunConfig.BREAK_BLOCKS && l.breaksBlocks();
        if (t == 0) {
            fireMainShot(world);
        } else {
            Vec3d tip = boreTick(world, t, breaks);
            LoadoutEffects.firingTick(this, world, t, tip);
        }
        if (t == 8 || t == 16) {
            // Secondary ring of blasts around the crater as the shockwave rolls out.
            Vec3d impact = Vec3d.ofCenter(getTarget());
            double rr = RailgunConfig.RING_BLAST_RADIUS * (t == 8 ? 0.7 : 1.15);
            for (int i = 0; i < RailgunConfig.RING_BLASTS; i++) {
                double a = (i + (t == 16 ? 0.5 : 0)) * Math.PI * 2 / RailgunConfig.RING_BLASTS;
                Vec3d p = impact.add(Math.cos(a) * rr, 0, Math.sin(a) * rr);
                RailgunFx.detonate(world, this, p, RailgunConfig.RING_BLAST_POWER * l.ringBlastMul(), breaks);
                RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, p.add(0, 1, 0), 18, 2.0, 1.5, 0.1);
                RailgunFx.burst(world, ModParticles.EMBER, p.add(0, 1, 0), 14, 1.5, 1.0, 0.6);
            }
            ring(world, ModParticles.SHOCK_DUST, impact, rr, 48, 0.9);
        }
        if (t > 0 && t % 4 == 0 && t < RailgunConfig.FIRE_TICKS - 8) {
            // Sustained beam: keep the impact zone boiling.
            Vec3d impact = Vec3d.ofCenter(getTarget());
            RailgunFx.burst(world, ModParticles.EMBER, impact.add(0, 1, 0), 25, 3.0, 1.0, 0.5);
            RailgunFx.burst(world, ModParticles.PLASMA_SPARK, impact.add(0, 1, 0), 30, 2.0, 1.0, 0.7);
            damageAlongBeam(world, getMuzzlePos(1f), getBeamEnd(1f), 0.3f);
        }

        setCharge(Math.max(0f, 1f - (float) t / RailgunConfig.FIRE_TICKS));

        if (t >= RailgunConfig.FIRE_TICKS) {
            if (shotsLeft > 0 && hasTarget()) {
                // Autoloader: re-chamber and go again with the capacitors still mostly full.
                shotsLeft--;
                LoadoutEffects.onShotEnded(this, world, true);
                setState(RailgunState.CHARGING);
                setCharge(0.8f);
            } else {
                world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_POWER_DOWN, SoundCategory.BLOCKS, 3.0f, 1.0f);
                setState(RailgunState.COOLDOWN);
            }
        }
    }

    private void tickCooldown(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        LoadoutEffects.cooldownTick(this, world, t);
        int cooldown = getLoadout().cooldownTicks(RailgunConfig.COOLDOWN_TICKS);
        if (t % 3 == 0 && t < cooldown - 20) {
            // Heat venting from the cooling fins along the barrel roof.
            Vec3d muzzle = getMuzzlePos(1f);
            Vec3d pivot = getPos().add(0, RailgunConfig.PIVOT_Y, 0);
            for (int i = 0; i < 3; i++) {
                double f = 0.2 + this.random.nextDouble() * 0.5;
                Vec3d p = pivot.lerp(muzzle, f).add(0, 1.5, 0);
                RailgunFx.launch(world, ModParticles.PLASMA_SMOKE, p, new Vec3d(0, 0.12 + this.random.nextDouble() * 0.08, 0));
            }
        }
        if (t >= cooldown) {
            setCharge(0f);
            setState(RailgunState.IDLE);
        }
    }

    private void fireMainShot(ServerWorld world) {
        BlockPos targetPos = getTarget();
        world.getChunk(targetPos); // force-load the impact chunk so the strike always lands
        Loadout l = getLoadout();
        boolean breaks = RailgunConfig.BREAK_BLOCKS && l.breaksBlocks();

        Vec3d muzzle = getMuzzlePos(1f);
        Vec3d impact = Vec3d.ofCenter(targetPos);

        damageAlongBeam(world, muzzle, impact, 1f);
        if (breaks) {
            // Cut straight through anything between the muzzle and the target.
            Vec3d dir = impact.subtract(muzzle).normalize();
            carveLine(world, muzzle.add(dir.multiply(4.0)), impact, RailgunConfig.PATH_RADIUS * l.boreRadiusMul());
        }
        RailgunFx.detonate(world, this, impact, RailgunConfig.IMPACT_EXPLOSION_POWER * l.blastMul(), breaks);

        // Impact
        RailgunFx.burst(world, ModParticles.FLASH, impact.add(0, 2, 0), 8, 3.0, 2.0, 0);
        RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, impact, 260, 8.0, 5.0, 0.18);
        RailgunFx.burst(world, ModParticles.DEBRIS, impact.add(0, 1, 0), breaks ? 300 : 40, 3.5, 2.0, 1.4);
        RailgunFx.burst(world, ModParticles.EMBER, impact.add(0, 1, 0), 220, 4.0, 3.0, 1.2);
        RailgunFx.burst(world, ModParticles.PLASMA_SPARK, impact.add(0, 1, 0), 260, 3.0, 3.0, 1.8);
        ring(world, ModParticles.SHOCK_DUST, impact, 3.0, 96, 1.7);
        ring(world, ModParticles.SHOCK_DUST, impact, 6.0, 96, 1.2);
        // Muzzle blast + recoil dust around the fortress (the ghost baffle swallows the flash)
        if (!l.has(Loadout.Option.GHOST)) {
            RailgunFx.burst(world, ModParticles.FLASH, muzzle, 4, 1.0, 1.0, 0);
            RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, muzzle, 140, 3.5, 3.5, 0.35);
            RailgunFx.burst(world, ModParticles.PLASMA_SPARK, muzzle, 160, 1.5, 1.5, 2.0);
        } else {
            RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, muzzle, 20, 1.0, 1.0, 0.05);
        }
        RailgunFx.burst(world, ModParticles.SHOCK_DUST, getPos().add(0, 0.3, 0), 140, 8.0, 0.2, 0.45);
        ring(world, ModParticles.SHOCK_DUST, getPos().add(0, 0.3, 0), 6.0, 64, 0.9);

        LoadoutEffects.fireSounds(this, world, muzzle, impact);
        LoadoutEffects.onImpact(this, world, muzzle, impact);
    }

    /** How deep (blocks past the target, along the beam) the bore has reached. */
    public double getBoreDepth(float tickDelta) {
        if (getState() != RailgunState.FIRING) return 0.0;
        double max = RailgunConfig.BORE_DEPTH * getLoadout().boreDepthMul();
        return Math.min(max, (stateTicks + tickDelta) * RailgunConfig.BORE_SPEED * getLoadout().boreDepthMul());
    }

    /** Where the beam currently ends: the target, plus however far it has bored through. */
    public Vec3d getBeamEnd(float tickDelta) {
        Vec3d target = Vec3d.ofCenter(getTarget());
        Vec3d dir = target.subtract(getMuzzlePos(tickDelta));
        if (dir.lengthSquared() < 1e-6) return target;
        return target.add(dir.normalize().multiply(getBoreDepth(tickDelta)));
    }

    /** Each firing tick: push the bore deeper, vaporizing a tunnel, with blasts at the tip. */
    private Vec3d boreTick(ServerWorld world, int t, boolean breaks) {
        Loadout l = getLoadout();
        Vec3d target = Vec3d.ofCenter(getTarget());
        Vec3d dir = target.subtract(getMuzzlePos(1f)).normalize();
        double max = RailgunConfig.BORE_DEPTH * l.boreDepthMul(), speed = RailgunConfig.BORE_SPEED * l.boreDepthMul();
        double d0 = Math.min(max, (t - 1) * speed);
        double d1 = Math.min(max, t * speed);
        Vec3d tip = target.add(dir.multiply(d1));
        if (d1 <= d0) return tip;
        if (breaks) {
            carveLine(world, target.add(dir.multiply(d0)), tip, RailgunConfig.BORE_RADIUS * l.boreRadiusMul());
        }
        if (t % RailgunConfig.BORE_BLAST_EVERY == 0) {
            RailgunFx.detonate(world, this, tip, RailgunConfig.BORE_BLAST_POWER * (float) l.boreRadiusMul(), breaks);
            RailgunFx.burst(world, ModParticles.FLASH, tip, 1, 0.5, 0.5, 0);
        }
        RailgunFx.burst(world, ModParticles.PLASMA_SPARK, tip, 16, 1.5, 1.5, 0.8);
        RailgunFx.burst(world, ModParticles.EMBER, tip, 10, 2.0, 2.0, 0.5);
        // Molten spray erupting back out of the bore mouth.
        RailgunFx.burst(world, ModParticles.EMBER, target.add(0, 1, 0), 12, 1.5, 0.5, 0.9);
        RailgunFx.burst(world, ModParticles.PLASMA_SMOKE, target.add(0, 1.5, 0), 6, 2.0, 1.0, 0.15);
        return tip;
    }

    /**
     * Vaporizes every breakable block within {@code radius} of the segment a-b (no drops, no
     * vanilla particles or sounds). Unbreakable blocks (bedrock, the railgun's own colliders,
     * hardness < 0) survive.
     */
    public void carveLine(ServerWorld world, Vec3d a, Vec3d b, double radius) {
        Vec3d ab = b.subtract(a);
        double len = ab.length();
        if (len < 1e-4) return;
        Vec3d dir = ab.multiply(1.0 / len);
        BlockPos min = BlockPos.ofFloored(Math.min(a.x, b.x) - radius, Math.min(a.y, b.y) - radius, Math.min(a.z, b.z) - radius);
        BlockPos max = BlockPos.ofFloored(Math.max(a.x, b.x) + radius, Math.max(a.y, b.y) + radius, Math.max(a.z, b.z) + radius);
        int bottom = world.getBottomY();
        double r2 = radius * radius;
        int budget = 60000;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (pos.getY() < bottom || pos.getY() >= world.getTopY()) continue;
            Vec3d c = Vec3d.ofCenter(pos);
            double along = MathHelper.clamp(c.subtract(a).dotProduct(dir), 0, len);
            if (c.squaredDistanceTo(a.add(dir.multiply(along))) > r2) continue;
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.getHardness(world, pos) < 0) continue;
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.SKIP_DROPS);
            if (--budget <= 0) return;
        }
    }

    /**
     * Hits every living entity within {@link RailgunConfig#BEAM_HIT_RADIUS} of the beam segment.
     * The segment can be hundreds of blocks long, so small boxes are swept along it.
     */
    public DamageSource beamDamage(ServerWorld world) {
        return new DamageSource(world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(RailgunMod.BEAM_DAMAGE),
                this, getOwner(world));
    }

    public void damageAlongBeam(ServerWorld world, Vec3d from, Vec3d to, float scale) {
        Vec3d delta = to.subtract(from);
        double length = delta.length();
        if (length < 1e-3) return;
        Vec3d dir = delta.multiply(1.0 / length);

        DamageSource source = beamDamage(world);
        double hitRadius = RailgunConfig.BEAM_HIT_RADIUS * getLoadout().hitRadiusMul();
        float damage = RailgunConfig.BEAM_DAMAGE * getLoadout().damageMul();

        double r = hitRadius + 1.0;
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
            if (dist <= hitRadius + victim.getWidth() * 0.5) {
                victim.damage(source, damage * scale);
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
                if (!clientLoopSoundStarted) {
                    clientLoopSoundStarted = true;
                    RailgunMod.client().startReentrySound(this);
                }
                for (int i = 0; i < 8; i++) {
                    double a = rand.nextDouble() * Math.PI * 2;
                    double rad = 1.8 + rand.nextDouble() * 0.8;
                    world.addParticle(ModParticles.REENTRY_FIRE, getX() + Math.cos(a) * rad, getY() + rand.nextDouble() * 2,
                            getZ() + Math.sin(a) * rad, Math.cos(a) * 0.1, 0.9 + rand.nextDouble() * 0.6, Math.sin(a) * 0.1);
                }
                for (int i = 0; i < 3; i++) {
                    world.addParticle(ModParticles.PLASMA_SMOKE, getX() + (rand.nextDouble() - 0.5) * 3,
                            getY() + 6 + rand.nextDouble() * 4, getZ() + (rand.nextDouble() - 0.5) * 3, 0, 0.5, 0);
                }
            }
            case POD, POD_READY -> {
                if (t % 10 == 0) {
                    Vec3d p = getPanelPos();
                    world.addParticle(ModParticles.ENERGY_MOTE, p.x + (rand.nextDouble() - 0.5), p.y + 0.6,
                            p.z + 0.2, 0, 0.02, 0);
                }
            }
            case TRANSFORMING -> {
                if (t == RailgunConfig.CLAMP_SLAM_TICK) RailgunMod.client().shake(this, 1.0f);
                if (t == 2) RailgunMod.client().shake(this, 0.35f);
            }
            case CHARGING -> {
                float charge = getCharge();
                Vec3d muzzle = getMuzzlePos(1f);
                int count = 2 + (int) (charge * 10);
                for (int i = 0; i < count; i++) {
                    double rad = 3 + rand.nextDouble() * 3;
                    Vec3d off = new Vec3d(rand.nextDouble() - 0.5, rand.nextDouble() - 0.5, rand.nextDouble() - 0.5)
                            .normalize().multiply(rad);
                    Vec3d p = muzzle.add(off);
                    Vec3d v = off.multiply(-0.06 - charge * 0.06);
                    world.addParticle(ModParticles.ENERGY_MOTE, p.x, p.y, p.z, v.x, v.y, v.z);
                }
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
                float left = 1f - (float) t / RailgunConfig.FIRE_TICKS;
                RailgunMod.client().shake(this, 0.25f + 0.55f * left);
                for (int i = 0; i < 4; i++) {
                    Vec3d p = impact.add((rand.nextDouble() - 0.5) * 10, rand.nextDouble() * 2, (rand.nextDouble() - 0.5) * 10);
                    world.addParticle(ModParticles.PLASMA_SPARK, p.x, p.y, p.z, (rand.nextDouble() - 0.5) * 0.6, 0.4 + rand.nextDouble() * 0.6, (rand.nextDouble() - 0.5) * 0.6);
                }
                for (int i = 0; i < 14; i++) {
                    Vec3d p = muzzle.lerp(impact, rand.nextDouble())
                            .add((rand.nextDouble() - 0.5) * 3, (rand.nextDouble() - 0.5) * 3, (rand.nextDouble() - 0.5) * 3);
                    world.addParticle(ModParticles.EMBER, p.x, p.y, p.z, 0, 0.05, 0);
                }
            }
            case LAUNCHING -> {
                if (t == 0) RailgunMod.client().shake(this, 1.0f);
                if (getLoadout().has(Loadout.Option.THRUSTER)) {
                    for (int i = 0; i < 4; i++) {
                        double a = i * Math.PI / 2 + Math.PI / 4;
                        world.addParticle(ModParticles.THRUSTER_FLAME, getX() + Math.cos(a) * 1.9, getY() + 0.2,
                                getZ() + Math.sin(a) * 1.9, 0, -1.6 - rand.nextDouble(), 0);
                    }
                }
                // Thrusters under the pod.
                for (int i = 0; i < 14; i++) {
                    double a = rand.nextDouble() * Math.PI * 2;
                    double rad = rand.nextDouble() * 1.4;
                    world.addParticle(ModParticles.REENTRY_FIRE, getX() + Math.cos(a) * rad, getY() - 0.3,
                            getZ() + Math.sin(a) * rad, Math.cos(a) * 0.05, -1.2 - rand.nextDouble() * 0.8, Math.sin(a) * 0.05);
                }
                for (int i = 0; i < 3; i++) {
                    world.addParticle(ModParticles.PLASMA_SMOKE, getX() + (rand.nextDouble() - 0.5) * 2, getY() - 2,
                            getZ() + (rand.nextDouble() - 0.5) * 2, 0, -0.3, 0);
                }
                world.addParticle(ModParticles.PLASMA_SPARK, getX(), getY() - 0.5, getZ(),
                        (rand.nextDouble() - 0.5) * 0.6, -1.0, (rand.nextDouble() - 0.5) * 0.6);
            }
            default -> { }
        }
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (STATE.equals(data)) {
            calculateDimensions();
            if (getWorld().isClient) {
                stateTicks = 0;
                RailgunMod.client().onStateChanged(this, getState());
            }
        }
    }

    @Override
    public EntityDimensions getDimensions(EntityPose pose) {
        return getState().isPodForm() ? POD_DIMENSIONS : super.getDimensions(pose);
    }

    // =========================================================================================
    // Control panel
    // =========================================================================================

    /** World position of the tablet panel (the same in every form: it lives in the core). */
    public Vec3d getPanelPos() {
        return getPos().add(POD_PANEL);
    }

    /** Physical panel / console: right-clicking it zooms the view into it (client). */
    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        RailgunState state = getState();
        if (state == RailgunState.FALLING || state == RailgunState.LAUNCHING) return ActionResult.PASS;
        // Only the control panel is interactive. Anywhere else, PASS so the click falls through
        // (e.g. the player can still raise their tablet while looking at the railgun).
        if (player.getEyePos().distanceTo(getPanelPos()) > RailgunConfig.PANEL_REACH) return ActionResult.PASS;
        if (getWorld().isClient) {
            RailgunMod.client().openPanel(this);
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }

    /** Panel actions sent from the zoomed-in panel UI (validated by ModPackets). */
    public enum PanelAction { DEPLOY, RETRACT, RETURN }

    public boolean handlePanelAction(PlayerEntity player, PanelAction action) {
        if (!(getWorld() instanceof ServerWorld world)) return false;
        if (player.getEyePos().distanceTo(getPanelPos()) > RailgunConfig.PANEL_REACH + 2.0) return false;
        Vec3d p = getPanelPos();
        RailgunState state = getState();
        switch (action) {
            case DEPLOY -> {
                if (state != RailgunState.POD || isSupplyInbound()) return false;
                world.playSound(null, p.x, p.y, p.z, ModSounds.RAILGUN_PANEL, SoundCategory.BLOCKS, 1.5f, 1.0f);
                setState(RailgunState.PANEL_OPEN);
            }
            case RETRACT -> {
                if (state != RailgunState.IDLE && state != RailgunState.COOLDOWN) return false;
                setState(RailgunState.PACKING);
            }
            case RETURN -> {
                if (state != RailgunState.POD_READY) return false;
                setState(RailgunState.LAUNCHING);
            }
        }
        return true;
    }

    // =========================================================================================
    // Aiming
    // =========================================================================================

    /** The rail points straight up in pod form, while transforming (until it lowers) and packing. */
    private boolean railStowed() {
        RailgunState s = getState();
        return s.isPodForm() || s == RailgunState.PACKING
                || (s == RailgunState.TRANSFORMING && stateTicks < RailgunConfig.RAIL_LOWER_TICK);
    }

    /** Aim at the start of the pack-up, so the rail can ease from there to vertical. */
    private float packFromYaw, packFromPitch;
    private boolean packCaptured;

    private static float smooth(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * (3f - 2f * f);
    }

    private void updateAim() {
        RailgunState s = getState();
        if (s != RailgunState.PACKING) packCaptured = false;
        if (s == RailgunState.TRANSFORMING) {
            // Eased swing from vertical to level once the turret is up.
            float f = smooth((stateTicks - RailgunConfig.RAIL_LOWER_TICK) / (float) RailgunConfig.RAIL_MOVE_TICKS);
            aimPitch = 90f * (1f - f);
            return;
        }
        if (s == RailgunState.PACKING) {
            if (!packCaptured) {
                packCaptured = true;
                packFromYaw = MathHelper.wrapDegrees(aimYaw);
                packFromPitch = aimPitch;
            }
            // Centre the turret while the barrel telescopes in, then swing the rail up (eased).
            float fy = smooth(stateTicks / (float) RailgunConfig.PACK_RAIL_RAISE_TICK);
            aimYaw = packFromYaw * (1f - fy);
            float fp = smooth((stateTicks - RailgunConfig.PACK_RAIL_RAISE_TICK) / (float) RailgunConfig.RAIL_MOVE_TICKS);
            aimPitch = packFromPitch + (90f - packFromPitch) * fp;
            if (Math.abs(aimYaw - prevAimYaw) > 180f) prevAimYaw = aimYaw;
            return;
        }
        float targetYaw = aimYaw;
        float targetPitch = 0f;
        if (railStowed()) {
            targetPitch = 90f;
            targetYaw = 0f;
        } else if (hasTarget() && s.isOperational()) {
            Vec3d to = Vec3d.ofCenter(getTarget()).subtract(getPos().add(0, RailgunConfig.PIVOT_Y, 0));
            double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
            targetYaw = (float) Math.toDegrees(Math.atan2(to.x, to.z));
            targetPitch = (float) Math.toDegrees(Math.atan2(to.y, horizontal));
        }
        float yawDiff = MathHelper.wrapDegrees(targetYaw - aimYaw);
        float slew = RailgunConfig.SLEW_DEG_PER_TICK * getLoadout().slewMul();
        aimYaw = MathHelper.wrapDegrees(aimYaw + MathHelper.clamp(yawDiff, -slew, slew));
        aimPitch += MathHelper.clamp(targetPitch - aimPitch, -slew, slew);
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

    public float getAimYawRad(float tickDelta) {
        return (float) Math.toRadians(MathHelper.lerpAngleDegrees(tickDelta, prevAimYaw, aimYaw));
    }

    public float getAimPitchRad(float tickDelta) {
        return (float) Math.toRadians(MathHelper.lerp(tickDelta, prevAimPitch, aimPitch));
    }

    public Vec3d getAimDirection(float tickDelta) {
        float yaw = getAimYawRad(tickDelta);
        float pitch = getAimPitchRad(tickDelta);
        double cosPitch = Math.cos(pitch);
        return new Vec3d(Math.sin(yaw) * cosPitch, Math.sin(pitch), Math.cos(yaw) * cosPitch);
    }

    public Vec3d getMuzzlePos(float tickDelta) {
        return getLerpedPos(tickDelta).add(0, RailgunConfig.PIVOT_Y, 0)
                .add(getAimDirection(tickDelta).multiply(RailgunConfig.BARREL_LENGTH));
    }

    // =========================================================================================
    // Fire-control commands (server side; validated by ModPackets)
    // =========================================================================================

    public void setTarget(BlockPos pos) {
        dataTracker.set(TARGET, pos.toImmutable());
        dataTracker.set(HAS_TARGET, true);
        if (getLoadout().has(Loadout.Option.TARGETING) && getWorld() instanceof ServerWorld w) {
            w.playSound(null, getX(), getY() + 6, getZ(), ModSounds.signature(Loadout.Option.TARGETING), SoundCategory.BLOCKS, 3f, 1f);
        }
    }

    public boolean tryStartCharge() {
        if (getState() != RailgunState.IDLE || !hasTarget()) return false;
        shotsLeft = getLoadout().burst() - 1;
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

    /** Creative shortcut: removes the railgun instantly with a light show. */
    public void recall() {
        if (getWorld() instanceof ServerWorld world) {
            Vec3d c = getPos().add(0, 3, 0);
            RailgunFx.burst(world, ModParticles.FLASH, c, 2, 1.0, 1.0, 0);
            RailgunFx.burst(world, ModParticles.ENERGY_MOTE, c, 200, 5.0, 3.0, 0.4);
            world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_POWER_DOWN, SoundCategory.BLOCKS, 3.0f, 0.6f);
        }
        discard();
    }

    /** Finds the railgun owned by a player in any loaded dimension. */
    @Nullable
    public static RailgunEntity findOwned(MinecraftServer server, UUID owner) {
        for (ServerWorld world : server.getWorlds()) {
            List<? extends RailgunEntity> found = world.getEntitiesByType(RailgunMod.RAILGUN,
                    e -> e.isAlive() && owner.equals(e.getOwnerUuid()));
            if (!found.isEmpty()) return found.get(0);
        }
        return null;
    }

    // =========================================================================================
    // Entity behaviour
    // =========================================================================================

    private void setChunkForced(ServerWorld world, boolean forced) {
        if (chunkForced == forced) return;
        ChunkPos c = getChunkPos();
        world.setChunkForced(c.x, c.z, forced);
        chunkForced = forced;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && getWorld() instanceof ServerWorld world) {
            RailgunCollision.remove(world, getBlockPos());
            setChunkForced(world, false);
        }
        super.remove(reason);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Indestructible, but creative players can sneak-punch it to remove it.
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

    /** Solid as a pillar; the deployed emplacement is walk-through so its stairs and console are reachable. */
    @Override
    public boolean isCollidable() {
        RailgunState s = getState();
        return s == RailgunState.POD || s == RailgunState.PANEL_OPEN || s == RailgunState.POD_READY;
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
        return getBoundingBox().expand(r, 8.0, r);
    }

    /** The beam itself only exists while firing. */
    public boolean isBeamActive() {
        return getState() == RailgunState.FIRING && hasTarget();
    }

    /** Muzzle charge effects (orb, arcs) while charging; no beam yet. */
    public boolean isChargeVisible() {
        return getState() == RailgunState.CHARGING && hasTarget();
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
        calculateDimensions();
    }

    public int getStateTicks() { return stateTicks; }
    public float getCharge() { return dataTracker.get(CHARGE); }
    public void setCharge(float charge) { dataTracker.set(CHARGE, MathHelper.clamp(charge, 0f, 1f)); }
    public BlockPos getTarget() { return dataTracker.get(TARGET); }
    public boolean hasTarget() { return dataTracker.get(HAS_TARGET); }
    @Nullable public UUID getOwnerUuid() { return dataTracker.get(OWNER).orElse(null); }
    public void setOwnerUuid(@Nullable UUID uuid) { dataTracker.set(OWNER, Optional.ofNullable(uuid)); }
    public void setLandY(double y) { this.landY = y; }

    // ---- Loadout ----------------------------------------------------------------------------

    public Loadout getLoadout() { return Loadout.of(dataTracker.get(LOADOUT)); }
    public boolean isSupplyInbound() { return dataTracker.get(SUPPLY_INBOUND); }

    /**
     * Panel order: sends the loadout request to orbital storage. A supply capsule drops beside the
     * pod and installs it. Only on a landed pod, one delivery at a time.
     */
    public boolean orderLoadout(PlayerEntity player, int raw) {
        if (!(getWorld() instanceof ServerWorld world)) return false;
        if (getState() != RailgunState.POD || isSupplyInbound()) return false;
        if (player.getEyePos().distanceTo(getPanelPos()) > RailgunConfig.PANEL_REACH + 3.0) return false;
        Loadout want = Loadout.of(raw);
        if (want.raw() != raw || want.equals(getLoadout())) return false;
        if (com.creedpdf.orbitalrailgun.entity.SupplyCapsuleEntity.dispatch(world, this, raw) == null) return false;
        dataTracker.set(SUPPLY_INBOUND, true);
        inboundTimeout = 1200;
        Vec3d p = getPanelPos();
        world.playSound(null, p.x, p.y, p.z, ModSounds.UI_LOADOUT_ORDER, SoundCategory.BLOCKS, 1.5f, 1f);
        return true;
    }

    /** Called by the supply capsule at the end of its transfer. */
    public void receiveLoadout(int raw) {
        dataTracker.set(LOADOUT, Loadout.of(raw).raw());
        dataTracker.set(SUPPLY_INBOUND, false);
        if (getWorld() instanceof ServerWorld world) {
            world.playSound(null, getX(), getY() + 4, getZ(), ModSounds.RAILGUN_ATTACH, SoundCategory.BLOCKS, 3f, 1f);
            RailgunFx.burst(world, ModParticles.TRANSFER_MOTE, getPos().add(0, 4, 0), 80, 1.5, 3.0, 0.2);
            RailgunFx.burst(world, ModParticles.LENS_GLINT, getPos().add(0, 6, 0), 6, 1.0, 3.0, 0);
        }
    }

    public void startAfterburn(Vec3d at) {
        afterburnPos = at;
        afterburnTicks = LoadoutEffects.AFTERBURN_TICKS;
    }

    @Nullable
    private Entity getOwner(ServerWorld world) {
        UUID owner = getOwnerUuid();
        return owner == null ? null : world.getEntity(owner);
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
        setOwnerUuid(nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null);
        aimYaw = prevAimYaw = nbt.getFloat("AimYaw");
        aimPitch = prevAimPitch = nbt.contains("AimPitch") ? nbt.getFloat("AimPitch") : 90f;
        landY = nbt.contains("LandY") ? nbt.getDouble("LandY") : Double.NaN;
        chunkForced = nbt.getBoolean("ChunkForced");
        launchSpeed = nbt.getDouble("LaunchSpeed");
        dataTracker.set(LOADOUT, Loadout.of(nbt.getInt("Loadout")).raw());
        shotsLeft = nbt.getInt("ShotsLeft");
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("State", getState().ordinal());
        nbt.putInt("StateTicks", stateTicks);
        nbt.putFloat("Charge", getCharge());
        nbt.putBoolean("HasTarget", hasTarget());
        nbt.putLong("Target", getTarget().asLong());
        UUID owner = getOwnerUuid();
        if (owner != null) nbt.putUuid("Owner", owner);
        nbt.putFloat("AimYaw", aimYaw);
        nbt.putFloat("AimPitch", aimPitch);
        if (!Double.isNaN(landY)) nbt.putDouble("LandY", landY);
        nbt.putBoolean("ChunkForced", chunkForced);
        nbt.putDouble("LaunchSpeed", launchSpeed);
        nbt.putInt("Loadout", getLoadout().raw());
        nbt.putInt("ShotsLeft", shotsLeft);
    }

    // =========================================================================================
    // GeckoLib
    // =========================================================================================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // No GeckoLib controllers: the client plays the keyframes itself from (state, stateTicks)
        // in RailgunAnimator, which is deterministic and synced with the server timeline.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
