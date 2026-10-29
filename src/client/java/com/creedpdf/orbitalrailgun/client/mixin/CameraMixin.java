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
        double[] parry = com.creedpdf.orbitalrailgun.client.hangeki.HangekiCine.camera(tickDelta);
        if (parry != null) {
            setRotation((float) parry[3], (float) parry[4]);
            setPos(new Vec3d(parry[0], parry[1], parry[2]));
            this.thirdPerson = true;
            return;
        }
        Vec3d hk = com.creedpdf.orbitalrailgun.client.hangeki.HangekiCine.shake();
        if (hk.lengthSquared() > 1e-8) setPos(getPos().add(hk));
        double[] krl = com.creedpdf.orbitalrailgun.client.krl.KrlCine.camera(tickDelta);
        if (krl != null) {
            setRotation((float) krl[3], (float) krl[4]);
            setPos(new Vec3d(krl[0], krl[1], krl[2]));
            this.thirdPerson = true;
            return;
        }
        double[] film = com.creedpdf.orbitalrailgun.client.ship.ShipCinematic.camera(tickDelta);
        if (film != null) {
            setRotation((float) film[3], (float) film[4]);
            setPos(new Vec3d(film[0], film[1], film[2]));
            this.thirdPerson = true;
            return;
        }
        Vec3d shake = com.creedpdf.orbitalrailgun.client.krl.KrlClient.shake(getPos(), tickDelta);
        if (shake.lengthSquared() > 1e-8) setPos(getPos().add(shake));
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
