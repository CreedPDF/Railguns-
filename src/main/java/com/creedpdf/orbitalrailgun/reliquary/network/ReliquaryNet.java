package com.creedpdf.orbitalrailgun.reliquary.network;

import com.creedpdf.orbitalrailgun.reliquary.CosmicReliquary;
import com.creedpdf.orbitalrailgun.reliquary.ModDataComponentTypes;
import com.creedpdf.orbitalrailgun.reliquary.logic.OrbitalLink;
import com.creedpdf.orbitalrailgun.reliquary.logic.RevolverShot;
import com.creedpdf.orbitalrailgun.reliquary.logic.StarEaterBeams;
import com.creedpdf.orbitalrailgun.reliquary.logic.VoidCuts;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.function.Consumer;

/**
 * Cosmic Reliquary networking. The server decides everything; packets out are only for drawing.
 * <pre>
 *  C2S  beam     bool            start / stop the Star-Eater's ray (attack held / released)
 *  C2S  slash    -               swing the Void-Carver
 *  C2S  fire     -               fire the revolver
 *  C2S  link     -               the Orbital Link (used looking straight up)
 *  S2C  beam_fx  varint player, bool              someone's stellar ray on / off
 *  S2C  slash_fx varint player, float yaw, pitch  the spatial ribbon of a swing
 *  S2C  arcs     varint rift, varint n, n x varint entity       rift arcs this instant
 *  S2C  shatter  double x, y, z, float size       a disintegration (cosmic_shatter rings)
 *  S2C  shot     varint player, double x, y, z, bool wall       a revolver round to there
 *  S2C  burn     varint n, n x varint entity      who is burning on a scar this second
 *  S2C  charge   varint                           your own Aetheric Charge (for the ring)
 *  S2C  link_fx  varint player                    an Orbital Link went up
 * </pre>
 */
public final class ReliquaryNet {
    private ReliquaryNet() {}

    public static final Identifier BEAM = id("beam"), SLASH = id("slash"), FIRE = id("fire"), LINK = id("link"),
            BEAM_FX = id("beam_fx"), SLASH_FX = id("slash_fx"), ARCS = id("arcs"), SHATTER = id("shatter"), SHOT = id("shot"),
            BURN = id("burn"), CHARGE = id("charge"), LINK_FX = id("link_fx");
    private static final double FX_RANGE = 160;

    private static Identifier id(String n) {
        return CosmicReliquary.id(n);
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(BEAM, (server, p, h, buf, s) -> {
            boolean on = buf.readBoolean();
            server.execute(() -> {
                if (on) StarEaterBeams.start(p);
                else StarEaterBeams.stop(p);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SLASH, (server, p, h, buf, s) -> server.execute(() -> VoidCuts.swing(p)));
        ServerPlayNetworking.registerGlobalReceiver(FIRE, (server, p, h, buf, s) -> server.execute(() -> RevolverShot.fire(p)));
        ServerPlayNetworking.registerGlobalReceiver(LINK, (server, p, h, buf, s) -> server.execute(() -> OrbitalLink.link(p)));
    }

    // ------------------------------------------------------------------------------------------- out

    private static void near(ServerWorld w, Vec3d at, Identifier id, Consumer<PacketByteBuf> write) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(at) > FX_RANGE * FX_RANGE) continue;
            PacketByteBuf buf = PacketByteBufs.create();
            write.accept(buf);
            ServerPlayNetworking.send(p, id, buf);
        }
    }

    public static void broadcastBeam(ServerPlayerEntity p, boolean on) {
        near(p.getServerWorld(), p.getPos(), BEAM_FX, b -> {
            b.writeVarInt(p.getId());
            b.writeBoolean(on);
        });
    }

    public static void broadcastSlash(ServerPlayerEntity p) {
        near(p.getServerWorld(), p.getPos(), SLASH_FX, b -> {
            b.writeVarInt(p.getId());
            b.writeFloat(p.getYaw());
            b.writeFloat(p.getPitch());
        });
    }

    public static void broadcastArcs(ServerWorld w, Entity rift, List<? extends Entity> targets) {
        near(w, rift.getPos(), ARCS, b -> {
            b.writeVarInt(rift.getId());
            b.writeVarInt(targets.size());
            for (Entity e : targets) b.writeVarInt(e.getId());
        });
    }

    public static void broadcastShatter(ServerWorld w, double x, double y, double z, float size) {
        near(w, new Vec3d(x, y, z), SHATTER, b -> {
            b.writeDouble(x);
            b.writeDouble(y);
            b.writeDouble(z);
            b.writeFloat(size);
        });
    }

    public static void broadcastShot(ServerWorld w, ServerPlayerEntity p, Vec3d end, boolean wall) {
        near(w, p.getPos(), SHOT, b -> {
            b.writeVarInt(p.getId());
            b.writeDouble(end.x);
            b.writeDouble(end.y);
            b.writeDouble(end.z);
            b.writeBoolean(wall);
        });
    }

    public static void broadcastBurn(ServerWorld w, List<LivingEntity> burning) {
        Vec3d at = burning.get(0).getPos();
        near(w, at, BURN, b -> {
            b.writeVarInt(burning.size());
            for (LivingEntity e : burning) b.writeVarInt(e.getId());
        });
    }

    public static void sendCharge(ServerPlayerEntity p) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeVarInt(ModDataComponentTypes.of(p).aethericCharge());
        ServerPlayNetworking.send(p, CHARGE, b);
    }

    public static void broadcastLink(ServerPlayerEntity p) {
        near(p.getServerWorld(), p.getPos(), LINK_FX, b -> b.writeVarInt(p.getId()));
    }
}
