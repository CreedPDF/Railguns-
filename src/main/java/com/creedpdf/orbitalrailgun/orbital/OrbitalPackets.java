package com.creedpdf.orbitalrailgun.orbital;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.OrbitalDepotEntity;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
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
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * Orbital mode networking.
 * <pre>
 *  C2S  orbital_action   varint action [+ payload]   STATUS, DEPLOY, STOW, FIRE(x, z), SKIP, CALL_DEPOT(pos), RECALL_DEPOT
 *  C2S  depot_send       varint entityId             SEND on the depot console
 *  S2C  orbital_status   phase, phaseTicks, loadout, readyIn, busy, depot
 *  S2C  orbital_fire     BlockPos target, varint loadout, long worldTime    starts the strike cinematic
 *  S2C  orbital_end      -                           cinematic over (player restored)
 *  S2C  orbital_attach   varint old, varint new, boolean deployFirst      docking cutscene
 *  S2C  orbital_beam     muzzle, BlockPos target, varint loadout, varint travel   beam for every player in the dimension
 * </pre>
 * Every request is re-validated on the server; the client only asks.
 */
public final class OrbitalPackets {
    private OrbitalPackets() {}

    public static final Identifier ACTION = RailgunMod.id("orbital_action");
    public static final Identifier DEPOT_SEND = RailgunMod.id("depot_send");
    public static final Identifier STATUS = RailgunMod.id("orbital_status");
    public static final Identifier FIRE = RailgunMod.id("orbital_fire");
    public static final Identifier END = RailgunMod.id("orbital_end");
    public static final Identifier ATTACH = RailgunMod.id("orbital_attach");
    public static final Identifier BEAM = RailgunMod.id("orbital_beam");

