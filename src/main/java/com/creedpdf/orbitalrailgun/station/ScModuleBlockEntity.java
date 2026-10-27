package com.creedpdf.orbitalrailgun.station;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * A station module: once a second it finds the grid it belongs to (the nearest loaded hub in reach), tells the hub what
 * it supplies and wants, and reads back the power the hub gave it. Linked state and power are synced for the renderer.
 */
public abstract class ScModuleBlockEntity extends BlockEntity {
    @Nullable protected BlockPos hub;
    /** kW the hub gave this module last second. */
    protected float power;

    protected ScModuleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public abstract ScModuleBlock.Kind kind();

    /** kW this module puts into the grid. */
    public float supply() {
        return 0f;
    }

    /** kW this module wants from the grid. */
    public float want() {
        return 0f;
    }

    @Nullable
    public BlockPos hub() {
        return hub;
    }

    public float power() {
        return power;
    }

    public boolean linked() {
        return hub != null;
    }

    /** Called every tick on both sides by the block's ticker. */
    public void tick() {
        if (world == null || world.isClient) return;
        if ((world.getTime() + pos.asLong()) % 20 != 0) return;
        ScHubBlockEntity h = ScGrid.hubFor(world, pos);
        BlockPos np = h == null ? null : h.getPos();
        float np2 = 0f;
        if (h != null) {
            h.report(this);
            np2 = h.allocation(pos);
        }
        boolean changed = !java.util.Objects.equals(np, hub) || Math.abs(np2 - power) > 0.01f;
        if (np != null && !np.equals(hub)) world.playSound(null, pos, Sc.S_LINK, SoundCategory.BLOCKS, 0.8f, 1.2f);
        hub = np;
        power = np2;
        if (changed) sync();
    }

    protected void sync() {
        markDirty();
        if (world != null && !world.isClient) world.updateListeners(pos, getCachedState(), getCachedState(), net.minecraft.block.Block.NOTIFY_LISTENERS);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        if (hub != null) nbt.putLong("Hub", hub.asLong());
        nbt.putFloat("Power", power);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        hub = nbt.contains("Hub") ? BlockPos.fromLong(nbt.getLong("Hub")) : null;
        power = nbt.getFloat("Power");
    }
}
