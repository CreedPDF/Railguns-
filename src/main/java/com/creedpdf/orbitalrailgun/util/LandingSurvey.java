package com.creedpdf.orbitalrailgun.util;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/**
 * Orbital site survey: decides whether a pod can land at a spot AND deploy there, before it is
 * ever released from orbit, and clears the landing platform when it touches down.
 *
 * <p>The deployed emplacement needs a disc of {@link #RADIUS} blocks and {@link #HEIGHT} blocks of
 * headroom above the landing level. Ordinary blocks in that volume (trees, grass, a hill top,
 * a house) are no problem: the pod smashes through and clears them. A drop is refused when:</p>
 * <ul>
 *   <li>the zone contains blocks the pod cannot break (bedrock, obsidian, reinforced blocks),</li>
 *   <li>the ground falls away under too much of the footprint (cliff edge, ravine),</li>
 *   <li>the landing block is liquid,</li>
 *   <li>another railgun is too close, or the build limit is in the way.</li>
 * </ul>
 */
public final class LandingSurvey {
    private LandingSurvey() {}

    public static final int RADIUS = 8;
    public static final int HEIGHT = 12;
    private static final int MAX_DROP = 3;

    public record Result(boolean ok, String reason, BlockPos obstacle) {
        static Result fail(String reason, BlockPos at) { return new Result(false, reason, at); }
        static final Result OK = new Result(true, "clear", BlockPos.ORIGIN);
    }

    /** @param ground the block the pod will stand on (its top face is the platform level) */
    public static Result survey(ServerWorld world, BlockPos ground) {
        BlockPos land = ground.up();
        if (!world.getFluidState(ground).isEmpty() || !world.getFluidState(land).isEmpty()) return Result.fail("no_room_liquid", ground);
        if (world.getBlockState(ground).isAir()) return Result.fail("no_lock", ground);
        if (land.getY() + HEIGHT >= world.getTopY() || ground.getY() <= world.getBottomY() + 2) return Result.fail("no_room_height", land);

        Vec3d c = Vec3d.ofBottomCenter(land);
        if (!world.getEntitiesByClass(RailgunEntity.class, new Box(c, c).expand(RADIUS * 3.0, 64, RADIUS * 3.0), e -> e.isAlive()).isEmpty()) {
            return Result.fail("no_room_crowded", land);
        }

        // Drop shaft above the platform: the pod smashes through leaves, trees and thin roofs,
        // but not through a mountain or anything unbreakable.
        int solid = 0;
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, land.getX(), land.getZ());
        for (int y = land.getY() + HEIGHT; y < top; y++) {
            BlockPos p = new BlockPos(land.getX(), y, land.getZ());
            BlockState s = world.getBlockState(p);
            if (s.isAir()) continue;
            if (unbreakable(world, p, s)) return Result.fail("no_room_unbreakable", p);
            if (++solid > 24) return Result.fail("no_sky", p);
        }

        int columns = 0, unsupported = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS + 1) continue;
                columns++;
                // Volume the emplacement occupies: must be breakable.
                for (int dy = 0; dy < HEIGHT; dy++) {
                    BlockPos p = land.add(dx, dy, dz);
                    BlockState s = world.getBlockState(p);
                    if (s.isAir()) continue;
                    if (unbreakable(world, p, s)) return Result.fail("no_room_unbreakable", p);
                }
                // Support: is there ground within a few blocks below the platform level?
                boolean supported = false;
                for (int dy = 0; dy <= MAX_DROP; dy++) {
                    BlockPos p = ground.add(dx, -dy, dz);
                    BlockState s = world.getBlockState(p);
                    if (!s.isAir() && s.getFluidState().isEmpty()) { supported = true; break; }
                }
                if (!supported) unsupported++;
            }
        }
        if (unsupported > columns * 0.35) return Result.fail("no_room_unstable", ground);
        return Result.OK;
    }

    /** Pod touchdown: everything breakable in the platform volume is smashed away. */
    public static int clearPlatform(ServerWorld world, BlockPos land) {
        int cleared = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS + 1) continue;
                for (int dy = 0; dy < HEIGHT; dy++) {
                    BlockPos p = land.add(dx, dy, dz);
                    BlockState s = world.getBlockState(p);
                    if (s.isAir() || unbreakable(world, p, s) || s.isOf(RailgunMod.RAILGUN_COLLIDER)) continue;
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.SKIP_DROPS);
                    cleared++;
                }
            }
        }
        return cleared;
    }

    public static boolean unbreakable(ServerWorld world, BlockPos p, BlockState s) {
        float h = s.getHardness(world, p);
        return h < 0 || h >= 50;
    }

    /** The platform level for a drop aimed at {@code aimed}: the top of the aimed block. */
    public static BlockPos groundFor(ServerWorld world, BlockPos aimed) {
        return aimed;
    }

    /** Height of the highest blocking block in a column (used while falling). */
    public static int top(ServerWorld world, int x, int z) {
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
    }
}
