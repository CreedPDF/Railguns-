package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.krl.KrlMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Terrain surveys received from the server (see {@link KrlMap}): the overview round a console and the detail tiles,
 * each turned into a map picture - real block colours where the server knew them, natural land colours elsewhere,
 * hill-shaded from the north-west, water darkening with depth, and contour lines (fine every 8 m, bold every 40 m).
 */
public final class KrlSurvey {
    public final BlockPos console;
    public final boolean tile;
    public final int x0, z0, n, cell, sea;
    public final short[] height;
    public final byte[] kind;
    public final int[] rgb;
    private NativeImageBackedTexture tex;
    private Identifier texId;
    private static int nextTex;

    private static KrlSurvey last;
    private static final Map<Long, KrlSurvey> TILES = new HashMap<>();
    /** Tiles asked for and not yet arrived. */
    static final Set<Long> ASKED = new HashSet<>();

    private KrlSurvey(BlockPos console, boolean tile, int x0, int z0, int n, int cell, short[] height, byte[] kind, int[] rgb, int sea) {
        this.console = console;
        this.tile = tile;
        this.x0 = x0;
        this.z0 = z0;
        this.n = n;
        this.cell = cell;
        this.height = height;
        this.kind = kind;
        this.rgb = rgb;
        this.sea = sea;
    }

    public static KrlSurvey read(PacketByteBuf b) {
        boolean tile = b.readBoolean();
        BlockPos c = b.readBlockPos();
        int x0 = b.readInt(), z0 = b.readInt();
        int n = b.readVarInt(), cell = b.readVarInt();
        short[] h = new short[n * n];
        for (int i = 0; i < h.length; i++) h[i] = b.readShort();
        byte[] k = b.readByteArray();
        int[] rgb = new int[n * n];
        for (int i = 0; i < rgb.length; i++) {
            int v = b.readMedium() & 0xFFFFFF;
            rgb[i] = v == 0 ? 0 : 0xFF000000 | v;
        }
        int sea = b.readVarInt();
        return new KrlSurvey(c, tile, x0, z0, n, cell, h, k, rgb, sea);
    }

