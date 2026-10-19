package com.creedpdf.orbitalrailgun.network;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.DeploymentScheduler;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
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
import com.creedpdf.orbitalrailgun.util.LandingSurvey;
import org.jetbrains.annotations.Nullable;

/**
 * All custom networking for the mod.
 *
 * <h2>Packet map</h2>
 * <pre>
 *  C2S  request_deploy   BlockPos target        tablet "CALL DOWN"  → validate, schedule orbital drop
 *  C2S  tablet_status    (empty)                tablet poll         → server answers with tablet_info
 *  C2S  tablet_target    BlockPos               tablet "SET TARGET" / "PAINT"
 *  C2S  tablet_fire      (empty)                tablet "FIRE"       → IDLE → CHARGING (auto-fires at 100%)
 *  C2S  tablet_abort     (empty)                tablet "ABORT"      → CHARGING → IDLE
 *  C2S  panel_action     varint entityId, varint action   zoomed-in panel: DEPLOY / RETRACT / RETURN
 *  S2C  drop_countdown   BlockPos, int ticks    HUD countdown + local uplink sound
 *  S2C  tablet_info      status snapshot        what the tablet displays
 *  C2S  panel_loadout    varint entityId, varint loadout   order a loadout from orbital storage
 *  C2S  survey           BlockPos ground        tablet asks whether a drop zone is usable
 *  S2C  survey_result    BlockPos, bool ok, String reason, BlockPos obstacle
 * </pre>
 *
 * <p>Tablet commands carry no entity id: the server resolves the sender's own railgun by owner
 * UUID ({@link RailgunEntity#findOwned}), so a player can only ever command their own railgun,
 * from any distance (deployed railguns keep their chunk loaded). The physical control panel
 * (retract / deploy / return) is a plain entity interaction and needs no packet.</p>
 *
 * <p>Every C2S handler runs on the server thread and re-validates state, ranges and world
 * bounds. Clients can only ask; the server decides.</p>
 */
public final class ModPackets {
    private ModPackets() {}

