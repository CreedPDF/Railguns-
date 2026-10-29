package com.creedpdf.orbitalrailgun.mixin;

import net.minecraft.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** HANGEKI: a rocket caught in a parry must not burn out (and burst) while it hangs in the air. */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketAccessor {
    @Accessor("life")
    int orbitalRailgun$life();

    @Accessor("life")
    void orbitalRailgun$setLife(int life);

    @Accessor("lifeTime")
    int orbitalRailgun$lifeTime();

    @Accessor("lifeTime")
    void orbitalRailgun$setLifeTime(int lifeTime);
}
