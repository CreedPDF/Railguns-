package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The spear's flight, as a pure function of time so the server, every client and the cutscene camera agree exactly:
 * <ol>
 *   <li><b>BOOST</b> - off the inclined rail (60 degrees, turned toward the target), accelerating, then a gravity turn; the motor burns
 *   out as it climbs into the stratosphere (apogee 380-760 blocks over the higher of launcher and target);</li>
 *   <li><b>STALL</b> - at apogee it hangs, nearly still, and noses over toward the target;</li>
 *   <li><b>FALL</b> - it drops, steepening and accelerating all the way to Mach 13 at impact.</li>
 * </ol>
 * Each leg is a cubic Bezier curve with an easing that matches speeds across the joins (zero at the stall).
 */
public final class KrlFlight {
    public static final int BOOST = 220, STALL = 60, FALL = 200, TOTAL = BOOST + STALL + FALL, AFTER = 90;
    public static final double MACH_MAX = 13.0;
    /** The rider's cutscene opens on the pad this many ticks before liftoff (the moment LAUNCH is pressed). */
    public static final int PAD_LEAD = Krl.COUNTDOWN;
    /** Pad shot until RIDE; on the spear until ORBIT; the stall shot until FALL_RIDE; on the spear until GROUND; then the ground shot. */
    public static final int RIDE = 34, ORBIT = BOOST - 14, FALL_RIDE = BOOST + STALL + 12, GROUND = TOTAL - 76;
    private static final Vec3d UP = new Vec3d(0, 1, 0);

    // ---- the launcher's turret geometry (mirrors tools/blender/build_krl.py), in the turret's own frame: +Z is where
    //      it points, +Y up, blocks from the bottom centre of the launcher block
    public static final double RAIL_EL = Math.toRadians(60);
    public static final double SE = Math.sin(RAIL_EL), CE = Math.cos(RAIL_EL);
    /** Trunnion, rail direction, rail "top" (the missile side). */
    public static final Vec3d PIVOT = new Vec3d(0, 1.25, -0.6), RAIL_D = new Vec3d(0, SE, CE), RAIL_N = new Vec3d(0, CE, -SE);
    public static final double SPEAR_UP = 0.42, NOZZLE_K = 0.3;
    /** The missile's nozzle exit on the rail, turret frame. */
    public static final Vec3d NOZZLE_LOCAL = PIVOT.add(RAIL_N.multiply(SPEAR_UP)).add(RAIL_D.multiply(NOZZLE_K));

    /** Turret frame to world, with the turret pointing along horizontal unit vector h. */
    public static Vec3d toWorld(Vec3d padBottomCenter, Vec3d h, Vec3d local) {
        Vec3d side = new Vec3d(h.z, 0, -h.x);
        return padBottomCenter.add(side.multiply(local.x)).add(0, local.y, 0).add(h.multiply(local.z));
    }

    /** The nozzle in the world once the turret has turned to face the target. */
    public static Vec3d nozzleFor(Vec3d padBottomCenter, Vec3d target) {
        Vec3d dh = new Vec3d(target.x - padBottomCenter.x, 0, target.z - padBottomCenter.z);
        Vec3d h = dh.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : dh.normalize();
        return toWorld(padBottomCenter, h, NOZZLE_LOCAL);
    }

    public final Vec3d launch, target, apex, apex2, h, rail;
    public final double dist, height;
    private final Vec3d b1, b2, f1, f2;
    private final double vFallMax, vBoostMax;

    /** The Mach number at impact for this missile's motor. */
    public final double machMax;

    public KrlFlight(Vec3d launch, Vec3d target) {
        this(launch, target, KrlLoadout.DEFAULT);
    }

    public KrlFlight(Vec3d launch, Vec3d target, KrlLoadout missile) {
        this.machMax = missile.mach();
        this.launch = launch;
        this.target = target;
        Vec3d dh = new Vec3d(target.x - launch.x, 0, target.z - launch.z);
        dist = dh.length();
        h = dist < 1e-3 ? new Vec3d(0, 0, 1) : dh.multiply(1 / dist);
        double baseY = Math.max(launch.y, target.y);
        height = MathHelper.clamp(380 + 0.18 * dist, 380, 760) * missile.heightMul();
        apex = new Vec3d(launch.x + h.x * dist * 0.5, baseY + height, launch.z + h.z * dist * 0.5);
        apex2 = apex.add(h.multiply(dist * 0.015 + 6)).add(0, -5, 0);
        rail = h.multiply(CE).add(0, SE, 0);
        b1 = launch.add(rail.multiply(Math.min(height * 0.5, dist * 0.6 + 40)));
        b2 = apex.subtract(h.multiply(Math.min(dist * 0.22 + 60, dist * 0.45)));
        f1 = apex2.add(h.multiply(dist * 0.08 + 10)).add(0, -height * 0.25, 0);
        f2 = target.add(0, height * 0.45, 0).subtract(h.multiply(Math.min(dist * 0.02, 20)));
        vFallMax = speed(TOTAL - 0.5);
        vBoostMax = maxBoostSpeed();
    }

