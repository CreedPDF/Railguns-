package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.grs.Grs;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The Gungnir Railway Station as it really is: not a station on Jupiter's rings but the ring itself - one continuous
 * hull of black and gunmetal plate, 520 blocks wide and 220 thick, all the way round Jupiter at 1.55 Jupiter radii,
 * with orange light running along its seams. On its back runs the rail; over the rail stand coil gates every few
 * degrees, crowding into a tunnel of coils for the last forty degrees before the muzzle - the great gate that faces
 * Earth, with the command hub slung beneath it. Gungnir laps the rail at light speed, a white-hot point with an orange
 * wake that lights the whole ring as a thin line round the planet from far away.
 *
 * <p>Everything is built procedurally here, in Jupiter-relative blocks, and drawn at Jupiter's impostor scale so the
 * picture is exact from any distance. It is drawn in two halves: the half behind Jupiter's centre before the planet,
 * the near half afterwards with real depth.</p>
 */
public final class GrsRing {
    private GrsRing() {}

    public static final Identifier HULL = RailgunMod.id("textures/grs/ring_hull.png");
    public static final Identifier HULL_GLOW = RailgunMod.id("textures/grs/ring_hull_glow.png");
    public static final Identifier GATE = RailgunMod.id("textures/grs/ring_gate.png");
    public static final Identifier GATE_GLOW = RailgunMod.id("textures/grs/ring_gate_glow.png");
    public static final Identifier WHITE = RailgunMod.id("textures/grs/fx/white.png");

    /** Octagonal hull profile (rho, h) counter-clockwise, blocks. */
    private static final double[][] PROFILE;
    private static final double TILE = 420;

    static {
        double W = Grs.HULL_W, H = Grs.HULL_H, C = 36;
        PROFILE = new double[][]{{W, -(H - C)}, {W, H - C}, {W - C, H}, {-(W - C), H}, {-W, H - C}, {-W, -(H - C)}, {-(W - C), -H}, {W - C, -H}};
    }

    // ---- the spear on its lap (cutscene overrides)
    /** When set, the spear is drawn at this ring angle instead of its own lap. */
    public static Double spearAngle = null;
    /** When true, the spear has left the ring (the rail is dark behind it). */
    public static boolean away = false;
    /** Extra brightness of the whole rail (the cutscene's "it lights up the ring"), 0..1. */
    public static float charge = 0;
    /** Size of the spear's light (small while the camera rides right behind it). */
    public static float orbScale = 1f;
    /** How far round the ring the wake reaches, radians. */
    public static float wake = 1.1f;

    public static double lapAngle(float time) {
        return Grs.muzzleAngle() + time / Grs.LAP_TICKS * Math.PI * 2;
    }

    public static double spear(float time) {
        return spearAngle != null ? spearAngle : lapAngle(time);
    }

    // ------------------------------------------------------------------------------------------- frame

    /** Everything needed to place ring-frame points into the current render frame. */
    static final class Frame {
        final Matrix4f m;
        final Vector3f jc, ex, ey, ez, sun;
        final float s, jr;
        final Vec3d view;
        final float alpha;

        Frame(Matrix4f m, Vector3f jc, float jr, Quaternionf toLocal, Vector3f sun, Vec3d view, float alpha) {
            this.m = m;
            this.jc = jc;
            this.jr = jr;
            this.s = (float) (jr / Body.JUPITER.radius);
            this.ex = toLocal.transform(new Vector3f(1, 0, 0));
            this.ey = toLocal.transform(new Vector3f(0, 1, 0));
            this.ez = toLocal.transform(new Vector3f(0, 0, 1));
            this.sun = sun;
            this.view = view;
            this.alpha = alpha;
        }

        /** Jupiter-relative blocks (x, y, z) to render space. */
        Vector3f at(double x, double y, double z, Vector3f out) {
            float fx = (float) (x * s), fy = (float) (y * s), fz = (float) (z * s);
            return out.set(jc.x + ex.x * fx + ey.x * fy + ez.x * fz, jc.y + ex.y * fx + ey.y * fy + ez.y * fz, jc.z + ex.z * fx + ey.z * fy + ez.z * fz);
        }

