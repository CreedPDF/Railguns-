package com.creedpdf.orbitalrailgun.client.network;

import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.network.ModPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
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
    }

    public static void sendDeployRequest(BlockPos target) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(target);
        ClientPlayNetworking.send(ModPackets.REQUEST_DEPLOY, buf);
    }

    public static void sendSetTarget(int entityId, BlockPos target) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entityId);
        buf.writeBlockPos(target);
        ClientPlayNetworking.send(ModPackets.SET_TARGET, buf);
    }

    public static void sendStartCharge(int entityId) { sendEntityCommand(ModPackets.START_CHARGE, entityId); }
    public static void sendAbort(int entityId) { sendEntityCommand(ModPackets.ABORT, entityId); }
    public static void sendRecall(int entityId) { sendEntityCommand(ModPackets.RECALL, entityId); }

    private static void sendEntityCommand(net.minecraft.util.Identifier channel, int entityId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entityId);
        ClientPlayNetworking.send(channel, buf);
    }
}
