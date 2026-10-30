package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.astraea.Astraea;
import com.creedpdf.orbitalrailgun.astraea.AstraeaItem;
import com.creedpdf.orbitalrailgun.astraea.AstraeaNet;
import com.creedpdf.orbitalrailgun.astraea.Timeline;
import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/** Client entrypoint for ASTRAEA: keys, packets, sounds heard by everyone, the shake, effects, HUD, the lance's model. */
public final class AstraeaClient {
    private AstraeaClient() {}

    private static KeyBinding ability, cine;
    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 19));
    private static final Random RNG = new Random();

    private static float shake;
    private static double lastLaps = -1;
    private static boolean drillPlayed, ascended;
    private static long nextQuake = -1;
    private static boolean wasHoldingCharged;

    public static void register() {
        ability = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.orbital_railgun.astraea", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V,
                "key.categories.orbital_railgun.hangeki"));
        cine = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.orbital_railgun.astraea_cine", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.orbital_railgun.hangeki"));
        AstraeaCine.load();
        AstraeaFrames.register();
        BuiltinItemRendererRegistry.INSTANCE.register(Astraea.LANCE, LanceFx::renderItem);
        ClientTickEvents.END_CLIENT_TICK.register(AstraeaClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(AstraeaClient::render);
        HudRenderCallback.EVENT.register(AstraeaClient::hud);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(AstraeaState::clear));
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.execute(AstraeaState::clear));

        ClientPlayNetworking.registerGlobalReceiver(AstraeaNet.RITUAL, (client, h, buf, s) -> {
            int player = buf.readVarInt();
            long start = buf.readLong();
            Vec3d anchor = vec(buf);
            client.execute(() -> {
                Entity e = client.world == null ? null : client.world.getEntityById(player);
                float yaw = e != null ? e.getYaw() : 0f;
                if (client.player != null && client.player.getId() == player) {
                    yaw = client.player.getYaw();
                    ascended = false;
                    play(Astraea.S_STANCE, 1f, 1f);
                }
                AstraeaState.RITUALS.put(player, new AstraeaState.Ritual(player, start, anchor, yaw));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(AstraeaNet.RITUAL_END, (client, h, buf, s) -> {
            int player = buf.readVarInt();
            client.execute(() -> {
                AstraeaState.Ritual r = AstraeaState.RITUALS.get(player);
                // cut short: drop it now; finished: let the rift fade out on its own
                if (r != null && AstraeaState.ritualT(player, 0) < Timeline.R_END - 2) AstraeaState.RITUALS.remove(player);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(AstraeaNet.STRIKE, (client, h, buf, s) -> {
            int player = buf.readVarInt();
            boolean mine = buf.readBoolean();
            long start = buf.readLong();
            Vec3d anchor = vec(buf), normal = vec(buf);
            double angle = buf.readDouble();
            client.execute(() -> {
                AstraeaState.strike = new AstraeaState.Strike(player, mine, start, anchor, normal, angle);
                lastLaps = -1;
                drillPlayed = false;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(AstraeaNet.MOMENT, (client, h, buf, s) -> {
            long time = buf.readLong();
            client.execute(() -> moment(client, time));
        });
        ClientPlayNetworking.registerGlobalReceiver(AstraeaNet.SPLIT, (client, h, buf, s) -> {
            long time = buf.readLong();
            int strikes = buf.readVarInt();
            Vec3d n = vec(buf);
            int count = buf.readVarInt();
            AstraeaState.Chasm[] cs = new AstraeaState.Chasm[count];
            for (int i = 0; i < count; i++) cs[i] = new AstraeaState.Chasm(buf.readDouble(), buf.readDouble(), buf.readDouble());
            client.execute(() -> {
                AstraeaState.splitTime = time;
                AstraeaState.strikes = strikes;
                AstraeaState.normal = n;
                AstraeaState.CHASMS.clear();
                java.util.Collections.addAll(AstraeaState.CHASMS, cs);
            });
        });
    }

    private static Vec3d vec(PacketByteBuf b) {
        return new Vec3d(b.readDouble(), b.readDouble(), b.readDouble());
    }

    private static boolean inOverworld(MinecraftClient mc) {
        return mc.world != null && mc.world.getRegistryKey() == World.OVERWORLD;
    }

    private static void play(SoundEvent e, float pitch, float vol) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(e, pitch, vol));
    }

    /** The Earth has just split: the sound, the frames, the shake, and aftershocks for a minute and a half. */
    private static void moment(MinecraftClient mc, long time) {
        if (!inOverworld(mc) && !(mc.world != null && AstraeaCine.strikeFilming())) return;
        play(Astraea.S_SPLIT, 1f, 1f);
        AstraeaFrames.split(1.42f + (RNG.nextFloat() - 0.5f) * 0.3f);
        kick(1f);
        nextQuake = time + 70 + RNG.nextInt(60);
    }

    public static void kick(float s) {
        shake = Math.max(shake, s);
    }

    /** 0..1-ish: how hard the ground is shaking now (also used by the space cutscene). */
    public static float shakeAmount() {
        MinecraftClient mc = MinecraftClient.getInstance();
        float drill = 0f;
        double t = AstraeaState.strikeT(mc.getTickDelta());
        if (t >= Timeline.S_DRILL && t < Timeline.S_SPLIT) drill = 0.12f + 0.4f * (float) Timeline.cutProgress(t);
        double since = AstraeaState.sinceSplit(mc.getTickDelta());
        float after = since >= 0 && since < 1800 ? 0.06f * (1f - (float) since / 1800f) : 0f;
        return Math.max(shake, Math.max(drill, after));
    }

    /** The camera's shake offset: mild - a rumble, never a blur. */
    public static Vec3d shake(float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!inOverworld(mc)) return Vec3d.ZERO;
        float a = shakeAmount();
        if (a < 0.003f) return Vec3d.ZERO;
        double ct = System.nanoTime() / 1e9;
        double amp = 0.11 * a;
        return new Vec3d(amp * (Math.sin(ct * 31.0) * 0.6 + Math.sin(ct * 53.0 + 1.1) * 0.4),
                amp * 0.8 * (Math.sin(ct * 41.0 + 2.0) * 0.6 + Math.sin(ct * 67.0) * 0.4),
                amp * (Math.sin(ct * 37.0 + 0.4) * 0.6 + Math.sin(ct * 59.0 + 2.6) * 0.4));
    }

    // ------------------------------------------------------------------------------------------- ticking

    private static void tick(MinecraftClient mc) {
        shake *= 0.9f;
        if (shake < 0.003f) shake = 0;
        while (cine.wasPressed()) {
            boolean on = AstraeaCine.toggle();
            if (mc.player != null)
                mc.player.sendMessage(KrlText.t("ASTRAEA  //  CUTSCENES " + (on ? "ON" : "OFF"), KrlText.GREY), true);
        }
        if (mc.player == null || mc.world == null) return;
        while (ability.wasPressed()) {
            if (mc.currentScreen != null) continue;
            if (AstraeaCine.skip()) continue;
            if (mc.player.getMainHandStack().isOf(Astraea.LANCE)) ClientPlayNetworking.send(AstraeaNet.KEY, PacketByteBufs.empty());
        }
        // the lance hums when you take it up, ascended
        ItemStack held = mc.player.getMainHandStack();
        boolean charged = held.isOf(Astraea.LANCE) && AstraeaItem.charged(held);
        if (charged && !wasHoldingCharged) play(Astraea.S_AURA, 1f, 0.5f);
        wasHoldingCharged = charged;

        // your own Ascension: the impact frames and the banner at the moment it is absorbed
        AstraeaState.Ritual mine = AstraeaState.RITUALS.get(mc.player.getId());
        if (mine != null) {
            double t = AstraeaState.ritualT(mine.player(), 0);
            if (!ascended && t >= Timeline.R_IMPACT) {
                ascended = true;
                AstraeaFrames.ascension();
                kick(0.7f);
            }
        }
        AstraeaState.RITUALS.values().removeIf(r -> AstraeaState.ritualT(r.player(), 0) > Timeline.R_END + 40);

        // the strike, heard by everyone in the overworld: a pass each lap, rising; the drill; the rumble
        AstraeaState.Strike s = AstraeaState.strike;
        if (s != null) {
            double t = AstraeaState.strikeT(0);
            if (t < 0) {
                AstraeaState.strike = null;
            } else if (inOverworld(mc) || s.mine()) {
                if (t >= Timeline.S_ORBIT && t < Timeline.S_DRILL) {
                    double u = (t - Timeline.S_ORBIT) / (Timeline.S_DRILL - Timeline.S_ORBIT);
                    double laps = Timeline.laps(u) * 2;   // twice a lap: over you, and round the far side
                    if (lastLaps >= 0 && Math.floor(laps) > Math.floor(lastLaps)) {
                        float k = (float) u;
                        play(Astraea.S_PASS, 0.75f + 0.75f * k, 0.45f + 0.55f * k);
                    }
                    lastLaps = laps;
                }
                if (!drillPlayed && t >= Timeline.S_DRILL) {
                    drillPlayed = true;
                    play(Astraea.S_DRILL, 1f, 1f);
                }
            }
        }
        // aftershocks
        if (nextQuake >= 0 && inOverworld(mc)) {
            long now = mc.world.getTime();
            double since = AstraeaState.sinceSplit(0);
            if (since > 1800) nextQuake = -1;
            else if (now >= nextQuake) {
                float k = 1f - (float) since / 1800f;
                play(Astraea.S_QUAKE, 0.85f + RNG.nextFloat() * 0.3f, 0.35f + 0.55f * k);
                kick(0.25f + 0.35f * k);
                nextQuake = now + 160 + RNG.nextInt(260);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- rendering

    private static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        float pt = ctx.tickDelta();
        Vec3d cam = ctx.camera().getPos();
        MatrixStack ms = ctx.matrixStack();
        Matrix4f m = ms.peek().getPositionMatrix();
        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(1e7f);
        RenderSystem.setShaderFogEnd(2e7f);
        for (AstraeaState.Ritual r : AstraeaState.RITUALS.values()) RitualFx.draw(BUF, m, cam, r, pt);
        BUF.draw();
        if (AstraeaState.strike != null && inOverworld(mc)) LanceFx.flight(BUF, ms, cam, pt);
        BUF.draw();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    private static void hud(DrawContext ctx, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.options.hudHidden) return;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        if (AstraeaCine.active()) {
            // letterbox
            int bar = Math.round(h * 0.085f);
            ctx.fill(0, 0, w, bar, 0xFF000000);
            ctx.fill(0, h - bar, w, h, 0xFF000000);
            String key = ability.getBoundKeyLocalizedText().getString().toUpperCase();
            var tx = KrlText.t("[" + key + "]  SKIP", 2f);
            ctx.drawText(mc.textRenderer, tx, w - mc.textRenderer.getWidth(tx) - 10, h - bar / 2 - 4, 0x99FFF2DE, false);
        }
        AstraeaFrames.renderHud(ctx);
    }
}
