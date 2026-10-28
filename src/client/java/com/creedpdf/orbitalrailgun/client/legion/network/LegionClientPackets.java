package com.creedpdf.orbitalrailgun.client.legion.network;

import com.creedpdf.orbitalrailgun.client.legion.hud.SwarmHud;
import com.creedpdf.orbitalrailgun.client.legion.render.LegionFx;
import com.creedpdf.orbitalrailgun.legion.SwarmFormation;
import com.creedpdf.orbitalrailgun.legion.network.LegionPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Client side of {@link LegionPackets}: the HUD's copy of the swarm, effect bursts, and the key requests. */
public final class LegionClientPackets {
    private LegionClientPackets() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(LegionPackets.SYNC, (client, handler, buf, sender) -> {
            int cap = buf.readVarInt();
            boolean active = buf.readBoolean();
            SwarmFormation f = SwarmFormation.byId(buf.readVarInt());
            int fury = buf.readVarInt();
            int n = buf.readVarInt();
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                buf.readVarInt();
                stacks.add(buf.readItemStack());
            }
            client.execute(() -> SwarmHud.update(cap, active, f, fury, stacks));
        });
        ClientPlayNetworking.registerGlobalReceiver(LegionPackets.FX, (client, handler, buf, sender) -> {
            byte kind = buf.readByte();
            Vec3d at = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            Vec3d dir = new Vec3d(buf.readFloat(), buf.readFloat(), buf.readFloat());
            int src = buf.readVarInt();
            client.execute(() -> LegionFx.burst(kind, at, dir, src));
        });
    }

    private static void send(Identifier id) {
        ClientPlayNetworking.send(id, PacketByteBufs.empty());
    }

    public static void sendToggle() {
        send(LegionPackets.TOGGLE);
    }

    public static void sendFormation() {
        send(LegionPackets.FORMATION);
    }

    public static void sendStorm() {
        send(LegionPackets.STORM);
    }
}