    public static long key(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    public static void put(KrlSurvey s) {
        if (s.tile) {
            int tx = Math.floorDiv(s.x0, KrlMap.TILE), tz = Math.floorDiv(s.z0, KrlMap.TILE);
            long k = key(tx, tz);
            ASKED.remove(k);
            KrlSurvey old = TILES.put(k, s);
            if (old != null) old.close();
            if (TILES.size() > 96) {
                Iterator<Map.Entry<Long, KrlSurvey>> it = TILES.entrySet().iterator();
                while (TILES.size() > 64 && it.hasNext()) {
                    Map.Entry<Long, KrlSurvey> e = it.next();
                    if (e.getKey() == k) continue;
                    e.getValue().close();
                    it.remove();
                }
            }
            return;
        }
        if (last != null) last.close();
        last = s;
    }

    @Nullable
    public static KrlSurvey get(BlockPos console) {
        return last != null && last.console.equals(console) ? last : null;
    }

    /** The newest overview, whichever console it came from. */
    @Nullable
    public static KrlSurvey latest() {
        return last;
    }

    @Nullable
    public static KrlSurvey tile(int tx, int tz) {
        return TILES.get(key(tx, tz));
    }

    public static void clear() {
        if (last != null) last.close();
        last = null;
        for (KrlSurvey t : TILES.values()) t.close();
        TILES.clear();
        ASKED.clear();
    }

    private void close() {
        if (tex != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(texId);
            tex = null;
        }
    }

    public boolean covers(double x, double z) {
        return x >= x0 && z >= z0 && x < x0 + n * cell && z < z0 + n * cell;
    }

    /** The best survey of a point: a detail tile if one has arrived, else the overview. */
    @Nullable
    public static KrlSurvey best(double x, double z) {
        KrlSurvey t = tile(Math.floorDiv((int) Math.floor(x), KrlMap.TILE), Math.floorDiv((int) Math.floor(z), KrlMap.TILE));
        if (t != null) return t;
        return last != null && last.covers(x, z) ? last : null;
    }

    /** Surface height at a world point (bilinear). */
    public double heightAt(double x, double z) {
        double fx = (x - x0) / cell - 0.5, fz = (z - z0) / cell - 0.5;
        int i = MathHelper.floor(fx), j = MathHelper.floor(fz);
        double u = fx - i, v = fz - j;
        return MathHelper.lerp(v, MathHelper.lerp(u, h(i, j), h(i + 1, j)), MathHelper.lerp(u, h(i, j + 1), h(i + 1, j + 1)));
    }

    public int h(int i, int j) {
        i = MathHelper.clamp(i, 0, n - 1);
        j = MathHelper.clamp(j, 0, n - 1);
        return height[j * n + i];
    }

    public int kindAt(double x, double z) {
        int i = MathHelper.clamp((int) ((x - x0) / cell), 0, n - 1), j = MathHelper.clamp((int) ((z - z0) / cell), 0, n - 1);
        return kind[j * n + i];
    }

    public int k(int i, int j) {
        i = MathHelper.clamp(i, 0, n - 1);
        j = MathHelper.clamp(j, 0, n - 1);
        return kind[j * n + i];
    }

    public static String kindName(int k) {
        return switch (k) {
            case KrlMap.OCEAN -> "OCEAN";
            case KrlMap.DEEP -> "DEEP OCEAN";
            case KrlMap.RIVER -> "RIVER / LAKE";
            case KrlMap.BEACH -> "SHORELINE";
            case KrlMap.DESERT -> "DESERT";
            case KrlMap.FOREST -> "FOREST";
            case KrlMap.TAIGA -> "TAIGA";
            case KrlMap.JUNGLE -> "JUNGLE";
            case KrlMap.SNOW -> "SNOW / ICE";
            case KrlMap.MOUNTAIN -> "HIGHLAND ROCK";
            case KrlMap.SWAMP -> "WETLAND";
            case KrlMap.BADLANDS -> "BADLANDS";
            case KrlMap.SAVANNA -> "SAVANNA";
            case KrlMap.PLAINS -> "GRASSLAND";
            case KrlMap.CHERRY -> "CHERRY GROVE";
            case KrlMap.MUSHROOM -> "MYCELIUM";
            case KrlMap.NETHER -> "NETHERRACK";
            case KrlMap.END -> "END STONE";
            default -> "MIXED TERRAIN";
        };
    }

    public static boolean wet(int k) {
        return k == KrlMap.OCEAN || k == KrlMap.DEEP || k == KrlMap.RIVER;
    }

    /** Natural colour of a land type (r, g, b 0..1), as seen from high altitude. */
    public static float[] natural(int k) {
        return switch (k) {
            case KrlMap.OCEAN -> new float[]{0.12f, 0.2f, 0.33f};
            case KrlMap.DEEP -> new float[]{0.07f, 0.13f, 0.25f};
            case KrlMap.RIVER -> new float[]{0.16f, 0.26f, 0.38f};
            case KrlMap.BEACH -> new float[]{0.76f, 0.71f, 0.53f};
            case KrlMap.DESERT -> new float[]{0.84f, 0.76f, 0.55f};
            case KrlMap.FOREST -> new float[]{0.2f, 0.36f, 0.15f};
            case KrlMap.TAIGA -> new float[]{0.22f, 0.33f, 0.22f};
            case KrlMap.JUNGLE -> new float[]{0.16f, 0.4f, 0.1f};
            case KrlMap.SNOW -> new float[]{0.9f, 0.92f, 0.95f};
            case KrlMap.MOUNTAIN -> new float[]{0.5f, 0.5f, 0.49f};
            case KrlMap.SWAMP -> new float[]{0.25f, 0.3f, 0.17f};
            case KrlMap.BADLANDS -> new float[]{0.7f, 0.39f, 0.2f};
            case KrlMap.SAVANNA -> new float[]{0.56f, 0.54f, 0.28f};
            case KrlMap.PLAINS -> new float[]{0.4f, 0.55f, 0.25f};
            case KrlMap.CHERRY -> new float[]{0.66f, 0.52f, 0.56f};
            case KrlMap.MUSHROOM -> new float[]{0.48f, 0.41f, 0.45f};
            case KrlMap.NETHER -> new float[]{0.44f, 0.12f, 0.1f};
            case KrlMap.END -> new float[]{0.84f, 0.84f, 0.64f};
            default -> new float[]{0.35f, 0.46f, 0.25f};
        };
    }

    /** The map picture, one pixel per cell (built once). */
    public Identifier texture() {
        if (tex != null) return texId;
        NativeImage img = new NativeImage(n, n, false);
        float metres = cell; // horizontal metres per cell
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) {
                int idx = j * n + i;
                int k = kind[idx];
                int y = height[idx];
                float r, g, b;
                if (rgb[idx] != 0) {
                    int c = rgb[idx];
                    r = ((c >> 16) & 255) / 255f;
                    g = ((c >> 8) & 255) / 255f;
                    b = (c & 255) / 255f;
                } else {
                    float[] c = natural(k);
                    r = c[0];
                    g = c[1];
                    b = c[2];
                }
                float shade;
                boolean water = wet(k) && y < sea;
                if (water) {
                    float depth = MathHelper.clamp((sea - y) / 30f, 0, 1);
                    shade = 1.05f - depth * 0.5f;
                } else {
                    // light from the north-west; the slope from the neighbours, steeper on fine tiles
                    float dx = (h(i + 1, j) - h(i - 1, j)) / (2f * metres), dz = (h(i, j + 1) - h(i, j - 1)) / (2f * metres);
                    float lit = (-dx - dz) * (tile ? 1.1f : 2.4f);
                    float alt = MathHelper.clamp((y - sea) / 140f, -0.2f, 1f);
                    shade = MathHelper.clamp(0.84f + lit + alt * 0.16f, 0.35f, 1.4f);
                    // contours: fine every 8 m, bold every 40 m
                    int c0 = Math.floorDiv(y, 8), cr = Math.floorDiv(h(i + 1, j), 8), cd = Math.floorDiv(h(i, j + 1), 8);
                    if (c0 != cr || c0 != cd) {
                        boolean bold = Math.floorDiv(y, 40) != Math.floorDiv(h(i + 1, j), 40) || Math.floorDiv(y, 40) != Math.floorDiv(h(i, j + 1), 40);
                        shade *= bold ? 0.62f : 0.82f;
                    }
                }
                int R = (int) MathHelper.clamp(r * shade * 255, 0, 255), G = (int) MathHelper.clamp(g * shade * 255, 0, 255),
                        B = (int) MathHelper.clamp(b * shade * 255, 0, 255);
                img.setColor(i, j, 0xFF000000 | (B << 16) | (G << 8) | R);
            }
        tex = new NativeImageBackedTexture(img);
        tex.setFilter(!tile, false);
        texId = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("orbital_railgun_krl_survey_" + (nextTex++), tex);
        return texId;
    }
}
