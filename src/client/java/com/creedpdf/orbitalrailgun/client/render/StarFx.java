package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Eclipse Core visuals: the accretion point, the star (granulated plasma surface, white-hot core,
 * layered halos, a rotating corona, looping prominences, a lensing ring, a pool of light on the
 * ground), solar flares arcing into targets, the collapse and the nova shockwave dome.
 */
public final class StarFx {
    private StarFx() {}

    public static final Identifier SURFACE = RailgunMod.id("textures/effect/star_surface.png");
    public static final Identifier CORONA = RailgunMod.id("textures/effect/star_corona.png");
    public static final Identifier NOVA = RailgunMod.id("textures/effect/nova_shell.png");

    private record Flare(Vec3d from, Vec3d to, long start, long seed) {}

    private static final List<Flare> FLARES = new ArrayList<>();
    private static final VertexConsumerProvider.Immediate BUFFER = VertexConsumerProvider.immediate(new BufferBuilder(1 << 21));
    private static final float R = 3.2f;   // burning radius, blocks

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(StarFx::render);
    }

    public static void flare(ClientWorld world, Vec3d from, Vec3d to) {
        FLARES.add(new Flare(from, to, world.getTime(), world.random.nextLong()));
    }

    private static void render(WorldRenderContext ctx) {
        ClientWorld world = ctx.world();
        if (world == null) return;
        float td = ctx.tickDelta();
        long now = world.getTime();
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        Quaternionf rot = ctx.camera().getRotation();
        boolean any = false;

        for (Entity e : world.getEntities()) {
            if (!(e instanceof RailgunEntity rg) || rg.getState() != RailgunState.STAR) continue;
            any = true;
            star(rg, rg.getStateTicks() + td, m, cam, rot, now + td);
        }
        FLARES.removeIf(f -> now - f.start > 8 || now < f.start);
        for (Flare f : FLARES) {
            any = true;
            float a = 1f - (now - f.start + td) / 8f;
            Vector3f from = rel(f.from, cam), to = rel(f.to, cam);
            Vector3f dir = new Vector3f(to).sub(from).normalize();
            Vector3f start = new Vector3f(dir).mul(R * 0.9f).add(from);
            Random rand = Random.create(f.seed + (now - f.start) / 2);
            for (int k = 0; k < 3; k++) {
                BeamRenderer.arc(BeamRenderer.glow(BUFFER), m, start, to, rand, 0.22f - k * 0.05f, 1f, 0.75f - k * 0.15f, 0.35f - k * 0.1f, a);
            }
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, to, 1.2f, 1f, 0.7f, 0.3f, 0.8f * a);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, start, 1.4f, 1f, 0.95f, 0.8f, 0.9f * a);
        }
        if (!any) return;
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        BUFFER.draw();
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    private static void star(RailgunEntity rg, float t, Matrix4f m, Vec3d cam, Quaternionf rot, float time) {
        Vector3f c = rel(rg.getStarPos(1f), cam);
        Vector3f ground = rel(rg.getPos().add(0, 0.12, 0), cam);
        Vector3f toCam = new Vector3f(c).negate().normalize();
        Vector3f up = new Vector3f(0, 1, 0);

        // ---- accretion: a hot point fed by the streams, growing toward ignition ----
        if (t >= RailgunEntity.STAR_ACCRETE && t < RailgunEntity.STAR_IGNITE + 4) {
            float f = MathHelper.clamp((t - RailgunEntity.STAR_ACCRETE) / (RailgunEntity.STAR_IGNITE - RailgunEntity.STAR_ACCRETE), 0f, 1f);
            float flick = 0.85f + 0.15f * MathHelper.sin(time * 1.7f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, 0.25f + 0.9f * f, 1f, 0.95f, 0.85f, flick);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, 0.8f + 2.6f * f, 1f, 0.55f, 0.2f, 0.35f * f);
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, toCam,
                    3.5f - 2.5f * f, 4.2f - 2.6f * f, 1f, 0.6f, 0.25f, 0.6f * f, time * 0.05f);
        }

        // ---- the star ----
        float radius;
        float heat = 1f;
        if (t < RailgunEntity.STAR_IGNITE) radius = 0f;
        else if (t < RailgunEntity.STAR_IGNITE + 12) {
            float f = (t - RailgunEntity.STAR_IGNITE) / 12f;
            radius = R * (float) (1 + 0.35 * Math.sin(f * Math.PI)) * Math.min(1f, f * 2.5f);
        } else if (t < RailgunEntity.STAR_COLLAPSE) {
            radius = R * (1f + 0.04f * MathHelper.sin(time * 0.9f) + 0.02f * MathHelper.sin(time * 2.3f));
        } else if (t < RailgunEntity.STAR_NOVA) {
            float f = (t - RailgunEntity.STAR_COLLAPSE) / (RailgunEntity.STAR_NOVA - RailgunEntity.STAR_COLLAPSE);
            radius = R * (1f - 0.82f * f * f);
            heat = 1f + 1.5f * f;
        } else radius = 0f;

        if (radius > 0.01f) {
            float[] surf = {1f, 0.62f / heat + 0.3f * (heat - 1), 0.22f / heat + 0.5f * (heat - 1)};
            // ground pool of light
            float pulse = 0.9f + 0.1f * MathHelper.sin(time * 0.7f);
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, ground, up, 0.5f, 22f * pulse,
                    1f, 0.55f, 0.2f, 0.35f, time * 0.004f);
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, ground, up, 6f, 9f,
                    1f, 0.75f, 0.35f, 0.4f * pulse, -time * 0.01f);
            // corona: two big rotating ray sprites facing the camera
            sprite(BeamRenderer.tex(BUFFER, CORONA), m, c, radius * 7.5f, rot, 1f, 0.7f, 0.35f, 0.75f, time * 0.006f);
            sprite(BeamRenderer.tex(BUFFER, CORONA), m, c, radius * 5.2f, rot, 1f, 0.85f, 0.6f, 0.6f, -time * 0.011f + 1f);
            // halos
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, radius * 1.35f, 1f, 0.65f, 0.25f, 0.45f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, radius * 2.2f, 1f, 0.45f, 0.15f, 0.18f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, radius * 4.5f, 1f, 0.35f, 0.1f, 0.07f);
            // surface: two counter-scrolling granulation layers over a white core
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, radius * 0.8f, 1f, 0.97f, 0.9f, 1f);
            BeamRenderer.texSphere(BeamRenderer.tex(BUFFER, SURFACE), m, c, radius, surf[0], surf[1], surf[2], 1f, time * 0.002f, time * 0.0013f);
            BeamRenderer.texSphere(BeamRenderer.tex(BUFFER, SURFACE), m, c, radius * 1.03f, 1f, Math.min(1, surf[1] + 0.2f), surf[2], 0.55f, -time * 0.0031f, time * 0.0007f + 0.37f);
            // lensing ring
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, toCam, radius * 1.5f, radius * 1.85f,
                    1f, 0.8f, 0.55f, 0.25f, time * 0.02f);
            // prominences: plasma loops rising off the limb and falling back
            for (int k = 0; k < 7; k++) {
                float base = k * 0.9f + time * 0.004f * (k % 2 == 0 ? 1 : -1);
                float tilt = 0.6f * MathHelper.sin(k * 1.7f);
                float life = (time * 0.012f + k * 0.37f) % 1f;
                float h = MathHelper.sin(life * MathHelper.PI) * radius * (0.45f + 0.25f * (k % 3));
                Vector3f a = dirOn(base, tilt), b = dirOn(base + 0.5f, tilt + 0.2f);
                int beads = 14;
                for (int i = 0; i <= beads; i++) {
                    float f = i / (float) beads;
                    Vector3f d = new Vector3f(a).lerp(b, f).normalize();
                    float lift = MathHelper.sin(f * MathHelper.PI) * h;
                    Vector3f p = new Vector3f(d).mul(radius + lift).add(c);
                    BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, p, 0.22f + 0.1f * MathHelper.sin(f * MathHelper.PI),
                            1f, 0.55f + 0.2f * f, 0.2f, 0.75f * MathHelper.sin(life * MathHelper.PI));
                }
            }
            // collapse: an inward-rushing ring
            if (t > RailgunEntity.STAR_COLLAPSE) {
                float f = (t - RailgunEntity.STAR_COLLAPSE) / (RailgunEntity.STAR_NOVA - RailgunEntity.STAR_COLLAPSE);
                float rr = radius + 18f * (1f - f);
                BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, toCam, rr, rr + 1.5f,
                        1f, 0.9f, 0.7f, 0.7f * f, time * 0.03f);
            }
        }

        // ---- nova ----
        if (t >= RailgunEntity.STAR_NOVA && t < RailgunEntity.STAR_NOVA + 50) {
            float f = (t - RailgunEntity.STAR_NOVA) / 50f;
            float ease = 1f - (1f - f) * (1f - f);
            float dome = 2f + 38f * ease;
            float a = (1f - f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, 3f + 10f * f, 1f, 1f, 0.95f, Math.max(0, 1f - f * 3f));
            BeamRenderer.texSphere(BeamRenderer.tex(BUFFER, NOVA), m, c, dome, 1f, 0.75f, 0.45f, 0.85f * a, time * 0.01f, 0f);
            BeamRenderer.texSphere(BeamRenderer.tex(BUFFER, NOVA), m, c, dome * 0.82f, 1f, 0.5f, 0.25f, 0.5f * a, -time * 0.013f, 0.5f);
            BeamRenderer.glowSphere(BeamRenderer.glow(BUFFER), m, c, dome * 1.05f, 1f, 0.45f, 0.15f, 0.12f * a);
            float w = 3f + 60f * ease;
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, ground, up, w, w + 6f,
                    1f, 0.7f, 0.35f, 0.9f * a, time * 0.01f);
            float w2 = 2f + 42f * ease;
            BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, new Vector3f(ground).add(0, 0.2f, 0), up,
                    w2, w2 + 3f, 1f, 0.9f, 0.7f, 0.7f * a, -time * 0.015f);
            for (int k = 0; k < 3; k++) {
                float q = Math.max(0, f * 1.4f - k * 0.12f);
                if (q <= 0 || q >= 1) continue;
                float rr = 4f + 50f * q;
                Vector3f n = dirOn(k * 2.1f, 0.9f);
                BeamRenderer.texRing(BeamRenderer.tex(BUFFER, BeamRenderLayers.TINT_SHOCKWAVE), m, c, n, rr, rr + 2.5f,
                        1f, 0.8f, 0.5f, 0.7f * (1 - q), time * 0.02f);
            }
        }
    }

    private static Vector3f dirOn(float az, float el) {
        return new Vector3f(MathHelper.cos(az) * MathHelper.cos(el), MathHelper.sin(el), MathHelper.sin(az) * MathHelper.cos(el));
    }

    private static Vector3f rel(Vec3d p, Vec3d cam) {
        return new Vector3f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z));
    }

    private static void sprite(VertexConsumer vc, Matrix4f m, Vector3f c, float size, Quaternionf rot,
                               float r, float g, float b, float a, float roll) {
        Vector3f right = rot.transform(new Vector3f(1, 0, 0)), up = rot.transform(new Vector3f(0, 1, 0));
        float cs = MathHelper.cos(roll), sn = MathHelper.sin(roll);
        Vector3f rr = new Vector3f(right).mul(cs).add(new Vector3f(up).mul(sn)).mul(size * 0.5f);
        Vector3f uu = new Vector3f(up).mul(cs).sub(new Vector3f(right).mul(sn)).mul(size * 0.5f);
        BeamRenderer.texVertRaw(vc, m, new Vector3f(c).sub(rr).sub(uu), r, g, b, a, 0, 1);
        BeamRenderer.texVertRaw(vc, m, new Vector3f(c).add(rr).sub(uu), r, g, b, a, 1, 1);
        BeamRenderer.texVertRaw(vc, m, new Vector3f(c).add(rr).add(uu), r, g, b, a, 1, 0);
        BeamRenderer.texVertRaw(vc, m, new Vector3f(c).sub(rr).add(uu), r, g, b, a, 0, 0);
    }
}
