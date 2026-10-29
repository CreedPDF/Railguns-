package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.client.hangeki.ParryClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HANGEKI's parry stance on the player model (seen by everyone, and by the bearer in the cutscene): the sword arm
 * raised and drawn across the body, blade angled up in front of the face, the off hand braced flat behind the
 * spine of the blade, feet set wide. A guard is a quicker, smaller version. Runs before PlayerEntityModel copies the
 * arms and legs onto the sleeves and trousers.
 */
@Mixin(BipedEntityModel.class)
public abstract class BipedEntityModelMixin<T extends LivingEntity> {
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart leftLeg;
    @Shadow @Final public ModelPart body;

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void orbitalRailgun$parryStance(T entity, float limbAngle, float limbDistance, float age, float headYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity p)) return;
        float k = ParryClient.poseWeight(p, MinecraftClient.getInstance().getTickDelta());
        if (k <= 0.001f) return;
        boolean guard = ParryClient.guardPose(p);
        boolean right = p.getMainArm() == Arm.RIGHT;
        ModelPart sword = right ? rightArm : leftArm, off = right ? leftArm : rightArm;
        float m = right ? 1f : -1f;
        float breathe = MathHelper.sin(age * 0.09f) * 0.03f;
        if (guard) {
            sword.pitch = MathHelper.lerp(k, sword.pitch, -1.35f);
            sword.yaw = MathHelper.lerp(k, sword.yaw, -0.62f * m);
            sword.roll = MathHelper.lerp(k, sword.roll, 0.2f * m);
            return;
        }
        sword.pitch = MathHelper.lerp(k, sword.pitch, -1.8f + breathe);
        sword.yaw = MathHelper.lerp(k, sword.yaw, -0.5f * m);
        sword.roll = MathHelper.lerp(k, sword.roll, 0.12f * m);
        off.pitch = MathHelper.lerp(k, off.pitch, -1.5f + breathe);
        off.yaw = MathHelper.lerp(k, off.yaw, 0.82f * m);
        off.roll = MathHelper.lerp(k, off.roll, -0.1f * m);
        rightLeg.pitch = MathHelper.lerp(k, rightLeg.pitch, right ? 0.42f : -0.38f);
        leftLeg.pitch = MathHelper.lerp(k, leftLeg.pitch, right ? -0.38f : 0.42f);
        rightLeg.roll = MathHelper.lerp(k, rightLeg.roll, 0.12f);
        leftLeg.roll = MathHelper.lerp(k, leftLeg.roll, -0.12f);
    }
}
