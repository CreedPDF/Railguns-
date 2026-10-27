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
            END = RailgunMod.id("krl_end"), RIDE = RailgunMod.id("krl_ride"), TILE = RailgunMod.id("krl_tile"),
            STATION_OPEN = RailgunMod.id("krl_station_open"), STATION_APPLY = RailgunMod.id("krl_station_apply"),
            RADAR = RailgunMod.id("krl_radar"), AA = RailgunMod.id("krl_aa");

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
        ServerPlayNetworking.registerGlobalReceiver(TILE, (server, p, h, buf, s) -> {
            BlockPos c = buf.readBlockPos();
            int tx = buf.readInt(), tz = buf.readInt();
            server.execute(() -> {
                if (console(p, c) == null) return;
                // only tiles within the map's reach
                double cx = (tx + 0.5) * KrlMap.TILE - c.getX(), cz = (tz + 0.5) * KrlMap.TILE - c.getZ();
                if (cx * cx + cz * cz > Math.pow(KrlLoadout.MAX_RANGE + 1200, 2)) return;
                KrlMap.requestTile(p, c, tx, tz);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(RADAR, (server, p, h, buf, s) -> {
            BlockPos c = buf.readBlockPos();
            boolean on = buf.readBoolean();
            server.execute(() -> {
                if (p.squaredDistanceTo(Vec3d.ofCenter(c)) > Krl.USE_RANGE * Krl.USE_RANGE * 4) return;
                if (!(p.getWorld().getBlockEntity(c) instanceof KrlConsoleBlockEntity be)) return;
                be.setRadar(on);
                p.getWorld().playSound(null, c, on ? Krl.S_ARM : Krl.S_CLICK, net.minecraft.sound.SoundCategory.BLOCKS, 1f, on ? 0.7f : 0.9f);
                sendState(p, be, false);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(STATION_APPLY, (server, p, h, buf, s) -> {
            BlockPos at = buf.readBlockPos();
            KrlLoadout want = KrlLoadout.read(buf);
            server.execute(() -> refit(p, at, want));
        });
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
            double lim = KrlLoadout.MAX_RANGE + Krl.LINK_RANGE;
            if (dx * dx + dz * dz > lim * lim || !w.getWorldBorder().contains(x, z)) {
                p.sendMessage(KrlText.t("AIM POINT OUTSIDE THE SURVEY", KrlText.GREY), true);
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
            p.sendMessage(KrlText.t("NO AIM POINT MARKED", KrlText.GREY), true);
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
            if (d > l.missile().range() || d < MIN_RANGE) {
                refused++;
                continue;
            }
            if (KrlServer.ignite(w, l, t, p, ride && lit == 0) != null) lit++;
            else refused++;
        }
        if (lit > 0) {
            w.playSound(null, c, Krl.S_ARM, SoundCategory.BLOCKS, 1.6f, 1f);
            p.sendMessage(KrlText.t("LAUNCH  //  " + lit + " RAIL" + (lit > 1 ? "S" : "") + " SLEWING TO TARGET  //  IGNITION, THEN LIFTOFF", KrlText.AMBER), true);
        } else if (refused > 0) {
            p.sendMessage(KrlText.t("NO RAIL COULD TAKE THE SHOT  //  EMPTY, BUSY, UNLOADED OR OUT OF RANGE", KrlText.RED), true);
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
        if (n > 0) p.sendMessage(KrlText.t("ABORT  //  " + n + " LAUNCH" + (n > 1 ? "ES" : "") + " SAFED", KrlText.AMBER), true);
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
            KrlLoadout m = KrlLoadout.DEFAULT;
            if (w.isChunkLoaded(lp) && w.getBlockEntity(lp) instanceof KrlLauncherBlockEntity l) {
                st = l.igniting() ? L_BURNING : l.loaded() ? L_READY : L_EMPTY;
                cd = l.countdown();
                fl = l.flight();
                launched = l.launchedAt();
                m = l.missile();
            } else if (w.isChunkLoaded(lp)) st = L_GONE;
            b.writeVarInt(st);
            b.writeVarInt(cd + 1);
            b.writeInt(fl);
            b.writeLong(launched);
            m.write(b);
            b.writeVarInt(w.isChunkLoaded(lp) ? KrlDefense.rounds(w, lp) : 0);
        }
        b.writeLong(w.getTime());
        b.writeBoolean(be.radar());
        List<KrlDefense.Contact> cs = KrlDefense.contacts(be.getPos());
        b.writeVarInt(cs.size());
        for (KrlDefense.Contact c : cs) {
            b.writeFloat((float) c.x());
            b.writeFloat((float) c.y());
            b.writeFloat((float) c.z());
            b.writeByte(c.kind());
            b.writeBoolean(c.engaged());
        }
        ServerPlayNetworking.send(p, STATE, b);
    }

    public static void sendMap(ServerPlayerEntity p, BlockPos console, KrlMap.Survey s, boolean tile) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeBoolean(tile);
        b.writeBlockPos(console);
        b.writeInt(s.x0());
        b.writeInt(s.z0());
        b.writeVarInt(s.n());
        b.writeVarInt(s.cell());
        for (short h : s.height()) b.writeShort(h);
        b.writeByteArray(s.kind());
        for (int c : s.rgb()) b.writeMedium(c == 0 ? 0 : (c & 0xFFFFFF) | (c == 0xFF000000 ? 1 : 0));
        b.writeVarInt(p.getServerWorld().getSeaLevel());
        ServerPlayNetworking.send(p, MAP_DATA, b);
    }

    // ------------------------------------------------------------------------------------------- the missile station

    public static void openStation(ServerPlayerEntity p, BlockPos at) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeBlockPos(at);
        ServerPlayNetworking.send(p, STATION_OPEN, b);
    }

    /** Refit the missile in the player's main hand, charging the materials it takes. */
    private static void refit(ServerPlayerEntity p, BlockPos at, KrlLoadout want) {
        ServerWorld w = p.getServerWorld();
        if (!w.getBlockState(at).isOf(Krl.STATION) || p.squaredDistanceTo(Vec3d.ofCenter(at)) > 64) return;
        var stack = p.getMainHandStack();
        if (!stack.isOf(Krl.SPEAR)) {
            p.sendMessage(KrlText.t("HOLD THE MISSILE IN YOUR MAIN HAND", KrlText.RED), true);
            return;
        }
        KrlLoadout have = KrlLoadout.of(stack);
        if (have.equals(want)) return;
        var cost = have.costTo(want);
        if (!p.getAbilities().creativeMode) {
            var inv = p.getInventory();
            for (var c : cost) {
                if (inv.count(c.getItem()) < c.getCount()) {
                    p.sendMessage(KrlText.t("NOT ENOUGH MATERIALS  //  NEED " + c.getCount() + " x " + c.getName().getString().toUpperCase(java.util.Locale.ROOT), KrlText.RED), true);
                    w.playSound(null, at, Krl.S_CLICK, SoundCategory.BLOCKS, 0.8f, 0.5f);
                    return;
                }
            }
            for (var c : cost) {
                int left = c.getCount();
                for (int i = 0; i < inv.size() && left > 0; i++) {
                    var s = inv.getStack(i);
                    if (!s.isOf(c.getItem())) continue;
                    int take = Math.min(left, s.getCount());
                    s.decrement(take);
                    left -= take;
                }
            }
        }
        // a stack of missiles is refitted one at a time
        if (stack.getCount() > 1) {
            var one = stack.split(1);
            want.applyTo(one);
            if (!p.getInventory().insertStack(one)) p.dropItem(one, false);
        } else {
            want.applyTo(stack);
        }
        w.playSound(null, at, Krl.S_LOAD, SoundCategory.BLOCKS, 1f, 1.1f);
        p.sendMessage(KrlText.t("REFIT COMPLETE  //  " + want.summary(), KrlText.GREEN), true);
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
        f.missile.write(b);
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
