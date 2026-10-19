package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.PlanetState;
import com.creedpdf.orbitalrailgun.util.LandingSurvey;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.UUID;

/**
 * ARES-0: the planet killer. Dropped from orbit like ARES-9, it plants six legs, raises a
 * thirty-block tri-rail accelerator and fires a beam from Earth into a planet in the sky. The
 * beam bores to the core, the core collapses and the planet explodes (see {@link Blast}).
 */
public class Ares0Entity extends Entity implements GeoEntity {
    public enum State { FALLING, LANDING, DEPLOYING, IDLE, CHARGING, FIRING, COOLDOWN, LEAVING }

    public static final int LANDING_TICKS = 30, DEPLOY_TICKS = 200, CHARGE_TICKS = 180, FIRE_TICKS = Blast.DETONATE + 25,
            COOLDOWN_TICKS = 240, LEAVE_TICKS = 160;
    /** Model geometry (blocks): yaw pivot height, pitch pivot height, muzzle distance along the barrel. */
    public static final double PITCH_Y = 6.5, BARREL_LENGTH = 31.0;

    private static final TrackedData<Integer> STATE = DataTracker.registerData(Ares0Entity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> TARGET = DataTracker.registerData(Ares0Entity.class, TrackedDataHandlerRegistry.INTEGER);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int stateTicks;
    private double landY = Double.NaN;
    @Nullable private UUID owner;
    private boolean chunkForced;

    // aim (degrees), smoothed on both sides
    private float aimYaw, aimPitch = 90f, prevAimYaw, prevAimPitch = 90f;

    public Ares0Entity(EntityType<?> type, World world) {
        super(type, world);
        this.ignoreCameraFrustum = true;
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(STATE, State.FALLING.ordinal());
        this.dataTracker.startTracking(TARGET, -1);
    }

    // ---- accessors ------------------------------------------------------------------------------

    public State getState() {
        return State.values()[MathHelper.clamp(this.dataTracker.get(STATE), 0, State.values().length - 1)];
    }

    public void setState(State s) {
        this.dataTracker.set(STATE, s.ordinal());
        this.stateTicks = 0;
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (STATE.equals(data)) this.stateTicks = 0;
    }

    public int getStateTicks() {
        return stateTicks;
    }

    @Nullable
    public Body getTarget() {
        int t = this.dataTracker.get(TARGET);
        return t >= 0 && t < Body.ALL.length ? Body.ALL[t] : null;
    }

    public void setTarget(@Nullable Body b) {
        this.dataTracker.set(TARGET, b == null ? -1 : b.ordinal());
    }

    @Nullable
    public UUID getOwnerUuid() {
        return owner;
    }

    public void setOwnerUuid(UUID uuid) {
        this.owner = uuid;
    }

    public void setLandY(double y) {
        this.landY = y;
    }

    public float getAimYaw(float pt) {
        return MathHelper.lerpAngleDegrees(pt, prevAimYaw, aimYaw);
    }

    public float getAimPitch(float pt) {
        return MathHelper.lerp(pt, prevAimPitch, aimPitch);
    }

    /** Pitch pivot in world space. */
    public Vec3d getPivot(float pt) {
        return getLerpedPos(pt).add(0, PITCH_Y, 0);
    }

    /** Barrel direction (same convention as ARES-9: yaw 0 = +Z, pitch up positive). */
    public Vec3d getBarrelDir(float pt) {
        double yaw = Math.toRadians(getAimYaw(pt)), pitch = Math.toRadians(getAimPitch(pt));
        return new Vec3d(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    public Vec3d getMuzzlePos(float pt) {
        return getPivot(pt).add(getBarrelDir(pt).multiply(BARREL_LENGTH));
    }

    public boolean isBeamActive() {
        return getState() == State.FIRING;
    }

    /** Sky direction of the current target, or null. */
    @Nullable
    public Vec3d targetDirection(float pt) {
        Body b = getTarget();
        if (b == null) return null;
        return b.skyDirection(getWorld().getSkyAngle(pt));
    }

    public static boolean visible(World world, Body b) {
        return b.skyDirection(world.getSkyAngle(1f)).y > 0.08;
    }

    // ---- ticking --------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        stateTicks++;
        prevAimYaw = aimYaw;
        prevAimPitch = aimPitch;
        updateAim();
        if (getWorld().isClient) {
            clientTick();
            return;
        }
        ServerWorld world = (ServerWorld) getWorld();
        int t = stateTicks;
        switch (getState()) {
            case FALLING -> tickFalling(world);
            case LANDING -> { if (t >= LANDING_TICKS) setState(State.DEPLOYING); }
            case DEPLOYING -> {
                if (t == 10) world.playSound(null, getX(), getY() + 4, getZ(), ModSounds.ARES0_DEPLOY, SoundCategory.BLOCKS, 6f, 1f);
                if (t == 70) RailgunFx.burst(world, ModParticles.SHOCK_DUST, getPos().add(0, 0.5, 0), 120, 7, 0.4, 0.15);
                if (t >= DEPLOY_TICKS) {
                    setState(State.IDLE);
                    notifyOwner(world, Text.translatable("message.orbital_railgun.ares0.ready").formatted(Formatting.AQUA));
                }
            }
            case CHARGING -> tickCharging(world, t);
            case FIRING -> tickFiring(world, t);
            case COOLDOWN -> { if (t >= COOLDOWN_TICKS) setState(State.IDLE); }
            case LEAVING -> tickLeaving(world, t);
            default -> {}
        }
    }

    private void updateAim() {
        float targetYaw = aimYaw, targetPitch;
        State s = getState();
        int t = stateTicks;
        Vec3d dir = targetDirection(1f);
        switch (s) {
            case FALLING, LANDING -> targetPitch = 90f;
            case DEPLOYING -> targetPitch = t < 120 ? 90f : 35f;
            case LEAVING -> targetPitch = 90f;
            default -> {
                if (dir != null) {
                    targetYaw = (float) Math.toDegrees(Math.atan2(dir.x, dir.z));
                    targetPitch = (float) Math.toDegrees(Math.asin(MathHelper.clamp(dir.y, -1, 1)));
                    targetPitch = MathHelper.clamp(targetPitch, 8f, 90f);
                } else {
                    targetPitch = 35f;
                }
            }
        }
        float rate = s == State.CHARGING || s == State.FIRING ? 1.2f : 0.6f;
        float dy = MathHelper.wrapDegrees(targetYaw - aimYaw);
        aimYaw += MathHelper.clamp(dy, -rate * 1.5f, rate * 1.5f);
        aimPitch += MathHelper.clamp(targetPitch - aimPitch, -rate, rate);
    }

    private void tickFalling(ServerWorld world) {
        this.noClip = true;
        double vy = Math.max(getVelocity().y - 0.12, -2.8);
        setVelocity(0, vy, 0);
        if (Double.isNaN(landY)) landY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, getBlockX(), getBlockZ());
        double next = getY() + vy;
        if (next - landY < 70 && stateTicks % 2 == 0) {
            RailgunFx.burst(world, ModParticles.THRUSTER_FLAME, getPos(), 18, 1.6, 0.4, 0.25);
        }
        if (next <= landY) {
            setPosition(getX(), landY, getZ());
            setVelocity(Vec3d.ZERO);
            this.noClip = false;
            int cleared = LandingSurvey.clearPlatform(world, BlockPos.ofFloored(getX(), landY, getZ()));
            Vec3d base = getPos().add(0, 0.5, 0);
            RailgunFx.burst(world, ModParticles.FLASH, base.add(0, 2, 0), 1, 0.5, 0.5, 0);
            RailgunFx.burst(world, ModParticles.SHOCK_DUST, base, 160 + Math.min(200, cleared), 8, 1, 0.3);
            RailgunFx.burst(world, ModParticles.DEBRIS, base.add(0, 1, 0), 80, 4, 1, 0.4);
            world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_LAND, SoundCategory.BLOCKS, 12f, 0.7f);
            setChunkForced(world, true);
            setState(State.LANDING);
        } else {
            setPosition(getX(), next, getZ());
        }
    }

