package com.creedpdf.orbitalrailgun.space;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** The Space dimension and the dimensions of the landable planets. */
public final class SpaceDims {
    private SpaceDims() {}

    public static final RegistryKey<World> SPACE = key("space");
    public static final RegistryKey<World> MOON = key("moon");
    public static final RegistryKey<World> MARS = key("mars");
    public static final RegistryKey<World> VENUS = key("venus");
    public static final RegistryKey<World> EUROPA = key("europa");

    private static RegistryKey<World> key(String id) {
        return RegistryKey.of(RegistryKeys.WORLD, RailgunMod.id(id));
    }

    public static boolean isSpace(World w) {
        return w.getRegistryKey() == SPACE;
    }

    /** The planet whose surface this world is (never Earth). */
    @Nullable
    public static Body planetOf(World w) {
        return planetOf(w.getRegistryKey());
    }

    @Nullable
    public static Body planetOf(RegistryKey<World> key) {
        if (key == MOON) return Body.MOON;
        if (key == MARS) return Body.MARS;
        if (key == VENUS) return Body.VENUS;
        if (key == EUROPA) return Body.EUROPA;
        return null;
    }

    /** No breathable air: space and every planet. */
    public static boolean isAirless(World w) {
        return isSpace(w) || planetOf(w) != null;
    }

    public static float gravity(World w) {
        if (isSpace(w)) return 0f;
        Body b = planetOf(w);
        return b == null ? 1f : b.gravity;
    }
}
