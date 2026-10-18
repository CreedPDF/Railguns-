package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.LandingSurvey;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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

/**
 * Orbital storage supply capsule. Carries the loadout ordered on a pod's panel down from orbit,
 * lands beside the pod, opens, transfers the attachments into the railgun along a hard-light
 * stream, closes and returns to orbit.
 *
 * <pre>DESCENT -> LANDED -> OPEN -> TRANSFER -> CLOSE -> ASCENT</pre>
 */
public class SupplyCapsuleEntity extends Entity implements GeoEntity {

    public enum Phase {
        DESCENT(0), LANDED(16), OPEN(24), TRANSFER(56), CLOSE(20), ASCENT(160);
        public final int ticks;
        Phase(int ticks) { this.ticks = ticks; }
    }

    private static final TrackedData<Integer> PHASE = DataTracker.registerData(SupplyCapsuleEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> RAILGUN = DataTracker.registerData(SupplyCapsuleEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> ORDER = DataTracker.registerData(SupplyCapsuleEntity.class, TrackedDataHandlerRegistry.INTEGER);

    public static final double DROP_HEIGHT = 140;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private int phaseTicks;
    private double landY = Double.NaN;
    private double speed;

    public SupplyCapsuleEntity(EntityType<? extends SupplyCapsuleEntity> type, World world) {
        super(type, world);
        this.noClip = true;
    }

    /** Spawns a capsule above a free spot beside the pod. */
    @Nullable
    public static SupplyCapsuleEntity dispatch(ServerWorld world, RailgunEntity railgun, int order) {
        BlockPos spot = pickSpot(world, railgun);
        if (spot == null) return null;
        SupplyCapsuleEntity c = RailgunMod.SUPPLY_CAPSULE.create(world);
        if (c == null) return null;
        double y = Math.min(spot.getY() + DROP_HEIGHT, world.getTopY() - 4);
        c.refreshPositionAndAngles(spot.getX() + 0.5, y, spot.getZ() + 0.5, 0f, 0f);
        c.landY = spot.getY();
        c.speed = 1.2;
        c.dataTracker.set(RAILGUN, railgun.getId());
        c.dataTracker.set(ORDER, order);
        world.spawnEntity(c);
        world.playSound(null, spot.getX() + 0.5, spot.getY() + 2, spot.getZ() + 0.5, ModSounds.CAPSULE_REENTRY, SoundCategory.BLOCKS, 10f, 1f);
        return c;
    }

    /** A landing spot about 5.5 blocks from the pod (left/right first, never in front of the panel). */
    @Nullable
    private static BlockPos pickSpot(ServerWorld world, RailgunEntity railgun) {
        int[][] offsets = {{6, 0}, {-6, 0}, {0, 6}, {5, 5}, {-5, 5}, {0, -6}};
        BlockPos base = railgun.getBlockPos();
        for (int[] o : offsets) {
            int x = base.getX() + o[0], z = base.getZ() + o[1];
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - base.getY()) > 4) continue;
            BlockPos ground = new BlockPos(x, y - 1, z);
            BlockState s = world.getBlockState(ground);
            if (!s.getFluidState().isEmpty()) continue;
            return new BlockPos(x, y, z);
        }
        return new BlockPos(base.getX() + 6, base.getY(), base.getZ());
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(PHASE, 0);
        dataTracker.startTracking(RAILGUN, -1);
        dataTracker.startTracking(ORDER, 0);
    }

    public Phase getPhase() {
        int i = dataTracker.get(PHASE);
        return Phase.values()[MathHelper.clamp(i, 0, Phase.values().length - 1)];
    }

    private void setPhase(Phase p) {
        dataTracker.set(PHASE, p.ordinal());
        phaseTicks = 0;
    }

    public int getPhaseTicks() { return phaseTicks; }
    public int getOrder() { return dataTracker.get(ORDER); }

    @Nullable
    public RailgunEntity getRailgun() {
        return getWorld().getEntityById(dataTracker.get(RAILGUN)) instanceof RailgunEntity r ? r : null;
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (PHASE.equals(data) && getWorld().isClient) phaseTicks = 0;
    }

