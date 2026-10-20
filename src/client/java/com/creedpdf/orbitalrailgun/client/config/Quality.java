package com.creedpdf.orbitalrailgun.client.config;

import com.creedpdf.orbitalrailgun.util.FxBudget;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Graphics quality for the mod's own effects (planets, blasts, debris, flames, sky).
 * <ul>
 *   <li>{@code ULTRA}: everything at full detail.</li>
 *   <li>{@code HIGH} (default): looks the same at normal viewing, far less geometry and particles.</li>
 *   <li>{@code MEDIUM}: half-resolution space textures, lighter effects.</li>
 *   <li>{@code LOW}: for weak PCs and laptops.</li>
 * </ul>
 * With {@code adaptive} on (default) the effective level steps down while the frame rate stays
 * low and climbs back when it recovers, never above the chosen level.
 *
 * <p>Stored in {@code config/orbital_railgun_client.json}; changed with the Mod Menu config screen
 * or {@code /orbitalquality}.
 */
public final class Quality {
    private Quality() {}

    public enum Level {
        LOW(0.28f, 0.22f, 2), MEDIUM(0.42f, 0.4f, 2), HIGH(0.62f, 0.62f, 1), ULTRA(1f, 1f, 1);

        public final float geo, fx;
        public final int texDiv;

        Level(float geo, float fx, int texDiv) {
            this.geo = geo;
            this.fx = fx;
            this.texDiv = texDiv;
        }
    }

    private static final class Data {
        Level quality = Level.HIGH;
        boolean adaptive = true;
    }

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orbital_railgun_client.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data = new Data();
    private static int cap = Level.ULTRA.ordinal();
    private static int lowTicks, highTicks;

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                Data d = GSON.fromJson(Files.readString(FILE), Data.class);
                if (d != null && d.quality != null) data = d;
            } else save();
        } catch (Exception ignored) {
            data = new Data();
        }
        apply();
    }

    public static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(data));
        } catch (Exception ignored) {}
    }

    public static Level chosen() {
        return data.quality;
    }

    public static boolean adaptive() {
        return data.adaptive;
    }

    public static void set(Level l, boolean adaptive) {
        data.quality = l;
        data.adaptive = adaptive;
        cap = Level.ULTRA.ordinal();
        lowTicks = highTicks = 0;
        save();
        apply();
    }

    /** The level effects use right now. */
    public static Level level() {
        int i = Math.min(data.quality.ordinal(), data.adaptive ? cap : Level.ULTRA.ordinal());
        return Level.values()[i];
    }

    /** Tessellation multiplier (sphere segments, flame rings...). */
    public static float geo() {
        return level().geo;
    }

    /** Particle / sprite count multiplier. */
    public static float fx() {
        return level().fx;
    }

    public static int scale(int n, int min) {
        return Math.max(min, Math.round(n * fx()));
    }

    public static int segs(int n, int min) {
        return Math.max(min, Math.round(n * geo()));
    }

    /** Space textures are loaded at 1/texDiv resolution (never changes with adaptive mode). */
    public static int texDiv() {
        return data.quality.texDiv;
    }

    /** Called every client tick: adaptive stepping. */
    public static void tick(MinecraftClient mc) {
        if (data.adaptive && mc.world != null && !mc.isPaused()) {
            int fps = mc.getCurrentFps();
            if (fps < 32) { lowTicks++; highTicks = 0; }
            else if (fps > 70) { highTicks++; lowTicks = 0; }
            else { lowTicks = Math.max(0, lowTicks - 1); highTicks = Math.max(0, highTicks - 1); }
            int cur = Math.min(cap, data.quality.ordinal());
            if (lowTicks > 50 && cur > 0) { cap = cur - 1; lowTicks = 0; }
            if (highTicks > 200 && cap < Level.ULTRA.ordinal()) { cap++; highTicks = 0; }
        }
        apply();
    }

    private static void apply() {
        FxBudget.particles = fx();
    }
}
