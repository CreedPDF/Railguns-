package com.creedpdf.orbitalrailgun.krl;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * KRL networking.
 * <pre>
 *  C2S  krl_poll    BlockPos console                         fire control asks for the board's state
 *  C2S  krl_map     BlockPos console                         ... and for the terrain survey
 *  C2S  krl_target  BlockPos console, int x, int z           mark the aim point (the server finds the ground there)
 *  C2S  krl_launch  BlockPos console, varint mask, bool ride light the selected launchers' motors
 *  C2S  krl_abort   BlockPos console, varint mask            call off their countdowns
 *  C2S  krl_unlink  BlockPos console, varint index           forget a launcher
 *  C2S  krl_skip    (empty)                                  leave the ride
 *  S2C  krl_state   bool open, BlockPos console, target?, launchers[]   the board
 *  S2C  krl_map     BlockPos console, survey                 the terrain survey
 *  S2C  krl_flight  flight                                   a spear on the pad or in the air (sent to the whole world)
 *  S2C  krl_end     varint id                                a countdown was called off
 *  S2C  krl_ride    varint id, bool                          you are (no longer) riding this flight
 * </pre>
 * Every request re-checks that the player is at the console; the server decides everything.
 */
public final class KrlNet {
    private KrlNet() {}

    public static final Identifier POLL = RailgunMod.id("krl_poll"), MAP = RailgunMod.id("krl_map"), TARGET = RailgunMod.id("krl_target"),
            LAUNCH = RailgunMod.id("krl_launch"), ABORT = RailgunMod.id("krl_abort"), UNLINK = RailgunMod.id("krl_unlink"), SKIP = RailgunMod.id("krl_skip"),
            STATE = RailgunMod.id("krl_state"), MAP_DATA = RailgunMod.id("krl_map_data"), FLIGHT = RailgunMod.id("krl_flight"),
            END = RailgunMod.id("krl_end"), RIDE = RailgunMod.id("krl_ride");

