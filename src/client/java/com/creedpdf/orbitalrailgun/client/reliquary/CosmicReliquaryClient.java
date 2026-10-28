package com.creedpdf.orbitalrailgun.client.reliquary;

import com.creedpdf.orbitalrailgun.client.reliquary.hud.AethericRing;
import com.creedpdf.orbitalrailgun.client.reliquary.input.ReliquaryInput;
import com.creedpdf.orbitalrailgun.client.reliquary.render.MoltenScarRenderer;
import com.creedpdf.orbitalrailgun.client.reliquary.render.ReliquaryFx;
import com.creedpdf.orbitalrailgun.client.reliquary.render.ReliquaryItemRenderers;
import com.creedpdf.orbitalrailgun.client.reliquary.render.SingularityRenderer;
import com.creedpdf.orbitalrailgun.client.reliquary.render.VoidRiftRenderer;
import com.creedpdf.orbitalrailgun.client.reliquary.sound.StarEaterHumSound;
import com.creedpdf.orbitalrailgun.reliquary.ModBlocks;
import com.creedpdf.orbitalrailgun.reliquary.ModEntities;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Client side of the Cosmic Reliquary, registered from the mod's ClientModInitializer: entity, block-entity and item
 * renderers, the world effects layer, the crosshair ring, input, sounds and packet receivers.
 */
public final class CosmicReliquaryClient {
    private CosmicReliquaryClient() {}

    public static void register() {
        EntityRendererRegistry.register(ModEntities.SINGULARITY, SingularityRenderer::new);
        EntityRendererRegistry.register(ModEntities.VOID_RIFT, VoidRiftRenderer::new);
        BlockEntityRendererFactories.register(ModBlocks.MOLTEN_SCAR_BE, MoltenScarRenderer::new);
        ReliquaryItemRenderers.register();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ReliquaryFx::render);
        HudRenderCallback.EVENT.register(AethericRing::render);
        ClientTickEvents.END_CLIENT_TICK.register(CosmicReliquaryClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            ReliquaryClientState.reset();
            ReliquaryFx.clear();
            ReliquaryInput.reset();
        });
        receivers();
    }

    private static void tick(MinecraftClient mc) {
        if (mc.isPaused()) return;
        ReliquaryClientState.ticks++;
        ReliquaryInput.tick(mc);
        ReliquaryFx.tick(mc);
    }

    private static void receivers() {
        ClientPlayNetworking.registerGlobalReceiver(ReliquaryNet.BEAM_FX, (mc, h, buf, s) -> {
            int id = buf.readVarInt();
            boolean on = buf.readBoolean();
            mc.execute(() -> {
                if (!on) {
                    ReliquaryClientState.BEAMS.remove(id);
                    return;
                }
                if (ReliquaryClientState.BEAMS.putIfAbsent(id, ReliquaryClientState.ticks) == null && mc.world != null
                        && mc.world.getEntityById(id) instanceof PlayerEntity p) mc.getSoundManager().play(new StarEaterHumSound(p));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(ReliquaryNet.SLASH_FX, (mc, h, buf, s) -> {
            int id = buf.readVarInt();
            float yaw = buf.readFloat(), pitch = buf.readFloat();
            mc.execute(() -> {
                if (mc.world != null && mc.world.getEntityById(id) instanceof PlayerEntity p) ReliquaryFx.slash(p, yaw, pitch);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(ReliquaryNet.ARCS, (mc, h, buf, s) -> {
            int rift = buf.readVarInt();
            int[] ids = new int[buf.readVarInt()];
            for (int i = 0; i < ids.length; i++) ids[i] = buf.readVarInt();
            mc.execute(() -> ReliquaryFx.arcs(rift, ids));
        });
        ClientPlayNetworking.registerGlobalReceiver(ReliquaryNet.SHATTER, (mc, h, buf, s) -> {
            Vec3d at = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            float size = buf.readFloat();
            mc.execute(() -> ReliquaryFx.shatter(at, size));
        });
        ClientPlayNetworking.registerGlobalReceiver(ReliquaryNet.SHOT, (mc, h, buf, s) -> {
            int id = buf.readVarInt();
            Vec3d end = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            boolean wall = buf.readBoolean();
            mc.execute(() -> {
                if (mc.world != null && mc.world.getEntityById(id) instanceof PlayerEntity p) ReliquaryFx.shot(p, end, wall);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(ReliquaryNet.BURN, (mc, h, buf, s) -> {
            int[] ids = new int[buf.readVarInt()];
            for (int i = 0; i < ids.length; i++) ids[i] = buf.readVarInt();
            mc.execute(() -> ReliquaryFx.burn(ids));
        });
        ClientPlayNetworking.registerGlobalReceiver(ReliquaryNet.CHARGE, (mc, h, buf, s) -> {
            int c = buf.readVarInt();
            mc.execute(() -> ReliquaryClientState.charge = c);
        });
        ClientPlayNetworking.registerGlobalReceiver(ReliquaryNet.LINK_FX, (mc, h, buf, s) -> {
            int id = buf.readVarInt();
            mc.execute(() -> ReliquaryFx.link(id));
        });
    }
}
