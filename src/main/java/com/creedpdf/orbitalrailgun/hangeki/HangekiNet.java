package com.creedpdf.orbitalrailgun.hangeki;

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
 * HANGEKI networking.
 * <pre>
 *  C2S  hangeki_parry   (empty)                      the parry key
 *  S2C  hangeki_scale   float                        how fast time runs (everyone)
 *  S2C  hangeki_meter   byte kind (-1 none), float closeness 0..1, string label, varint count, bool slowed
 *                                                    the threat meter of the sword-bearer
 *  S2C  hangeki_state   varint player, byte phase, long start (world time), varint ticks, vec3 focus, varint track+1
 *                                                    a duelist's stance (for the cutscene and the pose others see)
 *  S2C  hangeki_fx      byte kind, vec3 at, vec3 dir, vec3 to, varint player, varint entity, float speed, varint extra
 *                                                    a burst to draw
 * </pre>
 */
public final class HangekiNet {
    private HangekiNet() {}

    public static final Identifier PARRY = RailgunMod.id("hangeki_parry"), SCALE = RailgunMod.id("hangeki_scale"),
            METER = RailgunMod.id("hangeki_meter"), STATE = RailgunMod.id("hangeki_state"), FX = RailgunMod.id("hangeki_fx");

    public static final byte PH_IDLE = 0, PH_SLOW = 1, PH_STANCE = 2, PH_GUARD = 3, PH_RELEASE = 4;
    /** fx kinds */
    public static final byte FX_REFLECT = 0, FX_REFLECT_QUICK = 1, FX_COUNTER = 2, FX_BEAM = 3, FX_SONIC = 4, FX_MISSILE = 5,
            FX_MISSILE_HOLD = 6, FX_GUARD = 7, FX_FAIL = 8, FX_BEAM_HOLD = 9;
    /** meter kinds */
    public static final byte K_NONE = -1, K_PROJECTILE = 0, K_MISSILE = 1, K_BEAM = 2, K_SONIC = 3;

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(PARRY, (server, p, handler, buf, sender) -> server.execute(() -> ParryServer.press(p)));
    }

    public static void sendScale(MinecraftServer server, float scale) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PacketByteBuf b = PacketByteBufs.create();
            b.writeFloat(scale);
            ServerPlayNetworking.send(p, SCALE, b);
        }
    }

    public static void sendMeter(ServerPlayerEntity p, byte kind, float closeness, String label, int count, boolean slowed) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeByte(kind);
        b.writeFloat(closeness);
        b.writeString(label);
        b.writeVarInt(count);
        b.writeBoolean(slowed);
        ServerPlayNetworking.send(p, METER, b);
    }

    public static void sendState(ServerPlayerEntity who, byte phase, int ticks, Vec3d focus, int track) {
        ServerWorld w = who.getServerWorld();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p != who && p.squaredDistanceTo(who) > 128 * 128) continue;
            PacketByteBuf b = PacketByteBufs.create();
            b.writeVarInt(who.getId());
            b.writeByte(phase);
            b.writeLong(w.getTime());
            b.writeVarInt(ticks);
            b.writeDouble(focus.x);
            b.writeDouble(focus.y);
            b.writeDouble(focus.z);
            b.writeVarInt(track + 1);
            ServerPlayNetworking.send(p, STATE, b);
        }
    }

    public static void fx(ServerWorld w, byte kind, Vec3d at, Vec3d dir, Vec3d to, int player, int entity, float speed, int extra) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(at) > 160 * 160 && p.squaredDistanceTo(to) > 160 * 160) continue;
            PacketByteBuf b = PacketByteBufs.create();
            b.writeByte(kind);
            vec(b, at);
            vec(b, dir);
            vec(b, to);
            b.writeVarInt(player);
            b.writeVarInt(entity);
            b.writeFloat(speed);
            b.writeVarInt(extra);
            ServerPlayNetworking.send(p, FX, b);
        }
    }

    private static void vec(PacketByteBuf b, Vec3d v) {
        b.writeDouble(v.x);
        b.writeDouble(v.y);
        b.writeDouble(v.z);
    }
}
