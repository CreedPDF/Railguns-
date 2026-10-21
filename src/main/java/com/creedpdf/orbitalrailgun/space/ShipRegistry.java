package com.creedpdf.orbitalrailgun.space;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who owns a starship and where it is: waiting in orbit (callable from the uplink tablet) or
 * deployed as a particular entity in a particular world. Stored with the overworld.
 */
public final class ShipRegistry extends PersistentState {
    private static final String NAME = "orbital_railgun_ships";

    public static final int NONE = 0, ORBIT = 1, DEPLOYED = 2;

    public static final class Entry {
        public int state = NONE;
        @Nullable public UUID ship;
        public String dim = "";
        public BlockPos pos = BlockPos.ORIGIN;
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    public static ShipRegistry get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(ShipRegistry::fromNbt, ShipRegistry::new, NAME);
    }

    private static ShipRegistry fromNbt(NbtCompound nbt) {
        ShipRegistry r = new ShipRegistry();
        NbtList list = nbt.getList("Ships", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.getCompound(i);
            Entry e = new Entry();
            e.state = c.getInt("State");
            if (c.containsUuid("Ship")) e.ship = c.getUuid("Ship");
            e.dim = c.getString("Dim");
            e.pos = BlockPos.fromLong(c.getLong("Pos"));
            r.entries.put(c.getUuid("Owner"), e);
        }
        return r;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        entries.forEach((owner, e) -> {
            NbtCompound c = new NbtCompound();
            c.putUuid("Owner", owner);
            c.putInt("State", e.state);
            if (e.ship != null) c.putUuid("Ship", e.ship);
            c.putString("Dim", e.dim);
            c.putLong("Pos", e.pos.asLong());
            list.add(c);
        });
        nbt.put("Ships", list);
        return nbt;
    }

    public Entry entry(UUID owner) {
        return entries.computeIfAbsent(owner, k -> new Entry());
    }

    public void setOrbit(UUID owner) {
        Entry e = entry(owner);
        e.state = ORBIT;
        e.ship = null;
        markDirty();
    }

    public void setDeployed(UUID owner, UUID ship, String dim, BlockPos pos) {
        Entry e = entry(owner);
        e.state = DEPLOYED;
        e.ship = ship;
        e.dim = dim;
        e.pos = pos;
        markDirty();
    }

    /** Is {@code ship} the owner's current ship? (Stale copies discard themselves.) */
    public boolean isCurrent(UUID owner, UUID ship) {
        Entry e = entries.get(owner);
        return e == null || e.state != DEPLOYED || ship.equals(e.ship);
    }
}
