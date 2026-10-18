package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}
