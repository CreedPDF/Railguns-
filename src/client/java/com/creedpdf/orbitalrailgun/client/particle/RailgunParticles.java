package com.creedpdf.orbitalrailgun.client.particle;

import com.creedpdf.orbitalrailgun.client.particle.RailgunParticle.Style;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.particle.DefaultParticleType;

/** Client registration of every custom particle with its behaviour preset. */
public final class RailgunParticles {
    private RailgunParticles() {}

    //                                     life      size        grow   grav   fric  coll   bright anim  loop  aStart aEnd spin
    static final Style PLASMA_SPARK = new Style(10, 22, 0.20f, 0.45f, 0.3f, 0.55f, 0.96f, true, true, true, false, 1f, 0.9f, 0f);
    static final Style ENERGY_MOTE = new Style(18, 26, 0.25f, 0.55f, 0.2f, 0f, 1.0f, false, true, true, true, 0.4f, 1f, 0f);
    static final Style PLASMA_SMOKE = new Style(60, 110, 2.0f, 4.0f, 2.2f, -0.02f, 0.94f, false, false, true, false, 0.85f, 0f, 0.03f);
    static final Style REENTRY_FIRE = new Style(10, 18, 1.4f, 2.8f, 2.0f, -0.05f, 0.9f, false, true, true, false, 1f, 0.2f, 0.1f);
    static final Style EMBER = new Style(40, 90, 0.10f, 0.25f, 0.6f, 0.45f, 0.97f, true, true, true, false, 1f, 0.8f, 0.2f);
    static final Style DEBRIS = new Style(60, 120, 0.25f, 0.65f, 1.0f, 1.0f, 0.98f, true, false, false, false, 1f, 1f, 0.35f);
    static final Style FLASH = new Style(5, 8, 10f, 16f, 1.6f, 0f, 1.0f, false, true, true, false, 1f, 0f, 0f);
    static final Style SHOCK_DUST = new Style(30, 50, 2.5f, 4.5f, 2.0f, 0f, 0.92f, false, false, true, false, 0.8f, 0f, 0.02f);

    public static void register() {
        var reg = ParticleFactoryRegistry.getInstance();
        reg.register(ModParticles.PLASMA_SPARK, sprites -> factory(sprites, PLASMA_SPARK));
        reg.register(ModParticles.ENERGY_MOTE, sprites -> factory(sprites, ENERGY_MOTE));
        reg.register(ModParticles.PLASMA_SMOKE, sprites -> factory(sprites, PLASMA_SMOKE));
        reg.register(ModParticles.REENTRY_FIRE, sprites -> factory(sprites, REENTRY_FIRE));
        reg.register(ModParticles.EMBER, sprites -> factory(sprites, EMBER));
        reg.register(ModParticles.DEBRIS, sprites -> factory(sprites, DEBRIS));
        reg.register(ModParticles.FLASH, sprites -> factory(sprites, FLASH));
        reg.register(ModParticles.SHOCK_DUST, sprites -> factory(sprites, SHOCK_DUST));
    }

    private static ParticleFactory<DefaultParticleType> factory(
            net.minecraft.client.particle.SpriteProvider sprites, Style style) {
        return (type, world, x, y, z, vx, vy, vz) -> new RailgunParticle(world, x, y, z, vx, vy, vz, sprites, style);
    }
}