    /** Launcher states on the board. */
    public static final int L_UNKNOWN = 0, L_EMPTY = 1, L_READY = 2, L_BURNING = 3, L_GONE = 4;
    /** Closest allowed aim point (the spear needs room to turn over). */
    public static final double MIN_RANGE = 48;

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(POLL, (server, p, h, buf, s) -> {
            BlockPos c = buf.readBlockPos();
            server.execute(() -> {
                KrlConsoleBlockEntity be = console(p, c);
                if (be != null) sendState(p, be, false);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(MAP, (server, p, h, buf, s) -> {
            BlockPos c = buf.readBlockPos();
            server.execute(() -> {
                if (console(p, c) != null) KrlMap.request(p, c);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(TARGET, (server, p, h, buf, s) -> {
            BlockPos c = buf.readBlockPos();
            int x = buf.readInt(), z = buf.readInt();
            boolean clear = buf.readBoolean();
            server.execute(() -> target(p, c, x, z, clear));
        });
        ServerPlayNetworking.registerGlobalReceiver(LAUNCH, (server, p, h, buf, s) -> {
            BlockPos c = buf.readBlockPos();
            int mask = buf.readVarInt();
            boolean ride = buf.readBoolean();
            server.execute(() -> launch(p, c, mask, ride));
        });
        ServerPlayNetworking.registerGlobalReceiver(ABORT, (server, p, h, buf, s) -> {
            BlockPos c = buf.readBlockPos();
            int mask = buf.readVarInt();
            server.execute(() -> abort(p, c, mask));
        });
        ServerPlayNetworking.registerGlobalReceiver(UNLINK, (server, p, h, buf, s) -> {
            BlockPos c = buf.readBlockPos();
            int i = buf.readVarInt();
            server.execute(() -> {
                KrlConsoleBlockEntity be = console(p, c);
                if (be == null) return;
                be.unlink(i);
                p.getServerWorld().playSound(null, c, Krl.S_CLICK, SoundCategory.BLOCKS, 0.8f, 0.7f);
                sendState(p, be, false);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SKIP, (server, p, h, buf, s) -> server.execute(() -> KrlServer.release(p)));
    }

    // ------------------------------------------------------------------------------------------- requests

    private static KrlConsoleBlockEntity console(ServerPlayerEntity p, BlockPos c) {
        ServerWorld w = p.getServerWorld();
        if (!w.isChunkLoaded(c) || p.squaredDistanceTo(Vec3d.ofCenter(c)) > (Krl.USE_RANGE + 2) * (Krl.USE_RANGE + 2)) return null;
        return w.getBlockEntity(c) instanceof KrlConsoleBlockEntity be ? be : null;
    }

    private static void target(ServerPlayerEntity p, BlockPos c, int x, int z, boolean clear) {
        KrlConsoleBlockEntity be = console(p, c);
        if (be == null) return;
        ServerWorld w = p.getServerWorld();
        if (clear) {
            be.setTarget(null);
        } else {
            double dx = x - c.getX(), dz = z - c.getZ();
            double lim = Krl.RANGE + Krl.LINK_RANGE;
            if (dx * dx + dz * dz > lim * lim || !w.getWorldBorder().contains(x, z)) {
                p.sendMessage(Text.literal("Aim point outside the survey.").formatted(Formatting.GRAY), true);
                return;
            }
            int y = KrlMap.surfaceY(w, x, z);
            be.setTarget(new BlockPos(x, y, z));
            w.playSound(null, c, Krl.S_CLICK, SoundCategory.BLOCKS, 0.7f, 1.4f);
        }
        sendState(p, be, false);
    }

    private static void launch(ServerPlayerEntity p, BlockPos c, int mask, boolean ride) {
        KrlConsoleBlockEntity be = console(p, c);
        if (be == null) return;
        BlockPos t = be.target();
        ServerWorld w = p.getServerWorld();
        if (t == null) {
            p.sendMessage(Text.literal("No aim point marked.").formatted(Formatting.GRAY), true);
            return;
        }
        List<BlockPos> links = be.links();
        int lit = 0, refused = 0;
        for (int i = 0; i < links.size(); i++) {
            if ((mask & (1 << i)) == 0) continue;
            BlockPos lp = links.get(i);
            if (!w.isChunkLoaded(lp) || !(w.getBlockEntity(lp) instanceof KrlLauncherBlockEntity l) || !l.loaded() || l.igniting()) {
                refused++;
                continue;
            }
            double dx = t.getX() - lp.getX(), dz = t.getZ() - lp.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > Krl.RANGE || d < MIN_RANGE) {
                refused++;
                continue;
            }
            if (KrlServer.ignite(w, l, t, p, ride && lit == 0) != null) lit++;
            else refused++;
        }
        if (lit > 0) {
            w.playSound(null, c, Krl.S_ARM, SoundCategory.BLOCKS, 1.6f, 1f);
            p.sendMessage(Text.literal("LAUNCH - " + lit + " rail" + (lit > 1 ? "s" : "") + " slewing to target. Ignition, then liftoff.").formatted(Formatting.YELLOW), true);
        } else if (refused > 0) {
            p.sendMessage(Text.literal("No launcher could take the shot (empty, burning, unloaded or out of range).").formatted(Formatting.GRAY), true);
        }
        sendState(p, be, false);
    }

    private static void abort(ServerPlayerEntity p, BlockPos c, int mask) {
        KrlConsoleBlockEntity be = console(p, c);
        if (be == null) return;
        ServerWorld w = p.getServerWorld();
        List<BlockPos> links = be.links();
        int n = 0;
        for (int i = 0; i < links.size(); i++) {
            if ((mask & (1 << i)) == 0) continue;
            BlockPos lp = links.get(i);
            if (w.isChunkLoaded(lp) && w.getBlockEntity(lp) instanceof KrlLauncherBlockEntity l && l.igniting() && KrlServer.abort(l.flight())) n++;
        }
        if (n > 0) p.sendMessage(Text.literal("ABORT - " + n + " launch" + (n > 1 ? "es" : "") + " safed.").formatted(Formatting.YELLOW), true);
        sendState(p, be, false);
    }

    // ------------------------------------------------------------------------------------------- answers

    public static void open(ServerPlayerEntity p, KrlConsoleBlockEntity be) {
        sendState(p, be, true);
    }

    public static void sendState(ServerPlayerEntity p, KrlConsoleBlockEntity be, boolean open) {
        ServerWorld w = p.getServerWorld();
        PacketByteBuf b = PacketByteBufs.create();
        b.writeBoolean(open);
        b.writeBlockPos(be.getPos());
        b.writeInt(be.getCachedState().get(KrlConsoleBlock.FACING).getHorizontal());
        BlockPos t = be.target();
        b.writeBoolean(t != null);
        if (t != null) b.writeBlockPos(t);
        List<BlockPos> links = be.links();
        b.writeVarInt(links.size());
        for (BlockPos lp : links) {
            b.writeBlockPos(lp);
            int st = L_UNKNOWN, cd = -1, fl = -1;
            long launched = -1;
            if (w.isChunkLoaded(lp) && w.getBlockEntity(lp) instanceof KrlLauncherBlockEntity l) {
                st = l.igniting() ? L_BURNING : l.loaded() ? L_READY : L_EMPTY;
                cd = l.countdown();
                fl = l.flight();
                launched = l.launchedAt();
            } else if (w.isChunkLoaded(lp)) st = L_GONE;
            b.writeVarInt(st);
            b.writeVarInt(cd + 1);
            b.writeInt(fl);
            b.writeLong(launched);
        }
        b.writeLong(w.getTime());
        ServerPlayNetworking.send(p, STATE, b);
    }

    public static void sendMap(ServerPlayerEntity p, BlockPos console, KrlMap.Survey s) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeBlockPos(console);
        b.writeInt(s.cx());
        b.writeInt(s.cz());
        b.writeVarInt(KrlMap.N);
        b.writeVarInt(KrlMap.CELL);
        for (short h : s.height()) b.writeShort(h);
        b.writeByteArray(s.kind());
        b.writeVarInt(p.getServerWorld().getSeaLevel());
        ServerPlayNetworking.send(p, MAP_DATA, b);
    }

    private static PacketByteBuf flightBuf(KrlServer.Flight f) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeVarInt(f.id);
        b.writeIdentifier(f.world.getValue());
        b.writeBlockPos(f.pad);
        Vec3d l = f.launch, t = f.target;
        b.writeDouble(l.x);
        b.writeDouble(l.y);
        b.writeDouble(l.z);
        b.writeDouble(t.x);
        b.writeDouble(t.y);
        b.writeDouble(t.z);
        b.writeLong(f.liftoff);
        b.writeBoolean(f.rider != null);
        if (f.rider != null) b.writeUuid(f.rider);
        b.writeVarInt(f.livery);
        return b;
    }

    public static void sendFlight(ServerWorld w, KrlServer.Flight f) {
        for (ServerPlayerEntity p : w.getPlayers()) ServerPlayNetworking.send(p, FLIGHT, flightBuf(f));
    }

    public static void sendFlight(ServerPlayerEntity p, KrlServer.Flight f) {
        ServerPlayNetworking.send(p, FLIGHT, flightBuf(f));
    }

    public static void sendFlightEnd(ServerWorld w, int id) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            PacketByteBuf b = PacketByteBufs.create();
            b.writeVarInt(id);
            ServerPlayNetworking.send(p, END, b);
        }
    }

    public static void sendRide(ServerPlayerEntity p, int id, boolean on) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeVarInt(id);
        b.writeBoolean(on);
        ServerPlayNetworking.send(p, RIDE, b);
    }
}
