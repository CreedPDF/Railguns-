package com.creedpdf.orbitalrailgun.client.network;

import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.client.tablet.TabletStatus;
import com.creedpdf.orbitalrailgun.network.ModPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Client half of {@link ModPackets}: S2C receivers and typed C2S senders. */
public final class ClientPackets {
    private ClientPackets() {}

    public static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.DROP_COUNTDOWN, (client, handler, buf, sender) -> {
            BlockPos landing = buf.readBlockPos();
            int ticks = buf.readVarInt();
            client.execute(() -> RailgunHud.startCountdown(landing, ticks));
        });
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.TABLET_INFO, (client, handler, buf, sender) -> {
            TabletStatus status = TabletStatus.read(buf);
            client.execute(() -> TabletStatus.latest = status);
        });
    }

    public static void sendDeployRequest(BlockPos target) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(target);
        ClientPlayNetworking.send(ModPackets.REQUEST_DEPLOY, buf);
    }

    public static void sendTarget(BlockPos target) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(target);
        ClientPlayNetworking.send(ModPackets.TABLET_TARGET, buf);
    }

    public static void requestStatus() { sendEmpty(ModPackets.TABLET_STATUS); }
    public static void sendFire() { sendEmpty(ModPackets.TABLET_FIRE); }
    public static void sendAbort() { sendEmpty(ModPackets.TABLET_ABORT); }

    private static void sendEmpty(Identifier channel) {
        ClientPlayNetworking.send(channel, PacketByteBufs.empty());
    }
}
