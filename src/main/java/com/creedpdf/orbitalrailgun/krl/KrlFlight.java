package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The spear's flight, as a pure function of time so the server, every client and the cutscene camera agree exactly:
 * <ol>
 *   <li><b>BOOST</b> - off the inclined rail (60 degrees, turned toward the target), accelerating, then a gravity turn; the motor burns
 *   out as it climbs into the stratosphere (apogee 380-760 blocks over the higher of launcher and target);</li>
 *   <li><b>STALL</b> - over the top of the arc it slows and pitches over smoothly, following its own flight path;</li>
 *   <li><b>FALL</b> - the dive, steepening to nearly vertical and accelerating all the way to impact.</li>
 * </ol>
 * The climb and the dive are cubic Bezier curves joined level at the apex, travelled at arc-length speeds that match there.
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
    /** Arc-length tables of the climb and the dive (distance along each curve at N+1 evenly spaced u). */
    private static final int N = 256;
    private final double[] upLen = new double[N + 1], downLen = new double[N + 1];
    /** Speed shape constants: climb scale k1 (blocks per unit shape), dive scale k2 and floor c. */
    private final double k1, k2, c;
    private final double vFallMax, vBoostMax, vApex;
    /** The climb runs to the apex at T_UP ticks, the dive from there to impact. */
    static final double T_UP = BOOST + STALL * 0.5, T_DOWN = TOTAL - T_UP;
    /** Speed at the apex, as a fraction of the climb's peak shape. */
    private static final double APEX_FRAC = 0.24;
    /** The motor burns out at this many ticks after liftoff (top speed). */
    public static final int BURNOUT = 92;
    /** Peak Mach number of the climb. */
    public static final double CLIMB_MACH = 8.0;

    /** The Mach number at impact for this missile's motor. */
    public final double machMax;

    public KrlFlight(Vec3d launch, Vec3d target) {
        this(launch, target, KrlLoadout.DEFAULT);
    }

    /**
     * One continuous ballistic arc: a climb off the rail curving over into level flight at the apex, then a dive that
     * steepens to nearly vertical over the target. The nose always points along the path, so the missile pitches over
     * smoothly through the top of the arc (it slows there but never stops).
     */
    public KrlFlight(Vec3d launch, Vec3d target, KrlLoadout missile) {
        this.machMax = missile.mach();
        this.launch = launch;
        this.target = target;
        Vec3d dh = new Vec3d(target.x - launch.x, 0, target.z - launch.z);
        dist = dh.length();
        h = dist < 1e-3 ? new Vec3d(0, 0, 1) : dh.multiply(1 / dist);
        double baseY = Math.max(launch.y, target.y);
        // up past the Karman line: 1300-2000 blocks over the higher end (shown as 78-120 km)
        height = MathHelper.clamp(1300 + 0.25 * dist, 1300, 2000) * missile.heightMul();
        apex = new Vec3d(launch.x + h.x * dist * 0.45, baseY + height, launch.z + h.z * dist * 0.45);
        apex2 = apex;
        rail = h.multiply(CE).add(0, SE, 0);
        // the arc's broad top: control points well ahead and behind the apex keep the pitch-over gentle
        double lead = Math.max(MathHelper.clamp(dist * 0.3, 70, 260), height * 0.22);
        b1 = launch.add(rail.multiply(Math.min(height * 0.55, dist * 0.6 + 60)));
        b2 = apex.subtract(h.multiply(lead));
        f1 = apex.add(h.multiply(lead * 1.1));
        // the dive: comes in from high above the target, nearly straight down at the end
        f2 = target.add(0, height * 0.55, 0).subtract(h.multiply(Math.min(dist * 0.03, 14)));
        tabulate(launch, b1, b2, apex, upLen);
        tabulate(apex, f1, f2, target, downLen);
        double l1 = upLen[N], l2 = downLen[N];
        // climb: accelerating to burnout, then slowing under gravity to APEX_FRAC of the peak at the apex
        double ia = 0;
        for (int i = 0; i < 400; i++) ia += upShape((i + 0.5) / 400) / 400;
        k1 = l1 / (T_UP * ia);
        // dive: speed = k2 (c + y^2); continuous with the climb at the apex (k2 c = apex speed)
        double q = k1 * upShape(1);
        double cc = l2 > q * T_DOWN * 1.05 ? q * T_DOWN / (3 * (l2 - q * T_DOWN)) : 4.0;
        c = cc;
        k2 = l2 / (T_DOWN * (cc + 1.0 / 3.0));
        vApex = k2 * c;
        vFallMax = k2 * (c + 1);
        double m = 0;
        for (int i = 1; i < T_UP; i += 3) m = Math.max(m, speed(i));
        vBoostMax = Math.max(m, 1e-3);
    }

    /** Climb speed shape over x = t / T_UP: off the rail at full thrust to top speed by burnout, then coasting up, slowing to APEX_FRAC. */
    private static double upShape(double x) {
        double xb = BURNOUT / T_UP;
        double a = x < xb ? Math.sin(Math.PI * 0.5 * x / xb) : 1;
        a = Math.pow(a, 1.35);
        double b = smooth((x - xb) / (1 - xb));
        return a * (1 - (1 - APEX_FRAC) * Math.pow(b, 0.8));
    }

    private static void tabulate(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double[] out) {
        Vec3d prev = p0;
        out[0] = 0;
        for (int i = 1; i <= N; i++) {
            Vec3d p = bez(p0, p1, p2, p3, i / (double) N);
            out[i] = out[i - 1] + p.distanceTo(prev);
            prev = p;
        }
    }

    /** The curve parameter u at which `d` blocks along it have been covered. */
    private static double uAt(double[] len, double d) {
        if (d <= 0) return 0;
        if (d >= len[N]) return 1;
        int lo = 0, hi = N;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (len[mid] < d) lo = mid;
            else hi = mid;
        }
        double span = len[hi] - len[lo];
        return (lo + (span < 1e-9 ? 0 : (d - len[lo]) / span)) / N;
    }

    /** Distance covered along the climb by time t (0..UP). */
    private double upDist(double t) {
        double x = MathHelper.clamp(t / T_UP, 0, 1);
        // Simpson over the speed shape
        int n = 24;
        double hh = x / n, sum = upShape(0) + upShape(x);
        for (int i = 1; i < n; i++) sum += upShape(i * hh) * (i % 2 == 0 ? 2 : 4);
        return k1 * T_UP * sum * hh / 3;
    }

    /** Distance covered along the dive by time t since the apex (0..DOWN). */
    private double downDist(double t) {
        double y = MathHelper.clamp(t / T_DOWN, 0, 1);
        return k2 * T_DOWN * (c * y + y * y * y / 3);
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
        if (t < T_UP) return bez(launch, b1, b2, apex, uAt(upLen, upDist(t)));
        if (t < TOTAL) return bez(apex, f1, f2, target, uAt(downLen, downDist(t - T_UP)));
        return target;
    }

    /** Where the nose points: always along the path. */
    public Vec3d dir(double t) {
        if (t <= 0) return rail;
        Vec3d d;
        if (t < T_UP) d = bezD(launch, b1, b2, apex, Math.max(0.002, uAt(upLen, upDist(t))));
        else d = bezD(apex, f1, f2, target, Math.min(1, uAt(downLen, downDist(Math.min(t, TOTAL) - T_UP))));
        return d.lengthSquared() < 1e-9 ? rail : d.normalize();
    }

    /** Speed in blocks per tick. */
    public double speed(double t) {
        if (t <= 0) return 0;
        if (t < T_UP) return k1 * upShape(t / T_UP);
        double y = MathHelper.clamp((t - T_UP) / T_DOWN, 0, 1);
        return k2 * (c + y * y);
    }

    /** The Mach number shown for time t: about 8 at burnout, slowing over the apex, up to the motor's top speed at impact. */
    public double mach(double t) {
        double apexMach = CLIMB_MACH * vApex / vBoostMax;
        if (t < T_UP) return CLIMB_MACH * speed(t) / vBoostMax;
        double k = (speed(t) - vApex) / Math.max(1e-3, vFallMax - vApex);
        return apexMach + (machMax - apexMach) * MathHelper.clamp(k, 0, 1);
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
