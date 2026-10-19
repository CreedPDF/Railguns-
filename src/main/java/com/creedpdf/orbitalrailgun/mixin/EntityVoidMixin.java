package com.creedpdf.orbitalrailgun.mixin;

import com.creedpdf.orbitalrailgun.space.SpaceDims;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Space has no "bottom of the world": entities can float far below the dimension's block range. */
@Mixin(Entity.class)
public abstract class EntityVoidMixin {
    @Inject(method = "attemptTickInVoid", at = @At("HEAD"), cancellable = true)
    private void orbital_railgun$noVoidInSpace(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (SpaceDims.isSpace(self.getWorld())) ci.cancel();
    }
}
