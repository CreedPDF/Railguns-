package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.krl.KrlMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

/** A terrain survey received from the server (see {@link KrlMap}), with its map image for fire control. */
public final class KrlSurvey {
    public final BlockPos console;
    public final int cx, cz, n, cell, sea;
    public final short[] height;
    public final byte[] kind;
    public final int x0, z0;
    private NativeImageBackedTexture tex;
    private Identifier texId;

    private static KrlSurvey last;

    private KrlSurvey(BlockPos console, int cx, int cz, int n, int cell, short[] height, byte[] kind, int sea) {
        this.console = console;
        this.cx = cx;
        this.cz = cz;
        this.n = n;
        this.cell = cell;
        this.height = height;
        this.kind = kind;
        this.sea = sea;
        this.x0 = cx - n * cell / 2;
        this.z0 = cz - n * cell / 2;
    }

    public static KrlSurvey read(PacketByteBuf b) {
        BlockPos c = b.readBlockPos();
        int cx = b.readInt(), cz = b.readInt();
        int n = b.readVarInt(), cell = b.readVarInt();
        short[] h = new short[n * n];
        for (int i = 0; i < h.length; i++) h[i] = b.readShort();
        byte[] k = b.readByteArray();
        int sea = b.readVarInt();
        return new KrlSurvey(c, cx, cz, n, cell, h, k, sea);
    }

    public static void put(KrlSurvey s) {
        if (last != null) last.close();
        last = s;
    }

    @Nullable
    public static KrlSurvey get(BlockPos console) {
        return last != null && last.console.equals(console) ? last : null;
    }

    /** The newest survey, whichever console it came from. */
    @Nullable
    public static KrlSurvey latest() {
        return last;
    }

    public static void clear() {
        if (last != null) last.close();
        last = null;
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
            case KrlMap.OCEAN -> new float[]{0.10f, 0.17f, 0.30f};
            case KrlMap.DEEP -> new float[]{0.06f, 0.11f, 0.22f};
            case KrlMap.RIVER -> new float[]{0.13f, 0.22f, 0.33f};
            case KrlMap.BEACH -> new float[]{0.72f, 0.67f, 0.50f};
            case KrlMap.DESERT -> new float[]{0.80f, 0.72f, 0.52f};
            case KrlMap.FOREST -> new float[]{0.18f, 0.32f, 0.13f};
            case KrlMap.TAIGA -> new float[]{0.20f, 0.30f, 0.20f};
            case KrlMap.JUNGLE -> new float[]{0.14f, 0.36f, 0.09f};
            case KrlMap.SNOW -> new float[]{0.86f, 0.89f, 0.92f};
            case KrlMap.MOUNTAIN -> new float[]{0.46f, 0.46f, 0.45f};
            case KrlMap.SWAMP -> new float[]{0.22f, 0.27f, 0.15f};
            case KrlMap.BADLANDS -> new float[]{0.66f, 0.36f, 0.18f};
            case KrlMap.SAVANNA -> new float[]{0.52f, 0.50f, 0.25f};
            case KrlMap.PLAINS -> new float[]{0.36f, 0.50f, 0.22f};
            case KrlMap.CHERRY -> new float[]{0.62f, 0.48f, 0.52f};
            case KrlMap.MUSHROOM -> new float[]{0.45f, 0.38f, 0.42f};
            case KrlMap.NETHER -> new float[]{0.40f, 0.10f, 0.08f};
            case KrlMap.END -> new float[]{0.80f, 0.80f, 0.60f};
            default -> new float[]{0.32f, 0.42f, 0.22f};
        };
    }

    /** The map picture: natural colours, hill-shaded, with darker water depth; one pixel per cell. */
    public Identifier texture() {
        if (tex != null) return texId;
        NativeImage img = new NativeImage(n, n, false);
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) {
                int k = k(i, j);
                float[] c = natural(k);
                int y = h(i, j);
                float shade;
                if (wet(k)) {
                    float depth = MathHelper.clamp((sea - y) / 30f, 0, 1);
                    shade = 1.05f - depth * 0.45f;
                } else {
                    // light from the north-west, slope from the neighbours
                    float dx = (h(i + 1, j) - h(i - 1, j)) / (2f * cell), dz = (h(i, j + 1) - h(i, j - 1)) / (2f * cell);
                    float lit = (-dx - dz) * 2.2f;
                    float alt = MathHelper.clamp((y - sea) / 120f, -0.2f, 1f);
                    shade = MathHelper.clamp(0.82f + lit + alt * 0.22f, 0.35f, 1.35f);
                }
                int r = (int) MathHelper.clamp(c[0] * shade * 255, 0, 255), g = (int) MathHelper.clamp(c[1] * shade * 255, 0, 255),
                        b = (int) MathHelper.clamp(c[2] * shade * 255, 0, 255);
                img.setColor(i, j, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        tex = new NativeImageBackedTexture(img);
        tex.setFilter(true, false);
        texId = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("orbital_railgun_krl_survey", tex);
        return texId;
    }

    @SuppressWarnings("unused")
    private static final String SRC = RailgunMod.MOD_ID;
}
