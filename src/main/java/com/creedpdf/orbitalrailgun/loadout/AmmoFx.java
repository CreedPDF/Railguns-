package com.creedpdf.orbitalrailgun.loadout;

import com.creedpdf.orbitalrailgun.registry.ModParticles;
import net.minecraft.particle.ParticleEffect;

/**
 * Picks the particle set for the loaded ammunition. Standard rounds keep the classic red plasma
 * effects; every special round has its own flash, smoke and spark sprites, so no two
 * ammunition types share an impact.
 */
public final class AmmoFx {
    private AmmoFx() {}

    public static ParticleEffect flash(Loadout l) {
        return l.ammo() == null ? ModParticles.FLASH : ModParticles.AMMO_FLASH.get(l.ammo());
    }

    public static ParticleEffect smoke(Loadout l) {
        return l.ammo() == null ? ModParticles.PLASMA_SMOKE : ModParticles.AMMO_SMOKE.get(l.ammo());
    }

    public static ParticleEffect spark(Loadout l) {
        return l.ammo() == null ? ModParticles.PLASMA_SPARK : ModParticles.AMMO_SPARK.get(l.ammo());
    }

    /** Slow hot fragments; special rounds use their spark sprite. */
    public static ParticleEffect ember(Loadout l) {
        return l.ammo() == null ? ModParticles.EMBER : ModParticles.AMMO_SPARK.get(l.ammo());
    }

    /** Ground shock ring; special rounds roll their own smoke out instead of grey dust. */
    public static ParticleEffect dust(Loadout l) {
        return l.ammo() == null ? ModParticles.SHOCK_DUST : ModParticles.AMMO_SMOKE.get(l.ammo());
    }
}
