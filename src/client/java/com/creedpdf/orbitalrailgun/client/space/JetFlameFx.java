package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * The jetpack's exhaust: two small 3D blue flames, one under each nozzle. Each flame is a
 * revolved, flickering teardrop mesh in three nested shells (deep blue envelope, bright blue body,
 * blue-white core) with shock diamonds when boosting, so it reads as a solid plume from any angle.
 */
public final class JetFlameFx {
    private JetFlameFx() {}

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 18));
    /** Smoothed thrust per player entity id (0..1), plus boost flag. */
    private static final Map<Integer, float[]> STATE = new HashMap<>();

    private static final int AROUND = 28, ALONG = 20;
    /** Nozzle exit in the suit model (px): x = +-2.7, y = 8.3, 6.6 behind the body centre. */
    private static final float NOZZLE_X = 2.7f / 16f, NOZZLE_Y = 8.3f / 16f, NOZZLE_Z = 6.6f / 16f;

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(JetFlameFx::render);
    }

    /** Called every client tick with each flying player's target thrust. */
    static void set(PlayerEntity p, float target, boolean boost) {
        float[] s = STATE.computeIfAbsent(p.getId(), k -> new float[3]);
        s[0] += (target - s[0]) * 0.35f;
        s[1] = boost ? 1f : 0f;
        s[2] = 1f;
    }

    static void tickCleanup(ClientWorld w) {
        STATE.entrySet().removeIf(e -> {
            float[] s = e.getValue();
            if (s[2] < 0.5f) s[0] *= 0.6f;   // not thrusting this tick: the flame dies down
            s[2] = 0f;
            return s[0] < 0.01f || w.getEntityById(e.getKey()) == null;
        });
    }

    private static void render(WorldRenderContext ctx) {
        ClientWorld w = ctx.world();
        if (w == null || STATE.isEmpty()) return;
        float pt = ctx.tickDelta();
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean drew = false;
        for (Map.Entry<Integer, float[]> e : STATE.entrySet()) {
            if (!(w.getEntityById(e.getKey()) instanceof PlayerEntity p)) continue;
            float thrust = e.getValue()[0];
            if (thrust < 0.03f) continue;
            // first person: the flames are behind and below you, out of view anyway
            if (p == mc.player && mc.options.getPerspective().isFirstPerson()) continue;
            boolean boost = e.getValue()[1] > 0.5f;
            Vec3d pos = p.getLerpedPos(pt);
            float yaw = MathHelper.lerp(pt, p.prevBodyYaw, p.bodyYaw) * MathHelper.RADIANS_PER_DEGREE;
            Vector3f back = new Vector3f(MathHelper.sin(yaw), 0, -MathHelper.cos(yaw));
            Vector3f side = new Vector3f(MathHelper.cos(yaw), 0, MathHelper.sin(yaw));
            float sneak = p.isInSneakingPose() ? -0.2f : 0f;
            float time = p.age + pt;
            for (int s = -1; s <= 1; s += 2) {
                Vector3f nz = new Vector3f((float) (pos.x - cam.x), (float) (pos.y - cam.y) + NOZZLE_Y + sneak, (float) (pos.z - cam.z))
                        .add(new Vector3f(back).mul(NOZZLE_Z)).add(new Vector3f(side).mul(NOZZLE_X * s));
                flame(m, nz, back, thrust, boost, time + s * 7.3f);
            }
            drew = true;
        }
        if (!drew) return;
        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        BUF.draw();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    private static void flame(Matrix4f m, Vector3f nozzle, Vector3f back, float thrust, boolean boost, float time) {
        float flick = 0.9f + 0.1f * MathHelper.sin(time * 2.7f) * MathHelper.sin(time * 1.3f + 0.7f);
        float len = (0.28f + 0.5f * thrust + (boost ? 0.45f : 0f)) * flick;
        float r0 = 0.085f + 0.02f * thrust;
        // axis: straight down, kicked slightly back from the body
        Vector3f axis = new Vector3f(back).mul(0.18f).add(0, -1, 0).normalize();
        VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
        // envelope, body, core
        shell(g, m, nozzle, axis, len, r0 * 1.35f, time, 0.10f, 0.28f, 1.00f, 0.40f, 1.0f);
        shell(g, m, nozzle, axis, len * 0.8f, r0, time * 1.3f, 0.25f, 0.6f, 1.00f, 0.75f, 0.7f);
        shell(g, m, nozzle, axis, len * 0.42f, r0 * 0.55f, time * 1.7f, 0.80f, 0.93f, 1.00f, 1.00f, 0.4f);
        // shock diamonds
        if (boost) {
            for (int k = 1; k <= 3; k++) {
                float s = k * 0.2f;
                Vector3f c = new Vector3f(axis).mul(len * s).add(nozzle);
                diamond(g, m, c, axis, r0 * (0.75f - 0.12f * k), len * 0.07f, 0.7f, 0.85f, 1f, 0.8f - 0.18f * k);
            }
        }
        // the hot nozzle mouth
        com.creedpdf.orbitalrailgun.client.space.PlanetBlastFx.glowBall(g, m, nozzle, r0 * 1.6f, 0.35f, 0.6f, 1f, 0.5f * thrust);
    }

    /** Revolved flame shell: round shoulder at the nozzle, tapering to a wavering tip. */
    private static void shell(VertexConsumer g, Matrix4f m, Vector3f base, Vector3f axis, float len, float radius, float time,
                              float cr, float cg, float cb, float a, float turbulence) {
        Vector3f u = new Vector3f(axis).cross(Math.abs(axis.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).normalize();
        Vector3f v = new Vector3f(axis).cross(u).normalize();
        Vector3f[][] p = new Vector3f[ALONG + 1][AROUND + 1];
        float[][] al = new float[ALONG + 1][AROUND + 1];
        for (int i = 0; i <= ALONG; i++) {
            float s = i / (float) ALONG;
            float prof = (float) Math.pow(Math.sin(Math.min(1f, s * 3.2f) * MathHelper.HALF_PI), 0.6) * (float) Math.pow(1f - s, 0.85f);
            float rad = radius * (0.55f + 0.45f * prof / 0.75f) * (s < 0.31f ? 1f : 1f) * (float) Math.pow(1f - s, 0.6f);
            // the tip whips about
            float wob = s * s * len * 0.12f * turbulence;
            Vector3f centre = new Vector3f(axis).mul(len * s).add(base)
                    .add(new Vector3f(u).mul(wob * MathHelper.sin(time * 0.9f + s * 4f)))
                    .add(new Vector3f(v).mul(wob * MathHelper.cos(time * 1.1f + s * 5f)));
            for (int j = 0; j <= AROUND; j++) {
                float th = j / (float) AROUND * MathHelper.TAU;
                float lick = 1f + turbulence * s * 0.28f * MathHelper.sin(th * 3f + time * 1.9f - s * 11f)
                        + turbulence * 0.1f * MathHelper.sin(th * 5f - time * 2.6f + s * 7f);
                float rr = rad * lick;
                p[i][j] = new Vector3f(u).mul(MathHelper.cos(th) * rr).add(new Vector3f(v).mul(MathHelper.sin(th) * rr)).add(centre);
                al[i][j] = a * (float) Math.pow(1f - s, 0.7f);
            }
        }
        for (int i = 0; i < ALONG; i++) {
            for (int j = 0; j < AROUND; j++) {
                int[][] q = {{i, j}, {i, j + 1}, {i + 1, j + 1}, {i + 1, j}};
                for (int[] k : q) {
                    Vector3f x = p[k[0]][k[1]];
                    // soft silhouette: faces seen edge-on fade out
                    Vector3f n = new Vector3f(x).sub(new Vector3f(axis).mul(new Vector3f(x).sub(base).dot(axis)).add(base));
                    float f = n.lengthSquared() > 1e-10f ? Math.abs(n.normalize().dot(new Vector3f(x).normalize())) : 1f;
                    g.vertex(m, x.x, x.y, x.z).color(cr, cg, cb, al[k[0]][k[1]] * (0.35f + 0.65f * f)).next();
                }
            }
        }
    }

    /** A bright double-cone ring (Mach disk) on the flame axis. */
    private static void diamond(VertexConsumer g, Matrix4f m, Vector3f c, Vector3f axis, float r, float h, float cr, float cg, float cb, float a) {
        Vector3f u = new Vector3f(axis).cross(Math.abs(axis.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).normalize();
        Vector3f v = new Vector3f(axis).cross(u).normalize();
        Vector3f top = new Vector3f(axis).mul(-h).add(c), bot = new Vector3f(axis).mul(h).add(c);
        int n = 16;
        for (int j = 0; j < n; j++) {
            float t0 = j / (float) n * MathHelper.TAU, t1 = (j + 1) / (float) n * MathHelper.TAU;
            Vector3f e0 = new Vector3f(u).mul(MathHelper.cos(t0) * r).add(new Vector3f(v).mul(MathHelper.sin(t0) * r)).add(c);
            Vector3f e1 = new Vector3f(u).mul(MathHelper.cos(t1) * r).add(new Vector3f(v).mul(MathHelper.sin(t1) * r)).add(c);
            for (Vector3f tip : new Vector3f[]{top, bot}) {
                g.vertex(m, tip.x, tip.y, tip.z).color(cr, cg, cb, a).next();
                g.vertex(m, e0.x, e0.y, e0.z).color(cr, cg, cb, a * 0.5f).next();
                g.vertex(m, e1.x, e1.y, e1.z).color(cr, cg, cb, a * 0.5f).next();
                g.vertex(m, tip.x, tip.y, tip.z).color(cr, cg, cb, a).next();
            }
        }
    }
}
