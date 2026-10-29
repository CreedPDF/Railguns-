package com.creedpdf.orbitalrailgun.client.hangeki;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.hangeki.Hangeki;
import com.creedpdf.orbitalrailgun.hangeki.HangekiNet;
import com.creedpdf.orbitalrailgun.hangeki.TimeScale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/** Client entrypoint for HANGEKI: the parry key, packets, the sword's model, the HUD, the effects, the warning pips. */
public final class HangekiClient {
    private HangekiClient() {}

    private static KeyBinding parry;
    private static int pip;
    /** Real-time millis of the last impact frames (several reflected things in one parry make one set). */
    private static long lastFrames;

    public static KeyBinding parryKey() {
        return parry;
    }

    public static void register() {
        parry = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.orbital_railgun.parry", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R,
                "key.categories.orbital_railgun.hangeki"));
        ClientTickEvents.END_CLIENT_TICK.register(HangekiClient::tick);
        ClientTickEvents.END_CLIENT_TICK.register(HangekiFx::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(HangekiFx::render);
        HudRenderCallback.EVENT.register(HangekiHud::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            ParryClient.clear();
            HangekiFx.clear();
        }));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(ParryClient::clear));
        BuiltinItemRendererRegistry.INSTANCE.register(Hangeki.SWORD, HangekiClient::renderItem);

        ClientPlayNetworking.registerGlobalReceiver(HangekiNet.SCALE, (client, h, buf, s) -> {
            float sc = buf.readFloat();
            client.execute(() -> TimeScale.clientTarget = sc);
        });
        ClientPlayNetworking.registerGlobalReceiver(HangekiNet.METER, (client, h, buf, s) -> {
            byte kind = buf.readByte();
            float c = buf.readFloat();
            String label = buf.readString();
            int n = buf.readVarInt();
            boolean slowed = buf.readBoolean();
            client.execute(() -> ParryClient.meter(kind, c, label, n, slowed));
        });
        ClientPlayNetworking.registerGlobalReceiver(HangekiNet.STATE, (client, h, buf, s) -> {
            int player = buf.readVarInt();
            byte phase = buf.readByte();
            long start = buf.readLong();
            int ticks = buf.readVarInt();
            Vec3d focus = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            client.execute(() -> ParryClient.state(player, phase, start, ticks, focus));
        });
        ClientPlayNetworking.registerGlobalReceiver(HangekiNet.FX, (client, h, buf, s) -> {
            byte kind = buf.readByte();
            Vec3d at = vec(buf), dir = vec(buf), to = vec(buf);
            int player = buf.readVarInt(), entity = buf.readVarInt();
            float speed = buf.readFloat();
            int extra = buf.readVarInt();
            client.execute(() -> fx(client, kind, at, dir, to, player, entity, speed, extra));
        });
    }

    private static Vec3d vec(PacketByteBuf b) {
        return new Vec3d(b.readDouble(), b.readDouble(), b.readDouble());
    }

    private static void fx(MinecraftClient mc, byte kind, Vec3d at, Vec3d dir, Vec3d to, int player, int entity, float speed, int extra) {
        HangekiFx.add(kind, at, dir, to, player, entity, speed, extra);
        boolean mine = mc.player != null && mc.player.getId() == player;
        long now = System.currentTimeMillis();
        switch (kind) {
            case HangekiNet.FX_REFLECT -> {
                if (mine && now - lastFrames > 400) {
                    lastFrames = now;
                    HangekiHud.frames("REFLECTED", true);
                    HangekiCine.kick(1.4f);
                    if (mc.player != null) mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                } else if (!mine && mc.player != null && mc.player.getPos().distanceTo(at) < 24) HangekiCine.kick(0.6f);
            }
            case HangekiNet.FX_REFLECT_QUICK -> {
                if (mine && now - lastFrames > 250) {
                    lastFrames = now;
                    HangekiHud.frames("REFLECTED", false);
                    HangekiCine.kick(0.8f);
                }
            }
            case HangekiNet.FX_COUNTER -> {
                if (mine) {
                    if (extra == 0 && now - lastFrames > 250) {
                        lastFrames = now;
                        HangekiHud.frames("COUNTER", false);
                    }
                    HangekiCine.kick(0.9f);
                    if (mc.player != null) mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                }
            }
            case HangekiNet.FX_BEAM -> {
                if (mc.player != null && mc.player.getPos().distanceTo(to) < 60) HangekiCine.kick(1.6f);
            }
            default -> {
            }
        }
    }

    private static void tick(MinecraftClient mc) {
        HangekiCine.tick(mc);
        if (mc.player == null || mc.world == null) return;
        while (parry.wasPressed()) {
            if (ParryClient.holding() && mc.currentScreen == null) ClientPlayNetworking.send(HangekiNet.PARRY, PacketByteBufs.empty());
        }
        // the alarm: pips that quicken as the threat closes
        if (ParryClient.holding() && ParryClient.meterKind != HangekiNet.K_NONE && !HangekiCine.active()) {
            int every = ParryClient.slowed ? 9 : Math.max(2, Math.round(MathHelper.lerp(ParryClient.closeness, 16, 2)));
            if (++pip >= every) {
                pip = 0;
                float pitch = ParryClient.slowed ? 0.7f : 0.9f + 0.4f * ParryClient.closeness;
                mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(), Hangeki.S_WARN, SoundCategory.PLAYERS,
                        0.35f + 0.35f * ParryClient.closeness, pitch);
            }
        } else pip = 100;
    }

    // ------------------------------------------------------------------------------------------- the sword's model

    /**
     * The Blender katana is drawn into the same 0..1 item box a vanilla sword sprite fills, blade on the diagonal, so
     * the vanilla handheld transforms (item model JSON) hold it like any sword. The parry ring turns; the edge burns
     * brighter while its bearer is in slow motion or a stance.
     */
    private static void renderItem(net.minecraft.item.ItemStack stack, ModelTransformationMode mode, MatrixStack ms, VertexConsumerProvider vcp,
                                   int light, int overlay) {
        KrlMesh mesh = KrlModels.HANGEKI;
        if (mesh == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        float time = mc.world == null ? 0 : mc.world.getTime() + mc.getTickDelta();
        boolean hot = ParryClient.slowed || HangekiCine.active() || HangekiHud.framesActive();
        ms.push();
        ms.translate(0.5, 0.5, 0.5);
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-45));
        float s = 0.86f;
        ms.scale(s, s, s);
        ms.translate(0, -0.3, 0);
        float spin = time * (hot ? 0.45f : 0.06f);
        KrlMesh.Pose pose = (part, st) -> {
            if ("ring".equals(part)) st.multiply(RotationAxis.POSITIVE_Y.rotation(spin));
            return true;
        };
        float edge = hot ? 1f : 0.72f + 0.12f * MathHelper.sin(time * 0.15f);
        int lod = mode == ModelTransformationMode.GUI || mode == ModelTransformationMode.GROUND || mode == ModelTransformationMode.FIXED ? 2 : 0;
        if (KrlCompat.shadowPass()) lod = 2;
        mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.HANGEKI_TEX)), light, pose, null);
        if (!KrlCompat.shadowPass()) mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.HANGEKI_GLOW)), light, pose,
                (nx, ny, nz, o) -> {
                    o[0] = edge;
                    o[1] = edge;
                    o[2] = edge;
                    o[3] = 1f;
                });
        ms.pop();
    }
}
