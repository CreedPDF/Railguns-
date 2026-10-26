package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.client.config.Quality;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.krl.Krl;
import com.creedpdf.orbitalrailgun.krl.KrlFlight;
import com.creedpdf.orbitalrailgun.krl.KrlImpact;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a KRL flight looks like, drawn from the flight's time alone:
 * <ul>
 *   <li>the minute on the pad - the motor held down on the rail, its flame driven into the trench and out of the front of
 *   the pad, the deluge flashing to steam that rolls out in a growing white bank;</li>
 *   <li>liftoff - a flash, the sonic boom's condensation collar round the spear and its shock ring racing out across the
 *   ground, the steam blown out in every direction;</li>
 *   <li>the climb - the plume with its shock diamonds, a white contrail that hangs in the sky and drifts, the vapour cone
 *   as the spear goes through Mach 1, burnout;</li>
 *   <li>the stall - attitude-control puffs as it noses over;</li>
 *   <li>the fall - the nose glowing white-hot inside its plasma sheath as it closes on Mach 13 (seen from the ground only
 *   at night);</li>
 *   <li>the impact - the flash, a plasma fireball that cools from white through orange to deep red, the shock ring and
 *   condensation shell, a rising column of dust and smoke, molten spatter, and a crater floor that glows for minutes.</li>
 * </ul>
 */
