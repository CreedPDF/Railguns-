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
    /** The Gungnir cutscene's impact frames, applied to the finished world image like vanilla's post effects. */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
            shift = At.Shift.AFTER), require = 0)
    private void orbitalRailgun$grsImpactFrames(float tickDelta, long startTime, boolean tick, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.creedpdf.orbitalrailgun.client.grs.GrsImpactFx.apply(tickDelta);
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void orbitalRailgun$noHandInCinematic(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.Camera camera,
                                                  float tickDelta, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (com.creedpdf.orbitalrailgun.client.ship.ShipCinematic.active() || com.creedpdf.orbitalrailgun.client.grs.GrsCine.active()) ci.cancel();
    }

    @Inject(method = "getFarPlaneDistance", at = @At("RETURN"), cancellable = true, require = 0)
    private void orbitalRailgun$farBeam(CallbackInfoReturnable<Float> cir) {
        if (RemoteBeams.active() || com.creedpdf.orbitalrailgun.client.ship.ShipCinematic.active()) cir.setReturnValue(Math.max(cir.getReturnValueF(), 4096f));
    }
}