    public enum Action { STATUS, DEPLOY, STOW, FIRE, SKIP, CALL_DEPOT, RECALL_DEPOT }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, sender) -> {
            int a = buf.readVarInt();
            if (a < 0 || a >= Action.values().length) return;
            Action action = Action.values()[a];
            int x = 0, z = 0;
            BlockPos pos = BlockPos.ORIGIN;
            if (action == Action.FIRE) { x = buf.readVarInt(); z = buf.readVarInt(); }
            if (action == Action.CALL_DEPOT) pos = buf.readBlockPos();
            final int fx = x, fz = z;
            final BlockPos fpos = pos;
            server.execute(() -> handle(player, action, fx, fz, fpos));
        });
        ServerPlayNetworking.registerGlobalReceiver(DEPOT_SEND, (server, player, handler, buf, sender) -> {
            int id = buf.readVarInt();
            server.execute(() -> {
                if (!(player.getWorld().getEntityById(id) instanceof OrbitalDepotEntity depot) || !depot.isAlive()) return;
                Loadout draft = depot.getDraft();
                OrbitalState state = OrbitalState.get(player.getServer());
                OrbitalState.Rig rig = state.rig(player.getUuid());
                long now = now(player);
                if (OrbitalCinema.busy(player.getUuid()) || now < rig.busyUntil) { deny(player, "orbital_busy"); return; }
                int old = rig.loadout;
                boolean ok = depot.send(player, d -> {
                    OrbitalState.Phase ph = rig.phase(now(player));
                    boolean deployFirst = ph == OrbitalState.Phase.STOWED || ph == OrbitalState.Phase.STOWING;
                    if (deployFirst) {
                        rig.phase = OrbitalState.Phase.DEPLOYING;
                        rig.phaseStart = now(player);
                    }
                    rig.loadout = draft.raw();
                    state.changed();
                    sendAttach(player, old, draft.raw(), deployFirst);
                });
                if (!ok) deny(player, "depot_send");
            });
        });
    }

    private static long now(ServerPlayerEntity p) {
        return p.getServer().getOverworld().getTime();
    }

    private static void handle(ServerPlayerEntity player, Action action, int x, int z, BlockPos pos) {
        OrbitalState state = OrbitalState.get(player.getServer());
        OrbitalState.Rig rig = state.rig(player.getUuid());
        long now = now(player);
        OrbitalState.Phase phase = rig.phase(now);
        switch (action) {
            case STATUS -> { }
            case DEPLOY -> {
                if (phase != OrbitalState.Phase.STOWED) { deny(player, "orbital_busy"); break; }
                rig.phase = OrbitalState.Phase.DEPLOYING;
                rig.phaseStart = now;
                state.changed();
            }
            case STOW -> {
                if (phase != OrbitalState.Phase.DEPLOYED || now < rig.busyUntil) { deny(player, "orbital_busy"); break; }
                rig.phase = OrbitalState.Phase.STOWING;
                rig.phaseStart = now;
                state.changed();
            }
            case FIRE -> fire(player, state, rig, phase, now, x, z);
            case SKIP -> OrbitalCinema.skip(player);
            case CALL_DEPOT -> callDepot(player, pos);
            case RECALL_DEPOT -> {
                OrbitalDepotEntity d = OrbitalDepotEntity.findOwned(player.getServer(), player.getUuid());
                if (d != null) d.recall();
            }
        }
        sendStatus(player);
    }

    private static void fire(ServerPlayerEntity player, OrbitalState state, OrbitalState.Rig rig, OrbitalState.Phase phase,
                             long now, int x, int z) {
        if (phase != OrbitalState.Phase.DEPLOYED) { deny(player, "orbital_not_deployed"); return; }
        if (now < rig.readyAt || now < rig.busyUntil || OrbitalCinema.busy(player.getUuid())) { deny(player, "orbital_cooldown"); return; }
        if (player.getWorld().getRegistryKey() != World.OVERWORLD) { deny(player, "orbital_earth_only"); return; }
        double dx = x + 0.5 - player.getX(), dz = z + 0.5 - player.getZ();
        if (dx * dx + dz * dz > OrbitalConfig.RANGE * OrbitalConfig.RANGE) { deny(player, "out_of_range"); return; }
        ServerWorld world = player.getServerWorld();
        world.getChunk(x >> 4, z >> 4); // load / generate the target column
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        if (top <= world.getBottomY()) { deny(player, "no_lock"); return; }
        BlockPos target = new BlockPos(x, top - 1, z);
        Loadout l = Loadout.of(rig.loadout);
        rig.busyUntil = now + OrbitalConfig.CINE_END_T;
        rig.readyAt = now + OrbitalConfig.IMPACT_T + OrbitalConfig.COOLDOWN_TICKS;
        state.changed();
        OrbitalStrike.start(world, player.getUuid(), target, l);
        OrbitalCinema.begin(player, target);
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(target);
        buf.writeVarInt(l.raw());
        buf.writeLong(world.getTime());
        ServerPlayNetworking.send(player, FIRE, buf);
        player.sendMessage(Text.translatable("message.orbital_railgun.orbital.firing", target.getX(), target.getY(), target.getZ()).formatted(Formatting.RED), true);
    }

    private static void callDepot(ServerPlayerEntity player, BlockPos ground) {
        ServerWorld world = player.getServerWorld();
        if (world.getRegistryKey() != World.OVERWORLD) { deny(player, "orbital_earth_only"); return; }
        if (OrbitalDepotEntity.findOwned(player.getServer(), player.getUuid()) != null) { deny(player, "depot_exists"); return; }
        if (player.getPos().squaredDistanceTo(Vec3d.ofCenter(ground)) > 160 * 160 || !world.isChunkLoaded(ground)) { deny(player, "out_of_range"); return; }
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, ground.getX(), ground.getZ());
        if (top - 1 > ground.getY() + 2) { deny(player, "no_sky"); return; }
        double landY = top;
        OrbitalDepotEntity d = new OrbitalDepotEntity(RailgunMod.ORBITAL_DEPOT, world);
        d.setOwnerUuid(player.getUuid());
        d.setLandY(landY);
        d.refreshPositionAndAngles(ground.getX() + 0.5, landY + OrbitalDepotEntity.DROP_HEIGHT, ground.getZ() + 0.5, 0, 0);
        d.setDraft(Loadout.of(OrbitalState.get(player.getServer()).rig(player.getUuid()).loadout));
        world.spawnEntity(d);
        player.playSound(ModSounds.UI_UPLINK, SoundCategory.PLAYERS, 1f, 0.8f);
        player.sendMessage(Text.translatable("message.orbital_railgun.depot.inbound").formatted(Formatting.AQUA), true);
    }

    private static void deny(ServerPlayerEntity player, String reason) {
        player.sendMessage(Text.translatable("message.orbital_railgun.denied." + reason).formatted(Formatting.RED), true);
        player.playSound(ModSounds.UI_DENIED, SoundCategory.PLAYERS, 1f, 1f);
    }

    // ---- S2C ----------------------------------------------------------------------------------

    public static void sendStatus(ServerPlayerEntity player) {
        OrbitalState.Rig rig = OrbitalState.get(player.getServer()).rig(player.getUuid());
        long now = now(player);
        OrbitalState.Phase ph = rig.phase(now);
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(ph.ordinal());
        buf.writeVarInt((int) Math.min(Integer.MAX_VALUE, Math.max(0, now - rig.phaseStart)));
        buf.writeVarInt(rig.loadout);
        buf.writeVarInt((int) Math.max(0, rig.readyAt - now));
        buf.writeBoolean(now < rig.busyUntil);
        buf.writeBoolean(OrbitalDepotEntity.findOwned(player.getServer(), player.getUuid()) != null);
        ServerPlayNetworking.send(player, STATUS, buf);
    }

    public static void sendEnd(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, END, PacketByteBufs.create());
    }

    public static void sendAttach(ServerPlayerEntity player, int old, int now, boolean deployFirst) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(old);
        buf.writeVarInt(now);
        buf.writeBoolean(deployFirst);
        ServerPlayNetworking.send(player, ATTACH, buf);
    }

    public static void broadcastOrbitalBeam(ServerWorld world, Vec3d muzzle, BlockPos target, int loadout, int travel) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeDouble(muzzle.x);
            buf.writeDouble(muzzle.y);
            buf.writeDouble(muzzle.z);
            buf.writeBlockPos(target);
            buf.writeVarInt(loadout);
            buf.writeVarInt(travel);
            ServerPlayNetworking.send(p, BEAM, buf);
        }
    }
}
