package com.creedpdf.orbitalrailgun.krl;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The fire-control map: a terrain survey of the ground within range of a console - surface height and land type on a
 * grid of {@link #N} x {@link #N} cells of {@link #CELL} blocks, centred on the console. Chunks the server has loaded
 * are read directly; the rest of the 4 km square comes from the world generator (the same answer the terrain will
 * have when it generates), worked out off the server thread.
 */
public final class KrlMap {
    private KrlMap() {}

    public static final int N = 168, CELL = 25;
    /** Land types. */
    public static final byte LAND = 0, OCEAN = 1, DEEP = 2, RIVER = 3, BEACH = 4, DESERT = 5, FOREST = 6, TAIGA = 7, JUNGLE = 8, SNOW = 9,
            MOUNTAIN = 10, SWAMP = 11, BADLANDS = 12, SAVANNA = 13, PLAINS = 14, CHERRY = 15, MUSHROOM = 16, NETHER = 17, END = 18;

    public record Survey(int cx, int cz, short[] height, byte[] kind, long made) {}

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KRL terrain survey");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private record Key(RegistryKey<World> world, long pos) {}
    private static final Map<Key, Survey> CACHE = new HashMap<>();
    private static final Map<Key, Boolean> RUNNING = new HashMap<>();
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

    /** Send the console's survey to the player, surveying first if it is missing or stale. */
    public static void request(ServerPlayerEntity p, BlockPos console) {
        ServerWorld w = p.getServerWorld();
        Key k = new Key(w.getRegistryKey(), console.asLong());
        Survey s = CACHE.get(k);
        long now = w.getTime();
        if (s != null && now - s.made < FRESH && now >= s.made) {
            KrlNet.sendMap(p, console, s);
            return;
        }
        UUID who = p.getUuid();
        if (RUNNING.containsKey(k)) {
            PENDING.computeIfAbsent(k, kk -> new java.util.HashSet<>()).add(who);
            return;
        }
        RUNNING.put(k, true);
        PENDING.computeIfAbsent(k, kk -> new java.util.HashSet<>()).add(who);
        int cx = console.getX(), cz = console.getZ();
        int x0 = cx - N * CELL / 2, z0 = cz - N * CELL / 2;
        short[] h = new short[N * N];
        byte[] kind = new byte[N * N];
        boolean[] done = new boolean[N * N];
        // loaded chunks first, on the server thread
        for (int j = 0; j < N; j++)
            for (int i = 0; i < N; i++) {
                int x = x0 + i * CELL + CELL / 2, z = z0 + j * CELL + CELL / 2;
                WorldChunk c = w.getChunkManager().getWorldChunk(x >> 4, z >> 4);
                if (c == null) continue;
                int y = c.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                int ws = c.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
                h[j * N + i] = (short) y;
                byte kd = kind(w.getBiome(new BlockPos(x, y, z)));
                if (!c.getFluidState(new BlockPos(x, ws - 1, z)).isEmpty() && kd != OCEAN && kd != DEEP && kd != RIVER && kd != SWAMP) kd = RIVER;
                kind[j * N + i] = kd;
                done[j * N + i] = true;
            }
        ChunkGenerator g = w.getChunkManager().getChunkGenerator();
        NoiseConfig nc = w.getChunkManager().getNoiseConfig();
        var biomes = g.getBiomeSource();
        var sampler = nc.getMultiNoiseSampler();
        int sea = w.getSeaLevel();
        net.minecraft.server.MinecraftServer server = w.getServer();
        POOL.execute(() -> {
            try {
                for (int j = 0; j < N; j++)
                    for (int i = 0; i < N; i++) {
                        int idx = j * N + i;
                        if (done[idx]) continue;
                        int x = x0 + i * CELL + CELL / 2, z = z0 + j * CELL + CELL / 2;
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
            Survey s2 = new Survey(cx, cz, h, kind, now);
            server.execute(() -> {
                RUNNING.remove(k);
                CACHE.put(k, s2);
                var who2 = PENDING.remove(k);
                if (who2 == null) return;
                for (UUID u : who2) {
                    ServerPlayerEntity sp = server.getPlayerManager().getPlayer(u);
                    if (sp != null && sp.getWorld().getRegistryKey() == k.world) KrlNet.sendMap(sp, console, s2);
                }
            });
        });
    }

    private static final Map<Key, java.util.Set<UUID>> PENDING = new HashMap<>();

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
        RUNNING.clear();
        PENDING.clear();
    }

    @SuppressWarnings("unused")
    private static ChunkPos chunk(int x, int z) {
        return new ChunkPos(x >> 4, z >> 4);
    }
}
