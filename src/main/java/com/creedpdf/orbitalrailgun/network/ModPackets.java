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
import net.minecraft.world.Heightmap;
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
 *  S2C  drop_countdown   BlockPos, int ticks    HUD countdown + local uplink sound
 *  S2C  tablet_info      status snapshot        what the tablet displays
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
    public static final Identifier DROP_COUNTDOWN = RailgunMod.id("drop_countdown");
    public static final Identifier TABLET_INFO = RailgunMod.id("tablet_info");

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
        if (world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, landing.getX(), landing.getZ()) > landing.getY()) {
            deny(player, "no_sky");
            return;
        }

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
        if (dx * dx + dz * dz > RailgunConfig.MAX_FIRE_RANGE * RailgunConfig.MAX_FIRE_RANGE) { deny(player, "out_of_range"); return; }
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
        }
        ServerPlayNetworking.send(player, TABLET_INFO, buf);
    }
}
