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

    private record PendingDrop(RegistryKey<World> world, BlockPos landing, UUID owner, int releaseAtTick, int kind) {}

    private static final List<PendingDrop> PENDING = new ArrayList<>();
    private static int serverTicks;

    public static void schedule(ServerWorld world, BlockPos landing, UUID owner, int delayTicks) {
        schedule(world, landing, owner, delayTicks, 0);
    }

    /** kind 0 = ARES-9 railgun pod, 1 = ARES-0 planet killer. */
    public static void schedule(ServerWorld world, BlockPos landing, UUID owner, int delayTicks, int kind) {
        PENDING.add(new PendingDrop(world.getRegistryKey(), landing.toImmutable(), owner, serverTicks + delayTicks, kind));
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
        // Re-survey at release: the zone may have changed during the countdown.
        LandingSurvey.Result survey = LandingSurvey.survey(world, landing.down());
        if (!survey.ok()) {
            var player = world.getServer().getPlayerManager().getPlayer(drop.owner);
            if (player != null) {
                player.sendMessage(net.minecraft.text.Text.translatable("message.orbital_railgun.denied." + survey.reason())
                        .formatted(net.minecraft.util.Formatting.RED), false);
                player.playSound(com.creedpdf.orbitalrailgun.registry.ModSounds.UI_DENIED, net.minecraft.sound.SoundCategory.PLAYERS, 1f, 1f);
            }
            return;
        }

        double spawnY = Math.max(landing.getY() + 20, Math.min(landing.getY() + RailgunConfig.DROP_HEIGHT, world.getTopY() - 10));
        if (drop.kind == 1) {
            com.creedpdf.orbitalrailgun.entity.Ares0Entity ares = RailgunMod.ARES0.create(world);
            if (ares == null) return;
            ares.refreshPositionAndAngles(landing.getX() + 0.5, spawnY + 60, landing.getZ() + 0.5, 0f, 0f);
            ares.setOwnerUuid(drop.owner);
            ares.setLandY(landing.getY());
            ares.setVelocity(0, -RailgunConfig.DROP_START_SPEED, 0);
            world.spawnEntity(ares);
            world.playSound(null, landing.getX(), landing.getY() + 40, landing.getZ(),
                    com.creedpdf.orbitalrailgun.registry.ModSounds.RAILGUN_REENTRY, net.minecraft.sound.SoundCategory.BLOCKS, 16f, 0.6f);
            return;
        }
        RailgunEntity railgun = RailgunMod.RAILGUN.create(world);
        if (railgun == null) return;

        railgun.refreshPositionAndAngles(landing.getX() + 0.5, spawnY, landing.getZ() + 0.5, 0f, 0f);
        railgun.setOwnerUuid(drop.owner);
        railgun.setLandY(landing.getY());
        railgun.setState(RailgunState.FALLING);
        railgun.setVelocity(0, -RailgunConfig.DROP_START_SPEED, 0);
        world.spawnEntity(railgun);
    }

    public static void clear() {
        PENDING.clear();
        serverTicks = 0;
    }
}
