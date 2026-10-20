package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderer;
import com.creedpdf.orbitalrailgun.client.render.RiftFx;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.PlanetState;
import com.creedpdf.orbitalrailgun.space.RiftPortal;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import com.creedpdf.orbitalrailgun.space.SpaceServer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.DimensionEffects;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Client side of space and the planets: sky renderers, dimension effects, the space-side rim
 * of open rifts, low gravity, ambience and the sync packets.
 */
public final class SpaceClient {
    private SpaceClient() {}

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 20));
    private static AmbientSound ambient;

    public static void register() {
        DimensionRenderingRegistry.registerDimensionEffects(RailgunMod.id("space"), new Effects(0f, 0f, 0f, false));
        DimensionRenderingRegistry.registerDimensionEffects(RailgunMod.id("moon"), new Effects(0f, 0f, 0f, false));
        DimensionRenderingRegistry.registerDimensionEffects(RailgunMod.id("europa"), new Effects(0.01f, 0.01f, 0.015f, false));
        DimensionRenderingRegistry.registerDimensionEffects(RailgunMod.id("mars"), new Effects(0.78f, 0.56f, 0.4f, false));
        DimensionRenderingRegistry.registerDimensionEffects(RailgunMod.id("venus"), new Effects(0.8f, 0.6f, 0.3f, true));

        DimensionRenderingRegistry.registerSkyRenderer(SpaceDims.SPACE, ctx -> {
            ClientWorld w = ctx.world();
            if (w == null) return;
            float pt = ctx.tickDelta();
            Matrix4f m = new Matrix4f(ctx.matrixStack().peek().getPositionMatrix());
            SpaceSky.render(m, ctx.camera().getPos(), new Quaternionf(), w.getTime() + pt, pt, 1f, 1f, null, 1f);
        });
        for (RegistryKey<World> key : new RegistryKey[]{SpaceDims.MOON, SpaceDims.MARS, SpaceDims.VENUS, SpaceDims.EUROPA}) {
            DimensionRenderingRegistry.registerSkyRenderer(key, PlanetSky::render);
            DimensionRenderingRegistry.registerCloudRenderer(key, ctx -> {});
            DimensionRenderingRegistry.registerWeatherRenderer(key, ctx -> {});
        }
        DimensionRenderingRegistry.registerCloudRenderer(SpaceDims.SPACE, ctx -> {});
        DimensionRenderingRegistry.registerWeatherRenderer(SpaceDims.SPACE, ctx -> {});

        WorldRenderEvents.AFTER_TRANSLUCENT.register(SpaceClient::riftRims);
        ClientTickEvents.END_CLIENT_TICK.register(SpaceClient::tick);

        ClientPlayNetworking.registerGlobalReceiver(PlanetState.SYNC, (client, handler, buf, sender) -> {
            int n = buf.readVarInt();
            long[] v = new long[n];
            for (int i = 0; i < n; i++) v[i] = buf.readLong();
            client.execute(() -> ClientPlanets.set(v));
        });
        ClientPlayNetworking.registerGlobalReceiver(SpaceServer.REENTRY, (client, handler, buf, sender) -> {
            int body = buf.readVarInt();
            boolean down = buf.readBoolean();
            client.execute(() -> ReentryFx.start(Body.ALL[MathHelper.clamp(body, 0, Body.ALL.length - 1)], down));
        });
    }

    /** No clouds, no vanilla sky; fog in the planet's colour. */
    private static final class Effects extends DimensionEffects {
        private final Vec3d fog;
        private final boolean thick;

        Effects(float r, float g, float b, boolean thick) {
            super(Float.NaN, false, SkyType.NONE, false, false);
            this.fog = new Vec3d(r, g, b);
            this.thick = thick;
        }

        @Override
        public Vec3d adjustFogColor(Vec3d color, float sunHeight) {
            return fog;
        }

        @Override
        public boolean useThickFog(int camX, int camY) {
            return thick;
        }

        @Override
        public float[] getFogColorOverride(float skyAngle, float tickDelta) {
            return null;
        }
    }

    // ---- the space side of an open rift -----------------------------------------------------------

    private static void riftRims(WorldRenderContext ctx) {
        ClientWorld world = ctx.world();
        if (world == null || world.getRegistryKey() != SpaceDims.SPACE) return;
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        float time = world.getTime() + ctx.tickDelta();
        boolean any = false;
        for (Entity e : world.getEntities()) {
            if (!(e instanceof RiftPortal portal)) continue;
            any = true;
            Vec3d o = portal.getOriginPos();
            Vector3f c = new Vector3f((float) (o.x - cam.x), (float) (o.y - cam.y), (float) (o.z - cam.z));
            float R = (float) portal.width / 2f;
            RiftFx.rimAt(BUF, m, c, R, time, 0f, e.getId());
            // the railgun's beam punches on through, fading into space
            BeamRenderer.Beam b = new BeamRenderer.Beam(new Vector3f(c).add(0, -1f, 0), new Vector3f(c).add(0, 60f, 0));
            BeamRenderer.glowTube(BeamRenderer.glow(BUF), m, b, 3.2f, 0.5f, 0.2f, 0.95f, 0.12f, time);
            BeamRenderer.texTube(BeamRenderer.tex(BUF, BeamRenderLayers.TINT_PLASMA), m, b, 1.4f, 0.7f, 0.45f, 1f, 0.45f, 10f, time * 2f, 0f, 0.05f, time);
            BeamRenderer.texTube(BeamRenderer.tex(BUF, BeamRenderLayers.TINT_CORE), m, b, 0.6f, 1f, 0.95f, 1f, 0.6f, 5f, time * 3f, 0f, 0.02f, time);
        }
        if (!any) return;
        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        BUF.draw();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    // ---- gravity, ambience, doomed-planet quakes -------------------------------------------------

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        ClientWorld w = client.world;
        if (p == null || w == null) {
            ambient = null;
            return;
        }
        Jetpack.tick(client, p, w);
        ReentryFx.tick(client, p);
        blastSounds(p, w);

        boolean airless = SpaceDims.isAirless(w);
        float g = SpaceDims.gravity(w);
        // planets: weaker pull (vanilla applies 0.08/tick; we give part of it back)
        if (!SpaceDims.isSpace(w) && g < 0.999f && !p.hasNoGravity() && !p.isOnGround() && !p.isTouchingWater()
                && !p.getAbilities().flying && !p.isFallFlying()) {
            p.setVelocity(p.getVelocity().add(0, 0.08 * (1.0 - g), 0));
        }

        if (airless) {
            if (ambient == null || ambient.isDone()) {
                ambient = new AmbientSound();
                client.getSoundManager().play(ambient);
            }
            Random r = w.random;
            if (SpaceDims.isSpace(w) && r.nextInt(3) == 0) {
                // the odd glint of dust drifting past the visor
                Vec3d e = p.getEyePos().add((r.nextDouble() - 0.5) * 20, (r.nextDouble() - 0.5) * 14, (r.nextDouble() - 0.5) * 20);
                w.addParticle(ModParticles.RIFT_DUST, e.x, e.y, e.z, 0, 0, 0);
            }
        } else {
            ambient = null;
        }

        Body planet = SpaceDims.planetOf(w);
        if (planet != null) {
            float e = ClientPlanets.elapsed(planet, 0f);
            if (e >= 0 && e < Blast.EJECT) {
                RailgunHud.shake(0.15f + 0.9f * e / Blast.EJECT);
                if ((int) e % 40 == 0) p.playSound(ModSounds.PLANET_CRACK, 1.5f, 0.6f + 0.4f * e / Blast.EJECT);
            }
        }
    }

    private static final float[] LAST = new float[Body.ALL.length];

    static {
        java.util.Arrays.fill(LAST, -1f);
    }

    /** Crack, collapse and detonation of a planet ARES-0 hit, if you can see it. */
    private static void blastSounds(ClientPlayerEntity p, ClientWorld w) {
        for (Body b : Body.ALL) {
            float e = ClientPlanets.elapsed(b, 0f);
            float last = LAST[b.ordinal()];
            LAST[b.ordinal()] = e;
            if (e < 0 || last < 0 && e > 40) continue;
            boolean visible = w.getRegistryKey() == World.OVERWORLD ? com.creedpdf.orbitalrailgun.entity.Ares0Entity.visible(w, b)
                    : SpaceDims.isAirless(w);
            if (!visible) continue;
            if (last < 6 && e >= 6) p.playSound(ModSounds.PLANET_CRACK, 2f, 0.8f);
            if (last < Blast.CRACK_END - 30 && e >= Blast.CRACK_END - 30) p.playSound(ModSounds.PLANET_COLLAPSE, 2f, 0.9f);
            if (last < Blast.DETONATE && e >= Blast.DETONATE) {
                p.playSound(ModSounds.PLANET_EXPLODE, 3f, 0.85f);
                RailgunHud.shake(SpaceDims.isAirless(w) ? 2.4f : 1.2f);
            }
        }
    }

    /** Low hum of life support / the silence of vacuum. */
    private static final class AmbientSound extends MovingSoundInstance {
        private int age;

        AmbientSound() {
            super(ModSounds.SPACE_AMBIENT, SoundCategory.AMBIENT, SoundInstance.createRandom());
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = 0.01f;
            this.relative = true;
            this.attenuationType = SoundInstance.AttenuationType.NONE;
        }

        @Override
        public void tick() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.world == null || !SpaceDims.isAirless(mc.world)) {
                setDone();
                return;
            }
            age++;
            this.volume = Math.min(0.7f, age / 60f);
        }
    }

    /** Space-frame rotation that takes a planet's local "up" (+Y) to the surface normal at (x, z). */
    public static Quaternionf surfaceFrame(Body b, double x, double z) {
        Vec3d n = b.surfaceNormal(x, z);
        return new Quaternionf().rotationTo(0, 1, 0, (float) n.x, (float) n.y, (float) n.z);
    }

    static VertexConsumer glow() {
        return BUF.getBuffer(BeamRenderLayers.GLOW);
    }
}
