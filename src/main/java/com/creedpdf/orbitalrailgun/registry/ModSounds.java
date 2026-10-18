package com.creedpdf.orbitalrailgun.registry;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;

/**
 * Sound events. The actual .ogg files are mapped in assets/orbital_railgun/sounds.json. Out of
 * the box they point at vanilla sound files so the mod works without custom audio; drop your
 * own .ogg files into assets/orbital_railgun/sounds/ and repoint sounds.json to replace them.
 */
public final class ModSounds {
    private ModSounds() {}

    public static final SoundEvent UI_UPLINK = register("ui.uplink");
    public static final SoundEvent UI_COUNTDOWN_BEEP = register("ui.countdown_beep");
    public static final SoundEvent UI_DENIED = register("ui.denied");
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
