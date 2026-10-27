package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** The interceptor magazine's load: up to {@link Krl#MAG_CAP} missiles. */
public class KrlMagazineBlockEntity extends BlockEntity {
    private int rounds;

    public KrlMagazineBlockEntity(BlockPos pos, BlockState state) {
        super(Krl.MAGAZINE_BE, pos, state);
    }

    public int rounds() {
        return rounds;
    }

    /** Adds (or with a negative n removes) rounds; returns how many actually moved. */
    public int add(int n) {
        int before = rounds;
        rounds = Math.max(0, Math.min(Krl.MAG_CAP, rounds + n));
        if (rounds != before) {
            markDirty();
            if (world != null && !world.isClient) world.updateListeners(pos, getCachedState(), getCachedState(), net.minecraft.block.Block.NOTIFY_LISTENERS);
        }
        return Math.abs(rounds - before);
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
        nbt.putInt("rounds", rounds);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        rounds = Math.max(0, Math.min(Krl.MAG_CAP, nbt.getInt("rounds")));
    }
}
