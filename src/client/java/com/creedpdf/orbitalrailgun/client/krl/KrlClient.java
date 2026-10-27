package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.Krl;
import com.creedpdf.orbitalrailgun.krl.KrlClientHooks;
import com.creedpdf.orbitalrailgun.krl.KrlFlight;
import com.creedpdf.orbitalrailgun.krl.KrlNet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Client side of the KRL: the flights the server announced (drawn by {@link KrlFx} from the shared {@link KrlFlight}
 * curves), the fire-control board, the ride cutscene ({@link KrlCine}), the launcher and console renderers, sounds
 * and camera shake.
 */
public final class KrlClient {
    private KrlClient() {}

    /** A flight as the client knows it. */
    public static final class CFlight {
        public final int id;
        public final Identifier world;
        public final BlockPos pad;
        public final long liftoff;
        @Nullable
        public final UUID rider;
        public final int livery;
        public final com.creedpdf.orbitalrailgun.krl.KrlLoadout missile;
        public Vec3d launch, target;
        public KrlFlight path;
        /** Effects already started (smoke bursts, sounds). */
        boolean boomed, impacted, loops, mach1Puffed, boomHeard;
        double lastTrailT = 0;
        /** The flight time at which the climb goes through Mach 1 (for the vapour cone). */
        double mach1 = -1;

        CFlight(int id, Identifier world, BlockPos pad, Vec3d launch, Vec3d target, long liftoff, @Nullable UUID rider,
                com.creedpdf.orbitalrailgun.krl.KrlLoadout missile) {
            this.id = id;
            this.missile = missile;
            this.livery = missile.livery();
            this.world = world;
            this.pad = pad;
            this.launch = launch;
            this.target = target;
            this.liftoff = liftoff;
            this.rider = rider;
            this.path = new KrlFlight(launch, target, missile);
            for (int i = 2; i < KrlFlight.BOOST; i++) {
                if (path.mach(i) >= 1.0) {
                    mach1 = i;
                    break;
                }
            }
        }

        public double t(float pt) {
            return now() - liftoff + pt;
        }

