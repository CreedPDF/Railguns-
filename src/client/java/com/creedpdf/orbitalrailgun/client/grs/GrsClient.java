package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.grs.Grs;
import com.creedpdf.orbitalrailgun.grs.GrsNet;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of the GRS: the goggles' controls and state (power, target lock, the strike in flight, recharge), their
 * worn model, the target marker painted on the ground, the station drawn in space and the pass-by roar.
 *
 * <p>Controls while wearing the goggles: [G] locks the surface under your crosshair, [G] again calls Gungnir onto it;
 * [Up]/[Down] choose the power (how much destruction), [H] raises or lowers the visor. A lock expires after a few
 * seconds or when you look away from it and press G on something else.</p>
 */
public final class GrsClient {
    private GrsClient() {}

    public static KeyBinding FIRE, YIELD_UP, YIELD_DOWN, VISOR;

    // ---- goggles state
    public static int power = 5;
    public static boolean visor = true;
    public static BlockPos hover, locked;
    public static long lockedAt;
    public static final int LOCK_TICKS = 160;
    /** Client time the last strike was accepted (for the countdown), and when the goggles can fire again. */
    public static long firedAt = -1, readyAt;
    public static BlockPos firedAt_pos;
    public static String msg;
    public static int msgColor;
    public static long msgAt;
    public static long wornAt = -1;
    private static boolean wasWorn;
    private static float lastLapPhase;

    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 18));

    public static void register() {
        GrsModels.register();
        EntityRendererRegistry.register(RailgunMod.GUNGNIR_STRIKE, GungnirStrikeRenderer::new);
        String cat = "key.categories.orbital_railgun.grs";
        FIRE = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.orbital_railgun.grs_fire", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, cat));
        YIELD_UP = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.orbital_railgun.grs_yield_up", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UP, cat));
        YIELD_DOWN = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.orbital_railgun.grs_yield_down", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, cat));
        VISOR = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.orbital_railgun.grs_visor", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, cat));
        ClientTickEvents.END_CLIENT_TICK.register(GrsClient::tick);
        ClientTickEvents.END_CLIENT_TICK.register(GrsSpace::tick);
        HudRenderCallback.EVENT.register(GogglesHud::render);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(GrsClient::world);
        WorldRenderEvents.LAST.register(GrsCine::world);
        HudRenderCallback.EVENT.register(GrsCine::hud);
        ClientTickEvents.END_CLIENT_TICK.register(GrsCine::tick);
        ClientPlayNetworking.registerGlobalReceiver(GrsNet.STATUS, (client, handler, buf, sender) -> {
            int code = buf.readVarInt(), cd = buf.readVarInt();
            client.execute(() -> status(code, cd));
        });
        ArmorRenderer.register((ms, vcp, stack, entity, slot, light, model) -> {
            if (GrsModels.GOGGLES == null) return;
            ms.push();
            model.head.rotate(ms);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180));
            GrsModels.GOGGLES.render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(GrsModels.GOGGLES_TEX)), light, null, null);
            float g = 0.8f + 0.2f * MathHelper.sin((entity.age + MinecraftClient.getInstance().getTickDelta()) * 0.2f);
            GrsModels.GOGGLES.render(ms, vcp.getBuffer(RenderLayer.getEyes(GrsModels.GOGGLES_GLOW)), light, null, (nx, ny, nz, o) -> {
                o[0] = g;
                o[1] = g;
                o[2] = g;
                o[3] = 1;
            });
            ms.pop();
        }, RailgunMod.GUNGNIR_GOGGLES);
    }

    public static boolean worn() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && Grs.wearing(mc.player);
    }

    public static boolean onEarth() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world != null && mc.world.getRegistryKey() == World.OVERWORLD;
    }

    public static long now() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? 0 : mc.world.getTime();
    }

    static void play(SoundEvent s, float pitch, float vol) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(s, pitch, vol));
    }

    static void say(String s, int color) {
        msg = s;
        msgColor = color;
        msgAt = now();
    }

    private static void tick(MinecraftClient mc) {
        boolean w = worn();
        if (w && !wasWorn) {
            wornAt = now();
            play(ModSounds.GRS_GOGGLES, 1f, 0.9f);
        }
        if (!w && wasWorn) locked = null;
        wasWorn = w;
        if (!w || mc.player == null || mc.world == null) {
            hover = null;
            while (FIRE.wasPressed() || YIELD_UP.wasPressed() || YIELD_DOWN.wasPressed() || VISOR.wasPressed()) ;
            return;
        }
        while (VISOR.wasPressed()) {
            visor = !visor;
            play(ModSounds.GRS_YIELD, visor ? 1.3f : 0.8f, 0.6f);
        }
        while (YIELD_UP.wasPressed()) setYield(power + 1);
        while (YIELD_DOWN.wasPressed()) setYield(power - 1);
        // what the crosshair rests on, out to the goggles' range
        hover = null;
        if (mc.cameraEntity != null && visor) {
            HitResult hr = mc.cameraEntity.raycast(Grs.RANGE, mc.getTickDelta(), false);
            if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) hover = bh.getBlockPos();
        }
        if (locked != null && now() - lockedAt > LOCK_TICKS) {
            locked = null;
            say("LOCK RELEASED", 0xFFB07A50);
        }
        while (FIRE.wasPressed()) {
            if (GrsCine.active()) {
                GrsCine.skip();
                continue;
            }
            if (!visor) continue;
            if (!onEarth()) {
                say("NO EARTH UPLINK  ·  GUNGNIR STRIKES EARTH ONLY", 0xFFFF5040);
                play(ModSounds.GRS_DENIED, 1f, 0.8f);
            } else if (now() < readyAt) {
                say(String.format(java.util.Locale.ROOT, "SPEAR RECALLING  ·  %.1fs", (readyAt - now()) / 20f), 0xFFFF9A40);
                play(ModSounds.GRS_DENIED, 1f, 0.8f);
            } else if (locked != null && (hover == null || hover.getSquaredDistance(locked) < 9 * 9)) {
                PacketByteBuf b = PacketByteBufs.create();
                b.writeBlockPos(locked);
                b.writeVarInt(power);
                ClientPlayNetworking.send(GrsNet.FIRE, b);
                say("CALLING GUNGNIR", 0xFFFFB060);
            } else if (hover != null) {
                locked = hover;
                lockedAt = now();
                play(ModSounds.GRS_LOCK, 1f, 0.9f);
                say("TARGET LOCKED  ·  [" + key(FIRE) + "] TO CALL THE SPEAR", 0xFFFFC070);
            } else {
                say("NO SURFACE IN RANGE", 0xFFFF5040);
                play(ModSounds.GRS_DENIED, 1f, 0.6f);
            }
        }
        // the spear on its lap, felt through the goggles: a soft zoom every time it comes round
        float phase = (now() % Grs.LAP_TICKS) / (float) Grs.LAP_TICKS;
        if (visor && phase < lastLapPhase && !GrsCine.active()) play(ModSounds.GRS_WHOOSH, 0.9f + 0.1f * (power / 10f), 0.16f);
        lastLapPhase = phase;
    }

    static String key(KeyBinding k) {
        return k.getBoundKeyLocalizedText().getString().toUpperCase(java.util.Locale.ROOT);
    }

    private static void setYield(int y) {
        int n = Grs.clampYield(y);
        if (n != power) play(ModSounds.GRS_YIELD, 0.7f + n * 0.07f, 0.8f);
        power = n;
    }

    private static void status(int code, int cooldown) {
        switch (code) {
            case GrsNet.OK -> {
                firedAt = now();
                firedAt_pos = locked;
                GrsCine.start(locked);
                readyAt = now() + cooldown;
                locked = null;
                say("GUNGNIR RELEASED  ·  " + Grs.yieldName(power), 0xFFFFA040);
            }
            case GrsNet.NOT_EARTH -> say("NO EARTH UPLINK", 0xFFFF5040);
            case GrsNet.NO_GOGGLES -> say("GOGGLES NOT WORN", 0xFFFF5040);
            case GrsNet.RECHARGING -> {
                readyAt = now() + cooldown;
                say(String.format(java.util.Locale.ROOT, "SPEAR RECALLING  ·  %.1fs", cooldown / 20f), 0xFFFF9A40);
            }
            case GrsNet.OUT_OF_RANGE -> say("TARGET OUT OF RANGE", 0xFFFF5040);
            default -> {
            }
        }
        if (code != GrsNet.OK) play(ModSounds.GRS_DENIED, 1f, 0.8f);
    }

    // ------------------------------------------------------------------------------------------- target marker

    private static void world(WorldRenderContext ctx) {
        if (!worn() || !visor || !onEarth() || GrsCine.active()) return;
        BlockPos t = locked != null ? locked : hover;
        if (t == null) return;
        boolean lock = locked != null;
        GrsDraw.camera(ctx.camera());
        Vec3d cam = ctx.camera().getPos();
        MatrixStack ms = ctx.matrixStack();
        ms.push();
        ms.translate(t.getX() + 0.5 - cam.x, t.getY() + 1.02 - cam.y, t.getZ() + 0.5 - cam.z);
        Matrix4f m = ms.peek().getPositionMatrix();
        float time = now() + ctx.tickDelta();
        float R = (float) Grs.blastRadius(power), cr = Math.max(1.5f, Grs.radius(power));
        float pulse = 0.7f + 0.3f * MathHelper.sin(time * (lock ? 0.6f : 0.25f));
        float k = (lock ? 1f : 0.45f) * pulse;
        Vector3f c = new Vector3f(0, 0.05f, 0), a = new Vector3f(1, 0, 0), b = new Vector3f(0, 0, 1);
        VertexConsumer shock = BUF.getBuffer(BeamRenderLayers.textured(GrsDraw.SHOCK));
        GrsDraw.flat(shock, m, c, a, b, R, k, 0.42f * k, 0.06f * k, 1);
        GrsDraw.flat(shock, m, new Vector3f(0, 0.1f, 0), a, b, cr, k * 1.2f, 0.6f * k, 0.15f * k, 1);
        BUF.draw();
        if (lock) {
            float lk = 1 - (now() - lockedAt + ctx.tickDelta()) / LOCK_TICKS;
            Vector3f eye = new Vector3f((float) (cam.x - t.getX() - 0.5), (float) (cam.y - t.getY() - 1.02), (float) (cam.z - t.getZ() - 0.5));
            GrsDraw.beam(BUF.getBuffer(BeamRenderLayers.textured(GrsDraw.BEAM)), m, new Vector3f(0, 0, 0), new Vector3f(0, 90, 0), eye, 0.6f, time * 0.2f, 12f,
                    1f, 0.45f, 0.08f, 0.6f * pulse * Math.max(0.3f, lk));
            BUF.draw();
            GrsDraw.sprite(BUF.getBuffer(BeamRenderLayers.xray(GrsDraw.FLARE)), m, 0, 1.2f, 0, 2.2f * pulse, time * 0.05f, 1f, 0.5f, 0.12f, 1);
            BUF.draw();
        }
        ms.pop();
    }
}
