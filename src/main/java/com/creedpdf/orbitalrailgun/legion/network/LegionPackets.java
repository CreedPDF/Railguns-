package com.creedpdf.orbitalrailgun.legion.network;

import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.creedpdf.orbitalrailgun.legion.ArsenalLegion;
import com.creedpdf.orbitalrailgun.legion.capability.IWeaponSwarm;
import com.creedpdf.orbitalrailgun.legion.capability.SwarmCapability;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;

/**
 * Arsenal Legion networking.
 * <pre>
 *  S2C  arsenal_legion:swarm_sync    varint capacity, bool deployed, varint n, n x (varint slot, ItemStack)
 *                                    the owner's own swarm, for their HUD (sent when it changes)
 *  C2S  arsenal_legion:swarm_toggle  (empty)   stow / redeploy the swarm (the keybind)
 * </pre>
 * Everything else the client sees (the weapons, their states) travels with the entities themselves.
 */
public final class LegionPackets {
    private LegionPackets() {}

    public static final Identifier SYNC = ArsenalLegion.id("swarm_sync"), TOGGLE = ArsenalLegion.id("swarm_toggle");

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(TOGGLE, (server, p, handler, buf, sender) -> server.execute(() -> {
            IWeaponSwarm swarm = SwarmCapability.of(p);
            if (swarm.count() == 0) {
                p.sendMessage(KrlText.t("NO WEAPONS BOUND  //  USE THEM ON A LEGION ALTAR", KrlText.GREY), true);
                return;
            }
            swarm.setActive(!swarm.isActive());
            p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), ArsenalLegion.WEAPON_LAUNCH, SoundCategory.PLAYERS, 0.7f, swarm.isActive() ? 1.25f : 0.7f);
            p.sendMessage(KrlText.t(swarm.isActive() ? "LEGION DEPLOYED" : "LEGION STOWED", swarm.isActive() ? KrlText.AMBER : KrlText.GREY), true);
        }));
    }

    public static void sendSync(ServerPlayerEntity p, IWeaponSwarm swarm, int cap) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(cap);
        buf.writeBoolean(swarm.isActive());
        int n = 0;
        for (int i = 0; i < IWeaponSwarm.MAX_SLOTS; i++) if (!swarm.get(i).isEmpty()) n++;
        buf.writeVarInt(n);
        for (int i = 0; i < IWeaponSwarm.MAX_SLOTS; i++) {
            ItemStack s = swarm.get(i);
            if (s.isEmpty()) continue;
            buf.writeVarInt(i);
            buf.writeItemStack(s);
        }
        ServerPlayNetworking.send(p, SYNC, buf);
    }
}
