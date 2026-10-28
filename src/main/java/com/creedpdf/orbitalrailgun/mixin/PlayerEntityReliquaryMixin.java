package com.creedpdf.orbitalrailgun.mixin;

import com.creedpdf.orbitalrailgun.reliquary.ModDataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cosmic Reliquary's per-player profile (Aetheric Charge), saved with the player under "CosmicReliquary". */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityReliquaryMixin implements ModDataComponentTypes.Holder {
    @Unique
    private final ModDataComponentTypes.Profile cosmicReliquary$profile = new ModDataComponentTypes.Profile();

    @Override
    public ModDataComponentTypes.Profile cosmicReliquary$profile() {
        return cosmicReliquary$profile;
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void cosmicReliquary$write(NbtCompound nbt, CallbackInfo ci) {
        nbt.put("CosmicReliquary", cosmicReliquary$profile.write());
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void cosmicReliquary$read(NbtCompound nbt, CallbackInfo ci) {
        if (nbt.contains("CosmicReliquary")) cosmicReliquary$profile.read(nbt.getCompound("CosmicReliquary"));
    }
}
