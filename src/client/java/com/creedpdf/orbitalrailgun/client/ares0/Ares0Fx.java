package com.creedpdf.orbitalrailgun.client.ares0;

import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderer;
import com.creedpdf.orbitalrailgun.entity.Ares0Entity;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** ARES-0 effects: re-entry plasma, charge-up and the planet-killing beam (drawn with fog lifted). */
public final class Ares0Fx {
    private Ares0Fx() {}

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 21));

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(Ares0Fx::render);
    }

    private static void render(WorldRenderContext ctx) {
        ClientWorld w = ctx.world();
        if (w == null) return;
        boolean any = false;
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        Vec3d cam = ctx.camera().getPos();
        for (Entity e : w.getEntities()) {
            if (!(e instanceof Ares0Entity a)) continue;
            any = true;
            fx(a, ctx.tickDelta(), m, cam, BUF);
        }
        if (!any) return;
        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        BUF.draw();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    private static void fx(Ares0Entity e, float pt, Matrix4f m0, Vec3d cam, VertexConsumerProvider vcp) {
        Ares0Entity.State s = e.getState();
        if (s != Ares0Entity.State.CHARGING && s != Ares0Entity.State.FIRING && s != Ares0Entity.State.FALLING
                && s != Ares0Entity.State.COOLDOWN) return;
        float time = e.age + pt;
        float t = e.getStateTicks() + pt;
        Vec3d origin = e.getLerpedPos(pt);
        Matrix4f m = new Matrix4f(m0).translate((float) (origin.x - cam.x), (float) (origin.y - cam.y), (float) (origin.z - cam.z));
        Vec3d mzW = e.getMuzzlePos(pt);
        Vector3f mz = new Vector3f((float) (mzW.x - origin.x), (float) (mzW.y - origin.y), (float) (mzW.z - origin.z));
        Vec3d d = e.getBarrelDir(pt);
        Vector3f dir = new Vector3f((float) d.x, (float) d.y, (float) d.z);
        Vec3d pvW = e.getPivot(pt);
        Vector3f pivot = new Vector3f((float) (pvW.x - origin.x), (float) (pvW.y - origin.y), (float) (pvW.z - origin.z));

        switch (s) {
            case FALLING -> {
                // re-entry plasma sheath around the falling spike
                Vector3f top = new Vector3f(0, 34f, 0), bottom = new Vector3f(0, -2f, 0);
                BeamRenderer.Beam b = new BeamRenderer.Beam(bottom, top);
                BeamRenderer.texTube(BeamRenderer.tex(vcp, BeamRenderLayers.TINT_HALO), m, b, 5.5f, 1f, 0.55f, 0.2f, 0.55f, 6f, -time * 1.5f, 0f, 0.15f, time);
                BeamRenderer.glowTube(BeamRenderer.glow(vcp), m, b, 8f, 1f, 0.45f, 0.15f, 0.25f, time);
                BeamRenderer.glowSphere(BeamRenderer.glow(vcp), m, bottom, 6f, 1f, 0.8f, 0.5f, 0.6f);
            }
            case CHARGING -> {
                float f = MathHelper.clamp(t / Ares0Entity.CHARGE_TICKS, 0f, 1f);
                BeamRenderer.glowSphere(BeamRenderer.glow(vcp), m, mz, 1.2f + 4f * f, 0.75f, 0.85f, 1f, 0.4f + 0.5f * f);
                BeamRenderer.glowSphere(BeamRenderer.glow(vcp), m, mz, 3f + 10f * f, 0.45f, 0.4f, 1f, 0.15f * f);
                // energy running up the rails
                BeamRenderer.Beam rail = new BeamRenderer.Beam(pivot, mz);
                BeamRenderer.texTube(BeamRenderer.tex(vcp, BeamRenderLayers.TINT_CORE), m, rail, 0.35f + 0.5f * f, 0.7f, 0.85f, 1f, 0.6f * f, 4f, -time * 4f, 0f, 0.02f, time);
                Random r = Random.create((long) (time / 2) * 31L + e.getId());
                for (int k = 0; k < 2 + (int) (6 * f); k++) {
                    Vector3f a = new Vector3f(pivot).lerp(mz, r.nextFloat());
                    Vector3f b = new Vector3f(mz).add((r.nextFloat() - 0.5f) * 6f, (r.nextFloat() - 0.5f) * 6f, (r.nextFloat() - 0.5f) * 6f);
                    BeamRenderer.arc(BeamRenderer.glow(vcp), m, a, b, r, 0.12f, 0.7f, 0.85f, 1f, 0.8f);
                }
            }
            case FIRING, COOLDOWN -> {
                float a = s == Ares0Entity.State.FIRING ? Math.min(1f, t / 4f) : Math.max(0f, 1f - t / 25f);
                if (a <= 0f) break;
                float flick = 0.85f + 0.15f * MathHelper.sin(time * 4.3f);
                Vector3f end = new Vector3f(dir).mul(300f).add(mz);
                BeamRenderer.Beam b = new BeamRenderer.Beam(mz, end);
                BeamRenderer.texTube(BeamRenderer.tex(vcp, BeamRenderLayers.TINT_CORE), m, b, 1.4f, 1f, 1f, 1f, a * flick, 6f, time * 5f, 0f, 0.05f, time);
                BeamRenderer.texTube(BeamRenderer.tex(vcp, BeamRenderLayers.TINT_PLASMA), m, b, 3.2f, 0.55f, 0.75f, 1f, 0.9f * a, 12f, time * 3f, time * 0.05f, 0.12f, time);
                BeamRenderer.texTube(BeamRenderer.tex(vcp, BeamRenderLayers.TINT_HALO), m, b, 6.5f, 0.45f, 0.35f, 1f, 0.55f * a, 18f, time * 1.5f, 0f, 0.25f, time);
                BeamRenderer.glowTube(BeamRenderer.glow(vcp), m, b, 14f, 0.4f, 0.4f, 1f, 0.12f * a, time);
                BeamRenderer.helix(BeamRenderer.tex(vcp, BeamRenderLayers.TINT_HELIX), m, b, 5f, 1.4f, 0.08f, time * 0.5f, 0.75f, 0.85f, 1f, 0.8f * a, time);
                BeamRenderer.glowSphere(BeamRenderer.glow(vcp), m, mz, 5f, 0.9f, 0.95f, 1f, a);
                BeamRenderer.glowSphere(BeamRenderer.glow(vcp), m, mz, 12f, 0.5f, 0.55f, 1f, 0.3f * a);
                // pressure rings riding up the beam
                for (int k = 0; k < 4; k++) {
                    float u = ((time * 0.06f + k / 4f) % 1f);
                    Vector3f c = new Vector3f(dir).mul(4f + u * 60f).add(mz);
                    float w = 3f + u * 9f;
                    BeamRenderer.texRing(BeamRenderer.tex(vcp, BeamRenderLayers.TINT_SHOCKWAVE), m, c, dir, w, w + 1.6f, 0.7f, 0.8f, 1f, 0.7f * (1f - u) * a, time * 0.02f);
                }
                // ground blast ring around the base
                float g = (time * 0.04f) % 1f;
                BeamRenderer.texRing(BeamRenderer.tex(vcp, BeamRenderLayers.TINT_SHOCKWAVE), m, new Vector3f(0, 0.3f, 0), new Vector3f(0, 1, 0),
                        6f + g * 26f, 8f + g * 26f, 0.6f, 0.75f, 1f, 0.5f * (1f - g) * a, 0f);
            }
            default -> {}
        }
    }

}
