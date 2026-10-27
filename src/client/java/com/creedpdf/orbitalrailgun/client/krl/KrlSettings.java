package com.creedpdf.orbitalrailgun.client.krl;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The fire-control console's remembered client choices: ride the spear (the cutscene) or not, camera shake. */
public final class KrlSettings {
    private KrlSettings() {}

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orbital_railgun_krl.properties");
    public static boolean ride = true;
    public static float shake = 1f;

    public static void load() {
        try {
            if (!Files.exists(FILE)) return;
            Properties p = new Properties();
            try (var in = Files.newInputStream(FILE)) {
                p.load(in);
            }
            ride = Boolean.parseBoolean(p.getProperty("cutscene", "true"));
            shake = Math.max(0, Math.min(2, Float.parseFloat(p.getProperty("shake", "1"))));
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("cutscene", Boolean.toString(ride));
        p.setProperty("shake", Float.toString(shake));
        try (var out = Files.newOutputStream(FILE)) {
            p.store(out, "Deluxs Armory KRL fire control");
        } catch (IOException ignored) {
        }
    }
}
