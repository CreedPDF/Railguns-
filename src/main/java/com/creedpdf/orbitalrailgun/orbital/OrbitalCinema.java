package com.creedpdf.orbitalrailgun.orbital;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The firing player's side of a strike. While the beam-ride cinematic plays, the player is parked
 * as an invisible spectator above the target, so the server streams the target's chunks to them
 * (the client camera is driven by the cinematic, not by the player). At the end, on skip (J),
 * on disconnect or on server stop, they are put back exactly where and how they were.
 */
public final class OrbitalCinema {
    private OrbitalCinema() {}

    private static final class Session {
        final BlockPos target;
        int t;
        boolean skipped;
        boolean parked;
        RegistryKey<World> dim;
        Vec3d pos;
        float yaw, pitch;
        GameMode mode;
        boolean flying;

        Session(BlockPos target) {
            this.target = target;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static boolean busy(UUID player) {
        return SESSIONS.containsKey(player);
    }

    public static void begin(ServerPlayerEntity player, BlockPos target) {
        SESSIONS.put(player.getUuid(), new Session(target));
    }

    /** J pressed: the strike still lands, the player just gets their view back. */
    public static void skip(ServerPlayerEntity player) {
        Session s = SESSIONS.get(player.getUuid());
        if (s == null) return;
        s.skipped = true;
        restore(player, s);
        SESSIONS.remove(player.getUuid());
        OrbitalPackets.sendEnd(player);
    }

    public static void tick(MinecraftServer server) {
        for (Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Session> e = it.next();
            Session s = e.getValue();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            int now = s.t++;
            if (p == null) {
                it.remove();
                continue;
            }
            if (now == OrbitalConfig.PARK_T && !s.skipped) park(p, s);
            if (s.parked && now > OrbitalConfig.PARK_T) {
                // keep the spectator pinned above the target
                Vec3d spot = Vec3d.ofCenter(s.target).add(0, OrbitalConfig.PARK_HEIGHT, 0);
                if (p.getPos().squaredDistanceTo(spot) > 4.0) p.teleport(p.getServerWorld(), spot.x, spot.y, spot.z, p.getYaw(), p.getPitch());
            }
            if (now >= OrbitalConfig.CINE_END_T) {
                restore(p, s);
                it.remove();
                OrbitalPackets.sendEnd(p);
            }
        }
    }

    private static void park(ServerPlayerEntity p, Session s) {
        s.dim = p.getWorld().getRegistryKey();
        s.pos = p.getPos();
        s.yaw = p.getYaw();
        s.pitch = p.getPitch();
        s.mode = p.interactionManager.getGameMode();
        s.flying = p.getAbilities().flying;
        s.parked = true;
        p.changeGameMode(GameMode.SPECTATOR);
        Vec3d spot = Vec3d.ofCenter(s.target).add(0, OrbitalConfig.PARK_HEIGHT, 0);
        p.teleport(p.getServerWorld(), spot.x, spot.y, spot.z, p.getYaw(), 90f);
    }

    private static void restore(ServerPlayerEntity p, Session s) {
        if (!s.parked) return;
        s.parked = false;
        ServerWorld w = p.getServer().getWorld(s.dim);
        if (w == null) w = p.getServerWorld();
        p.teleport(w, s.pos.x, s.pos.y, s.pos.z, s.yaw, s.pitch);
        p.changeGameMode(s.mode == null ? GameMode.SURVIVAL : s.mode);
        if (s.flying && p.getAbilities().allowFlying) {
            p.getAbilities().flying = true;
            p.sendAbilitiesUpdate();
        }
        p.fallDistance = 0;
    }

    /** Disconnect / shutdown: put the player back before their data is saved. */
    public static void release(ServerPlayerEntity p) {
        Session s = SESSIONS.remove(p.getUuid());
        if (s != null) restore(p, s);
    }

    public static void releaseAll(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) release(p);
        SESSIONS.clear();
    }
}
