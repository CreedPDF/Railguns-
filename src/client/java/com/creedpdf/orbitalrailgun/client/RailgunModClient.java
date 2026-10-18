package com.creedpdf.orbitalrailgun.client;

import com.creedpdf.orbitalrailgun.RailgunClientBridge;
import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.client.render.RailgunEntityRenderer;
import com.creedpdf.orbitalrailgun.client.screen.RailgunControlScreen;
import com.creedpdf.orbitalrailgun.client.sound.RailgunChargeSoundInstance;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/** Client entrypoint: renderers, HUD, client packet receivers and the client bridge. */
public final class RailgunModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        RailgunMod.setClientBridge(new Bridge());

        EntityRendererRegistry.register(RailgunMod.RAILGUN, RailgunEntityRenderer::new);
        ClientPackets.registerReceivers();

        HudRenderCallback.EVENT.register(RailgunHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(RailgunHud::tick);

        ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
            if (stack.isOf(RailgunMod.RAILGUN_REMOTE)) {
                lines.add(Text.translatable("item.orbital_railgun.railgun_remote.tooltip.1").formatted(Formatting.GRAY));
                lines.add(Text.translatable("item.orbital_railgun.railgun_remote.tooltip.2").formatted(Formatting.DARK_RED));
            }
        });
    }

    /** Real client-side implementation of the hooks common code calls into. */
    private static final class Bridge implements RailgunClientBridge {
        @Override
        public void openControlScreen(RailgunEntity railgun) {
            MinecraftClient.getInstance().setScreen(new RailgunControlScreen(railgun));
        }

        @Override
        public void requestDeploy(BlockPos target) {
            ClientPackets.sendDeployRequest(target);
        }

        @Override
        public void onStateChanged(RailgunEntity railgun, RailgunState newState) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (newState == RailgunState.CHARGING) {
                client.getSoundManager().play(new RailgunChargeSoundInstance(railgun));
            } else if (newState == RailgunState.FIRING && client.player != null) {
                double dist = client.player.distanceTo(railgun);
                RailgunHud.triggerFireFlash((float) Math.max(0.0, 1.0 - dist / 64.0));
            }
        }
    }
}