public final class KrlFx {
    private KrlFx() {}

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 20));
    private static final Random RND = Random.create();
    static final float BURNOUT = KrlFlight.BOOST * 0.82f;

    // ------------------------------------------------------------------------------------------- particles

    static final class Puff {
        double x, y, z;
        float vx, vy, vz, age, life, s0, s1, rot, spin, gray, alpha, heat, drag, buoy;
        int tile;
        double d2;
    }

    static final class Spark {
        double x, y, z;
        float vx, vy, vz, age, life, size;
    }

    private static final List<Puff> PUFFS = new ArrayList<>();
    private static final List<Spark> SPARKS = new ArrayList<>();

    private static int cap() {
        return (int) (2400 * Math.max(0.3f, Quality.fx()));
    }

    static void puff(double x, double y, double z, double vx, double vy, double vz, float life, float s0, float s1, float gray, float alpha, float heat,
                     float drag, float buoy) {
        if (PUFFS.size() >= cap()) return;
        Puff p = new Puff();
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = (float) vx;
        p.vy = (float) vy;
        p.vz = (float) vz;
        p.life = life;
        p.s0 = s0;
        p.s1 = s1;
        p.rot = RND.nextFloat() * 6.283f;
        p.spin = (RND.nextFloat() - 0.5f) * 0.01f;
        p.gray = gray;
        p.alpha = alpha;
        p.heat = heat;
        p.drag = drag;
        p.buoy = buoy;
        p.tile = RND.nextInt(4);
        PUFFS.add(p);
    }

    static void spark(double x, double y, double z, double vx, double vy, double vz, float life, float size) {
        if (SPARKS.size() > 600) return;
        Spark s = new Spark();
        s.x = x;
        s.y = y;
        s.z = z;
        s.vx = (float) vx;
        s.vy = (float) vy;
        s.vz = (float) vz;
        s.life = life;
        s.size = size;
        SPARKS.add(s);
    }

    static float r(float a, float b) {
        return a + RND.nextFloat() * (b - a);
    }

    static double rs() {
        return RND.nextDouble() * 2 - 1;
    }

    public static void clear() {
        PUFFS.clear();
        SPARKS.clear();
    }

    /** High-altitude wind that carries contrails. */
    private static float windX(double y) {
        return (float) (0.012 + Math.max(0, y - 80) * 0.00012);
    }

    private static float windZ(double y) {
        return (float) (0.006 + Math.max(0, y - 80) * 0.00005);
    }

    static void tickParticles() {
        for (int i = PUFFS.size() - 1; i >= 0; i--) {
            Puff p = PUFFS.get(i);
            p.age++;
            if (p.age >= p.life) {
                PUFFS.set(i, PUFFS.get(PUFFS.size() - 1));
                PUFFS.remove(PUFFS.size() - 1);
                continue;
            }
            p.vx = p.vx * p.drag + windX(p.y) * (1 - p.drag);
            p.vz = p.vz * p.drag + windZ(p.y) * (1 - p.drag);
            p.vy = p.vy * p.drag + p.buoy;
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            p.rot += p.spin;
            p.heat *= 0.955f;
        }
        for (int i = SPARKS.size() - 1; i >= 0; i--) {
            Spark s = SPARKS.get(i);
            s.age++;
            if (s.age >= s.life) {
                SPARKS.set(i, SPARKS.get(SPARKS.size() - 1));
                SPARKS.remove(SPARKS.size() - 1);
                continue;
            }
            s.vy -= 0.055f;
            s.vx *= 0.985f;
            s.vz *= 0.985f;
            s.x += s.vx;
            s.y += s.vy;
            s.z += s.vz;
        }
    }

    // ------------------------------------------------------------------------------------------- spawning

    static void tick(KrlClient.CFlight f, double t) {
        float q = Math.max(0.35f, Quality.fx());
        KrlFlight p = f.path;
        Vec3d front = f.front(), side = new Vec3d(-front.z, 0, front.x);
        Vec3d l = f.launch;
        // the minute on the pad: steam rolling out of the front of the flame trench
        if (t < 0 && t > -Krl.IGNITION) {
            double ramp = MathHelper.clamp((t + Krl.IGNITION) / 40.0, 0, 1);
            double n = (1.6 + 0.6 * Math.sin(t * 0.37)) * ramp * q;
            for (int i = 0; i < n + RND.nextFloat(); i++) {
                Vec3d at = l.add(front.multiply(1.8)).add(side.multiply(rs() * 0.9)).add(0, -0.15, 0);
                Vec3d v = front.multiply(r(0.3f, 0.62f)).add(side.multiply(rs() * 0.14));
                puff(at.x, at.y, at.z, v.x, r(0.01f, 0.06f), v.z, r(170, 300), r(1f, 1.6f), r(7f, 11f), r(0.86f, 0.95f), 0.5f, 0.9f, 0.968f, 0.0045f);
            }
            // a little escapes straight up past the spear
            if (RND.nextFloat() < 0.25f * ramp * q) puff(l.x + rs() * 1.2, l.y + 0.3, l.z + rs() * 1.2, 0, r(0.06f, 0.12f), 0, r(80, 140), 0.8f, 4f, 0.9f, 0.35f, 0.5f, 0.97f, 0.002f);
        }
        // liftoff: the steam bank is blown out in every direction
        if (t >= -4 && t < 36) {
            int n = (int) ((t < 12 ? 7 : 3) * q);
            for (int i = 0; i < n; i++) {
                double a = RND.nextDouble() * Math.PI * 2;
                double sp = r(0.45f, 1.0f) * (t < 12 ? 1.2 : 0.8);
                puff(l.x + Math.cos(a) * 1.5, l.y + r(-0.2f, 0.6f), l.z + Math.sin(a) * 1.5, Math.cos(a) * sp, r(0.02f, 0.08f), Math.sin(a) * sp, r(300, 460),
                        r(2f, 3f), r(11f, 16f), r(0.84f, 0.93f), 0.55f, 1.2f, 0.955f, 0.004f);
            }
        }
        if (t >= 0 && !f.boomed) {
            f.boomed = true;
            for (int i = 0; i < 40 * q; i++) {
                double a = RND.nextDouble() * Math.PI * 2, sp = r(1.2f, 2.2f);
                puff(l.x, l.y + 0.2, l.z, Math.cos(a) * sp, 0.02, Math.sin(a) * sp, r(140, 220), 1f, r(5f, 8f), 0.62f, 0.45f, 0.4f, 0.9f, 0.002f);
            }
        }
        // the contrail
        if (t > 0 && t <= BURNOUT + 2) {
            double t0 = Math.max(f.lastTrailT, 0.05), t1 = Math.min(t, BURNOUT);
            double tt = t0;
            while (tt < t1) {
                double sp = p.speed(tt);
                Vec3d d = p.dir(tt);
                Vec3d at = p.pos(tt).subtract(d.multiply(1.2));
                double alt = Math.max(0, at.y - l.y);
                float low = (float) MathHelper.clamp(1 - alt / 60, 0, 1);
                float s0 = (float) (0.9 + 0.35 * sp), s1 = (float) (s0 * 3.5 + alt / 45 + low * 6);
                Vec3d v = d.multiply(-0.05 - 0.05 * low).add(rs() * 0.02, rs() * 0.02, rs() * 0.02);
                puff(at.x + rs() * 0.3, at.y + rs() * 0.3, at.z + rs() * 0.3, v.x, v.y, v.z, r(420, 640) * (0.7f + 0.3f * q), s0, s1,
                        0.93f - low * 0.12f, 0.5f - (float) Math.min(0.2, alt / 2500), 1.1f, 0.985f, 0.0008f);
                tt += (1.6 + sp * 0.28) / Math.max(0.2, sp) / Math.max(0.5, q);
            }
            f.lastTrailT = tt;
        }
        // attitude-control puffs at the stall
        double ts = t - KrlFlight.BOOST;
        if (ts > 4 && ts < KrlFlight.STALL - 4 && ((int) t) % 5 == 0) {
            Vec3d tip = nose(f, t), d = p.dir(t);
            Vec3d sd = d.crossProduct(new Vec3d(0, 1, 0));
            if (sd.lengthSquared() < 1e-4) sd = new Vec3d(1, 0, 0);
            sd = sd.normalize().multiply(((int) t / 5) % 2 == 0 ? 1 : -1);
            Vec3d at = tip.subtract(d.multiply(1.1)).add(sd.multiply(0.3));
            for (int i = 0; i < 2; i++)
                puff(at.x, at.y, at.z, sd.x * 0.16 + rs() * 0.02, sd.y * 0.16, sd.z * 0.16 + rs() * 0.02, r(26, 40), 0.25f, 1.5f, 1f, 0.55f, 0, 0.9f, 0);
        }
        // impact
        if (t >= KrlFlight.TOTAL && !f.impacted) {
            f.impacted = true;
            impactBurst(f, q);
        }
        double ti = t - KrlFlight.TOTAL;
        if (ti >= 0 && ti < 60) {
            Vec3d c = f.target;
            double rise = 72 * (1 - Math.exp(-ti / 22));
            for (int i = 0; i < 3 * q; i++) {
                double a = RND.nextDouble() * Math.PI * 2, rr = r(0, 5f) + ti * 0.08;
                puff(c.x + Math.cos(a) * rr, c.y + rise * r(0.2f, 1f), c.z + Math.sin(a) * rr, Math.cos(a) * 0.05, 0.22 * Math.exp(-ti / 18) + 0.01,
                        Math.sin(a) * 0.05, r(520, 760), r(4f, 6f), r(15f, 22f), r(0.22f, 0.32f), 0.75f, (float) (1.3 * Math.exp(-ti / 26)), 0.975f, 0.0006f);
            }
            if (ti > 18) {
                // the cap spreading out
                for (int i = 0; i < 2 * q; i++) {
                    double a = RND.nextDouble() * Math.PI * 2, rr = 8 + (ti - 18) * 0.5;
                    puff(c.x + Math.cos(a) * rr, c.y + rise + r(-3, 4), c.z + Math.sin(a) * rr, Math.cos(a) * 0.12, 0.02, Math.sin(a) * 0.12, r(600, 800),
                            r(6f, 8f), r(18f, 26f), r(0.24f, 0.34f), 0.7f, (float) (0.8 * Math.exp(-ti / 30)), 0.985f, 0.0004f);
                }
            }
        }
        // the crater keeps smoking
        if (ti >= 60 && ti < 900 && RND.nextFloat() < 0.5f * q * (1 - ti / 900)) {
            Vec3d c = f.target;
            puff(c.x + rs() * 8, c.y - 3, c.z + rs() * 8, 0, r(0.05f, 0.12f), 0, r(200, 320), 2f, r(8f, 12f), 0.25f, 0.45f, 0.5f, 0.98f, 0.001f);
        }
    }

    private static void impactBurst(KrlClient.CFlight f, float q) {
        Vec3d c = f.target;
        for (int i = 0; i < 80 * q; i++) {
            double a = RND.nextDouble() * Math.PI * 2, sp = r(0.8f, 2.1f);
            puff(c.x + Math.cos(a) * 3, c.y + r(0, 2), c.z + Math.sin(a) * 3, Math.cos(a) * sp, r(0.02f, 0.1f), Math.sin(a) * sp, r(320, 520), r(3f, 4.5f),
                    r(12f, 18f), r(0.3f, 0.42f), 0.8f, 1.4f, 0.94f, 0.0018f);
        }
        for (int i = 0; i < 160 * q; i++) {
            double a = RND.nextDouble() * Math.PI * 2, el = r(0.15f, 1.3f), sp = r(1.0f, 3.6f);
            spark(c.x, c.y + 1, c.z, Math.cos(a) * Math.cos(el) * sp, Math.sin(el) * sp, Math.sin(a) * Math.cos(el) * sp, r(40, 110), r(0.25f, 0.6f));
        }
    }

    // ------------------------------------------------------------------------------------------- geometry

    /** 0 while the flight point is the nozzle (on the rail, climbing), 1 once it is the nose (falling). */
    static float shift(double t) {
        if (t <= KrlFlight.BOOST) return 0;
        return (float) smooth((t - KrlFlight.BOOST) / KrlFlight.STALL);
    }

    static double smooth(double x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** The nozzle exit in the world. */
    static Vec3d nozzle(KrlClient.CFlight f, double t) {
        if (t <= 0) return f.launch;
        return f.path.pos(t).subtract(f.path.dir(t).multiply(KrlModels.NOSE_TIP * shift(t)));
    }

    static Vec3d nose(KrlClient.CFlight f, double t) {
        return nozzle(f, t).add(f.path.dir(t).multiply(KrlModels.NOSE_TIP));
    }

    /** How hot the nose is, 0..1 (rises with the Mach number in the fall). */
    static float heat(KrlClient.CFlight f, double t) {
        if (t < KrlFlight.BOOST + KrlFlight.STALL || t >= KrlFlight.TOTAL) return 0;
        double m = f.path.mach(t);
        return (float) Math.pow(MathHelper.clamp((m - 3.5) / 9.5, 0, 1), 1.25);
    }

    static boolean isRider(KrlClient.CFlight f) {
        return KrlClient.riding == f.id;
    }

    /** Daylight hides the falling spear from anyone not riding it (or right next to it). */
    static float fallVis(KrlClient.CFlight f, double t, Vec3d cam) {
        if (isRider(f) && KrlCine.onSpear(t)) return 1;
        if (cam.distanceTo(nose(f, t)) < 40) return 1;
        return KrlClient.night();
    }

    // ------------------------------------------------------------------------------------------- render

    static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        float pt = ctx.tickDelta();
        KrlDraw.camera(ctx.camera());
        Vec3d cam = ctx.camera().getPos();
        MatrixStack ms = ctx.matrixStack();
        Vector3f eye = new Vector3f(0, 0, 0);

        boolean any = false;
        for (KrlClient.CFlight f : KrlClient.flights()) if (KrlClient.here(f)) any = true;
        if (KrlCine.active()) KrlCine.farTerrain(ctx);
        if (!any && PUFFS.isEmpty() && SPARKS.isEmpty()) return;

        ms.push();
        Matrix4f m = ms.peek().getPositionMatrix();
        // 1. the spears
        for (KrlClient.CFlight f : KrlClient.flights()) {
            if (!KrlClient.here(f)) continue;
            double t = f.t(pt);
            if (t >= KrlFlight.TOTAL) continue;
            if (t < 0) {
                // on the rail: the launcher's renderer draws it when it is close enough to be drawn at all
                if (cam.distanceTo(f.launch) < 180) continue;
            }
            float vis = t > KrlFlight.BOOST + KrlFlight.STALL ? fallVis(f, t, cam) : 1;
            if (vis < 0.03f) continue;
            drawSpear(ms, cam, f, t, vis);
        }
        BUF.draw();

        // 2. smoke (alpha blended, back to front)
        drawSmoke(m, cam, pt);

        // 3. light: flames, glows, sheath, shock rings, fireballs
        for (KrlClient.CFlight f : KrlClient.flights()) {
            if (!KrlClient.here(f)) continue;
            double t = f.t(pt);
            drawLight(ms, m, cam, eye, f, t, pt);
        }
        drawSparks(m, cam, pt);
        ms.pop();
    }

    private static void drawSpear(MatrixStack ms, Vec3d cam, KrlClient.CFlight f, double t, float vis) {
        KrlMesh mesh = KrlModels.SPEAR;
        if (mesh == null) return;
        Vec3d n = nozzle(f, t), d = t <= 0 ? new Vec3d(0, 1, 0) : f.path.dir(t);
        ms.push();
        ms.translate(n.x - cam.x, n.y - cam.y, n.z - cam.z);
        Quaternionf q = new Quaternionf().rotationTo(0, 1, 0, (float) d.x, (float) d.y, (float) d.z);
        q.rotateY((float) (Math.max(0, t) * 0.045));
        ms.multiply(q);
        var w = KrlClient.world();
        int light = WorldRenderer.getLightmapCoordinates(w, BlockPos.ofFloored(n.add(d.multiply(3))));
        float h = heat(f, t);
        mesh.render(ms, BUF.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.SPEAR_TEX)), light, null, (nx, ny, nz, o) -> {
            o[0] = vis;
            o[1] = vis * (1 - h * 0.35f);
            o[2] = vis * (1 - h * 0.5f);
            o[3] = 1;
        });
        mesh.render(ms, BUF.getBuffer(RenderLayer.getEyes(KrlModels.SPEAR_GLOW)), light, null, (nx, ny, nz, o) -> {
            o[0] = vis;
            o[1] = vis;
            o[2] = vis;
            o[3] = 1;
        });
        ms.pop();
    }

    private static void drawSmoke(Matrix4f m, Vec3d cam, float pt) {
        if (PUFFS.isEmpty()) return;
        for (Puff p : PUFFS) {
            double dx = p.x - cam.x, dy = p.y - cam.y, dz = p.z - cam.z;
            p.d2 = dx * dx + dy * dy + dz * dz;
        }
        PUFFS.sort((a, b) -> Double.compare(b.d2, a.d2));
        float day = KrlClient.daylight();
        float amb = 0.13f + 0.87f * day;
        VertexConsumer vc = BUF.getBuffer(BeamRenderLayers.translucent(KrlDraw.SMOKE));
        for (Puff p : PUFFS) {
            float a = (p.age + pt) / p.life;
            float s = MathHelper.lerp((float) (1 - Math.pow(1 - a, 2.4)), p.s0, p.s1);
            float al = p.alpha * Math.min(1, (p.age + pt) / 6f) * (float) Math.pow(1 - a, 1.6);
            if (al < 0.01f) continue;
            float h = Math.min(1.2f, p.heat);
            float r = MathHelper.clamp(p.gray * amb + h * 0.9f, 0, 1), g = MathHelper.clamp(p.gray * amb + h * 0.42f, 0, 1),
                    b = MathHelper.clamp(p.gray * amb * 0.98f + h * 0.12f, 0, 1);
            float u0 = (p.tile % 2) * 0.5f, v0 = (p.tile / 2) * 0.5f;
            double px = p.x + p.vx * pt - cam.x, py = p.y + p.vy * pt - cam.y, pz = p.z + p.vz * pt - cam.z;
            KrlDraw.spriteUv(vc, m, (float) px, (float) py, (float) pz, s, p.rot, u0, v0, u0 + 0.5f, v0 + 0.5f, r, g, b, al);
        }
        BUF.draw();
    }

    private static void drawSparks(Matrix4f m, Vec3d cam, float pt) {
        if (SPARKS.isEmpty()) return;
        VertexConsumer vc = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.TRAIL));
        Vector3f eye = new Vector3f();
        for (Spark s : SPARKS) {
            float a = (s.age + pt) / s.life;
            float k = 1 - a;
            Vector3f head = new Vector3f((float) (s.x + s.vx * pt - cam.x), (float) (s.y + s.vy * pt - cam.y), (float) (s.z + s.vz * pt - cam.z));
            Vector3f tail = new Vector3f(head).sub(s.vx * 2.5f, s.vy * 2.5f, s.vz * 2.5f);
            KrlDraw.streak(vc, m, tail, head, eye, s.size * (0.6f + 0.4f * k), 1f, 0.55f * k + 0.25f, 0.18f * k, k);
        }
        BUF.draw();
    }

    private static Vector3f rel(Vec3d p, Vec3d cam) {
        return new Vector3f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z));
    }

    /** At least this big on screen (so a distant flame or a star-like falling spear stays a point of light). */
    private static float minSize(float s, Vec3d p, Vec3d cam, float k) {
        return Math.max(s, (float) (cam.distanceTo(p) * k));
    }

    private static void drawLight(MatrixStack ms, Matrix4f m, Vec3d cam, Vector3f eye, KrlClient.CFlight f, double t, float pt) {
        KrlFlight p = f.path;
        float flick = 0.9f + 0.1f * MathHelper.sin((float) t * 2.7f) * MathHelper.sin((float) t * 1.3f + 1);
        // ---- the motor held down on the pad
        if (t < 0 && t > -Krl.IGNITION) {
            float ramp = (float) MathHelper.clamp((t + Krl.IGNITION) / 30.0, 0, 1);
            Vec3d l = f.launch, front = f.front();
            Vector3f a = rel(l, cam);
            VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.FLAME));
            KrlDraw.beam(fl, m, a, rel(l.add(0, -0.45, 0), cam), eye, 0.42f, 0, 0.45f, 1f, 0.78f, 0.5f, ramp * flick);
            KrlDraw.beam(fl, m, rel(l.add(0, -0.25, 0), cam), rel(l.add(front.multiply(6.5)).add(0, -0.1, 0), cam), eye, 0.9f * ramp, 0, 6.5f, 1f, 0.55f, 0.22f,
                    0.8f * ramp * flick);
            BUF.draw();
            VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
            Vector3f g0 = rel(l.add(front.multiply(2.5)).add(0, 0.2, 0), cam);
            KrlDraw.sprite(gl, m, g0.x, g0.y, g0.z, 6f, 0, 1f, 0.5f, 0.18f, 0.55f * ramp * flick);
            KrlDraw.sprite(gl, m, g0.x, g0.y, g0.z, 16f, 0, 1f, 0.35f, 0.1f, (0.12f + 0.25f * KrlClient.night()) * ramp * flick);
            KrlDraw.sprite(gl, m, a.x, a.y - 0.2f, a.z, 1.6f, 0, 1f, 0.9f, 0.7f, 0.9f * ramp);
            BUF.draw();
        }
        // ---- liftoff: flash, sonic-boom collar and ground shock ring
        if (t >= 0 && t < 40) {
            Vec3d l = f.launch;
            float tb = (float) t;
            VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.FLARE));
            Vector3f a = rel(l.add(0, 1.5, 0), cam);
            if (tb < 6) KrlDraw.sprite(gl, m, a.x, a.y, a.z, 22f * (1 - tb / 6f) + 4, 0, 1f, 0.9f, 0.75f, 1 - tb / 6f);
            BUF.draw();
            VertexConsumer ring = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.RING));
            float rr = 3 + tb * 3.4f;
            KrlDraw.flat(ring, m, rel(l.add(0, 0.25, 0), cam), new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), rr, 1f, 0.92f, 0.85f,
                    0.7f * (float) Math.pow(1 - tb / 40f, 1.6));
            BUF.draw();
            if (tb < 16) {
                // the condensation collar the boom leaves round the spear as it clears the tower
                Vec3d mid = nozzle(f, t).add(0, 3.2, 0);
                VertexConsumer vap = BUF.getBuffer(BeamRenderLayers.translucent(KrlDraw.VAPOR));
                float k = tb / 16f;
                float day = 0.35f + 0.65f * KrlClient.daylight();
                KrlDraw.flat(vap, m, rel(mid, cam), new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), 2.2f + tb * 0.9f, day, day, day, 0.8f * (1 - k) * (1 - k));
                KrlDraw.sprite(vap, m, (float) (mid.x - cam.x), (float) (mid.y - cam.y), (float) (mid.z - cam.z), 2f + tb * 0.75f, tb * 0.05f, day, day, day,
                        0.55f * (1 - k));
                BUF.draw();
            }
        }
        // ---- the climb
        if (t >= 0 && t < KrlFlight.BOOST) {
            Vec3d n = nozzle(f, t), d = p.dir(t);
            double sp = p.speed(t);
            double alt = Math.max(0, n.y - f.launch.y);
            float burn = (float) MathHelper.clamp((BURNOUT - t) / 18.0, 0, 1);
            if (burn > 0) {
                float len = (float) (5 + 16 * Math.min(1, sp / 4) + alt / 60);
                float wid = (float) (0.55 + 0.4 * Math.min(1, sp / 4) + alt / 400) * burn;
                Vec3d tail = n.subtract(d.multiply(len * (0.6 + 0.4 * burn)));
                VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.FLAME));
                KrlDraw.beam(fl, m, rel(n, cam), rel(tail, cam), eye, wid, 0, (float) n.distanceTo(tail), 1f, 0.72f, 0.42f, burn * flick);
                KrlDraw.beam(fl, m, rel(n, cam), rel(n.subtract(d.multiply(len * 0.35)), cam), eye, wid * 0.45f, 0, len * 0.35f, 1f, 0.95f, 0.85f, burn);
                BUF.draw();
                VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
                Vector3f a = rel(n.subtract(d.multiply(0.6)), cam);
                float s = minSize(2.4f, n, cam, 0.006f);
                KrlDraw.sprite(gl, m, a.x, a.y, a.z, s, 0, 1f, 0.75f, 0.45f, 0.9f * burn);
                KrlDraw.sprite(gl, m, a.x, a.y, a.z, s * 3.2f, 0, 1f, 0.45f, 0.15f, (0.18f + 0.3f * KrlClient.night()) * burn);
                BUF.draw();
            } else if (t < BURNOUT + 40) {
                // the nozzle cooling after burnout
                float k = (float) (1 - (t - BURNOUT) / 40);
                VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
                Vector3f a = rel(n, cam);
                KrlDraw.sprite(gl, m, a.x, a.y, a.z, 0.8f, 0, 1f, 0.3f, 0.08f, 0.6f * k);
                BUF.draw();
            }
            // the vapour cone as it goes through Mach 1
            if (f.mach1 > 0 && t >= f.mach1 - 2 && t < f.mach1 + 14) {
                float k = (float) ((t - f.mach1 + 2) / 16);
                Vec3d mid = n.add(d.multiply(3.3));
                Vector3f e1 = new Vector3f(), e2 = new Vector3f();
                basis(d, e1, e2);
                float day = 0.35f + 0.65f * KrlClient.daylight();
                VertexConsumer vap = BUF.getBuffer(BeamRenderLayers.translucent(KrlDraw.VAPOR));
                for (int i = 0; i < 3; i++) {
                    Vec3d c = mid.subtract(d.multiply(i * 0.7));
                    KrlDraw.flat(vap, m, rel(c, cam), e1, e2, 1.3f + i * 0.55f + k * 0.8f, day, day, day, 0.7f * (float) Math.sin(Math.PI * k) * (1 - i * 0.25f));
                }
                BUF.draw();
            }
        }
        // ---- the fall: the plasma sheath round the nose
        float h = heat(f, t);
        if (h > 0.01f && t < KrlFlight.TOTAL) {
            float vis = fallVis(f, t, cam);
            if (vis > 0.02f) drawSheath(ms, m, cam, eye, f, t, h * vis);
        }
        // ---- the impact
        double ti = t - KrlFlight.TOTAL;
        if (ti >= 0) drawImpact(m, cam, eye, f, (float) ti);
    }

    static void basis(Vec3d d, Vector3f e1, Vector3f e2) {
        Vector3f dv = new Vector3f((float) d.x, (float) d.y, (float) d.z);
        Vector3f ref = Math.abs(d.y) < 0.9 ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        dv.cross(ref, e1).normalize();
        dv.cross(e1, e2).normalize();
    }

    private static void drawSheath(MatrixStack ms, Matrix4f m, Vec3d cam, Vector3f eye, KrlClient.CFlight f, double t, float h) {
        KrlMesh mesh = KrlModels.SPEAR;
        Vec3d n = nozzle(f, t), d = f.path.dir(t), tip = nose(f, t);
        float time = (float) t;
        // white-hot at the very tip, orange and red further out in the sheath
        if (mesh != null) {
            ms.push();
            ms.translate(n.x - cam.x, n.y - cam.y, n.z - cam.z);
            ms.multiply(new Quaternionf().rotationTo(0, 1, 0, (float) d.x, (float) d.y, (float) d.z).rotateY((float) (t * 0.045)));
            float base = KrlModels.NOSE_FROM;
            float[][] shells = {{1.02f, 1.0f, 0.95f, 0.85f, 0.55f}, {1.10f, 1.0f, 0.7f, 0.35f, 0.42f}, {1.24f, 1.0f, 0.42f, 0.12f, 0.3f},
                    {1.45f, 0.9f, 0.18f, 0.05f, 0.2f}};
            VertexConsumer vc = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.WHITE_TEX));
            for (int i = 0; i < shells.length; i++) {
                float[] sh = shells[i];
                float s = 1 + (sh[0] - 1) * (0.5f + 0.5f * h) + 0.015f * MathHelper.sin(time * 1.9f + i);
                float al = sh[4] * h;
                ms.push();
                ms.translate(0, base, 0);
                ms.scale(s, 1 + (s - 1) * 0.6f, s);
                ms.translate(0, -base, 0);
                mesh.renderNoOverlay(ms, vc, KrlDraw.FULL, (part, st) -> part.equals("nose"), (nx, ny, nz, o) -> {
                    float rim = 1 - Math.abs(nz);
                    float k = al * (0.35f + 0.65f * rim * rim);
                    o[0] = sh[1] * k;
                    o[1] = sh[2] * k;
                    o[2] = sh[3] * k;
                    o[3] = 1;
                });
                ms.pop();
            }
            ms.pop();
            BUF.draw();
        }
        // the glow wrapped round the tip, and a short hot wake along the body
        VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
        Vector3f a = rel(tip.subtract(d.multiply(0.5)), cam);
        float far = minSize(1.2f + 1.6f * h, tip, cam, 0.0055f * h);
        KrlDraw.sprite(gl, m, a.x, a.y, a.z, far, 0, 1f, 0.85f, 0.65f, 0.95f * h);
        KrlDraw.sprite(gl, m, a.x, a.y, a.z, far * 2.6f, 0, 1f, 0.42f, 0.15f, 0.45f * h);
        BUF.draw();
        VertexConsumer pl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.PLASMA));
        Vector3f b0 = rel(tip.subtract(d.multiply(0.4)), cam), b1 = rel(tip.subtract(d.multiply(4.5 + 5 * h)), cam);
        KrlDraw.beam(pl, m, b0, b1, eye, 0.45f + 0.35f * h, -time * 0.35f, 3f, 1f, 0.45f, 0.14f, 0.55f * h * h);
        BUF.draw();
    }

    /** White -> yellow -> orange -> deep red as k goes 1 -> 0. */
    static float[] fireColor(float k) {
        k = MathHelper.clamp(k, 0, 1);
        float[][] st = {{0f, 0.35f, 0.04f, 0.02f}, {0.25f, 0.85f, 0.2f, 0.05f}, {0.5f, 1f, 0.45f, 0.1f}, {0.75f, 1f, 0.75f, 0.35f}, {1f, 1f, 0.96f, 0.88f}};
        for (int i = 1; i < st.length; i++) {
            if (k <= st[i][0]) {
                float u = (k - st[i - 1][0]) / (st[i][0] - st[i - 1][0]);
                return new float[]{MathHelper.lerp(u, st[i - 1][1], st[i][1]), MathHelper.lerp(u, st[i - 1][2], st[i][2]), MathHelper.lerp(u, st[i - 1][3], st[i][3])};
            }
        }
        return new float[]{1, 0.96f, 0.88f};
    }

    private static void drawImpact(Matrix4f m, Vec3d cam, Vector3f eye, KrlClient.CFlight f, float ti) {
        Vec3d c = f.target;
        // flash
        if (ti < 10) {
            VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.FLARE));
            Vector3f a = rel(c.add(0, 4, 0), cam);
            float k = 1 - ti / 10f;
            KrlDraw.sprite(fl, m, a.x, a.y, a.z, minSize(70 * (float) Math.sqrt(k) + 10, c, cam, 0.06f), 0, 1, 0.95f, 0.9f, k);
            BUF.draw();
        }
        // the fireball
        if (ti < 150) {
            float R = (float) (5 + 23 * (1 - Math.exp(-ti / 5)));
            float temp = (float) Math.exp(-ti / 30);
            float[] col = fireColor(temp);
            float al = MathHelper.clamp(1 - (ti - 60) / 90f, 0, 1);
            Vec3d ctr = c.add(0, R * 0.5 + ti * 0.14, 0);
            Vector3f a = rel(ctr, cam);
            VertexConsumer pl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.PLASMA));
            for (int i = 0; i < 4; i++) {
                float rot = ti * (0.012f + i * 0.006f) * (i % 2 == 0 ? 1 : -1) + i * 1.7f;
                KrlDraw.sprite(pl, m, a.x, a.y, a.z, R * (0.75f + i * 0.12f), rot, col[0], col[1], col[2], al * (0.85f - i * 0.12f));
            }
            BUF.draw();
            VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
            KrlDraw.sprite(gl, m, a.x, a.y, a.z, R * 1.9f, 0, col[0], col[1] * 0.8f, col[2] * 0.6f, al * 0.6f);
            KrlDraw.sprite(gl, m, a.x, a.y, a.z, R * 4f, 0, 1f, 0.4f, 0.12f, al * 0.2f * (0.4f + KrlClient.night()));
            BUF.draw();
        }
        // ground shock ring and the condensation shell
        if (ti < 40) {
            float k = ti / 40f;
            VertexConsumer ring = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.RING));
            KrlDraw.flat(ring, m, rel(c.add(0, 0.4, 0), cam), new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), 6 + ti * 4.4f, 1f, 0.7f, 0.4f,
                    0.85f * (float) Math.pow(1 - k, 1.5));
            BUF.draw();
            if (ti < 26) {
                float kk = ti / 26f;
                float day = 0.3f + 0.7f * KrlClient.daylight();
                VertexConsumer vap = BUF.getBuffer(BeamRenderLayers.translucent(KrlDraw.VAPOR));
                Vector3f a = rel(c.add(0, 8 + ti * 0.6, 0), cam);
                KrlDraw.sprite(vap, m, a.x, a.y, a.z, 8 + ti * 3.6f, ti * 0.01f, day, day, day, 0.5f * (float) Math.sin(Math.PI * kk));
                KrlDraw.flat(vap, m, rel(c.add(0, 1.5, 0), cam), new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), 8 + ti * 4.6f, day, day, day,
                        0.45f * (1 - kk));
                BUF.draw();
            }
        }
        // the crater floor, glowing as it cools
        if (ti < 1250) {
            float k = (float) Math.exp(-ti / 380);
            float fl = 0.85f + 0.15f * MathHelper.sin(ti * 0.21f) * MathHelper.sin(ti * 0.07f + 2);
            Vec3d floor = c.add(0, -KrlImpact.RADIUS * 0.62 + 0.15, 0);
            VertexConsumer cr = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.CRACKS));
            float[] col = fireColor(0.2f + 0.55f * k);
            KrlDraw.flat(cr, m, rel(floor, cam), new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), KrlImpact.RADIUS * 0.85f, col[0], col[1], col[2], k * fl);
            BUF.draw();
            VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
            Vector3f a = rel(floor.add(0, 2.5, 0), cam);
            KrlDraw.sprite(gl, m, a.x, a.y, a.z, KrlImpact.RADIUS * 1.6f, 0, 1f, 0.32f, 0.08f, 0.35f * k * fl * (0.3f + 0.7f * KrlClient.night()));
            BUF.draw();
        }
    }
}
