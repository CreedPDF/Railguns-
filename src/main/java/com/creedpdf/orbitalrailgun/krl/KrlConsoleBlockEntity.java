package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Fire control's memory: up to five linked launchers and the marked target. */
public class KrlConsoleBlockEntity extends BlockEntity {
    private final List<BlockPos> links = new ArrayList<>();
    @Nullable
    private BlockPos target;

    public KrlConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(Krl.CONSOLE_BE, pos, state);
    }

    /** The linked launchers that still exist (missing ones are dropped). */
    public List<BlockPos> links() {
        if (world != null && !world.isClient) {
            boolean changed = links.removeIf(p -> world.isChunkLoaded(p) && !world.getBlockState(p).isOf(Krl.LAUNCHER));
            if (changed) sync();
        }
        return links;
    }

    public boolean link(BlockPos p) {
        if (links.contains(p) || links().size() >= Krl.MAX_LINKS) return false;
        links.add(p.toImmutable());
        sync();
        return true;
    }

    public void unlink(int i) {
        if (i >= 0 && i < links.size()) {
            links.remove(i);
            sync();
        }
    }

    @Nullable
    public BlockPos target() {
        return target;
    }

    public void setTarget(@Nullable BlockPos t) {
        target = t == null ? null : t.toImmutable();
        sync();
    }

    private void sync() {
        markDirty();
        if (world != null && !world.isClient) world.updateListeners(pos, getCachedState(), getCachedState(), net.minecraft.block.Block.NOTIFY_LISTENERS);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    @Nullable
    @Override
    public net.minecraft.network.packet.Packet<net.minecraft.network.listener.ClientPlayPacketListener> toUpdatePacket() {
        return net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        NbtList l = new NbtList();
        for (BlockPos p : links) l.add(NbtLong.of(p.asLong()));
        nbt.put("links", l);
        if (target != null) nbt.putLong("target", target.asLong());
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        links.clear();
        for (NbtElement e : nbt.getList("links", NbtElement.LONG_TYPE)) links.add(BlockPos.fromLong(((NbtLong) e).longValue()));
        target = nbt.contains("target") ? BlockPos.fromLong(nbt.getLong("target")) : null;
    }
}
