package com.creedpdf.orbitalrailgun.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GRS networking.
 * <pre>
 *  C2S  grs_fire     BlockPos target, varint power     the goggles' FIRE key -> validate, spawn the strike
 *  S2C  grs_status   varint code, varint cooldown      0 accepted, 1 not on Earth, 2 no goggles, 3 recharging, 4 out of range
 * </pre>
 */
public final class GrsNet {
    private GrsNet() {}

    public static final Identifier FIRE = RailgunMod.id("grs_fire");
    public static final Identifier STATUS = RailgunMod.id("grs_status");
    public static final int OK = 0, NOT_EARTH = 1, NO_GOGGLES = 2, RECHARGING = 3, OUT_OF_RANGE = 4;

    /** Game time at which each player may fire again. */
    private static final Map<UUID, Long> READY = new HashMap<>();

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(FIRE, (server, player, handler, buf, sender) -> {
            BlockPos target = buf.readBlockPos();
            int power = buf.readVarInt();
            server.execute(() -> fire(player, target, power));
        });
    }

    private static void fire(ServerPlayerEntity player, BlockPos target, int power) {
        ServerWorld w = player.getServerWorld();
        long now = w.getTime();
        if (!Grs.wearing(player)) {
            status(player, NO_GOGGLES, 0);
            return;
        }
        if (!Grs.earth(w)) {
            status(player, NOT_EARTH, 0);
            return;
        }
        long ready = READY.getOrDefault(player.getUuid(), 0L);
        if (now < ready) {
            status(player, RECHARGING, (int) (ready - now));
            return;
        }
        double dx = target.getX() + 0.5 - player.getX(), dz = target.getZ() + 0.5 - player.getZ();
        if (dx * dx + dz * dz > Grs.RANGE * Grs.RANGE || target.getY() < w.getBottomY() || target.getY() > w.getTopY()
                || !w.isChunkLoaded(ChunkSectionPos.getSectionCoord(target.getX()), ChunkSectionPos.getSectionCoord(target.getZ()))) {
            status(player, OUT_OF_RANGE, 0);
            return;
        }
        GungnirStrikeEntity e = new GungnirStrikeEntity(RailgunMod.GUNGNIR_STRIKE, w);
        e.setPosition(target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5);
        e.setup(power, player.getUuid());
        w.spawnEntity(e);
        int cd = Grs.IMPACT + Grs.COOLDOWN;
        READY.put(player.getUuid(), now + cd);
        status(player, OK, cd);
        RailgunMod.LOGGER.info("GRS: {} called Gungnir at {} (power {})", player.getName().getString(), target.toShortString(), Grs.clampYield(power));
    }

    private static void status(ServerPlayerEntity p, int code, int cooldown) {
        PacketByteBuf out = PacketByteBufs.create();
        out.writeVarInt(code);
        out.writeVarInt(cooldown);
        ServerPlayNetworking.send(p, STATUS, out);
    }

    public static void clear() {
        READY.clear();
    }
}