        Vector3f ring(double th, double rho, double h, Vector3f out) {
            double r = Grs.ringRadius() + rho;
            return at(Math.cos(th) * r, h, Math.sin(th) * r, out);
        }

        /** A direction in the ring frame (radial component a, up b, tangent c) to render space. */
        Vector3f dir(double th, double a, double b, double c, Vector3f out) {
            double x = Math.cos(th) * a - Math.sin(th) * c, z = Math.sin(th) * a + Math.cos(th) * c;
            return out.set((float) (ex.x * x + ey.x * b + ez.x * z), (float) (ex.y * x + ey.y * b + ez.y * z), (float) (ex.z * x + ey.z * b + ez.z * z));
        }

        boolean back(Vector3f p) {
            return (p.x - jc.x) * jc.x + (p.y - jc.y) * jc.y + (p.z - jc.z) * jc.z > 0;
        }

        /** 1 lit, small in Jupiter's shadow. */
        float shade(Vector3f p) {
            float rx = p.x - jc.x, ry = p.y - jc.y, rz = p.z - jc.z;
            float along = rx * sun.x + ry * sun.y + rz * sun.z;
            if (along > 0) return 1;
            float px = rx - sun.x * along, py = ry - sun.y * along, pz = rz - sun.z * along;
            float d = (float) Math.sqrt(px * px + py * py + pz * pz) / jr;
            return d > 1.02f ? 1 : d < 0.97f ? 0.06f : 0.06f + 0.94f * (d - 0.97f) / 0.05f;
        }
    }

    private static double viewDist(Frame f, double th, double h) {
        return f.view.distanceTo(Grs.ringPoint(th, 0, h));
    }

    // ------------------------------------------------------------------------------------------- drawing

