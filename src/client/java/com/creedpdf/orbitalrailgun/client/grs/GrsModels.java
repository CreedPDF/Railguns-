package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.util.Optional;

/** Loads the GRS meshes on every resource reload. */
public final class GrsModels {
    private GrsModels() {}

    public static GrsMesh STATION, SPEAR, GOGGLES;

    public static final Identifier STATION_TEX = tex("station"), STATION_GLOW = tex("station_glow");
    public static final Identifier SPEAR_TEX = tex("spear"), SPEAR_GLOW = tex("spear_glow");
    public static final Identifier GOGGLES_TEX = tex("goggles"), GOGGLES_GLOW = tex("goggles_glow");

    private static Identifier tex(String n) {
        return RailgunMod.id("textures/grs/" + n + ".png");
    }

    public static void register() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return RailgunMod.id("grs_models");
            }

            @Override
            public void reload(ResourceManager rm) {
                STATION = load(rm, "station");
                SPEAR = load(rm, "spear");
                GOGGLES = load(rm, "goggles");
            }
        });
    }

    private static GrsMesh load(ResourceManager rm, String name) {
        Identifier id = RailgunMod.id("models/grs/" + name + ".json");
        Optional<Resource> r = rm.getResource(id);
        if (r.isEmpty()) {
            RailgunMod.LOGGER.warn("GRS model {} missing", id);
            return null;
        }
        try (Reader rd = r.get().getReader()) {
            JsonObject o = JsonParser.parseReader(rd).getAsJsonObject();
            return GrsMesh.parse(o);
        } catch (Exception e) {
            RailgunMod.LOGGER.error("GRS model {} failed to load", id, e);
            return null;
        }
    }
}