    public boolean tryFire(ServerPlayerEntity player, Body body) {
        if (getState() != State.IDLE) return false;
        if (!body.destroyable()) return false;
        PlanetState ps = PlanetState.get(player.getServer());
        if (ps.doomed(body)) return false;
        if (!visible(getWorld(), body)) return false;
        setTarget(body);
        setState(State.CHARGING);
        getWorld().playSound(null, getX(), getY() + 6, getZ(), ModSounds.ARES0_CHARGE, SoundCategory.BLOCKS, 10f, 1f);
        return true;
    }

    public void select(Body body) {
        if (getState() == State.IDLE || getState() == State.COOLDOWN) setTarget(body);
    }

    private void tickCharging(ServerWorld world, int t) {
        Body b = getTarget();
        if (b == null || !visible(world, b)) {
            setState(State.IDLE);
            notifyOwner(world, Text.translatable("message.orbital_railgun.ares0.lost").formatted(Formatting.RED));
            return;
        }
        if (t % 10 == 0) RailgunFx.burst(world, ModParticles.ENERGY_MOTE, getMuzzlePos(1f), 12, 1.2, 1.2, 0.05);
        if (t >= CHARGE_TICKS) {
            setState(State.FIRING);
            Vec3d m = getMuzzlePos(1f);
            world.playSound(null, m.x, m.y, m.z, ModSounds.ARES0_FIRE, SoundCategory.BLOCKS, 40f, 1f);
            RailgunFx.burst(world, ModParticles.FLASH, m, 2, 0.5, 0.5, 0);
            PlanetState.get(world.getServer()).start(world.getServer(), b, world.getTime());
            for (ServerPlayerEntity p : world.getServer().getPlayerManager().getPlayerList()) {
                p.sendMessage(Text.translatable("message.orbital_railgun.ares0.firing", b.displayName()).formatted(Formatting.RED), false);
            }
        }
    }

