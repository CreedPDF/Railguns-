package com.creedpdf.orbitalrailgun.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * One strike of Gungnir. It lives at the target: the release and descent play out above it, it lands with the impact,
 * carves a crater sized by the chosen power, then stands planted for a while before it is recalled to orbit.
 * Everything you see is drawn by the client from this entity's tick counter (see the client's GrsStrikeFx).
 */
public class GungnirStrikeEntity extends Entity {
    private static final TrackedData<Integer> YIELD = DataTracker.registerData(GungnirStrikeEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> TICKS = DataTracker.registerData(GungnirStrikeEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> DX = DataTracker.registerData(GungnirStrikeEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> DY = DataTracker.registerData(GungnirStrikeEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> DZ = DataTracker.registerData(GungnirStrikeEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Optional<UUID>> OWNER = DataTracker.registerData(GungnirStrikeEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    /** Client-side smooth counter (re-synced from the tracker). */
    private int clientTicks = -1;
    /** The server's counter (synced to clients sparsely through TICKS). */
    private int serverTicks;
    /** Crater columns, packed (dx, dz), sorted by distance; built at impact. */
    private IntArrayList columns;
    private int carved;
    private int groundY;
    /** The invisible collision column that makes the planted spear solid (packed block positions). */
    private final it.unimi.dsi.fastutil.longs.LongArrayList solid = new it.unimi.dsi.fastutil.longs.LongArrayList();

    public GungnirStrikeEntity(EntityType<? extends GungnirStrikeEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.ignoreCameraFrustum = true;
    }

    public void setup(int power, UUID owner) {
        dataTracker.set(YIELD, Grs.clampYield(power));
        dataTracker.set(OWNER, Optional.ofNullable(owner));
        Vec3d d = Grs.jupiterSkyDir(getWorld());
        dataTracker.set(DX, (float) d.x);
        dataTracker.set(DY, (float) d.y);
        dataTracker.set(DZ, (float) d.z);
    }

    /** Direction from the target up toward where the spear comes from (Jupiter's place in the sky). */
    public Vec3d skyDir() {
        Vec3d d = new Vec3d(dataTracker.get(DX), dataTracker.get(DY), dataTracker.get(DZ));
        return d.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : d.normalize();
    }

    public Vec3d plantAxis() {
        return Grs.plantAxis(skyDir());
    }

    public int power() {
        return dataTracker.get(YIELD);
    }

    public int ticks() {
        if (!getWorld().isClient) return serverTicks;
        return clientTicks >= 0 ? clientTicks : dataTracker.get(TICKS);
    }

    public float ticks(float pt) {
        return ticks() + pt;
    }

    public UUID owner() {
        return dataTracker.get(OWNER).orElse(null);
    }

    public boolean planted() {
        int t = ticks();
        return t >= Grs.IMPACT && t < Grs.END;
    }

    /** Where the spear meets the ground. */
    public Vec3d base() {
        return getPos();
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(YIELD, 5);
        dataTracker.startTracking(TICKS, 0);
        dataTracker.startTracking(OWNER, Optional.empty());
        dataTracker.startTracking(DX, 0f);
        dataTracker.startTracking(DY, 1f);
        dataTracker.startTracking(DZ, 0f);
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (TICKS.equals(data) && getWorld().isClient) {
            int t = dataTracker.get(TICKS);
            if (clientTicks < 0 || Math.abs(clientTicks - t) > 3) clientTicks = t;
        }
    }

    @Override
    public boolean shouldRender(double distance) {
        return true;
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
        World w = getWorld();
        if (w.isClient) {
            if (clientTicks < 0) clientTicks = dataTracker.get(TICKS);
            clientTicks++;
            return;
        }
        ServerWorld sw = (ServerWorld) w;
        int t = ++serverTicks;
        // sync sparsely; the client counts by itself in between
        if (t % 20 == 0 || t == Grs.IMPACT || t == Grs.RECALL) dataTracker.set(TICKS, t);
        BlockPos at = getBlockPos();
        sw.getChunkManager().addTicket(ChunkTicketType.PORTAL, new ChunkPos(at), 3, at);

        if (t == 1) {
            sound(sw, ModSounds.GRS_RELEASE, 10f, 1f);
            for (ServerPlayerEntity p : sw.getPlayers()) {
                if (p.squaredDistanceTo(getPos()) < 1500 * 1500)
                    p.playSound(ModSounds.GRS_RELEASE, SoundCategory.AMBIENT, 0.35f, 1.05f);
            }
        }
        if (t == Grs.IMPACT - 14) sound(sw, ModSounds.GRS_DESCENT, 32f, 1f);
        if (t == Grs.IMPACT) impact(sw);
        if (t == Grs.IMPACT + 7) sound(sw, ModSounds.GRS_PLANT, 20f, 1f);
        if (t > Grs.IMPACT && t <= Grs.IMPACT + Grs.CARVE_TICKS) carve(sw, t - Grs.IMPACT);
        if (t == Grs.IMPACT + Grs.CARVE_TICKS + 2) plantCollision(sw);
        if (t == Grs.RECALL) {
            sound(sw, ModSounds.GRS_RECALL, 20f, 1f);
            clearCollision(sw);
        }
        if (t > Grs.IMPACT && t < Grs.RECALL && t % 120 == 40) sound(sw, ModSounds.GRS_HUM, 6f, 1f);
        if (t >= Grs.END) discard();
    }

    /** Fill the standing part of the spear with invisible collider blocks, so it is something you can bump into and climb. */
    private void plantCollision(ServerWorld w) {
        Vec3d axis = plantAxis();
        Vec3d base = getPos();
        BlockPos.Mutable p = new BlockPos.Mutable();
        BlockState c = RailgunMod.RAILGUN_COLLIDER.getDefaultState();
        float above = Grs.SPEAR_LENGTH - Grs.PLANT_DEPTH;
        for (double h = 0; h < above - 6; h += 0.5) {
            Vec3d q = base.add(axis.multiply(h));
            double rad = h < 24 ? 2.2 : 1.4;
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    int x = MathHelper.floor(q.x) + dx, z = MathHelper.floor(q.z) + dz, y = MathHelper.floor(q.y);
                    double ex = x + 0.5 - q.x, ez = z + 0.5 - q.z;
                    if (ex * ex + ez * ez > rad * rad) continue;
                    p.set(x, y, z);
                    if (y >= w.getTopY() || !w.getBlockState(p).isAir()) continue;
                    w.setBlockState(p, c, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                    solid.add(p.asLong());
                }
        }
    }

    private void clearCollision(ServerWorld w) {
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int i = 0; i < solid.size(); i++) {
            p.set(solid.getLong(i));
            if (w.getBlockState(p).isOf(RailgunMod.RAILGUN_COLLIDER)) w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        }
        solid.clear();
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!getWorld().isClient && reason.shouldDestroy()) clearCollision((ServerWorld) getWorld());
        super.remove(reason);
    }

    private void sound(ServerWorld w, SoundEvent s, float vol, float pitch) {
        w.playSound(null, getX(), getY() + 2, getZ(), s, SoundCategory.BLOCKS, vol, pitch);
    }

    private DamageSource source(ServerWorld w) {
        UUID o = owner();
        Entity attacker = o == null ? null : w.getEntity(o);
        return new DamageSource(w.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(Grs.DAMAGE), this, attacker);
    }

    private void impact(ServerWorld w) {
        sound(w, ModSounds.GRS_IMPACT, 40f, 1f);
        int y = power();
        double R = Grs.blastRadius(y);
        DamageSource src = source(w);
        UUID o = owner();
        Vec3d c = getPos();
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(R, R, R), e -> e.isAlive() && !e.isSpectator())) {
            if (o != null && o.equals(e.getUuid())) continue;
            double d = e.getPos().distanceTo(c);
            if (d > R) continue;
            float k = (float) Math.pow(1 - d / R, 0.7);
            float dmg = Grs.damage(y) * k + (d < 4 ? 2000f : 0f);
            e.damage(src, dmg);
            e.setOnFireFor(4 + y);
            Vec3d push = e.getPos().subtract(c).add(0, 0.6, 0).normalize().multiply((0.8 + y * 0.25) * k);
            e.addVelocity(push.x, push.y + 0.4 * k, push.z);
            e.velocityModified = true;
        }
        groundY = getBlockY() - 1;
        buildColumns(y);
    }

    private void buildColumns(int power) {
        int r = Grs.radius(power);
        int outer = Math.max(4, (int) Math.ceil(r * 1.4));
        columns = new IntArrayList();
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                if (dx * dx + dz * dz <= outer * outer) columns.add(((dx + 512) << 10) | (dz + 512));
            }
        }
        columns.sort((a, b) -> Integer.compare(d2(a), d2(b)));
        carved = 0;
    }

