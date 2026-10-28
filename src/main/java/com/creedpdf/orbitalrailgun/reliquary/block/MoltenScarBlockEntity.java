package com.creedpdf.orbitalrailgun.reliquary.block;

import com.creedpdf.orbitalrailgun.reliquary.ModBlocks;
import com.creedpdf.orbitalrailgun.reliquary.logic.MoltenScars;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A scar's heat: who made it (the burns are credited to them) and its age. It registers itself with
 * {@link MoltenScars} (the world tick handler that burns whoever stands on it) while it exists, and removes the block
 * when it has cooled ({@link #LIFE} ticks). The age is synced so the client can fade its glow.
 */
public class MoltenScarBlockEntity extends BlockEntity {
    public static final int LIFE = 600;
    @Nullable private UUID owner;
    private int age;
    private boolean registered;

    public MoltenScarBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.MOLTEN_SCAR_BE, pos, state);
    }

    public void reheat(PlayerEntity by) {
        owner = by.getUuid();
        age = 0;
        markDirty();
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public int age() {
        return age;
    }

    void serverTick() {
        if (world == null) return;
        if (!registered) {
            MoltenScars.add(world, pos);
            registered = true;
        }
        if (++age >= LIFE) world.setBlockState(pos, Blocks.AIR.getDefaultState());
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        if (world != null && !world.isClient) MoltenScars.remove(world, pos);
        registered = false;
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
        if (owner != null) nbt.putUuid("Owner", owner);
        nbt.putInt("Age", age);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        owner = nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null;
        age = nbt.getInt("Age");
    }
}
