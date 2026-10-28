package com.creedpdf.orbitalrailgun.mixin;

import com.creedpdf.orbitalrailgun.legion.SwarmAttributes;
import com.creedpdf.orbitalrailgun.legion.capability.IWeaponSwarm;
import com.creedpdf.orbitalrailgun.legion.capability.SwarmHolder;
import com.creedpdf.orbitalrailgun.legion.capability.WeaponSwarm;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Arsenal Legion's player "capability": every player carries a {@link WeaponSwarm}, saved in their NBT, and has the
 * {@code arsenal_legion:swarm_capacity} attribute (default 6, raised by any attribute modifier, e.g. on armour).
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntitySwarmMixin implements SwarmHolder {
    @Unique
    private final WeaponSwarm arsenalLegion$swarm = new WeaponSwarm();

    @Override
    public IWeaponSwarm arsenalLegion$swarm() {
        return arsenalLegion$swarm;
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void arsenalLegion$write(NbtCompound nbt, CallbackInfo ci) {
        nbt.put("ArsenalLegion", arsenalLegion$swarm.writeNbt());
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void arsenalLegion$read(NbtCompound nbt, CallbackInfo ci) {
        if (nbt.contains("ArsenalLegion")) arsenalLegion$swarm.readNbt(nbt.getCompound("ArsenalLegion"));
    }

    @Inject(method = "createPlayerAttributes", at = @At("RETURN"))
    private static void arsenalLegion$attributes(CallbackInfoReturnable<DefaultAttributeContainer.Builder> cir) {
        cir.getReturnValue().add(SwarmAttributes.SWARM_CAPACITY);
    }
}
