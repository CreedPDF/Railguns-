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

    static final Style SPALL = new Style(20, 40, 0.12f, 0.3f, 0.6f, 0.6f, 0.97f, true, true, false, false, 1f, 0.9f, 0.4f);
    static final Style THERMITE_FLAME = new Style(14, 26, 0.8f, 1.8f, 1.8f, -0.06f, 0.92f, false, true, true, false, 1f, 0.1f, 0.08f);
    static final Style FROST = new Style(30, 70, 0.25f, 0.7f, 0.8f, 0.02f, 0.94f, false, true, true, true, 0.95f, 0f, 0.06f);
    static final Style ION_ARC = new Style(3, 7, 0.6f, 1.4f, 1.2f, 0f, 0.8f, false, true, true, true, 1f, 0.2f, 0f);
    static final Style BOMBLET = new Style(40, 60, 0.5f, 0.7f, 1.0f, 0f, 1.0f, false, true, true, true, 1f, 1f, 0f);
    static final Style SINGULARITY_MOTE = new Style(20, 34, 0.3f, 0.7f, 0.2f, 0f, 1.0f, false, true, true, true, 0.3f, 1f, 0.15f);
    static final Style FALLOUT = new Style(120, 220, 3.0f, 6.0f, 2.6f, -0.012f, 0.97f, false, false, true, false, 0.9f, 0f, 0.01f);
    static final Style LENS_GLINT = new Style(6, 10, 1.0f, 2.2f, 0.3f, 0f, 1.0f, false, true, true, false, 1f, 0f, 0f);
    static final Style COOLANT_VAPOR = new Style(30, 50, 0.8f, 1.6f, 2.4f, -0.01f, 0.93f, false, false, true, false, 0.8f, 0f, 0.03f);
    static final Style OVERCLOCK_SPARK = new Style(8, 14, 0.15f, 0.35f, 0.5f, 0f, 0.9f, false, true, true, true, 1f, 0.6f, 0f);
    static final Style SHIELD_SPARK = new Style(10, 18, 0.5f, 1.0f, 1.6f, 0f, 0.85f, false, true, true, false, 1f, 0f, 0f);
    static final Style SENTRY_BOLT = new Style(2, 4, 0.35f, 0.5f, 0.6f, 0f, 1.0f, false, true, true, true, 1f, 0.5f, 0f);
    static final Style THRUSTER_FLAME = new Style(8, 16, 0.9f, 1.8f, 2.2f, 0f, 0.9f, false, true, true, false, 1f, 0f, 0.1f);
    static final Style SEISMIC_CRACK = new Style(30, 50, 1.2f, 2.4f, 1.4f, 0f, 0.9f, false, false, true, false, 0.9f, 0f, 0f);
    static final Style SHELL_CASING = new Style(80, 120, 0.5f, 0.6f, 1.0f, 1.2f, 0.98f, true, false, false, false, 1f, 1f, 0.5f);
    static final Style PLASMA_POOL = new Style(24, 44, 0.8f, 2.0f, 1.6f, -0.03f, 0.9f, false, true, true, true, 0.9f, 0f, 0.05f);
    static final Style TRANSFER_MOTE = new Style(14, 22, 0.3f, 0.6f, 0.4f, 0f, 1.0f, false, true, true, true, 0.6f, 1f, 0.1f);

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
        reg.register(ModParticles.SPALL, sprites -> factory(sprites, SPALL));
        reg.register(ModParticles.THERMITE_FLAME, sprites -> factory(sprites, THERMITE_FLAME));
        reg.register(ModParticles.FROST, sprites -> factory(sprites, FROST));
        reg.register(ModParticles.ION_ARC, sprites -> factory(sprites, ION_ARC));
        reg.register(ModParticles.BOMBLET, sprites -> factory(sprites, BOMBLET));
        reg.register(ModParticles.SINGULARITY_MOTE, sprites -> factory(sprites, SINGULARITY_MOTE));
        reg.register(ModParticles.FALLOUT, sprites -> factory(sprites, FALLOUT));
        reg.register(ModParticles.LENS_GLINT, sprites -> factory(sprites, LENS_GLINT));
        reg.register(ModParticles.COOLANT_VAPOR, sprites -> factory(sprites, COOLANT_VAPOR));
        reg.register(ModParticles.OVERCLOCK_SPARK, sprites -> factory(sprites, OVERCLOCK_SPARK));
        reg.register(ModParticles.SHIELD_SPARK, sprites -> factory(sprites, SHIELD_SPARK));
        reg.register(ModParticles.SENTRY_BOLT, sprites -> factory(sprites, SENTRY_BOLT));
        reg.register(ModParticles.THRUSTER_FLAME, sprites -> factory(sprites, THRUSTER_FLAME));
        reg.register(ModParticles.SEISMIC_CRACK, sprites -> factory(sprites, SEISMIC_CRACK));
        reg.register(ModParticles.SHELL_CASING, sprites -> factory(sprites, SHELL_CASING));
        reg.register(ModParticles.PLASMA_POOL, sprites -> factory(sprites, PLASMA_POOL));
        reg.register(ModParticles.TRANSFER_MOTE, sprites -> factory(sprites, TRANSFER_MOTE));
    }

    private static ParticleFactory<DefaultParticleType> factory(
            net.minecraft.client.particle.SpriteProvider sprites, Style style) {
        return (type, world, x, y, z, vx, vy, vz) -> new RailgunParticle(world, x, y, z, vx, vy, vz, sprites, style);
    }
}
