package com.creedpdf.orbitalrailgun.reliquary.logic;

import com.creedpdf.orbitalrailgun.reliquary.ModEntities;
import com.creedpdf.orbitalrailgun.reliquary.ModItems;
import com.creedpdf.orbitalrailgun.reliquary.entity.CustomSingularityEntity;
import com.creedpdf.orbitalrailgun.reliquary.item.StarEaterGazeItem;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the Star-Eater's Gaze. While a player holds attack with it (the client says when it starts and
 * stops), every tick: a ray from the eye along the look, up to {@link StarEaterGazeItem#RANGE} blocks or the first
 * solid block; every living thing whose box (padded 0.4) the ray crosses has its velocity multiplied by
 * {@link StarEaterGazeItem#DRAG} - a direct physics constraint, no status effect. After
 * {@link StarEaterGazeItem#COLLAPSE_TICKS} ticks held, a singularity forms where the ray ends and the beam stops.
 */
public final class StarEaterBeams {
    private StarEaterBeams() {}

    /** Ticks held, per beaming player. */
    private static final Map<UUID, Integer> HELD = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(StarEaterBeams::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> HELD.remove(h.player.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> HELD.clear());
    }

    public static void start(ServerPlayerEntity p) {
        if (!p.getMainHandStack().isOf(ModItems.STAR_EATER_GAZE) || p.getItemCooldownManager().isCoolingDown(ModItems.STAR_EATER_GAZE)) return;
        if (HELD.putIfAbsent(p.getUuid(), 0) == null) ReliquaryNet.broadcastBeam(p, true);
    }

    public static void stop(ServerPlayerEntity p) {
        if (HELD.remove(p.getUuid()) != null) ReliquaryNet.broadcastBeam(p, false);
    }

    /** Where the ray from this player ends (first solid block, or full range). */
    public static Vec3d rayEnd(PlayerEntity p, float pt) {
        Vec3d eye = p.getCameraPosVec(pt), look = p.getRotationVec(pt);
        Vec3d far = eye.add(look.multiply(StarEaterGazeItem.RANGE));
        HitResult h = p.getWorld().raycast(new RaycastContext(eye, far, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        return h.getType() == HitResult.Type.MISS ? far : h.getPos();
    }

    private static void tick(MinecraftServer server) {
        for (Iterator<Map.Entry<UUID, Integer>> it = HELD.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> e = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || p.isSpectator() || !p.getMainHandStack().isOf(ModItems.STAR_EATER_GAZE)) {
                it.remove();
                if (p != null) ReliquaryNet.broadcastBeam(p, false);
                continue;
            }
            int held = e.getValue() + 1;
            e.setValue(held);
            ServerWorld w = p.getServerWorld();
            Vec3d eye = p.getEyePos(), end = rayEnd(p, 1f);
            Box span = new Box(eye, end).expand(1.0);
            for (Entity t : w.getOtherEntities(p, span, x -> x instanceof LivingEntity && x.isAlive())) {
                if (t.getBoundingBox().expand(0.4).raycast(eye, end).isEmpty() && !t.getBoundingBox().expand(0.4).contains(eye)) continue;
                t.setVelocity(t.getVelocity().multiply(StarEaterGazeItem.DRAG));
                t.velocityModified = true;
            }
            if (held >= StarEaterGazeItem.COLLAPSE_TICKS) {
                it.remove();
                ReliquaryNet.broadcastBeam(p, false);
                p.getItemCooldownManager().set(ModItems.STAR_EATER_GAZE, StarEaterGazeItem.COOLDOWN);
                CustomSingularityEntity s = new CustomSingularityEntity(ModEntities.SINGULARITY, w);
                Vec3d at = end.subtract(p.getRotationVec(1f).multiply(0.75));
                s.setPosition(at);
                s.setOwner(p);
                w.spawnEntity(s);
            }
        }
    }
}
