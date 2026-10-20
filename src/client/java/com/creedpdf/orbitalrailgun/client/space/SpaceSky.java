package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderer;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Realistic sky seen from space (and, filtered through an atmosphere, from the planets): a
 * black sky packed with sharp stars and the Milky Way, a white sun with a hard glare, and every
 * body of the system as a lit 3D sphere at its true direction and apparent size.
 *
 * <p>Bodies are thousands of blocks away, far beyond the camera's far plane, so each one is
 * drawn as an exact "impostor": its relative position and radius are scaled by the same factor,
 * which keeps its direction and angular size exact. Bodies are drawn far to near with the depth
 * buffer cleared between them.</p>
 */
public final class SpaceSky {
    private SpaceSky() {}

    public static final Identifier STARS = BodyDraw.tex("space_sky");
    public static final Identifier SUN = BodyDraw.tex("sun");
    public static final Identifier SUN_GLARE = BodyDraw.tex("sun_glare");
    /** Direction toward the sun (the sun is infinitely far away). */
    public static final Vector3f SUN_DIR = new Vector3f(0.55f, 0.30f, -0.78f).normalize();

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 22));
    private static final float IMPOSTOR = 60f;

    /**
     * @param viewPos     camera position in space-dimension coordinates
     * @param toLocal     rotation from space frame to the render frame (identity in space)
     * @param starAlpha   0..1 visibility of stars (daylight on a planet hides them)
     * @param bodyAlpha   0..1 visibility of other bodies
     * @param exclude     body not to draw (the one you stand on)
     */
    public static void render(Matrix4f m, Vec3d viewPos, Quaternionf toLocal, float time, float pt, float starAlpha, float bodyAlpha,
                              Body exclude, float sunScale) {
        render(m, viewPos, toLocal, time, pt, starAlpha, bodyAlpha, exclude, sunScale, 0);
    }

    /**
     * Instead of clearing the depth buffer between layers (which would break Immersive Portals'
     * portal rendering, where this sky can be drawn inside another world's frame), every layer is
     * squeezed into its own thin slice at the far end of the depth range: nearer layers get
     * nearer slices, so they always cover farther ones, and the world drawn afterwards (at
     * normal depths) covers all of it.
     */
    public static void slot(int i) {
        GL11.glDepthRange(1.0 - (i + 1) * 2e-7, 1.0 - i * 2e-7);
    }

    public static void resetDepth() {
        GL11.glDepthRange(0.0, 1.0);
    }

    public static void render(Matrix4f m, Vec3d viewPos, Quaternionf toLocal, float time, float pt, float starAlpha, float bodyAlpha,
                              Body exclude, float sunScale, int slotBase) {
        int slot = slotBase;
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        // ---- stars + Milky Way
        if (starAlpha > 0.01f) {
            VertexConsumer vc = BUF.getBuffer(starAlpha >= 0.999f ? BeamRenderLayers.solid(STARS) : BeamRenderLayers.textured(STARS));
            Quaternionf sky = new Quaternionf(toLocal).mul(new Quaternionf().rotationX(0.42f).rotateZ(0.2f));
            BodyDraw.sphere(vc, m, new Vector3f(), 50f, sky, 64, 128, (n, out) -> {
                out[0] = starAlpha; out[1] = starAlpha; out[2] = starAlpha; out[3] = 1f;
            });
            slot(slot);
            BUF.draw();
        }
        slot++;

        // ---- the sun
        Vector3f sun = toLocal.transform(new Vector3f(SUN_DIR));
        drawSun(m, sun, sunScale, starAlpha);
        slot(slot++);
        BUF.draw();

        // ---- bodies, far to near
        Body[] order = Arrays.copyOf(Body.ALL, Body.ALL.length);
        Arrays.sort(order, Comparator.comparingDouble((Body b) -> -b.center.squaredDistanceTo(viewPos)));
        for (Body b : order) {
            if (b == exclude) continue;
            Vec3d rel = b.center.subtract(viewPos);
            double dist = rel.length();
            if (dist < b.radius * 1.0005) continue;
            float k = (float) (IMPOSTOR / dist);
            Vector3f c = toLocal.transform(new Vector3f((float) rel.x * k, (float) rel.y * k, (float) rel.z * k));
            float r = (float) b.radius * k;
            float ang = r / IMPOSTOR;
            float e = ClientPlanets.elapsed(b, pt);
            float extent = e >= Blast.DETONATE ? 30f : 1f;
            if (ang * extent < 0.0006f) {
                // too small to resolve: a point of light
                starPoint(m, c, r, b, sun, bodyAlpha);
                slot(slot++);
                BUF.draw();
                continue;
            }
            int seg = ang > 0.25f ? 384 : ang > 0.04f ? 192 : 96;
            Vector3f impact = toLocal.transform(PlanetBlastFx.impactDir(b));
            BodyDraw.draw(BUF, m, b, c, r, sun, time, pt, bodyAlpha, seg, impact);
            slot(slot++);
            BUF.draw();
        }

        // ---- ARES-0's beam crossing space, from Earth to its target
        for (Body b : Body.ALL) {
            float e = ClientPlanets.elapsed(b, pt);
            if (e < 0 || e > Blast.DETONATE + 20) continue;
            Vec3d dir = b.center.subtract(Body.EARTH.center).normalize();
            Vec3d from = Body.EARTH.center.add(dir.multiply(Body.EARTH.radius + 40));
            Vec3d to = b.center.subtract(dir.multiply(b.radius * 0.98));
            beam(m, project(from, viewPos, toLocal), project(to, viewPos, toLocal), e, bodyAlpha);
        }
        slot(slot);
        BUF.draw();
        resetDepth();

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    private static Vector3f project(Vec3d p, Vec3d view, Quaternionf toLocal) {
        Vec3d rel = p.subtract(view);
        double d = Math.max(1e-3, rel.length());
        float k = (float) (IMPOSTOR * 0.9 / d);
        return toLocal.transform(new Vector3f((float) rel.x * k, (float) rel.y * k, (float) rel.z * k));
    }

    private static void beam(Matrix4f m, Vector3f a, Vector3f b, float e, float alpha) {
        float f = e < 10 ? e / 10f : e > Blast.DETONATE ? Math.max(0f, 1f - (e - Blast.DETONATE) / 20f) : 1f;
        BeamRenderer.Beam bm = new BeamRenderer.Beam(a, b);
        float w = 0.15f;
        BeamRenderer.glowTube(BeamRenderer.glow(BUF), m, bm, w * 4f, 1f, 0.3f, 0.05f, 0.25f * f * alpha, e);
        BeamRenderer.glowTube(BeamRenderer.glow(BUF), m, bm, w * 1.6f, 1f, 0.55f, 0.15f, 0.5f * f * alpha, e);
        BeamRenderer.glowTube(BeamRenderer.glow(BUF), m, bm, w * 0.6f, 1f, 0.93f, 0.8f, 0.95f * f * alpha, e);
    }

    private static void drawSun(Matrix4f m, Vector3f dir, float scale, float clarity) {
        Vector3f c = new Vector3f(dir).mul(55f);
        float core = 0.34f * scale;
        // (always re-fetch the consumer: switching layers on the shared buffer invalidates the old one)
        PlanetBlastFx.billboard(BUF.getBuffer(BeamRenderLayers.textured(SUN_GLARE)), m, c, 12f * scale * (0.5f + 0.5f * clarity), 0f, 1f, 0.97f, 0.92f, 1f);
        PlanetBlastFx.glowBall(BeamRenderer.glow(BUF), m, c, core * 2.4f, 1f, 0.95f, 0.85f, 0.35f);
        BeamRenderer.texSphere(BeamRenderer.tex(BUF, SUN), m, c, core, 1f, 0.98f, 0.94f, 1f, 0f, 0f);
        PlanetBlastFx.glowBall(BeamRenderer.glow(BUF), m, c, core * 1.08f, 1f, 1f, 1f, 1f);
    }

    private static void starPoint(Matrix4f m, Vector3f c, float r, Body b, Vector3f sun, float alpha) {
        Vector3f p = new Vector3f(c).normalize().mul(IMPOSTOR * 0.98f);
        float s = Math.max(0.12f, r * 1.6f);
        float[] col = switch (b) {
            case MARS -> new float[]{1f, 0.62f, 0.45f};
            case VENUS -> new float[]{1f, 0.97f, 0.85f};
            case JUPITER, SATURN -> new float[]{1f, 0.92f, 0.78f};
            default -> new float[]{0.92f, 0.95f, 1f};
        };
        float e = ClientPlanets.elapsed(b, 0f);
        if (e >= Blast.DETONATE) {
            col = new float[]{1f, 0.5f, 0.3f};
            s *= 2.5f;
            alpha *= Math.max(0.2f, 1f - (e - Blast.DETONATE) / Blast.DEBRIS_END);
        }
        PlanetBlastFx.glowBall(BeamRenderer.glow(BUF), m, p, s, col[0], col[1], col[2], alpha);
    }

    static float clamp01(float f) {
        return MathHelper.clamp(f, 0f, 1f);
    }
}
