package com.creedpdf.orbitalrailgun.util;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Holds orbital drops that have been confirmed but not yet released (the HUD countdown phase).
 * Runs entirely on the server thread via END_WORLD_TICK, so no synchronization is needed.
 */
public final class DeploymentScheduler {
    private DeploymentScheduler() {}

    private record PendingDrop(RegistryKey<World> world, BlockPos landing, UUID owner, int releaseAtTick) {}

    private static final List<PendingDrop> PENDING = new ArrayList<>();
    private static int serverTicks;

    public static void schedule(ServerWorld world, BlockPos landing, UUID owner, int delayTicks) {
        PENDING.add(new PendingDrop(world.getRegistryKey(), landing.toImmutable(), owner, serverTicks + delayTicks));
    }

    public static boolean hasPending(UUID owner) {
        for (PendingDrop drop : PENDING) {
            if (drop.owner.equals(owner)) return true;
        }
        return false;
    }

    public static void tick(ServerWorld world) {
        // END_WORLD_TICK fires once per dimension; advance the shared clock only for the overworld
        // so countdowns run at real time regardless of how many dimensions are loaded.
        if (world.getRegistryKey() == World.OVERWORLD) serverTicks++;
        if (PENDING.isEmpty()) return;

        Iterator<PendingDrop> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingDrop drop = it.next();
            if (drop.world != world.getRegistryKey() || serverTicks < drop.releaseAtTick) continue;
            it.remove();
            release(world, drop);
        }
    }

    private static void release(ServerWorld world, PendingDrop drop) {
        BlockPos landing = drop.landing;
        world.getChunk(landing); // make sure the landing chunk is loaded before spawning into it

        double spawnY = Math.min(landing.getY() + RailgunConfig.DROP_HEIGHT, world.getTopY() - 6);
        RailgunEntity railgun = RailgunMod.RAILGUN.create(world);
        if (railgun == null) return;

        railgun.refreshPositionAndAngles(landing.getX() + 0.5, spawnY, landing.getZ() + 0.5, 0f, 0f);
        railgun.setOwnerUuid(drop.owner);
        railgun.setState(RailgunState.FALLING);
        railgun.setVelocity(0, -RailgunConfig.DROP_START_SPEED, 0);
        world.spawnEntity(railgun);
    }

    public static void clear() {
        PENDING.clear();
        serverTicks = 0;
    }
}
