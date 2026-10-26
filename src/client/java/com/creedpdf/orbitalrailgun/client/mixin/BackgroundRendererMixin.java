package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.client.krl.KrlCine;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pushes fog far out while the KRL ride is high up, so the ground (and the distant survey terrain) stays visible. */
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {
    @Inject(method = "applyFog", at = @At("RETURN"), require = 0)
    private static void orbitalRailgun$krlFog(Camera camera, BackgroundRenderer.FogType fogType, float viewDistance, boolean thickFog, float tickDelta,
                                              CallbackInfo ci) {
        float[] f = KrlCine.fog(viewDistance);
        if (f != null) {
            RenderSystem.setShaderFogStart(f[0]);
            RenderSystem.setShaderFogEnd(f[1]);
        }
    }
}
