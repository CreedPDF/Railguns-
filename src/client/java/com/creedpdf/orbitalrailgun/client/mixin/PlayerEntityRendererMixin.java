package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Third person: a player holding the uplink tablet raises both arms in front of them (vanilla's
 * two-handed "crossbow hold" pose), so others see the tablet held in both hands.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
    private static void orbitalRailgun$tabletPose(AbstractClientPlayerEntity player, Hand hand,
                                                  CallbackInfoReturnable<BipedEntityModel.ArmPose> cir) {
        if (player.getMainHandStack().isOf(RailgunMod.RAILGUN_REMOTE)) {
            cir.setReturnValue(BipedEntityModel.ArmPose.CROSSBOW_HOLD);
        }
    }

    /**
     * HANGEKI's stance: the whole body sinks into the crouch and turns side-on to what it is parrying (lead shoulder
     * toward the threat), unwinding again after the hit.
     */
    @Inject(method = "setupTransforms(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/util/math/MatrixStack;FFF)V",
            at = @At("TAIL"))
    private void orbitalRailgun$stanceBody(AbstractClientPlayerEntity player, net.minecraft.client.util.math.MatrixStack ms, float age, float bodyYaw,
                                          float tickDelta, CallbackInfo ci) {
        float k = com.creedpdf.orbitalrailgun.client.hangeki.ParryClient.poseWeight(player, tickDelta);
        if (k <= 0.001f || player.isFallFlying() || player.isSwimming()) return;
        float depth = com.creedpdf.orbitalrailgun.client.hangeki.ParryClient.guardPose(player) ? 0.45f * k : k;
        float side = player.getMainArm() == net.minecraft.util.Arm.RIGHT ? 1f : -1f;
        ms.translate(0, -0.3f * depth, 0);
        ms.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(-38f * depth * side));
    }
}
