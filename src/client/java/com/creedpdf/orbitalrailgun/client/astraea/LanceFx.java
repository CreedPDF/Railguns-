package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.astraea.AstraeaItem;
import com.creedpdf.orbitalrailgun.astraea.Timeline;
import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Random;

import static com.creedpdf.orbitalrailgun.client.astraea.AstraeaDraw.*;

/**
 * The lance itself: in the hand / inventory (the Blender model, its halo and shards turning, wrapped in a crackling
 * white lightning aura that grows fierce once it is charged), and in flight (hurled up out of the hand into the sky,
 * and falling back into it at the end) as the model with a long white trail and a star's glare.
 */
public final class LanceFx {
    private LanceFx() {}

    // ------------------------------------------------------------------------------------------- the item

    public static void renderItem(ItemStack stack, ModelTransformationMode mode, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        KrlMesh mesh = KrlModels.ASTRAEA;
        if (mesh == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        float time = mc.world == null ? 0 : mc.world.getTime() + mc.getTickDelta();
        boolean charged = AstraeaItem.charged(stack);
        boolean gui = mode == ModelTransformationMode.GUI || mode == ModelTransformationMode.FIXED || mode == ModelTransformationMode.GROUND;
        ms.push();
        ms.translate(0.5, 0.5, 0.5);
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-45));
        if (gui) {
            float s = 0.44f;
            ms.scale(s, s, s);
            ms.translate(0, -0.28, 0);
        } else {
            float s = 0.62f;
            ms.scale(s, s, s);
            ms.translate(0, -0.1, 0);
        }
        drawModel(ms, vcp, light, time, charged ? 1f : 0.7f, gui ? 2 : 0);
        if (!KrlCompat.shadowPass()) aura(ms, vcp, time, charged, gui);
        ms.pop();
    }

    /** The model (body + turning halo and shards), glow at `glow` (0..1+). */
    static void drawModel(MatrixStack ms, VertexConsumerProvider vcp, int light, float time, float glow, int lod) {
        KrlMesh mesh = KrlModels.ASTRAEA;
        if (mesh == null) return;
        if (KrlCompat.shadowPass()) lod = 2;
        KrlMesh.Pose pose = (part, st) -> {
            if ("halo".equals(part)) st.multiply(RotationAxis.POSITIVE_Y.rotation(time * 0.05f));
            else if ("shards".equals(part)) {
                st.multiply(RotationAxis.POSITIVE_Y.rotation(-time * 0.08f));
                st.translate(0, MathHelper.sin(time * 0.1f) * 0.03f, 0);
            }
            return true;
        };
        mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.ASTRAEA_TEX)), light, pose, null);
        if (!KrlCompat.shadowPass()) {
            float g = glow;
            mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.ASTRAEA_GLOW)), light, pose, (nx, ny, nz, o) -> {
                o[0] = g;
                o[1] = g;
                o[2] = g;
                o[3] = 1f;
            });
        }
    }

    /** White lightning crawling over the lance (model space: the shaft along +Y), regenerated every couple of ticks. */
    private static void aura(MatrixStack ms, VertexConsumerProvider vcp, float time, boolean charged, boolean gui) {
        VertexConsumer g = vcp.getBuffer(BeamRenderLayers.GLOW);
        Matrix4f m = ms.peek().getPositionMatrix();
        long tick = (long) (time / 2f);
        Random r = new Random(tick * 7349L);
        int n = charged ? 7 : 4;
        float[] col = charged ? GOLD : WARM;
        for (int i = 0; i < n; i++) {
            float y0 = -1.1f + r.nextFloat() * 2.9f;
            float y1 = y0 + (0.15f + r.nextFloat() * 0.5f) * (r.nextBoolean() ? 1 : -1);
            float a0 = r.nextFloat() * MathHelper.TAU, a1 = a0 + (r.nextFloat() - 0.5f) * 2.5f;
            float rad0 = y0 > 0.95f ? 0.2f : 0.07f, rad1 = y1 > 0.95f ? 0.22f : 0.08f;
            Vector3f p0 = new Vector3f(MathHelper.cos(a0) * rad0, y0, MathHelper.sin(a0) * rad0);
            Vector3f p1 = new Vector3f(MathHelper.cos(a1) * rad1 * 1.6f, y1, MathHelper.sin(a1) * rad1 * 1.6f);
            float al = 0.5f + 0.5f * r.nextFloat();
            bolt(g, m, p0, p1, 0.12f, gui ? 0.02f : 0.012f, col, al, r.nextLong());
        }
        // a faint sheath of light along the blade
        strip(g, m, new Vector3f(0, 1.05f, 0), new Vector3f(0, 1.82f, 0), charged ? 0.45f : 0.3f, 0.05f, WHITE, charged ? 0.35f : 0.18f, 0f);
    }

    // ------------------------------------------------------------------------------------------- in flight

    /** The lance flying: up out of the hand, and back down into it. */
    static void flight(VertexConsumerProvider.Immediate buf, MatrixStack ms, Vec3d cam, float pt) {
        AstraeaState.Strike s = AstraeaState.strike;
        if (s == null) return;
        double t = AstraeaState.strikeT(pt);
        if (t < 0) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d pos;
        Vec3d dir;
        double trailLen;
        float glare;
        if (t >= Timeline.S_RELEASE && t < Timeline.S_ORBIT + 20) {
            double u = (t - Timeline.S_RELEASE) / (Timeline.S_ORBIT + 20 - Timeline.S_RELEASE);
            double h = 2.3 + 900 * Math.pow(u, 2.4);
            pos = s.anchor().add(0, h, 0);
            dir = new Vec3d(0, 1, 0);
            trailLen = Math.min(h - 2.3, 30 + 120 * u);
            glare = (float) (0.4 + 2.5 * u);
        } else if (t >= Timeline.S_RETURN && t <= Timeline.S_CATCH + 1) {
            double u = MathHelper.clamp((t - Timeline.S_RETURN) / (Timeline.S_CATCH - Timeline.S_RETURN), 0, 1);
            Entity who = mc.world == null ? null : mc.world.getEntityById(s.player());
            Vec3d hand = who != null ? who.getLerpedPos((float) pt).add(0, 1.5, 0) : s.anchor().add(0, 1.5, 0);
            double e = 1 - Math.pow(1 - u, 2.2);
            pos = hand.add(0, 700 * (1 - e), 0);
            dir = new Vec3d(0, -1, 0);
            trailLen = 20 + 140 * (1 - u);
            glare = (float) (2.6 * (1 - u) + 0.4);
        } else return;
        Vector3f p = rel(pos, cam);
        double dist = Math.sqrt(p.lengthSquared());
        // the model, when near enough to see
        if (dist < 220) {
            ms.push();
            ms.translate(p.x, p.y, p.z);
            ms.multiply(new Quaternionf().rotationTo(0f, 1f, 0f, (float) dir.x, (float) dir.y, (float) dir.z));
            ms.multiply(RotationAxis.POSITIVE_Y.rotation((float) t * 0.6f));
            ms.translate(0, -0.4, 0);
            drawModel(ms, buf, AstraeaDraw.LIGHT, (float) t, 1.2f, dist > 60 ? 2 : 0);
            ms.pop();
        }
        Matrix4f m = ms.peek().getPositionMatrix();
        VertexConsumer g = buf.getBuffer(BeamRenderLayers.GLOW);
        Vector3f tail = rel(pos.subtract(dir.multiply(trailLen)), cam);
        float w = (float) Math.max(0.35, dist * 0.004);
        strip(g, m, p, tail, w * 4.5f, w * 0.5f, WARM, 0.35f, 0f);
        strip(g, m, p, tail, w * 1.2f, w * 0.2f, WHITE, 1f, 0f);
        VertexConsumer st = buf.getBuffer(BeamRenderLayers.textured(STAR));
        float size = (float) Math.max(2.5, dist * 0.03) * glare;
        sprite(st, m, p, size, (float) t * 0.03f, WHITE, Math.min(1f, glare));
        // the catch
        if (t >= Timeline.S_CATCH - 1 && t <= Timeline.S_CATCH + 1) glowBall(buf.getBuffer(BeamRenderLayers.GLOW), m, p, 3f, WHITE, 1f);
    }
}
