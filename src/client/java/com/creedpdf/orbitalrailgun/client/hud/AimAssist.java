package com.creedpdf.orbitalrailgun.client.hud;

import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Where the uplink tablet is aimed, out to the full {@link RailgunConfig#REMOTE_RANGE}. A precise block raycast first;
 * if that misses (a far ridge just under the crosshair, terrain beyond what the raycast resolves), the ray is marched
 * against the terrain surface and locks onto the ground it hits, or onto the ground it passes closest over.
 */
public final class AimAssist {
    private AimAssist() {}

    /** How close above the ground a passing ray still locks on (blocks, plus a little per 100 m of range). */
    private static final double GRAZE = 2.5;

    @Nullable
    public static BlockPos aim(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null) return null;
        HitResult hit = p.raycast(RailgunConfig.REMOTE_RANGE, tickDelta, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh) return bh.getBlockPos();
        Vec3d eye = p.getCameraPosVec(tickDelta), dir = p.getRotationVec(tickDelta);
        BlockPos best = null;
        double bestClear = Double.MAX_VALUE;
        double step = 1.0;
        for (double s = 2; s <= RailgunConfig.REMOTE_RANGE; s += step) {
            double x = eye.x + dir.x * s, y = eye.y + dir.y * s, z = eye.z + dir.z * s;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!w.getChunkManager().isChunkLoaded(bx >> 4, bz >> 4)) {
                step = 4;
                continue;
            }
            step = s > 300 ? 2 : 1;
            int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
            if (top <= w.getBottomY()) continue;
            double clear = y - top;
            if (clear <= 0) return new BlockPos(bx, top - 1, bz);
            double allow = GRAZE + s / 100.0;
            if (clear < allow && clear < bestClear) {
                bestClear = clear;
                best = new BlockPos(bx, top - 1, bz);
            }
        }
        return best;
    }
}