    public static final Identifier REQUEST_DEPLOY = RailgunMod.id("request_deploy");
    public static final Identifier TABLET_STATUS = RailgunMod.id("tablet_status");
    public static final Identifier TABLET_TARGET = RailgunMod.id("tablet_target");
    public static final Identifier TABLET_FIRE = RailgunMod.id("tablet_fire");
    public static final Identifier TABLET_ABORT = RailgunMod.id("tablet_abort");
    public static final Identifier PANEL_ACTION = RailgunMod.id("panel_action");
    public static final Identifier DROP_COUNTDOWN = RailgunMod.id("drop_countdown");
    public static final Identifier TABLET_INFO = RailgunMod.id("tablet_info");
    public static final Identifier PANEL_LOADOUT = RailgunMod.id("panel_loadout");
    public static final Identifier SURVEY = RailgunMod.id("survey");
    public static final Identifier SURVEY_RESULT = RailgunMod.id("survey_result");
    public static final Identifier BEAM_SHOT = RailgunMod.id("beam_shot");
    public static final Identifier TABLET_SCAN = RailgunMod.id("tablet_scan");
    public static final Identifier SCAN_RESULT = RailgunMod.id("scan_result");
    public static final Identifier TABLET_IGNITE = RailgunMod.id("tablet_ignite");
    public static final Identifier STAR_FLARE = RailgunMod.id("star_flare");

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_DEPLOY, (server, player, handler, buf, sender) -> {
            BlockPos target = buf.readBlockPos();
            server.execute(() -> handleDeployRequest(player, target));
        });
        ServerPlayNetworking.registerGlobalReceiver(TABLET_STATUS, (server, player, handler, buf, sender) ->
                server.execute(() -> sendTabletInfo(player)));
        ServerPlayNetworking.registerGlobalReceiver(TABLET_TARGET, (server, player, handler, buf, sender) -> {
            BlockPos target = buf.readBlockPos();
            server.execute(() -> handleSetTarget(player, target));
        });
        ServerPlayNetworking.registerGlobalReceiver(TABLET_FIRE, (server, player, handler, buf, sender) ->
                server.execute(() -> {
                    RailgunEntity railgun = owned(player);
                    if (railgun == null) { deny(player, "no_railgun"); return; }
                    if (!railgun.tryStartCharge()) deny(player, railgun.getState().isOperational()
                            ? (railgun.hasTarget() ? "busy" : "no_target") : "not_deployed");
                    sendTabletInfo(player);
                }));
        ServerPlayNetworking.registerGlobalReceiver(PANEL_ACTION, (server, player, handler, buf, sender) -> {
            int entityId = buf.readVarInt();
            int action = buf.readVarInt();
            server.execute(() -> {
                if (!(player.getWorld().getEntityById(entityId) instanceof RailgunEntity railgun) || !railgun.isAlive()) return;
                RailgunEntity.PanelAction[] actions = RailgunEntity.PanelAction.values();
                if (action < 0 || action >= actions.length) return;
                if (!railgun.handlePanelAction(player, actions[action])) deny(player, "busy");
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(PANEL_LOADOUT, (server, player, handler, buf, sender) -> {
            int entityId = buf.readVarInt();
            int raw = buf.readVarInt();
            server.execute(() -> {
                if (!(player.getWorld().getEntityById(entityId) instanceof RailgunEntity railgun) || !railgun.isAlive()) return;
                if (!railgun.configureLoadout(player, raw)) deny(player, "loadout");
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SURVEY, (server, player, handler, buf, sender) -> {
            BlockPos ground = buf.readBlockPos();
            server.execute(() -> {
                ServerWorld world = (ServerWorld) player.getWorld();
                if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(ground)) > 600 * 600 || !world.isChunkLoaded(ground)) return;
                LandingSurvey.Result r = LandingSurvey.survey(world, ground);
                PacketByteBuf out = PacketByteBufs.create();
                out.writeBlockPos(ground);
                out.writeBoolean(r.ok());
                out.writeString(r.reason());
                out.writeBlockPos(r.obstacle());
                ServerPlayNetworking.send(player, SURVEY_RESULT, out);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(TABLET_SCAN, (server, player, handler, buf, sender) ->
                server.execute(() -> {
                    RailgunEntity railgun = owned(player);
                    if (railgun == null) { deny(player, "no_railgun"); return; }
                    if (!railgun.tryStartScan()) deny(player, railgun.getState() == RailgunState.IDLE ? "scan_cooldown"
                            : railgun.getState().isOperational() ? "busy" : "not_deployed");
                    sendTabletInfo(player);
                }));
        ServerPlayNetworking.registerGlobalReceiver(TABLET_IGNITE, (server, player, handler, buf, sender) ->
                server.execute(() -> {
                    RailgunEntity railgun = owned(player);
                    if (railgun == null) { deny(player, "no_railgun"); return; }
                    if (!railgun.tryIgnite()) deny(player, railgun.getState() == RailgunState.IDLE ? "star_cooldown"
                            : railgun.getState().isOperational() ? "busy" : "not_deployed");
                    sendTabletInfo(player);
                }));
        ServerPlayNetworking.registerGlobalReceiver(TABLET_ABORT, (server, player, handler, buf, sender) ->
                server.execute(() -> {
                    RailgunEntity railgun = owned(player);
                    if (railgun != null) railgun.abortCharge();
                    sendTabletInfo(player);
                }));
    }

    @Nullable
    private static RailgunEntity owned(ServerPlayerEntity player) {
        return RailgunEntity.findOwned(player.getServer(), player.getUuid());
    }

    // ---- Handlers ---------------------------------------------------------------------------

    private static void handleDeployRequest(ServerPlayerEntity player, BlockPos target) {
        ServerWorld world = (ServerWorld) player.getWorld();

        if (!player.getMainHandStack().isOf(RailgunMod.RAILGUN_REMOTE)) return;
        if (player.getItemCooldownManager().isCoolingDown(RailgunMod.RAILGUN_REMOTE)) { deny(player, "cooldown"); return; }
        if (DeploymentScheduler.hasPending(player.getUuid())) { deny(player, "pending"); return; }
        if (owned(player) != null) { deny(player, "already_deployed"); return; }

        double maxRange = RailgunConfig.MAX_FIRE_RANGE + 4.0;
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) > maxRange * maxRange) { deny(player, "out_of_range"); return; }
        if (!world.isChunkLoaded(target) || world.getBlockState(target).isAir()) { deny(player, "no_lock"); return; }

        BlockPos landing = target.up();
        if (world.isOutOfHeightLimit(landing)) { deny(player, "no_lock"); return; }
        // Orbital site survey: the pod is only released if it can land AND deploy here.
        LandingSurvey.Result survey = LandingSurvey.survey(world, target);
        if (!survey.ok()) { deny(player, survey.reason()); return; }

        DeploymentScheduler.schedule(world, landing, player.getUuid(), RailgunConfig.DROP_COUNTDOWN_TICKS);
        player.getItemCooldownManager().set(RailgunMod.RAILGUN_REMOTE, RailgunConfig.REMOTE_COOLDOWN_TICKS);
        sendDropCountdown(player, landing, RailgunConfig.DROP_COUNTDOWN_TICKS);
    }

    private static void handleSetTarget(ServerPlayerEntity player, BlockPos target) {
        RailgunEntity railgun = owned(player);
        if (railgun == null) { deny(player, "no_railgun"); return; }
        RailgunState state = railgun.getState();
        if (!state.isOperational()) { deny(player, "not_deployed"); return; }
        if (state == RailgunState.CHARGING || state == RailgunState.FIRING) { deny(player, "busy"); return; }
        if (railgun.getWorld() != player.getWorld()) { deny(player, "other_dimension"); return; }
        double dx = target.getX() + 0.5 - railgun.getX();
        double dz = target.getZ() + 0.5 - railgun.getZ();
        double range = RailgunConfig.MAX_FIRE_RANGE * railgun.getLoadout().rangeMul();
        if (dx * dx + dz * dz > range * range) { deny(player, "out_of_range"); return; }
        if (railgun.getWorld().isOutOfHeightLimit(target)) { deny(player, "out_of_range"); return; }

        railgun.setTarget(target);
        player.playSound(ModSounds.UI_TARGET_LOCK, SoundCategory.PLAYERS, 1.0f, 1.0f);
        player.sendMessage(Text.translatable("message.orbital_railgun.target_locked",
                target.getX(), target.getY(), target.getZ()).formatted(Formatting.RED), true);
        sendTabletInfo(player);
    }

    private static void deny(ServerPlayerEntity player, String reason) {
        player.sendMessage(Text.translatable("message.orbital_railgun.denied." + reason).formatted(Formatting.RED), true);
        player.playSound(ModSounds.UI_DENIED, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    // ---- S2C senders ------------------------------------------------------------------------

    /** Every shot goes to everyone in the dimension, so the beam renders at any distance. */
    public static void broadcastBeamShot(net.minecraft.server.world.ServerWorld world, int entityId,
                                         net.minecraft.util.math.Vec3d muzzle, BlockPos target, int loadout) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeVarInt(entityId);
            buf.writeDouble(muzzle.x);
            buf.writeDouble(muzzle.y);
            buf.writeDouble(muzzle.z);
            buf.writeBlockPos(target);
            buf.writeVarInt(loadout);
            ServerPlayNetworking.send(player, BEAM_SHOT, buf);
        }
    }

    /** Scan pulse: ores (packed pos + kind) and tagged hostiles, to everyone near the emplacement. */
    public static void broadcastScan(ServerWorld world, RailgunEntity railgun, Vec3d head, java.util.List<long[]> ores, int[] mobs) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.squaredDistanceTo(railgun) > 192 * 192) continue;
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeDouble(head.x);
            buf.writeDouble(head.y);
            buf.writeDouble(head.z);
            buf.writeDouble(railgun.getY());
            buf.writeVarInt(ores.size());
            for (long[] e : ores) {
                buf.writeLong(e[0]);
                buf.writeByte((int) e[1]);
            }
            buf.writeVarInt(mobs.length);
            for (int id : mobs) buf.writeVarInt(id);
            ServerPlayNetworking.send(p, SCAN_RESULT, buf);
            if (p.getUuid().equals(railgun.getOwnerUuid())) {
                p.sendMessage(Text.translatable("message.orbital_railgun.scan.result", ores.size(), mobs.length).formatted(Formatting.AQUA), true);
            }
        }
    }

    /** A solar flare arc for clients to draw (star surface to target). */
    public static void broadcastFlare(ServerWorld world, Vec3d from, Vec3d to) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.squaredDistanceTo(from) > 256 * 256) continue;
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeDouble(from.x);
            buf.writeDouble(from.y);
            buf.writeDouble(from.z);
            buf.writeDouble(to.x);
            buf.writeDouble(to.y);
            buf.writeDouble(to.z);
            ServerPlayNetworking.send(p, STAR_FLARE, buf);
        }
    }

    public static void sendDropCountdown(ServerPlayerEntity player, BlockPos landing, int ticks) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(landing);
        buf.writeVarInt(ticks);
        ServerPlayNetworking.send(player, DROP_COUNTDOWN, buf);
    }

    /**
     * Snapshot for the tablet display. Layout:
     * boolean found, boolean pending, [if found: varint state, float charge, boolean hasTarget,
     * BlockPos target, BlockPos pos, float yawDeg, float pitchDeg, boolean sameDimension, int stateTicks].
     */
    public static void sendTabletInfo(ServerPlayerEntity player) {
        RailgunEntity railgun = owned(player);
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(railgun != null);
        buf.writeBoolean(DeploymentScheduler.hasPending(player.getUuid()));
        if (railgun != null) {
            buf.writeVarInt(railgun.getState().ordinal());
            buf.writeFloat(railgun.getCharge());
            buf.writeBoolean(railgun.hasTarget());
            buf.writeBlockPos(railgun.getTarget());
            buf.writeBlockPos(railgun.getBlockPos());
            buf.writeFloat((float) Math.toDegrees(railgun.getAimYawRad(1f)));
            buf.writeFloat((float) Math.toDegrees(railgun.getAimPitchRad(1f)));
            buf.writeBoolean(railgun.getWorld() == player.getWorld());
            buf.writeVarInt(railgun.getStateTicks());
            buf.writeVarInt(railgun.getLoadout().raw());
            buf.writeBoolean(railgun.getState() == com.creedpdf.orbitalrailgun.entity.RailgunState.ARMING);
        }
        ServerPlayNetworking.send(player, TABLET_INFO, buf);
    }
}
