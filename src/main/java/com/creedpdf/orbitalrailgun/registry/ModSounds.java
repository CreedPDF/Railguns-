package com.creedpdf.orbitalrailgun.registry;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;

/**
 * Sound events. Every one maps (in assets/orbital_railgun/sounds.json) to an .ogg in
 * assets/orbital_railgun/sounds/ synthesized by tools/sfx/build_sounds.py. No vanilla audio.
 */
public final class ModSounds {
    private ModSounds() {}

    public static final SoundEvent UI_UPLINK = register("ui.uplink");
    public static final SoundEvent UI_COUNTDOWN_BEEP = register("ui.countdown_beep");
    public static final SoundEvent UI_DENIED = register("ui.denied");
    public static final SoundEvent UI_CLICK = register("ui.click");
    public static final SoundEvent UI_TARGET_LOCK = register("ui.target_lock");
    public static final SoundEvent RAILGUN_REENTRY = register("railgun.reentry");
    public static final SoundEvent RAILGUN_LAND = register("railgun.land");
    public static final SoundEvent RAILGUN_DEPLOY = register("railgun.deploy");
    public static final SoundEvent RAILGUN_CHARGE_LOOP = register("railgun.charge_loop");
    public static final SoundEvent RAILGUN_FIRE = register("railgun.fire");
    public static final SoundEvent RAILGUN_IMPACT = register("railgun.impact");
    public static final SoundEvent RAILGUN_POWER_DOWN = register("railgun.power_down");

    private static SoundEvent register(String path) {
        var id = RailgunMod.id(path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** Forces class loading so the static fields register during mod init. */
    public static void init() {}
}