    private void tickFiring(ServerWorld world, int t) {
        Vec3d m = getMuzzlePos(1f);
        if (t % 3 == 0) RailgunFx.burst(world, ModParticles.PLASMA_SPARK, m, 10, 0.8, 0.8, 0.4);
        if (t % 5 == 0) RailgunFx.burst(world, ModParticles.SHOCK_DUST, getPos().add(0, 0.3, 0), 30, 6, 0.3, 0.1);
        if (t >= FIRE_TICKS) {
            setState(State.COOLDOWN);
            setTarget(null);
        }
    }

    public void beginLeave() {
        if (getState() == State.IDLE || getState() == State.COOLDOWN) {
            setState(State.LEAVING);
            getWorld().playSound(null, getX(), getY() + 4, getZ(), ModSounds.ARES0_DEPLOY, SoundCategory.BLOCKS, 6f, 0.8f);
        }
    }

    private void tickLeaving(ServerWorld world, int t) {
        if (t < 60) return;
        this.noClip = true;
        double v = Math.min(3.5, (t - 60) * 0.06);
        setPosition(getX(), getY() + v, getZ());
        if (t % 2 == 0) RailgunFx.burst(world, ModParticles.THRUSTER_FLAME, getPos(), 20, 1.6, 0.2, 0.2);
        if (t == 62) world.playSound(null, getX(), getY(), getZ(), ModSounds.RAILGUN_LAUNCH, SoundCategory.BLOCKS, 12f, 0.7f);
        if (t >= LEAVE_TICKS + 60) {
            setChunkForced(world, false);
            discard();
        }
    }

