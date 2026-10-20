package com.creedpdf.orbitalrailgun.client.config;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Lower-resolution copies of the big space textures (planets, sky), built on first use when the
 * quality setting asks for them. Halving a 4096x2048 map cuts its video memory from 32 MB to 8 MB.
 */
public final class ScaledTextures {
    private ScaledTextures() {}

    private static final Map<String, Identifier> CACHE = new HashMap<>();

    public static Identifier map(Identifier id) {
        int div = Quality.texDiv();
        if (div <= 1 || !RailgunMod.MOD_ID.equals(id.getNamespace()) || !id.getPath().startsWith("textures/space/")) return id;
        String key = div + ":" + id;
        Identifier hit = CACHE.get(key);
        if (hit != null) return hit;
        Identifier out = id;
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            Optional<net.minecraft.resource.Resource> res = mc.getResourceManager().getResource(id);
            if (res.isPresent()) {
                try (InputStream in = res.get().getInputStream(); NativeImage src = NativeImage.read(in)) {
                    int w = src.getWidth() / div, h = src.getHeight() / div;
                    if (src.getWidth() > 1024 && w > 0 && h > 0) {
                        NativeImage dst = new NativeImage(w, h, false);
                        src.resizeSubRectTo(0, 0, src.getWidth(), src.getHeight(), dst);
                        out = RailgunMod.id("scaled" + div + "/" + id.getPath());
                        mc.getTextureManager().registerTexture(out, new NativeImageBackedTexture(dst));
                    }
                }
            }
        } catch (Exception e) {
            out = id;
        }
        CACHE.put(key, out);
        return out;
    }
}
