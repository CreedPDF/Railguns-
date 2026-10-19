package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.client.space.OverworldSky;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Planets (and their deaths) in the overworld sky; hides the Moon once ARES-0 has destroyed it. */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererSkyMixin {
    @Inject(method = "renderSky(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V",
            at = @At("RETURN"))
    private void orbitalRailgun$planets(MatrixStack matrices, Matrix4f projection, float tickDelta, Camera camera, boolean thickFog,
                                        Runnable fogCallback, CallbackInfo ci) {
        if (!thickFog) OverworldSky.render(matrices, tickDelta, camera);
    }

    @ModifyArg(method = "renderSky(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/util/Identifier;)V"),
            index = 1)
    private Identifier orbitalRailgun$moonGone(Identifier id) {
        return OverworldSky.moonTexture(id);
    }
}
