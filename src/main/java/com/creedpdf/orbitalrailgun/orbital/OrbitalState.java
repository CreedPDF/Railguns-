package com.creedpdf.orbitalrailgun.orbital;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every player's ARES-9 orbital platform, saved with the world: whether it is stowed or deployed
 * in orbit, its installed attachments (sent up from an orbital depot) and its cooldown.
 * Times are overworld game time.
 */
public final class OrbitalState extends PersistentState {
    public enum Phase { STOWED, DEPLOYING, DEPLOYED, STOWING }

    public static final class Rig {
        public Phase phase = Phase.STOWED;
        public long phaseStart;
        public int loadout;
        public long readyAt;
        /** Strike in progress until this time (no new orders). */
        public long busyUntil;

        /** Resolves finished transformations. */
        public Phase phase(long now) {
            if (phase == Phase.DEPLOYING && now - phaseStart >= OrbitalConfig.DEPLOY_TICKS) phase = Phase.DEPLOYED;
            if (phase == Phase.STOWING && now - phaseStart >= OrbitalConfig.STOW_TICKS) phase = Phase.STOWED;
            return phase;
        }
    }

    private final Map<UUID, Rig> rigs = new HashMap<>();

    public static OrbitalState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager()
                .getOrCreate(OrbitalState::fromNbt, OrbitalState::new, "orbital_railgun_orbital");
    }

    public Rig rig(UUID player) {
        return rigs.computeIfAbsent(player, k -> new Rig());
    }

    public void changed() {
        markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, Rig> e : rigs.entrySet()) {
            NbtCompound c = new NbtCompound();
            c.putUuid("Player", e.getKey());
            c.putInt("Phase", e.getValue().phase.ordinal());
            c.putLong("PhaseStart", e.getValue().phaseStart);
            c.putInt("Loadout", e.getValue().loadout);
            c.putLong("ReadyAt", e.getValue().readyAt);
            list.add(c);
        }
        nbt.put("Rigs", list);
        return nbt;
    }

    public static OrbitalState fromNbt(NbtCompound nbt) {
        OrbitalState s = new OrbitalState();
        NbtList list = nbt.getList("Rigs", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.getCompound(i);
            Rig r = new Rig();
            int ph = c.getInt("Phase");
            r.phase = Phase.values()[Math.max(0, Math.min(Phase.values().length - 1, ph))];
            r.phaseStart = c.getLong("PhaseStart");
            r.loadout = c.getInt("Loadout");
            r.readyAt = c.getLong("ReadyAt");
            s.rigs.put(c.getUuid("Player"), r);
        }
        return s;
    }
}