    private void clientTick() {
        World w = getWorld();
        if (stateTicks == 1 && getState() == State.LANDING) RailgunMod.client().shakeAt(getPos(), 2.0f, 90);
        if (stateTicks == 1 && getState() == State.FIRING) RailgunMod.client().shakeAt(getPos(), 2.6f, 200);
        if (getState() == State.FIRING && stateTicks % 4 == 0) RailgunMod.client().shakeAt(getPos(), 0.35f, 120);
        net.minecraft.util.math.random.Random r = w.random;
        if (getState() == State.CHARGING) {
            Vec3d m = getMuzzlePos(1f);
            float f = Math.min(1f, stateTicks / (float) CHARGE_TICKS);
            for (int i = 0; i < 2 + (int) (6 * f); i++) {
                Vec3d p = m.add((r.nextDouble() - 0.5) * 8, (r.nextDouble() - 0.5) * 8, (r.nextDouble() - 0.5) * 8);
                Vec3d v = m.subtract(p).multiply(0.12);
                w.addParticle(ModParticles.ENERGY_MOTE, p.x, p.y, p.z, v.x, v.y, v.z);
            }
        }
        if (getState() == State.FALLING && r.nextInt(2) == 0) {
            w.addParticle(ModParticles.REENTRY_FIRE, getX() + (r.nextDouble() - 0.5) * 4, getY() + r.nextDouble() * 6,
                    getZ() + (r.nextDouble() - 0.5) * 4, 0, 0.6, 0);
        }
    }

    private void notifyOwner(ServerWorld world, Text msg) {
        if (owner == null) return;
        ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(owner);
        if (p != null) p.sendMessage(msg, true);
    }

    private void setChunkForced(ServerWorld world, boolean forced) {
        if (chunkForced == forced) return;
        ChunkPos c = getChunkPos();
        world.setChunkForced(c.x, c.z, forced);
        chunkForced = forced;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!getWorld().isClient && reason.shouldDestroy()) setChunkForced((ServerWorld) getWorld(), false);
        super.remove(reason);
    }

    // ---- interaction ----------------------------------------------------------------------------

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (getState() == State.FALLING || getState() == State.LEAVING) return ActionResult.PASS;
        if (getWorld().isClient) {
            RailgunMod.client().openAres0(this);
            return ActionResult.SUCCESS;
        }
        return ActionResult.CONSUME;
    }

    @Override
    public boolean canHit() {
        return true;
    }

    @Override
    public boolean isCollidable() {
        return getState() != State.FALLING && getState() != State.LEAVING;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 1024 * 1024;
    }

    public static List<Ares0Entity> owned(MinecraftServer server, UUID owner) {
        java.util.ArrayList<Ares0Entity> out = new java.util.ArrayList<>();
        for (ServerWorld w : server.getWorlds()) {
            out.addAll(w.getEntitiesByType(RailgunMod.ARES0, e -> e.isAlive() && owner.equals(e.owner)));
        }
        return out;
    }

    // ---- persistence ----------------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        setState(State.values()[MathHelper.clamp(nbt.getInt("State"), 0, State.values().length - 1)]);
        this.stateTicks = nbt.getInt("StateTicks");
        if (nbt.containsUuid("Owner")) owner = nbt.getUuid("Owner");
        landY = nbt.contains("LandY") ? nbt.getDouble("LandY") : Double.NaN;
        aimYaw = prevAimYaw = nbt.getFloat("AimYaw");
        aimPitch = prevAimPitch = nbt.contains("AimPitch") ? nbt.getFloat("AimPitch") : 90f;
        this.dataTracker.set(TARGET, nbt.contains("Target") ? nbt.getInt("Target") : -1);
        chunkForced = nbt.getBoolean("Forced");
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("State", getState().ordinal());
        nbt.putInt("StateTicks", stateTicks);
        if (owner != null) nbt.putUuid("Owner", owner);
        if (!Double.isNaN(landY)) nbt.putDouble("LandY", landY);
        nbt.putFloat("AimYaw", aimYaw);
        nbt.putFloat("AimPitch", aimPitch);
        nbt.putInt("Target", this.dataTracker.get(TARGET));
        nbt.putBoolean("Forced", chunkForced);
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    // ---- GeckoLib -------------------------------------------------------------------------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
