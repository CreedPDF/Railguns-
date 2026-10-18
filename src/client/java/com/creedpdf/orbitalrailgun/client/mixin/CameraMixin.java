package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.client.panel.PanelScreen;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flies the camera to the railgun's control panel while {@link PanelScreen} is open: after
 * vanilla positions the camera at the player's eyes, the position and rotation are blended
 * toward the panel's viewpoint by {@link PanelScreen#zoom}. The camera is flagged as
 * third-person meanwhile so the player's body stays visible behind it.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean thirdPerson;

    @Shadow public abstract Vec3d getPos();

    @Shadow public abstract float getYaw();

    @Shadow public abstract float getPitch();

    @Shadow protected abstract void setPos(Vec3d pos);

    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void orbitalRailgun$panelZoom(BlockView area, Entity focusedEntity, boolean thirdPersonView,
                                         boolean inverseView, float tickDelta, CallbackInfo ci) {
        PanelScreen panel = PanelScreen.active();
        if (panel == null) return;
        float z = PanelScreen.zoom(tickDelta);
        if (z <= 0f) return;
        Vec3d from = getPos();
        Vec3d to = panel.camPos();
        float yaw = getYaw() + MathHelper.wrapDegrees(panel.camYaw() - getYaw()) * z;
        float pitch = MathHelper.lerp(z, getPitch(), panel.camPitch());
        setRotation(yaw, pitch);
        setPos(from.lerp(to, z));
        if (z > 0.05f) this.thirdPerson = true;
    }
}
