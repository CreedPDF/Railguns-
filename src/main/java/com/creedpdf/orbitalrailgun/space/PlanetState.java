package com.creedpdf.orbitalrailgun.space;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;

import java.util.Arrays;

/**
 * Which planets ARES-0 has destroyed, and when (world time the beam hit). Stored with the
 * overworld and mirrored to every client.
 */
public final class PlanetState extends PersistentState {
    public static final Identifier SYNC = RailgunMod.id("planets");
    private static final String NAME = "orbital_railgun_planets";

    private final long[] blastStart = new long[Body.ALL.length];

    public PlanetState() {
        Arrays.fill(blastStart, -1L);
    }

    public static PlanetState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(PlanetState::fromNbt, PlanetState::new, NAME);
    }

    private static PlanetState fromNbt(NbtCompound nbt) {
        PlanetState s = new PlanetState();
        for (Body b : Body.ALL) {
            if (nbt.contains(b.id)) s.blastStart[b.ordinal()] = nbt.getLong(b.id);
        }
        return s;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        for (Body b : Body.ALL) {
            if (blastStart[b.ordinal()] >= 0) nbt.putLong(b.id, blastStart[b.ordinal()]);
        }
        return nbt;
    }

    public long blastStart(Body b) {
        return blastStart[b.ordinal()];
    }

    /** Targeted and on its way out (or already gone). */
    public boolean doomed(Body b) {
        return blastStart[b.ordinal()] >= 0;
    }

    /** Gone: past detonation. */
    public boolean destroyed(Body b, long now) {
        long s = blastStart[b.ordinal()];
        return s >= 0 && now >= s + Blast.DETONATE;
    }

    public void start(MinecraftServer server, Body b, long time) {
        if (!b.destroyable() || doomed(b)) return;
        blastStart[b.ordinal()] = time;
        markDirty();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) sync(p);
    }

    public void sync(ServerPlayerEntity p) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(blastStart.length);
        for (long v : blastStart) buf.writeLong(v);
        ServerPlayNetworking.send(p, SYNC, buf);
    }
}
