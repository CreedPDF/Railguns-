package com.creedpdf.orbitalrailgun.station;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.MeteorEntity;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.MeteorShower;
import com.creedpdf.orbitalrailgun.space.PlanetState;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.List;

/**
 * Debris on a collision course with a station. While a destroyed planet's meteor shower rains on Earth, its debris
 * field also streams through orbit past every loaded station in the Space dimension; a hub's debris drill sends a
 * swarm at that one station anywhere. Each rock is a real {@link MeteorEntity} aimed at the hub (it drifts straight in
 * zero-g) and blasts whatever it hits.
 */
public final class ScDebris {
    private ScDebris() {}

    static void clear() {}

    public static void tick(ServerWorld w) {
        var hubs = ScGrid.hubs(w);
        if (hubs.isEmpty()) return;
        boolean shower = SpaceDims.isSpace(w) && showerActive(w);
        Random r = w.random;
        for (BlockPos h : List.copyOf(hubs)) {
            if (!w.isChunkLoaded(h) || !(w.getBlockEntity(h) instanceof ScHubBlockEntity hub)) continue;
            float rate = (hub.drill() > 0 ? 0.075f : 0f) + (shower ? 0.03f : 0f);
            if (rate > 0 && r.nextFloat() < rate) spawn(w, h, r);
        }
    }

    private static boolean showerActive(ServerWorld w) {
        PlanetState ps = PlanetState.get(w.getServer());
        long now = w.getServer().getOverworld().getTime();
        for (Body b : Body.ALL) {
            if (!b.destroyable() || !ps.doomed(b)) continue;
            long since = now - ps.blastStart(b) - Blast.DETONATE - MeteorShower.ARRIVE;
            if (since >= 0 && since <= MeteorShower.DURATION) return true;
        }
        return false;
    }

    private static void spawn(ServerWorld w, BlockPos hub, Random r) {
        Vec3d c = Vec3d.ofCenter(hub).add(0, 1, 0);
        boolean space = SpaceDims.isSpace(w);
        for (double dist : new double[]{110, 80, 55}) {
            double a = r.nextDouble() * Math.PI * 2;
            double el = space ? (r.nextDouble() - 0.5) * 1.0 : 0.35 + r.nextDouble() * 0.5;   // in gravity they come from above
            Vec3d dir = new Vec3d(Math.cos(a) * Math.cos(el), Math.sin(el), Math.sin(a) * Math.cos(el));
            Vec3d start = c.add(dir.multiply(dist));
            if (!w.isChunkLoaded(BlockPos.ofFloored(start)) || start.y > w.getTopY() + 64 || start.y < w.getBottomY()) continue;
            Vec3d aim = c.add((r.nextDouble() - 0.5) * 14, (r.nextDouble() - 0.5) * 8, (r.nextDouble() - 0.5) * 14);
            double speed = 0.9 + r.nextDouble() * 0.9;
            Vec3d v = aim.subtract(start).normalize().multiply(speed);
            float u = r.nextFloat();
            MeteorEntity m = new MeteorEntity(RailgunMod.METEOR, w);
            m.setPosition(start);
            m.setup(Body.ALL[r.nextInt(Body.ALL.length)], 0.5f + u * u * 1.6f, v);
            w.spawnEntity(m);
            return;
        }
    }
}
