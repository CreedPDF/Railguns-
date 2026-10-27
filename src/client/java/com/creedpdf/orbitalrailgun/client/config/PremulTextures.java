package com.creedpdf.orbitalrailgun.client.config;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Shader-pack fix for additive glow sprites. The effect textures are white with the shape in their alpha; vanilla
 * blends them by that alpha, but shader packs often cut alpha at a threshold or ignore it for "emissive" passes, so
 * every halo turned into a hard-edged disc. While a pack is active the additive layers use a copy whose colour is
 * multiplied by its alpha: wherever the alpha fades out the colour is black too, which adds nothing to the image.
 */
public final class PremulTextures {
    private PremulTextures() {}

    private static final Map<Identifier, Identifier> CACHE = new HashMap<>();

    public static Identifier map(Identifier id) {
        if (!RailgunMod.MOD_ID.equals(id.getNamespace()) || !KrlCompat.shaders()) return id;
        Identifier hit = CACHE.get(id);
        if (hit != null) return hit;
        Identifier out = id;
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            Optional<net.minecraft.resource.Resource> res = mc.getResourceManager().getResource(id);
            if (res.isPresent()) {
                try (InputStream in = res.get().getInputStream()) {
                    NativeImage img = NativeImage.read(in);
                    if (img.getFormat().hasAlpha()) {
                        for (int y = 0; y < img.getHeight(); y++)
                            for (int x = 0; x < img.getWidth(); x++) {
                                int c = img.getColor(x, y); // ABGR
                                int a = (c >>> 24) & 255;
                                int r = (c & 255) * a / 255, g = ((c >> 8) & 255) * a / 255, b = ((c >> 16) & 255) * a / 255;
                                img.setColor(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                            }
                        out = RailgunMod.id("premul/" + id.getPath());
                        mc.getTextureManager().registerTexture(out, new NativeImageBackedTexture(img));
                    } else {
                        img.close();
                    }
                }
            }
        } catch (Exception e) {
            out = id;
        }
        CACHE.put(id, out);
        return out;
    }

    /** Resource reload: drop the copies (the texture manager has already freed them). */
    public static void clear() {
        CACHE.clear();
    }
}
