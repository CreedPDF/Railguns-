package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.astraea.Timeline;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.space.SpaceSky;
import com.creedpdf.orbitalrailgun.client.space.SplitEarthDraw;
import com.creedpdf.orbitalrailgun.space.Body;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.Random;

import static com.creedpdf.orbitalrailgun.client.astraea.AstraeaDraw.*;

/**
 * The strike's cutscene from space, drawn over the whole finished frame (after the world - and after any shader pack's
 * own passes - before the HUD): the real solar system (SpaceSky, the same planets, stars and sun), the Earth, and the
 * lance as a white star dragging a trail round it.
 * <ol>
 *   <li><b>Lift-off</b>: from just above the surface, the star climbing away into orbit;</li>
 *   <li><b>Wide</b>: the whole planet, the star circling it, lap after lap, quicker each time, its trail lengthening;</li>
 *   <li><b>Chase</b>: right behind it as it tears round, the Earth's limb whipping past underneath;</li>
 *   <li><b>Spiral</b>: wide again, closer, as the trail closes into a ring and the orbit sinks toward the surface;</li>
 *   <li><b>The drill</b>: low over the planet following the white-hot head boring round it, the molten seam behind;</li>
 *   <li><b>The split</b>: side-on to the cut, the planet breaking into halves and drifting apart, the shockwave and the
 *   flash, chunks of world tumbling in the gap (shown at cinematic speed).</li>
 * </ol>
 */
public final class StrikeCine {
    private StrikeCine() {}

