package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.client.reliquary.input.ReliquaryInput;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With a Cosmic Reliquary weapon in the main hand, the attack key fires / swings / beams it instead of hitting or
 * breaking anything (see ReliquaryInput).
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientReliquaryMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void cosmicReliquary$attack(CallbackInfoReturnable<Boolean> cir) {
        if (ReliquaryInput.onAttack((MinecraftClient) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void cosmicReliquary$noBreaking(boolean breaking, CallbackInfo ci) {
        if (ReliquaryInput.holding((MinecraftClient) (Object) this)) ci.cancel();
    }
}
