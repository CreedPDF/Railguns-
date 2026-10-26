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
    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void orbitalRailgun$noHandInCinematic(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.Camera camera,
                                                  float tickDelta, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (com.creedpdf.orbitalrailgun.client.ship.ShipCinematic.active() || com.creedpdf.orbitalrailgun.client.krl.KrlCine.active()) ci.cancel();
    }

    @Inject(method = "getFarPlaneDistance", at = @At("RETURN"), cancellable = true, require = 0)
    private void orbitalRailgun$farBeam(CallbackInfoReturnable<Float> cir) {
        if (RemoteBeams.active() || com.creedpdf.orbitalrailgun.client.ship.ShipCinematic.active()
                || com.creedpdf.orbitalrailgun.client.krl.KrlCine.active() || com.creedpdf.orbitalrailgun.client.krl.KrlClient.farFlight())
            cir.setReturnValue(Math.max(cir.getReturnValueF(), 6144f));
    }
}
