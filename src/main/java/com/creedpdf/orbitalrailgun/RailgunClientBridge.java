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

    /** Open the railgun control screen for this entity. */
    default void openControlScreen(RailgunEntity railgun) {}

    /** Send a deploy request for the block the player is looking at. */
    default void requestDeploy(BlockPos target) {}

    /** Called on the client whenever the synced state of a railgun changes. */
    default void onStateChanged(RailgunEntity railgun, RailgunState newState) {}
}
