package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** The missile's arrival: a plain, vanilla explosion where it hits. */
public final class KrlImpact {
    private KrlImpact() {}

    /** Explosion power (TNT is 4). */
    public static final float POWER = 9f;

    public static void explode(ServerWorld w, Vec3d at, @Nullable UUID owner) {
        Entity attacker = owner == null ? null : w.getEntity(owner);
        DamageSource src = new DamageSource(w.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(Krl.PLASMA), null, attacker);
        w.createExplosion(null, src, null, at.x, at.y - 0.5, at.z, POWER, false, World.ExplosionSourceType.TNT);
    }
}
