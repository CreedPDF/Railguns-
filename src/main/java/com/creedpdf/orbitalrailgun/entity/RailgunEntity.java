package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.loadout.PartDrop;
import com.creedpdf.orbitalrailgun.loadout.AmmoFx;
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

    /** Bit per clamp (pp, pn, np, nn) with no block to grip: its vectoring rockets hold the emplacement. */
    private static final TrackedData<Integer> CLAMP_FREE =
            DataTracker.registerData(RailgunEntity.class, TrackedDataHandlerRegistry.INTEGER);

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
        this.dataTracker.startTracking(CLAMP_FREE, 0);
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
            clampParticles(getWorld(), this.random);
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
            case ARMING -> tickArming(t);
            case SCANNING -> tickScanning(t);
            case RIFT -> tickRift(t);
        }

        ServerWorld sw = (ServerWorld) getWorld();
        clampTick(sw, state, t);
        if (state.isOperational()) LoadoutEffects.deployedTick(this, sw, this.age);
        if (afterburnTicks > 0) LoadoutEffects.afterburnTick(this, sw, afterburnPos, afterburnTicks--);
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
            RailgunCollision.place(world, getBlockPos());
            setState(PartDrop.armingTicks(getLoadout()) > 0 ? RailgunState.ARMING : RailgunState.IDLE);
        }
    }

    // ---- Rift ---------------------------------------------------------------------------------------

    /** Timeline (ticks): crown unfolds + rail swings up, beam, tear, open (vortex), collapse, snap, fold. */
    public static final int RIFT_FIRE = 120, RIFT_OPEN = 136, RIFT_OPENED = 206, RIFT_COLLAPSE = 806, RIFT_SNAP = 856, RIFT_TICKS = 930;
    /** The beam fires and holds for one second, then cuts out; the rift stays open on its own. */
    public static final int RIFT_BEAM_END = RIFT_FIRE + 20;
    /** Warnings to anyone in space before the rift closes (ticks before RIFT_COLLAPSE). */
    public static final int[] RIFT_WARNINGS = {400, 200, 100};
    public static final int RIFT_COOLDOWN = 1200;
    public static final double RIFT_HEIGHT = 160.0, RIFT_RADIUS = 26.0, VORTEX_RADIUS = 40.0, LIFT_RADIUS = 5.5;
    private int riftReadyAge;
    private int riftSwallowed;

    public boolean tryOpenRift() {
        if (getState() != RailgunState.IDLE || this.age < riftReadyAge) return false;
        riftSwallowed = 0;
        setState(RailgunState.RIFT);
        return true;
    }

    public boolean riftReady() {
        return this.age >= riftReadyAge;
    }

    public Vec3d getRiftPos(float tickDelta) {
        return getLerpedPos(tickDelta).add(0, RIFT_HEIGHT, 0);
    }

    /** Rift radius (blocks) at a point of the timeline; shared by server and client. */
    public static float riftRadius(float t) {
        if (t < RIFT_OPEN) return 0f;
        if (t < RIFT_OPENED) {
            float f = (t - RIFT_OPEN) / (RIFT_OPENED - RIFT_OPEN);
            float c = 1.70158f;
            float back = 1 + (c + 1) * (float) Math.pow(f - 1, 3) + c * (float) Math.pow(f - 1, 2);
            return (float) RIFT_RADIUS * back;
        }
        if (t < RIFT_COLLAPSE) return (float) RIFT_RADIUS;
        if (t < RIFT_SNAP) {
            float f = (t - RIFT_COLLAPSE) / (RIFT_SNAP - RIFT_COLLAPSE);
            return (float) RIFT_RADIUS * (1f - f * f * f);
        }
        return 0f;
    }

    private void tickRift(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        Vec3d rift = getRiftPos(1f);
        PlayerEntity owner = getOwner(world) instanceof PlayerEntity pe ? pe : null;
        if (t == 0) world.playSound(null, getX(), getY() + 8, getZ(), ModSounds.RIFT_CHARGE, SoundCategory.BLOCKS, 4f, 1f);
        if (t == RIFT_FIRE) {
            Vec3d m = getMuzzlePos(1f);
            world.playSound(null, m.x, m.y, m.z, ModSounds.RAILGUN_FIRE, SoundCategory.BLOCKS, 8f, 0.7f);
            RailgunFx.burst(world, ModParticles.RIFT_FLASH, m, 1, 0, 0, 0);
        }
        if (t == RIFT_OPEN) {
            world.playSound(null, rift.x, rift.y, rift.z, ModSounds.RIFT_TEAR, SoundCategory.BLOCKS, 30f, 1f);
            RailgunFx.burst(world, ModParticles.RIFT_FLASH, rift, 2, 0, 0, 0);
            RailgunFx.burst(world, ModParticles.RIFT_SPARK, rift, 300, 6, 1, 1.2);
            if (owner instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                sp.sendMessage(Text.translatable("message.orbital_railgun.rift.open").formatted(Formatting.LIGHT_PURPLE), true);
            }
        }
        if (t == RIFT_OPENED) {
            com.creedpdf.orbitalrailgun.space.RiftPortals.open(world, this, RIFT_RADIUS * 0.9, RIFT_SNAP - RIFT_OPENED + 40);
            world.playSound(null, rift.x, rift.y, rift.z, ModSounds.RIFT_PORTAL, SoundCategory.BLOCKS, 24f, 1f);
        }
        if (t >= RIFT_OPENED && t < RIFT_COLLAPSE) {
            vortex(world, rift, t);
            liftPlayers(world, rift, t);
        }
        for (int w : RIFT_WARNINGS) {
            if (t == RIFT_COLLAPSE - w) warnSpace(world, w / 20);
        }
        if (t >= RIFT_COLLAPSE && t < RIFT_SNAP && t % 4 == 0) {
            com.creedpdf.orbitalrailgun.space.RiftPortals.resize(world, this, riftRadius(t) / RIFT_RADIUS);
        }
        if (t == RIFT_COLLAPSE) world.playSound(null, rift.x, rift.y, rift.z, ModSounds.RIFT_CHARGE, SoundCategory.BLOCKS, 20f, 0.55f);
        if (t == RIFT_SNAP) {
            com.creedpdf.orbitalrailgun.space.RiftPortals.close(world, this);
            world.playSound(null, rift.x, rift.y, rift.z, ModSounds.RIFT_CLOSE, SoundCategory.BLOCKS, 32f, 1f);
            RailgunFx.burst(world, ModParticles.RIFT_FLASH, rift, 2, 0, 0, 0);
            RailgunFx.burst(world, ModParticles.RIFT_DUST, rift, 500, 10, 2, 0.4);
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(rift, rift).expand(24), LivingEntity::isAlive)) {
                if (owner != null && e.getUuid().equals(owner.getUuid())) continue;
                if (!(e instanceof net.minecraft.entity.mob.Monster)) continue;
                e.damage(beamDamage(world), 24f);
            }
            if (owner instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                sp.sendMessage(Text.translatable("message.orbital_railgun.rift.closed", riftSwallowed).formatted(Formatting.LIGHT_PURPLE), true);
            }
        }
        if (t >= RIFT_TICKS) {
            riftReadyAge = this.age + RIFT_COOLDOWN;
            setState(RailgunState.IDLE);
        }
    }

    /** Everyone in space (and the owner) hears the countdown to the rift closing. */
    private void warnSpace(ServerWorld world, int seconds) {
        Text msg = Text.translatable("message.orbital_railgun.rift.closing", seconds).formatted(Formatting.LIGHT_PURPLE);
        ServerWorld space = world.getServer().getWorld(com.creedpdf.orbitalrailgun.space.SpaceDims.SPACE);
        if (space != null) {
            for (net.minecraft.server.network.ServerPlayerEntity p : space.getPlayers()) {
                p.sendMessage(msg, false);
                p.playSound(ModSounds.SUIT_ALARM, SoundCategory.PLAYERS, 1f, 1f);
            }
        }
        if (getOwner(world) instanceof net.minecraft.server.network.ServerPlayerEntity sp && sp.getWorld() != space) sp.sendMessage(msg, true);
    }

    /**
     * The beam is a way up: players standing in its column (at the emplacement) are carried up
     * the beam and through the rift into the dimension beyond. Sneaking resists the lift.
     */
    private void liftPlayers(ServerWorld world, Vec3d rift, int t) {
        Box col = new Box(getX() - LIFT_RADIUS, getY() - 2, getZ() - LIFT_RADIUS, getX() + LIFT_RADIUS, rift.y + 6, getZ() + LIFT_RADIUS);
        for (net.minecraft.server.network.ServerPlayerEntity p : world.getEntitiesByClass(net.minecraft.server.network.ServerPlayerEntity.class, col,
                x -> x.isAlive() && !x.isSpectator())) {
            double dx = getX() - p.getX(), dz = getZ() - p.getZ();
            if (dx * dx + dz * dz > LIFT_RADIUS * LIFT_RADIUS) continue;
            if (p.getY() >= rift.y + 2) continue; // through the portal (Immersive Portals carries them into space)
            if (p.isSneaking() && p.getY() < getY() + 12) continue;
            double rise = Math.min(1.4, 0.35 + (p.getY() - getY()) * 0.012);
            p.setVelocity(dx * 0.05, rise, dz * 0.05);
            p.velocityModified = true;
            p.fallDistance = 0;
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(p));
            if (t % 20 == 0) {
                world.playSound(null, p.getX(), p.getY(), p.getZ(), ModSounds.RIFT_LIFT, SoundCategory.PLAYERS, 1.5f, 1f);
                p.sendMessage(Text.translatable("message.orbital_railgun.rift.lifting").formatted(Formatting.LIGHT_PURPLE), true);
            }
            if (t % 2 == 0) RailgunFx.burst(world, ModParticles.RIFT_WISP, p.getPos(), 3, 0.5, 0.5, 0.05);
        }
    }

    /** Hostiles within the vortex are lifted, spun up toward the rift and swallowed by it. */
    private void vortex(ServerWorld world, Vec3d rift, int t) {
        Box area = new Box(getX() - VORTEX_RADIUS, getY() - 12, getZ() - VORTEX_RADIUS,
                getX() + VORTEX_RADIUS, rift.y + 4, getZ() + VORTEX_RADIUS);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area,
                x -> x.isAlive() && x instanceof net.minecraft.entity.mob.Monster)) {
            Vec3d p = e.getPos();
            double dx = rift.x - p.x, dz = rift.z - p.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            if (h > VORTEX_RADIUS) continue;
            double dy = rift.y - p.y;
            if (dy < 3.5 && h < RIFT_RADIUS * 0.8) {
                // swallowed
                RailgunFx.burst(world, ModParticles.RIFT_FLASH, p, 1, 0, 0, 0);
                RailgunFx.burst(world, ModParticles.RIFT_SPARK, p, 40, 0.6, 0.6, 0.6);
                world.playSound(null, p.x, p.y, p.z, ModSounds.RIFT_ZAP, SoundCategory.HOSTILE, 3f, 1.4f);
                e.damage(beamDamage(world), 400f);
                if (!e.isAlive()) riftSwallowed++;
                continue;
            }
            double nx = h > 0.01 ? dx / h : 0, nz = h > 0.01 ? dz / h : 0;
            double pull = 0.05 + 0.08 * (1 - h / VORTEX_RADIUS);
            double lift = 0.11 + 0.06 * Math.min(1.0, (VORTEX_RADIUS - h) / VORTEX_RADIUS);
            Vec3d v = e.getVelocity();
            Vec3d nv = new Vec3d(v.x * 0.9 + nx * pull - nz * 0.16, Math.min(0.85, v.y * 0.9 + lift + 0.08), v.z * 0.9 + nz * pull + nx * 0.16);
            e.setVelocity(nv);
            e.velocityModified = true;
            e.fallDistance = 0;
            if (t % 4 == 0) RailgunFx.burst(world, ModParticles.RIFT_WISP, p.add(0, e.getHeight() / 2, 0), 2, 0.3, 0.3, 0.02);
        }
    }

    /** A bolt of rift lightning slams into the ground somewhere near the emplacement. */
    private void groundStrike(ServerWorld world, Vec3d rift) {
        double a = random.nextDouble() * Math.PI * 2, d = 8 + random.nextDouble() * 18;
        Vec3d ground = LoadoutEffects.surface(world, getPos().add(Math.cos(a) * d, 0, Math.sin(a) * d));
        world.playSound(null, ground.x, ground.y, ground.z, ModSounds.RIFT_ZAP, SoundCategory.BLOCKS, 6f, 0.8f);
        RailgunFx.burst(world, ModParticles.RIFT_SPARK, ground.add(0, 0.5, 0), 50, 0.8, 0.4, 0.6);
        RailgunFx.burst(world, ModParticles.RIFT_FLASH, ground.add(0, 1, 0), 1, 0, 0, 0);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(ground, ground).expand(3),
                x -> x.isAlive() && x instanceof net.minecraft.entity.mob.Monster)) {
            e.damage(beamDamage(world), 12f);
        }
        com.creedpdf.orbitalrailgun.network.ModPackets.broadcastRiftBolt(world, rift, ground);
    }

    // ---- scan array ---------------------------------------------------------------------------------

    public static final int SCAN_TICKS = 80, SCAN_PULSE_TICK = 30, SCAN_COOLDOWN = 400;
    public static final double SCAN_RADIUS = 48.0, SCAN_MOB_RADIUS = 64.0;
    private int scanReadyAge;

    /** Tablet SCAN: raise the scan array and send a pulse across the terrain. */
    public boolean tryStartScan() {
        if (getState() != RailgunState.IDLE || this.age < scanReadyAge) return false;
        setState(RailgunState.SCANNING);
        return true;
    }

    public boolean scanReady() {
        return this.age >= scanReadyAge;
    }

    /** World position of the spinning sensor head (model px (-34, 150, -20), turns with the turret). */
    public Vec3d getScanHeadPos(float tickDelta) {
        double x = -34 / 16.0, y = 150 / 16.0, z = 20 / 16.0;
        double yaw = getAimYawRad(tickDelta);
        double c = Math.cos(yaw), sn = Math.sin(yaw);
        return getLerpedPos(tickDelta).add(x * c + z * sn, y, -x * sn + z * c);
    }

    private void tickScanning(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        Vec3d head = getScanHeadPos(1f);
        if (t == 0) world.playSound(null, head.x, head.y, head.z, ModSounds.SCAN_CHARGE, SoundCategory.BLOCKS, 3f, 1f);
        if (t == SCAN_PULSE_TICK) {
            world.playSound(null, head.x, head.y, head.z, ModSounds.SCAN_PULSE, SoundCategory.BLOCKS, 8f, 1f);
            com.creedpdf.orbitalrailgun.network.ModPackets.broadcastScan(world, this, head, scanTerrain(world), scanHostiles(world));
        }
        if (t >= SCAN_TICKS) {
            scanReadyAge = this.age + SCAN_COOLDOWN;
            setState(RailgunState.IDLE);
        }
    }

    /** Ore kinds sent to clients (colour per kind). */
    public static int oreKind(String path) {
        if (path.equals("ancient_debris")) return 9;
        if (!path.endsWith("_ore")) return -1;
        if (path.contains("coal")) return 0;
        if (path.contains("iron")) return 1;
        if (path.contains("copper")) return 2;
        if (path.contains("gold")) return 3;
        if (path.contains("redstone")) return 4;
        if (path.contains("lapis")) return 5;
        if (path.contains("diamond")) return 6;
        if (path.contains("emerald")) return 7;
        if (path.contains("quartz")) return 8;
        return 10;
    }

    /** Every ore within the scan sphere: rare ones always, the rest nearest-first, capped. */
    private List<long[]> scanTerrain(ServerWorld world) {
        BlockPos c = getBlockPos();
        int r = (int) SCAN_RADIUS;
        double r2 = SCAN_RADIUS * SCAN_RADIUS;
        List<long[]> rare = new java.util.ArrayList<>(), common = new java.util.ArrayList<>();
        BlockPos.Mutable p = new BlockPos.Mutable();
        int y0 = Math.max(world.getBottomY(), c.getY() - r), y1 = Math.min(world.getTopY() - 1, c.getY() + r);
        java.util.Map<net.minecraft.block.Block, Integer> kinds = new java.util.HashMap<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r2) continue;
                if (!world.isChunkLoaded((c.getX() + x) >> 4, (c.getZ() + z) >> 4)) continue;
                for (int y = y0; y <= y1; y++) {
                    int dy = y - c.getY();
                    double d2 = x * x + z * z + dy * dy;
                    if (d2 > r2) continue;
                    p.set(c.getX() + x, y, c.getZ() + z);
                    BlockState st = world.getBlockState(p);
                    if (st.isAir()) continue;
                    int kind = kinds.computeIfAbsent(st.getBlock(),
                            b -> oreKind(net.minecraft.registry.Registries.BLOCK.getId(b).getPath()));
                    if (kind < 0) continue;
                    long[] e = {p.asLong(), kind, (long) d2};
                    if (kind == 3 || kind == 6 || kind == 7 || kind == 9) rare.add(e);
                    else common.add(e);
                }
            }
        }
        common.sort(java.util.Comparator.comparingLong(e -> e[2]));
        List<long[]> out = new java.util.ArrayList<>(rare.subList(0, Math.min(rare.size(), 300)));
        for (long[] e : common) {
            if (out.size() >= 700) break;
            out.add(e);
        }
        return out;
    }

    private int[] scanHostiles(ServerWorld world) {
        return world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(SCAN_MOB_RADIUS),
                        e -> e.isAlive() && e instanceof net.minecraft.entity.mob.Monster).stream()
                .limit(96).mapToInt(Entity::getId).toArray();
    }

    // ---- clamp vectoring rockets ----------------------------------------------------------------

    private static final double[][] CLAMP_FEET = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public int getClampFreeMask() { return dataTracker.get(CLAMP_FREE); }

    private boolean clampsDown(RailgunState state, int t) {
        return state.isOperational() || state == RailgunState.ARMING || state == RailgunState.SCANNING
                || state == RailgunState.RIFT
                || (state == RailgunState.TRANSFORMING && t >= RailgunConfig.CLAMP_SLAM_TICK)
                || (state == RailgunState.PACKING && t < RailgunConfig.PACK_TICKS - RailgunConfig.CLAMP_SLAM_TICK);
    }

    /** Clamp feet need solid blocks to bite into; any foot over air lights its rockets instead. */
    private void clampTick(ServerWorld world, RailgunState state, int t) {
        if (!clampsDown(state, t)) {
            if (dataTracker.get(CLAMP_FREE) != 0) dataTracker.set(CLAMP_FREE, 0);
            return;
        }
        if (this.age % 10 == 0 || t == RailgunConfig.CLAMP_SLAM_TICK) {
            int mask = 0;
            for (int i = 0; i < 4; i++) {
                BlockPos foot = BlockPos.ofFloored(getX() + CLAMP_FEET[i][0] * 6.3, getY() - 0.2, getZ() + CLAMP_FEET[i][1] * 6.3);
                boolean grip = false;
                for (int dy = 0; dy <= 2 && !grip; dy++) {
                    BlockPos p = foot.down(dy);
                    grip = !world.getBlockState(p).getCollisionShape(world, p).isEmpty()
                            && !world.getBlockState(p).isOf(RailgunMod.RAILGUN_COLLIDER);
                }
                if (!grip) mask |= 1 << i;
            }
            if (mask != dataTracker.get(CLAMP_FREE)) {
                if (mask != 0) world.playSound(null, getX(), getY() + 1, getZ(), ModSounds.CLAMP_THRUSTER, SoundCategory.BLOCKS, 4f, 0.9f);
                dataTracker.set(CLAMP_FREE, mask);
            }
        }
        int mask = dataTracker.get(CLAMP_FREE);
        if (mask != 0 && this.age % 36 == 0) {
            world.playSound(null, getX(), getY() + 1, getZ(), ModSounds.CLAMP_THRUSTER, SoundCategory.BLOCKS, 3.2f, 0.95f + 0.1f * random.nextFloat());
        }
    }

    /** Client: rocket plumes under every unsupported clamp, with gimballed vectoring jets. */
    private void clampParticles(World world, Random rand) {
        int mask = dataTracker.get(CLAMP_FREE);
        if (mask == 0) return;
        for (int i = 0; i < 4; i++) {
            if ((mask & (1 << i)) == 0) continue;
            double fx = getX() + CLAMP_FEET[i][0] * 6.3, fz = getZ() + CLAMP_FEET[i][1] * 6.3, fy = getY() - 0.1;
            for (int k = 0; k < 3; k++) {
                world.addParticle(ModParticles.THRUSTER_FLAME, fx + (rand.nextDouble() - 0.5) * 0.5, fy, fz + (rand.nextDouble() - 0.5) * 0.5,
                        (rand.nextDouble() - 0.5) * 0.06, -0.55 - rand.nextDouble() * 0.3, (rand.nextDouble() - 0.5) * 0.06);
            }
            world.addParticle(ModParticles.DRONE_JET, fx, fy - 0.3, fz, 0, -0.9, 0);
            // vectoring: short side bursts that keep the emplacement centred
            double phase = (this.age + i * 7) % 24;
            if (phase < 4) {
                double sx = Math.sin((this.age + i * 13) * 0.37), sz = Math.cos((this.age + i * 11) * 0.29);
                world.addParticle(ModParticles.DRONE_JET, fx, fy + 0.6, fz, sx * 0.55, -0.05, sz * 0.55);
                world.addParticle(ModParticles.DOCK_SPARK, fx, fy + 0.6, fz, sx * 0.2, 0.05, sz * 0.2);
            }
            if (this.age % 3 == 0) {
                world.addParticle(ModParticles.PLASMA_SMOKE, fx + (rand.nextDouble() - 0.5) * 2, fy - 1.5, fz + (rand.nextDouble() - 0.5) * 2, 0, -0.05, 0);
            }
        }
    }

    /**
     * Orbital part drops: each attachment is fired down from orbital storage, burns its retro
     * thrusters, vectors in beside its mount, reaches out with docking arms and clamps on.
     */
    private void tickArming(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        Loadout loadout = getLoadout();
        Vec3d base = getPos();
        float yaw = getAimYawRad(1f);
        for (PartDrop.Entry e : PartDrop.schedule(loadout)) {
            int lt = t - e.start();
            if (lt == 0) {
                Vec3d hi = PartDrop.world(base, e, yaw, e.ax() * PartDrop.HOVER, e.ay() * PartDrop.HOVER + 480, e.az() * PartDrop.HOVER);
                world.playSound(null, hi.x, hi.y, hi.z, ModSounds.DRONE_DESCENT, SoundCategory.BLOCKS, 8f, 0.95f + 0.1f * random.nextFloat());
            } else if (lt == PartDrop.DESCENT - 10) {
                Vec3d hv = PartDrop.world(base, e, yaw, e.ax() * PartDrop.HOVER, e.ay() * PartDrop.HOVER + 60, e.az() * PartDrop.HOVER);
                world.playSound(null, hv.x, hv.y, hv.z, ModSounds.DRONE_RETRO, SoundCategory.BLOCKS, 4f, 0.95f + 0.1f * random.nextFloat());
            } else if (lt == PartDrop.HOVER_END - 6) {
                Vec3d hv = PartDrop.world(base, e, yaw, e.ax() * PartDrop.HOVER, e.ay() * PartDrop.HOVER, e.az() * PartDrop.HOVER);
                world.playSound(null, hv.x, hv.y, hv.z, ModSounds.DRONE_DOCK, SoundCategory.BLOCKS, 3f, 1f);
            } else if (lt == PartDrop.APPROACH_END) {
                Vec3d at = PartDrop.world(base, e, yaw, 0, 0, 0);
                RailgunFx.burst(world, ModParticles.DOCK_SPARK, at, 22, 0.35, 0.35, 0.25);
                RailgunFx.burst(world, ModParticles.DRONE_JET, at, 6, 0.3, 0.3, 0.02);
            }
        }
        if (t >= PartDrop.armingTicks(loadout)) {
            world.playSound(null, getX(), getY() + 4, getZ(), ModSounds.RAILGUN_ATTACH, SoundCategory.BLOCKS, 3f, 1f);
            if (loadout.has(Loadout.Option.SHIELD)) {
                world.playSound(null, getX(), getY() + 2, getZ(), ModSounds.signature(Loadout.Option.SHIELD), SoundCategory.BLOCKS, 5f, 1f);
            }
            setState(RailgunState.IDLE);
        }
    }

    private void tickPacking(int t) {
        ServerWorld world = (ServerWorld) getWorld();
        float yaw = getAimYawRad(1f);
        for (PartDrop.Entry e : PartDrop.schedule(getLoadout())) {
            int lt = t - PartDrop.departStart(e);
            if (lt == 0) {
                Vec3d at = PartDrop.world(getPos(), e, yaw, 0, 0, 0);
                RailgunFx.burst(world, ModParticles.DOCK_SPARK, at, 10, 0.3, 0.3, 0.2);
            } else if (lt == 12) {
                Vec3d hv = PartDrop.world(getPos(), e, yaw, e.ax() * PartDrop.HOVER, e.ay() * PartDrop.HOVER, e.az() * PartDrop.HOVER);
                world.playSound(null, hv.x, hv.y, hv.z, ModSounds.DRONE_ASCEND, SoundCategory.BLOCKS, 5f, 0.95f + 0.1f * random.nextFloat());
            }
        }
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
        if (t >= RailgunConfig.PACK_TICKS) {
            setState(RailgunState.POD_READY);
            if (remoteRecall) {
                remoteRecall = false;
                setState(RailgunState.LAUNCHING);
            }
        }
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
                RailgunFx.burst(world, AmmoFx.smoke(l), p.add(0, 1, 0), 18, 2.0, 1.5, 0.1);
                RailgunFx.burst(world, AmmoFx.ember(l), p.add(0, 1, 0), 14, 1.5, 1.0, 0.6);
            }
            ring(world, AmmoFx.dust(l), impact, rr, 48, 0.9);
        }
        if (t > 0 && t % 4 == 0 && t < RailgunConfig.FIRE_TICKS - 8) {
            // Sustained beam: keep the impact zone boiling.
            Vec3d impact = Vec3d.ofCenter(getTarget());
            RailgunFx.burst(world, AmmoFx.ember(l), impact.add(0, 1, 0), 25, 3.0, 1.0, 0.5);
            RailgunFx.burst(world, AmmoFx.spark(l), impact.add(0, 1, 0), 30, 2.0, 1.0, 0.7);
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
        com.creedpdf.orbitalrailgun.network.ModPackets.broadcastBeamShot(world, getId(), muzzle, targetPos, l.raw());

        damageAlongBeam(world, muzzle, impact, 1f);
        if (breaks) {
            // Cut straight through anything between the muzzle and the target.
            Vec3d dir = impact.subtract(muzzle).normalize();
            carveLine(world, muzzle.add(dir.multiply(4.0)), impact, RailgunConfig.PATH_RADIUS * l.boreRadiusMul());
        }
        RailgunFx.detonate(world, this, impact, RailgunConfig.IMPACT_EXPLOSION_POWER * l.blastMul(), breaks);

        // Impact
        RailgunFx.burst(world, AmmoFx.flash(l), impact.add(0, 2, 0), 8, 3.0, 2.0, 0);
        RailgunFx.burst(world, AmmoFx.smoke(l), impact, 260, 8.0, 5.0, 0.18);
        RailgunFx.burst(world, ModParticles.DEBRIS, impact.add(0, 1, 0), breaks ? 300 : 40, 3.5, 2.0, 1.4);
        RailgunFx.burst(world, AmmoFx.ember(l), impact.add(0, 1, 0), 220, 4.0, 3.0, 1.2);
        RailgunFx.burst(world, AmmoFx.spark(l), impact.add(0, 1, 0), 260, 3.0, 3.0, 1.8);
        ring(world, AmmoFx.dust(l), impact, 3.0, 96, 1.7);
        ring(world, AmmoFx.dust(l), impact, 6.0, 96, 1.2);
        // Muzzle blast + recoil dust around the fortress (the ghost baffle swallows the flash)
        if (!l.has(Loadout.Option.GHOST)) {
            RailgunFx.burst(world, AmmoFx.flash(l), muzzle, 4, 1.0, 1.0, 0);
            RailgunFx.burst(world, AmmoFx.smoke(l), muzzle, 140, 3.5, 3.5, 0.35);
            RailgunFx.burst(world, AmmoFx.spark(l), muzzle, 160, 1.5, 1.5, 2.0);
        } else {
            RailgunFx.burst(world, ModParticles.GHOST_WISP, muzzle, 40, 1.2, 1.2, 0.08);
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
            RailgunFx.burst(world, AmmoFx.flash(l), tip, 1, 0.5, 0.5, 0);
        }
        RailgunFx.burst(world, AmmoFx.spark(l), tip, 16, 1.5, 1.5, 0.8);
        RailgunFx.burst(world, AmmoFx.ember(l), tip, 10, 2.0, 2.0, 0.5);
        // Molten spray erupting back out of the bore mouth.
        RailgunFx.burst(world, AmmoFx.ember(l), target.add(0, 1, 0), 12, 1.5, 0.5, 0.9);
        RailgunFx.burst(world, AmmoFx.smoke(l), target.add(0, 1.5, 0), 6, 2.0, 1.0, 0.15);
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
            case RIFT -> {
                Vec3d rift = getRiftPos(1f);
                float radius = riftRadius(t);
                if (t < RIFT_FIRE && t > 14) {
                    Vec3d m = getMuzzlePos(1f);
                    for (int i = 0; i < 3; i++) {
                        double a = rand.nextDouble() * Math.PI * 2, rr = 2 + rand.nextDouble() * 2;
                        Vec3d p = m.add(Math.cos(a) * rr, (rand.nextDouble() - 0.5) * 2, Math.sin(a) * rr);
                        Vec3d v = m.subtract(p).multiply(0.12);
                        world.addParticle(ModParticles.RIFT_SPARK, p.x, p.y, p.z, v.x, v.y, v.z);
                    }
                }
                if (radius > 1f) {
                    // stardust raining off the rim, wisps spiralling up the vortex
                    for (int i = 0; i < 4; i++) {
                        double a = rand.nextDouble() * Math.PI * 2;
                        double rr = radius * (0.85 + rand.nextDouble() * 0.2);
                        world.addParticle(ModParticles.RIFT_DUST, rift.x + Math.cos(a) * rr, rift.y - 0.5, rift.z + Math.sin(a) * rr,
                                0, -0.12 - rand.nextDouble() * 0.1, 0);
                    }
                    if (t >= RIFT_OPENED && t < RIFT_COLLAPSE) {
                        for (int i = 0; i < 3; i++) {
                            double a = rand.nextDouble() * Math.PI * 2, rr = 4 + rand.nextDouble() * (VORTEX_RADIUS - 4);
                            double y = getY() + 1 + rand.nextDouble() * (RIFT_HEIGHT - 6);
                            double tx = -Math.sin(a), tz = Math.cos(a);
                            world.addParticle(ModParticles.RIFT_WISP, getX() + Math.cos(a) * rr, y, getZ() + Math.sin(a) * rr,
                                    tx * 0.35 - Math.cos(a) * 0.06, 0.25, tz * 0.35 - Math.sin(a) * 0.06);
                        }
                    }
                }
                if (t == RIFT_OPEN) RailgunMod.client().shake(this, 1.2f);
                if (t == RIFT_SNAP) RailgunMod.client().shake(this, 1.8f);
            }
            case SCANNING -> {
                Vec3d head = getScanHeadPos(1f);
                if (t < SCAN_PULSE_TICK) {
                    for (int i = 0; i < 3; i++) {
                        double a = rand.nextDouble() * Math.PI * 2, rr = 1.2 + rand.nextDouble() * 1.4;
                        Vec3d p = head.add(Math.cos(a) * rr, (rand.nextDouble() - 0.4) * 1.2, Math.sin(a) * rr);
                        Vec3d v = head.subtract(p).multiply(0.12);
                        world.addParticle(ModParticles.SCAN_MOTE, p.x, p.y, p.z, v.x, v.y, v.z);
                    }
                } else if (t == SCAN_PULSE_TICK) {
                    world.addParticle(ModParticles.SCAN_FLARE, head.x, head.y, head.z, 0, 0, 0);
                    for (int i = 0; i < 40; i++) {
                        double a = i / 40.0 * Math.PI * 2;
                        world.addParticle(ModParticles.SCAN_MOTE, head.x, head.y, head.z, Math.cos(a) * 0.5, 0.02, Math.sin(a) * 0.5);
                    }
                }
            }
            case ARMING, PACKING -> {
                if (state == RailgunState.ARMING) armingParticles(world, rand, t);
                else packingParticles(world, rand, t);
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

    /** Client: plasma trails, retro burns, vectoring jets and docking sparks of the part drops. */
    private void armingParticles(World world, Random rand, int t) {
        float yaw = getAimYawRad(1f);
        for (PartDrop.Entry e : PartDrop.schedule(getLoadout())) {
            int lt = t - e.start();
            if (lt < 0 || lt > PartDrop.DOCK_END) continue;
            PartDrop.Pose pose = PartDrop.arrive(e, lt);
            Vec3d p = PartDrop.world(getPos(), e, yaw, pose.x(), pose.y(), pose.z());
            if (lt < PartDrop.DESCENT) {
                // Re-entry streak above the falling part; the retro burn lights for the last third.
                float speed = PartDrop.DROP / 16f * 2.2f / PartDrop.DESCENT * (float) Math.pow(1 - lt / (float) PartDrop.DESCENT, 1.2);
                for (int i = 0; i < 6; i++) {
                    double up = rand.nextDouble() * speed * 0.6;
                    world.addParticle(ModParticles.DRONE_TRAIL, p.x + (rand.nextDouble() - 0.5) * 0.4, p.y + 0.6 + up,
                            p.z + (rand.nextDouble() - 0.5) * 0.4, 0, 0.05, 0);
                }
                if (lt > PartDrop.DESCENT - 12) {
                    for (int i = 0; i < 6; i++) {
                        world.addParticle(ModParticles.DRONE_JET, p.x + (rand.nextDouble() - 0.5) * 0.5, p.y - 0.4,
                                p.z + (rand.nextDouble() - 0.5) * 0.5, (rand.nextDouble() - 0.5) * 0.1, -0.9 - rand.nextDouble() * 0.5,
                                (rand.nextDouble() - 0.5) * 0.1);
                    }
                }
            } else if (lt < PartDrop.APPROACH_END) {
                // Hovering and vectoring: four gimballed jets fire against the approach direction
                // and downward to hold altitude, flickering as the guidance corrects.
                Vec3d a = PartDrop.world(Vec3d.ZERO, e, yaw, e.ax(), e.ay(), e.az()).subtract(PartDrop.world(Vec3d.ZERO, e, yaw, 0, 0, 0)).normalize();
                for (int k = 0; k < 4; k++) {
                    double ang = k * Math.PI / 2 + lt * 0.3;
                    Vec3d off = new Vec3d(Math.cos(ang) * 0.45, 0, Math.sin(ang) * 0.45);
                    Vec3d v = new Vec3d((rand.nextDouble() - 0.5) * 0.2, -0.45 - rand.nextDouble() * 0.2, (rand.nextDouble() - 0.5) * 0.2);
                    if (lt >= PartDrop.HOVER_END) v = v.add(a.multiply(0.35));
                    world.addParticle(ModParticles.DRONE_JET, p.x + off.x, p.y - 0.3, p.z + off.z, v.x, v.y, v.z);
                }
            } else if (lt == PartDrop.APPROACH_END) {
                for (int i = 0; i < 24; i++) {
                    world.addParticle(ModParticles.DOCK_SPARK, p.x, p.y, p.z, (rand.nextDouble() - 0.5) * 0.6,
                            rand.nextDouble() * 0.5, (rand.nextDouble() - 0.5) * 0.6);
                }
            }
        }
    }

    private void packingParticles(World world, Random rand, int t) {
        float yaw = getAimYawRad(1f);
        for (PartDrop.Entry e : PartDrop.schedule(getLoadout())) {
            int lt = t - PartDrop.departStart(e);
            if (lt < 6 || lt > PartDrop.UNDOCK_END) continue;
            PartDrop.Pose pose = PartDrop.depart(e, lt);
            Vec3d p = PartDrop.world(getPos(), e, yaw, pose.x(), pose.y(), pose.z());
            for (int i = 0; i < (lt > 14 ? 8 : 3); i++) {
                world.addParticle(ModParticles.DRONE_JET, p.x + (rand.nextDouble() - 0.5) * 0.5, p.y - 0.4,
                        p.z + (rand.nextDouble() - 0.5) * 0.5, (rand.nextDouble() - 0.5) * 0.1, -1.0 - rand.nextDouble() * 0.6,
                        (rand.nextDouble() - 0.5) * 0.1);
            }
            if (lt > 20) world.addParticle(ModParticles.DRONE_TRAIL, p.x, p.y - 1.5, p.z, 0, -0.1, 0);
        }
    }

    /** Panel actions sent from the zoomed-in panel UI (validated by ModPackets). */
    public enum PanelAction { DEPLOY, RETRACT, RETURN }

    /** Set by a remote recall: once packed, the pod launches back to orbit without anyone at the panel. */
    private boolean remoteRecall;

    /**
     * Uplink-tablet recall from any distance: packs the emplacement up and sends the pod back to
     * orbit. Returns false while it is busy (charging, firing, scanning, rift, arming...).
     */
    public boolean remoteRecall() {
        RailgunState s = getState();
        switch (s) {
            case IDLE, COOLDOWN -> {
                remoteRecall = true;
                setState(RailgunState.PACKING);
                return true;
            }
            case POD, POD_READY -> {
                setState(RailgunState.LAUNCHING);
                return true;
            }
            case PACKING -> {
                remoteRecall = true;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public boolean handlePanelAction(PlayerEntity player, PanelAction action) {
        if (!(getWorld() instanceof ServerWorld world)) return false;
        if (player.getEyePos().distanceTo(getPanelPos()) > RailgunConfig.PANEL_REACH + 2.0) return false;
        Vec3d p = getPanelPos();
        RailgunState state = getState();
        switch (action) {
            case DEPLOY -> {
                if (state != RailgunState.POD) return false;
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
        if (s == RailgunState.RIFT) {
            int tt = stateTicks;
            targetPitch = tt < RIFT_TICKS - 30 ? 90f : 0f;
        } else if (railStowed()) {
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
            Vec3d at = LoadoutEffects.surface(w, Vec3d.ofCenter(pos)).add(0, 0.6, 0);
            RailgunFx.burst(w, ModParticles.TARGET_PING, at, 1, 0, 0, 0);
            ring(w, ModParticles.TARGET_PING, at, 3.0, 8, 0.0);
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
        if (getWorld() instanceof ServerWorld world) com.creedpdf.orbitalrailgun.space.RiftPortals.close(world, this);
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
        Box box = getBoundingBox().expand(r, 8.0, r);
        RailgunState s = getState();
        // Attachment parts fly in from ~150 blocks up (and leave the same way).
        if (s == RailgunState.ARMING || s == RailgunState.PACKING) box = box.stretch(0, PartDrop.DROP / 16.0 + 8, 0);
        return box;
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

    /**
     * Panel confirm: locks the loadout in. Nothing is delivered yet; the attachments drop from
     * orbital storage and dock themselves once the railgun deploys (see {@link PartDrop}).
     */
    public boolean configureLoadout(PlayerEntity player, int raw) {
        if (!(getWorld() instanceof ServerWorld world)) return false;
        if (getState() != RailgunState.POD) return false;
        if (player.getEyePos().distanceTo(getPanelPos()) > RailgunConfig.PANEL_REACH + 3.0) return false;
        Loadout want = Loadout.of(raw);
        if (want.raw() != raw) return false;
        dataTracker.set(LOADOUT, raw);
        Vec3d p = getPanelPos();
        world.playSound(null, p.x, p.y, p.z, ModSounds.UI_LOADOUT_ORDER, SoundCategory.BLOCKS, 1.5f, 1f);
        RailgunFx.burst(world, ModParticles.TARGET_PING, p, 3, 0.2, 0.2, 0);
        return true;
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
        remoteRecall = nbt.getBoolean("RemoteRecall");
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
        nbt.putBoolean("RemoteRecall", remoteRecall);
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
