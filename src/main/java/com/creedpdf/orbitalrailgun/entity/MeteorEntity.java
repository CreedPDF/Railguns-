package com.creedpdf.orbitalrailgun.entity;

import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Body;
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
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * A burning chunk of a planet ARES-0 destroyed, falling to Earth. It streaks in at a steep angle,
 * and where it lands it blasts a crater and leaves a heap of that planet's rock (moon basalt and
 * tuff, Martian red rock, Venusian blackstone and magma, Europan ice, gas-giant core metal).
 */
public class MeteorEntity extends Entity {
    private static final TrackedData<Float> SIZE = DataTracker.registerData(MeteorEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Integer> BODY = DataTracker.registerData(MeteorEntity.class, TrackedDataHandlerRegistry.INTEGER);

    public MeteorEntity(EntityType<? extends MeteorEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.ignoreCameraFrustum = true;
    }

    public void setup(Body body, float size, Vec3d velocity) {
        dataTracker.set(BODY, body.ordinal());
        dataTracker.set(SIZE, size);
        setVelocity(velocity);
        velocityDirty = true;
    }

    public float size() {
        return dataTracker.get(SIZE);
    }

    public Body body() {
        int i = dataTracker.get(BODY);
        return Body.values()[MathHelper.clamp(i, 0, Body.values().length - 1)];
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(SIZE, 1f);
        dataTracker.startTracking(BODY, Body.MOON.ordinal());
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 1024 * 1024;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        Vec3d v = getVelocity().add(0, -0.04, 0);
        setVelocity(v);
        Vec3d pos = getPos();
        Vec3d next = pos.add(v);
        World w = getWorld();
        if (w.isClient) {
            trail(w, pos, v);
            setPosition(next);
            return;
        }
        if (age == 1) {
            w.playSound(null, getX(), getY(), getZ(), ModSounds.METEOR_FALL, SoundCategory.WEATHER, 6f + size() * 2f, 0.8f + 0.25f * (2.5f - size()) / 2.5f);
        }
        BlockHitResult hit = w.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, this));
        if (hit.getType() == HitResult.Type.BLOCK) {
            impact((ServerWorld) w, hit.getPos());
            return;
        }
        if (next.y < w.getBottomY() || age > 600) {
            discard();
            return;
        }
        setPosition(next);
    }

    private void trail(World w, Vec3d pos, Vec3d v) {
        Random r = w.random;
        float s = size();
        int n = com.creedpdf.orbitalrailgun.util.FxBudget.count(4 + (int) (s * 4), 2);
        for (int i = 0; i < n; i++) {
            double f = r.nextDouble();
            Vec3d p = pos.add(v.multiply(-f)).add((r.nextDouble() - 0.5) * s, (r.nextDouble() - 0.5) * s, (r.nextDouble() - 0.5) * s);
            w.addParticle(ModParticles.REENTRY_FIRE, true, p.x, p.y, p.z, v.x * 0.1, v.y * 0.1, v.z * 0.1);
            if (i % 2 == 0) w.addParticle(ParticleTypes.LARGE_SMOKE, true, p.x, p.y, p.z, 0, 0.02, 0);
        }
        if (r.nextInt(3) == 0) w.addParticle(ParticleTypes.LAVA, true, pos.x, pos.y, pos.z, 0, 0, 0);
        // long-lived smoke column: sparse, it lingers anyway
        if (age % Math.max(2, Math.round(3 / Math.max(0.25f, com.creedpdf.orbitalrailgun.util.FxBudget.particles))) == 0)
            w.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, pos.x, pos.y, pos.z, 0, 0.01, 0);
    }

    /** Shot down in the air by an interceptor: it bursts into burning fragments and smoke, and never lands. */
    public void intercept(ServerWorld w) {
        if (isRemoved()) return;
        float s = size();
        Vec3d at = getPos();
        w.playSound(null, at.x, at.y, at.z, ModSounds.METEOR_IMPACT, SoundCategory.WEATHER, 6f + s * 2f, 1.1f + 0.2f * w.random.nextFloat());
        w.spawnParticles(ModParticles.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        w.spawnParticles(ModParticles.REENTRY_FIRE, at.x, at.y, at.z, 60, s * 1.5, s * 1.5, s * 1.5, 0.35);
        w.spawnParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 20, s, s, s, 0.2);
        w.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 40, s * 2, s * 2, s * 2, 0.08);
        w.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, at.x, at.y, at.z, 10, s, s, s, 0.02);
        discard();
    }

    private void impact(ServerWorld w, Vec3d at) {
        float s = size();
        Body b = body();
        w.createExplosion(this, at.x, at.y, at.z, Math.min(6.5f, 2.5f + s * 1.8f), true, World.ExplosionSourceType.TNT);
        w.playSound(null, at.x, at.y, at.z, ModSounds.METEOR_IMPACT, SoundCategory.WEATHER, 8f + s * 3f, 0.75f + 0.3f * w.random.nextFloat());
        w.spawnParticles(ModParticles.FLASH, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
        w.spawnParticles(ModParticles.SHOCK_DUST, at.x, at.y + 1, at.z, 40, s * 2, 0.5, s * 2, 0.15);
        w.spawnParticles(ParticleTypes.LAVA, at.x, at.y + 1, at.z, 16, s, 0.5, s, 0.1);
        w.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, at.x, at.y + 1, at.z, 8, s, 1, s, 0.02);
        // a heap of alien rock in the crater
        Random r = w.random;
        BlockPos c = BlockPos.ofFloored(at);
        int count = (int) (10 + s * 18);
        int reach = (int) Math.ceil(1.5 + s * 1.6);
        for (int i = 0; i < count; i++) {
            int dx = r.nextInt(reach * 2 + 1) - reach, dz = r.nextInt(reach * 2 + 1) - reach;
            if (dx * dx + dz * dz > reach * reach) continue;
            BlockPos p = c.add(dx, reach + 2, dz);
            for (int k = 0; k < reach * 3 + 6; k++) {
                BlockPos below = p.down();
                if (!w.getBlockState(below).isAir() && !w.getBlockState(below).isReplaceable()) break;
                p = below;
            }
            if (w.getBlockState(p).isAir() || w.getBlockState(p).isReplaceable()) w.setBlockState(p, material(b, r));
            if (r.nextInt(5) == 0 && w.getBlockState(p.up()).isAir()) w.setBlockState(p.up(), Blocks.FIRE.getDefaultState());
        }
        w.setBlockState(c.up(), material(b, r));
        discard();
    }

    private static BlockState material(Body b, Random r) {
        int k = r.nextInt(10);
        return switch (b) {
            case MOON -> (k < 4 ? Blocks.SMOOTH_BASALT : k < 8 ? Blocks.TUFF : Blocks.MAGMA_BLOCK).getDefaultState();
            case MARS -> (k < 4 ? Blocks.RED_TERRACOTTA : k < 7 ? Blocks.RED_SANDSTONE : k < 9 ? Blocks.TERRACOTTA : Blocks.MAGMA_BLOCK).getDefaultState();
            case VENUS -> (k < 4 ? Blocks.BLACKSTONE : k < 7 ? Blocks.BASALT : Blocks.MAGMA_BLOCK).getDefaultState();
            case EUROPA -> (k < 5 ? Blocks.PACKED_ICE : k < 8 ? Blocks.BLUE_ICE : Blocks.MAGMA_BLOCK).getDefaultState();
            case JUPITER -> (k < 3 ? Blocks.RAW_IRON_BLOCK : k < 6 ? Blocks.CRYING_OBSIDIAN : k < 8 ? Blocks.OBSIDIAN : Blocks.MAGMA_BLOCK).getDefaultState();
            case SATURN -> (k < 3 ? Blocks.RAW_GOLD_BLOCK : k < 6 ? Blocks.OBSIDIAN : k < 8 ? Blocks.CALCITE : Blocks.MAGMA_BLOCK).getDefaultState();
            default -> Blocks.MAGMA_BLOCK.getDefaultState();
        };
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        dataTracker.set(SIZE, nbt.getFloat("size"));
        dataTracker.set(BODY, nbt.getInt("body"));
        age = nbt.getInt("age");
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putFloat("size", size());
        nbt.putInt("body", dataTracker.get(BODY));
        nbt.putInt("age", age);
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }
}
