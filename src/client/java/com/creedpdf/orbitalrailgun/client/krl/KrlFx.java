package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.client.config.Quality;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.krl.Krl;
import com.creedpdf.orbitalrailgun.krl.KrlFlight;
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
import net.minecraft.world.Heightmap;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * What a KRL flight looks like, drawn from the flight's time alone:
 * <ul>
 *   <li>ignition - the motor lights on the inclined rail; its 3D plume ({@link KrlFlame}) hits the ground behind the
 *   launcher and splashes out across it, for two seconds;</li>
 *   <li>liftoff - a flash and the sonic boom's shock ring racing out across the ground;</li>
 *   <li>the climb - the 3D plume with its shock diamonds, a thin contrail once the missile is clear of the pad, the
 *   vapour cone as it goes through Mach 1, burnout;</li>
 *   <li>the stall - attitude-control puffs as it noses over;</li>
 *   <li>the fall - the nose glowing white-hot inside its plasma sheath as it closes on Mach 13 (seen from the ground
 *   only at night).</li>
 * </ul>
 * The impact itself is an ordinary explosion (made by the server).
 */
public final class KrlFx {
    private KrlFx() {}

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 20));
    private static final Random RND = Random.create();
    static final float BURNOUT = KrlFlight.BURNOUT;
    /** The contrail starts this far from the launcher (no smoke at the pad). */
    private static final double TRAIL_CLEAR = 28;

    // ------------------------------------------------------------------------------------------- particles (contrail, RCS)

    static final class Puff {
        double x, y, z;
        float vx, vy, vz, age, life, s0, s1, rot, spin, gray, alpha, heat, drag, buoy;
        int tile;
        double d2;
    }

    private static final List<Puff> PUFFS = new ArrayList<>();

    private static int cap() {
        return (int) (1600 * Math.max(0.3f, Quality.fx()));
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

    static float r(float a, float b) {
        return a + RND.nextFloat() * (b - a);
    }

    static double rs() {
        return RND.nextDouble() * 2 - 1;
    }

    public static void clear() {
        PUFFS.clear();
    }

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
            p.heat *= 0.94f;
        }
    }

    static void tick(KrlClient.CFlight f, double t) {
        float q = Math.max(0.35f, Quality.fx());
        KrlFlight p = f.path;
        // the contrail, once clear of the launcher
        if (t > 0 && t <= BURNOUT + 2) {
            double t0 = Math.max(f.lastTrailT, 0.05), t1 = Math.min(t, BURNOUT);
            double tt = t0;
            while (tt < t1) {
                double sp = p.speed(tt);
                Vec3d d = p.dir(tt);
                Vec3d at = p.pos(tt).subtract(d.multiply(1.2));
                double step = (1.8 + sp * 0.3) / Math.max(0.2, sp) / Math.max(0.5, q);
                if (at.distanceTo(f.launch) < TRAIL_CLEAR) {
                    tt += step;
                    continue;
                }
                double alt = Math.max(0, at.y - f.launch.y);
                float s0 = (float) (0.5 + 0.18 * sp), s1 = (float) (s0 * 3.2 + alt / 70);
                Vec3d v = d.multiply(-0.04).add(rs() * 0.015, rs() * 0.015, rs() * 0.015);
                puff(at.x + rs() * 0.2, at.y + rs() * 0.2, at.z + rs() * 0.2, v.x, v.y, v.z, r(360, 560) * (0.7f + 0.3f * q), s0, s1,
                        0.94f, 0.36f - (float) Math.min(0.16, alt / 3000), 0.8f, 0.985f, 0.0006f);
                tt += step;
            }
            f.lastTrailT = tt;
        }
        // breaking the sound barrier: a ring of condensation blasted out round the missile
        if (f.mach1 > 0 && !f.mach1Puffed && t >= f.mach1) {
            f.mach1Puffed = true;
            Vec3d d = p.dir(f.mach1), c = nozzle(f, f.mach1).add(d.multiply(3));
            Vec3d e1 = d.crossProduct(Math.abs(d.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0)).normalize(), e2 = d.crossProduct(e1).normalize();
            int n = (int) (64 * Math.max(0.5f, q));
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + rs() * 0.05;
                Vec3d rd = e1.multiply(Math.cos(a)).add(e2.multiply(Math.sin(a)));
                double sp = 0.9 + RND.nextDouble() * 0.5;
                puff(c.x + rd.x * 1.5, c.y + rd.y * 1.5, c.z + rd.z * 1.5, rd.x * sp - d.x * 0.3, rd.y * sp - d.y * 0.3, rd.z * sp - d.z * 0.3,
                        r(50, 90), 1.4f, 6.5f, 1f, 0.6f, 0, 0.9f, 0);
            }
            for (int i = 0; i < n / 2; i++) {
                Vec3d at = c.subtract(d.multiply(RND.nextDouble() * 9));
                puff(at.x + rs(), at.y + rs(), at.z + rs(), rs() * 0.2, rs() * 0.2, rs() * 0.2, r(40, 70), 2f, 5f, 1f, 0.45f, 0, 0.92f, 0);
            }
        }
        // the re-entry trail: a white smoke streak behind the glowing nose, so the dive reads against a daylit sky too
        double tf0 = KrlFlight.BOOST + KrlFlight.STALL + 6;
        if (t > tf0 && t < KrlFlight.TOTAL - 1) {
            double tt = f.lastFallT < tf0 ? tf0 : f.lastFallT;
            while (tt < t) {
                double sp = p.speed(tt);
                float hh = heat(f, tt);
                Vec3d d = p.dir(tt);
                Vec3d at = nose(f, tt).subtract(d.multiply(2.5));
                if (hh > 0.08f) {
                    float s0 = 0.6f + 0.8f * hh, s1 = s0 * 4.5f + 2f;
                    Vec3d v = d.multiply(-0.03).add(rs() * 0.02, rs() * 0.02, rs() * 0.02);
                    puff(at.x + rs() * 0.3, at.y + rs() * 0.3, at.z + rs() * 0.3, v.x, v.y, v.z, r(200, 320) * (0.7f + 0.3f * q), s0, s1,
                            0.96f, 0.42f * Math.min(1f, hh * 1.6f), 1.2f * hh, 0.98f, 0.0004f);
                }
                tt += Math.max(0.25, 2.2 / Math.max(0.3, sp)) / Math.max(0.5, q);
            }
            f.lastFallT = tt;
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
        // glowing from about Mach 2.5, white-hot by 70 % of the motor's top speed
        return (float) Math.pow(MathHelper.clamp((m - 2.5) / (0.7 * f.path.machMax - 2.5), 0, 1), 0.85);
    }

    static boolean isRider(KrlClient.CFlight f) {
        return KrlClient.riding == f.id;
    }

    /** The falling missile is always drawn, day or night. */
    static float fallVis(KrlClient.CFlight f, double t, Vec3d cam) {
        return 1;
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
        if (!any && PUFFS.isEmpty() && !KrlAa.any()) return;

        ms.push();
        Matrix4f m = ms.peek().getPositionMatrix();
        // 1. the missiles
        for (KrlClient.CFlight f : KrlClient.flights()) {
            if (!KrlClient.here(f)) continue;
            double t = f.t(pt);
            if (t >= KrlFlight.TOTAL) continue;
            // on the rail the launcher's renderer draws it, when it is close enough to be drawn at all
            if (t < 0 && cam.distanceTo(f.launch) < 180) continue;
            float vis = t > KrlFlight.BOOST + KrlFlight.STALL ? fallVis(f, t, cam) : 1;
            if (vis < 0.03f) continue;
            drawSpear(ms, cam, f, t, vis);
        }
        // high above, the missile is far past the fog line: draw it without fog so it stays visible
        float fogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart(), fogEnd = com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(1e7f);
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(2e7f);
        BUF.draw();
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(fogStart);
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(fogEnd);

        // interceptors in flight
        drawInterceptors(ms, cam, pt);

        // 2. the contrail (alpha blended, back to front)
        drawSmoke(m, cam, pt);

        // 3. light: flames, flashes, sheath
        for (KrlClient.CFlight f : KrlClient.flights()) {
            if (!KrlClient.here(f)) continue;
            drawLight(ms, m, cam, eye, f, f.t(pt));
        }
        drawInterceptorLight(m, cam, eye, pt);
        ms.pop();
    }

    private static void drawSpear(MatrixStack ms, Vec3d cam, KrlClient.CFlight f, double t, float vis) {
        KrlMesh mesh = KrlModels.SPEAR;
        if (mesh == null) return;
        Vec3d n = nozzle(f, t), d = f.path.dir(t);
        ms.push();
        ms.translate(n.x - cam.x, n.y - cam.y, n.z - cam.z);
        ms.multiply(frame(f, d, t));
        // far away, scale the missile up about its flight point so it never shrinks below a few pixels
        double dist = cam.distanceTo(n);
        float big = (float) Math.max(1, dist / 160);
        if (big > 1) {
            float yp = KrlModels.NOSE_TIP * shift(t);
            ms.translate(0, yp, 0);
            ms.scale(big, big, big);
            ms.translate(0, -yp, 0);
        }
        int light = WorldRenderer.getLightmapCoordinates(KrlClient.world(), BlockPos.ofFloored(n.add(d.multiply(3))));
        // lit at least by the open sky, so it isn't a black speck at dusk
        light = Math.max(light & 0xFFFF, 0xA0) | (Math.max(light >>> 16, 0xF0) << 16);
        float h = heat(f, t);
        int lod = dist < 14 ? 0 : dist < 40 ? 1 : 2;
        mesh.lod(lod).render(ms, BUF.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.spearTex(f.livery))), light, null, (nx, ny, nz, o) -> {
            o[0] = vis;
            o[1] = vis * (1 - h * 0.35f);
            o[2] = vis * (1 - h * 0.5f);
            o[3] = 1;
        });
        mesh.glow(lod).render(ms, BUF.getBuffer(RenderLayer.getEyes(KrlModels.SPEAR_GLOW)), light, null, (nx, ny, nz, o) -> {
            o[0] = vis;
            o[1] = vis;
            o[2] = vis;
            o[3] = 1;
        });
        ms.pop();
    }

    /**
     * The missile's orientation: +Y along its heading, +Z the normal of the flight plane (constant), so it leaves the
     * rail with exactly the roll it had on it (lugs down); after liftoff it rolls slowly.
     */
    static Quaternionf frame(KrlClient.CFlight f, Vec3d d, double t) {
        Vec3d z = f.path.h.crossProduct(new Vec3d(0, 1, 0)).normalize();
        Vec3d y = d.subtract(z.multiply(d.dotProduct(z))).normalize();
        Vec3d x = y.crossProduct(z);
        org.joml.Matrix3f m = new org.joml.Matrix3f((float) x.x, (float) x.y, (float) x.z, (float) y.x, (float) y.y, (float) y.z, (float) z.x,
                (float) z.y, (float) z.z);
        return new Quaternionf().setFromNormalized(m).rotateY((float) (Math.max(0, t) * 0.045));
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
            float h = Math.min(1f, p.heat);
            float r = MathHelper.clamp(p.gray * amb + h * 0.6f, 0, 1), g = MathHelper.clamp(p.gray * amb + h * 0.3f, 0, 1),
                    b = MathHelper.clamp(p.gray * amb * 0.98f + h * 0.08f, 0, 1);
            float u0 = (p.tile % 2) * 0.5f, v0 = (p.tile / 2) * 0.5f;
            double px = p.x + p.vx * pt - cam.x, py = p.y + p.vy * pt - cam.y, pz = p.z + p.vz * pt - cam.z;
            KrlDraw.spriteUv(vc, m, (float) px, (float) py, (float) pz, s, p.rot, u0, v0, u0 + 0.5f, v0 + 0.5f, r, g, b, al);
        }
        BUF.draw();
    }

    private static Vec3d relv(Vec3d p, Vec3d cam) {
        return p.subtract(cam);
    }

    private static Vector3f rel(Vec3d p, Vec3d cam) {
        return new Vector3f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z));
    }

    /** At least this big on screen (so a distant flame or a star-like falling missile stays a point of light). */
    private static float minSize(float s, Vec3d p, Vec3d cam, float k) {
        return Math.max(s, (float) (cam.distanceTo(p) * k));
    }

    /** Where the hold-down exhaust meets the ground behind the launcher, or null if it is further than `max`. */
    private static double groundHit(Vec3d from, Vec3d dir, double max) {
        var w = KrlClient.world();
        for (double s = 0.5; s <= max; s += 0.25) {
            Vec3d p = from.add(dir.multiply(s));
            BlockPos b = BlockPos.ofFloored(p);
            if (!w.getBlockState(b).getCollisionShape(w, b).isEmpty() && !w.getBlockState(b).isOf(Krl.LAUNCHER)) return s;
            if (p.y < w.getTopY(Heightmap.Type.MOTION_BLOCKING, b.getX(), b.getZ())) return s;
        }
        return max;
    }

    private static void drawLight(MatrixStack ms, Matrix4f m, Vec3d cam, Vector3f eye, KrlClient.CFlight f, double t) {
        KrlFlight p = f.path;
        float time = (float) (KrlClient.now() % 100000) + (float) (t - Math.floor(t));
        float flick = 0.9f + 0.1f * MathHelper.sin((float) t * 2.7f) * MathHelper.sin((float) t * 1.3f + 1);
        // ---- ignition: the motor burning on the rail, the plume striking the ground behind the launcher
        if (t < 0 && t >= -Krl.FLAME) {
            float ramp = (float) MathHelper.clamp((t + Krl.FLAME) / 5.0, 0, 1);
            float flash = (float) Math.max(0, 1 - (t + Krl.FLAME) / 4.0);
            Vec3d l = f.launch, back = p.rail.multiply(-1);
            double hit = groundHit(l, back, 6);
            float len = (float) Math.min(5.5, hit + 0.8) * ramp;
            VertexConsumer pl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.PLASMA));
            KrlFlame.plume(m, pl, relv(l, cam), back, len, 0.26f, ramp * flick, time);
            // the splash: tongues of flame spreading out over the ground from where the plume lands
            Vec3d hp = l.add(back.multiply(hit));
            Vec3d bh = new Vec3d(back.x, 0, back.z).normalize();
            Vec3d sd = new Vec3d(-bh.z, 0, bh.x);
            for (int i = -2; i <= 2; i++) {
                double a = i * 0.55;
                Vec3d dir = bh.multiply(Math.cos(a)).add(sd.multiply(Math.sin(a))).add(0, 0.12, 0).normalize();
                float tl = (float) (1.6 + 0.9 * Math.cos(a)) * ramp * (0.85f + 0.15f * MathHelper.sin(time * 3.3f + i));
                KrlFlame.plume(m, pl, relv(hp.add(0, 0.15, 0), cam), dir, tl, 0.32f, 0.55f * ramp, time + i);
            }
            BUF.draw();
            VertexConsumer wh = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.WHITE_TEX));
            KrlFlame.diamonds(m, wh, relv(l, cam), back, len, 0.26f, ramp * 0.8f, time);
            BUF.draw();
            VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
            Vector3f a = rel(l, cam), g0 = rel(hp.add(0, 0.3, 0), cam);
            KrlDraw.flat(gl, m, rel(l.add(back.multiply(0.05)), cam), vec(KrlFlame.basis(back)[0]), vec(KrlFlame.basis(back)[1]), 0.3f, 1f, 0.9f, 0.7f, ramp);
            KrlDraw.sprite(gl, m, g0.x, g0.y, g0.z, 3.5f, 0, 1f, 0.55f, 0.2f, 0.6f * ramp * flick);
            KrlDraw.sprite(gl, m, g0.x, g0.y, g0.z, 12f, 0, 1f, 0.4f, 0.12f, (0.12f + 0.3f * KrlClient.night()) * ramp * flick);
            KrlDraw.sprite(gl, m, a.x, a.y, a.z, 1.2f, 0, 1f, 0.92f, 0.75f, 0.8f * ramp + flash);
            BUF.draw();
        }
        // ---- liftoff: flash and the sonic boom's shock ring across the ground
        if (t >= 0 && t < 40) {
            Vec3d l = f.launch;
            float tb = (float) t;
            VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.FLARE));
            Vector3f a = rel(l.add(0, 1, 0), cam);
            if (tb < 6) KrlDraw.sprite(fl, m, a.x, a.y, a.z, 18f * (1 - tb / 6f) + 4, 0, 1f, 0.9f, 0.75f, 1 - tb / 6f);
            BUF.draw();
            VertexConsumer ring = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.RING));
            double gy = KrlClient.world().getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(l.x), (int) Math.floor(l.z));
            float rr = 3 + tb * 3.4f;
            KrlDraw.flat(ring, m, rel(new Vec3d(l.x, gy + 0.2, l.z), cam), new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), rr, 1f, 0.95f, 0.9f,
                    0.6f * (float) Math.pow(1 - tb / 40f, 1.6));
            BUF.draw();
        }
        // ---- the climb
        if (t >= 0 && t < KrlFlight.BOOST) {
            Vec3d n = nozzle(f, t), d = p.dir(t), back = d.multiply(-1);
            double sp = p.speed(t);
            double alt = Math.max(0, n.y - f.launch.y);
            float burn = (float) MathHelper.clamp((BURNOUT - t) / 18.0, 0, 1);
            if (burn > 0) {
                float fm = f.missile.flameMul();
                float len = (float) (4.5 + 13 * Math.min(1, sp / 4) + alt / 55) * (0.6f + 0.4f * burn) * fm;
                float rad = (float) (0.26 + 0.2 * Math.min(1, sp / 4) + alt / 420) * burn * (0.7f + 0.3f * fm);
                VertexConsumer pl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.PLASMA));
                KrlFlame.plume(m, pl, relv(n, cam), back, len, rad, burn * flick, time);
                BUF.draw();
                VertexConsumer wh = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.WHITE_TEX));
                KrlFlame.diamonds(m, wh, relv(n, cam), back, len, rad, burn * 0.9f, time);
                BUF.draw();
                // a soft halo so the flame still reads as a point of light far away
                VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
                Vector3f a = rel(n.add(back.multiply(len * 0.2)), cam);
                float s = minSize(1.6f, n, cam, 0.006f);
                KrlDraw.sprite(gl, m, a.x, a.y, a.z, s, 0, 1f, 0.75f, 0.45f, 0.45f * burn);
                KrlDraw.sprite(gl, m, a.x, a.y, a.z, s * 3.2f, 0, 1f, 0.45f, 0.15f, (0.1f + 0.3f * KrlClient.night()) * burn);
                BUF.draw();
            } else if (t < BURNOUT + 40) {
                float k = (float) (1 - (t - BURNOUT) / 40);
                VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
                Vector3f a = rel(n, cam);
                KrlDraw.sprite(gl, m, a.x, a.y, a.z, 0.6f, 0, 1f, 0.3f, 0.08f, 0.6f * k);
                BUF.draw();
            }
            // the sonic boom: the vapour cone riding the missile, a flash and the shock front racing out
            if (f.mach1 > 0 && t >= f.mach1 - 2 && t < f.mach1 + 34) {
                double tb = t - f.mach1;
                Vector3f e1 = new Vector3f(), e2 = new Vector3f();
                basis(d, e1, e2);
                float day = 0.45f + 0.55f * KrlClient.daylight();
                if (tb < 16) {
                    float k = (float) ((tb + 2) / 18);
                    Vec3d mid = n.add(d.multiply(3.3));
                    VertexConsumer vap = BUF.getBuffer(BeamRenderLayers.translucent(KrlDraw.VAPOR));
                    for (int i = 0; i < 6; i++) {
                        Vec3d c = mid.subtract(d.multiply(i * 0.9));
                        KrlDraw.flat(vap, m, rel(c, cam), e1, e2, 1.6f + i * 0.9f + k * 2.2f, day, day, day,
                                0.75f * (float) Math.sin(Math.PI * k) * (1 - i * 0.13f));
                    }
                    BUF.draw();
                }
                Vec3d at = p.pos(Math.max(0, f.mach1));
                Vector3f a = rel(at, cam);
                float k = (float) MathHelper.clamp((tb + 2) / 36, 0, 1);
                VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.FLARE));
                if (tb < 5) KrlDraw.sprite(fl, m, a.x, a.y, a.z, 16f * (float) (1 - Math.max(0, tb) / 5) + 3, 0.3f, 1f, 0.95f, 0.85f, 0.9f * (float) (1 - Math.max(0, tb) / 5));
                BUF.draw();
                VertexConsumer ring = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.RING));
                float rr = 3 + (float) Math.max(0, tb + 2) * 7f;
                float al = 0.85f * (float) Math.pow(1 - k, 1.4);
                KrlDraw.sprite(ring, m, a.x, a.y, a.z, rr, 0, 1f, 0.97f, 0.92f, al);
                KrlDraw.flat(ring, m, a, e1, e2, rr * 0.8f, 1f, 0.97f, 0.92f, al * 0.8f);
                BUF.draw();
            }
        }
        // ---- the fall: the plasma sheath round the nose
        float h = heat(f, t);
        if (h > 0.01f && t < KrlFlight.TOTAL) {
            float vis = fallVis(f, t, cam);
            if (vis > 0.02f) drawSheath(ms, m, cam, eye, f, t, h * vis);
        }
    }

    // ------------------------------------------------------------------------------------------- interceptors

    /** Interceptor scale (a smaller, slimmer round than the strike missile). */
    private static final float AA_SCALE = 0.55f;

    private static void drawInterceptors(MatrixStack ms, Vec3d cam, float pt) {
        KrlMesh mesh = KrlModels.SPEAR;
        if (mesh == null || KrlAa.SHOTS.isEmpty()) return;
        for (KrlAa.Shot s : KrlAa.SHOTS.values()) {
            Vec3d p = s.at(pt), d = s.vel.lengthSquared() < 1e-9 ? new Vec3d(0, 1, 0) : s.vel.normalize();
            double dist = cam.distanceTo(p);
            ms.push();
            ms.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
            Vec3d z = d.crossProduct(Math.abs(d.y) < 0.95 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0)).normalize();
            Vec3d x = d.crossProduct(z);
            org.joml.Matrix3f rm = new org.joml.Matrix3f((float) x.x, (float) x.y, (float) x.z, (float) d.x, (float) d.y, (float) d.z, (float) z.x,
                    (float) z.y, (float) z.z);
            ms.multiply(new Quaternionf().setFromNormalized(rm));
            float sc = AA_SCALE * (float) Math.max(1, dist / 140);
            ms.scale(sc, sc, sc);
            // the round is drawn nose-first from its tail
            ms.translate(0, -KrlModels.NOSE_TIP, 0);
            int lod = dist < 14 ? 0 : dist < 40 ? 1 : 2;
            mesh.lod(lod).render(ms, BUF.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.spearTex(4))), KrlDraw.FULL, null, null);
            ms.pop();
        }
        BUF.draw();
    }

    private static void drawInterceptorLight(Matrix4f m, Vec3d cam, Vector3f eye, float pt) {
        float time = (float) (KrlClient.now() % 100000) + pt;
        if (!KrlAa.SHOTS.isEmpty()) {
            VertexConsumer pl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.PLASMA));
            for (KrlAa.Shot s : KrlAa.SHOTS.values()) {
                Vec3d p = s.at(pt), d = s.vel.lengthSquared() < 1e-9 ? new Vec3d(0, 1, 0) : s.vel.normalize();
                double dist = cam.distanceTo(p);
                float sc = AA_SCALE * (float) Math.max(1, dist / 140);
                Vec3d tail = p.subtract(d.multiply(KrlModels.NOSE_TIP * sc));
                float sp = (float) s.vel.length();
                KrlFlame.plume(m, pl, relv(tail, cam), d.multiply(-1), (2.5f + sp * 0.35f) * sc / AA_SCALE, 0.16f * sc / AA_SCALE, 1f, time + s.id);
            }
            BUF.draw();
            VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
            for (KrlAa.Shot s : KrlAa.SHOTS.values()) {
                Vec3d p = s.at(pt), d = s.vel.lengthSquared() < 1e-9 ? new Vec3d(0, 1, 0) : s.vel.normalize();
                double dist = cam.distanceTo(p);
                float sc = AA_SCALE * (float) Math.max(1, dist / 140);
                Vector3f a = rel(p.subtract(d.multiply(KrlModels.NOSE_TIP * sc * 1.2)), cam);
                float sz = minSize(1.1f, p, cam, 0.006f);
                KrlDraw.sprite(gl, m, a.x, a.y, a.z, sz, 0, 1f, 0.78f, 0.45f, 0.85f);
                KrlDraw.sprite(gl, m, a.x, a.y, a.z, sz * 2.6f, 0, 1f, 0.5f, 0.2f, 0.25f + 0.25f * KrlClient.night());
            }
            BUF.draw();
        }
        if (!KrlAa.BURSTS.isEmpty()) {
            VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.FLARE));
            for (KrlAa.Burst b : KrlAa.BURSTS) {
                float k = MathHelper.clamp((b.age + pt) / 24f, 0, 1);
                Vector3f a = rel(b.at, cam);
                float big = minSize(1, b.at, cam, 0.02f);
                KrlDraw.sprite(fl, m, a.x, a.y, a.z, (6 + 10 * k) * Math.max(1, big / 3), k * 2, 1f, 0.85f, 0.55f, (1 - k) * (1 - k));
            }
            BUF.draw();
            VertexConsumer ring = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.RING));
            for (KrlAa.Burst b : KrlAa.BURSTS) {
                float k = MathHelper.clamp((b.age + pt) / 24f, 0, 1);
                Vector3f a = rel(b.at, cam);
                KrlDraw.sprite(ring, m, a.x, a.y, a.z, 3 + 30 * k, 0, 1f, 0.95f, 0.85f, 0.7f * (1 - k));
            }
            BUF.draw();
        }
    }

    private static Vector3f vec(Vec3d v) {
        return new Vector3f((float) v.x, (float) v.y, (float) v.z);
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
        float flick = 0.92f + 0.08f * MathHelper.sin(time * 3.7f) * MathHelper.sin(time * 1.9f + 1);
        // the sheath itself: nested shells of the nose, white-hot inside, orange and red further out
        if (mesh != null) {
            ms.push();
            ms.translate(n.x - cam.x, n.y - cam.y, n.z - cam.z);
            ms.multiply(frame(f, d, t));
            float base = KrlModels.NOSE_FROM;
            float[][] shells = {{1.03f, 1.0f, 0.98f, 0.92f, 0.95f}, {1.16f, 1.0f, 0.82f, 0.5f, 0.8f}, {1.38f, 1.0f, 0.55f, 0.2f, 0.62f},
                    {1.7f, 1.0f, 0.32f, 0.08f, 0.45f}, {2.15f, 0.9f, 0.16f, 0.04f, 0.3f}};
            VertexConsumer vc = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.WHITE_TEX));
            for (int i = 0; i < shells.length; i++) {
                float[] sh = shells[i];
                float sc = 1 + (sh[0] - 1) * (0.45f + 0.55f * h) + 0.02f * MathHelper.sin(time * 1.9f + i);
                float al = sh[4] * h * flick;
                ms.push();
                ms.translate(0, base, 0);
                ms.scale(sc, 1 + (sc - 1) * 0.55f, sc);
                ms.translate(0, -base, 0);
                mesh.renderNoOverlay(ms, vc, KrlDraw.FULL, (part, st) -> part.equals("nose"), (nx, ny, nz, o) -> {
                    float rim = 1 - Math.abs(nz);
                    float k = al * (0.45f + 0.55f * rim * rim);
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
        // the glow round the tip: a white core, an orange halo, a wide red bloom, and a flare star
        VertexConsumer gl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.GLOW));
        Vector3f a = rel(tip.subtract(d.multiply(0.8)), cam);
        float night = KrlClient.night();
        float core = minSize(2.4f + 3.4f * h, tip, cam, 0.008f * h);
        KrlDraw.sprite(gl, m, a.x, a.y, a.z, core, 0, 1f, 0.95f, 0.85f, h * flick);
        KrlDraw.sprite(gl, m, a.x, a.y, a.z, core * 2.6f, 0, 1f, 0.6f, 0.25f, 0.85f * h * flick);
        KrlDraw.sprite(gl, m, a.x, a.y, a.z, core * 6.5f, 0, 1f, 0.35f, 0.1f, (0.25f + 0.3f * night) * h);
        BUF.draw();
        VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.FLARE));
        KrlDraw.sprite(fl, m, a.x, a.y, a.z, core * 4.5f, time * 0.01f, 1f, 0.8f, 0.55f, (0.35f + 0.35f * night) * h);
        BUF.draw();
        // additive light vanishes against a bright sky: in daylight lay an alpha-blended hot core over it
        float day = KrlClient.daylight();
        if (day > 0.05f) {
            VertexConsumer dc = BUF.getBuffer(BeamRenderLayers.translucent(KrlDraw.GLOW));
            KrlDraw.sprite(dc, m, a.x, a.y, a.z, core * 1.5f, 0, 1f, 0.62f, 0.25f, 0.55f * h * day);
            KrlDraw.sprite(dc, m, a.x, a.y, a.z, core * 0.75f, 0, 1f, 0.93f, 0.8f, 0.9f * h * day);
            BUF.draw();
        }
        // the ionised wake streaming back along the body
        VertexConsumer pl = BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.PLASMA));
        Vector3f b0 = rel(tip.subtract(d.multiply(0.3)), cam), b1 = rel(tip.subtract(d.multiply(7 + 12 * h)), cam),
                b2 = rel(tip.subtract(d.multiply(3 + 4 * h)), cam);
        KrlDraw.beam(pl, m, b0, b1, eye, 0.7f + 0.8f * h, -time * 0.4f, 4f, 1f, 0.42f, 0.12f, 0.75f * h * h);
        KrlDraw.beam(pl, m, b0, b2, eye, 0.4f + 0.4f * h, -time * 0.6f, 2f, 1f, 0.85f, 0.6f, 0.8f * h * h);
        BUF.draw();
    }
}
