package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.client.ship.ShipCinematic;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the crosshair, hotbar and bars while the starship cinematic is running. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private void orbitalRailgun$crosshair(DrawContext context, CallbackInfo ci) {
        if (ShipCinematic.active() || (com.creedpdf.orbitalrailgun.client.krl.KrlCine.active() || com.creedpdf.orbitalrailgun.client.station.ScCine.active())) ci.cancel();
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true, require = 0)
    private void orbitalRailgun$hotbar(float tickDelta, DrawContext context, CallbackInfo ci) {
        if (ShipCinematic.active() || (com.creedpdf.orbitalrailgun.client.krl.KrlCine.active() || com.creedpdf.orbitalrailgun.client.station.ScCine.active())) ci.cancel();
    }

    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true, require = 0)
    private void orbitalRailgun$bars(DrawContext context, CallbackInfo ci) {
        if (ShipCinematic.active() || (com.creedpdf.orbitalrailgun.client.krl.KrlCine.active() || com.creedpdf.orbitalrailgun.client.station.ScCine.active())) ci.cancel();
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true, require = 0)
    private void orbitalRailgun$xp(DrawContext context, int x, CallbackInfo ci) {
        if (ShipCinematic.active() || (com.creedpdf.orbitalrailgun.client.krl.KrlCine.active() || com.creedpdf.orbitalrailgun.client.station.ScCine.active())) ci.cancel();
    }
}
