package com.creedpdf.orbitalrailgun.legion.network;

import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.creedpdf.orbitalrailgun.legion.ArsenalLegion;
import com.creedpdf.orbitalrailgun.legion.SwarmManager;
import com.creedpdf.orbitalrailgun.legion.capability.IWeaponSwarm;
import com.creedpdf.orbitalrailgun.legion.capability.SwarmCapability;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * Arsenal Legion networking.
 * <pre>
 *  S2C  arsenal_legion:swarm_sync    varint capacity, bool deployed, varint formation, varint fury, varint n,
 *                                    n x (varint slot, ItemStack)      the owner's own swarm, for their HUD
 *  S2C  arsenal_legion:swarm_fx      byte kind, double x, y, z, float dx, dy, dz, varint source entity
 *                                    a burst to draw: pierce, execution, parry, storm, formation shift
 *  C2S  arsenal_legion:swarm_toggle    (empty)   stow / redeploy the swarm
 *  C2S  arsenal_legion:swarm_formation (empty)   next formation
 *  C2S  arsenal_legion:swarm_storm     (empty)   unleash a Blade Storm (needs full Fury)
 * </pre>
 * The weapons themselves (and their states, formation and storm timing) travel with the entities.
 */
public final class LegionPackets {
    private LegionPackets() {}

    public static final Identifier SYNC = ArsenalLegion.id("swarm_sync"), FX = ArsenalLegion.id("swarm_fx"), TOGGLE = ArsenalLegion.id("swarm_toggle"),
            FORMATION = ArsenalLegion.id("swarm_formation"), STORM = ArsenalLegion.id("swarm_storm");
    public static final byte FX_PIERCE = 0, FX_EXECUTE = 1, FX_PARRY = 2, FX_STORM = 3, FX_FORMATION = 4;

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
        ServerPlayNetworking.registerGlobalReceiver(FORMATION, (server, p, handler, buf, sender) -> server.execute(() -> SwarmManager.cycleFormation(p)));
        ServerPlayNetworking.registerGlobalReceiver(STORM, (server, p, handler, buf, sender) -> server.execute(() -> SwarmManager.storm(p)));
    }

    public static void sendSync(ServerPlayerEntity p, IWeaponSwarm swarm, int cap) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(cap);
        buf.writeBoolean(swarm.isActive());
        buf.writeVarInt(swarm.formation().ordinal());
        buf.writeVarInt(swarm.fury());
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

    /** A burst for every client within 96 blocks to draw. */
    public static void fx(ServerWorld w, byte kind, Vec3d at, Vec3d dir, int source) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(at) > 96 * 96) continue;
            PacketByteBuf b = PacketByteBufs.create();
            b.writeByte(kind);
            b.writeDouble(at.x);
            b.writeDouble(at.y);
            b.writeDouble(at.z);
            b.writeFloat((float) dir.x);
            b.writeFloat((float) dir.y);
            b.writeFloat((float) dir.z);
            b.writeVarInt(source);
            ServerPlayNetworking.send(p, FX, b);
        }
    }
}
