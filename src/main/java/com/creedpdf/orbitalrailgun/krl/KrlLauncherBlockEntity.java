package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;


/**
 * The launcher's state: whether a spear sits on the rail, and once fire control launches it, the minute the motor
 * burns on the pad (ignition time, target, who fired it, whether they ride along) before the spear leaves.
 */
public class KrlLauncherBlockEntity extends BlockEntity {
    private boolean loaded;
    /** World time the motor lit, or -1. */
    private long ignitedAt = -1;
    /** World time the last spear left the rail (the clamps and the pad remember it). */
    private long launchedAt = -1;
    /** The flight this launcher is counting down for (KrlServer), or -1. */
    private int flight = -1;
    /** The loaded missile's paint scheme. */
    private KrlLoadout missile = KrlLoadout.DEFAULT;

    public KrlLauncherBlockEntity(BlockPos pos, BlockState state) {
        super(Krl.LAUNCHER_BE, pos, state);
    }

    public boolean loaded() {
        return loaded;
    }

    public boolean igniting() {
        return ignitedAt >= 0;
    }

    public long ignitedAt() {
        return ignitedAt;
    }

    public long launchedAt() {
        return launchedAt;
    }

    public int flight() {
        return flight;
    }

    public int livery() {
        return missile.livery();
    }

    public KrlLoadout missile() {
        return missile;
    }

    /** Ticks left before the spear leaves, or -1. */
    public int countdown() {
        if (ignitedAt < 0 || world == null) return -1;
        return (int) Math.max(0, Krl.COUNTDOWN - (world.getTime() - ignitedAt));
    }


    public String statusLine() {
        if (igniting()) return String.format(java.util.Locale.ROOT, "KRL-1  //  LAUNCH SEQUENCE  T-%.1f", countdown() / 20.0);
        return loaded ? "KRL-1  //  " + missile.summary() + "  //  AWAITING FIRE CONTROL" : "KRL-1  //  RAIL EMPTY  //  LOAD A KINETIC MISSILE";
    }

    public void load(KrlLoadout missile) {
        loaded = true;
        this.missile = missile;
        sync();
    }

    /** Fire control lit the motor; {@link KrlServer} owns the flight from here. */
    void ignited(int flightId) {
        ignitedAt = world == null ? 0 : world.getTime();
        flight = flightId;
        sync();
    }

    /** The countdown was called off: the spear stays on the rail. */
    void aborted() {
        ignitedAt = -1;
        flight = -1;
        sync();
    }

    /** The spear left the rail. */
    void lifted() {
        loaded = false;
        ignitedAt = -1;
        flight = -1;
        launchedAt = world == null ? 0 : world.getTime();
        sync();
    }

    public static void serverTick(World world, BlockPos pos, BlockState state, KrlLauncherBlockEntity be) {
        if (!be.igniting()) return;
        // a countdown the server no longer knows about (aborted from afar, or lost with a restart): back to standby
        if (KrlServer.flight(be.flight) == null) {
            be.aborted();
            return;
        }
        long t = world.getTime() - be.ignitedAt;
        // once the motor is lit, the exhaust scorches anything on or behind the pad
        if (t >= Krl.SLEW && t % 5 == 0) {
            Box blast = new Box(pos).expand(3, 0, 3).stretch(0, 3, 0);
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, blast, e -> e.isAlive() && !KrlServer.riding(e))) {
                e.setOnFireFor(4);
                e.damage(world.getDamageSources().onFire(), 3f);
            }
        }
    }

    public static void clientTick(World world, BlockPos pos, BlockState state, KrlLauncherBlockEntity be) {
        KrlClientHooks.launcherTick(be);
    }

    private void sync() {
        markDirty();
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putBoolean("loaded", loaded);
        nbt.putLong("ignited", ignitedAt);
        nbt.putLong("launched", launchedAt);
        nbt.putInt("flight", flight);
        nbt.put("missile", missile.toNbt());
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        loaded = nbt.getBoolean("loaded");
        ignitedAt = nbt.contains("ignited") ? nbt.getLong("ignited") : -1;
        launchedAt = nbt.contains("launched") ? nbt.getLong("launched") : -1;
        flight = nbt.contains("flight") ? nbt.getInt("flight") : -1;
        missile = nbt.contains("missile") ? KrlLoadout.fromNbt(nbt.getCompound("missile")) : new KrlLoadout(0, 0, 0, nbt.getInt("livery"));
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
}
