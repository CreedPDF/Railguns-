package com.creedpdf.orbitalrailgun.station;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A strike satellite docked to the station. It draws up to {@link Sc#SAT_KW} kW while charging and is full after
 * {@link Sc#SAT_CHARGE_TICKS} ticks at full power. The charge lives in {@link ScSatellites}; this entity keeps it up to
 * date while loaded and syncs it (and when it last fired) for the renderer.
 */
public class ScSatelliteBlockEntity extends ScModuleBlockEntity {
    @Nullable private UUID owner;
    private float charge;
    private long fired = -100000;

    public ScSatelliteBlockEntity(BlockPos pos, BlockState state) {
        super(Sc.SATELLITE_BE, pos, state);
    }

    @Override
    public ScModuleBlock.Kind kind() {
        return ScModuleBlock.Kind.SATELLITE;
    }

    void setOwner(UUID owner) {
        this.owner = owner;
        sync();
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public float charge() {
        return charge;
    }

    public long fired() {
        return fired;
    }

    @Override
    public float want() {
        return charge < 0.999f ? Sc.SAT_KW : 0f;
    }

    @Override
    public void tick() {
        super.tick();
        if (world == null || world.isClient || owner == null) return;
        if ((world.getTime() + pos.asLong()) % 20 != 5) return;
        ScSatellites store = ScSatellites.get(world.getServer());
        long now = ScSatellites.now(world.getServer());
        ScSatellites.Rec r = store.entry(owner, world.getRegistryKey(), pos, now);
        store.update(r, power / Sc.SAT_KW / Sc.SAT_CHARGE_TICKS, now);
        float c = r.chargeAt(now);
        // keep its own fire time in this world's clock for the renderer
        long firedHere = r.fired < 0 ? -100000 : world.getTime() - (now - r.fired);
        if (Math.abs(c - charge) > 0.004f || (c >= 1f) != (charge >= 1f) || Math.abs(firedHere - fired) > 2) {
            charge = c;
            fired = firedHere;
            sync();
        }
    }

    /** Called by the strike when this satellite fires (if loaded). */
    void firedNow(ServerWorld w) {
        charge = 0f;
        fired = w.getTime();
        sync();
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        if (owner != null) nbt.putUuid("Owner", owner);
        nbt.putFloat("Charge", charge);
        nbt.putLong("Fired", fired);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        owner = nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null;
        charge = nbt.getFloat("Charge");
        fired = nbt.contains("Fired") ? nbt.getLong("Fired") : -100000;
    }
}
