package com.creedpdf.orbitalrailgun.rift;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import qouteall.imm_ptl.core.api.PortalAPI;

import java.util.ArrayList;

/**
 * Legacy: the v3.5 rift dimension. The rift now opens into real space (see the space package),
 * so the dimension is kept only so existing worlds still load, and anyone found inside it is
 * sent back to the overworld spawn.
 */
public final class RiftDimension {
    private RiftDimension() {}

    public static final RegistryKey<World> KEY = RegistryKey.of(RegistryKeys.WORLD, RailgunMod.id("rift"));

    public static void tick(ServerWorld world) {
        if (world.getRegistryKey() != KEY || world.getTime() % 20 != 0) return;
        ServerWorld home = world.getServer().getOverworld();
        BlockPos s = home.getSpawnPos();
        for (ServerPlayerEntity p : new ArrayList<>(world.getPlayers())) {
            PortalAPI.teleportEntity(p, home, new net.minecraft.util.math.Vec3d(s.getX() + 0.5, s.getY() + 1, s.getZ() + 0.5));
        }
    }
}
