package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.astraea.Timeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What the client knows about ASTRAEA: rituals and the strike in progress, and the (permanent) split. */
public final class AstraeaState {
    private AstraeaState() {}

    public record Ritual(int player, long start, Vec3d anchor, float yaw) {}

    public record Strike(int player, boolean mine, long start, Vec3d anchor, Vec3d normal, double angle) {}

    public record Chasm(double x, double z, double angle) {}

    static final Map<Integer, Ritual> RITUALS = new HashMap<>();
    static Strike strike;

    // the split, mirrored from SplitState
    public static long splitTime = -1;
    public static int strikes;
    public static Vec3d normal = new Vec3d(0, 0, 1);
    public static final List<Chasm> CHASMS = new ArrayList<>();

    static void clear() {
        RITUALS.clear();
        strike = null;
        splitTime = -1;
        strikes = 0;
        CHASMS.clear();
    }

    public static double now(float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? 0 : mc.world.getTime() + pt;
    }

    /** Ticks since the strike began (with partial tick), or -1. */
    public static double strikeT(float pt) {
        if (strike == null) return -1;
        double t = now(pt) - strike.start();
        return t > Timeline.S_END + 40 ? -1 : t;
    }

    /** Ticks since a player's ritual began, or -1. */
    public static double ritualT(int player, float pt) {
        Ritual r = RITUALS.get(player);
        if (r == null) return -1;
        return now(pt) - r.start();
    }

    /** The strike in progress (any player's), or null. */
    public static Strike strike() {
        return strike;
    }

    /** 0..1 how far round the planet the cut has been carved, while a strike is drilling and the Earth is still whole; else -1. */
    public static double drill(float pt) {
        double t = strikeT(pt);
        if (t < Timeline.S_DRILL || t >= Timeline.S_SPLIT || split()) return -1;
        return Timeline.cutProgress(t);
    }

    /** Where the lance is in the Space frame at time t of a strike (rising, circling, spiralling in, then boring along the cut). */
    public static Vec3d lancePos(Strike s, double t) {
        com.creedpdf.orbitalrailgun.space.Body e = com.creedpdf.orbitalrailgun.space.Body.EARTH;
        Vec3d u = e.surfaceNormal(s.anchor().x, s.anchor().z);
        Vec3d w = u.crossProduct(s.normal()).normalize();
        double rho, th;
        if (t < Timeline.S_ORBIT) {
            double k = Math.max(0, (t - Timeline.S_RELEASE) / (Timeline.S_ORBIT - Timeline.S_RELEASE));
            k = k * k * (3 - 2 * k);
            rho = 1.0 + (Timeline.ORBIT_HIGH - 1.0) * k;
            th = 0;
        } else if (t < Timeline.S_DRILL) {
            rho = Timeline.orbitRadius(t);
            th = Timeline.orbitAngle(t);
        } else {
            rho = 1.0;
            th = drillAngle(t);
        }
        return e.center.add(u.multiply(Math.cos(th) * rho * e.radius)).add(w.multiply(Math.sin(th) * rho * e.radius));
    }

    /** The angle of the drill's head round the cut (it starts where the orbit ended, and goes once round). */
    public static double drillAngle(double t) {
        return Timeline.orbitAngle(Timeline.S_DRILL) + Math.PI * 2 * Timeline.cutProgress(t);
    }

    public static boolean split() {
        return splitTime >= 0;
    }

    /** Ticks since the Earth split (with partial tick), or -1 if it is whole. */
    public static double sinceSplit(float pt) {
        return splitTime < 0 ? -1 : Math.max(0, now(pt) - splitTime);
    }

    /** The chasm nearest a point (for which side of the cut you are on), or null. */
    public static Chasm nearest(double x, double z) {
        Chasm best = null;
        double bd = Double.MAX_VALUE;
        for (Chasm c : CHASMS) {
            double d = (c.x() - x) * (c.x() - x) + (c.z() - z) * (c.z() - z);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }
}