    public static final int FROM = 30, TO = Timeline.S_SPLIT + 72;
    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 20));
    private static final float K = 54f;

    static boolean showing(double t) {
        return t >= FROM && t < TO;
    }

    private record Shot(Vec3d eye, Vec3d look, Vec3d up, float fov) {}

    /** Called from GameRendererMixin after the world (and post-processing) are drawn. */
    public static void renderScreen(float pt) {
        AstraeaState.Strike s = AstraeaState.strike();
        if (s == null || !AstraeaCine.strikeFilming()) return;
        double t = AstraeaState.strikeT(pt);
        if (!showing(t)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Body E = Body.EARTH;
        Vec3d C = E.center;
        double R = E.radius;
        Vec3d U = E.surfaceNormal(s.anchor().x, s.anchor().z);
        Vec3d N = s.normal().normalize();
        Vec3d W = U.crossProduct(N).normalize();
        Shot shot = shot(s, t, C, R, U, N, W);
        boolean splitShot = t >= Timeline.S_SPLIT - 4;
        SplitEarthDraw.cineSince = splitShot ? Math.max(0, t - Timeline.S_SPLIT) : -1;

        // camera, with the shake folded into its rotation
        Vec3d dir = shot.look().subtract(shot.eye()).normalize();
        Vec3d up = shot.up();
        if (Math.abs(dir.dotProduct(up)) > 0.98) up = Math.abs(dir.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Matrix4f view = new Matrix4f().lookAlong((float) dir.x, (float) dir.y, (float) dir.z, (float) up.x, (float) up.y, (float) up.z);
        float sh = AstraeaClient.shakeAmount();
        if (sh > 0) {
            float a = sh * 0.012f;
            float ct = (float) (System.nanoTime() / 1e9);
            view.rotateLocalX(a * MathHelper.sin(ct * 37f)).rotateLocalY(a * MathHelper.sin(ct * 29f + 1.3f));
        }

        int fw = mc.getWindow().getFramebufferWidth(), fh = mc.getWindow().getFramebufferHeight();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().perspective((float) Math.toRadians(shot.fov()), fw / (float) Math.max(1, fh), 0.05f, 400f),
                VertexSorter.BY_DISTANCE);
        var mv = RenderSystem.getModelViewStack();
        mv.push();
        mv.loadIdentity();
        RenderSystem.applyModelViewMatrix();
        mc.getFramebuffer().beginWrite(true);
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        float time = (float) (AstraeaState.now(pt));
        try {
            SpaceSky.render(view, shot.eye(), new Quaternionf(), time, pt, 1f, 1f, null, 1f, 0);
            lance(view, s, t, shot.eye(), C, R, time);
        } finally {
            SpaceSky.resetDepth();
            SplitEarthDraw.cineSince = -1;
            mv.pop();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static Shot shot(AstraeaState.Strike s, double t, Vec3d C, double R, Vec3d U, Vec3d N, Vec3d W) {
        Vec3d L = AstraeaState.lancePos(s, t);
        Vec3d P = Body.EARTH.surfaceToSpace(s.anchor().x, s.anchor().z, 0);
        if (t < 100) {
            // lift-off: just above the surface, the star climbing away
            double u = (t - FROM) / 70.0;
            Vec3d eye = P.add(U.multiply(R * (0.012 + 0.03 * u))).add(W.multiply(-R * 0.05)).add(N.multiply(R * 0.035));
            return new Shot(eye, L, U, 62f);
        }
        if (t < 220) {
            double u = (t - 100) / 120.0;
            Vec3d d = N.multiply(0.92).add(U.multiply(0.3 + 0.1 * u)).add(W.multiply(0.25)).normalize();
            return new Shot(C.add(d.multiply(R * (3.7 - 0.5 * u))), C, U, 55f);
        }
        if (t < 330) {
            // chase
            Vec3d T = AstraeaState.lancePos(s, t + 0.25).subtract(AstraeaState.lancePos(s, t - 0.25)).normalize();
            Vec3d rad = L.subtract(C).normalize();
            Vec3d eye = L.subtract(T.multiply(R * 0.32)).add(rad.multiply(R * 0.1)).add(N.multiply(R * 0.05));
            return new Shot(eye, L.add(T.multiply(R * 0.45)).subtract(rad.multiply(R * 0.12)), rad, 68f);
        }
        if (t < Timeline.S_DRILL) {
            double u = (t - 330) / (double) (Timeline.S_DRILL - 330);
            Vec3d d = N.multiply(0.85).add(U.multiply(0.45)).add(W.multiply(-0.2 + 0.35 * u)).normalize();
            return new Shot(C.add(d.multiply(R * (2.9 - 0.5 * u))), C, U, 58f);
        }
        if (t < Timeline.S_SPLIT - 4) {
            // following the head round the planet, low and to the side
            double ah = AstraeaState.drillAngle(t) - 0.35;
            Vec3d hd = U.multiply(Math.cos(ah)).add(W.multiply(Math.sin(ah)));
            Vec3d eye = C.add(hd.multiply(0.8).add(N.multiply(0.72)).normalize().multiply(R * 1.75));
            return new Shot(eye, C.add(hd.multiply(R * 0.55)), hd, 60f);
        }
        // the split: side-on to the cut so the halves part across the screen
        double a0 = Timeline.orbitAngle(Timeline.S_DRILL);
        Vec3d u0 = U.multiply(Math.cos(a0)).add(W.multiply(Math.sin(a0)));
        Vec3d w0 = u0.crossProduct(N).normalize();
        double k = MathHelper.clamp((t - Timeline.S_SPLIT) / 72.0, 0, 1);
        Vec3d d = w0.add(N.multiply(0.22)).add(u0.multiply(0.25)).normalize();
        return new Shot(C.add(d.multiply(R * (4.2 + 1.4 * k))), C, u0, 56f);
    }

    // ------------------------------------------------------------------------------------------- the lance

    private static Vector3f proj(Vec3d p, Vec3d eye) {
        Vec3d rel = p.subtract(eye);
        double d = Math.max(1e-3, rel.length());
        float k = (float) (K / d);
        return new Vector3f((float) rel.x * k, (float) rel.y * k, (float) rel.z * k);
    }

    /** Is p hidden from the eye behind the (whole) Earth? */
    private static boolean behind(Vec3d eye, Vec3d p, Vec3d C, double R) {
        Vec3d d = p.subtract(eye);
        double len = d.length();
        d = d.multiply(1 / len);
        Vec3d oc = eye.subtract(C);
        double b = oc.dotProduct(d), c = oc.lengthSquared() - R * R * 0.995;
        double disc = b * b - c;
        if (disc < 0) return false;
        double t0 = -b - Math.sqrt(disc);
        return t0 > 0 && t0 < len;
    }

    private static void lance(Matrix4f m, AstraeaState.Strike s, double t, Vec3d eye, Vec3d C, double R, float time) {
        if (t >= Timeline.S_SPLIT) return;
        SpaceSky.slot(40);
        RenderSystem.depthMask(false);
        VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
        double u = MathHelper.clamp((t - Timeline.S_ORBIT) / (Timeline.S_DRILL - Timeline.S_ORBIT), 0, 1);
        boolean drilling = t >= Timeline.S_DRILL;
        // the trail: back along its path for (up to) most of a lap
        double rate = Math.max(1e-4, Timeline.lapRate(u));
        double back = drilling ? 0 : Math.min(t - Timeline.S_RELEASE, Math.min(0.92 / rate, 90));
        int n = 220;
        Vector3f prev = null;
        float prevA = 0;
        Vec3d L = AstraeaState.lancePos(s, t);
        double dist = L.distanceTo(eye);
        for (int i = 0; i <= n && back > 0; i++) {
            double tt = t - back * i / n;
            Vec3d p = AstraeaState.lancePos(s, tt);
            if (behind(eye, p, C, R)) {
                prev = null;
                continue;
            }
            Vector3f q = proj(p, eye);
            float k = 1f - i / (float) n;
            float a = k * k * (0.6f + 0.4f * (float) u);
            if (prev != null) {
                float w = (float) Math.max(0.04, R * 0.006 * K / p.distanceTo(eye)) * (1f + 1.5f * (float) u);
                strip(g, m, prev, q, w * 3.5f, w * 3.5f, WARM, prevA * 0.25f, a * 0.25f);
                strip(g, m, prev, q, w, w, WHITE, prevA, a);
            }
            prev = q;
            prevA = a;
        }
        if (!behind(eye, L, C, R)) {
            Vector3f q = proj(L, eye);
            float size = (float) Math.max(0.9, R * 0.05 * K / dist) * (1f + (float) u) * (drilling ? 1.6f : 1f);
            float fl = 0.85f + 0.15f * MathHelper.sin(time * 2.7f);
            glowBall(g, m, q, size * 1.4f * fl, drilling ? EMBER : WARM, 0.5f);
            glowBall(g, m, q, size * 0.4f, WHITE, 1f);
            VertexConsumer st = BUF.getBuffer(BeamRenderLayers.textured(STAR));
            sprite(st, m, q, size * 2.2f * fl, time * 0.05f, WHITE, 1f);
            // lightning crackling round it
            g = BUF.getBuffer(BeamRenderLayers.GLOW);
            Random r = new Random((long) (time / 2) * 977L);
            for (int i = 0; i < 4; i++) {
                Vector3f a = new Vector3f(q).add((r.nextFloat() - 0.5f) * size, (r.nextFloat() - 0.5f) * size, (r.nextFloat() - 0.5f) * size);
                Vector3f b = new Vector3f(q).add((r.nextFloat() - 0.5f) * size * 2.5f, (r.nextFloat() - 0.5f) * size * 2.5f, (r.nextFloat() - 0.5f) * size * 2.5f);
                bolt(g, m, a, b, size * 0.6f, size * 0.03f, WARM, 0.8f, r.nextLong());
            }
        }
        BUF.draw();
        RenderSystem.depthMask(true);
        SpaceSky.resetDepth();
    }
}
