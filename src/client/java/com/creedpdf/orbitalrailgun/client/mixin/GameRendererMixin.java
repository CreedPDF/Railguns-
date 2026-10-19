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
}
