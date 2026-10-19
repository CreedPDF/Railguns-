package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Rift visuals: the violet beam into the sky, the rift itself (a lumpy, crackling violet tear
 * with deep space behind it: stars, nebula, a burning sun and planets on glowing orbits), rim
 * lightning, bolts to the ground, the collapse and the shockwave when it slams shut.
 */
public final class RiftFx {
    private RiftFx() {}

    public static final Identifier SPACE = RailgunMod.id("textures/effect/rift_space.png");
    public static final Identifier NEBULA = RailgunMod.id("textures/effect/rift_nebula.png");
    public static final Identifier ORBIT = RailgunMod.id("textures/effect/orbit_ring.png");
    public static final Identifier SUN = RailgunMod.id("textures/effect/star_surface.png");
    public static final Identifier[] PLANETS = {
            RailgunMod.id("textures/effect/planet_rock.png"), RailgunMod.id("textures/effect/planet_ocean.png"),
            RailgunMod.id("textures/effect/planet_desert.png"), RailgunMod.id("textures/effect/planet_gas.png"),
            RailgunMod.id("textures/effect/planet_ice.png"), RailgunMod.id("textures/effect/planet_lava.png")};

    private record Bolt(Vec3d from, Vec3d to, long start, long seed) {}

    private static final List<Bolt> BOLTS = new ArrayList<>();
    private static final VertexConsumerProvider.Immediate BUFFER = VertexConsumerProvider.immediate(new BufferBuilder(1 << 21));

