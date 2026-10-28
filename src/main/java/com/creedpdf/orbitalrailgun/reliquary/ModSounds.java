package com.creedpdf.orbitalrailgun.reliquary;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/** Cosmic Reliquary sounds, all synthesised from scratch by tools/sfx/reliquary_sounds.py. */
public final class ModSounds {
    private ModSounds() {}

    public static final SoundEvent STAR_EATER_HUM = reg("star_eater_hum"), SINGULARITY_FORM = reg("singularity_form"),
            CORE_IMPLODE = reg("core_implode"), VOID_SLASH = reg("void_slash"), VOID_ARC = reg("void_arc"),
            CORE_REVOLVER_FIRE = reg("core_revolver_fire"), MOLTEN_SIZZLE = reg("molten_sizzle"), ORBITAL_LINK = reg("orbital_link");

    private static SoundEvent reg(String name) {
        Identifier id = CosmicReliquary.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    static void init() {}
}
