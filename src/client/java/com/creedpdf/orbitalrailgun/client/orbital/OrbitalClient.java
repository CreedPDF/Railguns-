package com.creedpdf.orbitalrailgun.client.orbital;

import com.creedpdf.orbitalrailgun.client.render.RemoteBeams;
import com.creedpdf.orbitalrailgun.orbital.OrbitalPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Client half of orbital mode: the last status snapshot, receivers and typed requests. */
public final class OrbitalClient {
    private OrbitalClient() {}

    /** Mirrors OrbitalState.Phase. */
    public enum Phase { STOWED, DEPLOYING, DEPLOYED, STOWING }

    public static final class Status {
        public Phase phase = Phase.STOWED;
        public int phaseTicks;
        public int loadout;
        public int readyIn;
        public boolean busy;
        public boolean depot;
        public long receivedAt;

        /** Ticks into the current phase, extrapolated since the snapshot arrived. */
        public float phaseTicksNow(float tickDelta) {
            MinecraftClient mc = MinecraftClient.getInstance();
            long now = mc.world == null ? receivedAt : mc.world.getTime();
            return phaseTicks + (now - receivedAt) + tickDelta;
        }

        public int readyInNow() {
            MinecraftClient mc = MinecraftClient.getInstance();
            long now = mc.world == null ? receivedAt : mc.world.getTime();
            return (int) Math.max(0, readyIn - (now - receivedAt));
        }
    }

    public static Status status = new Status();
    public static boolean statusKnown;

    public static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(OrbitalPackets.STATUS, (client, handler, buf, sender) -> {
            Status s = new Status();
            int ph = buf.readVarInt();
            s.phase = Phase.values()[Math.max(0, Math.min(Phase.values().length - 1, ph))];
            s.phaseTicks = buf.readVarInt();
            s.loadout = buf.readVarInt();
            s.readyIn = buf.readVarInt();
            s.busy = buf.readBoolean();
            s.depot = buf.readBoolean();
            client.execute(() -> {
                s.receivedAt = client.world == null ? 0 : client.world.getTime();
                status = s;
                statusKnown = true;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(OrbitalPackets.FIRE, (client, handler, buf, sender) -> {
            BlockPos target = buf.readBlockPos();
            int loadout = buf.readVarInt();
            long serverTime = buf.readLong();
            client.execute(() -> OrbitalScreen.onFire(target, loadout));
        });
        ClientPlayNetworking.registerGlobalReceiver(OrbitalPackets.END, (client, handler, buf, sender) ->
                client.execute(StrikeCinematicScreen::onEnd));
        ClientPlayNetworking.registerGlobalReceiver(OrbitalPackets.ATTACH, (client, handler, buf, sender) -> {
            int old = buf.readVarInt();
            int now = buf.readVarInt();
            boolean deployFirst = buf.readBoolean();
            client.execute(() -> OrbitalScreen.openAttach(old, now, deployFirst));
        });
        ClientPlayNetworking.registerGlobalReceiver(OrbitalPackets.BEAM, (client, handler, buf, sender) -> {
            Vec3d muzzle = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            BlockPos target = buf.readBlockPos();
            int loadout = buf.readVarInt();
            int travel = buf.readVarInt();
            client.execute(() -> {
                if (client.world != null) RemoteBeams.addOrbital(client.world, muzzle, target, loadout, travel);
            });
        });
    }

    public static void send(OrbitalPackets.Action action) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(action.ordinal());
        ClientPlayNetworking.send(OrbitalPackets.ACTION, buf);
    }

    public static void fire(int x, int z) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(OrbitalPackets.Action.FIRE.ordinal());
        buf.writeVarInt(x);
        buf.writeVarInt(z);
        ClientPlayNetworking.send(OrbitalPackets.ACTION, buf);
    }

    public static void callDepot(BlockPos ground) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(OrbitalPackets.Action.CALL_DEPOT.ordinal());
        buf.writeBlockPos(ground);
        ClientPlayNetworking.send(OrbitalPackets.ACTION, buf);
    }

    public static void depotSend(int entityId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entityId);
        ClientPlayNetworking.send(OrbitalPackets.DEPOT_SEND, buf);
    }
}