    /** orbit radius (fraction of the rift radius), planet radius, texture, period scale, ringed */
    private static final float[][] SYSTEM = {
            {0.20f, 0.030f, 0, 1.00f, 0}, {0.29f, 0.042f, 1, 0.72f, 0}, {0.38f, 0.036f, 5, 0.55f, 0},
            {0.50f, 0.070f, 3, 0.36f, 0}, {0.62f, 0.060f, 2, 0.27f, 1}, {0.74f, 0.045f, 4, 0.20f, 0}};

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RiftFx::render);
    }

    public static void bolt(ClientWorld world, Vec3d from, Vec3d to) {
        BOLTS.add(new Bolt(from, to, world.getTime(), world.random.nextLong()));
    }

    private static void render(WorldRenderContext ctx) {
        ClientWorld world = ctx.world();
        if (world == null) return;
        float td = ctx.tickDelta();
        long now = world.getTime();
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        boolean any = false;
        for (Entity e : world.getEntities()) {
            if (!(e instanceof RailgunEntity rg) || rg.getState() != RailgunState.RIFT) continue;
            any = true;
            rift(rg, rg.getStateTicks() + td, m, cam, now + td);
        }
        BOLTS.removeIf(b -> now - b.start > 9 || now < b.start);
        for (Bolt b : BOLTS) {
            any = true;
            float a = 1f - (now - b.start + td) / 9f;
            Random rand = Random.create(b.seed + (now - b.start) / 2);
            Vector3f from = rel(b.from, cam), to = rel(b.to, cam);
            double ang = rand.nextDouble() * Math.PI * 2;
            from.add((float) Math.cos(ang) * 10f, -0.3f, (float) Math.sin(ang) * 10f);
            for (int k = 0; k < 3; k++) {
                BeamRenderer.arc(BeamRenderer.glow(BUFFER), m, from, to, rand, 0.32f - k * 0.08f, 0.8f + 0.1f * k, 0.5f + 0.2f * k, 1f, a);
            }
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, to, 2.2f, 0.75f, 0.45f, 1f, 0.8f * a);
        }
        if (!any) return;
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        BUFFER.draw();
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    private static void rift(RailgunEntity rg, float t, Matrix4f m, Vec3d cam, float time) {
        Vector3f c = rel(rg.getRiftPos(1f), cam);
        Vector3f up = new Vector3f(0, 1, 0);
        float R = RailgunEntity.riftRadius(t);

        // ---- beam from the muzzle into the sky ----
        if (t > RailgunEntity.RIFT_FIRE && t < RailgunEntity.RIFT_SNAP + 4) {
            float a = t < RailgunEntity.RIFT_FIRE + 4 ? (t - RailgunEntity.RIFT_FIRE) / 4f
                    : t > RailgunEntity.RIFT_COLLAPSE ? Math.max(0.2f, 1f - (t - RailgunEntity.RIFT_COLLAPSE) / 44f) : 1f;
            float flick = 0.85f + 0.15f * MathHelper.sin(time * 5.3f);
            Vector3f mz = rel(rg.getMuzzlePos(1f), cam);
            BeamRenderer.Beam b = new BeamRenderer.Beam(mz, new Vector3f(c));
            float w = t < RailgunEntity.RIFT_OPEN ? 1.4f : 1f;
            BeamRenderer.texTube(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_CORE), m, b, 0.7f * w, 1f, 0.92f, 1f, a * flick, 5f, time * 3f, 0f, 0.05f, time);
            BeamRenderer.texTube(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_PLASMA), m, b, 1.5f * w, 0.7f, 0.4f, 1f, 0.8f * a, 10f, time * 2f, time * 0.05f, 0.1f, time);
            BeamRenderer.texTube(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_HALO), m, b, 3.0f * w, 0.5f, 0.2f, 0.9f, 0.45f * a, 16f, time, 0f, 0.2f, time);
            BeamRenderer.glowTube(BeamRenderer.glow(BUFFER), m, b, 6f * w, 0.45f, 0.15f, 0.8f, 0.1f * a, time);
            BeamRenderer.helix(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_HELIX), m, b, 2.4f, 0.9f, 0.18f, time * 0.4f, 0.6f, 0.8f, 1f, 0.7f * a, time);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, mz, 2.2f, 0.85f, 0.7f, 1f, 0.8f * a);
        }

        // ---- the tear opening: blinding flash and a ring blasting out across the sky ----
        if (t >= RailgunEntity.RIFT_OPEN && t < RailgunEntity.RIFT_OPEN + 24) {
            float f = (t - RailgunEntity.RIFT_OPEN) / 24f;
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, 4f + 20f * f, 0.85f, 0.7f, 1f, (1f - f) * 0.9f);
            float w = 4f + 70f * f;
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, up, w, w + 6f, 0.7f, 0.45f, 1f, 0.9f * (1 - f), time * 0.02f);
        }

        if (R > 0.05f) {
            float collapse = t > RailgunEntity.RIFT_COLLAPSE ? (t - RailgunEntity.RIFT_COLLAPSE) / (RailgunEntity.RIFT_SNAP - RailgunEntity.RIFT_COLLAPSE) : 0f;
            int seg = 96;
            float[] rr = new float[seg + 1];
            for (int i = 0; i <= seg; i++) {
                float th = i / (float) seg * MathHelper.TAU;
                rr[i] = R * edge(th, time);
            }
            // ---- deep space: opaque starfield disk (both faces) ----
            VertexConsumer sp = BUFFER.getBuffer(RenderLayer.getEntityTranslucentEmissive(SPACE));
            float rot = time * 0.0015f;
            for (int i = 0; i < seg; i++) {
                float a0 = i / (float) seg * MathHelper.TAU, a1 = (i + 1) / (float) seg * MathHelper.TAU;
                Vector3f p0 = new Vector3f(c.x + MathHelper.cos(a0) * rr[i], c.y, c.z + MathHelper.sin(a0) * rr[i]);
                Vector3f p1 = new Vector3f(c.x + MathHelper.cos(a1) * rr[i + 1], c.y, c.z + MathHelper.sin(a1) * rr[i + 1]);
                Vector3f[] q = {c, p0, p1, p1};
                for (int pass = 0; pass < 2; pass++) {
                    for (int k = 0; k < 4; k++) {
                        Vector3f v = q[pass == 0 ? k : 3 - k];
                        float lx = v.x - c.x, lz = v.z - c.z;
                        float u = 0.5f + (lx * MathHelper.cos(rot) - lz * MathHelper.sin(rot)) / (R * 2.6f);
                        float w = 0.5f + (lx * MathHelper.sin(rot) + lz * MathHelper.cos(rot)) / (R * 2.6f);
                        sp.vertex(m, v.x, v.y, v.z).color(1f, 1f, 1f, 1f).texture(u, w).overlay(OverlayTexture.DEFAULT_UV)
                                .light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0, -1, 0).next();
                    }
                }
            }
            Vector3f inner = new Vector3f(c).add(0, -0.08f, 0);
            // nebula glow
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, NEBULA), m, inner, up, 0.01f, R * 0.95f, 0.65f, 0.4f, 1f, 0.55f, time * 0.0008f);

            // ---- a little solar system inside ----
            Vector3f sys = new Vector3f(c).add(0, -0.3f, 0);
            float sunR = R * 0.095f * (1f + collapse * 0.6f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, sys, sunR * 0.8f, 1f, 0.97f, 0.88f, 1f);
            BeamRenderer.texSphere(BeamRenderer.tex(BUFFER, SUN), m, sys, sunR, 1f, 0.7f, 0.35f, 1f, time * 0.002f, time * 0.001f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, sys, sunR * 1.6f, 1f, 0.6f, 0.25f, 0.4f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, sys, sunR * 3f, 1f, 0.5f, 0.3f, 0.12f);
            for (int k = 0; k < SYSTEM.length; k++) {
                float[] pl = SYSTEM[k];
                float orb = pl[0] * R;
                BeamRenderer.texRing(BeamRenderer.tex(BUFFER, ORBIT), m, new Vector3f(sys).add(0, 0.02f, 0), up, orb - 0.05f, orb + 0.05f,
                        0.55f, 0.8f, 1f, 0.45f, 0f);
                float ang = k * 1.9f + time * 0.012f * pl[3];
                Vector3f pp = new Vector3f(sys.x + MathHelper.cos(ang) * orb, sys.y, sys.z + MathHelper.sin(ang) * orb);
                float pr = pl[1] * R;
                BeamRenderer.texSphere(BeamRenderer.tex(BUFFER, PLANETS[(int) pl[2]]), m, pp, pr, 1f, 1f, 1f, 1f, time * 0.004f * (k + 1), 0f);
                BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, pp, pr * 1.25f, 0.5f, 0.6f, 1f, 0.12f);
                if (pl[4] > 0) {
                    Vector3f n = new Vector3f(0.35f, 1f, 0.2f).normalize();
                    BeamRenderer.texRing(BeamRenderer.tex(BUFFER, ORBIT), m, pp, n, pr * 1.4f, pr * 2.3f, 0.95f, 0.85f, 0.65f, 0.75f, 0f);
                }
            }
            // a faint galactic glint across the middle
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, ORBIT), m, new Vector3f(sys).add(0, 0.05f, 0), new Vector3f(0.0f, 1f, 0.06f).normalize(),
                    R * 0.05f, R * 0.9f, 0.7f, 0.6f, 1f, 0.06f, 0f);

            // ---- the rim: violet electric edge ----
            VertexConsumer g = BeamRenderer.glow(BUFFER);
            float bright = 1f + collapse * 1.5f;
            band(g, m, c, rr, seg, 0.95f, 1.03f, -0.1f, 0.95f, 0.85f, 1f, Math.min(1f, 0.95f * bright));
            band(g, m, c, rr, seg, 0.99f, 1.10f, -0.12f, 0.62f, 0.38f, 1f, Math.min(1f, 0.75f * bright));
            band(g, m, c, rr, seg, 1.05f, 1.32f, -0.14f, 0.45f, 0.2f, 0.95f, 0.28f * bright);
            band(g, m, c, rr, seg, 1.2f, 1.8f, -0.16f, 0.35f, 0.15f, 0.8f, 0.08f * bright);
            Random rand = Random.create((long) (time / 2) * 131L + rg.getId());
            int arcs = 14 + (int) (collapse * 20);
            for (int k = 0; k < arcs; k++) {
                int i0 = rand.nextInt(seg), i1 = (i0 + 2 + rand.nextInt(6)) % seg;
                float a0 = i0 / (float) seg * MathHelper.TAU, a1 = i1 / (float) seg * MathHelper.TAU;
                float o0 = 1f + (rand.nextFloat() - 0.3f) * 0.12f, o1 = 1f + (rand.nextFloat() - 0.3f) * 0.12f;
                Vector3f s0 = new Vector3f(c.x + MathHelper.cos(a0) * rr[i0] * o0, c.y - 0.2f, c.z + MathHelper.sin(a0) * rr[i0] * o0);
                Vector3f s1 = new Vector3f(c.x + MathHelper.cos(a1) * rr[i1] * o1, c.y - 0.2f + (rand.nextFloat() - 0.5f) * 1.5f,
                        c.z + MathHelper.sin(a1) * rr[i1] * o1);
                BeamRenderer.arc(g, m, s0, s1, rand, 0.12f, 0.85f, 0.7f, 1f, 0.9f);
            }
        }

        // ---- slam shut: flash and a shockwave rolling across the sky ----
        if (t >= RailgunEntity.RIFT_SNAP && t < RailgunEntity.RIFT_SNAP + 34) {
            float f = (t - RailgunEntity.RIFT_SNAP) / 34f;
            float e = 1f - (1f - f) * (1f - f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, 3f + 12f * f, 0.95f, 0.85f, 1f, Math.max(0, 1f - f * 2.5f));
            float w = 3f + 90f * e;
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, up, w, w + 8f, 0.75f, 0.5f, 1f, 0.9f * (1 - f), time * 0.02f);
            float w2 = 2f + 55f * e;
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, up, w2, w2 + 3f, 0.95f, 0.9f, 1f, 0.7f * (1 - f), -time * 0.03f);
        }
    }

    /** Lumpy, slowly boiling rift outline (multiplier on the radius). */
    private static float edge(float th, float time) {
        return 1f + 0.07f * MathHelper.sin(5 * th + time * 0.04f) + 0.05f * MathHelper.sin(9 * th - time * 0.07f)
                + 0.035f * MathHelper.sin(17 * th + time * 0.11f) + 0.02f * MathHelper.sin(31 * th - time * 0.2f);
    }

    private static void band(VertexConsumer vc, Matrix4f m, Vector3f c, float[] rr, int seg, float k0, float k1, float dy,
                             float r, float g, float b, float a) {
        for (int i = 0; i < seg; i++) {
            float a0 = i / (float) seg * MathHelper.TAU, a1 = (i + 1) / (float) seg * MathHelper.TAU;
            float c0 = MathHelper.cos(a0), s0 = MathHelper.sin(a0), c1 = MathHelper.cos(a1), s1 = MathHelper.sin(a1);
            vc.vertex(m, c.x + c0 * rr[i] * k0, c.y + dy, c.z + s0 * rr[i] * k0).color(r, g, b, a).next();
            vc.vertex(m, c.x + c1 * rr[i + 1] * k0, c.y + dy, c.z + s1 * rr[i + 1] * k0).color(r, g, b, a).next();
            vc.vertex(m, c.x + c1 * rr[i + 1] * k1, c.y + dy, c.z + s1 * rr[i + 1] * k1).color(r, g, b, 0f).next();
            vc.vertex(m, c.x + c0 * rr[i] * k1, c.y + dy, c.z + s0 * rr[i] * k1).color(r, g, b, 0f).next();
        }
    }

    private static Vector3f rel(Vec3d p, Vec3d cam) {
        return new Vector3f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z));
    }
}
