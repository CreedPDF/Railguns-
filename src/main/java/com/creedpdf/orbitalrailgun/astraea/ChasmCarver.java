package com.creedpdf.orbitalrailgun.astraea;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

/**
 * Tears a real chasm across the overworld where the Earth split: a meandering crack hundreds of blocks long, opening
 * from its middle outward in both directions at once (the ground "unzips" toward both horizons). Each slice across it:
 * <ul>
 *   <li>a V-to-U profile, widest at the surface, its width wandering along the crack and swelling in the middle;</li>
 *   <li>rough walls (the width jitters with depth), seared to basalt and blackstone, magma toward the bottom;</li>
 *   <li>a floor of lava near the bottom of the world;</li>
 *   <li>the rim crumbling: blocks at the lip break away and tumble in;</li>
 *   <li>dust and smoke boiling out of it, the grinding roar of the ground tearing.</li>
 * </ul>
 * Blocks change without neighbour updates (no chain reactions), with drops off; bedrock and other unbreakables stay.
 */
public final class ChasmCarver {
    private ChasmCarver() {}

    public static final int HALF = 240;
    private static final int PER_TICK = 2;

    /** Advance every unfinished chasm. Returns true if anything was carved. */
    public static boolean tick(ServerWorld w, SplitState st) {
        boolean any = false;
        for (SplitState.Chasm c : st.chasms) {
            if (c.finished()) continue;
            Random r = Random.create(c.seed ^ (c.done * 31L));
            for (int i = 0; i < PER_TICK && !c.finished(); i++) {
                slice(w, c, c.done, r);
                if (c.done > 0) slice(w, c, -c.done, r);
                c.done++;
            }
            any = true;
            st.markDirty();
        }
        return any;
    }

    private static double n1(double s, long seed) {
        return Math.sin(s * 0.031 + seed % 97) * 0.55 + Math.sin(s * 0.079 + seed % 13) * 0.3 + Math.sin(s * 0.21 + seed % 7) * 0.15;
    }

    private static double halfWidth(double s, long seed) {
        double w = 10 + 6 * n1(s, seed) + 7 * Math.exp(-(s / 70) * (s / 70));
        double taper = MathHelper.clamp((HALF - Math.abs(s)) / 50.0, 0.12, 1);
        return Math.max(2.5, w * taper);
    }

    private static double meander(double s, long seed) {
        return 12 * Math.sin(s * 0.011 + (seed % 31)) + 4 * Math.sin(s * 0.047 + (seed % 17)) + 1.5 * Math.sin(s * 0.17);
    }

    private static void slice(ServerWorld w, SplitState.Chasm c, int si, Random r) {
        double s = si;
        Vec3d d = new Vec3d(Math.cos(c.angle), 0, Math.sin(c.angle));
        Vec3d perp = new Vec3d(-d.z, 0, d.x);
        double hw = halfWidth(s, c.seed);
        double mid = meander(s, c.seed);
        int bottom = Math.max(w.getBottomY() + 4, -56);
        int lava = bottom + 3;
        Vec3d centre = new Vec3d(c.x, 0, c.z).add(d.multiply(s));
        int reach = (int) Math.ceil(hw + 3);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int topSeen = bottom;
        for (int oi = -reach * 2; oi <= reach * 2; oi++) {
            double o = oi * 0.5;
            Vec3d col = centre.add(perp.multiply(o + mid));
            int x = MathHelper.floor(col.x), z = MathHelper.floor(col.z);
            w.getChunk(x >> 4, z >> 4);
            int top = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
            if (top < bottom) continue;
            topSeen = Math.max(topSeen, top);
            double ao = Math.abs(o);
            for (int y = top; y >= bottom; y--) {
                double f = (y - bottom) / (double) Math.max(1, top - bottom);
                double jit = Math.sin(y * 0.37 + s * 0.51 + c.seed % 11) * 0.9 + Math.sin(y * 1.13 - s * 0.23) * 0.5;
                double hwy = hw * (0.22 + 0.78 * Math.pow(f, 0.55)) + jit;
                pos.set(x, y, z);
                BlockState st = w.getBlockState(pos);
                if (st.getHardness(w, pos) < 0) continue;
                if (ao <= hwy) {
                    BlockState to = y <= lava ? Blocks.LAVA.getDefaultState() : Blocks.AIR.getDefaultState();
                    if (st != to) w.setBlockState(pos, to, 2 | 16);
                } else if (ao <= hwy + 1.6 && !st.isAir() && st.getFluidState().isEmpty()) {
                    // seared walls
                    BlockState wall = f < 0.1 ? Blocks.MAGMA_BLOCK.getDefaultState()
                            : f < 0.45 ? (r.nextFloat() < 0.6 ? Blocks.BASALT.getDefaultState() : Blocks.BLACKSTONE.getDefaultState())
                            : r.nextFloat() < 0.25 ? Blocks.TUFF.getDefaultState() : null;
                    if (wall != null) w.setBlockState(pos, wall, 2 | 16);
                }
            }
            // the lip crumbles in
            if (ao > hw && ao < hw + 2.5 && r.nextFloat() < 0.05) {
                pos.set(x, top, z);
                BlockState st = w.getBlockState(pos);
                if (!st.isAir() && st.getFluidState().isEmpty() && st.getHardness(w, pos) >= 0) {
                    FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(w, pos.toImmutable(), st);
                    Vec3d in = perp.multiply(o > 0 ? -1 : 1);
                    fb.setVelocity(in.x * 0.25, 0.15, in.z * 0.25);
                    fb.dropItem = false;
                }
            }
        }
        // dust, smoke and the roar of the ground tearing
        Vec3d at = centre.add(perp.multiply(mid)).add(0, topSeen + 1, 0);
        if (si % 3 == 0) {
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (p.squaredDistanceTo(at) > 400 * 400) continue;
                w.spawnParticles(p, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, at.x, at.y, at.z, 6, hw * 0.4, 2, hw * 0.4, 0.02);
                w.spawnParticles(p, ParticleTypes.LARGE_SMOKE, true, at.x, at.y - 2, at.z, 10, hw * 0.4, 3, hw * 0.4, 0.06);
                w.spawnParticles(p, ParticleTypes.LAVA, true, at.x, lava + 1, at.z, 4, hw * 0.3, 1, hw * 0.3, 0);
            }
        }
        if (si % 14 == 0) w.playSound(null, at.x, at.y, at.z, Astraea.S_TEAR, SoundCategory.BLOCKS, 8f, 0.85f + r.nextFloat() * 0.3f);
    }
}
