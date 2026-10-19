package com.creedpdf.orbitalrailgun.client.orbital;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.io.Reader;
import java.util.Optional;

/**
 * The Earth sphere exported from Blender (unit radius, UVs for the equirectangular maps).
 * Falls back to a generated UV sphere if the resource is missing.
 */
public final class EarthMesh {
    private static final Identifier FILE = RailgunMod.id("models/environment/earth_mesh.json");
    private static EarthMesh instance;

    public final float[] pos;   // x, y, z per vertex
    public final float[] uv;    // u, v per vertex
    public final int[] tris;

    private EarthMesh(float[] pos, float[] uv, int[] tris) {
        this.pos = pos;
        this.uv = uv;
        this.tris = tris;
    }

    public static EarthMesh get() {
        if (instance == null) instance = load();
        return instance;
    }

    public int vertexCount() {
        return uv.length / 2;
    }

    private static EarthMesh load() {
        try {
            Optional<Resource> res = MinecraftClient.getInstance().getResourceManager().getResource(FILE);
            if (res.isPresent()) {
                JsonObject root;
                try (Reader r = res.get().getReader()) {
                    root = JsonParser.parseReader(r).getAsJsonObject();
                }
                JsonArray v = root.getAsJsonArray("vertices");
                JsonArray t = root.getAsJsonArray("triangles");
                float[] pos = new float[v.size() * 3];
                float[] uv = new float[v.size() * 2];
                for (int i = 0; i < v.size(); i++) {
                    JsonArray e = v.get(i).getAsJsonArray();
                    pos[i * 3] = e.get(0).getAsFloat();
                    pos[i * 3 + 1] = e.get(1).getAsFloat();
                    pos[i * 3 + 2] = e.get(2).getAsFloat();
                    uv[i * 2] = e.get(3).getAsFloat();
                    uv[i * 2 + 1] = e.get(4).getAsFloat();
                }
                int[] tris = new int[t.size() * 3];
                for (int i = 0; i < t.size(); i++) {
                    JsonArray e = t.get(i).getAsJsonArray();
                    tris[i * 3] = e.get(0).getAsInt();
                    tris[i * 3 + 1] = e.get(1).getAsInt();
                    tris[i * 3 + 2] = e.get(2).getAsInt();
                }
                return new EarthMesh(pos, uv, tris);
            }
        } catch (Exception ex) {
            RailgunMod.LOGGER.error("Earth mesh failed to load, using a generated sphere", ex);
        }
        return generated(128, 64);
    }

    private static EarthMesh generated(int seg, int rings) {
        int n = (seg + 1) * (rings + 1);
        float[] pos = new float[n * 3], uv = new float[n * 2];
        int k = 0;
        for (int r = 0; r <= rings; r++) {
            float v = r / (float) rings;
            float lat = MathHelper.PI * (0.5f - v);
            for (int s = 0; s <= seg; s++) {
                float u = s / (float) seg;
                float lon = MathHelper.TAU * u;
                pos[k * 3] = MathHelper.cos(lat) * MathHelper.cos(lon);
                pos[k * 3 + 1] = MathHelper.sin(lat);
                pos[k * 3 + 2] = -MathHelper.cos(lat) * MathHelper.sin(lon);
                uv[k * 2] = u;
                uv[k * 2 + 1] = v;
                k++;
            }
        }
        int[] tris = new int[seg * rings * 6];
        int q = 0;
        for (int r = 0; r < rings; r++) {
            for (int s = 0; s < seg; s++) {
                int a = r * (seg + 1) + s, b = a + seg + 1;
                tris[q++] = a; tris[q++] = b; tris[q++] = a + 1;
                tris[q++] = a + 1; tris[q++] = b; tris[q++] = b + 1;
            }
        }
        return new EarthMesh(pos, uv, tris);
    }
}
