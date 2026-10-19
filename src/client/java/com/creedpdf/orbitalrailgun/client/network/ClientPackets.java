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
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.SURVEY_RESULT, (client, handler, buf, sender) -> {
            BlockPos ground = buf.readBlockPos();
            boolean ok = buf.readBoolean();
            String reason = buf.readString();
            BlockPos obstacle = buf.readBlockPos();
            client.execute(() -> TabletStatus.survey = new TabletStatus.Survey(ground, ok, reason, obstacle, System.currentTimeMillis()));
        });
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.BEAM_SHOT, (client, handler, buf, sender) -> {
            int id = buf.readVarInt();
            net.minecraft.util.math.Vec3d muzzle = new net.minecraft.util.math.Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            BlockPos target = buf.readBlockPos();
            int loadout = buf.readVarInt();
            client.execute(() -> {
                if (client.world != null) com.creedpdf.orbitalrailgun.client.render.RemoteBeams.add(client.world, id, muzzle, target, loadout);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.SCAN_RESULT, (client, handler, buf, sender) -> {
            net.minecraft.util.math.Vec3d head = new net.minecraft.util.math.Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            double groundY = buf.readDouble();
            int n = buf.readVarInt();
            long[] pos = new long[n];
            byte[] kind = new byte[n];
            for (int i = 0; i < n; i++) {
                pos[i] = buf.readLong();
                kind[i] = buf.readByte();
            }
            int m = buf.readVarInt();
            int[] mobs = new int[m];
            for (int i = 0; i < m; i++) mobs[i] = buf.readVarInt();
            client.execute(() -> {
                if (client.world != null) com.creedpdf.orbitalrailgun.client.render.ScanFx.add(client.world, head, groundY, pos, kind, mobs);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.STAR_FLARE, (client, handler, buf, sender) -> {
            net.minecraft.util.math.Vec3d a = new net.minecraft.util.math.Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            net.minecraft.util.math.Vec3d b = new net.minecraft.util.math.Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            client.execute(() -> {
                if (client.world != null) com.creedpdf.orbitalrailgun.client.render.StarFx.flare(client.world, a, b);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.TABLET_INFO, (client, handler, buf, sender) -> {
            TabletStatus status = TabletStatus.read(buf);
            client.execute(() -> TabletStatus.latest = status);
        });
    }

    public static void sendIgnite() {
        ClientPlayNetworking.send(ModPackets.TABLET_IGNITE, PacketByteBufs.create());
    }

    public static void sendScan() {
        ClientPlayNetworking.send(ModPackets.TABLET_SCAN, PacketByteBufs.create());
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

    public static void sendPanelAction(int entityId, com.creedpdf.orbitalrailgun.entity.RailgunEntity.PanelAction action) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entityId);
        buf.writeVarInt(action.ordinal());
        ClientPlayNetworking.send(ModPackets.PANEL_ACTION, buf);
    }

    public static void sendLoadoutOrder(int entityId, int loadout) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entityId);
        buf.writeVarInt(loadout);
        ClientPlayNetworking.send(ModPackets.PANEL_LOADOUT, buf);
    }

    public static void requestSurvey(BlockPos ground) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(ground);
        ClientPlayNetworking.send(ModPackets.SURVEY, buf);
    }

    public static void requestStatus() { sendEmpty(ModPackets.TABLET_STATUS); }
    public static void sendFire() { sendEmpty(ModPackets.TABLET_FIRE); }
    public static void sendAbort() { sendEmpty(ModPackets.TABLET_ABORT); }

    private static void sendEmpty(Identifier channel) {
        ClientPlayNetworking.send(channel, PacketByteBufs.empty());
    }
}