    @Override
    public void tick() {
        super.tick();
        Phase phase = getPhase();
        int t = phaseTicks++;
        if (getWorld().isClient) {
            clientTick(phase, t);
            return;
        }
        ServerWorld w = (ServerWorld) getWorld();
        RailgunEntity rg = getRailgun();
        switch (phase) {
            case DESCENT -> {
                if (Double.isNaN(landY)) landY = getY() - DROP_HEIGHT;
                double toGo = getY() - landY;
                // Free-fall, then a hard retro burn over the last 22 blocks.
                speed = toGo > 22 ? Math.min(3.2, speed + 0.12) : Math.max(0.35, speed * 0.86);
                double ny = Math.max(landY, getY() - speed);
                setPosition(getX(), ny, getZ());
                if (toGo < 12) smashColumn(w, BlockPos.ofFloored(getX(), ny, getZ()));
                if (ny <= landY + 1e-3) {
                    smashColumn(w, BlockPos.ofFloored(getX(), landY, getZ()));
                    w.playSound(null, getX(), getY(), getZ(), ModSounds.CAPSULE_LAND, SoundCategory.BLOCKS, 6f, 1f);
                    RailgunFx.burst(w, ModParticles.COOLANT_VAPOR, getPos().add(0, 0.5, 0), 40, 2.0, 0.3, 0.12);
                    RailgunFx.burst(w, ModParticles.SHOCK_DUST, getPos().add(0, 0.3, 0), 30, 2.0, 0.1, 0.25);
                    setPhase(Phase.LANDED);
                }
            }
            case LANDED -> {
                if (t >= Phase.LANDED.ticks) {
                    w.playSound(null, getX(), getY(), getZ(), ModSounds.CAPSULE_OPEN, SoundCategory.BLOCKS, 3f, 1f);
                    setPhase(Phase.OPEN);
                }
            }
            case OPEN -> {
                if (t >= Phase.OPEN.ticks) {
                    w.playSound(null, getX(), getY(), getZ(), ModSounds.CAPSULE_TRANSFER, SoundCategory.BLOCKS, 3f, 1f);
                    setPhase(Phase.TRANSFER);
                }
            }
            case TRANSFER -> {
                if (rg != null && t % 2 == 0) {
                    Vec3d from = cargoPos();
                    Vec3d to = rg.getPos().add(0, 3.2, 0);
                    Vec3d d = to.subtract(from);
                    RailgunFx.launch(w, ModParticles.TRANSFER_MOTE, from, d.multiply(1 / 14.0));
                }
                if (t >= Phase.TRANSFER.ticks) {
                    if (rg != null && rg.isAlive()) rg.receiveLoadout(getOrder());
                    setPhase(Phase.CLOSE);
                }
            }
            case CLOSE -> {
                if (t >= Phase.CLOSE.ticks) {
                    w.playSound(null, getX(), getY(), getZ(), ModSounds.CAPSULE_LAUNCH, SoundCategory.BLOCKS, 8f, 1f);
                    RailgunFx.burst(w, ModParticles.THRUSTER_FLAME, getPos(), 30, 1.0, 0.2, 0.2);
                    speed = 0.05;
                    setPhase(Phase.ASCENT);
                }
            }
            case ASCENT -> {
                speed = Math.min(3.5, speed + 0.06 + t * 0.002);
                setPosition(getX(), getY() + speed, getZ());
                if (t > Phase.ASCENT.ticks || getY() > getWorld().getTopY() + 32) discard();
            }
        }
        // Never leave a pod waiting if its railgun vanished.
        if (rg == null && phase.ordinal() <= Phase.TRANSFER.ordinal() && phase != Phase.DESCENT && t > 5) setPhase(Phase.CLOSE);
    }

    public Vec3d cargoPos() {
        float lift = getPhase() == Phase.TRANSFER ? Math.min(1f, phaseTicks / 12f) : getPhase() == Phase.CLOSE ? 1f - Math.min(1f, phaseTicks / 10f) : 0f;
        return getPos().add(0, 1.6 + lift * 1.4, 0);
    }

