package com.creedpdf.orbitalrailgun;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import net.minecraft.util.math.BlockPos;

/**
 * Side-safe hooks from common code into the client.
 *
 * <p>Common classes (the item, the entity) run on both the logical client and the dedicated
 * server. They must never reference client-only classes such as {@code MinecraftClient}
 * directly, or the dedicated server crashes with NoClassDefFoundError. Instead they call this
 * interface; the server keeps the no-op default, and {@code RailgunModClient} installs the real
 * implementation at startup.</p>
 */
public interface RailgunClientBridge {
    RailgunClientBridge NO_OP = new RailgunClientBridge() {};

    /** Opens the uplink tablet (zoom-in UI). {@code aimed} is the block the player looked at, if any. */
    default void openTablet(@org.jetbrains.annotations.Nullable BlockPos aimed) {}

    /** Camera shake for the local player, scaled by distance to the railgun. */
    default void shake(RailgunEntity railgun, float strength) {}

    /** Starts the looping re-entry roar that follows a falling platform (client only). */
    default void startReentrySound(RailgunEntity railgun) {}

    /** Called on the client whenever the synced state of a railgun changes. */
    default void onStateChanged(RailgunEntity railgun, RailgunState newState) {}
}
