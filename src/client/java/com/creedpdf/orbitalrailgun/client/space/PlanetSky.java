package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * The sky from a planet's surface. Where you stand on the planet decides where the sun and the
 * other bodies are (the surface maps onto the sphere), so walking far enough brings night.
 * Airless worlds show the full black, star-filled sky with the other bodies hanging in it -
 * Earth from the Moon, a gigantic Jupiter from Europa; Mars has a butterscotch daytime sky
 * with a blue sunset glow around the small sun; Venus is a furnace under endless yellow cloud.
 */
public final class PlanetSky {
    private PlanetSky() {}

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 20));
    public static final Identifier WHITE = BodyDraw.tex("white");

    public static void render(WorldRenderContext ctx) {
        ClientWorld w = ctx.world();
        if (w == null) return;
        Body b = SpaceDims.planetOf(w);
        if (b == null) return;
        float pt = ctx.tickDelta();
        float time = w.getTime() + pt;
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = new Matrix4f(ctx.matrixStack().peek().getPositionMatrix());

        Quaternionf toSpace = SpaceClient.surfaceFrame(b, cam.x, cam.z);
        Quaternionf toLocal = new Quaternionf(toSpace).conjugate();
        double alt = Math.max(2.0, cam.y - 60.0);
        Vec3d viewPos = b.surfaceToSpace(cam.x, cam.z, alt);
        Vector3f sun = toLocal.transform(new Vector3f(SpaceSky.SUN_DIR));
        float day = PlanetDrawMath.smooth(-0.12f, 0.18f, sun.y);

        float e = ClientPlanets.elapsed(b, pt);
        float doom = e >= 0 ? MathHelper.clamp(e / Blast.EJECT, 0f, 1f) : 0f;

        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        float stars, bodies, sunScale;
        switch (b) {
            case MARS -> {
                dome(m, sun, day, doom, 0.80f, 0.60f, 0.43f, 0.50f, 0.34f, 0.24f, true);
                stars = 1f - day * 0.97f;
                bodies = 1f - day * 0.75f;
                sunScale = 0.66f;
            }
            case VENUS -> {
                dome(m, sun, 0.55f + 0.45f * day, doom, 0.86f, 0.66f, 0.34f, 0.62f, 0.44f, 0.2f, false);
                stars = 0f;
                bodies = 0f;
                sunScale = 0f;
            }
            default -> {
                stars = 1f;
                bodies = 1f;
                sunScale = b == Body.EUROPA ? 0.35f : 1f;
                if (doom > 0) dome(m, sun, 0f, doom, 0f, 0f, 0f, 0f, 0f, 0f, false);
            }
        }
        SpaceSky.slot(0);
        BUF.draw();
        if (b == Body.VENUS) {
            // only a diffuse bright patch where the sun is
            VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
            PlanetBlastFx.glowBall(g, m, new Vector3f(sun).mul(30f), 9f, 1f, 0.92f, 0.7f, 0.35f * day);
            SpaceSky.slot(1);
            BUF.draw();
            SpaceSky.resetDepth();
            RenderSystem.setShaderFogStart(fs);
            RenderSystem.setShaderFogEnd(fe);
            return;
        }
        SpaceSky.resetDepth();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
        SpaceSky.render(m, viewPos, toLocal, time, pt, stars > 0.995f && doom == 0 ? 1f : stars * 0.999f, bodies, b, sunScale, 1);
    }

    /** Gradient sky dome (horizon -> zenith), blue-ish glow around the sun on Mars, red glare when the planet is dying. */
    private static void dome(Matrix4f m, Vector3f sun, float day, float doom, float hr, float hg, float hb, float zr, float zg, float zb, boolean blueSun) {
        VertexConsumer vc = BUF.getBuffer(BeamRenderLayers.solid(WHITE));
        Vector3f s = new Vector3f(sun).normalize();
        BodyDraw.sphere(vc, m, new Vector3f(), 40f, new Quaternionf(), 48, 96, (n, out) -> {
            float up = MathHelper.clamp(n.y, 0f, 1f);
            float h = (float) Math.pow(1f - up, 2.5);
            float r = MathHelper.lerp(h, zr, hr), g = MathHelper.lerp(h, zg, hg), bl = MathHelper.lerp(h, zb, hb);
            if (blueSun) {
                float near = (float) Math.pow(Math.max(0f, n.dot(s)), 24);
                r = MathHelper.lerp(near * 0.7f, r, 0.62f);
                g = MathHelper.lerp(near * 0.7f, g, 0.72f);
                bl = MathHelper.lerp(near * 0.7f, bl, 0.86f);
            }
            float k = day;
            r *= k; g *= k; bl *= k;
            if (doom > 0) {
                float glare = doom * (0.6f + 0.4f * h);
                r = MathHelper.lerp(glare, r, 0.95f);
                g = MathHelper.lerp(glare, g, 0.25f);
                bl = MathHelper.lerp(glare, bl, 0.08f);
            }
            out[0] = r; out[1] = g; out[2] = bl; out[3] = 1f;
        });
    }
}
