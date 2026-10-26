package com.creedpdf.orbitalrailgun.krl;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every KRL flight in progress, run from world time so nothing depends on where the spear happens to be loaded:
 * the minute on the rail, liftoff, the climb, the stall, the fall and the impact. Clients get each flight once (launch
 * point, target, liftoff time) and draw it from the same {@link KrlFlight} curves.
 *
 * <p>A player who rides along is kept, invisible and invulnerable, at the cutscene's camera ({@link KrlFlight#anchor})
 * so the world streams in around what they are watching - the pad, the spear's path, the target - and is put back
 * where they stood afterwards. Sneaking ends the ride.</p>
 */
public final class KrlServer {
    private KrlServer() {}

    public static final class Flight {
        public final int id;
        public final RegistryKey<World> world;
        public final BlockPos pad;
        public final long liftoff;
        @Nullable
        public final UUID owner;
        @Nullable
        UUID rider;
        Vec3d launch, target;
        KrlFlight path;
        boolean lifted;
        @Nullable
        KrlImpact impact;

        Flight(int id, RegistryKey<World> world, BlockPos pad, Vec3d launch, Vec3d target, long liftoff, @Nullable UUID owner) {
            this.id = id;
            this.world = world;
            this.pad = pad;
            this.launch = launch;
            this.target = target;
            this.liftoff = liftoff;
            this.owner = owner;
            this.path = new KrlFlight(launch, target);
        }

        public Vec3d target() {
            return target;
        }

        public KrlFlight path() {
            return path;
        }
    }

    private static final class Ride {
        final UUID player;
        final int flight;
        final RegistryKey<World> world;
        final Vec3d back;
        final float yaw, pitch;
        final boolean allowFlying, flying, invulnerable, invisible;
        /** Sneaking ends the ride - once the key has been let go since it began. */
        boolean released;

        Ride(ServerPlayerEntity p, int flight) {
            this.player = p.getUuid();
            this.flight = flight;
            this.world = p.getWorld().getRegistryKey();
            this.back = p.getPos();
            this.yaw = p.getYaw();
            this.pitch = p.getPitch();
            this.allowFlying = p.getAbilities().allowFlying;
            this.flying = p.getAbilities().flying;
            this.invulnerable = p.isInvulnerable();
            this.invisible = p.isInvisible();
            this.released = !p.isSneaking();
        }
    }

    private static final Map<Integer, Flight> FLIGHTS = new LinkedHashMap<>();
    private static final Map<UUID, Ride> RIDES = new HashMap<>();
    private static int nextId = 1;

    @Nullable
    public static Flight flight(int id) {
        return id < 0 ? null : FLIGHTS.get(id);
    }

    public static List<Flight> flights(World w) {
        List<Flight> out = new ArrayList<>();
        for (Flight f : FLIGHTS.values()) if (f.world == w.getRegistryKey()) out.add(f);
        return out;
    }

    public static boolean riding(Entity e) {
        return RIDES.containsKey(e.getUuid());
    }

    // ------------------------------------------------------------------------------------------- orders

    /** Light the motor of a loaded launcher; the spear leaves {@link Krl#IGNITION} ticks later. */
    @Nullable
    public static Flight ignite(ServerWorld w, KrlLauncherBlockEntity be, BlockPos target, ServerPlayerEntity by, boolean ride) {
        if (!be.loaded() || be.igniting()) return null;
        Vec3d launch = be.railPoint();
        Vec3d tgt = Vec3d.ofBottomCenter(target).add(0, 1, 0);
        Flight f = new Flight(nextId++, w.getRegistryKey(), be.getPos().toImmutable(), launch, tgt, w.getTime() + Krl.IGNITION, by.getUuid());
        if (ride && !RIDES.containsKey(by.getUuid()) && !anyRide(by.getUuid())) f.rider = by.getUuid();
        FLIGHTS.put(f.id, f);
        be.ignited(f.id);
        KrlNet.sendFlight(w, f);
        RailgunMod.LOGGER.info("KRL: {} lit launcher {} for {} ({} blocks)", by.getName().getString(), be.getPos().toShortString(),
                target.toShortString(), (int) f.path.dist);
        return f;
    }

    private static boolean anyRide(UUID who) {
        for (Flight f : FLIGHTS.values()) if (who.equals(f.rider)) return true;
        return false;
    }

