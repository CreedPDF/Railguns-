package com.creedpdf.orbitalrailgun.astraea;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * ASTRAEA networking.
 * <pre>
 *  C2S  astraea_key     (empty)                               the ability key
 *  S2C  astraea_ritual  varint player, long start, vec3 anchor   an Ascension begins (everyone nearby)
 *  S2C  astraea_rend    varint player                         it is over
 *  S2C  astraea_strike  varint player, long start, vec3 anchor, vec3 normal, double chasm angle   a throw (everyone)
 *  S2C  astraea_moment  long time                             Earth has just split (everyone in the overworld)
 *  S2C  astraea_split   the whole SplitState (see SplitState.sync)
 * </pre>
 */
public final class AstraeaNet {
    private AstraeaNet() {}

    public static final Identifier KEY = RailgunMod.id("astraea_key"), RITUAL = RailgunMod.id("astraea_ritual"),
            RITUAL_END = RailgunMod.id("astraea_rend"), STRIKE = RailgunMod.id("astraea_strike"), MOMENT = RailgunMod.id("astraea_moment"),
            SPLIT = RailgunMod.id("astraea_split");

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(KEY, (server, p, handler, buf, sender) -> server.execute(() -> AstraeaServer.press(p)));
    }

    private static void vec(PacketByteBuf b, Vec3d v) {
        b.writeDouble(v.x);
        b.writeDouble(v.y);
        b.writeDouble(v.z);
    }

    static void sendRitual(ServerWorld w, ServerPlayerEntity who, long start, Vec3d anchor) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p != who && p.squaredDistanceTo(who) > 256 * 256) continue;
            PacketByteBuf b = PacketByteBufs.create();
            b.writeVarInt(who.getId());
            b.writeLong(start);
            vec(b, anchor);
            ServerPlayNetworking.send(p, RITUAL, b);
        }
    }

    static void sendRitualEnd(ServerWorld w, ServerPlayerEntity who) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            PacketByteBuf b = PacketByteBufs.create();
            b.writeVarInt(who.getId());
            ServerPlayNetworking.send(p, RITUAL_END, b);
        }
    }

    static void sendStrike(ServerWorld w, ServerPlayerEntity who, long start, Vec3d anchor, Vec3d normal, double angle) {
        for (ServerPlayerEntity p : w.getServer().getPlayerManager().getPlayerList()) {
            PacketByteBuf b = PacketByteBufs.create();
            b.writeVarInt(p == who ? who.getId() : who.getId());
            b.writeBoolean(p == who);
            b.writeLong(start);
            vec(b, anchor);
            vec(b, normal);
            b.writeDouble(angle);
            ServerPlayNetworking.send(p, STRIKE, b);
        }
    }

    static void sendSplitMoment(MinecraftServer server, long time) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf b = PacketByteBufs.create();
            b.writeLong(time);
            ServerPlayNetworking.send(p, MOMENT, b);
        }
    }
}
