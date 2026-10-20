package com.creedpdf.orbitalrailgun.space;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.MeteorEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

/**
 * After a planet dies, pieces of it rain down on Earth for a while: a dense shower at first that
 * thins out over {@link #DURATION} ticks, around every player in the overworld.
 */
public final class MeteorShower {
    private MeteorShower() {}

    /** Ticks after detonation before the first pieces arrive. */
    public static final int ARRIVE = 240;
    /** How long the shower lasts (~5 minutes). */
    public static final int DURATION = 6000;

    public static void tick(MinecraftServer server, Body b, long e) {
        long since = e - Blast.DETONATE - ARRIVE;
        if (since < 0 || since > DURATION) return;
        ServerWorld w = server.getOverworld();
        float f = since / (float) DURATION;
        // about one piece every 2 s per player at first, thinning to one every ~10 s: each impact is
        // a real explosion, so the sky streaks (client-only, free for the server) carry the spectacle
        float rate = (0.012f + 0.028f * (float) Math.exp(-since / 1200f)) * (1f - f * 0.6f);
        if (b.gasGiant()) rate *= 1.3f;
        Random r = w.random;
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (r.nextFloat() > rate) continue;
            spawn(w, p, b, r);
        }
    }

    private static void spawn(ServerWorld w, ServerPlayerEntity p, Body b, Random r) {
        double ang = r.nextDouble() * Math.PI * 2;
        double dist = 24 + r.nextDouble() * 90;
        int tx = MathHelper.floor(p.getX() + Math.cos(ang) * dist), tz = MathHelper.floor(p.getZ() + Math.sin(ang) * dist);
        if (!w.isChunkLoaded(tx >> 4, tz >> 4)) return;
        int ty = w.getTopY(Heightmap.Type.MOTION_BLOCKING, tx, tz);
        // coming in steeply from beyond the player, so the start point is near the player and loaded
        Vec3d heading = new Vec3d(Math.cos(ang), 0, Math.sin(ang));
        double speed = 2.6 + r.nextDouble() * 1.4;
        Vec3d dir = heading.multiply(0.42 + r.nextDouble() * 0.25).add(0, -1, 0).normalize();
        double drop = Math.min(w.getTopY() - 4 - ty, 300);
        double along = drop / -dir.y;
        Vec3d target = new Vec3d(tx + 0.5, ty, tz + 0.5);
        Vec3d start = target.subtract(dir.multiply(along));
        if (!w.isChunkLoaded(BlockPos.ofFloored(start))) return;
        float u = r.nextFloat();
        float size = 0.6f + u * u * 2.2f;
        MeteorEntity m = new MeteorEntity(RailgunMod.METEOR, w);
        m.setPosition(start);
        m.setup(b, size, dir.multiply(speed));
        w.spawnEntity(m);
    }
}