    private void smashColumn(ServerWorld w, BlockPos center) {
        for (BlockPos p : BlockPos.iterate(center.add(-1, 0, -1), center.add(1, 3, 1))) {
            BlockState s = w.getBlockState(p);
            if (s.isAir() || LandingSurvey.unbreakable(w, p, s) || s.isOf(RailgunMod.RAILGUN_COLLIDER)) continue;
            w.setBlockState(p, Blocks.AIR.getDefaultState(), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.SKIP_DROPS);
            RailgunFx.burst(w, ModParticles.DEBRIS, Vec3d.ofCenter(p), 4, 0.4, 0.4, 0.3);
        }
    }

    private void clientTick(Phase phase, int t) {
        World w = getWorld();
        Random r = this.random;
        if (phase == Phase.DESCENT) {
            double toGo = Double.isNaN(landY) ? 99 : getY() - landY;
            boolean retro = getVelocityApprox() < 2.5;
            for (int i = 0; i < 3; i++) {
                w.addParticle(retro ? ModParticles.THRUSTER_FLAME : ModParticles.THERMITE_FLAME,
                        getX() + (r.nextDouble() - 0.5) * 1.2, getY() + (retro ? -0.2 : 3.6 + r.nextDouble()),
                        getZ() + (r.nextDouble() - 0.5) * 1.2, 0, retro ? -0.8 : 0.6, 0);
            }
            if (t % 2 == 0) w.addParticle(ModParticles.COOLANT_VAPOR, getX(), getY() + 4.5, getZ(), 0, 0.3, 0);
        } else if (phase == Phase.ASCENT) {
            for (int i = 0; i < 4; i++) {
                w.addParticle(ModParticles.THRUSTER_FLAME, getX() + (r.nextDouble() - 0.5) * 0.8, getY() - 0.3,
                        getZ() + (r.nextDouble() - 0.5) * 0.8, 0, -1.0 - r.nextDouble(), 0);
            }
        } else if (phase == Phase.TRANSFER && t % 3 == 0) {
            Vec3d c = cargoPos();
            w.addParticle(ModParticles.LENS_GLINT, c.x, c.y, c.z, 0, 0, 0);
        } else if (phase == Phase.OPEN && t < 6) {
            w.addParticle(ModParticles.COOLANT_VAPOR, getX() + (r.nextDouble() - 0.5) * 2, getY() + 1.5, getZ() + (r.nextDouble() - 0.5) * 2,
                    (r.nextDouble() - 0.5) * 0.2, 0.05, (r.nextDouble() - 0.5) * 0.2);
        }
    }

    private double lastY = Double.NaN, lastSpeed;

    private double getVelocityApprox() {
        if (!Double.isNaN(lastY)) lastSpeed = lastY - getY();
        lastY = getY();
        return lastSpeed;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 256 * 256;
    }

    @Override
    public Box getVisibilityBoundingBox() {
        return getBoundingBox().expand(12, 6, 12); // transfer stream reaches the pod
    }

    @Override
    public boolean isCollidable() {
        return getPhase() != Phase.DESCENT && getPhase() != Phase.ASCENT;
    }

    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        dataTracker.set(PHASE, nbt.getInt("Phase"));
        dataTracker.set(ORDER, nbt.getInt("Order"));
        phaseTicks = nbt.getInt("PhaseTicks");
        landY = nbt.contains("LandY") ? nbt.getDouble("LandY") : Double.NaN;
        speed = nbt.getDouble("Speed");
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("Phase", dataTracker.get(PHASE));
        nbt.putInt("Order", getOrder());
        nbt.putInt("PhaseTicks", phaseTicks);
        if (!Double.isNaN(landY)) nbt.putDouble("LandY", landY);
        nbt.putDouble("Speed", speed);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Petals, legs and cargo cradle are posed procedurally from (phase, phaseTicks).
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
