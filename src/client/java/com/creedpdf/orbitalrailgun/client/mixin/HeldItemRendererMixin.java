package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.panel.PanelScreen;
import com.creedpdf.orbitalrailgun.client.tablet.TabletScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person rendering of the uplink tablet held in BOTH hands, and the zoom into it.
 *
 * <p>Modelled on vanilla's two-handed map pose: both arms are drawn reaching in from the bottom
 * of the screen and the tablet sits between them, tilting with the camera pitch. While the
 * tablet screen is open, {@link TabletScreen#zoom} interpolates that pose toward one where the
 * tablet is squared up, centred and pulled right up to the camera so its display fills the view;
 * the arms drop away as it rises.</p>
 *
 * <p>When the main hand holds the tablet the off-hand item is hidden (both hands are busy).</p>
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private ItemRenderer itemRenderer;

    @Shadow protected abstract float getMapAngle(float tickDelta);

    @Shadow protected abstract void renderArm(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, Arm arm);

    /** No hands while the camera is away at a railgun control panel. */
    @Inject(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at = @At("HEAD"), cancellable = true)
    private void orbitalRailgun$hideHandsAtPanel(float tickDelta, MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers,
                                                 ClientPlayerEntity player, int light, CallbackInfo ci) {
        if (PanelScreen.zoom(tickDelta) > 0f) ci.cancel();
    }

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void orbitalRailgun$renderTablet(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                                             float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                                             VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        boolean mainIsTablet = player.getMainHandStack().isOf(RailgunMod.RAILGUN_REMOTE);
        if (hand == Hand.OFF_HAND && mainIsTablet) {
            ci.cancel();
            return;
        }
        if (hand != Hand.MAIN_HAND || !item.isOf(RailgunMod.RAILGUN_REMOTE)) return;
        ci.cancel();

        float zoom = TabletScreen.zoom(tickDelta);
        float rest = 1f - zoom;
        matrices.push();

        // Vanilla map-style hold (swing bob suppressed while zooming).
        float sq = MathHelper.sqrt(swingProgress);
        float bobY = -0.2f * MathHelper.sin(swingProgress * MathHelper.PI) * rest;
        float bobZ = -0.4f * MathHelper.sin(sq * MathHelper.PI) * rest;
        matrices.translate(0.0f, -bobY / 2.0f, bobZ);
        float angle = getMapAngle(pitch);
        float restY = 0.04f + equipProgress * -1.2f + angle * -0.5f;
        matrices.translate(0.0f, MathHelper.lerp(zoom, restY, -0.035f), MathHelper.lerp(zoom, -0.72f, -0.235f));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.lerp(zoom, angle * -85.0f, 0f)));

        if (!player.isInvisible() && zoom < 0.6f) {
            matrices.push();
            matrices.translate(0.0f, -zoom * 0.6f, 0.0f); // hands fall away as the tablet rises
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90.0f));
            renderArm(matrices, vertexConsumers, light, Arm.RIGHT);
            renderArm(matrices, vertexConsumers, light, Arm.LEFT);
            matrices.pop();
        }

        float tilt = MathHelper.sin(sq * MathHelper.PI) * 20.0f * rest;
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(tilt));
        // Tablet model is 14x11 px in item space; 0.8 makes it about as wide as a held map.
        float scale = 0.8f;
        matrices.translate(0.0f, 0.06f * rest, 0.0f);
        matrices.scale(scale, scale, scale);
        itemRenderer.renderItem(item, ModelTransformationMode.NONE, light, OverlayTexture.DEFAULT_UV, matrices,
                vertexConsumers, client.world, 0);
        matrices.pop();
    }
}
