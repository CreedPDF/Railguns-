package com.creedpdf.orbitalrailgun.client;

import com.creedpdf.orbitalrailgun.RailgunClientBridge;
import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.client.network.ClientPackets;
import com.creedpdf.orbitalrailgun.client.particle.RailgunParticles;
import com.creedpdf.orbitalrailgun.client.render.RailgunEntityRenderer;
import com.creedpdf.orbitalrailgun.client.sound.RailgunChargeSoundInstance;
import com.creedpdf.orbitalrailgun.client.sound.RailgunReentrySoundInstance;
import com.creedpdf.orbitalrailgun.client.panel.PanelScreen;
import com.creedpdf.orbitalrailgun.client.tablet.TabletScreen;
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
        EntityRendererRegistry.register(RailgunMod.ARES0, com.creedpdf.orbitalrailgun.client.ares0.Ares0Renderer::new);
        EntityRendererRegistry.register(RailgunMod.METEOR, com.creedpdf.orbitalrailgun.client.render.MeteorRenderer::new);
        registerPortalRenderer();
        com.creedpdf.orbitalrailgun.client.ares0.Ares0Fx.register();
        com.creedpdf.orbitalrailgun.client.space.SpaceClient.register();
        HudRenderCallback.EVENT.register(com.creedpdf.orbitalrailgun.client.space.SpaceHud::render);
        RailgunParticles.register();
        ClientPackets.registerReceivers();
        com.creedpdf.orbitalrailgun.client.render.RemoteBeams.register();
        com.creedpdf.orbitalrailgun.client.render.ScanFx.register();
        com.creedpdf.orbitalrailgun.client.render.RiftFx.register();

        HudRenderCallback.EVENT.register(RailgunHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(RailgunHud::tick);

        ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
            if (stack.getItem() instanceof com.creedpdf.orbitalrailgun.item.EvaSuitItem eva) {
                lines.add(Text.translatable("item.orbital_railgun.eva.tooltip").formatted(Formatting.GRAY));
                if (eva.getType() == net.minecraft.item.ArmorItem.Type.CHESTPLATE) {
                    lines.add(Text.translatable("item.orbital_railgun.eva_chestplate.tooltip").formatted(Formatting.AQUA));
                }
            }
            if (stack.isOf(RailgunMod.RAILGUN_REMOTE)) {
                lines.add(Text.translatable("item.orbital_railgun.railgun_remote.tooltip.1").formatted(Formatting.GRAY));
                lines.add(Text.translatable("item.orbital_railgun.railgun_remote.tooltip.2").formatted(Formatting.DARK_RED));
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerPortalRenderer() {
        EntityRendererRegistry.register(RailgunMod.RIFT_PORTAL,
                (net.minecraft.client.render.entity.EntityRendererFactory) qouteall.imm_ptl.core.render.PortalEntityRenderer::new);
    }

    /** Real client-side implementation of the hooks common code calls into. */
    private static final class Bridge implements RailgunClientBridge {
        @Override
        public void openTablet(BlockPos aimed) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen == null) client.setScreen(new TabletScreen(aimed));
        }

        @Override
        public void openPanel(RailgunEntity railgun) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen == null) client.setScreen(new PanelScreen(railgun));
        }

        @Override
        public void shake(RailgunEntity railgun, float strength) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            double dist = client.player.distanceTo(railgun);
            RailgunHud.shake((float) (strength * Math.max(0.0, 1.0 - dist / 64.0)));
        }

        @Override
        public Object evaRenderProvider() {
            return com.creedpdf.orbitalrailgun.client.render.EvaSuitRenderer.provider();
        }

        @Override
        public void shakeAt(net.minecraft.util.math.Vec3d pos, float strength, double range) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            double d = client.player.getPos().distanceTo(pos);
            RailgunHud.shake((float) (strength * Math.max(0.0, 1.0 - d / range)));
        }

        @Override
        public void openAres0(com.creedpdf.orbitalrailgun.entity.Ares0Entity ares) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen == null) client.setScreen(new com.creedpdf.orbitalrailgun.client.ares0.Ares0Screen(ares));
        }

        @Override
        public void startReentrySound(RailgunEntity railgun) {
            MinecraftClient.getInstance().getSoundManager().play(new RailgunReentrySoundInstance(railgun));
        }

        @Override
        public void onStateChanged(RailgunEntity railgun, RailgunState newState) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            double dist = client.player.distanceTo(railgun);
            switch (newState) {
                case POD -> RailgunHud.shake((float) Math.max(0.0, 1.0 - dist / 60.0));
                case CHARGING -> client.getSoundManager().play(new RailgunChargeSoundInstance(railgun));
                case RIFT -> client.getSoundManager().play(new com.creedpdf.orbitalrailgun.client.sound.RiftSoundInstance(railgun));
                case FIRING -> {
                    double toImpact = Math.sqrt(client.player.getBlockPos().getSquaredDistance(railgun.getTarget()));
                    float near = (float) Math.max(0.0, 1.0 - Math.min(dist, toImpact) / 120.0);
                    RailgunHud.triggerFireFlash(Math.min(1f, 0.35f + near));
                    RailgunHud.shake(0.9f + 2.2f * near);
                }
                default -> { }
            }
        }
    }
}
