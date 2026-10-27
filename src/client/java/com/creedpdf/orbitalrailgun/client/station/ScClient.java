package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.station.Sc;
import com.creedpdf.orbitalrailgun.station.ScNet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Client side of Space Command: renderers, packets, effects and the strike cutscene. */
public final class ScClient {
    private ScClient() {}

    /** Client tick counter (effects run on it). */
    static long ticks;

    public static void register() {
        BlockEntityRendererFactories.register(Sc.HUB_BE, ScHubRenderer::new);
        BlockEntityRendererFactories.register(Sc.SOLAR_BE, ScSolarRenderer::new);
        BlockEntityRendererFactories.register(Sc.RAILGUN_BE, ScRailgunRenderer::new);
        BlockEntityRendererFactories.register(Sc.SATELLITE_BE, ScSatelliteRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(ScNet.HUB, (client, handler, buf, sender) -> {
            BlockPos hub = buf.readBlockPos();
            boolean open = buf.readBoolean();
            ScHubScreen.Info info = ScHubScreen.Info.read(buf, hub);
            client.execute(() -> {
                ScHubScreen.info = info;
                if (open && client.currentScreen == null) client.setScreen(new ScHubScreen(hub));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(ScNet.RAIL_SHOT, (client, handler, buf, sender) -> {
            buf.readBlockPos();
            Vec3d from = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            Vec3d to = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            client.execute(() -> ScFx.shot(from, to));
        });
        ClientPlayNetworking.registerGlobalReceiver(ScNet.STRIKE, (client, handler, buf, sender) -> {
            int id = buf.readVarInt();
            Vec3d t = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            boolean mine = buf.readBoolean();
            client.execute(() -> {
                ScFx.strike(id, t, mine);
                if (mine) ScSounds.strikeSequence(client);
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> ScFx.clear());
        ClientTickEvents.END_CLIENT_TICK.register(ScClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ScFx::render);
        HudRenderCallback.EVENT.register(ScCine::hud);
    }

    private static void tick(MinecraftClient mc) {
        if (mc.isPaused()) return;
        ticks++;
        ScFx.tick(mc);
        ScCine.tick(mc);
        ScSounds.tick(mc);
    }
}