    /** Call off a countdown (only while the spear is still on the rail). */
    public static boolean abort(int id) {
        Flight f = FLIGHTS.get(id);
        if (f == null || f.lifted) return false;
        FLIGHTS.remove(id);
        MinecraftServer s = server();
        ServerWorld w = s == null ? null : s.getWorld(f.world);
        if (w != null) {
            if (w.isChunkLoaded(f.pad) && w.getBlockEntity(f.pad) instanceof KrlLauncherBlockEntity be && be.flight() == id) be.aborted();
            KrlNet.sendFlightEnd(w, id);
            if (f.rider != null && w.getPlayerByUuid(f.rider) instanceof ServerPlayerEntity p) release(p);
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------- tick

    private static MinecraftServer SERVER;

    @Nullable
    private static MinecraftServer server() {
        return SERVER;
    }

    public static void tick(ServerWorld w) {
        SERVER = w.getServer();
        if (FLIGHTS.isEmpty() && RIDES.isEmpty()) return;
        long now = w.getTime();
        Iterator<Flight> it = FLIGHTS.values().iterator();
        List<Flight> done = new ArrayList<>();
        while (it.hasNext()) {
            Flight f = it.next();
            if (f.world != w.getRegistryKey()) continue;
            long t = now - f.liftoff;
            // keep the pad loaded through the countdown and the target from the moment the motor lights
            if (t < 10) w.getChunkManager().addTicket(ChunkTicketType.PORTAL, new ChunkPos(f.pad), 2, f.pad);
            BlockPos tb = BlockPos.ofFloored(f.target);
            w.getChunkManager().addTicket(ChunkTicketType.PORTAL, new ChunkPos(tb), 3, tb);

            if (t == -40) settleTarget(w, f);
            if (t == -KrlFlight.PAD_LEAD && f.rider != null) {
                if (w.getPlayerByUuid(f.rider) instanceof ServerPlayerEntity p && p.isAlive() && !RIDES.containsKey(p.getUuid())) mount(p, f);
                else f.rider = null;
            }
            if (t == 0) liftoff(w, f);
            if (t == KrlFlight.BOOST + 4) w.playSound(null, f.path.apex.x, f.path.apex.y, f.path.apex.z, Krl.S_STALL, SoundCategory.BLOCKS, 12f, 1f);
            if (t == KrlFlight.TOTAL - 80) w.playSound(null, f.target.x, f.target.y + 20, f.target.z, Krl.S_INCOMING, SoundCategory.BLOCKS, 40f, 1f);
            if (t == KrlFlight.TOTAL) {
                f.impact = new KrlImpact(w, BlockPos.ofFloored(f.target).down(), f.owner);
                f.impact.blast();
            }
            if (f.impact != null && t > KrlFlight.TOTAL) f.impact.carve((int) (t - KrlFlight.TOTAL));
            if (t >= KrlFlight.TOTAL + KrlFlight.AFTER) done.add(f);
        }
        for (Flight f : done) {
            FLIGHTS.remove(f.id);
            if (f.rider != null && w.getPlayerByUuid(f.rider) instanceof ServerPlayerEntity p) release(p);
        }
        tickRides(w);
    }

    /** A second before liftoff the target's chunk is long loaded: drop the aim point onto the real surface there. */
    private static void settleTarget(ServerWorld w, Flight f) {
        int x = (int) Math.floor(f.target.x), z = (int) Math.floor(f.target.z);
        int y = KrlMap.surfaceY(w, x, z);
        Vec3d t = new Vec3d(x + 0.5, y + 1, z + 0.5);
        if (Math.abs(t.y - f.target.y) < 0.5) return;
        f.target = t;
        f.path = new KrlFlight(f.launch, f.target);
        KrlNet.sendFlight(w, f);
    }

    private static void liftoff(ServerWorld w, Flight f) {
        f.lifted = true;
        if (w.getBlockEntity(f.pad) instanceof KrlLauncherBlockEntity be) be.lifted();
        Vec3d at = f.launch;
        w.playSound(null, at.x, at.y + 2, at.z, Krl.S_BOOM, SoundCategory.BLOCKS, 32f, 1f);
        w.playSound(null, at.x, at.y + 2, at.z, Krl.S_CLIMB, SoundCategory.BLOCKS, 16f, 1f);
        // the sonic boom and the exhaust shove everything near the pad away
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(BlockPos.ofFloored(at)).expand(12), LivingEntity::isAlive)) {
            if (riding(e)) continue;
            Vec3d d = e.getPos().subtract(at);
            double len = Math.max(0.5, d.length());
            double k = Math.max(0, 1 - len / 12);
            if (k <= 0) continue;
            e.addVelocity(d.x / len * 1.8 * k, 0.55 * k, d.z / len * 1.8 * k);
            e.velocityModified = true;
            e.damage(w.getDamageSources().create(Krl.PLASMA), (float) (10 * k));
            if (k > 0.4) e.setOnFireFor(5);
        }
        RailgunMod.LOGGER.info("KRL: spear {} away from {}", f.id, f.pad.toShortString());
    }

