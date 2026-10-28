package com.creedpdf.orbitalrailgun.client.legion.network;

import com.creedpdf.orbitalrailgun.client.legion.hud.SwarmHud;
import com.creedpdf.orbitalrailgun.legion.network.LegionPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Client side of {@link LegionPackets}: the HUD's copy of the swarm, and the stow / deploy request. */
public final class LegionClientPackets {
    private LegionClientPackets() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(LegionPackets.SYNC, (client, handler, buf, sender) -> {
            int cap = buf.readVarInt();
            boolean active = buf.readBoolean();
            int n = buf.readVarInt();
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                buf.readVarInt();
                stacks.add(buf.readItemStack());
            }
            client.execute(() -> SwarmHud.update(cap, active, stacks));
        });
    }

    public static void sendToggle() {
        ClientPlayNetworking.send(LegionPackets.TOGGLE, PacketByteBufs.empty());
    }
}
