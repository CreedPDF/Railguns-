package com.creedpdf.orbitalrailgun.client.krl;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;

/**
 * Shader-pack (Iris / Oculus) awareness, through Iris's public API by reflection so there is no hard dependency:
 * whether a pack is in use and whether the frame being drawn is the shadow pass. KRL models drop to their coarsest
 * detail and skip their glow in the shadow pass (the shadow map can't see a bolt head, and a pack draws block
 * entities there a second time), and pick a detail level by distance otherwise.
 */
public final class KrlCompat {
    private KrlCompat() {}

    private static Object api;
    private static Method shadow, inUse;
    private static boolean tried;

    private static void init() {
        if (tried) return;
        tried = true;
        if (!FabricLoader.getInstance().isModLoaded("iris") && !FabricLoader.getInstance().isModLoaded("oculus")) return;
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = c.getMethod("getInstance").invoke(null);
            shadow = c.getMethod("isRenderingShadowPass");
            inUse = c.getMethod("isShaderPackInUse");
        } catch (Throwable t) {
            api = null;
        }
    }

    /** True while a shader pack draws its shadow map. */
    public static boolean shadowPass() {
        init();
        if (api == null) return false;
        try {
            return (boolean) shadow.invoke(api);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when a shader pack is active. */
    public static boolean shaders() {
        init();
        if (api == null) return false;
        try {
            return (boolean) inUse.invoke(api);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Detail level for a model at a block: 0 within 12 blocks, 1 within 32, else 2; always 2 in the shadow pass. */
    public static int lod(BlockPos at) {
        if (shadowPass()) return 2;
        Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        double d2 = cam.squaredDistanceTo(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        return d2 < 12 * 12 ? 0 : d2 < 32 * 32 ? 1 : 2;
    }
}