        /** The direction the pad's flame trench opens toward (the launcher's front), from the block if it is loaded. */
        public Vec3d front() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world != null && mc.world.getBlockState(pad).isOf(Krl.LAUNCHER)) {
                var d = mc.world.getBlockState(pad).get(com.creedpdf.orbitalrailgun.krl.KrlLauncherBlock.FACING);
                return new Vec3d(d.getOffsetX(), 0, d.getOffsetZ());
            }
            return new Vec3d(0, 0, 1);
        }
    }

    private static final Map<Integer, CFlight> FLIGHTS = new LinkedHashMap<>();
    /** The flight this client is riding, or -1. */
    static int riding = -1;
    static long rideSince;

    public static Collection<CFlight> flights() {
        return FLIGHTS.values();
    }

    @Nullable
    public static CFlight flight(int id) {
        return FLIGHTS.get(id);
    }

    /** A countdown or flight on this pad (the latest), if any. */
    @Nullable
    public static CFlight padFlight(BlockPos pad) {
        CFlight out = null;
        for (CFlight f : FLIGHTS.values()) if (f.pad.equals(pad) && here(f)) out = f;
        return out;
    }

    public static boolean here(CFlight f) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world != null && mc.world.getRegistryKey().getValue().equals(f.world);
    }

    public static long now() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? 0 : mc.world.getTime();
    }

    /** 0 by day, 1 in full night (and under a dark storm). */
    public static float night() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return 0;
        return MathHelper.clamp((mc.world.getAmbientDarkness() - 4) / 6f, 0, 1);
    }

    /** 0..1 how lit the open sky is. */
    public static float daylight() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return 1;
        return MathHelper.clamp(1 - mc.world.getAmbientDarkness() / 11f, 0, 1);
    }

    // ------------------------------------------------------------------------------------------- setup

    public static void register() {
        KrlModels.register();
        KrlSettings.load();
        BlockEntityRendererFactories.register(Krl.LAUNCHER_BE, KrlLauncherRenderer::new);
        BlockEntityRendererFactories.register(Krl.CONSOLE_BE, KrlConsoleRenderer::new);
        BlockEntityRendererFactories.register(Krl.STATION_BE, KrlStationRenderer::new);
        BuiltinItemRendererRegistry.INSTANCE.register(Krl.STATION_ITEM, (stack, mode, ms, vcp, light, overlay) -> item(KrlModels.STATION,
                KrlModels.STATION_TEX, KrlModels.STATION_GLOW, mode, ms, vcp, light, 1.85f, false));
        BuiltinItemRendererRegistry.INSTANCE.register(Krl.SPEAR, (stack, mode, ms, vcp, light, overlay) -> item(KrlModels.SPEAR,
                KrlModels.spearTex(com.creedpdf.orbitalrailgun.krl.KineticSpearItem.livery(stack)), KrlModels.SPEAR_GLOW, mode, ms, vcp, light, 7.2f, true));
        BuiltinItemRendererRegistry.INSTANCE.register(Krl.LAUNCHER_ITEM, (stack, mode, ms, vcp, light, overlay) -> item(KrlModels.LAUNCHER,
                KrlModels.LAUNCHER_TEX, KrlModels.LAUNCHER_GLOW, mode, ms, vcp, light, 10.3f, false));
        BuiltinItemRendererRegistry.INSTANCE.register(Krl.CONSOLE_ITEM, (stack, mode, ms, vcp, light, overlay) -> item(KrlModels.CONSOLE,
                KrlModels.CONSOLE_TEX, KrlModels.CONSOLE_GLOW, mode, ms, vcp, light, 3.1f, false));

        ClientPlayNetworking.registerGlobalReceiver(KrlNet.FLIGHT, (client, h, buf, s) -> {
            CFlight f = readFlight(buf);
            client.execute(() -> FLIGHTS.put(f.id, merge(f)));
        });
        ClientPlayNetworking.registerGlobalReceiver(KrlNet.END, (client, h, buf, s) -> {
            int id = buf.readVarInt();
            client.execute(() -> {
                FLIGHTS.remove(id);
                if (riding == id) riding = -1;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(KrlNet.RIDE, (client, h, buf, s) -> {
            int id = buf.readVarInt();
            boolean on = buf.readBoolean();
            client.execute(() -> {
                if (on) {
                    riding = id;
                    rideSince = now();
                    if (client.currentScreen instanceof KrlScreen) client.setScreen(null);
                } else if (riding == id) {
                    riding = -1;
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(KrlNet.STATE, (client, h, buf, s) -> {
            KrlScreen.Board b = KrlScreen.Board.read(buf);
            client.execute(() -> KrlScreen.board(b));
        });
        ClientPlayNetworking.registerGlobalReceiver(KrlNet.MAP_DATA, (client, h, buf, s) -> {
            KrlSurvey m = KrlSurvey.read(buf);
            client.execute(() -> KrlSurvey.put(m));
        });
        ClientPlayNetworking.registerGlobalReceiver(KrlNet.STATION_OPEN, (client, h, buf, s) -> {
            BlockPos at = buf.readBlockPos();
            client.execute(() -> client.setScreen(new KrlStationScreen(at)));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            FLIGHTS.clear();
            riding = -1;
            KrlFx.clear();
            KrlSurvey.clear();
        });

        KrlClientHooks.launcher = be -> {};
        ClientTickEvents.END_CLIENT_TICK.register(KrlClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(KrlFx::render);
        HudRenderCallback.EVENT.register(KrlCine::hud);
    }

    private static CFlight readFlight(PacketByteBuf b) {
        int id = b.readVarInt();
        Identifier w = b.readIdentifier();
        BlockPos pad = b.readBlockPos();
        Vec3d l = new Vec3d(b.readDouble(), b.readDouble(), b.readDouble());
        Vec3d t = new Vec3d(b.readDouble(), b.readDouble(), b.readDouble());
        long lift = b.readLong();
        UUID r = b.readBoolean() ? b.readUuid() : null;
        var m = com.creedpdf.orbitalrailgun.krl.KrlLoadout.read(b);
        return new CFlight(id, w, pad, l, t, lift, r, m);
    }

    /** A re-sent flight (its aim point settled onto the real ground) keeps the effects already played. */
    private static CFlight merge(CFlight n) {
        CFlight o = FLIGHTS.get(n.id);
        if (o != null) {
            n.boomed = o.boomed;
            n.boomHeard = o.boomHeard;
            n.impacted = o.impacted;
            n.loops = o.loops;
            n.mach1Puffed = o.mach1Puffed;
            n.lastTrailT = o.lastTrailT;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------- items

    private static void item(KrlMesh mesh, Identifier tex, Identifier glow, net.minecraft.client.render.model.json.ModelTransformationMode mode, MatrixStack ms,
                             net.minecraft.client.render.VertexConsumerProvider vcp, int light, float size, boolean tilt) {
        if (mesh == null) return;
        ms.push();
        ms.translate(0.5, 0.0, 0.5);
        float s = 1f / size;
        if (tilt && mode == net.minecraft.client.render.model.json.ModelTransformationMode.GUI) {
            ms.translate(0, 0.5, 0);
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-45));
            ms.translate(0, -0.66, 0);
            s *= 1.33f;
        } else if (tilt) {
            s *= 1.6f;
            ms.translate(0, -0.2, 0);
        }
        ms.scale(s, s, s);
        mesh.render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(tex)), light, null, null);
        mesh.render(ms, vcp.getBuffer(RenderLayer.getEyes(glow)), light, null, null);
        ms.pop();
    }

    // ------------------------------------------------------------------------------------------- tick

    private static void tick(MinecraftClient mc) {
        if (mc.world == null) return;
        List<Integer> gone = new ArrayList<>();
        for (CFlight f : FLIGHTS.values()) {
            if (!here(f)) continue;
            double t = f.t(0);
            if (t > KrlFlight.TOTAL + 1300) {
                gone.add(f.id);
                continue;
            }
            if (!f.loops && t > -Krl.FLAME - 2) startLoops(mc, f);
            boom(mc, f, t);
            KrlFx.tick(f, t);
        }
        for (int id : gone) FLIGHTS.remove(id);
        KrlFx.tickParticles();
        KrlCine.tick(mc);
    }

    /**
     * The sonic boom of liftoff, heard when the sound gets to you: sound covers about 17 blocks a tick, so someone a
     * kilometre away hears it three seconds after the flash. Loud, and audible a long way off.
     */
    private static void boom(MinecraftClient mc, CFlight f, double t) {
        if (f.boomHeard || t < 0) return;
        Vec3d ear = mc.gameRenderer.getCamera().getPos();
        double d = ear.distanceTo(f.launch);
        if (t > 400 || d > 2200) {
            if (t > 400) f.boomHeard = true;
            return;
        }
        if (t < d / 17.15) return;
        f.boomHeard = true;
        float vol = (float) MathHelper.clamp(1.25 - d / 1600, 0.12, 1.25);
        float pitch = (float) MathHelper.clamp(1.0 - d / 4000, 0.7, 1.0);
        mc.getSoundManager().play(new net.minecraft.client.sound.PositionedSoundInstance(Krl.S_BOOM.getId(), SoundCategory.BLOCKS, vol, pitch,
                Random.create(), false, 0, SoundInstance.AttenuationType.NONE, 0, 0, 0, true));
    }

    private static void startLoops(MinecraftClient mc, CFlight f) {
        f.loops = true;
        int id = f.id;
        // the motor: on the pad through the countdown, then riding the spear until burnout
        loop(mc, Krl.S_BURN, () -> {
            CFlight c = FLIGHTS.get(id);
            if (c == null) return null;
            double t = c.t(0);
            if (t < 0) return c.launch;
            if (t > KrlFlight.BOOST * 0.82) return null;
            return c.path.pos(t);
        }, () -> {
            CFlight c = FLIGHTS.get(id);
            if (c == null) return 0;
            double t = c.t(0);
            double ramp = MathHelper.clamp((t + Krl.FLAME) / 6.0, 0, 1);
            double out = t < KrlFlight.BOOST * 0.7 ? 1 : MathHelper.clamp((KrlFlight.BOOST * 0.82 - t) / (KrlFlight.BOOST * 0.12), 0, 1);
            return (t < 0 ? 2.2 : 4.0) * ramp * out;
        });
    }

    private static void loop(MinecraftClient mc, SoundEvent ev, Supplier<Vec3d> where, DoubleSupplier vol) {
        mc.getSoundManager().play(new Loop(ev, where, vol));
    }

    /** A looping sound that follows a point and a volume; stops when the point goes away. */
    static final class Loop extends MovingSoundInstance {
        private final Supplier<Vec3d> where;
        private final DoubleSupplier vol;
        private final boolean relative;

        Loop(SoundEvent ev, Supplier<Vec3d> where, DoubleSupplier vol) {
            this(ev, where, vol, false);
        }

        Loop(SoundEvent ev, Supplier<Vec3d> where, DoubleSupplier vol, boolean relative) {
            super(ev, SoundCategory.BLOCKS, Random.create());
            this.where = where;
            this.vol = vol;
            this.relative = relative;
            this.repeat = true;
            this.repeatDelay = 0;
            this.attenuationType = relative ? SoundInstance.AttenuationType.NONE : SoundInstance.AttenuationType.LINEAR;
            this.volume = 0.01f;
            Vec3d p = where.get();
            if (p != null) {
                x = p.x;
                y = p.y;
                z = p.z;
            }
        }

        @Override
        public boolean isRelative() {
            return relative;
        }

        @Override
        public boolean shouldAlwaysPlay() {
            return true;
        }

        @Override
        public void tick() {
            Vec3d p = where.get();
            if (p == null) {
                setDone();
                return;
            }
            if (!relative) {
                x = p.x;
                y = p.y;
                z = p.z;
            }
            volume = (float) Math.max(0.0001, vol.getAsDouble());
        }
    }

    /** Non-positional loops for the rider (rushing air, the plasma sheath). */
    static void riderLoops(MinecraftClient mc, int id) {
        mc.getSoundManager().play(new Loop(Krl.S_WIND, () -> riding == id && FLIGHTS.containsKey(id) ? Vec3d.ZERO : null, () -> KrlCine.windVolume(), true));
        mc.getSoundManager().play(new Loop(Krl.S_PLASMA, () -> riding == id && FLIGHTS.containsKey(id) ? Vec3d.ZERO : null, () -> KrlCine.plasmaVolume(), true));
    }

    // ------------------------------------------------------------------------------------------- shake

    /** Camera shake from launches and impacts nearby (blocks of offset). */
    public static Vec3d shake(Vec3d cam, float pt) {
        double amp = 0;
        for (CFlight f : FLIGHTS.values()) {
            if (!here(f)) continue;
            double t = f.t(pt);
            double dp = cam.distanceTo(f.launch);
            if (t > -Krl.FLAME && t < 0) amp += 0.05 * Math.max(0, 1 - dp / 90) * MathHelper.clamp((t + Krl.FLAME) / 6, 0, 1);
            if (t >= 0 && t < 90) amp += 0.32 * Math.max(0, 1 - dp / 260) * Math.exp(-t / 22);
            double di = cam.distanceTo(f.target);
            double ti = t - KrlFlight.TOTAL;
            if (ti >= 0 && ti < 100) amp += 0.6 * Math.exp(-di / 200) * Math.exp(-ti / 16);
        }
        amp *= KrlSettings.shake;
        if (amp < 1e-4) return Vec3d.ZERO;
        double tt = (now() + pt) * 1.7;
        return new Vec3d(noise(tt, 1.3) * amp, noise(tt, 7.9) * amp * 0.8, noise(tt, 4.1) * amp);
    }

    private static double noise(double t, double s) {
        return Math.sin(t * 2.1 + s) * 0.5 + Math.sin(t * 3.7 + s * 2.3) * 0.3 + Math.sin(t * 6.3 + s * 5.1) * 0.2;
    }

    /** Anything in the air that needs the far plane pushed out. */
    public static boolean farFlight() {
        for (CFlight f : FLIGHTS.values()) if (here(f) && f.t(0) > -20) return true;
        return false;
    }

    public static ClientWorld world() {
        return MinecraftClient.getInstance().world;
    }

    /** Opaque red at the given brightness (for the armed lamp). */
    public static int alphaRed(float g) {
        int v = (int) (MathHelper.clamp(g, 0, 1) * 255);
        return 0xFF000000 | (v << 16) | ((v / 3) << 8) | (v / 4);
    }
}
