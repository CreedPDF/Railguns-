package com.creedpdf.orbitalrailgun.legion;

import com.creedpdf.orbitalrailgun.legion.capability.IWeaponSwarm;
import com.creedpdf.orbitalrailgun.legion.capability.SwarmCapability;
import com.creedpdf.orbitalrailgun.legion.entity.AutonomousWeaponEntity;
import com.creedpdf.orbitalrailgun.legion.network.LegionPackets;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs every player's swarm on the server.
 * <ul>
 *   <li>Keeps the flying entities in step with the {@link IWeaponSwarm}: one entity per bound weapon (up to the
 *   player's capacity) while the swarm is deployed, none while it is stowed, the owner is dead or a spectator. Entities
 *   are rebuilt after a dimension change or a relog; they are never saved in the world.</li>
 *   <li>Strikes: when the player hits something, or something hits the player, a third of the swarm (at least one ready
 *   striker) dashes through it.</li>
 *   <li>Defence: if the swarm holds a shield or a heavy weapon, projectiles flying at the player are intercepted (each
 *   projectile by one defender).</li>
 *   <li>Death drops the bound weapons (unless keepInventory), like the rest of the inventory.</li>
 * </ul>
 * Work per player per tick is a short array walk; the projectile scan runs every other tick in a small box.
 */
public final class SwarmManager {
    private SwarmManager() {}

    private static final Map<UUID, AutonomousWeaponEntity[]> LIVE = new HashMap<>();
    private static final Map<UUID, Integer> SENT = new HashMap<>();
    private static final double DEFEND_RADIUS = 10;

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SwarmManager::tick);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp && !(entity instanceof AutonomousWeaponEntity) && entity.isAlive())
                retaliate(sp, entity);
            return ActionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity sp && source.getAttacker() instanceof LivingEntity att && att != sp && att.isAlive())
                retaliate(sp, att);
            return true;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity sp) onDeath(sp);
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) ->
                SwarmCapability.of(newPlayer).copyFrom(SwarmCapability.of(oldPlayer)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> despawn(handler.player.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LIVE.clear();
            SENT.clear();
        });
    }

    // ------------------------------------------------------------------------------------------- the swarm in the world

    private static void tick(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            IWeaponSwarm swarm = SwarmCapability.of(p);
            int cap = SwarmCapability.capacity(p);
            AutonomousWeaponEntity[] live = LIVE.computeIfAbsent(p.getUuid(), k -> new AutonomousWeaponEntity[IWeaponSwarm.MAX_SLOTS]);
            boolean deploy = swarm.isActive() && p.isAlive() && !p.isSpectator();
            // how many fly (the first `cap` bound weapons)
            int size = 0;
            for (int s = 0; s < IWeaponSwarm.MAX_SLOTS && size < cap; s++) if (!swarm.get(s).isEmpty()) size++;
            int order = 0;
            boolean hasDefender = false;
            for (int s = 0; s < IWeaponSwarm.MAX_SLOTS; s++) {
                ItemStack stack = swarm.get(s);
                AutonomousWeaponEntity e = live[s];
                if (e != null && (e.isRemoved() || e.getWorld() != p.getWorld())) {
                    if (!e.isRemoved()) e.discard();
                    e = live[s] = null;
                }
                boolean want = deploy && !stack.isEmpty() && order < cap;
                if (want) {
                    if (e == null) {
                        e = new AutonomousWeaponEntity(ArsenalLegion.WEAPON, p.getWorld());
                        e.setup(p, s, stack);
                        p.getWorld().spawnEntity(e);
                        live[s] = e;
                    } else if (!ItemStack.areEqual(e.getStack(), stack)) {
                        e.setStack(stack);
                    }
                    e.setOrder(order, size);
                    if (SwarmCapability.canDefend(stack)) hasDefender = true;
                    order++;
                } else if (e != null) {
                    e.discard();
                    live[s] = null;
                }
            }
            if (hasDefender && (server.getTicks() & 1) == 0) defend(p, live);
            // tell the owner's HUD when the swarm changes
            int key = swarm.revision() * 31 + cap * 7 + (swarm.isActive() ? 1 : 0);
            Integer last = SENT.get(p.getUuid());
            if (last == null || last != key) {
                SENT.put(p.getUuid(), key);
                LegionPackets.sendSync(p, swarm, cap);
            }
        }
    }

    private static void despawn(UUID owner) {
        AutonomousWeaponEntity[] live = LIVE.remove(owner);
        SENT.remove(owner);
        if (live == null) return;
        for (AutonomousWeaponEntity e : live) if (e != null && !e.isRemoved()) e.discard();
    }

    // ------------------------------------------------------------------------------------------- strikes

    /** A third of the swarm (at least one ready striker) goes for {@code target}. */
    static void retaliate(ServerPlayerEntity p, Entity target) {
        AutonomousWeaponEntity[] live = LIVE.get(p.getUuid());
        if (live == null || target == p) return;
        int flying = 0;
        for (AutonomousWeaponEntity e : live) if (e != null && !e.isRemoved()) flying++;
        int send = Math.max(1, flying / 3);
        Vec3d at = target.getPos();
        // nearest ready strikers first
        while (send > 0) {
            AutonomousWeaponEntity best = null;
            double bd = Double.MAX_VALUE;
            for (AutonomousWeaponEntity e : live) {
                if (e == null || e.isRemoved() || !e.ready() || !SwarmCapability.canStrike(e.getStack())) continue;
                double d = e.squaredDistanceTo(at);
                if (d < bd) {
                    bd = d;
                    best = e;
                }
            }
            if (best == null || !best.strike(target)) return;
            send--;
        }
    }

    // ------------------------------------------------------------------------------------------- defence

    private static void defend(ServerPlayerEntity p, AutonomousWeaponEntity[] live) {
        ServerWorld w = p.getServerWorld();
        Vec3d chest = p.getPos().add(0, p.getHeight() * 0.6, 0);
        List<ProjectileEntity> shots = w.getEntitiesByClass(ProjectileEntity.class, p.getBoundingBox().expand(DEFEND_RADIUS), pr -> incoming(pr, p, chest));
        if (shots.isEmpty()) return;
        Set<Integer> taken = new HashSet<>();
        for (AutonomousWeaponEntity e : live) if (e != null && !e.isRemoved() && !e.ready()) taken.add(e.getId());
        for (ProjectileEntity pr : shots) {
            AutonomousWeaponEntity best = null;
            double bd = Double.MAX_VALUE;
            for (AutonomousWeaponEntity e : live) {
                if (e == null || e.isRemoved() || taken.contains(e.getId()) || !e.ready() || !SwarmCapability.canDefend(e.getStack())) continue;
                double d = e.squaredDistanceTo(pr);
                if (d < bd) {
                    bd = d;
                    best = e;
                }
            }
            if (best == null) return;
            if (best.defend(pr)) taken.add(best.getId());
        }
    }

    /** A live projectile, not the player's own, flying toward them. */
    private static boolean incoming(ProjectileEntity pr, ServerPlayerEntity p, Vec3d chest) {
        if (!pr.isAlive() || pr.getOwner() == p) return false;
        Vec3d v = pr.getVelocity();
        if (v.lengthSquared() < 0.04) return false;   // stuck in the ground or spent
        if (pr instanceof PersistentProjectileEntity && pr.getVelocity().lengthSquared() < 0.1) return false;
        Vec3d to = chest.subtract(pr.getPos());
        double dist = to.length();
        if (dist < 1.2) return false;
        return v.normalize().dotProduct(to.multiply(1 / dist)) > 0.82;
    }

    // ------------------------------------------------------------------------------------------- death

    private static void onDeath(ServerPlayerEntity p) {
        despawn(p.getUuid());
        if (p.getWorld().getGameRules().getBoolean(GameRules.KEEP_INVENTORY)) return;
        for (ItemStack s : SwarmCapability.of(p).takeAll()) {
            ItemEntity item = new ItemEntity(p.getWorld(), p.getX(), p.getY() + 0.5, p.getZ(), s);
            item.setPickupDelay(40);
            p.getWorld().spawnEntity(item);
        }
    }
}
