package com.creedpdf.orbitalrailgun.network;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.DeploymentScheduler;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
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

import java.util.function.Consumer;

/**
 * All custom networking for the mod.
 *
 * <h2>Packet map</h2>
 * <pre>
 *  C2S  request_deploy  BlockPos target          remote right-click  → validate, schedule orbital drop
 *  C2S  set_target      int entityId, BlockPos   menu "SET TARGET" / "PAINT"
 *  C2S  start_charge    int entityId             menu "FIRE"         → IDLE → CHARGING (auto-fires at 100%)
 *  C2S  abort           int entityId             menu "ABORT"        → CHARGING → IDLE
 *  C2S  recall          int entityId             menu "RECALL"       → removes the platform
 *  S2C  drop_countdown  BlockPos landing, int ticks   starts HUD countdown + local uplink sound
 * </pre>
 *
 * <h2>What is NOT a packet</h2>
 * Charge level, state, and target are {@link net.minecraft.entity.data.TrackedData} on
 * {@link RailgunEntity}. The vanilla entity tracker already syncs those to every watching client
 * each tick, so the menu's charge monitor and the beam renderer read them directly from the
 * client-side entity. That is cheaper and more reliable than a bespoke sync packet.
 *
 * <h2>Security</h2>
 * Every C2S handler runs on the server thread ({@code server.execute}) and re-validates: the
 * entity exists and is a railgun, the player is within interaction range, the requested state
 * transition is legal, and target coordinates are inside range/world bounds. Clients can only
 * ask; the server decides.
 */
public final class ModPackets {
    private ModPackets() {}

    // C2S
    public static final Identifier REQUEST_DEPLOY = RailgunMod.id("request_deploy");
    public static final Identifier SET_TARGET = RailgunMod.id("set_target");
    public static final Identifier START_CHARGE = RailgunMod.id("start_charge");
    public static final Identifier ABORT = RailgunMod.id("abort");
    public static final Identifier RECALL = RailgunMod.id("recall");
    // S2C
    public static final Identifier DROP_COUNTDOWN = RailgunMod.id("drop_countdown");

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_DEPLOY, (server, player, handler, buf, sender) -> {
            BlockPos target = buf.readBlockPos(); // read on the netty thread, act on the server thread
            server.execute(() -> handleDeployRequest(player, target));
        });

        ServerPlayNetworking.registerGlobalReceiver(SET_TARGET, (server, player, handler, buf, sender) -> {
            int entityId = buf.readVarInt();
            BlockPos target = buf.readBlockPos();
            server.execute(() -> withRailgun(player, entityId, railgun -> handleSetTarget(player, railgun, target)));
        });

        ServerPlayNetworking.registerGlobalReceiver(START_CHARGE, (server, player, handler, buf, sender) -> {
            int entityId = buf.readVarInt();
            server.execute(() -> withRailgun(player, entityId, railgun -> {
                if (!railgun.tryStartCharge()) deny(player, railgun.hasTarget() ? "busy" : "no_target");
            }));
        });

        ServerPlayNetworking.registerGlobalReceiver(ABORT, (server, player, handler, buf, sender) -> {
            int entityId = buf.readVarInt();
            server.execute(() -> withRailgun(player, entityId, RailgunEntity::abortCharge));
        });

        ServerPlayNetworking.registerGlobalReceiver(RECALL, (server, player, handler, buf, sender) -> {
            int entityId = buf.readVarInt();
            server.execute(() -> withRailgun(player, entityId, railgun -> {
                if (railgun.getState() == RailgunState.FIRING) deny(player, "busy");
                else railgun.recall();
            }));
        });
    }

    // ---- Handlers ---------------------------------------------------------------------------

    private static void handleDeployRequest(ServerPlayerEntity player, BlockPos target) {
        ServerWorld world = (ServerWorld) player.getWorld();

        boolean holding = player.getMainHandStack().isOf(RailgunMod.RAILGUN_REMOTE)
                || player.getOffHandStack().isOf(RailgunMod.RAILGUN_REMOTE);
        if (!holding || player.getItemCooldownManager().isCoolingDown(RailgunMod.RAILGUN_REMOTE)) return;

        if (DeploymentScheduler.hasPending(player.getUuid())) { deny(player, "pending"); return; }

        double maxRange = RailgunConfig.REMOTE_RANGE + 4.0;
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) > maxRange * maxRange) { deny(player, "out_of_range"); return; }
        if (!world.isChunkLoaded(target) || world.getBlockState(target).isAir()) { deny(player, "no_lock"); return; }

        BlockPos landing = target.up();
        if (world.isOutOfHeightLimit(landing)) { deny(player, "no_lock"); return; }
        // Clear sky: nothing motion-blocking above the landing column.
        if (world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, landing.getX(), landing.getZ()) > landing.getY()) {
            deny(player, "no_sky");
            return;
        }

        DeploymentScheduler.schedule(world, landing, player.getUuid(), RailgunConfig.DROP_COUNTDOWN_TICKS);
        player.getItemCooldownManager().set(RailgunMod.RAILGUN_REMOTE, RailgunConfig.REMOTE_COOLDOWN_TICKS);
        sendDropCountdown(player, landing, RailgunConfig.DROP_COUNTDOWN_TICKS);
    }

    private static void handleSetTarget(ServerPlayerEntity player, RailgunEntity railgun, BlockPos target) {
        RailgunState state = railgun.getState();
        if (state == RailgunState.CHARGING || state == RailgunState.FIRING || !state.isOperational()) {
            deny(player, "busy");
            return;
        }
        double dx = target.getX() + 0.5 - railgun.getX();
        double dz = target.getZ() + 0.5 - railgun.getZ();
        if (dx * dx + dz * dz > RailgunConfig.MAX_FIRE_RANGE * RailgunConfig.MAX_FIRE_RANGE) { deny(player, "out_of_range"); return; }
        if (railgun.getWorld().isOutOfHeightLimit(target)) { deny(player, "out_of_range"); return; }

        railgun.setTarget(target);
        player.playSound(ModSounds.UI_TARGET_LOCK, SoundCategory.PLAYERS, 1.0f, 1.0f);
        player.sendMessage(Text.translatable("message.orbital_railgun.target_locked",
                target.getX(), target.getY(), target.getZ()).formatted(Formatting.RED), true);
    }

    /** Resolves and range-checks the railgun a menu packet refers to. Silently drops bad ids. */
    private static void withRailgun(ServerPlayerEntity player, int entityId, Consumer<RailgunEntity> action) {
        RailgunEntity railgun = resolve(player, entityId);
        if (railgun != null) action.accept(railgun);
    }

    @Nullable
    private static RailgunEntity resolve(ServerPlayerEntity player, int entityId) {
        Entity entity = player.getWorld().getEntityById(entityId);
        if (!(entity instanceof RailgunEntity railgun) || !railgun.isAlive()) return null;
        double max = RailgunConfig.MAX_INTERACT_DISTANCE;
        if (player.squaredDistanceTo(railgun) > max * max) return null;
        return railgun;
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
}