    /**
     * @param front false: the half behind Jupiter's centre (drawn before the planet); true: the near half
     */
    public static void draw(VertexConsumerProvider buf, Matrix4f m, Vec3d view, Quaternionf toLocal, Vector3f jc, float jr, Vector3f sun, float time,
                            float alpha, boolean front, boolean detail) {
        if (alpha < 0.02f) return;
        Frame f = new Frame(m, jc, jr, toLocal, new Vector3f(sun).normalize(), view, alpha);
        float ang = jr / Math.max(1e-3f, jc.length());
        int seg = ang > 0.35f ? 2048 : ang > 0.12f ? 1024 : ang > 0.03f ? 512 : 192;
        hull(buf.getBuffer(BeamRenderLayers.solid(HULL)), f, seg, front, false);
        hull(buf.getBuffer(BeamRenderLayers.textured(HULL_GLOW)), f, seg, front, true);
        edges(buf.getBuffer(BeamRenderLayers.textured(WHITE)), f, seg, front, time);
        if (detail) gates(buf, f, front, time);
        rail(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.BEAM)), f, seg, front, time);
        spear(buf, f, front, time);
        if (detail && front) hub(buf, f, time);
    }

    private static void hull(VertexConsumer vc, Frame f, int seg, boolean front, boolean glow) {
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f(), n = new Vector3f(), mid = new Vector3f();
        double R = Grs.ringRadius();
        double step = Math.PI * 2 / seg;
        int np = PROFILE.length;
        double[] vacc = new double[np + 1];
        for (int k = 0; k < np; k++) {
            double[] p0 = PROFILE[k], p1 = PROFILE[(k + 1) % np];
            vacc[k + 1] = vacc[k] + Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
        }
        float gk = f.alpha * 0.9f;
        for (int i = 0; i < seg; i++) {
            double t0 = i * step, t1 = t0 + step, tm = t0 + step / 2;
            f.ring(tm, 0, 0, mid);
            if (f.back(mid) == front) continue;
            float u0 = (float) (t0 * R / TILE), u1 = (float) (t1 * R / TILE);
            float lit = f.shade(mid);
            for (int k = 0; k < np; k++) {
                double[] p0 = PROFILE[k], p1 = PROFILE[(k + 1) % np];
                double er = p1[0] - p0[0], eh = p1[1] - p0[1];
                double len = Math.hypot(er, eh);
                double nr = eh / len, nh = -er / len;
                f.dir(tm, nr, nh, 0, n);
                f.ring(tm, p0[0] * 0.5 + p1[0] * 0.5, p0[1] * 0.5 + p1[1] * 0.5, c);
                // back-face cull against the eye at the origin
                if (n.x * c.x + n.y * c.y + n.z * c.z > 0) continue;
                f.ring(t0, p0[0], p0[1], a);
                f.ring(t1, p0[0], p0[1], b);
                f.ring(t1, p1[0], p1[1], c);
                f.ring(t0, p1[0], p1[1], d);
                float v0 = (float) (vacc[k] / TILE), v1 = (float) (vacc[k + 1] / TILE);
                float r, g, bl;
                if (glow) {
                    r = g = bl = gk * (0.55f + 0.45f * lit) * (1 + charge * 0.8f);
                } else {
                    float diff = Math.max(0, n.dot(f.sun));
                    // Jupiter-shine on the faces that look at the planet
                    Vector3f toJ = new Vector3f(f.jc).sub(mid).normalize();
                    float js = Math.max(0, n.dot(toJ)) * 0.16f;
                    float k2 = 0.05f + 0.95f * diff * lit;
                    r = k2 + js;
                    g = k2 * 0.97f + js * 0.72f;
                    bl = k2 * 0.94f + js * 0.48f;
                }
                GrsDraw.v(vc, f.m, a.x, a.y, a.z, u0, v0, r, g, bl, 1);
                GrsDraw.v(vc, f.m, b.x, b.y, b.z, u1, v0, r, g, bl, 1);
                GrsDraw.v(vc, f.m, c.x, c.y, c.z, u1, v1, r, g, bl, 1);
                GrsDraw.v(vc, f.m, d.x, d.y, d.z, u0, v1, r, g, bl, 1);
            }
            // the rail's spine on the hull's back
            double rw = 22, h0 = Grs.HULL_H, h1 = Grs.RAIL_TOP;
            double[][] faces = {{-rw, h1, rw, h1, 0, 1}, {rw, h0, rw, h1, 1, 0}, {-rw, h1, -rw, h0, -1, 0}};
            for (double[] fc : faces) {
                f.dir(tm, fc[4], fc[5], 0, n);
                f.ring(tm, (fc[0] + fc[2]) / 2, (fc[1] + fc[3]) / 2, c);
                if (n.x * c.x + n.y * c.y + n.z * c.z > 0) continue;
                f.ring(t0, fc[0], fc[1], a);
                f.ring(t1, fc[0], fc[1], b);
                f.ring(t1, fc[2], fc[3], c);
                f.ring(t0, fc[2], fc[3], d);
                float k2 = glow ? gk * 0.5f : 0.05f + 0.8f * Math.max(0, n.dot(f.sun)) * lit;
                GrsDraw.v(vc, f.m, a.x, a.y, a.z, u0, 0, k2, k2, k2, 1);
                GrsDraw.v(vc, f.m, b.x, b.y, b.z, u1, 0, k2, k2, k2, 1);
                GrsDraw.v(vc, f.m, c.x, c.y, c.z, u1, 0.2f, k2, k2, k2, 1);
                GrsDraw.v(vc, f.m, d.x, d.y, d.z, u0, 0.2f, k2, k2, k2, 1);
            }
        }
    }

    /** Orange light along the hull's chamfer edges and round it every few hundred blocks. */
    private static void edges(VertexConsumer vc, Frame f, int seg, boolean front, float time) {
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f(), mid = new Vector3f();
        double step = Math.PI * 2 / seg;
        // edges seen from far would shrink below a pixel: widen them with distance
        int[] corners = {2, 3};
        for (int i = 0; i < seg; i++) {
            double t0 = i * step, t1 = t0 + step, tm = t0 + step / 2;
            f.ring(tm, 0, 0, mid);
            if (f.back(mid) == front) continue;
            float lit = 0.7f + 0.3f * f.shade(mid);
            float dist = mid.length();
            float w = Math.max(3f, dist / f.s * 0.0005f);
            float pulse = 0.75f + 0.25f * MathHelper.sin((float) (tm * 40 - time * 0.3));
            float k = f.alpha * lit * pulse * (0.45f + charge * 0.5f);
            for (int ci : corners) {
                double[] p = PROFILE[ci];
                double[] q = PROFILE[(ci + 1) % PROFILE.length];
                double er = q[0] - p[0], eh = q[1] - p[1], len = Math.hypot(er, eh);
                double ur = er / len * w, uh = eh / len * w;
                double nr = eh / len * 1.5, nh = -er / len * 1.5;
                f.ring(t0, p[0] + nr, p[1] + nh, a);
                f.ring(t1, p[0] + nr, p[1] + nh, b);
                f.ring(t1, p[0] + ur + nr, p[1] + uh + nh, c);
                f.ring(t0, p[0] + ur + nr, p[1] + uh + nh, d);
                GrsDraw.v(vc, f.m, a.x, a.y, a.z, 0, 0, k, 0.42f * k, 0.07f * k, 1);
                GrsDraw.v(vc, f.m, b.x, b.y, b.z, 1, 0, k, 0.42f * k, 0.07f * k, 1);
                GrsDraw.v(vc, f.m, c.x, c.y, c.z, 1, 1, k, 0.42f * k, 0.07f * k, 1);
                GrsDraw.v(vc, f.m, d.x, d.y, d.z, 0, 1, k, 0.42f * k, 0.07f * k, 1);
            }
        }
    }

    /** The rail's light, and the spear's wake running along it. */
    private static void rail(VertexConsumer vc, Frame f, int seg, boolean front, float time) {
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f(), mid = new Vector3f();
        double step = Math.PI * 2 / seg;
        double sp = spear(time);
        for (int i = 0; i < seg; i++) {
            double t0 = i * step, t1 = t0 + step, tm = t0 + step / 2;
            f.ring(tm, 0, Grs.RAIL_TOP, mid);
            if (f.back(mid) == front) continue;
            double behind = ((sp - tm) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
            float wk = away ? 0 : (float) Math.max(0, 1 - behind / wake);
            float k = f.alpha * (0.35f + 0.4f * charge + 1.4f * wk * wk);
            float dist = mid.length();
            float w = Math.max(10f, dist / f.s * 0.0008f) * (1 + wk);
            f.ring(t0, -w, Grs.RAIL_TOP + 1.5, a);
            f.ring(t1, -w, Grs.RAIL_TOP + 1.5, b);
            f.ring(t1, w, Grs.RAIL_TOP + 1.5, c);
            f.ring(t0, w, Grs.RAIL_TOP + 1.5, d);
            float v0 = (float) (t0 * 40 - time * 0.2), v1 = (float) (t1 * 40 - time * 0.2);
            GrsDraw.v(vc, f.m, a.x, a.y, a.z, 0, v0, k, 0.5f * k + wk * 0.3f * k, 0.12f * k + wk * 0.25f * k, 1);
            GrsDraw.v(vc, f.m, b.x, b.y, b.z, 0, v1, k, 0.5f * k + wk * 0.3f * k, 0.12f * k + wk * 0.25f * k, 1);
            GrsDraw.v(vc, f.m, c.x, c.y, c.z, 1, v1, k, 0.5f * k + wk * 0.3f * k, 0.12f * k + wk * 0.25f * k, 1);
            GrsDraw.v(vc, f.m, d.x, d.y, d.z, 1, v0, k, 0.5f * k + wk * 0.3f * k, 0.12f * k + wk * 0.25f * k, 1);
            // the wake in the air along the path, camera-facing, so it reads as a line of light round Jupiter
            if (wk > 0.01f || charge > 0.01f) {
                Vector3f p0 = f.ring(t0, 0, Grs.PATH_H, new Vector3f()), p1 = f.ring(t1, 0, Grs.PATH_H, new Vector3f());
                float db = dist / f.s;
                float near = MathHelper.clamp(db / 400f, 0, 1);
                near = near * near * (3 - 2 * near);
                float al = Math.min(1, wk * wk * 1.2f + charge * 0.45f) * f.alpha * near;
                float lw = Math.max(8f, db * 0.0012f) * f.s * (0.5f + wk);
                GrsDraw.streak(vc, f.m, p0, p1, new Vector3f(), lw, 1f, 0.55f + 0.35f * wk, 0.18f + 0.5f * wk * wk, al);
            }
        }
    }

    private static void spear(VertexConsumerProvider buf, Frame f, boolean front, float time) {
        if (away || orbScale <= 0.001f) return;
        double sp = spear(time);
        Vector3f p = f.ring(sp, 0, Grs.PATH_H, new Vector3f());
        if (f.back(p) == front) return;
        float dist = p.length();
        float s = Math.max(70 * f.s, dist * 0.006f) * orbScale;
        GrsDraw.sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.ORB)), f.m, p.x, p.y, p.z, s, 0, f.alpha, 0.85f * f.alpha, 0.6f * f.alpha, 1);
        GrsDraw.sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.FLARE)), f.m, p.x, p.y, p.z, s * 5, time * 0.05f, f.alpha, 0.5f * f.alpha,
                0.12f * f.alpha, 1);
    }

    // ------------------------------------------------------------------------------------------- coil gates

    /** Ring angles of the gates, relative to the muzzle: seven spaced round the ring, five closing in on the muzzle. */
    private static final double[] GATES;

    static {
        java.util.List<Double> g = new java.util.ArrayList<>();
        for (int k = 1; k < 8; k++) g.add(k * Math.PI / 4);
        for (int i = 1; i <= 5; i++) g.add(-Math.toRadians(1.6 * i));
        GATES = g.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** True if the angle th (absolute) falls inside the coil tunnel before the muzzle. */
    public static boolean inTunnel(double th) {
        double rel = MathHelper.wrapDegrees(Math.toDegrees(th - Grs.muzzleAngle()));
        return rel > -9 && rel < 0;
    }

    private static void gates(VertexConsumerProvider buf, Frame f, boolean front, float time) {
        double mz = Grs.muzzleAngle();
        double sp = spear(time);
        for (int pass = 0; pass < 2; pass++) {
            VertexConsumer vc = buf.getBuffer(pass == 0 ? BeamRenderLayers.solid(GATE) : BeamRenderLayers.textured(GATE_GLOW));
            for (double rel : GATES) {
                double th = mz + rel;
                double d = viewDist(f, th, Grs.PATH_H);
                if (d > 16000) continue;
                Vector3f cp = f.ring(th, 0, Grs.PATH_H, new Vector3f());
                if (f.back(cp) == front) continue;
                int n = d < 1500 ? 40 : d < 5000 ? 20 : 10;
                double behind = ((sp - th) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
                float hot = away ? 0 : (float) Math.max(0, 1 - behind / 0.25);
                gate(vc, f, th, Grs.GATE_IN, Grs.GATE_OUT, Grs.GATE_D, n, pass == 1, hot, time);
            }
            double d = viewDist(f, mz, Grs.PATH_H);
            Vector3f cp = f.ring(mz, 0, Grs.PATH_H, new Vector3f());
            if (d < 40000 && f.back(cp) != front) gate(vc, f, mz, Grs.MUZZLE_IN, Grs.MUZZLE_OUT, Grs.MUZZLE_D, d < 4000 ? 64 : 32, pass == 1, charge, time);
        }
    }

    /** One coil gate: an annulus round the path at angle th, with legs down to the rail. */
    private static void gate(VertexConsumer vc, Frame f, double th, double ri, double ro, double depth, int n, boolean glow, float hot, float time) {
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f(), nn = new Vector3f();
        double R = Grs.ringRadius();
        double dth = depth / R;
        for (int j = 0; j < n; j++) {
            double p0 = j / (double) n * Math.PI * 2, p1 = (j + 1) / (double) n * Math.PI * 2, pm = (p0 + p1) / 2;
            double c0 = Math.cos(p0), s0 = Math.sin(p0), c1 = Math.cos(p1), s1 = Math.sin(p1), cm = Math.cos(pm), sm = Math.sin(pm);
            boolean panel = (j * 8 / n) % 2 == 0 && ((j * 24 / n) % 3 != 2);
            // four faces: front, back (the annulus), outer and inner rims
            for (int face = 0; face < 4; face++) {
                double t0, t1, r0, r1;
                double nr, nh, nt;
                switch (face) {
                    case 0 -> { t0 = th - dth; t1 = th - dth; r0 = ri; r1 = ro; nr = 0; nh = 0; nt = -1; }
                    case 1 -> { t0 = th + dth; t1 = th + dth; r0 = ro; r1 = ri; nr = 0; nh = 0; nt = 1; }
                    case 2 -> { t0 = th - dth; t1 = th + dth; r0 = ro; r1 = ro; nr = cm; nh = sm; nt = 0; }
                    default -> { t0 = th + dth; t1 = th - dth; r0 = ri; r1 = ri; nr = -cm; nh = -sm; nt = 0; }
                }
                f.dir(th, nr, nh, nt, nn);
                Vector3f mid = f.ring((t0 + t1) / 2, cm * (r0 + r1) / 2, Grs.PATH_H + sm * (r0 + r1) / 2, new Vector3f());
                if (nn.x * mid.x + nn.y * mid.y + nn.z * mid.z > 0) continue;
                f.ring(t0, c0 * r0, Grs.PATH_H + s0 * r0, a);
                f.ring(t0, c1 * r0, Grs.PATH_H + s1 * r0, b);
                f.ring(t1, c1 * r1, Grs.PATH_H + s1 * r1, c);
                f.ring(t1, c0 * r1, Grs.PATH_H + s0 * r1, d);
                float u0 = j / (float) n * 8, u1 = (j + 1) / (float) n * 8;
                float k;
                if (glow) {
                    k = (face < 2 && panel ? 1f : face == 3 ? 0.55f + hot * 1.5f : 0.15f) * f.alpha * (0.7f + 0.3f * MathHelper.sin(time * 0.2f + j));
                    k *= 1 + hot * 2;
                } else {
                    float diff = Math.max(0, nn.dot(f.sun));
                    k = 0.06f + 0.9f * diff * f.shade(mid);
                }
                float v0 = face == 2 || face == 3 ? 0 : 0.5f, v1 = face == 2 || face == 3 ? 0.5f : 1f;
                GrsDraw.v(vc, f.m, a.x, a.y, a.z, u0, v0, k, glow ? 0.45f * k + hot * 0.4f : k, glow ? 0.08f * k + hot * 0.3f : k, 1);
                GrsDraw.v(vc, f.m, b.x, b.y, b.z, u1, v0, k, glow ? 0.45f * k + hot * 0.4f : k, glow ? 0.08f * k + hot * 0.3f : k, 1);
                GrsDraw.v(vc, f.m, c.x, c.y, c.z, u1, v1, k, glow ? 0.45f * k + hot * 0.4f : k, glow ? 0.08f * k + hot * 0.3f : k, 1);
                GrsDraw.v(vc, f.m, d.x, d.y, d.z, u0, v1, k, glow ? 0.45f * k + hot * 0.4f : k, glow ? 0.08f * k + hot * 0.3f : k, 1);
            }
        }
        if (glow) return;
        // two legs down to the rail
        double legW = (ro - ri) * 0.6;
        for (int side = -1; side <= 1; side += 2) {
            double rho = side * ri * 0.62;
            double top = Grs.PATH_H - ri * 0.78, bot = Grs.RAIL_TOP;
            if (top <= bot + 2) continue;
            box(vc, f, th, rho - legW / 2, rho + legW / 2, bot, top, depth * 0.8);
        }
    }

    private static void box(VertexConsumer vc, Frame f, double th, double r0, double r1, double h0, double h1, double depth) {
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f(), nn = new Vector3f(), mid = new Vector3f();
        double dt = depth / Grs.ringRadius();
        double ta = th - dt, tb = th + dt;
        double[][] faces = {
                {ta, r0, h0, ta, r1, h0, ta, r1, h1, ta, r0, h1, 0, 0, -1},
                {tb, r1, h0, tb, r0, h0, tb, r0, h1, tb, r1, h1, 0, 0, 1},
                {ta, r1, h0, tb, r1, h0, tb, r1, h1, ta, r1, h1, 1, 0, 0},
                {tb, r0, h0, ta, r0, h0, ta, r0, h1, tb, r0, h1, -1, 0, 0}};
        for (double[] q : faces) {
            f.dir(th, q[12], q[13], q[14], nn);
            f.ring(th, (r0 + r1) / 2 + q[12] * (r1 - r0) / 2, (h0 + h1) / 2, mid);
            if (nn.x * mid.x + nn.y * mid.y + nn.z * mid.z > 0) continue;
            float k = 0.05f + 0.85f * Math.max(0, nn.dot(f.sun)) * f.shade(mid);
            f.ring(q[0], q[1], q[2], a);
            f.ring(q[3], q[4], q[5], b);
            f.ring(q[6], q[7], q[8], c);
            f.ring(q[9], q[10], q[11], d);
            GrsDraw.v(vc, f.m, a.x, a.y, a.z, 0, 0.5f, k, k, k, 1);
            GrsDraw.v(vc, f.m, b.x, b.y, b.z, 1, 0.5f, k, k, k, 1);
            GrsDraw.v(vc, f.m, c.x, c.y, c.z, 1, 1, k, k, k, 1);
            GrsDraw.v(vc, f.m, d.x, d.y, d.z, 0, 1, k, k, k, 1);
        }
    }

    /** The command hub slung beneath the hull at the muzzle (the station model, at three times size). */
    private static void hub(VertexConsumerProvider buf, Frame f, float time) {
        if (GrsModels.STATION == null) return;
        double mz = Grs.muzzleAngle();
        if (viewDist(f, mz, 0) > 30000) return;
        Vector3f c = f.ring(mz, 0, -Grs.HULL_H - 75, new Vector3f());
        MatrixStack ms = new MatrixStack();
        ms.peek().getPositionMatrix().set(f.m);
        ms.translate(c.x, c.y, c.z);
        float sc = f.s * 2.4f;
        ms.scale(sc, sc, sc);
        // face the hub's dish outward, ring plane level
        Vector3f radial = f.dir(mz, 1, 0, 0, new Vector3f());
        ms.multiply(new Quaternionf().rotationTo(new Vector3f(1, 0, 0), radial));
        Vector3f sun = f.sun;
        GrsMesh.Shade shade = (nx, ny, nz, o) -> {
            float s = Math.max(0, nx * sun.x + ny * sun.y + nz * sun.z);
            o[0] = (0.05f + 0.95f * s) * f.alpha;
            o[1] = (0.05f + 0.92f * s) * f.alpha;
            o[2] = (0.05f + 0.88f * s) * f.alpha;
            o[3] = 1;
        };
        GrsModels.STATION.renderNoOverlay(ms, buf.getBuffer(BeamRenderLayers.solid(GrsModels.STATION_TEX)), GrsDraw.FULL, null, shade);
        float g = (0.85f + 0.15f * MathHelper.sin(time * 0.2f)) * f.alpha;
        GrsModels.STATION.renderNoOverlay(ms, buf.getBuffer(BeamRenderLayers.textured(GrsModels.STATION_GLOW)), GrsDraw.FULL, null, (nx, ny, nz, o) -> {
            o[0] = g;
            o[1] = g;
            o[2] = g;
            o[3] = 1;
        });
    }
}
