package com.creedpdf.orbitalrailgun.mixin;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** HANGEKI: an arrow already stuck in a block is no threat. */
@Mixin(PersistentProjectileEntity.class)
public interface PersistentProjectileAccessor {
    @Accessor("inGround")
    boolean orbitalRailgun$inGround();
}
