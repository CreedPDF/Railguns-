package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
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
 * The deployable orbital railgun platform.
 *
 * <h2>Why an Entity and not a GeoBlockEntity?</h2>
 * A block entity is anchored to one block, can't fall from orbit with real physics, and a 3x4
 * block footprint would need a multiblock of invisible placeholder blocks. A plain {@link Entity}
 * (not LivingEntity: no health, AI, pathfinding or armor overhead) can move freely during the
 * drop, has a proper bounding box for collision and right-click picking, persists itself to NBT,
 * and GeckoLib renders it with {@code GeoEntityRenderer}.
 *
 * <h2>Authority & sync</h2>
 * The server owns the state machine. State, charge and target are {@link TrackedData}, so the
 * vanilla entity tracker pushes them to every client in range automatically: no custom packets
 * are needed for charge monitoring. Clients only derive cosmetic values (turret aim smoothing,
 * particles, sounds) from that synced data.
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
            case COOLDOWN -> {
                if (t >= RailgunConfig.COOLDOWN_TICKS) {
                    setCharge(0f);
                    setState(RailgunState.IDLE);
                }
            }
        }
    }

    private void tickFalling() {
        double vy = Math.max(getVelocity().y - RailgunConfig.DROP_ACCEL, -RailgunConfig.DROP_TERMINAL_SPEED);
        setVelocity(0, vy, 0);
        move(MovementType.SELF, getVelocity());

        if (isOnGround() || verticalCollision) {
            land();
        } else if (getY() < getWorld().getBottomY() - 64) {
            discard();
        }
    }

    private void land() {
        setVelocity(Vec3d.ZERO);
        ServerWorld world = (ServerWorld) getWorld();

        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 0.5, getZ(), 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 0.3, getZ(), 50, 3.0, 0.2, 3.0, 0.03);
        world.spawnParticles(ParticleTypes.LAVA, getX(), getY() + 0.5, getZ(), 25, 1.5, 0.2, 1.5, 0.0);

        // Landing shockwave: shove and hurt anything standing in the drop zone.
        Box zone = getBoundingBox().expand(5.0, 2.0, 5.0);
        for (LivingEntity victim : world.getEntitiesByClass(LivingEntity.class, zone, LivingEntity::isAlive)) {
            Vec3d push = victim.getPos().subtract(getPos()).multiply(1, 0, 1);
            push = push.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 0) : push.normalize().multiply(1.4);
            victim.addVelocity(push.x, 0.6, push.z);
            victim.velocityModified = true;
            victim.damage(getDamageSources().fallingBlock(this), 8.0f);
        }

        world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_LAND, SoundCategory.BLOCKS, 4.0f, 0.8f);
        world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_DEPLOY, SoundCategory.BLOCKS, 2.0f, 1.0f);
        setState(RailgunState.DEPLOYING);
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
            explode(world, impact, RailgunConfig.SECONDARY_EXPLOSION_POWER);
        }

        setCharge(Math.max(0f, 1f - (float) t / RailgunConfig.FIRE_TICKS));

        if (t >= RailgunConfig.FIRE_TICKS) {
            world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_POWER_DOWN, SoundCategory.BLOCKS, 1.5f, 1.0f);
            setState(RailgunState.COOLDOWN);
        }
    }

    private void fireMainShot(ServerWorld world) {
        BlockPos targetPos = getTarget();
        world.getChunk(targetPos); // force-load the impact chunk so the strike always lands

        Vec3d muzzle = getMuzzlePos(1f);
        Vec3d impact = Vec3d.ofCenter(targetPos);

        damageAlongBeam(world, muzzle, impact);
        explode(world, impact, RailgunConfig.IMPACT_EXPLOSION_POWER);

        world.spawnParticles(ParticleTypes.FLASH, impact.x, impact.y, impact.z, 2, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y, impact.z, 3, 1.5, 1.0, 1.5, 0);
        world.spawnParticles(ParticleTypes.LAVA, impact.x, impact.y, impact.z, 60, 2.5, 1.0, 2.5, 0);
        world.spawnParticles(ParticleTypes.FLASH, muzzle.x, muzzle.y, muzzle.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, getX(), getY() + 0.5, getZ(), 30, 2.0, 0.3, 2.0, 0.02);

        world.playSound(null, muzzle.x, muzzle.y, muzzle.z, ModSounds.RAILGUN_FIRE, SoundCategory.BLOCKS, 8.0f, 1.0f);
        world.playSound(null, impact.x, impact.y, impact.z, ModSounds.RAILGUN_IMPACT, SoundCategory.BLOCKS, 8.0f, 0.7f);
    }

    /**
     * Hits every living entity within {@link RailgunConfig#BEAM_HIT_RADIUS} of the beam segment.
     * The segment can be hundreds of blocks long, so instead of one enormous AABB query we sweep
     * small boxes along it and de-duplicate.
     */
    private void damageAlongBeam(ServerWorld world, Vec3d from, Vec3d to) {
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
            Box box = new Box(a, b).expand(r);
            hit.addAll(world.getEntitiesByClass(LivingEntity.class, box, LivingEntity::isAlive));
        }

        for (LivingEntity victim : hit) {
            Vec3d c = victim.getBoundingBox().getCenter();
            double along = MathHelper.clamp(c.subtract(from).dotProduct(dir), 0, length);
            double dist = c.distanceTo(from.add(dir.multiply(along)));
            if (dist <= RailgunConfig.BEAM_HIT_RADIUS + victim.getWidth() * 0.5) {
                victim.damage(source, RailgunConfig.BEAM_DAMAGE);
                victim.setOnFireFor(6);
            }
        }
    }

    private void explode(ServerWorld world, Vec3d pos, float power) {
        world.createExplosion(this, pos.x, pos.y, pos.z, power,
                RailgunConfig.BREAK_BLOCKS ? World.ExplosionSourceType.TNT : World.ExplosionSourceType.NONE);
    }

    // =========================================================================================
    // Client-side cosmetics
    // =========================================================================================

    private void clientTick(RailgunState state, int t) {
        World world = getWorld();
        var rand = this.random;
        switch (state) {
            case FALLING -> {
                // Re-entry plasma + smoke trail streaming above the falling pod.
                for (int i = 0; i < 6; i++) {
                    double ox = (rand.nextDouble() - 0.5) * 2.5, oz = (rand.nextDouble() - 0.5) * 2.5;
                    world.addParticle(ParticleTypes.FLAME, getX() + ox, getY() + rand.nextDouble() * 1.5, getZ() + oz, 0, 0.6, 0);
                    world.addParticle(ParticleTypes.LARGE_SMOKE, getX() + ox, getY() + 3.0, getZ() + oz, 0, 0.4, 0);
                }
            }
            case CHARGING -> {
                float charge = getCharge();
                Vec3d muzzle = getMuzzlePos(1f);
                int count = 1 + (int) (charge * 6);
                DustParticleEffect red = new DustParticleEffect(new Vector3f(1f, 0.08f, 0.05f), 1.0f + charge);
                for (int i = 0; i < count; i++) {
                    // Particles spawn on a shell and get sucked into the muzzle.
                    double rx = (rand.nextDouble() - 0.5) * 3, ry = (rand.nextDouble() - 0.5) * 3, rz = (rand.nextDouble() - 0.5) * 3;
                    world.addParticle(red, muzzle.x + rx, muzzle.y + ry, muzzle.z + rz, -rx * 0.15, -ry * 0.15, -rz * 0.15);
                    if (rand.nextFloat() < charge) {
                        world.addParticle(ParticleTypes.ELECTRIC_SPARK,
                                getX() + (rand.nextDouble() - 0.5) * 3, getY() + 1 + rand.nextDouble() * 2.5,
                                getZ() + (rand.nextDouble() - 0.5) * 3, 0, 0, 0);
                    }
                }
            }
            case COOLDOWN -> {
                if (t % 2 == 0) {
                    // Coolant venting from the base.
                    for (int i = 0; i < 2; i++) {
                        double a = rand.nextDouble() * Math.PI * 2;
                        world.addParticle(ParticleTypes.CLOUD, getX() + Math.cos(a) * 1.4, getY() + 0.8,
                                getZ() + Math.sin(a) * 1.4, Math.cos(a) * 0.08, 0.05, Math.sin(a) * 0.08);
                    }
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

    /** World-space position of the muzzle: pivot + aim direction * barrel length. */
    public Vec3d getMuzzlePos(float tickDelta) {
        float yaw = getAimYawRad(tickDelta);
        float pitch = getAimPitchRad(tickDelta);
        double cosPitch = Math.cos(pitch);
        Vec3d dir = new Vec3d(Math.sin(yaw) * cosPitch, Math.sin(pitch), Math.cos(yaw) * cosPitch);
        return getLerpedPos(tickDelta).add(0, RailgunConfig.PIVOT_Y, 0).add(dir.multiply(RailgunConfig.BARREL_LENGTH));
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
        getWorld().playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_POWER_DOWN, SoundCategory.BLOCKS, 1.5f, 1.2f);
        return true;
    }

    /** Returns the platform to orbit (removes it with a light show). */
    public void recall() {
        if (getWorld() instanceof ServerWorld world) {
            world.spawnParticles(ParticleTypes.END_ROD, getX(), getY() + 2, getZ(), 80, 1.0, 2.0, 1.0, 0.25);
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY() + 1, getZ(), 120, 1.5, 1.5, 1.5, 0.1);
            world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_POWER_DOWN, SoundCategory.BLOCKS, 2.0f, 0.6f);
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
    public boolean canHit() {
        return !isRemoved();
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public boolean shouldRender(double distance) {
        if (isBeamActive()) return true;
        double max = 256.0;
        return distance < max * max;
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
