package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.astraea.Timeline;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.space.SplitEarthDraw;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Random;

import static com.creedpdf.orbitalrailgun.client.astraea.AstraeaDraw.*;

/**
 * ASTRAEA in the overworld sky (drawn with the sky, behind everything else):
 * <ul>
 *   <li>the lance in orbit: a white star crossing the sky along the line of the cut, lap after lap, faster and faster,
 *   dragging a trail that lengthens until it closes into a ring of light arcing over the whole sky; then the ring
 *   sinks to the horizon as it spirals in, and the horizon glows at both ends of the cut while it drills;</li>
 *   <li>after the split, permanently: the other half of the Earth over the horizon across the chasm - its glowing
 *   cut face toward you (crust, molten mantle, the cores), the planet's own surface curving away behind it - rising
 *   higher over the first minutes; a molten haze round it, a belt of torn planet arcing across the sky.</li>
 * </ul>
 */
public final class SkyStrike {
    private SkyStrike() {}

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 20));
    private static final float D = 80f;

    /** @param world camera-rotation matrix (world axes), sky space (camera at the origin) */
    public static void render(Matrix4f world, Vec3d cam, float time, float pt, float skyAngle) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld w = mc.world;
        if (w == null) return;
        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        Vector3f sun = new Vector3f(-MathHelper.sin(skyAngle * MathHelper.TAU), MathHelper.cos(skyAngle * MathHelper.TAU), 0f);
        if (AstraeaState.split()) otherHalf(world, cam, time, pt, sun);
        orbit(world, cam, time, pt);
        BUF.draw();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    // ------------------------------------------------------------------------------------------- the lance in orbit

    /** Where the lance is, seen from a point on the cut (local frame: up and along the cut), as a direction; null below the horizon. */
    static Vector3f lanceDir(double t, Vector3f along) {
        double rho, th;
        if (t < Timeline.S_ORBIT) return null;
        if (t < Timeline.S_DRILL) {
            rho = Timeline.orbitRadius(t);
            th = Timeline.orbitAngle(t);
        } else {
            rho = 0.985;
            th = AstraeaState.drillAngle(t);
        }
        float upC = (float) (rho * Math.cos(th) - 1), alC = (float) (rho * Math.sin(th));
        Vector3f d = new Vector3f(0, upC, 0).add(new Vector3f(along).mul(alC));
        if (d.lengthSquared() < 1e-8f) return null;
        d.normalize();
        return d;
    }

    private static void orbit(Matrix4f m, Vec3d cam, float time, float pt) {
        AstraeaState.Strike s = AstraeaState.strike();
        double t = AstraeaState.strikeT(pt);
        if (s == null || t < Timeline.S_ORBIT || t > Timeline.S_SPLIT + 60) return;
        Vector3f along = new Vector3f((float) Math.cos(s.angle()), 0, (float) Math.sin(s.angle()));
        VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
        double u = MathHelper.clamp((t - Timeline.S_ORBIT) / (Timeline.S_DRILL - Timeline.S_ORBIT), 0, 1);
        if (t < Timeline.S_SPLIT) {
            // the trail: the last stretch of the orbit, fading back from the head
            double span = Math.min(Math.PI * 2 * 0.985, 0.25 + Math.PI * 2 * Timeline.lapRate(u) * 30);
            double th = Timeline.orbitAngle(t);
            int n = 160;
            Vector3f prev = null;
            float prevA = 0;
            for (int i = 0; i <= n; i++) {
                double tt = t - (span / (Math.PI * 2 * Math.max(1e-4, Timeline.lapRate(u)))) * i / n;
                Vector3f d = lanceDir(Math.max(Timeline.S_ORBIT, tt), along);
                if (d == null || d.y < -0.02f) {
                    prev = null;
                    continue;
                }
                float k = 1f - i / (float) n;
                Vector3f p = new Vector3f(d).mul(D);
                float a = k * k * (0.55f + 0.45f * (float) u);
                if (prev != null) {
                    float wd = 0.35f + 1.3f * (float) u;
                    strip(g, m, prev, p, wd * 3f, wd * 3f, WARM, prevA * 0.3f, a * 0.3f);
                    strip(g, m, prev, p, wd, wd, WHITE, prevA, a);
                }
                prev = p;
                prevA = a;
            }
            Vector3f head = lanceDir(t, along);
            if (head != null && head.y > -0.02f) {
                Vector3f p = new Vector3f(head).mul(D * 0.98f);
                VertexConsumer st = BUF.getBuffer(BeamRenderLayers.textured(STAR));
                sprite(st, m, p, 3.5f + 3f * (float) u, time * 0.05f, WHITE, 1f);
                g = BUF.getBuffer(BeamRenderLayers.GLOW);
                glowBall(g, m, p, 1.4f, WHITE, 1f);
            }
            g = BUF.getBuffer(BeamRenderLayers.GLOW);
        }
        // while it drills: both ends of the cut glow at the horizon
        double cut = Timeline.cutProgress(t);
        float hor = (float) Math.min(1, cut * 1.4) * (t < Timeline.S_SPLIT ? 1f : Math.max(0f, 1f - (float) (t - Timeline.S_SPLIT) / 60f));
        if (hor > 0.01f) {
            for (int sg = -1; sg <= 1; sg += 2) {
                Vector3f dir = new Vector3f(along).mul(sg).add(0, 0.02f, 0).normalize().mul(D * 0.97f);
                glowBall(g, m, dir, 18f * hor, EMBER, 0.55f * hor);
                glowBall(g, m, dir, 6f * hor, WARM, 0.8f * hor);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the other half

    private static void otherHalf(Matrix4f m, Vec3d cam, float time, float pt, Vector3f sun) {
        AstraeaState.Chasm c = AstraeaState.nearest(cam.x, cam.z);
        if (c == null) return;
        double since = AstraeaState.sinceSplit(pt);
        if (since < 0) return;
        Vector3f perp = new Vector3f((float) -Math.sin(c.angle()), 0, (float) Math.cos(c.angle()));
        float side = Math.signum((float) ((cam.x - c.x()) * perp.x + (cam.z - c.z()) * perp.z));
        if (side == 0) side = 1;
        Vector3f across = new Vector3f(perp).mul(-side);
        float grow = 1f - (float) Math.exp(-since / 2400.0);
        float rise = (float) Math.min(1, since / 160.0);
        float e = (float) Math.toRadians(-90 + 16 * rise + 40 * grow);
        float alpha = (float) Math.toRadians(58);
        Vector3f dir = new Vector3f(across).mul(MathHelper.cos(e)).add(0, MathHelper.sin(e), 0).normalize();
        Vector3f ctr = new Vector3f(dir).mul(D);
        float rr = D * MathHelper.sin(alpha);
        float beta = (float) Math.toRadians(14 + 22 * grow);
        Vector3f face = new Vector3f(across).mul(-MathHelper.cos(beta)).add(0, MathHelper.sin(beta), 0).normalize();
        float heat = 0.45f + 0.55f * (float) Math.exp(-since / 6000.0);
        Quaternionf spin = new Quaternionf().rotationY(0.6f).rotateX(0.4f);
        // the sky glows over that horizon
        VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
        Vector3f hz = new Vector3f(across).add(0, 0.08f, 0).normalize().mul(D * 0.99f);
        glowBall(g, m, hz, 70f, EMBER, 0.22f * heat);
        BUF.draw();
        SplitEarthDraw.half(BUF, m, ctr, rr, sun, new Quaternionf(), spin, face, heat, 0.998f, 128);
        BUF.draw();
        haze(m, ctr, rr, face, time, heat);
        belt(m, across, time, heat, sun);
    }

    private static void haze(Matrix4f m, Vector3f ctr, float rr, Vector3f face, float time, float heat) {
        Vector3f[] b = basis(face);
        VertexConsumer hz = BUF.getBuffer(BeamRenderLayers.textured(HAZE));
        Vector3f front = new Vector3f(ctr).add(new Vector3f(face).mul(rr * 0.05f));
        quad(hz, m, front, b[0], b[1], rr * 1.25f, time * 0.0003f, EMBER, 0.35f * heat);
        quad(hz, m, front, b[0], b[1], rr * 1.5f, -time * 0.0002f, GOLD, 0.15f * heat);
        VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
        ring(g, m, ctr, b[0], b[1], rr * 0.98f, rr * 1.2f, 96, EMBER, 0.35f * heat, 0f);
    }

    /** A belt of torn planet arcing up from behind the other half across the sky. */
    private static void belt(Matrix4f m, Vector3f across, float time, float heat, Vector3f sun) {
        KrlMesh chunks = KrlModels.EARTH_CHUNKS;
        Random r = new Random(9090);
        MatrixStack ms = new MatrixStack();
        ms.peek().getPositionMatrix().set(m);
        Vector3f side = new Vector3f(across).cross(0, 1, 0).normalize();
        VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
        for (int i = 0; i < 70; i++) {
            float k = r.nextFloat();
            float az = (k - 0.5f) * 2.4f + (time * 0.00002f) * (1 + r.nextFloat());
            float el = (float) Math.sin(k * Math.PI) * 0.75f + (r.nextFloat() - 0.5f) * 0.12f - 0.05f;
            Vector3f d = new Vector3f(across).mul(MathHelper.cos(az) * MathHelper.cos(el)).add(new Vector3f(side).mul(MathHelper.sin(az) * MathHelper.cos(el)))
                    .add(0, MathHelper.sin(el), 0).normalize();
            if (d.y < -0.05f) continue;
            Vector3f p = new Vector3f(d).mul(D * 0.95f);
            float sc = 0.25f + r.nextFloat() * 1.1f;
            if (chunks != null && sc > 0.6f) {
                String part = "c" + r.nextInt(8);
                ms.push();
                ms.translate(p.x, p.y, p.z);
                ms.multiply(new Quaternionf().rotationXYZ(time * 0.001f * (1 + i % 3), r.nextFloat() * 6, r.nextFloat() * 6));
                ms.scale(sc, sc, sc);
                KrlMesh.Pose only = (pn, st) -> pn.equals(part);
                chunks.lod(2).renderNoOverlay(ms, BUF.getBuffer(BeamRenderLayers.translucent(KrlModels.CHUNK_TEX)), LIGHT, only, (nx, ny, nz, o) -> {
                    float lk = 0.25f + 0.75f * Math.max(0f, nx * sun.x + ny * sun.y + nz * sun.z);
                    o[0] = lk; o[1] = lk; o[2] = lk; o[3] = 1f;
                });
                chunks.glow(2).renderNoOverlay(ms, BUF.getBuffer(BeamRenderLayers.textured(KrlModels.CHUNK_GLOW)), LIGHT, only, (nx, ny, nz, o) -> {
                    o[0] = heat; o[1] = heat; o[2] = heat; o[3] = heat;
                });
                ms.pop();
                g = BUF.getBuffer(BeamRenderLayers.GLOW);
            } else {
                glowBall(g, m, p, sc * 0.6f, EMBER, 0.6f * heat);
            }
        }
    }
}
