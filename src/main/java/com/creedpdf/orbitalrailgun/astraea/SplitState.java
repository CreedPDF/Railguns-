package com.creedpdf.orbitalrailgun.astraea;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether ASTRAEA has split the Earth - permanently. Stored with the overworld and mirrored to every client:
 * when it split (overworld time), how many strikes it has taken (each pushes the halves further apart), the split
 * plane (its normal in the Space dimension's frame), and the chasms torn across the overworld (each carved
 * progressively; the carving resumes after a restart).
 */
public final class SplitState extends PersistentState {
    private static final String NAME = "orbital_railgun_astraea_split";

    public long splitTime = -1;
    public int strikes;
    public Vec3d normal = new Vec3d(0, 0, 1);
    public final List<Chasm> chasms = new ArrayList<>();

    /** One chasm across the overworld: a centre line through (x, z) along `angle`, carved slice by slice from the middle. */
    public static final class Chasm {
        public double x, z, angle;
        public int half;        // slices each way from the middle
        public int done;        // slices carved each way so far
        public long seed;

        public NbtCompound write() {
            NbtCompound n = new NbtCompound();
            n.putDouble("x", x);
            n.putDouble("z", z);
            n.putDouble("a", angle);
            n.putInt("half", half);
            n.putInt("done", done);
            n.putLong("seed", seed);
            return n;
        }

        public static Chasm read(NbtCompound n) {
            Chasm c = new Chasm();
            c.x = n.getDouble("x");
            c.z = n.getDouble("z");
            c.angle = n.getDouble("a");
            c.half = n.getInt("half");
            c.done = n.getInt("done");
            c.seed = n.getLong("seed");
            return c;
        }

        public boolean finished() {
            return done >= half;
        }
    }

    public static SplitState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(SplitState::fromNbt, SplitState::new, NAME);
    }

    private static SplitState fromNbt(NbtCompound nbt) {
        SplitState s = new SplitState();
        s.splitTime = nbt.contains("time") ? nbt.getLong("time") : -1;
        s.strikes = nbt.getInt("strikes");
        s.normal = new Vec3d(nbt.getDouble("nx"), nbt.getDouble("ny"), nbt.getDouble("nz"));
        if (s.normal.lengthSquared() < 1e-6) s.normal = new Vec3d(0, 0, 1);
        NbtList list = nbt.getList("chasms", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) s.chasms.add(Chasm.read(list.getCompound(i)));
        return s;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putLong("time", splitTime);
        nbt.putInt("strikes", strikes);
        nbt.putDouble("nx", normal.x);
        nbt.putDouble("ny", normal.y);
        nbt.putDouble("nz", normal.z);
        NbtList list = new NbtList();
        for (Chasm c : chasms) list.add(c.write());
        nbt.put("chasms", list);
        return nbt;
    }

    public boolean split() {
        return splitTime >= 0;
    }

    public void sync(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) sync(p);
    }

    public void sync(ServerPlayerEntity p) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeLong(splitTime);
        b.writeVarInt(strikes);
        b.writeDouble(normal.x);
        b.writeDouble(normal.y);
        b.writeDouble(normal.z);
        b.writeVarInt(chasms.size());
        for (Chasm c : chasms) {
            b.writeDouble(c.x);
            b.writeDouble(c.z);
            b.writeDouble(c.angle);
        }
        ServerPlayNetworking.send(p, AstraeaNet.SPLIT, b);
    }
}
