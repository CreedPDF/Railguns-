package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.util.Identifier;

/**
 * The impact frames: full-screen post effects applied to the rendered world for a few ticks at a time while the
 * cutscene cuts between them (0 molten orange/black, 1 ice-blue negative, 2 orange torn by glitch slices, 3 glowing
 * orange edges on black). Applied right after the world is drawn, before the HUD, like vanilla's own post effects.
 */
public final class GrsImpactFx {
    private GrsImpactFx() {}

    private static final PostEffectProcessor[] FX = new PostEffectProcessor[4];
    private static boolean failed;
    private static int w = -1, h = -1;
    /** The mode to apply this frame (-1 none); set by the cutscene. */
    public static int mode = -1;

    public static void apply(float tickDelta) {
        int m = mode;
        if (m < 0 || failed) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            if (FX[m] == null) {
                FX[m] = new PostEffectProcessor(mc.getTextureManager(), mc.getResourceManager(), mc.getFramebuffer(),
                        new Identifier("minecraft", "shaders/post/railguns_grs_impact_" + m + ".json"));
                w = -1;
            }
            int fw = mc.getFramebuffer().textureWidth, fh = mc.getFramebuffer().textureHeight;
            if (fw != w || fh != h) {
                for (PostEffectProcessor p : FX) if (p != null) p.setupDimensions(fw, fh);
                w = fw;
                h = fh;
            }
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            FX[m].render(tickDelta);
            mc.getFramebuffer().beginWrite(true);
        } catch (Exception e) {
            failed = true;
            RailgunMod.LOGGER.warn("GRS impact frames unavailable: {}", e.toString());
        }
    }

    public static void close() {
        for (int i = 0; i < FX.length; i++) {
            if (FX[i] != null) FX[i].close();
            FX[i] = null;
        }
        w = h = -1;
        failed = false;
    }
}
