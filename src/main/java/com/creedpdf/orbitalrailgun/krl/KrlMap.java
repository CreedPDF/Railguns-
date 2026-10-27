package com.creedpdf.orbitalrailgun.krl;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The fire-control map. Two kinds of terrain survey, both grids of surface height, land type and (where the server has
 * the chunk loaded) the real map colour of the top block:
 * <ul>
 *   <li>the overview: {@link #N} x {@link #N} cells of {@link #CELL} blocks centred on the console, out past the
 *   longest missile range;</li>
 *   <li>detail tiles: {@link #TN} x {@link #TN} cells of {@link #TCELL} blocks, a {@link #TILE}-block square each,
 *   sent when the map is zoomed in.</li>
 * </ul>
 * Chunks the server has loaded are read directly (on the server thread); the rest comes from the world generator, worked
 * out off the server thread.
 */
public final class KrlMap {
    private KrlMap() {}

    public static final int N = 256, CELL = 26;
    public static final int TN = 128, TCELL = 4, TILE = TN * TCELL;
    /** Land types. */
    public static final byte LAND = 0, OCEAN = 1, DEEP = 2, RIVER = 3, BEACH = 4, DESERT = 5, FOREST = 6, TAIGA = 7, JUNGLE = 8, SNOW = 9,
            MOUNTAIN = 10, SWAMP = 11, BADLANDS = 12, SAVANNA = 13, PLAINS = 14, CHERRY = 15, MUSHROOM = 16, NETHER = 17, END = 18;

    /** A survey: origin (north-west corner), size, cell size, and per cell height, land type and RGB (0 = unknown). */
    public record Survey(int x0, int z0, int n, int cell, short[] height, byte[] kind, int[] rgb, long made) {}

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KRL terrain survey");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private record Key(RegistryKey<World> world, long pos, boolean tile) {}

    private static final Map<Key, Survey> CACHE = new HashMap<>();
    private static final Map<Key, Set<UUID>> PENDING = new HashMap<>();
    private static final long FRESH = 20 * 60 * 5;

    /** The ground height (top solid surface) at x, z: the loaded chunk if there is one, else the generator's. */
    public static int surfaceY(ServerWorld w, int x, int z) {
        WorldChunk c = w.getChunkManager().getWorldChunk(x >> 4, z >> 4);
        if (c != null) return c.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        try {
            ChunkGenerator g = w.getChunkManager().getChunkGenerator();
            return g.getHeight(x, z, Heightmap.Type.OCEAN_FLOOR_WG, w, w.getChunkManager().getNoiseConfig()) - 1;
        } catch (Exception e) {
            return w.getSeaLevel();
        }
    }

    /** The overview centred on a console. */
    public static void request(ServerPlayerEntity p, BlockPos console) {
        int x0 = console.getX() - N * CELL / 2, z0 = console.getZ() - N * CELL / 2;
        survey(p, new Key(p.getWorld().getRegistryKey(), console.asLong(), false), x0, z0, N, CELL, console, false);
    }

    /** One detail tile (tile coordinates: x0 = tx * TILE). */
    public static void requestTile(ServerPlayerEntity p, BlockPos console, int tx, int tz) {
        BlockPos key = new BlockPos(tx, 0, tz);
        survey(p, new Key(p.getWorld().getRegistryKey(), key.asLong(), true), tx * TILE, tz * TILE, TN, TCELL, console, true);
    }

    private static void survey(ServerPlayerEntity p, Key k, int x0, int z0, int n, int cell, BlockPos console, boolean tile) {
        ServerWorld w = p.getServerWorld();
        long now = w.getTime();
        Survey s = CACHE.get(k);
        if (s != null && now - s.made() < FRESH && now >= s.made()) {
            KrlNet.sendMap(p, console, s, tile);
            return;
        }
        boolean running = PENDING.containsKey(k);
        PENDING.computeIfAbsent(k, kk -> new HashSet<>()).add(p.getUuid());
        if (running) return;
        short[] h = new short[n * n];
        byte[] kind = new byte[n * n];
        int[] rgb = new int[n * n];
        boolean[] done = new boolean[n * n];
        // loaded chunks first, on the server thread: real heights and the real top block's map colour
        BlockPos.Mutable bp = new BlockPos.Mutable();
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) {
                int x = x0 + i * cell + cell / 2, z = z0 + j * cell + cell / 2;
                WorldChunk c = w.getChunkManager().getWorldChunk(x >> 4, z >> 4);
                if (c == null) continue;
                int idx = j * n + i;
                int y = c.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                int ws = c.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
                h[idx] = (short) y;
                byte kd = kind(w.getBiome(bp.set(x, y, z)));
                BlockState top = c.getBlockState(bp.set(x, ws - 1, z));
                if (!c.getFluidState(bp).isEmpty() && kd != OCEAN && kd != DEEP && kd != RIVER && kd != SWAMP) kd = RIVER;
                kind[idx] = kd;
                MapColor mc = top.getMapColor(w, bp);
                rgb[idx] = mc == MapColor.CLEAR ? 0 : (0xFF000000 | mc.color);
                done[idx] = true;
            }
        ChunkGenerator g = w.getChunkManager().getChunkGenerator();
        NoiseConfig nc = w.getChunkManager().getNoiseConfig();
        var biomes = g.getBiomeSource();
        var sampler = nc.getMultiNoiseSampler();
        int sea = w.getSeaLevel();
        MinecraftServer server = w.getServer();
        POOL.execute(() -> {
            try {
                for (int j = 0; j < n; j++)
                    for (int i = 0; i < n; i++) {
                        int idx = j * n + i;
                        if (done[idx]) continue;
                        int x = x0 + i * cell + cell / 2, z = z0 + j * cell + cell / 2;
                        int y;
                        try {
                            y = g.getHeight(x, z, Heightmap.Type.OCEAN_FLOOR_WG, w, nc);
                        } catch (Exception e) {
                            y = sea;
                        }
                        h[idx] = (short) y;
                        kind[idx] = kind(biomes.getBiome(BiomeCoords.fromBlock(x), BiomeCoords.fromBlock(Math.max(y, sea)), BiomeCoords.fromBlock(z), sampler));
                    }
            } catch (Throwable t) {
                RailgunMod.LOGGER.warn("KRL: terrain survey failed", t);
            }
            Survey s2 = new Survey(x0, z0, n, cell, h, kind, rgb, now);
            server.execute(() -> {
                CACHE.put(k, s2);
                if (CACHE.size() > 256) CACHE.clear();
                Set<UUID> who = PENDING.remove(k);
                if (who == null) return;
                for (UUID u : who) {
                    ServerPlayerEntity sp = server.getPlayerManager().getPlayer(u);
                    if (sp != null && sp.getWorld().getRegistryKey() == k.world()) KrlNet.sendMap(sp, console, s2, tile);
                }
            });
        });
    }

    static byte kind(RegistryEntry<Biome> b) {
        if (b.isIn(BiomeTags.IS_NETHER)) return NETHER;
        if (b.isIn(BiomeTags.IS_END)) return END;
        if (b.isIn(BiomeTags.IS_DEEP_OCEAN)) return DEEP;
        if (b.isIn(BiomeTags.IS_OCEAN)) return OCEAN;
        if (b.isIn(BiomeTags.IS_RIVER)) return RIVER;
        if (b.isIn(BiomeTags.IS_BEACH)) return BEACH;
        if (b.isIn(BiomeTags.IS_BADLANDS)) return BADLANDS;
        if (b.isIn(BiomeTags.IS_JUNGLE)) return JUNGLE;
        if (b.isIn(BiomeTags.IS_SAVANNA)) return SAVANNA;
        String n = b.getKey().map(k -> k.getValue().getPath()).orElse("");
        if (n.contains("mushroom")) return MUSHROOM;
        if (n.contains("cherry")) return CHERRY;
        if (n.contains("frozen") || n.contains("snowy") || n.contains("ice") || n.contains("grove") || n.contains("peaks")) return SNOW;
        if (n.contains("desert")) return DESERT;
        if (n.contains("swamp") || n.contains("mangrove")) return SWAMP;
        if (b.isIn(BiomeTags.IS_TAIGA)) return TAIGA;
        if (b.isIn(BiomeTags.IS_MOUNTAIN) || n.contains("stony") || n.contains("windswept")) return MOUNTAIN;
        if (b.isIn(BiomeTags.IS_FOREST)) return FOREST;
        if (n.contains("plains") || n.contains("meadow")) return PLAINS;
        return LAND;
    }

    public static void clear() {
        CACHE.clear();
        PENDING.clear();
    }
}
