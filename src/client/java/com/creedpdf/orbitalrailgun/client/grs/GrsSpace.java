package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.space.ClientPlanets;
import com.creedpdf.orbitalrailgun.grs.Grs;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The Gungnir Railway Station where it lives: on the rings of Jupiter in the Space dimension. Draws Jupiter's dusty
 * rings (in two halves, behind and in front of the planet), the station itself at true scale when you are close and as
 * an impostor when you are far, and Gungnir lapping its accelerator ring as an orb of orange light with a long wake.
 * Every time the orb goes by you, you hear it.
 */
public final class GrsSpace {
    private GrsSpace() {}

    public static final Identifier RING = RailgunMod.id("textures/grs/fx/jupiter_ring.png");
    private static final float IMPOSTOR = 60f;
    /** Radius of the station's accelerator ring in the model, blocks. */
    public static final float TRACK_R = 56f;

    /** Angle of the spear on its lap. */
    public static float lapAngle(float time) {
        return time / Grs.LAP_TICKS * MathHelper.TAU;
    }

    public static float stationYaw(float time) {
        return time * 0.0008f;
    }

    /** World position of the lapping spear (space dimension). */
    public static Vec3d orbPos(float time) {
        float a = lapAngle(time) + stationYaw(time);
        return Grs.stationPos().add(MathHelper.cos(a) * TRACK_R, 1.5, MathHelper.sin(a) * TRACK_R);
    }

    private static boolean alive(float pt) {
        return ClientPlanets.elapsed(Body.JUPITER, pt) < Blast.DETONATE;
    }

    /**
     * One half of the Jupiter system for the space sky: the far half of the rings (and the station if it is behind
     * Jupiter's centre), or the near half. {@code jc}, {@code jr} are Jupiter's impostor centre and radius.
     */
    public static void pass(VertexConsumerProvider buf, Matrix4f m, Vec3d viewPos, Quaternionf toLocal, Vector3f jc, float jr, Vector3f sun,
                            float time, float pt, float alpha, boolean front) {
        if (!alive(pt) || alpha < 0.02f) return;
        GrsDraw.camera(MinecraftClient.getInstance().gameRenderer.getCamera());
        rings(buf, m, jc, jr, toLocal, sun, alpha, front);
        if (!front && !stationInFront(viewPos)) station(buf, m, viewPos, toLocal, sun, time, alpha);
    }

    /** True when the station is nearer than Jupiter's centre (then it is drawn last, with real depth). */
    public static boolean stationInFront(Vec3d viewPos) {
        return Grs.stationPos().distanceTo(viewPos) < Body.JUPITER.center.distanceTo(viewPos);
    }

    /** The station in front of Jupiter: drawn after the sky's layers with the normal depth range, so its own geometry sorts. */
    public static void stationFront(VertexConsumerProvider buf, Matrix4f m, Vec3d viewPos, Quaternionf toLocal, Vector3f sun, float time, float pt,
                                    float alpha) {
        if (!alive(pt) || alpha < 0.02f || !stationInFront(viewPos)) return;
        GrsDraw.camera(MinecraftClient.getInstance().gameRenderer.getCamera());
        station(buf, m, viewPos, toLocal, sun, time, alpha);
    }

    private static void rings(VertexConsumerProvider buf, Matrix4f m, Vector3f c, float r, Quaternionf toLocal, Vector3f sun, float alpha, boolean front) {
        float rin = (float) (r * Grs.RING_IN), rout = (float) (r * Grs.RING_OUT);
        Vector3f e1 = toLocal.transform(new Vector3f(1, 0, 0)), e2 = toLocal.transform(new Vector3f(0, 0, 1));
        VertexConsumer vc = buf.getBuffer(BeamRenderLayers.translucent(RING));
        int n = 192;
        for (int i = 0; i < n; i++) {
            float a0 = i / (float) n * MathHelper.TAU, a1 = (i + 1) / (float) n * MathHelper.TAU;
            float am = (a0 + a1) * 0.5f;
            Vector3f mid = new Vector3f(e1).mul(MathHelper.cos(am)).add(new Vector3f(e2).mul(MathHelper.sin(am)));
            // the far half is behind the planet's centre as seen from the eye (at the origin)
            boolean back = mid.dot(c) > 0;
            if (back == front) continue;
            float lit = shadow(mid, rin * 0.5f + rout * 0.5f, r, sun) * alpha;
            float[][] q = {{a0, rin, 0}, {a0, rout, 1}, {a1, rout, 1}, {a1, rin, 0}};
            for (float[] p : q) {
                Vector3f d = new Vector3f(e1).mul(MathHelper.cos(p[0])).add(new Vector3f(e2).mul(MathHelper.sin(p[0])));
                Vector3f v = new Vector3f(c).add(d.mul(p[1]));
                GrsDraw.v(vc, m, v.x, v.y, v.z, p[2], 0.5f, lit, lit * 0.95f, lit * 0.9f, alpha);
            }
        }
    }

