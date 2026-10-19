package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.sound.RiftAmbientSoundInstance;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.rift.RiftDimension;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.DimensionEffects;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * Client side of the dimension beyond the rift: a full nebula skybox with a blazing star and huge,
 * lit planets hanging overhead, dark violet fog, drifting cosmic dust, the deep ambient hum, the
 * column of light over the return pad and the violet flash when you cross over.
 */
public final class RiftDimensionClient {
    private RiftDimensionClient() {}

    public static final Identifier SKY = RailgunMod.id("textures/effect/rift_sky.png");
    public static final Identifier SUN = RailgunMod.id("textures/effect/star_surface.png");
    public static final Identifier RINGS = RailgunMod.id("textures/effect/planet_rings.png");

    private static final VertexConsumerProvider.Immediate SKY_BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 21));
    private static final VertexConsumerProvider.Immediate PAD_BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 20));

    private static long flashStart = -1000;
    private static RiftAmbientSoundInstance ambient;

    public static void register() {
        DimensionRenderingRegistry.registerDimensionEffects(RailgunMod.id("rift"), new Effects());
        DimensionRenderingRegistry.registerSkyRenderer(RiftDimension.KEY, RiftDimensionClient::sky);
        DimensionRenderingRegistry.registerCloudRenderer(RiftDimension.KEY, ctx -> {});
        DimensionRenderingRegistry.registerWeatherRenderer(RiftDimension.KEY, ctx -> {});
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RiftDimensionClient::padBeam);
        ClientTickEvents.END_CLIENT_TICK.register(RiftDimensionClient::tick);
        HudRenderCallback.EVENT.register(RiftDimensionClient::hud);
        ClientPlayNetworking.registerGlobalReceiver(RiftDimension.ENTER_PACKET, (client, handler, buf, sender) ->
                client.execute(() -> flashStart = System.currentTimeMillis()));
    }

    /** No clouds, no vanilla sky, dark violet fog. */
    private static final class Effects extends DimensionEffects {
        Effects() {
            super(Float.NaN, false, SkyType.NONE, false, false);
        }

        @Override
        public Vec3d adjustFogColor(Vec3d color, float sunHeight) {
            return new Vec3d(0.06, 0.035, 0.11);
        }

        @Override
        public boolean useThickFog(int camX, int camY) {
            return false;
        }

        @Override
        public float[] getFogColorOverride(float skyAngle, float tickDelta) {
            return null;
        }
    }

    // ------------------------------------------------------------------ sky

    private static void sky(WorldRenderContext ctx) {
        ClientWorld world = ctx.world();
        if (world == null) return;
        float time = world.getTime() + ctx.tickDelta();
        // drawn at half scale so even tiny render distances never clip the far plane
        Matrix4f m = new Matrix4f(ctx.matrixStack().peek().getPositionMatrix()).scale(0.5f);
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        // nebula skybox, turning very slowly
        Quaternionf drift = new Quaternionf().rotationY(time * 0.00004f).rotateX(0.15f);
        VertexConsumer box = SKY_BUF.getBuffer(BeamRenderLayers.solid(SKY));
        int lat = 32, lon = 64;
        float R = 196f;
        for (int i = 0; i < lat; i++) {
            float v0 = i / (float) lat, v1 = (i + 1) / (float) lat;
            for (int j = 0; j < lon; j++) {
                float u0 = j / (float) lon, u1 = (j + 1) / (float) lon;
                skyVert(box, m, drift, R, u0, v0);
                skyVert(box, m, drift, R, u1, v0);
                skyVert(box, m, drift, R, u1, v1);
                skyVert(box, m, drift, R, u0, v1);
            }
        }
        SKY_BUF.draw();

        // the star
        Vector3f star = new Vector3f(-0.55f, 0.45f, -0.7f).normalize().mul(150f);
        VertexConsumer g = BeamRenderer.glow(SKY_BUF);
        BeamRenderer.glowSphere(g, m, star, 40f, 0.5f, 0.4f, 0.9f, 0.10f);
        BeamRenderer.glowSphere(g, m, star, 18f, 0.8f, 0.7f, 1f, 0.3f);
        BeamRenderer.texSphere(BeamRenderer.tex(SKY_BUF, SUN), m, star, 7f, 1f, 0.92f, 0.85f, 1f, time * 0.0006f, time * 0.0003f);
        BeamRenderer.glowSphere(g, m, star, 6.4f, 1f, 1f, 0.95f, 1f);
        SKY_BUF.draw();

        // huge lit planets
        Vector3f gas = new Vector3f(0.6f, 0.38f, 0.45f).normalize().mul(110f);
        PlanetDraw.planet(SKY_BUF, m, RiftFx.P_GAS, gas, 34f, new Quaternionf().rotationZ(0.3f).rotateY(time * 0.0004f),
                new Vector3f(star).sub(gas), 0.04f, new float[]{1f, 0.78f, 0.5f}, 0.9f);
        PlanetDraw.rings(SKY_BUF, m, RINGS, gas, new Vector3f(0.35f, 1f, -0.2f), 44f, 74f, new Vector3f(star).sub(gas), 34f);
        Vector3f ocean = new Vector3f(-0.3f, 0.12f, 0.9f).normalize().mul(95f);
        PlanetDraw.planet(SKY_BUF, m, RiftFx.P_OCEAN, ocean, 15f, new Quaternionf().rotationZ(-0.4f).rotateY(time * 0.0008f),
                new Vector3f(star).sub(ocean), 0.04f, new float[]{0.4f, 0.65f, 1f}, 1.3f);
        Vector3f moon = new Vector3f(ocean).add(-14f, 9f, -18f);
        PlanetDraw.planet(SKY_BUF, m, RiftFx.P_ICE, moon, 3.2f, new Quaternionf().rotationY(time * 0.001f),
                new Vector3f(star).sub(moon), 0.03f, null, 0f);
        Vector3f lava = new Vector3f(0.85f, -0.05f, -0.5f).normalize().mul(120f);
        PlanetDraw.planet(SKY_BUF, m, RiftFx.P_LAVA, lava, 9f, new Quaternionf().rotationX(0.2f).rotateY(time * 0.0012f),
                new Vector3f(star).sub(lava), 0.2f, new float[]{1f, 0.45f, 0.2f}, 0.8f);
        Vector3f desert = new Vector3f(-0.9f, -0.25f, 0.15f).normalize().mul(130f);
        PlanetDraw.planet(SKY_BUF, m, RailgunMod.id("textures/effect/planet_desert.png"), desert, 6f, new Quaternionf().rotationY(time * 0.001f),
                new Vector3f(star).sub(desert), 0.04f, new float[]{1f, 0.75f, 0.5f}, 0.6f);
        SKY_BUF.draw();

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        // everything above is infinitely far away
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
    }

    private static void skyVert(VertexConsumer vc, Matrix4f m, Quaternionf drift, float R, float u, float v) {
        float la = MathHelper.PI * (0.5f - v), lo = MathHelper.TAU * u;
        Vector3f p = new Vector3f(MathHelper.cos(la) * MathHelper.cos(lo), MathHelper.sin(la), MathHelper.cos(la) * MathHelper.sin(lo));
        drift.transform(p).mul(R);
        BeamRenderer.texVertRaw(vc, m, p, 1f, 1f, 1f, 1f, u, v);
    }

    // ------------------------------------------------------------------ return pad

    private static void padBeam(WorldRenderContext ctx) {
        ClientWorld world = ctx.world();
        if (world == null || world.getRegistryKey() != RiftDimension.KEY) return;
        float time = world.getTime() + ctx.tickDelta();
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        Vector3f base = new Vector3f((float) (0.5 - cam.x), (float) (RiftDimension.PAD_Y + 1 - cam.y), (float) (0.5 - cam.z));
        BeamRenderer.Beam b = new BeamRenderer.Beam(base, new Vector3f(base).add(0, 90f, 0));
        float flick = 0.85f + 0.15f * MathHelper.sin(time * 0.4f);
        BeamRenderer.texTube(BeamRenderer.tex(PAD_BUF, BeamRenderLayers.TINT_CORE), m, b, 0.35f, 1f, 0.95f, 1f, 0.8f * flick, 6f, -time * 0.05f, 0f, 0f, time);
        BeamRenderer.texTube(BeamRenderer.tex(PAD_BUF, BeamRenderLayers.TINT_PLASMA), m, b, 0.9f, 0.7f, 0.45f, 1f, 0.5f, 10f, -time * 0.03f, 0f, 0.02f, time);
        BeamRenderer.glowTube(BeamRenderer.glow(PAD_BUF), m, b, 2.2f, 0.55f, 0.25f, 1f, 0.12f, time);
        BeamRenderer.helix(BeamRenderer.tex(PAD_BUF, BeamRenderLayers.TINT_HELIX), m, b, 1.3f, 0.3f, 0.3f, time * 0.05f, 0.7f, 0.8f, 1f, 0.5f, time);
        for (int k = 0; k < 3; k++) {
            float f = ((time * 0.02f + k / 3f) % 1f);
            float w = 0.6f + f * 2.4f;
            BeamRenderer.texRing(BeamRenderer.tex(PAD_BUF, BeamRenderLayers.TINT_SHOCKWAVE), m, new Vector3f(base).add(0, 0.05f + f * 3f, 0),
                    new Vector3f(0, 1, 0), w, w + 0.35f, 0.75f, 0.55f, 1f, 0.7f * (1 - f), 0f);
        }
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        PAD_BUF.draw();
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    // ------------------------------------------------------------------ ambience

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        ClientWorld world = client.world;
        if (p == null || world == null || world.getRegistryKey() != RiftDimension.KEY) {
            ambient = null;
            return;
        }
        if (ambient == null || ambient.isDone() || !client.getSoundManager().isPlaying(ambient)) {
            if (ambient == null || ambient.isDone()) {
                ambient = new RiftAmbientSoundInstance();
                client.getSoundManager().play(ambient);
            }
        }
        Random r = world.random;
        for (int i = 0; i < 6; i++) {
            double x = p.getX() + (r.nextDouble() - 0.5) * 40, y = p.getY() + (r.nextDouble() - 0.4) * 24, z = p.getZ() + (r.nextDouble() - 0.5) * 40;
            world.addParticle(ModParticles.RIFT_DUST, x, y, z, (r.nextDouble() - 0.5) * 0.02, 0.01 + r.nextDouble() * 0.02, (r.nextDouble() - 0.5) * 0.02);
        }
        if (r.nextInt(3) == 0) {
            world.addParticle(ModParticles.RIFT_WISP, p.getX() + (r.nextDouble() - 0.5) * 30, p.getY() + (r.nextDouble() - 0.3) * 16,
                    p.getZ() + (r.nextDouble() - 0.5) * 30, 0, 0.02, 0);
        }
        // motes rising up the return beam
        if (p.squaredDistanceTo(0.5, RiftDimension.PAD_Y + 1, 0.5) < 64 * 64) {
            for (int i = 0; i < 3; i++) {
                double a = r.nextDouble() * Math.PI * 2, rad = 0.3 + r.nextDouble() * 1.2;
                world.addParticle(ModParticles.RIFT_SPARK, 0.5 + Math.cos(a) * rad, RiftDimension.PAD_Y + 1.1, 0.5 + Math.sin(a) * rad,
                        0, 0.15 + r.nextDouble() * 0.2, 0);
            }
        }
    }

    private static void hud(DrawContext dc, float tickDelta) {
        long dt = System.currentTimeMillis() - flashStart;
        if (dt < 0 || dt > 2200) return;
        float f = dt / 2200f;
        float a = f < 0.08f ? f / 0.08f : (1f - f) / 0.92f;
        a = a * a;
        int w = dc.getScaledWindowWidth(), h = dc.getScaledWindowHeight();
        int white = ((int) (a * 255 * Math.max(0, 1f - f * 3f)) << 24) | 0xF4ECFF;
        int violet = ((int) (a * 235) << 24) | 0x5A1AA8;
        dc.fill(0, 0, w, h, violet);
        dc.fill(0, 0, w, h, white);
    }
}
