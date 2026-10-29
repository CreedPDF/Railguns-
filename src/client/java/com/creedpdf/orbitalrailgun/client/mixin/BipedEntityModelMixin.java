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
 * HANGEKI's parry stance on the player model - the whole body, not just the arms. The torso drops and leans into
 * the attack (a deep crouch: the same pivots vanilla moves to sneak, pushed further), the feet split into a long
 * lunge (lead leg forward, back leg driven back and out), the greatsword is held out at head height laid flat and
 * horizontal across the body, the off hand braced on the flat, and the head tucks down behind the guard. PlayerEntityRendererMixin
 * lowers the body and turns it side-on to the threat. A guard is a quick half-crouch; after a parry the body unwinds
 * out of the stance. Runs before PlayerEntityModel copies the parts onto the sleeves, trousers and jacket.
 */
@Mixin(BipedEntityModel.class)
public abstract class BipedEntityModelMixin<T extends LivingEntity> {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart hat;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart leftLeg;

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void orbitalRailgun$parryStance(T entity, float limbAngle, float limbDistance, float age, float headYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity p)) return;
        float k = ParryClient.poseWeight(p, MinecraftClient.getInstance().getTickDelta());
        if (k <= 0.001f) return;
        boolean guard = ParryClient.guardPose(p);
        float depth = guard ? 0.55f * k : k;
        boolean right = p.getMainArm() == Arm.RIGHT;
        ModelPart sword = right ? rightArm : leftArm, off = right ? leftArm : rightArm;
        ModelPart lead = right ? leftLeg : rightLeg, back = right ? rightLeg : leftLeg;
        float m = right ? 1f : -1f;
        float breathe = MathHelper.sin(age * 0.11f) * 0.025f * k;

        // crouch: torso forward, everything above the hips dropped (vanilla sneak pivots, pushed further)
        body.pitch = MathHelper.lerp(depth, body.pitch, 0.62f + breathe);
        body.pivotY = MathHelper.lerp(depth, body.pivotY, 4.6f);
        head.pivotY = MathHelper.lerp(depth, head.pivotY, 5.4f);
        head.pitch = MathHelper.lerp(depth, head.pitch, Math.max(head.pitch, 0.25f));
        sword.pivotY = MathHelper.lerp(depth, sword.pivotY, 6.4f);
        off.pivotY = MathHelper.lerp(depth, off.pivotY, 6.4f);
        sword.pivotZ = MathHelper.lerp(depth, sword.pivotZ, -1.2f);
        off.pivotZ = MathHelper.lerp(depth, off.pivotZ, -1.2f);
        // lunge: lead foot forward and out, back foot driven back
        lead.pivotY = MathHelper.lerp(depth, lead.pivotY, 12.6f);
        back.pivotY = MathHelper.lerp(depth, back.pivotY, 12.6f);
        lead.pivotZ = MathHelper.lerp(depth, lead.pivotZ, 4.4f);
        back.pivotZ = MathHelper.lerp(depth, back.pivotZ, 4.4f);
        lead.pitch = MathHelper.lerp(depth, lead.pitch, -0.95f);
        back.pitch = MathHelper.lerp(depth, back.pitch, 0.78f);
        lead.yaw = MathHelper.lerp(depth, lead.yaw, 0.18f * m);
        back.yaw = MathHelper.lerp(depth, back.yaw, -0.25f * m);
        lead.roll = MathHelper.lerp(depth, lead.roll, -0.16f * m);
        back.roll = MathHelper.lerp(depth, back.roll, 0.2f * m);
        // arms: sword high and across, off hand braced on the spine of the blade
        if (guard) {
            sword.pitch = MathHelper.lerp(k, sword.pitch, -1.55f);
            sword.yaw = MathHelper.lerp(k, sword.yaw, -0.55f * m);
            sword.roll = MathHelper.lerp(k, sword.roll, 0.25f * m);
            off.pitch = MathHelper.lerp(k, off.pitch, -0.9f);
            off.yaw = MathHelper.lerp(k, off.yaw, 0.5f * m);
        } else {
            // the greatsword laid flat across in front of the face: sword arm straight out at head height and rolled
            // about its own axis so the blade lies horizontal across the body; the off hand braces the flat
            sword.pitch = MathHelper.lerp(k, sword.pitch, -1.78f + breathe);
            sword.yaw = MathHelper.lerp(k, sword.yaw, 0.12f * m);
            sword.roll = MathHelper.lerp(k, sword.roll, 1.5f * m);
            off.pitch = MathHelper.lerp(k, off.pitch, -1.82f + breathe);
            off.yaw = MathHelper.lerp(k, off.yaw, 0.42f * m);
            off.roll = MathHelper.lerp(k, off.roll, 0.35f * m);
        }
        hat.copyTransform(head);
    }
}