    /** 1 in sunlight, dark where Jupiter's shadow falls across the ring. */
    private static float shadow(Vector3f dir, float rad, float r, Vector3f sun) {
        Vector3f p = new Vector3f(dir).mul(rad);
        float along = p.dot(sun);
        if (along > 0) return 0.95f;
        float perp = new Vector3f(p).sub(new Vector3f(sun).mul(along)).length();
        return perp < r ? 0.07f : 0.95f;
    }

    private static void station(VertexConsumerProvider buf, Matrix4f m, Vec3d viewPos, Quaternionf toLocal, Vector3f sun, float time, float alpha) {
        Vec3d rel = Grs.stationPos().subtract(viewPos);
        double d = rel.length();
        float k = d < IMPOSTOR ? 1f : (float) (IMPOSTOR * 0.96 / d);
        Vector3f c = toLocal.transform(new Vector3f((float) rel.x * k, (float) rel.y * k, (float) rel.z * k));
        float yaw = stationYaw(time);
        Quaternionf q = new Quaternionf(toLocal).rotateY(-yaw);
        MatrixStack ms = new MatrixStack();
        ms.peek().getPositionMatrix().set(m);
        ms.translate(c.x, c.y, c.z);
        ms.scale(k, k, k);
        ms.multiply(q);
        float ang = (float) (90 / d);
        if (GrsModels.STATION != null && ang > 0.002f) {
            Vector3f jup = Body.JUPITER.center.subtract(Grs.stationPos()).normalize().toVector3f();
            GrsMesh.Shade shade = (nx, ny, nz, o) -> {
                // the stack's normal matrix carries the station's rotation: these normals are already in the sky frame
                float s = Math.max(0, nx * sun.x + ny * sun.y + nz * sun.z);
                float j = Math.max(0, nx * jup.x + ny * jup.y + nz * jup.z) * 0.22f;
                o[0] = (0.05f + 0.95f * s + j) * alpha;
                o[1] = (0.05f + 0.92f * s + j * 0.7f) * alpha;
                o[2] = (0.05f + 0.88f * s + j * 0.45f) * alpha;
                o[3] = 1;
            };
            GrsMesh.Pose pose = (part, mst) -> {
                if (part.equals("dish")) mst.multiply(new Quaternionf().rotateY(time * 0.003f));
                return true;
            };
            GrsModels.STATION.renderNoOverlay(ms, buf.getBuffer(BeamRenderLayers.solid(GrsModels.STATION_TEX)), GrsDraw.FULL, pose, shade);
            float g = (0.85f + 0.15f * MathHelper.sin(time * 0.2f)) * alpha;
            GrsModels.STATION.renderNoOverlay(ms, buf.getBuffer(BeamRenderLayers.textured(GrsModels.STATION_GLOW)), GrsDraw.FULL, pose, (nx, ny, nz, o) -> {
                o[0] = g;
                o[1] = g;
                o[2] = g;
                o[3] = 1;
            });
        }
        // Gungnir on its lap: an orb of light with a long wake round the track (placed in the sky frame so the
        // sprites keep facing the camera)
        float a = lapAngle(time);
        float minS = 0.12f;
        VertexConsumer orb = buf.getBuffer(BeamRenderLayers.textured(GrsDraw.ORB));
        Vector3f head = null;
        for (int i = 0; i < 40; i++) {
            float aa = a - i * 0.05f;
            float fade = (float) Math.pow(1 - i / 40f, 1.6) * alpha;
            Vector3f p = q.transform(new Vector3f(MathHelper.cos(aa) * TRACK_R, 1.5f, MathHelper.sin(aa) * TRACK_R)).mul(k).add(c);
            if (i == 0) head = p;
            float sz = (i == 0 ? 3.2f : 2.4f * (1 - i / 48f)) * k;
            if (i == 0) sz = Math.max(sz, minS);
            GrsDraw.sprite(orb, m, p.x, p.y, p.z, sz, 0, fade, 0.45f * fade + (i == 0 ? 0.3f : 0), 0.08f * fade + (i == 0 ? 0.2f : 0), 1);
        }
        GrsDraw.sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.FLARE)), m, head.x, head.y, head.z, Math.max(14f * k, minS * 5), time * 0.05f, alpha,
                0.5f * alpha, 0.1f * alpha, 1);
    }

    /** From Earth: a strobing orange star riding just off Jupiter's disc - the spear on its lap, seen across the solar system. */
    public static void overworldMark(VertexConsumerProvider buf, Matrix4f m, Vector3f jc, float jr, float time, float pt, float alpha) {
        if (!alive(pt) || alpha < 0.02f) return;
        GrsDraw.camera(MinecraftClient.getInstance().gameRenderer.getCamera());
        float a = lapAngle(time);
        float strobe = 0.55f + 0.45f * (float) Math.pow(0.5f + 0.5f * MathHelper.cos(a), 8);
        Vector3f side = new Vector3f(jc).cross(0, 1, 0);
        if (side.lengthSquared() < 1e-6f) side.set(1, 0, 0);
        side.normalize();
        Vector3f p = new Vector3f(jc).add(side.mul(jr * 1.62f));
        boolean worn = MinecraftClient.getInstance().player != null && Grs.wearing(MinecraftClient.getInstance().player);
        float k = alpha * strobe * (worn ? 1.4f : 0.9f);
        GrsDraw.sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.ORB)), m, p.x, p.y, p.z, Math.max(0.5f, jr * 0.12f), 0, k, 0.5f * k, 0.1f * k, 1);
        GrsDraw.sprite(buf.getBuffer(BeamRenderLayers.textured(GrsDraw.FLARE)), m, p.x, p.y, p.z, Math.max(1.6f, jr * 0.4f), time * 0.02f, k * 0.6f,
                0.3f * k, 0.05f * k, 1);
    }

    // ------------------------------------------------------------------------------------------- the pass-by roar

    private static float lastRel = Float.NaN;

    /** Client tick: when the spear on its lap goes past you near the station, play the zoom of it going by. */
    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || !SpaceDims.isSpace(mc.world) || !alive(0)) {
            lastRel = Float.NaN;
            return;
        }
        Vec3d s = Grs.stationPos();
        Vec3d eye = mc.player.getEyePos();
        double dist = eye.distanceTo(s);
        if (dist > 900) {
            lastRel = Float.NaN;
            return;
        }
        float time = mc.world.getTime();
        float myAng = (float) Math.atan2(eye.z - s.z, eye.x - s.x);
        float orb = lapAngle(time) + stationYaw(time);
        float rel = MathHelper.wrapDegrees((float) Math.toDegrees(orb - myAng));
        if (!Float.isNaN(lastRel) && lastRel < 0 && rel >= 0 && Math.abs(rel - lastRel) < 180) {
            Vec3d o = orbPos(time);
            double od = o.distanceTo(eye);
            float vol = (float) MathHelper.clamp(1.8 - od / 260.0, 0.15, 1.8);
            float pitch = (float) MathHelper.clamp(1.15 - od / 900.0, 0.75, 1.2);
            mc.world.playSound(o.x, o.y, o.z, ModSounds.GRS_WHOOSH, SoundCategory.AMBIENT, vol, pitch, false);
        }
        lastRel = rel;
    }
}
