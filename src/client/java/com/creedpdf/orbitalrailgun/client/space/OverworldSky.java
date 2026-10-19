package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderer;
import com.creedpdf.orbitalrailgun.entity.Ares0Entity;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.DimensionEffects;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Planets in the overworld's night sky (Mars, Venus, Jupiter with Europa, Saturn and its rings),
 * lit by the sun so they show phases, and - when ARES-0 fires - the beam climbing into the sky
 * and the planet cracking open and exploding up there. A destroyed Moon is gone from the sky.
 */
public final class OverworldSky {
    private OverworldSky() {}

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 21));
    private static final Identifier MOON_PHASES = new Identifier("textures/environment/moon_phases.png");
    private static final Identifier NO_MOON = RailgunMod.id("textures/space/moon_gone.png");
    private static final float DIST = 90f;

    public static Identifier moonTexture(Identifier id) {
        return MOON_PHASES.equals(id) && ClientPlanets.gone(Body.MOON) ? NO_MOON : id;
    }

    public static void render(MatrixStack matrices, float pt, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld w = mc.world;
        if (w == null || w.getRegistryKey() != World.OVERWORLD) return;
        if (w.getDimensionEffects().getSkyType() != DimensionEffects.SkyType.NORMAL) return;
        float time = w.getTime() + pt;
        float night = MathHelper.clamp(w.method_23787(pt) * 2.2f, 0f, 1f) * (1f - w.getRainGradient(pt));

        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        // ARES-0 beams, in world orientation
        Matrix4f world = new Matrix4f(matrices.peek().getPositionMatrix());
        Vec3d cam = camera.getPos();
        for (Entity e : w.getEntities()) {
            if (!(e instanceof Ares0Entity ares) || !ares.isBeamActive()) continue;
            Vec3d dir = ares.targetDirection(pt);
            if (dir == null) continue;
            Vec3d near = ares.getMuzzlePos(pt).add(dir.multiply(260)).subtract(cam);
            Vector3f a = new Vector3f((float) near.x, (float) near.y, (float) near.z).normalize().mul(DIST * 0.97f);
            Vector3f b = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z).mul(DIST * 0.97f);
            BeamRenderer.Beam bm = new BeamRenderer.Beam(a, b);
            float flick = 0.85f + 0.15f * MathHelper.sin(time * 4.1f);
            BeamRenderer.glowTube(BeamRenderer.glow(BUF), world, bm, 1.6f, 0.35f, 0.5f, 1f, 0.3f * flick, time);
            BeamRenderer.glowTube(BeamRenderer.glow(BUF), world, bm, 0.45f, 0.9f, 0.95f, 1f, 0.95f * flick, time);
        }
        BUF.draw();

        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-90f));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(w.getSkyAngle(pt) * 360f));
        Matrix4f m = matrices.peek().getPositionMatrix();
        Vector3f sun = new Vector3f(0, 1, 0);
        for (Body b : Body.ALL) {
            if (b == Body.EARTH) continue;
            float e = ClientPlanets.elapsed(b, pt);
            Vector3f dir = localDir(b);
            float size = b == Body.MOON ? 6.0f : b.skySize;
            float r = DIST * (float) Math.tan(Math.toRadians(size));
            Vector3f c = new Vector3f(dir).mul(DIST);
            float alpha;
            if (b == Body.MOON) {
                if (e < 0) continue; // the vanilla moon
                alpha = 1f;
            } else {
                alpha = e >= 0 ? Math.max(0.85f, night) : night;
            }
            if (alpha < 0.02f) continue;
            if (e >= Blast.DETONATE + Blast.DEBRIS_END && b != Body.MOON) alpha *= 0.7f;
            Vector3f impact = new Vector3f(c).negate().normalize();
            BodyDraw.draw(BUF, m, b, c, r, sun, time, pt, Math.min(alpha, 0.998f), 48, impact);
            BUF.draw();
        }
        matrices.pop();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    /** Body direction in vanilla's sky frame (sun at +Y). */
    private static Vector3f localDir(Body b) {
        if (b == Body.MOON) return new Vector3f(0, -1, 0);
        double th = Math.toRadians(b.skyTheta), tilt = Math.toRadians(b.skyTilt);
        return new Vector3f((float) Math.sin(tilt), (float) (Math.cos(tilt) * Math.cos(th)), (float) (Math.cos(tilt) * Math.sin(th))).normalize();
    }
}
