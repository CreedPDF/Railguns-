package com.creedpdf.orbitalrailgun.krl;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The spear's arrival: a superheated plasma explosion. Everything near is burnt and thrown; the ground is carved into a
 * bowl lined with glassy, still-glowing rock (obsidian, magma, blackstone) and the rim is scorched and set alight. The
 * crater is cut over {@link #CARVE} ticks from the centre outwards so the server never stalls.
 */
public final class KrlImpact {
    public static final int RADIUS = 13, CARVE = 14;
    public static final double BLAST = 44;
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    private final ServerWorld w;
    private final BlockPos at;
    @Nullable
    private final UUID owner;
    private IntArrayList columns;
    private int carved;

    public KrlImpact(ServerWorld w, BlockPos ground, @Nullable UUID owner) {
        this.w = w;
        this.at = ground;
        this.owner = owner;
    }

    private DamageSource source() {
        Entity attacker = owner == null ? null : w.getEntity(owner);
        return new DamageSource(w.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(Krl.PLASMA), null, attacker);
    }

    public void blast() {
        Vec3d c = Vec3d.ofCenter(at).add(0, 1, 0);
        w.playSound(null, c.x, c.y, c.z, Krl.S_IMPACT, SoundCategory.BLOCKS, 40f, 1f);
        DamageSource src = source();
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(BLAST), e -> e.isAlive() && !e.isSpectator())) {
            if (KrlServer.riding(e)) continue;
            double d = e.getPos().distanceTo(c);
            if (d > BLAST) continue;
            float k = (float) Math.pow(1 - d / BLAST, 0.75);
            e.damage(src, 70f * k + (d < RADIUS ? 1000f : 0f));
            e.setOnFireFor(4 + (int) (8 * k));
            Vec3d push = e.getPos().subtract(c).add(0, 0.8, 0).normalize().multiply(2.6 * k);
            e.addVelocity(push.x, push.y + 0.5 * k, push.z);
            e.velocityModified = true;
        }
        int outer = (int) Math.ceil(RADIUS * 1.9);
        columns = new IntArrayList();
        for (int dx = -outer; dx <= outer; dx++)
            for (int dz = -outer; dz <= outer; dz++)
                if (dx * dx + dz * dz <= outer * outer) columns.add(((dx + 512) << 10) | (dz + 512));
        columns.sort((a, b) -> Integer.compare(d2(a), d2(b)));
    }

    private static int d2(int packed) {
        int dx = (packed >> 10) - 512, dz = (packed & 1023) - 512;
        return dx * dx + dz * dz;
    }

    public void carve(int step) {
        if (columns == null || carved >= columns.size()) return;
        int until = (int) Math.ceil(columns.size() * Math.min(1.0, step / (double) CARVE));
        int outer = (int) Math.ceil(RADIUS * 1.9);
        double depth = RADIUS * 0.62;
        Random rnd = w.random;
        BlockPos.Mutable p = new BlockPos.Mutable();
        int gy = at.getY();
        for (; carved < until; carved++) {
            int packed = columns.getInt(carved);
            int dx = (packed >> 10) - 512, dz = (packed & 1023) - 512;
            double dist = Math.sqrt(dx * dx + dz * dz) + rnd.nextDouble() * 0.8;
            int x = at.getX() + dx, z = at.getZ() + dz;
            int top = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
            if (dist < RADIUS) {
                double k = dist / RADIUS;
                int floor = (int) Math.floor(gy - depth * Math.sqrt(Math.max(0, 1 - k * k)));
                int from = Math.min(top, gy + RADIUS + 8);
                for (int y = from; y > floor; y--) {
                    p.set(x, y, z);
                    BlockState s = w.getBlockState(p);
                    if (s.isAir() || s.getHardness(w, p) < 0) continue;
                    w.setBlockState(p, Blocks.AIR.getDefaultState(), FLAGS);
                }
                // the bowl is lined with fused, glowing rock
                for (int y = floor; y > floor - 2; y--) {
                    p.set(x, y, z);
                    BlockState f = w.getBlockState(p);
                    if (f.getHardness(w, p) < 0) continue;
                    if (!f.isAir() || y == floor) w.setBlockState(p, fused(rnd, 1 - k, y == floor), FLAGS);
                }
                if (k < 0.35 && rnd.nextInt(5) == 0 && w.getBlockState(p.set(x, floor + 1, z)).isAir())
                    w.setBlockState(p, Blocks.FIRE.getDefaultState(), Block.NOTIFY_LISTENERS);
            } else {
                // the scorched rim
                double k = (dist - RADIUS) / Math.max(1, outer - RADIUS);
                if (rnd.nextDouble() < k * 0.85) continue;
                p.set(x, top, z);
                BlockState s = w.getBlockState(p);
                if (!s.isAir() && (s.isReplaceable() || s.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) || s.isIn(net.minecraft.registry.tag.BlockTags.FLOWERS))) {
                    w.setBlockState(p, Blocks.AIR.getDefaultState(), FLAGS);
                    p.move(0, -1, 0);
                    s = w.getBlockState(p);
                }
                if (s.isAir() || s.getHardness(w, p) < 0 || !s.isOpaque()) continue;
                w.setBlockState(p, scorched(rnd, 1 - k), FLAGS);
                if (rnd.nextInt(6) == 0 && w.getBlockState(p.up()).isAir()) w.setBlockState(p.up(), Blocks.FIRE.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
    }

    /** heat 0..1 (1 at the centre). */
    private static BlockState fused(Random r, double heat, boolean surface) {
        double x = r.nextDouble();
        if (surface && x < 0.18 + 0.4 * heat) return Blocks.MAGMA_BLOCK.getDefaultState();
        if (x < 0.55) return Blocks.OBSIDIAN.getDefaultState();
        if (x < 0.8) return Blocks.BLACKSTONE.getDefaultState();
        return Blocks.BASALT.getDefaultState();
    }

    private static BlockState scorched(Random r, double heat) {
        double x = r.nextDouble();
        if (x < 0.06 + 0.2 * heat) return Blocks.MAGMA_BLOCK.getDefaultState();
        if (x < 0.4) return Blocks.BLACKSTONE.getDefaultState();
        if (x < 0.65) return Blocks.BASALT.getDefaultState();
        if (x < 0.85) return Blocks.COARSE_DIRT.getDefaultState();
        return Blocks.SMOOTH_BASALT.getDefaultState();
    }
}
