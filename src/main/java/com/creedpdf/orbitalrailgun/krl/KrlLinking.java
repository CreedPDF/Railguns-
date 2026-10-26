package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The launcher each player last picked for linking (sneak-use a launcher, then sneak-use a console). */
public final class KrlLinking {
    private KrlLinking() {}

    private record Pick(BlockPos pos, long at) {}

    /** A pick lasts a minute. */
    private static final long TTL = 1200;
    private static final Map<UUID, Pick> PICKS = new HashMap<>();

    public static void select(ServerPlayerEntity p, BlockPos launcher) {
        PICKS.put(p.getUuid(), new Pick(launcher.toImmutable(), p.getServerWorld().getTime()));
    }

    @Nullable
    public static BlockPos pending(ServerPlayerEntity p) {
        Pick k = PICKS.get(p.getUuid());
        if (k == null) return null;
        if (p.getServerWorld().getTime() - k.at > TTL || p.getServerWorld().getTime() < k.at) {
            PICKS.remove(p.getUuid());
            return null;
        }
        return k.pos;
    }

    public static void clear(ServerPlayerEntity p) {
        PICKS.remove(p.getUuid());
    }

    public static void clear() {
        PICKS.clear();
    }
}