    private static int d2(int packed) {
        int dx = (packed >> 10) - 512, dz = (packed & 1023) - 512;
        return dx * dx + dz * dz;
    }

    private void carve(ServerWorld w, int step) {
        if (columns == null) {
            groundY = getBlockY() - 1;
            buildColumns(power());
        }
        int total = columns.size();
        int until = (int) Math.ceil(total * Math.min(1.0, step / (double) Grs.CARVE_TICKS));
        int r = Grs.radius(power());
        double depth = r * 0.55;
        int outer = Math.max(4, (int) Math.ceil(r * 1.4));
        Random rnd = w.random;
        BlockPos.Mutable p = new BlockPos.Mutable();
        int bx = getBlockX(), bz = getBlockZ();
        for (; carved < until; carved++) {
            int packed = columns.getInt(carved);
            int dx = (packed >> 10) - 512, dz = (packed & 1023) - 512;
            double dist = Math.sqrt(dx * dx + dz * dz);
            int x = bx + dx, z = bz + dz;
            int top = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
            if (r > 0 && dist < r) {
                double k = dist / r;
                int floor = (int) Math.floor(groundY - depth * Math.sqrt(1 - k * k)) ;
                int from = Math.min(top, groundY + (int) (r * 0.9) + 6);
                for (int y = from; y > floor; y--) {
                    p.set(x, y, z);
                    BlockState s = w.getBlockState(p);
                    if (s.isAir() || s.getHardness(w, p) < 0) continue;
                    w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }
                p.set(x, floor, z);
                BlockState f = w.getBlockState(p);
                if (!f.isAir() && f.getHardness(w, p) >= 0) w.setBlockState(p, scorched(rnd, 1 - k), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            } else {
                // the burnt rim: blackened, glowing ground, flattened plants
                double k = (dist - r) / Math.max(1, outer - r);
                if (rnd.nextDouble() > 0.95 - k * 0.6) continue;
                p.set(x, top, z);
                BlockState s = w.getBlockState(p);
                if (s.isReplaceable() && !s.isAir()) {
                    w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                    p.move(0, -1, 0);
                    s = w.getBlockState(p);
                }
                if (s.isAir() || s.getHardness(w, p) < 0 || !s.isOpaque()) continue;
                w.setBlockState(p, scorched(rnd, 0.6 * (1 - k)), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                if (rnd.nextInt(9) == 0 && w.getBlockState(p.up()).isAir())
                    w.setBlockState(p.up(), Blocks.FIRE.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
    }

    /** Black and molten orange ground: blackstone, basalt, magma, a little obsidian. heat 0..1. */
    private static BlockState scorched(Random r, double heat) {
        double x = r.nextDouble();
        if (x < 0.12 + 0.3 * heat) return Blocks.MAGMA_BLOCK.getDefaultState();
        if (x < 0.5) return Blocks.BLACKSTONE.getDefaultState();
        if (x < 0.78) return Blocks.BASALT.getDefaultState();
        if (x < 0.9) return Blocks.SMOOTH_BASALT.getDefaultState();
        return Blocks.OBSIDIAN.getDefaultState();
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        dataTracker.set(YIELD, Grs.clampYield(nbt.getInt("yield")));
        serverTicks = nbt.getInt("ticks");
        dataTracker.set(TICKS, serverTicks);
        if (nbt.containsUuid("owner")) dataTracker.set(OWNER, Optional.of(nbt.getUuid("owner")));
        carved = nbt.getInt("carved");
        groundY = nbt.contains("ground") ? nbt.getInt("ground") : getBlockY() - 1;
        solid.clear();
        for (long l : nbt.getLongArray("solid")) solid.add(l);
        if (nbt.contains("dx")) {
            dataTracker.set(DX, nbt.getFloat("dx"));
            dataTracker.set(DY, nbt.getFloat("dy"));
            dataTracker.set(DZ, nbt.getFloat("dz"));
        }
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("yield", power());
        nbt.putInt("ticks", serverTicks);
        UUID o = owner();
        if (o != null) nbt.putUuid("owner", o);
        nbt.putInt("carved", carved);
        nbt.putInt("ground", groundY);
        nbt.putLongArray("solid", solid.toLongArray());
        nbt.putFloat("dx", dataTracker.get(DX));
        nbt.putFloat("dy", dataTracker.get(DY));
        nbt.putFloat("dz", dataTracker.get(DZ));
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    /** Unused by the server; keeps the id constant visible for the renderer registry. */
    public static final String ID = RailgunMod.MOD_ID + ":gungnir_strike";

    @SuppressWarnings("unused")
    private static double clamp01(double x) {
        return MathHelper.clamp(x, 0, 1);
    }
}
