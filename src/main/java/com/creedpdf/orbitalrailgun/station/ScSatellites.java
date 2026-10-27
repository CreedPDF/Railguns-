package com.creedpdf.orbitalrailgun.station;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every strike satellite, its owner and its charge, stored with the overworld so the tablet can reach a satellite in
 * an unloaded orbit. Charge keeps building at the rate the satellite last had while its station was loaded (a station
 * in orbit keeps flying while you are away), and is projected forward on demand. Times are overworld ticks.
 */
public final class ScSatellites extends PersistentState {
    private static final String NAME = "orbital_railgun_satellites";

    public static final class Rec {
        public UUID owner;
        public RegistryKey<World> dim;
        public BlockPos pos;
        public float charge;
        /** Charge per tick at the last update. */
        public float rate;
        public long time;
        public long fired = -100000;

        public float chargeAt(long now) {
            return Math.min(1f, charge + rate * Math.max(0, now - time));
        }
    }

    private final List<Rec> recs = new ArrayList<>();

    public static ScSatellites get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(ScSatellites::fromNbt, ScSatellites::new, NAME);
    }

    public static long now(MinecraftServer server) {
        return server.getOverworld().getTime();
    }

    @Nullable
    public Rec find(RegistryKey<World> dim, BlockPos pos) {
        for (Rec r : recs) if (r.dim == dim && r.pos.equals(pos)) return r;
        return null;
    }

    public Rec entry(UUID owner, RegistryKey<World> dim, BlockPos pos, long now) {
        Rec r = find(dim, pos);
        if (r == null) {
            r = new Rec();
            r.owner = owner;
            r.dim = dim;
            r.pos = pos.toImmutable();
            r.time = now;
            recs.add(r);
            markDirty();
        }
        return r;
    }

    /** Bring a record up to date and set its new charge rate. */
    public void update(Rec r, float rate, long now) {
        float c = r.chargeAt(now);
        if (Math.abs(c - r.charge) > 1e-4 || r.rate != rate) {
            r.charge = c;
            r.rate = rate;
            r.time = now;
            markDirty();
        }
    }

    public void remove(RegistryKey<World> dim, BlockPos pos) {
        if (recs.removeIf(r -> r.dim == dim && r.pos.equals(pos))) markDirty();
    }

    public List<Rec> owned(UUID owner) {
        List<Rec> out = new ArrayList<>();
        for (Rec r : recs) if (owner.equals(r.owner)) out.add(r);
        return out;
    }

    /** The owner's most-charged satellite. */
    @Nullable
    public Rec best(UUID owner, long now) {
        Rec best = null;
        for (Rec r : owned(owner)) if (best == null || r.chargeAt(now) > best.chargeAt(now)) best = r;
        return best;
    }

    public void spend(Rec r, long now) {
        r.charge = 0f;
        r.time = now;
        r.fired = now;
        markDirty();
    }

    private static ScSatellites fromNbt(NbtCompound nbt) {
        ScSatellites s = new ScSatellites();
        NbtList list = nbt.getList("Sats", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.getCompound(i);
            Rec r = new Rec();
            r.owner = c.containsUuid("Owner") ? c.getUuid("Owner") : null;
            r.dim = RegistryKey.of(RegistryKeys.WORLD, new Identifier(c.getString("Dim")));
            r.pos = BlockPos.fromLong(c.getLong("Pos"));
            r.charge = c.getFloat("Charge");
            r.rate = c.getFloat("Rate");
            r.time = c.getLong("Time");
            r.fired = c.getLong("Fired");
            s.recs.add(r);
        }
        return s;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Rec r : recs) {
            NbtCompound c = new NbtCompound();
            if (r.owner != null) c.putUuid("Owner", r.owner);
            c.putString("Dim", r.dim.getValue().toString());
            c.putLong("Pos", r.pos.asLong());
            c.putFloat("Charge", r.charge);
            c.putFloat("Rate", r.rate);
            c.putLong("Time", r.time);
            c.putLong("Fired", r.fired);
            list.add(c);
        }
        nbt.put("Sats", list);
        return nbt;
    }
}
