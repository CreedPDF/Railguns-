package com.creedpdf.orbitalrailgun.client.krl;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.util.Optional;

/** Loads the KRL meshes (built in Blender by tools/blender/build_krl.py) on every resource reload. */
public final class KrlModels {
    private KrlModels() {}

    public static KrlMesh LAUNCHER, SPEAR, CONSOLE, STATION, MAGAZINE;
    /** The ARES Kestrel starship (tools/blender/build_ship.py). */
    public static KrlMesh SHIP;
    public static final Identifier SHIP_TEX = RailgunMod.id("textures/ship/starship.png");
    public static final Identifier SHIP_GLOW = RailgunMod.id("textures/ship/starship_glow.png");
    /** Arsenal Legion (tools/blender/build_legion.py): the floating weapons' harness and the altar. */
    public static KrlMesh BLADE, ALTAR;
    public static final Identifier BLADE_TEX = al("textures/entity/floating_blade.png"), BLADE_GLOW = al("textures/entity/floating_blade_glow.png"),
            ALTAR_TEX = al("textures/entity/legion_altar.png"), ALTAR_GLOW = al("textures/entity/legion_altar_glow.png");

    private static Identifier al(String path) {
        return new Identifier("arsenal_legion", path);
    }
    /** The spear's nose (the part the plasma sheath wraps): where it starts along the spear, and its tip. */
    public static float NOSE_FROM = 5.35f, NOSE_TIP = 7f;

    public static final Identifier LAUNCHER_TEX = tex("launcher"), LAUNCHER_GLOW = tex("launcher_glow");
    public static final Identifier SPEAR_GLOW = tex("spear_glow");
    private static final Identifier[] SPEAR_TEX = new Identifier[com.creedpdf.orbitalrailgun.krl.Krl.LIVERIES.length];

    static {
        for (int i = 0; i < SPEAR_TEX.length; i++) SPEAR_TEX[i] = tex("spear_" + com.creedpdf.orbitalrailgun.krl.Krl.LIVERIES[i]);
    }

    /** The missile's albedo for a livery. */
    public static Identifier spearTex(int livery) {
        return SPEAR_TEX[com.creedpdf.orbitalrailgun.krl.Krl.livery(livery)];
    }
    public static final Identifier CONSOLE_TEX = tex("console"), CONSOLE_GLOW = tex("console_glow");
    public static final Identifier STATION_TEX = tex("station"), STATION_GLOW = tex("station_glow");
    public static final Identifier MAGAZINE_TEX = tex("magazine"), MAGAZINE_GLOW = tex("magazine_glow");

    private static Identifier tex(String n) {
        return RailgunMod.id("textures/krl/" + n + ".png");
    }

    public static void register() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return RailgunMod.id("krl_models");
            }

            @Override
            public void reload(ResourceManager rm) {
                LAUNCHER = load(rm, "launcher", LAUNCHER_GLOW);
                SPEAR = load(rm, "spear", SPEAR_GLOW);
                CONSOLE = load(rm, "console", CONSOLE_GLOW);
                STATION = load(rm, "station", STATION_GLOW);
                MAGAZINE = load(rm, "magazine", MAGAZINE_GLOW);
                SHIP = load(rm, RailgunMod.id("models/ship/starship.json"), SHIP_GLOW);
                BLADE = load(rm, al("models/entity/floating_blade.json"), BLADE_GLOW);
                ALTAR = load(rm, al("models/entity/legion_altar.json"), ALTAR_GLOW);
                if (SPEAR != null && SPEAR.part("nose") != null) {
                    float[] r = SPEAR.yRange("nose");
                    NOSE_FROM = r[0];
                    NOSE_TIP = r[1];
                }
            }
        });
    }

    private static KrlMesh load(ResourceManager rm, String name, Identifier glow) {
        return load(rm, RailgunMod.id("models/krl/" + name + ".json"), glow);
    }

    private static KrlMesh load(ResourceManager rm, Identifier id, Identifier glow) {
        KrlMesh m = load(rm, id);
        if (m == null) return null;
        NativeImage img = null;
        try {
            Optional<Resource> g = rm.getResource(glow);
            if (g.isPresent()) try (var in = g.get().getInputStream()) {
                img = NativeImage.read(in);
            }
        } catch (Exception e) {
            RailgunMod.LOGGER.warn("KRL glow texture {} unreadable", glow, e);
        }
        try {
            m.prepare(img);
        } finally {
            if (img != null) img.close();
        }
        return m;
    }

    private static KrlMesh load(ResourceManager rm, Identifier id) {
        Optional<Resource> r = rm.getResource(id);
        if (r.isEmpty()) {
            RailgunMod.LOGGER.warn("KRL model {} missing", id);
            return null;
        }
        try (Reader rd = r.get().getReader()) {
            JsonObject o = JsonParser.parseReader(rd).getAsJsonObject();
            return KrlMesh.parse(o);
        } catch (Exception e) {
            RailgunMod.LOGGER.error("KRL model {} failed to load", id, e);
            return null;
        }
    }
}
