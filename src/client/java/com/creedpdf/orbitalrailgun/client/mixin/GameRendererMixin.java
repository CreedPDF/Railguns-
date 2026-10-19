package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.client.render.RemoteBeams;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pushes the projection far plane out while a railgun beam is live, so 2000-block shots are never clipped. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFarPlaneDistance", at = @At("RETURN"), cancellable = true, require = 0)
    private void orbitalRailgun$farBeam(CallbackInfoReturnable<Float> cir) {
        if (RemoteBeams.active()) cir.setReturnValue(Math.max(cir.getReturnValueF(), 4096f));
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void orbitalRailgun$cinematicFov(net.minecraft.client.render.Camera camera, float tickDelta, boolean changingFov,
                                             CallbackInfoReturnable<Double> cir) {
        if (com.creedpdf.orbitalrailgun.client.orbital.CinematicCamera.active()) {
            cir.setReturnValue((double) com.creedpdf.orbitalrailgun.client.orbital.CinematicCamera.fov());
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true, require = 0)
    private void orbitalRailgun$noBob(net.minecraft.client.util.math.MatrixStack matrices, float tickDelta,
                                      org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (com.creedpdf.orbitalrailgun.client.orbital.CinematicCamera.active()) ci.cancel();
    }

    @Inject(method = "getFarPlaneDistance", at = @At("RETURN"), cancellable = true, require = 0)
    private void orbitalRailgun$farCinematic(CallbackInfoReturnable<Float> cir) {
        if (com.creedpdf.orbitalrailgun.client.orbital.CinematicCamera.active()) cir.setReturnValue(Math.max(cir.getReturnValueF(), 2048f));
    }
}
