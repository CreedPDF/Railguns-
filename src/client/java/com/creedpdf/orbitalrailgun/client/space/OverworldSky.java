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
import net.minecraft.client.render.VertexConsumer;
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
import org.joml.Quaternionf;
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
            Vec3d near = ares.getMuzzlePos(pt).add(dir.multiply(220)).subtract(cam);
            Vector3f a = new Vector3f((float) near.x, (float) near.y, (float) near.z).normalize().mul(DIST * 0.97f);
            Vector3f b = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z).mul(DIST * 0.97f);
            BeamRenderer.Beam bm = new BeamRenderer.Beam(a, b);
            float flick = 0.85f + 0.15f * MathHelper.sin(time * 4.1f);
            // the column of fire climbing out through the atmosphere, all the way to its target
            BeamRenderer.glowTube(BeamRenderer.glow(BUF), world, bm, 3.2f, 1f, 0.3f, 0.05f, 0.18f * flick, time);
            BeamRenderer.glowTube(BeamRenderer.glow(BUF), world, bm, 1.5f, 1f, 0.5f, 0.12f, 0.45f * flick, time);
            BeamRenderer.glowTube(BeamRenderer.glow(BUF), world, bm, 0.5f, 1f, 0.92f, 0.8f, 0.95f * flick, time);
            PlanetBlastFx.glowBall(BeamRenderer.glow(BUF), world, b, 2.2f, 1f, 0.6f, 0.2f, 0.7f * flick);
        }
        meteorShower(world, time, pt);
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
            Quaternionf sr = new Quaternionf().rotateY((float) Math.toRadians(-90)).rotateX((float) Math.toRadians(w.getSkyAngle(pt) * 360f));
            if (b == Body.JUPITER) {
                // the Gungnir Railway Station: the ring round Jupiter, far half first
                com.creedpdf.orbitalrailgun.client.grs.GrsSpace.overworld(BUF, world, sr, c, r, sun, time, pt, alpha, false);
                BUF.draw();
            }
            BodyDraw.draw(BUF, m, b, c, r, sun, time, pt, Math.min(alpha, 0.998f), com.creedpdf.orbitalrailgun.client.config.Quality.segs(96, 32), impact);
            BUF.draw();
            if (b == Body.JUPITER) {
                com.creedpdf.orbitalrailgun.client.grs.GrsSpace.overworld(BUF, world, sr, c, r, sun, time, pt, alpha, true);
                BUF.draw();
            }
        }
        matrices.pop();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    /**
     * After a planet dies its debris streaks across the whole sky for a while: dozens of burning
     * fragments at a time, a few big fireballs with glowing heads among them.
     */
    private static void meteorShower(Matrix4f m, float time, float pt) {
        float intensity = 0f;
        for (Body b : Body.ALL) {
            float e = ClientPlanets.elapsed(b, pt);
            if (e < 0) continue;
            float since = e - Blast.DETONATE - com.creedpdf.orbitalrailgun.space.MeteorShower.ARRIVE + 120;
            float dur = com.creedpdf.orbitalrailgun.space.MeteorShower.DURATION + 1200;
            if (since < 0 || since > dur) continue;
            intensity = Math.max(intensity, Math.min(1f, since / 200f) * (1f - since / dur) * (b.gasGiant() ? 1f : 0.8f));
        }
        if (intensity <= 0) return;
        int slots = com.creedpdf.orbitalrailgun.client.config.Quality.scale((int) (90 * intensity) + 6, 6);
        net.minecraft.util.math.random.Random rand = net.minecraft.util.math.random.Random.create();
        float[] col = new float[3];
        for (int i = 0; i < slots; i++) {
            rand.setSeed(i * 0x9E3779B97F4A7C15L + 77);
            float period = 30f + rand.nextFloat() * 90f;
            float phase = rand.nextFloat() * period;
            int cycle = (int) Math.floor((time + phase) / period);
            float u = ((time + phase) % period) / period;
            rand.setSeed(i * 0x9E3779B97F4A7C15L + cycle * 0xC2B2AE3D27D4EB4FL);
            float dur = 0.25f + rand.nextFloat() * 0.45f;
            if (u > dur) continue;
            float k = u / dur;
            float az = rand.nextFloat() * MathHelper.TAU;
            float el = 0.35f + rand.nextFloat() * 1.0f;
            Vector3f start = new Vector3f(MathHelper.cos(az) * MathHelper.cos(el), MathHelper.sin(el), MathHelper.sin(az) * MathHelper.cos(el));
            float travelAz = az + (rand.nextFloat() - 0.5f) * 1.2f;
            Vector3f travel = new Vector3f(MathHelper.cos(travelAz) * 0.5f, -1f, MathHelper.sin(travelAz) * 0.5f).normalize();
            travel.sub(new Vector3f(start).mul(travel.dot(start))).normalize();
            float sweep = 0.35f + rand.nextFloat() * 0.5f;
            boolean big = rand.nextFloat() < 0.12f;
            Vector3f head = new Vector3f(start).add(new Vector3f(travel).mul(sweep * k)).normalize();
            if (head.y < -0.02f) continue;
            float tl = (big ? 0.22f : 0.12f) * Math.min(1f, k * 4f);
            Vector3f tailDir = new Vector3f(start).add(new Vector3f(travel).mul(sweep * k - tl)).normalize();
            Vector3f h = new Vector3f(head).mul(DIST * 0.9f), t = new Vector3f(tailDir).mul(DIST * 0.9f);
            Vector3f d = new Vector3f(h).sub(t);
            float len = d.length();
            if (len < 1e-3f) continue;
            d.div(len);
            float a = MathHelper.sin(k * MathHelper.PI) * intensity * (big ? 1f : 0.75f);
            PlanetBlastFx.blackbodyPublic(big ? 0.85f : 0.62f + 0.2f * rand.nextFloat(), col);
            VertexConsumer vc = BUF.getBuffer(com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers.textured(PlanetBlastFx.STREAK));
            PlanetBlastFx.streak(vc, m, t, d, len, big ? 0.55f : 0.22f, col[0], col[1], col[2], a);
            if (big) {
                PlanetBlastFx.streak(vc, m, new Vector3f(t).add(new Vector3f(d).mul(len * 0.5f)), d, len * 0.5f, 0.2f, 1f, 0.95f, 0.85f, a);
                PlanetBlastFx.glowBall(BeamRenderer.glow(BUF), m, h, 0.7f, 1f, 0.7f, 0.35f, a * 0.8f);
            }
        }
    }

    /** Body direction in vanilla's sky frame (sun at +Y). */
    private static Vector3f localDir(Body b) {
        if (b == Body.MOON) return new Vector3f(0, -1, 0);
        double th = Math.toRadians(b.skyTheta), tilt = Math.toRadians(b.skyTilt);
        return new Vector3f((float) Math.sin(tilt), (float) (Math.cos(tilt) * Math.cos(th)), (float) (Math.cos(tilt) * Math.sin(th))).normalize();
    }
}
