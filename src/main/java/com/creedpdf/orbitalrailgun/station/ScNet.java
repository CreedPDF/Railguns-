package com.creedpdf.orbitalrailgun.station;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.krl.KrlText;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Space Command networking.
 * <pre>
 *  C2S  sc_hub_poll    BlockPos hub                    the console asks for the grid's state
 *  C2S  sc_hub_action  BlockPos hub, varint action     0 = start / stop a debris drill, 1 = defence AUTO / HOLD
 *  C2S  sc_sat_strike  BlockPos aim                    the tablet orders a satellite strike there
 *  S2C  sc_hub         BlockPos hub, bool open, grid   the console's read-out (open = show the screen)
 *  S2C  sc_rail_shot   BlockPos turret, from, to       a station railgun fired (everyone near)
 *  S2C  sc_strike      varint id, target, bool cine    a strike is on its way (cine: you ordered it - ride the cutscene)
 * </pre>
 */
public final class ScNet {
    private ScNet() {}

    public static final Identifier HUB_POLL = RailgunMod.id("sc_hub_poll"), HUB_ACTION = RailgunMod.id("sc_hub_action"),
            SAT_STRIKE = RailgunMod.id("sc_sat_strike"), HUB = RailgunMod.id("sc_hub"), RAIL_SHOT = RailgunMod.id("sc_rail_shot"),
            STRIKE = RailgunMod.id("sc_strike");

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(HUB_POLL, (server, p, h, buf, s) -> {
            BlockPos pos = buf.readBlockPos();
            server.execute(() -> {
                ScHubBlockEntity hub = hub(p, pos);
                if (hub != null) sendHub(p, hub, false);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(HUB_ACTION, (server, p, h, buf, s) -> {
            BlockPos pos = buf.readBlockPos();
            int action = buf.readVarInt();
            server.execute(() -> {
                ScHubBlockEntity hub = hub(p, pos);
                if (hub == null) return;
                if (action == 0) hub.toggleDrill();
                if (action == 1) hub.toggleDefense();
                p.getWorld().playSound(null, pos.up(), Sc.S_LINK, SoundCategory.BLOCKS, 0.8f, action == 0 ? 0.7f : 1.3f);
                sendHub(p, hub, false);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SAT_STRIKE, (server, p, h, buf, s) -> {
            BlockPos aim = buf.readBlockPos();
            server.execute(() -> {
                String why = ScStrike.order(p, aim);
                if (why != null) {
                    String msg = switch (why) {
                        case "no_satellite" -> "NO STRIKE SATELLITE IN ORBIT";
                        case "charging" -> "SATELLITE CHARGING";
                        case "range" -> "TARGET OUT OF RANGE  (" + (int) Sc.STRIKE_RANGE + " m)";
                        default -> "SATELLITE BUSY";
                    };
                    p.sendMessage(KrlText.t(msg, KrlText.RED), true);
                }
                com.creedpdf.orbitalrailgun.network.ModPackets.sendTabletInfo(p);
            });
        });
    }

    private static ScHubBlockEntity hub(ServerPlayerEntity p, BlockPos pos) {
        if (p.squaredDistanceTo(Vec3d.ofCenter(pos)) > 14 * 14) return null;
        return p.getWorld().getBlockEntity(pos) instanceof ScHubBlockEntity hub ? hub : null;
    }

    static void sendHub(ServerPlayerEntity p, ScHubBlockEntity hub, boolean open) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(hub.getPos());
        buf.writeBoolean(open);
        buf.writeBoolean(hub.online);
        buf.writeFloat(hub.supply);
        buf.writeFloat(hub.demand);
        buf.writeVarInt(hub.solar);
        buf.writeVarInt(hub.solarLive);
        buf.writeVarInt(hub.railguns);
        buf.writeVarInt(hub.railgunsLive);
        buf.writeVarInt(hub.sats);
        // best satellite charge on this grid
        float best = 0f;
        ScSatellites store = ScSatellites.get(p.getServer());
        long now = ScSatellites.now(p.getServer());
        for (ScSatellites.Rec r : store.owned(p.getUuid()))
            if (r.dim == p.getWorld().getRegistryKey() && r.pos.getManhattanDistance(hub.getPos()) < Sc.LINK * 3) best = Math.max(best, r.chargeAt(now));
        buf.writeFloat(best);
        buf.writeVarInt(hub.threats);
        buf.writeVarInt(hub.drill);
        buf.writeBoolean(hub.defenseAuto);
        ServerPlayNetworking.send(p, HUB, buf);
    }

    static void broadcastRailShot(ServerWorld w, BlockPos turret, Vec3d from, Vec3d to) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(turret);
        buf.writeDouble(from.x);
        buf.writeDouble(from.y);
        buf.writeDouble(from.z);
        buf.writeDouble(to.x);
        buf.writeDouble(to.y);
        buf.writeDouble(to.z);
        for (ServerPlayerEntity p : w.getPlayers())
            if (p.squaredDistanceTo(from) < 320 * 320) ServerPlayNetworking.send(p, RAIL_SHOT, PacketByteBufs.copy(buf));
    }

    static void broadcastStrike(ServerWorld w, int id, Vec3d target, ServerPlayerEntity shooter) {
        List<ServerPlayerEntity> ps = w.getPlayers();
        for (ServerPlayerEntity p : ps) {
            boolean mine = p == shooter;
            if (!mine && p.squaredDistanceTo(target) > 900 * 900) continue;
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeVarInt(id);
            buf.writeDouble(target.x);
            buf.writeDouble(target.y);
            buf.writeDouble(target.z);
            buf.writeBoolean(mine);
            ServerPlayNetworking.send(p, STRIKE, buf);
        }
    }

    /** The tablet's SAT block (appended to the tablet status): varint count, float charge, bool powered, string dim. */
    public static void writeTablet(ServerPlayerEntity p, PacketByteBuf buf) {
        ScSatellites store = ScSatellites.get(p.getServer());
        long now = ScSatellites.now(p.getServer());
        List<ScSatellites.Rec> mine = store.owned(p.getUuid());
        ScSatellites.Rec best = store.best(p.getUuid(), now);
        buf.writeVarInt(mine.size());
        buf.writeFloat(best == null ? 0f : best.chargeAt(now));
        buf.writeBoolean(best != null && (best.rate > 0 || best.chargeAt(now) >= 1f));
        buf.writeString(best == null ? "" : best.dim.getValue().getPath());
        buf.writeBoolean(ScStrike.busy(p.getUuid()));
    }
}