    // ------------------------------------------------------------------------------------------- riding

    private static void mount(ServerPlayerEntity p, Flight f) {
        p.stopRiding();
        Ride r = new Ride(p, f.id);
        RIDES.put(p.getUuid(), r);
        p.getAbilities().allowFlying = true;
        p.getAbilities().flying = true;
        p.sendAbilitiesUpdate();
        p.setInvulnerable(true);
        p.setInvisible(true);
        KrlNet.sendRide(p, f.id, true);
        hold(p, f, p.getServerWorld().getTime() - f.liftoff);
    }

    private static void hold(ServerPlayerEntity p, Flight f, long t) {
        Vec3d a = f.path.anchor(t);
        p.networkHandler.requestTeleport(a.x, a.y, a.z, p.getYaw(), p.getPitch());
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
        p.getServerWorld().getChunkManager().updatePosition(p);
    }

    private static void tickRides(ServerWorld w) {
        if (RIDES.isEmpty()) return;
        List<ServerPlayerEntity> end = new ArrayList<>();
        for (Ride r : RIDES.values()) {
            if (r.world != w.getRegistryKey()) continue;
            if (!(w.getPlayerByUuid(r.player) instanceof ServerPlayerEntity p)) continue;
            Flight f = FLIGHTS.get(r.flight);
            if (f == null || !p.isAlive()) {
                end.add(p);
                continue;
            }
            if (!p.isSneaking()) r.released = true;
            else if (r.released) {
                end.add(p);
                continue;
            }
            p.setInvisible(true);
            p.setOnFire(false);
            p.fallDistance = 0;
            long t = w.getTime() - f.liftoff;
            // every other tick is plenty for the world to follow; the camera itself is the client's
            if (t % 2 == 0 || t == KrlFlight.RIDE || t == KrlFlight.GROUND) hold(p, f, t);
        }
        for (ServerPlayerEntity p : end) release(p);
    }

    /** Put a rider back where they stood. */
    public static void release(ServerPlayerEntity p) {
        Ride r = RIDES.remove(p.getUuid());
        if (r == null) return;
        Flight f = FLIGHTS.get(r.flight);
        if (f != null && p.getUuid().equals(f.rider)) f.rider = null;
        p.getAbilities().allowFlying = r.allowFlying;
        p.getAbilities().flying = r.flying;
        p.sendAbilitiesUpdate();
        p.setInvulnerable(r.invulnerable);
        p.setInvisible(r.invisible);
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
        ServerWorld home = p.getServer() == null ? null : p.getServer().getWorld(r.world);
        if (home != null && home == p.getServerWorld()) {
            p.networkHandler.requestTeleport(r.back.x, r.back.y, r.back.z, r.yaw, r.pitch);
            home.getChunkManager().updatePosition(p);
        } else if (home != null) {
            p.teleport(home, r.back.x, r.back.y, r.back.z, r.yaw, r.pitch);
        }
        KrlNet.sendRide(p, r.flight, false);
    }

    public static void join(ServerPlayerEntity p) {
        SERVER = p.getServer();
        for (Flight f : flights(p.getWorld())) KrlNet.sendFlight(p, f);
    }

    public static void stopping(MinecraftServer s) {
        for (UUID u : new ArrayList<>(RIDES.keySet())) {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(u);
            if (p != null) release(p);
        }
        RIDES.clear();
    }

    public static void clear() {
        FLIGHTS.clear();
        RIDES.clear();
        SERVER = null;
    }
}
