package com.creedpdf.orbitalrailgun.station;

import com.creedpdf.orbitalrailgun.space.SpaceDims;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

/** A solar array. Its blankets roll out over {@link Sc#SOLAR_DEPLOY} ticks after placement; then it powers the grid. */
public class ScSolarBlockEntity extends ScModuleBlockEntity {
    /** World time it was placed (drives the roll-out on the client too). */
    private long placedAt = Long.MIN_VALUE;

    public ScSolarBlockEntity(BlockPos pos, BlockState state) {
        super(Sc.SOLAR_BE, pos, state);
    }

    @Override
    public ScModuleBlock.Kind kind() {
        return ScModuleBlock.Kind.SOLAR;
    }

    void placed() {
        if (world != null) placedAt = world.getTime();
        sync();
    }

    public long placedAt() {
        return placedAt;
    }

    public boolean deployed() {
        return world == null || placedAt == Long.MIN_VALUE || world.getTime() - placedAt >= Sc.SOLAR_DEPLOY;
    }

    /** 0..1 how far the blankets have rolled out (eased). */
    public float extension(float pt) {
        if (world == null || placedAt == Long.MIN_VALUE) return 1f;
        float t = (world.getTime() - placedAt + pt) / Sc.SOLAR_DEPLOY;
        if (t >= 1f) return 1f;
        t = Math.max(0f, t);
        return t * t * (3 - 2 * t);
    }

    /** Light on the cells: full in orbit, half in clear daylight on a planet, nothing at night or under cover. */
    public float efficiency() {
        if (world == null) return 0f;
        if (SpaceDims.isSpace(world)) return 1f;
        if (!world.isDay() || !world.isSkyVisible(pos.up(3))) return 0f;
        return 0.5f * (world.isRaining() ? 0.5f : 1f);
    }

    @Override
    public float supply() {
        return deployed() ? Sc.SOLAR_KW * efficiency() : 0f;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLong("Placed", placedAt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        placedAt = nbt.contains("Placed") ? nbt.getLong("Placed") : Long.MIN_VALUE;
    }
}
