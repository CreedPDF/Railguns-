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
    public static final Identifier TABLET_RIFT = RailgunMod.id("tablet_rift");
    public static final Identifier RIFT_BOLT = RailgunMod.id("rift_bolt");
    public static final Identifier ARES0_ACTION = RailgunMod.id("ares0_action");
    /** Uplink tablet remote control: varint action (0 recall ARES-9, 1 ARES-0 select, 2 ARES-0 fire, 3 ARES-0 recall), varint body. */
    public static final Identifier TABLET_REMOTE = RailgunMod.id("tablet_remote");
    /** Starship terminal: varint entity id, varint action (0 launch, 1 stow), varint destination body. */
    public static final Identifier SHIP_ACTION = RailgunMod.id("ship_action");
    /** Uplink hangar: varint action (0 call to BlockPos, 1 recall to orbit), BlockPos ground, varint facing. */
    public static final Identifier TABLET_SHIP = RailgunMod.id("tablet_ship");

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_DEPLOY, (server, player, handler, buf, sender) -> {
            BlockPos target = buf.readBlockPos();
            int kind = buf.isReadable() ? buf.readByte() : 0;
            server.execute(() -> handleDeployRequest(player, target, kind));
        });
        ServerPlayNetworking.registerGlobalReceiver(ARES0_ACTION, (server, player, handler, buf, sender) -> {
            int entityId = buf.readVarInt();
            int action = buf.readVarInt();
            int body = buf.readVarInt();
            server.execute(() -> handleAres0(player, entityId, action, body));
        });
        ServerPlayNetworking.registerGlobalReceiver(TABLET_REMOTE, (server, player, handler, buf, sender) -> {
            int action = buf.readVarInt();
            int body = buf.readVarInt();
            server.execute(() -> {
                handleRemote(player, action, body);
                sendTabletInfo(player);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SHIP_ACTION, (server, player, handler, buf, sender) -> {
            int id = buf.readVarInt();
            int action = buf.readVarInt();
            int body = buf.readVarInt();
            server.execute(() -> {
                if (!(player.getWorld().getEntityById(id) instanceof com.creedpdf.orbitalrailgun.entity.ShipEntity ship) || !ship.isAlive()) return;
                if (player.squaredDistanceTo(ship) > 24 * 24) return;
                boolean ok = switch (action) {
                    case 0 -> body >= 0 && body < com.creedpdf.orbitalrailgun.space.Body.ALL.length
                            && ship.launch(player, com.creedpdf.orbitalrailgun.space.Body.ALL[body]);
                    case 1 -> ship.depart(player);
                    default -> false;
                };
                if (!ok) deny(player, "busy");
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(TABLET_SHIP, (server, player, handler, buf, sender) -> {
            int action = buf.readVarInt();
            BlockPos ground = buf.readBlockPos();
            int facing = buf.readVarInt();
            server.execute(() -> {
                handleHangar(player, action, ground, facing);
                sendTabletInfo(player);
            });
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
        ServerPlayNetworking.registerGlobalReceiver(TABLET_RIFT, (server, player, handler, buf, sender) ->
                server.execute(() -> {
                    RailgunEntity railgun = owned(player);
                    if (railgun == null) { deny(player, "no_railgun"); return; }
                    if (!railgun.tryOpenRift()) deny(player, railgun.getState() == RailgunState.IDLE ? "rift_cooldown"
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

    /** ARES-0 console: 0 = select target, 1 = fire, 2 = return to orbit. */
    private static void handleAres0(ServerPlayerEntity player, int entityId, int action, int bodyIdx) {
        if (!(player.getWorld().getEntityById(entityId) instanceof com.creedpdf.orbitalrailgun.entity.Ares0Entity ares) || !ares.isAlive()) return;
        if (player.squaredDistanceTo(ares) > 96 * 96) return;
        if (ares.getOwnerUuid() != null && !ares.getOwnerUuid().equals(player.getUuid()) && !player.isCreative()) {
            deny(player, "not_owner");
            return;
        }
        com.creedpdf.orbitalrailgun.space.Body body = bodyIdx >= 0 && bodyIdx < com.creedpdf.orbitalrailgun.space.Body.ALL.length
                ? com.creedpdf.orbitalrailgun.space.Body.ALL[bodyIdx] : null;
        switch (action) {
            case 0 -> { if (body != null) ares.select(body); }
            case 1 -> {
                if (body == null) return;
                if (!ares.tryFire(player, body)) {
                    deny(player, com.creedpdf.orbitalrailgun.space.PlanetState.get(player.getServer()).doomed(body) ? "ares0_gone"
                            : !com.creedpdf.orbitalrailgun.entity.Ares0Entity.visible(ares.getWorld(), body) ? "ares0_horizon" : "busy");
                }
            }
            case 2 -> ares.beginLeave();
            default -> {}
        }
    }

    /** Remote control from the uplink tablet, at any distance and from any dimension. */
    private static void handleRemote(ServerPlayerEntity player, int action, int bodyIdx) {
        com.creedpdf.orbitalrailgun.space.Body body = bodyIdx >= 0 && bodyIdx < com.creedpdf.orbitalrailgun.space.Body.ALL.length
                ? com.creedpdf.orbitalrailgun.space.Body.ALL[bodyIdx] : null;
        if (action >= 4 && action <= 7) {
            RailgunEntity railgun = owned(player);
            if (railgun == null) { deny(player, "no_railgun"); return; }
            boolean ok = switch (action) {
                case 4 -> railgun.remoteConfigure(bodyIdx);
                case 5 -> railgun.remoteAction(RailgunEntity.PanelAction.DEPLOY);
                case 6 -> railgun.remoteAction(RailgunEntity.PanelAction.RETRACT);
                default -> railgun.remoteAction(RailgunEntity.PanelAction.RETURN);
            };
            if (!ok) deny(player, action == 4 ? "loadout" : "busy");
            else player.playSound(ModSounds.UI_TARGET_LOCK, SoundCategory.PLAYERS, 1.0f, 1.2f);
            return;
        }
        if (action == 0) {
            RailgunEntity railgun = owned(player);
            if (railgun == null) { deny(player, "no_railgun"); return; }
            if (!railgun.remoteRecall()) { deny(player, "busy"); return; }
            player.sendMessage(Text.translatable("message.orbital_railgun.remote.recall").formatted(Formatting.RED), true);
            player.playSound(ModSounds.UI_TARGET_LOCK, SoundCategory.PLAYERS, 1.0f, 0.7f);
            return;
        }
        java.util.List<com.creedpdf.orbitalrailgun.entity.Ares0Entity> list =
                com.creedpdf.orbitalrailgun.entity.Ares0Entity.owned(player.getServer(), player.getUuid());
        if (list.isEmpty()) { deny(player, "no_ares0"); return; }
        com.creedpdf.orbitalrailgun.entity.Ares0Entity ares = list.get(0);
        switch (action) {
            case 1 -> { if (body != null) ares.select(body); }
            case 2 -> {
                if (body == null) return;
                if (!ares.tryFire(player, body)) {
                    deny(player, com.creedpdf.orbitalrailgun.space.PlanetState.get(player.getServer()).doomed(body) ? "ares0_gone"
                            : !com.creedpdf.orbitalrailgun.entity.Ares0Entity.visible(ares.getWorld(), body) ? "ares0_horizon" : "busy");
                } else {
                    player.playSound(ModSounds.UI_TARGET_LOCK, SoundCategory.PLAYERS, 1.0f, 0.5f);
                }
            }
            case 3 -> {
                ares.beginLeave();
                player.sendMessage(Text.translatable("message.orbital_railgun.remote.recall").formatted(Formatting.RED), true);
            }
            default -> {}
        }
    }

    /** Hangar: call the starship down from orbit to {@code ground}, or send it back up. */
    private static void handleHangar(ServerPlayerEntity player, int action, BlockPos ground, int facing) {
        com.creedpdf.orbitalrailgun.space.ShipRegistry reg = com.creedpdf.orbitalrailgun.space.ShipRegistry.get(player.getServer());
        com.creedpdf.orbitalrailgun.space.ShipRegistry.Entry e = reg.entry(player.getUuid());
        com.creedpdf.orbitalrailgun.entity.ShipEntity ship = findShip(player.getServer(), e);
        if (action == 0) {
            if (e.state == com.creedpdf.orbitalrailgun.space.ShipRegistry.NONE) { deny(player, "no_ship"); return; }
            if (ship != null) { deny(player, "ship_deployed"); return; }
            ServerWorld world = (ServerWorld) player.getWorld();
            boolean surface = world.getRegistryKey() == net.minecraft.world.World.OVERWORLD
                    || com.creedpdf.orbitalrailgun.space.SpaceDims.planetOf(world.getRegistryKey()) != null;
            if (!surface) { deny(player, "ship_surface"); return; }
            if (player.getBlockPos().getSquaredDistance(ground) > 400 * 400 || !world.isChunkLoaded(ground)) { deny(player, "out_of_range"); return; }
            int gy = com.creedpdf.orbitalrailgun.entity.ShipEntity.groundAt(world, ground);
            com.creedpdf.orbitalrailgun.entity.ShipEntity.call(world, player, new BlockPos(ground.getX(), gy, ground.getZ()), facing);
            player.sendMessage(Text.translatable("message.orbital_railgun.ship.inbound").formatted(Formatting.RED), true);
            player.playSound(ModSounds.UI_TARGET_LOCK, SoundCategory.PLAYERS, 1.0f, 0.6f);
        } else if (action == 1) {
            if (ship == null) {
                if (e.state == com.creedpdf.orbitalrailgun.space.ShipRegistry.DEPLOYED) reg.setOrbit(player.getUuid());
                deny(player, "ship_not_found");
                return;
            }
            if (!ship.depart(player)) { deny(player, "busy"); return; }
            player.sendMessage(Text.translatable("message.orbital_railgun.remote.recall").formatted(Formatting.RED), true);
        }
    }

    @org.jetbrains.annotations.Nullable
    public static com.creedpdf.orbitalrailgun.entity.ShipEntity findShip(net.minecraft.server.MinecraftServer server,
                                                                        com.creedpdf.orbitalrailgun.space.ShipRegistry.Entry e) {
        if (e.state != com.creedpdf.orbitalrailgun.space.ShipRegistry.DEPLOYED || e.ship == null) return null;
        for (ServerWorld w : server.getWorlds()) {
            if (w.getEntity(e.ship) instanceof com.creedpdf.orbitalrailgun.entity.ShipEntity s && s.isAlive()) return s;
        }
        return null;
    }

    private static void handleDeployRequest(ServerPlayerEntity player, BlockPos target) {
        handleDeployRequest(player, target, 0);
    }

    private static void handleDeployRequest(ServerPlayerEntity player, BlockPos target, int kind) {
        ServerWorld world = (ServerWorld) player.getWorld();

        if (!player.getMainHandStack().isOf(RailgunMod.RAILGUN_REMOTE)) return;
        if (player.getItemCooldownManager().isCoolingDown(RailgunMod.RAILGUN_REMOTE)) { deny(player, "cooldown"); return; }
        if (DeploymentScheduler.hasPending(player.getUuid())) { deny(player, "pending"); return; }
        if (kind == 1) {
            if (!com.creedpdf.orbitalrailgun.entity.Ares0Entity.owned(player.getServer(), player.getUuid()).isEmpty()) { deny(player, "already_deployed"); return; }
        } else if (owned(player) != null) { deny(player, "already_deployed"); return; }

        double maxRange = RailgunConfig.MAX_FIRE_RANGE + 4.0;
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) > maxRange * maxRange) { deny(player, "out_of_range"); return; }
        if (!world.isChunkLoaded(target) || world.getBlockState(target).isAir()) { deny(player, "no_lock"); return; }

        BlockPos landing = target.up();
        if (world.isOutOfHeightLimit(landing)) { deny(player, "no_lock"); return; }
        // Orbital site survey: the pod is only released if it can land AND deploy here.
        LandingSurvey.Result survey = LandingSurvey.survey(world, target);
        if (!survey.ok()) { deny(player, survey.reason()); return; }

        DeploymentScheduler.schedule(world, landing, player.getUuid(), RailgunConfig.DROP_COUNTDOWN_TICKS, kind);
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

    /** A rift lightning bolt for clients to draw (rim to ground). */
    public static void broadcastRiftBolt(ServerWorld world, Vec3d from, Vec3d to) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.squaredDistanceTo(to) > 256 * 256) continue;
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeDouble(from.x);
            buf.writeDouble(from.y);
            buf.writeDouble(from.z);
            buf.writeDouble(to.x);
            buf.writeDouble(to.y);
            buf.writeDouble(to.z);
            ServerPlayNetworking.send(p, RIFT_BOLT, buf);
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
        // ARES-0 block: boolean found, [varint state, varint target body (-1 none), varint stateTicks,
        // BlockPos pos, varint visible mask, varint doomed mask]
        java.util.List<com.creedpdf.orbitalrailgun.entity.Ares0Entity> a0 =
                com.creedpdf.orbitalrailgun.entity.Ares0Entity.owned(player.getServer(), player.getUuid());
        buf.writeBoolean(!a0.isEmpty());
        if (!a0.isEmpty()) {
            com.creedpdf.orbitalrailgun.entity.Ares0Entity ares = a0.get(0);
            buf.writeVarInt(ares.getState().ordinal());
            com.creedpdf.orbitalrailgun.space.Body tb = ares.getTarget();
            buf.writeVarInt(tb == null ? -1 : tb.ordinal());
            buf.writeVarInt(ares.getStateTicks());
            buf.writeBlockPos(ares.getBlockPos());
            int vis = 0, doom = 0;
            com.creedpdf.orbitalrailgun.space.PlanetState ps = com.creedpdf.orbitalrailgun.space.PlanetState.get(player.getServer());
            for (com.creedpdf.orbitalrailgun.space.Body b : com.creedpdf.orbitalrailgun.space.Body.ALL) {
                if (com.creedpdf.orbitalrailgun.entity.Ares0Entity.visible(ares.getWorld(), b)) vis |= 1 << b.ordinal();
                if (ps.doomed(b)) doom |= 1 << b.ordinal();
            }
            buf.writeVarInt(vis);
            buf.writeVarInt(doom);
        }
        // Starship block: varint state (0 none, 1 orbit, 2 parked, 3 flying), BlockPos, string dim
        com.creedpdf.orbitalrailgun.space.ShipRegistry.Entry se = com.creedpdf.orbitalrailgun.space.ShipRegistry.get(player.getServer()).entry(player.getUuid());
        com.creedpdf.orbitalrailgun.entity.ShipEntity sh = findShip(player.getServer(), se);
        int shipState = se.state == com.creedpdf.orbitalrailgun.space.ShipRegistry.NONE ? 0
                : sh == null ? (se.state == com.creedpdf.orbitalrailgun.space.ShipRegistry.DEPLOYED ? 2 : 1) : sh.inFlight() ? 3 : 2;
        buf.writeVarInt(shipState);
        buf.writeBlockPos(sh != null ? sh.getBlockPos() : se.pos);
        buf.writeString(sh != null ? sh.getWorld().getRegistryKey().getValue().getPath() : se.dim);
        // Space Command strike satellite block
        com.creedpdf.orbitalrailgun.station.ScNet.writeTablet(player, buf);
        ServerPlayNetworking.send(player, TABLET_INFO, buf);
    }
}
