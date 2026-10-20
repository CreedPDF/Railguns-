package com.creedpdf.orbitalrailgun.mixin;

import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No stepping out of the starship mid-flight (sneak would dismount you into the sky or vacuum). */
@Mixin(PlayerEntity.class)
public abstract class PlayerDismountMixin {
    @Inject(method = "shouldDismount", at = @At("HEAD"), cancellable = true)
    private void orbitalRailgun$stayInShip(CallbackInfoReturnable<Boolean> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (self.getVehicle() instanceof ShipEntity ship && ship.inFlight()) cir.setReturnValue(false);
    }
}
