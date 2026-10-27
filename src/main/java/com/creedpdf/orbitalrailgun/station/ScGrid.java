package com.creedpdf.orbitalrailgun.station;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The command hubs currently loaded in each world, so modules can find the grid they belong to. */
public final class ScGrid {
    private ScGrid() {}

    private static final Map<RegistryKey<World>, Set<BlockPos>> HUBS = new HashMap<>();

    static void add(World w, BlockPos hub) {
        HUBS.computeIfAbsent(w.getRegistryKey(), k -> new HashSet<>()).add(hub.toImmutable());
    }

    static void remove(World w, BlockPos hub) {
        Set<BlockPos> s = HUBS.get(w.getRegistryKey());
        if (s != null) s.remove(hub);
    }

    public static Set<BlockPos> hubs(World w) {
        return HUBS.getOrDefault(w.getRegistryKey(), Set.of());
    }

    static void clear() {
        HUBS.clear();
    }

    /** The nearest loaded hub whose grid reaches {@code pos} (within {@link Sc#LINK} blocks on every axis). */
    @Nullable
    public static ScHubBlockEntity hubFor(World w, BlockPos pos) {
        ScHubBlockEntity best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos h : hubs(w)) {
            if (Math.abs(h.getX() - pos.getX()) > Sc.LINK || Math.abs(h.getY() - pos.getY()) > Sc.LINK || Math.abs(h.getZ() - pos.getZ()) > Sc.LINK)
                continue;
            double d = h.getSquaredDistance(pos);
            if (d < bd && w.isChunkLoaded(h) && w.getBlockEntity(h) instanceof ScHubBlockEntity hub && !hub.isRemoved()) {
                bd = d;
                best = hub;
            }
        }
        return best;
    }
}
