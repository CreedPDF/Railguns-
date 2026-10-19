package com.creedpdf.orbitalrailgun.registry;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
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
    public static final SoundEvent RAILGUN_PANEL = register("railgun.panel");
    public static final SoundEvent RAILGUN_TRANSFORM = register("railgun.transform");
    public static final SoundEvent RAILGUN_PACK = register("railgun.pack");
    public static final SoundEvent RAILGUN_LAUNCH = register("railgun.launch");
    public static final SoundEvent UI_TABLET_OPEN = register("ui.tablet_open");
    public static final SoundEvent UI_TABLET_CLOSE = register("ui.tablet_close");

    // ---- Loadout / orbital storage ----------------------------------------------------------
    public static final SoundEvent UI_LOADOUT_SELECT = register("ui.loadout_select");
    public static final SoundEvent UI_LOADOUT_ORDER = register("ui.loadout_order");
    public static final SoundEvent DRONE_DESCENT = register("drone.descent");
    public static final SoundEvent DRONE_RETRO = register("drone.retro");
    public static final SoundEvent DRONE_DOCK = register("drone.dock");
    public static final SoundEvent DRONE_ASCEND = register("drone.ascend");
    public static final SoundEvent RAILGUN_ATTACH = register("railgun.attach");
    public static final SoundEvent CLAMP_THRUSTER = register("railgun.clamp_thruster");
    public static final SoundEvent SCAN_CHARGE = register("scan.charge");
    public static final SoundEvent SCAN_PULSE = register("scan.pulse");
    public static final SoundEvent SCAN_PING = register("scan.ping");

    private static final java.util.Map<Loadout.Option, SoundEvent> SIGNATURE = new java.util.EnumMap<>(Loadout.Option.class);
    private static final java.util.Map<Loadout.Option, SoundEvent> IMPACT = new java.util.EnumMap<>(Loadout.Option.class);
    static {
        for (Loadout.Option o : Loadout.Option.values()) {
            switch (o.category) {
                case MUZZLE -> SIGNATURE.put(o, register("muzzle." + o.id));
                case AMMO -> {
                    SIGNATURE.put(o, register("ammo." + o.id + ".fire"));
                    IMPACT.put(o, register("ammo." + o.id + ".impact"));
                }
                case MODULE -> SIGNATURE.put(o, register("module." + o.id));
            }
        }
    }
    public static final SoundEvent MODULE_SHIELD_BLOCK = register("module.shield_block");

    /** Muzzle firing signature, ammunition fire sound, or the module's own sound. */
    public static SoundEvent signature(Loadout.Option o) {
        return SIGNATURE.get(o);
    }

    /** Ammunition impact sound. */
    public static SoundEvent impact(Loadout.Option ammo) {
        return IMPACT.get(ammo);
    }

    private static SoundEvent register(String path) {
        var id = RailgunMod.id(path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** Forces class loading so the static fields register during mod init. */
    public static void init() {}
}