    // ------------------------------------------------------------------------------------------- curves

    private static Vec3d bez(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double u) {
        double a = 1 - u;
        return p0.multiply(a * a * a).add(p1.multiply(3 * a * a * u)).add(p2.multiply(3 * a * u * u)).add(p3.multiply(u * u * u));
    }

    private static Vec3d bezD(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double u) {
        double a = 1 - u;
        return p1.subtract(p0).multiply(3 * a * a).add(p2.subtract(p1).multiply(6 * a * u)).add(p3.subtract(p2).multiply(3 * u * u));
    }

    private static double smooth(double x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    // ------------------------------------------------------------------------------------------- position, heading

    /** Position at flight time t (ticks since it left the rail; fractional for rendering). */
    public Vec3d pos(double t) {
        if (t <= 0) return launch;
        if (t < BOOST) return bez(launch, b1, b2, apex, smooth(t / BOOST));
        if (t < BOOST + STALL) return apex.add(apex2.subtract(apex).multiply(smooth((t - BOOST) / STALL)));
        if (t < TOTAL) return bez(apex2, f1, f2, target, Math.pow((t - BOOST - STALL) / FALL, 2.3));
        return target;
    }

    /** Where the nose points. */
    public Vec3d dir(double t) {
        if (t <= 0) return rail;
        if (t < BOOST * 0.97) {
            Vec3d d = bezD(launch, b1, b2, apex, smooth(t / BOOST));
            return d.lengthSquared() < 1e-9 ? rail : d.normalize();
        }
        Vec3d end = bezD(launch, b1, b2, apex, 0.999).normalize();
        Vec3d fallStart = f1.subtract(apex2).normalize();
        if (t < BOOST + STALL) {
            double k = smooth((t - BOOST * 0.97) / (STALL + BOOST * 0.03));
            return end.multiply(1 - k).add(fallStart.multiply(k)).normalize();
        }
        if (t < TOTAL) {
            double u = Math.max(0.002, Math.pow((t - BOOST - STALL) / FALL, 2.3));
            return bezD(apex2, f1, f2, target, u).normalize();
        }
        return bezD(apex2, f1, f2, target, 1).normalize();
    }

    /** Speed in blocks per tick. */
    public double speed(double t) {
        return pos(t + 0.25).distanceTo(pos(t - 0.25)) / 0.5;
    }

    private double maxBoostSpeed() {
        double m = 0;
        for (int i = 1; i < BOOST; i += 5) m = Math.max(m, speed(i));
        return Math.max(m, 1e-3);
    }

    /** The Mach number shown for time t: about 4 at the end of the boost's fastest stretch, 13 at impact. */
    public double mach(double t) {
        if (t < BOOST) return 4.2 * speed(t) / vBoostMax;
        if (t < BOOST + STALL) return 0.3 * (1 - (t - BOOST) / STALL) + 0.05;
        return machMax * Math.min(1, speed(t) / Math.max(1e-3, vFallMax));
    }

    /** Altitude shown in kilometres (one block of height above the target is scaled to 60 m). */
    public double altitudeKm(double t) {
        return Math.max(0, (pos(t).y - target.y) * 0.06);
    }

    /** Where the ground camera stands for the last shot: off to one side of the approach, back from the target. */
    public Vec3d groundCam() {
        Vec3d side = new Vec3d(-h.z, 0, h.x);
        return target.add(h.multiply(-58)).add(side.multiply(46)).add(0, 3, 0);
    }

    /** Where the pad camera stands: beside and in front of the launcher. */
    public Vec3d padCam() {
        Vec3d side = new Vec3d(-h.z, 0, h.x);
        return launch.add(side.multiply(17)).add(h.multiply(12)).add(0, 1.5, 0);
    }

    /** Where the server keeps a riding player (so the world streams in around the camera). */
    public Vec3d anchor(double t) {
        if (t < RIDE) return padCam().add(0, 4, 0);
        if (t < GROUND) return pos(t);
        return groundCam().add(0, 4, 0);
    }

    public enum Phase {BOOST, STALL, FALL, DONE}

    public static Phase phase(double t) {
        return t < BOOST ? Phase.BOOST : t < BOOST + STALL ? Phase.STALL : t < TOTAL ? Phase.FALL : Phase.DONE;
    }
}
