package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.client.hangeki.HangekiTime;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * HANGEKI's slow motion on the client: the tick clock counts real time scaled by the time scale, so the client's
 * world (its own movement, entity interpolation, particles, animations) slows in step with the server's.
 */
@Mixin(RenderTickCounter.class)
public abstract class RenderTickCounterMixin {
    @Shadow public float tickDelta;
    @Shadow public float lastFrameDuration;
    @Shadow private long prevTimeMillis;
    @Shadow @Final private float tickTime;

    @Inject(method = "beginRenderTick", at = @At("HEAD"), cancellable = true)
    private void orbitalRailgun$slowMotion(long timeMillis, CallbackInfoReturnable<Integer> cir) {
        float s = HangekiTime.frame(timeMillis);
        if (Math.abs(s - 1f) < 0.001f) return;
        this.lastFrameDuration = (float) (timeMillis - this.prevTimeMillis) / this.tickTime * s;
        this.prevTimeMillis = timeMillis;
        this.tickDelta += this.lastFrameDuration;
        int i = (int) this.tickDelta;
        this.tickDelta -= (float) i;
        cir.setReturnValue(i);
    }
}
