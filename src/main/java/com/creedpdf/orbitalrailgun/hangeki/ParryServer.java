package com.creedpdf.orbitalrailgun.hangeki;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.krl.KrlFlight;
import com.creedpdf.orbitalrailgun.krl.KrlImpact;
import com.creedpdf.orbitalrailgun.krl.KrlLoadout;
import com.creedpdf.orbitalrailgun.krl.KrlServer;
import com.creedpdf.orbitalrailgun.mixin.FireworkRocketAccessor;
import com.creedpdf.orbitalrailgun.mixin.PersistentProjectileAccessor;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.projectile.LlamaSpitEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Unit;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server half of HANGEKI. Every world tick (before entities move) each sword-bearer's surroundings are scanned
 * for things about to hurt them:
 * <ul>
 *   <li>projectiles closing on them (arrows, tridents, fireballs, skulls, rockets, spit, shulker rounds...);</li>
 *   <li>a KRL missile diving onto the spot they stand on;</li>
 *   <li>a railgun about to fire with them in (or beside) its line of fire;</li>
 *   <li>a warden charging its sonic boom at them.</li>
 * </ul>
 * The nearest one drives the threat meter. When one is about to land (about three blocks out) time slows hard
 * ({@link TimeScale}) and the threat is <i>held</i>: it creeps in but cannot arrive. Parry within the window and the
 * stance plays out (still in slow motion, the bearer untouchable); at its end time snaps back, every held threat hits
 * the blade and is sent back where it came from at twice the force. Miss the window and it lands.
 *
 * <p>Parry with nothing incoming raises a short guard instead: a blow landing in it is countered (the melee side).</p>
 */
public final class ParryServer {
    private ParryServer() {}

    public static final double SCAN = 28, TRIGGER = 3.0, CRAWL = 0.028, HOLD_STOP = 1.9;
    public static final float SLOW_SCALE = 0.16f, HITSTOP_SCALE = 0.3f;
    /** Ticks (slowed) the bearer has to parry once time slows; the stance; the guard; protection after a parry. */
    public static final int SLOW_WINDOW = 26, STANCE_TICKS = 7, GUARD_TICKS = 10, GUARD_COOLDOWN = 16, PROTECT = 20, HITSTOP = 4;
    /** A warden's boom lands this long after its charge starts (SonicBoomTask.SOUND_DELAY). */
    public static final int SONIC_DELAY = 34;

    // ------------------------------------------------------------------------------------------- state

    static final class Threat {
        final byte kind;
        final String label;
        Entity entity;
        double speed, dist, closeness;
        boolean imminent;
        Vec3d origin = Vec3d.ZERO;
        // a held projectile's own state, restored or doubled afterwards
        Vec3d vel0 = Vec3d.ZERO;
        boolean noGrav0;
        double[] power0;
        int life0 = -1;
        // a caught missile (flown by us)
        int flightId = -1;
        KrlServer.Flight flight;
        KrlLoadout missile;
        @Nullable UUID missileOwner;
        Vec3d mPos, mDir;

        Threat(byte kind, String label) {
            this.kind = kind;
            this.label = label;
        }

        Vec3d pos() {
            if (kind == HangekiNet.K_MISSILE) return mPos != null ? mPos : origin;
            if (kind == HangekiNet.K_BEAM && entity instanceof RailgunEntity r) return r.getMuzzlePos(1f);
            if (kind == HangekiNet.K_SONIC && entity != null) return entity.getPos().add(0, 1.6, 0);
            return entity != null ? entity.getPos() : origin;
        }

        boolean same(Threat o) {
            if (kind != o.kind) return false;
            if (kind == HangekiNet.K_MISSILE) return flightId == o.flightId;
            return entity == o.entity;
        }
    }

    static final class Duelist {
        byte phase = HangekiNet.PH_IDLE;
        long phaseStart, guardUntil, protectUntil, cooldownUntil, hitstopUntil;
        final List<Threat> held = new ArrayList<>();
        final Map<UUID, Long> ignore = new HashMap<>();
        final Map<Integer, Long> sonicSeen = new HashMap<>();
        Vec3d focus = Vec3d.ZERO;
        byte meterKind = HangekiNet.K_NONE;
        int meterQ = -1;
        RegistryKey<World> world;

        boolean holds(Threat t) {
            for (Threat h : held) if (h.same(t)) return true;
            return false;
        }
    }

    /** A missile parried back, flying home in a straight line. */
    record Homing(RegistryKey<World> world, Vec3d from, Vec3d to, double speed, long t0, KrlLoadout missile, UUID owner) {}

    /** A projectile caught in a guard: sent back next tick (after its own hit code has run). */
    record Quick(RegistryKey<World> world, ProjectileEntity e, Vec3d vel, UUID player) {}

    private static final Map<UUID, Duelist> DUELISTS = new HashMap<>();
    private static final Set<RailgunEntity> HELD_RAILGUNS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final List<Homing> HOMING = new ArrayList<>();
    private static final List<Quick> QUICK = new ArrayList<>();
    private static final List<Runnable> DEFER = new ArrayList<>();

    public static boolean holdsRailgun(RailgunEntity r) {
        return HELD_RAILGUNS.contains(r);
    }

    public static boolean holding(LivingEntity p) {
        return p.getMainHandStack().isOf(Hangeki.SWORD);
    }

    private static Duelist duelist(ServerPlayerEntity p) {
        return DUELISTS.computeIfAbsent(p.getUuid(), k -> new Duelist());
    }

    public static void forget(ServerPlayerEntity p) {
        Duelist d = DUELISTS.remove(p.getUuid());
        if (d != null) for (Threat t : d.held) release(p.getServerWorld(), p, t, false);
    }

    public static void clear() {
        DUELISTS.clear();
        HELD_RAILGUNS.clear();
        HOMING.clear();
        QUICK.clear();
        DEFER.clear();
        TimeScale.server = 1f;
    }

    private static Vec3d chest(LivingEntity p) {
        return p.getPos().add(0, p.getHeight() * 0.62, 0);
    }

    // ------------------------------------------------------------------------------------------- tick

    public static void tickWorld(ServerWorld w) {
        long now = w.getTime();
        tickQuick(w, now);
        tickHoming(w, now);
        List<? extends RailgunEntity> charging = null;
        for (ServerPlayerEntity p : w.getPlayers()) {
            Duelist d = DUELISTS.get(p.getUuid());
            boolean armed = holding(p) && p.isAlive() && !p.isSpectator();
            if (d == null && !armed) continue;
            if (d == null) d = duelist(p);
            if (d.world != null && d.world != w.getRegistryKey() && d.phase != HangekiNet.PH_IDLE) {
                // changed dimension mid-parry: everything held is let go
                for (Threat t : d.held) release(w, p, t, false);
                d.held.clear();
                d.phase = HangekiNet.PH_IDLE;
            }
            d.world = w.getRegistryKey();
            d.ignore.values().removeIf(until -> until < now);
            if (charging == null && armed) charging = w.getEntitiesByType(RailgunMod.RAILGUN, r -> r.getState() == RailgunState.CHARGING && r.hasTarget());
            switch (d.phase) {
                case HangekiNet.PH_SLOW -> tickSlow(w, p, d, armed, charging, now);
                case HangekiNet.PH_STANCE -> {
                    hold(w, p, d);
                    if (now - d.phaseStart >= STANCE_TICKS) impact(w, p, d, now);
                }
                default -> tickIdle(w, p, d, armed, charging, now);
            }
        }
    }

    private static void tickIdle(ServerWorld w, ServerPlayerEntity p, Duelist d, boolean armed, @Nullable List<? extends RailgunEntity> charging, long now) {
        if (d.phase == HangekiNet.PH_GUARD && now >= d.guardUntil) d.phase = HangekiNet.PH_IDLE;
        if (!armed) {
            meter(p, d, HangekiNet.K_NONE, 0, "", 0, false);
            return;
        }
        List<Threat> seen = scan(w, p, d, charging, now);
        Threat best = null;
        for (Threat t : seen) if (best == null || t.closeness > best.closeness) best = t;
        if (best == null) meter(p, d, HangekiNet.K_NONE, 0, "", 0, false);
        else meter(p, d, best.kind, (float) best.closeness, best.label, seen.size(), false);
        List<Threat> imm = new ArrayList<>();
        for (Threat t : seen) if (t.imminent) imm.add(t);
        if (!imm.isEmpty()) beginSlow(w, p, d, imm, now);
    }

    private static void tickSlow(ServerWorld w, ServerPlayerEntity p, Duelist d, boolean armed, @Nullable List<? extends RailgunEntity> charging, long now) {
        if (!armed) {
            fail(w, p, d, now);
            return;
        }
        // anything else arriving joins the slow motion
        for (Threat t : scan(w, p, d, charging, now)) {
            if (t.imminent && !d.holds(t) && capture(w, p, t)) d.held.add(t);
        }
        d.held.removeIf(t -> gone(t));
        if (d.held.isEmpty()) {
            endSlow(w, p, d, now);
            return;
        }
        hold(w, p, d);
        Threat first = d.held.get(0);
        meter(p, d, first.kind, 1f, first.label, d.held.size(), true);
        if (now - d.phaseStart > SLOW_WINDOW) fail(w, p, d, now);
    }

    private static boolean gone(Threat t) {
        return switch (t.kind) {
            case HangekiNet.K_MISSILE -> false;
            case HangekiNet.K_BEAM -> t.entity == null || t.entity.isRemoved() || ((RailgunEntity) t.entity).getState() != RailgunState.CHARGING;
            default -> t.entity == null || t.entity.isRemoved() || !t.entity.isAlive();
        };
    }

    public static void endServerTick(MinecraftServer server) {
        if (!DEFER.isEmpty()) {
            List<Runnable> run = new ArrayList<>(DEFER);
            DEFER.clear();
            for (Runnable r : run) r.run();
        }
        float s = 1f;
        for (Map.Entry<UUID, Duelist> en : DUELISTS.entrySet()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            if (p == null) continue;
            Duelist d = en.getValue();
            long now = p.getWorld().getTime();
            if (d.phase == HangekiNet.PH_SLOW || d.phase == HangekiNet.PH_STANCE) s = Math.min(s, SLOW_SCALE);
            else if (now < d.hitstopUntil) s = Math.min(s, HITSTOP_SCALE);
        }
        if (s != TimeScale.server) {
            TimeScale.server = s;
            HangekiNet.sendScale(server, s);
        }
    }

    private static void meter(ServerPlayerEntity p, Duelist d, byte kind, float closeness, String label, int count, boolean slowed) {
        int q = kind == HangekiNet.K_NONE ? -1 : Math.round(closeness * 40) + (slowed ? 1000 : 0) + count * 100000;
        if (kind == d.meterKind && q == d.meterQ) return;
        d.meterKind = kind;
        d.meterQ = q;
        HangekiNet.sendMeter(p, kind, closeness, label, count, slowed);
    }

    // ------------------------------------------------------------------------------------------- the scan

    private static List<Threat> scan(ServerWorld w, ServerPlayerEntity p, Duelist d, @Nullable List<? extends RailgunEntity> charging, long now) {
        List<Threat> out = new ArrayList<>();
        Vec3d chest = chest(p);
        // projectiles
        Box box = p.getBoundingBox().expand(SCAN);
        for (ProjectileEntity e : w.getEntitiesByClass(ProjectileEntity.class, box, Entity::isAlive)) {
            if (e instanceof FishingBobberEntity || d.ignore.containsKey(e.getUuid()) || e.getOwner() == p) continue;
            if (e instanceof PersistentProjectileEntity pe && ((PersistentProjectileAccessor) pe).orbitalRailgun$inGround()) continue;
            Vec3d v = e.getVelocity();
            double sp = v.length();
            if (sp < 0.12) continue;
            Vec3d rel = chest.subtract(e.getPos());
            double dist = rel.length();
            if (dist < 1e-3) continue;
            if (rel.dotProduct(v) / dist < 0.08) continue;   // not closing
            double tca = MathHelper.clamp(rel.dotProduct(v) / (sp * sp), 0, 60);
            double miss = e.getPos().add(v.multiply(tca)).distanceTo(chest);
            if (miss > 1.5 + e.getWidth()) continue;
            Threat t = new Threat(HangekiNet.K_PROJECTILE, label(e));
            t.entity = e;
            t.speed = sp;
            t.dist = dist;
            t.closeness = MathHelper.clamp(1 - (dist - TRIGGER) / (SCAN - TRIGGER), 0, 1);
            t.imminent = dist <= TRIGGER + sp;
            Entity owner = e.getOwner();
            t.origin = owner != null && owner.getWorld() == w ? owner.getBoundingBox().getCenter() : e.getPos().subtract(v.normalize().multiply(24));
            out.add(t);
        }
        // KRL missiles diving onto the bearer
        for (KrlServer.Flight f : KrlServer.flights(w)) {
            if (!f.lifted()) continue;
            long t = now - f.liftoff;
            if (t <= KrlFlight.BOOST + KrlFlight.STALL || t >= KrlFlight.TOTAL) continue;
            Vec3d tg = f.target();
            double hd = Math.hypot(tg.x - p.getX(), tg.z - p.getZ());
            if (hd > 12 || Math.abs(tg.y - p.getY()) > 16) continue;
            long eta = KrlFlight.TOTAL - t;
            Threat th = new Threat(HangekiNet.K_MISSILE, "KRL MISSILE");
            th.flightId = f.id;
            th.flight = f;
            th.speed = f.path().speed(t);
            th.mDir = f.path().dir(t);
            th.dist = f.path().pos(t).distanceTo(chest);
            th.closeness = MathHelper.clamp(1 - eta / 140.0, 0, 1);
            th.imminent = eta <= 2 || th.dist <= TRIGGER + th.speed;
            th.origin = Vec3d.ofCenter(f.pad).add(0, 1.2, 0);
            out.add(th);
        }
        // railguns about to fire through the bearer
        if (charging != null) {
            for (RailgunEntity r : charging) {
                Vec3d m = r.getMuzzlePos(1f), tg = Vec3d.ofCenter(r.getTarget());
                Vec3d ab = tg.subtract(m);
                double len = ab.length();
                if (len < 1) continue;
                Vec3d dir = ab.multiply(1 / len);
                double along = MathHelper.clamp(chest.subtract(m).dotProduct(dir), 0, len);
                double off = chest.distanceTo(m.add(dir.multiply(along)));
                double reach = RailgunConfig.BEAM_HIT_RADIUS * r.getLoadout().hitRadiusMul() + 1.5;
                if (off > reach && chest.distanceTo(tg) > 10) continue;
                Threat t = new Threat(HangekiNet.K_BEAM, "RAILGUN");
                t.entity = r;
                t.dist = chest.distanceTo(m);
                t.speed = 40;
                t.closeness = r.getCharge();
                t.imminent = r.getCharge() >= 0.975f;
                t.origin = m;
                out.add(t);
            }
        }
        // wardens charging a sonic boom at the bearer
        Set<Integer> live = new java.util.HashSet<>();
        for (WardenEntity wd : w.getEntitiesByClass(WardenEntity.class, p.getBoundingBox().expand(22), Entity::isAlive)) {
            Brain<WardenEntity> brain = wd.getBrain();
            if (!brain.hasMemoryModule(MemoryModuleType.SONIC_BOOM_SOUND_DELAY)) continue;
            if (brain.getOptionalRegisteredMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) != p) continue;
            live.add(wd.getId());
            long seen = d.sonicSeen.computeIfAbsent(wd.getId(), k -> now);
            long eta = SONIC_DELAY - (now - seen);
            Threat t = new Threat(HangekiNet.K_SONIC, "SONIC BOOM");
            t.entity = wd;
            t.dist = chest.distanceTo(wd.getEyePos());
            t.speed = 20;
            t.closeness = MathHelper.clamp(1 - eta / (double) SONIC_DELAY, 0, 1);
            t.imminent = eta <= 4;
            t.origin = wd.getPos().add(0, 1.6, 0);
            out.add(t);
        }
        d.sonicSeen.keySet().retainAll(live);
        return out;
    }

    private static String label(Entity e) {
        if (e instanceof TridentEntity) return "TRIDENT";
        if (e instanceof FireworkRocketEntity) return "ROCKET";
        if (e instanceof WitherSkullEntity) return "WITHER SKULL";
        if (e instanceof DragonFireballEntity) return "DRAGON FIRE";
        if (e instanceof SmallFireballEntity) return "FIRE CHARGE";
        if (e instanceof FireballEntity) return "FIREBALL";
        if (e instanceof ShulkerBulletEntity) return "SHULKER ROUND";
        if (e instanceof LlamaSpitEntity) return "SPIT";
        if (e instanceof PersistentProjectileEntity) return "ARROW";
        return e.getType().getName().getString().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------------- slow motion

    private static void beginSlow(ServerWorld w, ServerPlayerEntity p, Duelist d, List<Threat> imm, long now) {
        for (Threat t : imm) if (capture(w, p, t)) d.held.add(t);
        if (d.held.isEmpty()) return;
        d.phase = HangekiNet.PH_SLOW;
        d.phaseStart = now;
        d.guardUntil = 0;
        d.focus = d.held.get(0).pos();
        hold(w, p, d);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), Hangeki.S_SLOW, SoundCategory.PLAYERS, 1.4f, 1f);
        HangekiNet.sendState(p, HangekiNet.PH_SLOW, SLOW_WINDOW, d.focus);
        Threat first = d.held.get(0);
        meter(p, d, first.kind, 1f, first.label, d.held.size(), true);
    }

    /** Take hold of a threat. False if it slipped away (a missile already gone). */
    private static boolean capture(ServerWorld w, ServerPlayerEntity p, Threat t) {
        Vec3d chest = chest(p);
        switch (t.kind) {
            case HangekiNet.K_PROJECTILE -> {
                Entity e = t.entity;
                t.vel0 = e.getVelocity();
                t.noGrav0 = e.hasNoGravity();
                if (e instanceof ExplosiveProjectileEntity x) t.power0 = new double[]{x.powerX, x.powerY, x.powerZ};
                if (e instanceof FireworkRocketEntity fw) t.life0 = ((FireworkRocketAccessor) fw).orbitalRailgun$life();
                // it stops right where time caught it: no closer than TRIGGER
                Vec3d rel = e.getPos().subtract(chest);
                if (rel.length() < TRIGGER - 0.2 && t.vel0.lengthSquared() > 1e-6) {
                    Vec3d at = chest.subtract(t.vel0.normalize().multiply(TRIGGER));
                    e.setPosition(at.x, at.y, at.z);
                }
            }
            case HangekiNet.K_MISSILE -> {
                Vec3d pathDir = t.mDir;
                KrlServer.Flight f = KrlServer.intercept(t.flightId);
                if (f == null) return false;
                t.missile = f.missile;
                t.missileOwner = f.owner;
                Vec3d in = pathDir.lengthSquared() > 1e-6 ? pathDir.normalize() : new Vec3d(0, -1, 0);
                t.mPos = chest.subtract(in.multiply(TRIGGER + 0.8));
                t.mDir = chest.subtract(t.mPos).normalize();
            }
            case HangekiNet.K_BEAM -> HELD_RAILGUNS.add((RailgunEntity) t.entity);
            default -> {
            }
        }
        return true;
    }

    /** Keep every held threat hanging in front of the bearer, creeping in. */
    private static void hold(ServerWorld w, ServerPlayerEntity p, Duelist d) {
        Vec3d chest = chest(p);
        for (Threat t : d.held) {
            switch (t.kind) {
                case HangekiNet.K_PROJECTILE -> {
                    Entity e = t.entity;
                    if (e == null || e.isRemoved()) continue;
                    Vec3d to = chest.subtract(e.getPos());
                    double dist = to.length();
                    Vec3d dir = dist > 1e-4 ? to.multiply(1 / dist) : t.vel0.normalize();
                    e.setVelocity(dir.multiply(dist > HOLD_STOP ? CRAWL : 0.002));
                    e.setNoGravity(true);
                    if (e instanceof ExplosiveProjectileEntity x) {
                        x.powerX = 0;
                        x.powerY = 0;
                        x.powerZ = 0;
                    }
                    if (e instanceof FireworkRocketEntity fw && t.life0 >= 0) ((FireworkRocketAccessor) fw).orbitalRailgun$setLife(t.life0);
                    e.velocityDirty = true;
                }
                case HangekiNet.K_MISSILE -> {
                    if (t.mPos.distanceTo(chest) > HOLD_STOP + 0.6) t.mPos = t.mPos.add(t.mDir.multiply(CRAWL));
                    t.mDir = chest.subtract(t.mPos).normalize();
                    HangekiNet.fx(w, HangekiNet.FX_MISSILE_HOLD, t.mPos, t.mDir, t.mPos, p.getId(), t.flightId, 0f, t.missile.livery());
                }
                case HangekiNet.K_SONIC -> {
                    if (t.entity instanceof WardenEntity wd)
                        wd.getBrain().remember(MemoryModuleType.SONIC_BOOM_SOUND_DELAY, Unit.INSTANCE, 3L);
                }
                default -> {
                }
            }
        }
    }

    private static void endSlow(ServerWorld w, ServerPlayerEntity p, Duelist d, long now) {
        d.phase = HangekiNet.PH_IDLE;
        d.held.clear();
        HangekiNet.sendState(p, HangekiNet.PH_IDLE, 0, d.focus);
    }

    /** Too slow (or the sword was put away): everything held lands as it would have. */
    private static void fail(ServerWorld w, ServerPlayerEntity p, Duelist d, long now) {
        for (Threat t : d.held) {
            release(w, p, t, true);
            if (t.entity != null) d.ignore.put(t.entity.getUuid(), now + 80);
        }
        d.held.clear();
        d.phase = HangekiNet.PH_IDLE;
        d.cooldownUntil = now + 10;
        w.playSound(null, p.getX(), p.getY(), p.getZ(), Hangeki.S_RESUME, SoundCategory.PLAYERS, 1f, 0.8f);
        HangekiNet.sendState(p, HangekiNet.PH_IDLE, 0, d.focus);
        HangekiNet.fx(w, HangekiNet.FX_FAIL, p.getEyePos(), Vec3d.ZERO, p.getEyePos(), p.getId(), 0, 0f, 0);
    }

    /** Let go of a threat: it carries on as it was. A caught missile that is let go lands on the bearer. */
    private static void release(ServerWorld w, ServerPlayerEntity p, Threat t, boolean landMissile) {
        switch (t.kind) {
            case HangekiNet.K_PROJECTILE -> {
                Entity e = t.entity;
                if (e == null || e.isRemoved()) return;
                e.setVelocity(t.vel0);
                e.setNoGravity(t.noGrav0);
                if (e instanceof ExplosiveProjectileEntity x && t.power0 != null) {
                    x.powerX = t.power0[0];
                    x.powerY = t.power0[1];
                    x.powerZ = t.power0[2];
                }
                e.velocityDirty = true;
            }
            case HangekiNet.K_MISSILE -> {
                if (landMissile && t.missile != null) KrlImpact.explode(w, p.getPos(), t.missileOwner, t.missile);
            }
            case HangekiNet.K_BEAM -> HELD_RAILGUNS.remove((RailgunEntity) t.entity);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the parry

    /** The parry key / right-click. */
    public static void press(ServerPlayerEntity p) {
        if (!holding(p) || !p.isAlive() || p.isSpectator()) return;
        Duelist d = duelist(p);
        ServerWorld w = p.getServerWorld();
        long now = w.getTime();
        if (d.phase == HangekiNet.PH_SLOW && !d.held.isEmpty()) {
            d.phase = HangekiNet.PH_STANCE;
            d.phaseStart = now;
            d.focus = d.held.get(0).pos();
            d.protectUntil = now + STANCE_TICKS + PROTECT;
            w.playSound(null, p.getX(), p.getY(), p.getZ(), Hangeki.S_STANCE, SoundCategory.PLAYERS, 1.6f, 1f);
            HangekiNet.sendState(p, HangekiNet.PH_STANCE, STANCE_TICKS, d.focus);
            return;
        }
        if (d.phase == HangekiNet.PH_IDLE || d.phase == HangekiNet.PH_GUARD) {
            if (now < d.cooldownUntil) return;
            d.phase = HangekiNet.PH_GUARD;
            d.guardUntil = now + GUARD_TICKS;
            d.cooldownUntil = now + GUARD_COOLDOWN;
            w.playSound(null, p.getX(), p.getY(), p.getZ(), Hangeki.S_GUARD, SoundCategory.PLAYERS, 0.8f, 1f);
            HangekiNet.sendState(p, HangekiNet.PH_GUARD, GUARD_TICKS, p.getEyePos().add(p.getRotationVector().multiply(3)));
        }
    }

    /** The end of the stance: time snaps back and everything held hits the blade and goes home at double force. */
    private static void impact(ServerWorld w, ServerPlayerEntity p, Duelist d, long now) {
        Vec3d eye = p.getEyePos();
        Vec3d face = d.focus.subtract(eye);
        face = face.lengthSquared() < 1e-6 ? p.getRotationVector() : face.normalize();
        Vec3d blade = eye.add(face.multiply(1.1)).add(0, -0.25, 0);
        HangekiNet.fx(w, HangekiNet.FX_REFLECT, blade, face, d.focus, p.getId(), 0, 0f, d.held.size());
        w.playSound(null, blade.x, blade.y, blade.z, Hangeki.S_IMPACT, SoundCategory.PLAYERS, 3.0f, 1f);
        w.playSound(null, blade.x, blade.y, blade.z, Hangeki.S_REFLECT, SoundCategory.PLAYERS, 2.0f, 1f);
        for (Threat t : d.held) {
            try {
                reflect(w, p, d, t, blade, now);
            } catch (RuntimeException ex) {
                RailgunMod.LOGGER.warn("HANGEKI: could not reflect {}", t.label, ex);
            }
        }
        d.held.clear();
        d.phase = HangekiNet.PH_IDLE;
        d.protectUntil = now + PROTECT;
        d.cooldownUntil = now + 6;
        HangekiNet.sendState(p, HangekiNet.PH_IDLE, 0, d.focus);
        w.spawnParticles(ParticleTypes.FLASH, blade.x, blade.y, blade.z, 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.CRIT, blade.x, blade.y, blade.z, 40, 0.3, 0.3, 0.3, 0.9);
    }

    private static void reflect(ServerWorld w, ServerPlayerEntity p, Duelist d, Threat t, Vec3d blade, long now) {
        switch (t.kind) {
            case HangekiNet.K_PROJECTILE -> {
                if (t.entity instanceof ProjectileEntity e && !e.isRemoved())
                    sendBack(w, p, d, e, t.vel0, t.power0, t.life0, t.origin, blade, now, false);
            }
            case HangekiNet.K_MISSILE -> {
                if (t.missile == null) return;
                Vec3d to = t.origin;
                double sp = MathHelper.clamp(t.speed * 2, 6, 64);
                HOMING.add(new Homing(w.getRegistryKey(), blade, to, sp, now, t.missile, p.getUuid()));
                BlockPos tb = BlockPos.ofFloored(to);
                w.getChunkManager().addTicket(ChunkTicketType.PORTAL, new ChunkPos(tb), 3, tb);
                HangekiNet.fx(w, HangekiNet.FX_MISSILE, blade, to.subtract(blade).normalize(), to, p.getId(), t.flightId, (float) sp, t.missile.livery());
            }
            case HangekiNet.K_BEAM -> {
                RailgunEntity r = (RailgunEntity) t.entity;
                HELD_RAILGUNS.remove(r);
                if (r.isRemoved()) return;
                Vec3d muzzle = r.getMuzzlePos(1f);
                Vec3d dir = muzzle.subtract(blade).normalize();
                r.parried();
                HangekiNet.fx(w, HangekiNet.FX_BEAM, blade, dir, muzzle, p.getId(), r.getId(), 0f, r.getLoadout().raw());
                r.damageAlongBeam(w, blade.add(dir.multiply(3)), muzzle, 2f);
                boolean breaks = RailgunConfig.BREAK_BLOCKS && r.getLoadout().breaksBlocks();
                RailgunFx.detonate(w, r, muzzle, RailgunConfig.IMPACT_EXPLOSION_POWER * r.getLoadout().blastMul() * 2f, breaks);
                w.playSound(null, muzzle.x, muzzle.y, muzzle.z, ModSounds.RAILGUN_IMPACT, SoundCategory.BLOCKS, 8f, 0.85f);
                w.playSound(null, blade.x, blade.y, blade.z, ModSounds.RAILGUN_FIRE, SoundCategory.PLAYERS, 4f, 1.1f);
            }
            case HangekiNet.K_SONIC -> {
                if (!(t.entity instanceof WardenEntity wd) || wd.isRemoved()) return;
                wd.getBrain().forget(MemoryModuleType.SONIC_BOOM_SOUND_DELAY);
                wd.getBrain().remember(MemoryModuleType.SONIC_BOOM_SOUND_COOLDOWN, Unit.INSTANCE, 40L);
                sonicBack(w, p, blade, wd.getPos().add(0, 1.6, 0), wd, 20f);
            }
            default -> {
            }
        }
    }

    /** A boom sent back along the line to `to`: the warden's own particles, its sound, double damage and a shove. */
    private static void sonicBack(ServerWorld w, ServerPlayerEntity p, Vec3d from, Vec3d to, LivingEntity victim, float damage) {
        Vec3d d = to.subtract(from);
        double len = d.length();
        Vec3d dir = len < 1e-4 ? Vec3d.ZERO : d.multiply(1 / len);
        for (int i = 1; i < MathHelper.floor(len) + 4; i++) {
            Vec3d q = from.add(dir.multiply(i));
            w.spawnParticles(ParticleTypes.SONIC_BOOM, q.x, q.y, q.z, 1, 0, 0, 0, 0);
        }
        w.playSound(null, from.x, from.y, from.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 3f, 0.8f);
        HangekiNet.fx(w, HangekiNet.FX_SONIC, from, dir, to, p.getId(), victim.getId(), 0f, 0);
        DEFER.add(() -> {
            if (!victim.isAlive()) return;
            victim.timeUntilRegen = 0;
            victim.damage(w.getDamageSources().sonicBoom(p), damage);
            victim.setVelocity(victim.getVelocity().add(dir.x * 1.6, 0.45, dir.z * 1.6));
            victim.velocityDirty = true;
            victim.velocityModified = true;
        });
    }

    /** Point a projectile back at where it came from, at twice its incoming speed, as the bearer's own. */
    private static void sendBack(ServerWorld w, ServerPlayerEntity p, Duelist d, ProjectileEntity e, Vec3d vel0, @Nullable double[] power0, int life0,
                                 Vec3d origin, Vec3d blade, long now, boolean quick) {
        Entity owner = e.getOwner();
        Vec3d target = owner != null && owner != p && owner.isAlive() && owner.getWorld() == w ? owner.getBoundingBox().getCenter() : origin;
        if (e instanceof ShulkerBulletEntity) {
            // a homing round would only turn round again: it is swatted, and its shooter gets twice its sting
            e.discard();
            if (owner instanceof LivingEntity lo && lo.isAlive()) {
                lo.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 200, 1), p);
                sonicBack(w, p, blade, lo.getBoundingBox().getCenter(), lo, 8f);
            }
            return;
        }
        Vec3d dir0 = target.subtract(blade);
        Vec3d dir = dir0.lengthSquared() < 1e-6 ? vel0.multiply(-1).normalize() : dir0.normalize();
        Vec3d start = blade.add(dir.multiply(0.7));
        double sp = Math.max(1.6, vel0.length() * 2.0);
        e.setPosition(start.x, start.y, start.z);
        e.setOwner(p);
        e.setVelocity(dir.multiply(sp));
        e.setNoGravity(true);   // straight back down the line it came in on
        if (e instanceof ExplosiveProjectileEntity x) {
            double pw = 0.1;
            if (power0 != null) pw = Math.max(pw, Math.sqrt(power0[0] * power0[0] + power0[1] * power0[1] + power0[2] * power0[2]));
            pw *= 2;
            x.powerX = dir.x * pw;
            x.powerY = dir.y * pw;
            x.powerZ = dir.z * pw;
        }
        if (e instanceof PersistentProjectileEntity a) a.setCritical(true);
        if (e instanceof FireworkRocketEntity fw) {
            FireworkRocketAccessor acc = (FireworkRocketAccessor) fw;
            int life = life0 >= 0 ? life0 : acc.orbitalRailgun$life();
            acc.orbitalRailgun$setLife(life);
            acc.orbitalRailgun$setLifeTime(life + (int) Math.ceil(target.distanceTo(start) / sp) + 6);
        }
        ProjectileUtil.setRotationFromVelocity(e, 1f);
        e.velocityDirty = true;
        e.velocityModified = true;
        d.ignore.put(e.getUuid(), now + 200);
        HangekiNet.fx(w, quick ? HangekiNet.FX_REFLECT_QUICK : HangekiNet.FX_REFLECT, blade, dir, target, p.getId(), e.getId(), (float) sp, -1);
    }

    // ------------------------------------------------------------------------------------------- the melee side

    private static boolean unparryable(DamageSource s) {
        return s.isOf(DamageTypes.OUT_OF_WORLD) || s.isOf(DamageTypes.GENERIC_KILL) || s.isOf(DamageTypes.STARVE) || s.isOf(DamageTypes.DROWN)
                || s.isOf(DamageTypes.IN_WALL) || s.isOf(DamageTypes.FALL) || s.isOf(DamageTypes.CRAMMING) || s.isOf(DamageTypes.OUTSIDE_BORDER)
                || s.isOf(DamageTypes.FLY_INTO_WALL) || s.isOf(DamageTypes.FREEZE) || s.isOf(DamageTypes.ON_FIRE) || s.isOf(DamageTypes.IN_FIRE)
                || s.isOf(DamageTypes.LAVA) || s.isOf(DamageTypes.HOT_FLOOR) || s.isOf(DamageTypes.WITHER) || s.isOf(DamageTypes.CACTUS)
                || s.isOf(DamageTypes.SWEET_BERRY_BUSH) || s.isOf(DamageTypes.DRY_OUT) || s.isOf(DamageTypes.STALAGMITE) || s.isOf(DamageTypes.MAGIC)
                || s.isOf(DamageTypes.GENERIC);
    }

    /** ALLOW_DAMAGE for players: untouchable through a stance and just after a parry; a guard counters what lands in it. */
    public static boolean allowDamage(ServerPlayerEntity p, DamageSource source, float amount) {
        Duelist d = DUELISTS.get(p.getUuid());
        if (d == null || unparryable(source)) return true;
        long now = p.getWorld().getTime();
        if (d.phase == HangekiNet.PH_STANCE || now < d.protectUntil) return false;
        if (now < d.guardUntil && holding(p)) {
            guardHit(p, d, source, amount, now);
            return false;
        }
        return true;
    }

    private static void guardHit(ServerPlayerEntity p, Duelist d, DamageSource source, float amount, long now) {
        ServerWorld w = p.getServerWorld();
        d.guardUntil = 0;
        d.phase = HangekiNet.PH_IDLE;
        d.protectUntil = now + 8;
        d.cooldownUntil = now;
        d.hitstopUntil = now + HITSTOP;
        Entity src = source.getSource(), att = source.getAttacker();
        Vec3d eye = p.getEyePos();
        Vec3d from = src != null ? src.getBoundingBox().getCenter() : att != null ? att.getBoundingBox().getCenter() : eye.add(p.getRotationVector().multiply(2));
        Vec3d face = from.subtract(eye);
        face = face.lengthSquared() < 1e-6 ? p.getRotationVector() : face.normalize();
        Vec3d contact = eye.add(face.multiply(0.9)).add(0, -0.25, 0);
        w.playSound(null, contact.x, contact.y, contact.z, Hangeki.S_COUNTER, SoundCategory.PLAYERS, 2.2f, 1f);
        if (src instanceof ProjectileEntity proj && src != att) {
            QUICK.add(new Quick(w.getRegistryKey(), proj, proj.getVelocity(), p.getUuid()));
            HangekiNet.fx(w, HangekiNet.FX_COUNTER, contact, face, from, p.getId(), proj.getId(), 0f, 1);
            return;
        }
        HangekiNet.fx(w, HangekiNet.FX_COUNTER, contact, face, from, p.getId(), att != null ? att.getId() : 0, 0f, 0);
        if (att instanceof LivingEntity l && l != p) {
            float dmg = Math.max(6f, amount * 2f);
            DEFER.add(() -> counter(p, l, dmg));
        }
    }

    /** The riposte: double the blow back, a hard shove away and a stagger. */
    private static void counter(ServerPlayerEntity p, LivingEntity l, float dmg) {
        if (!l.isAlive() || !p.isAlive()) return;
        l.timeUntilRegen = 0;
        l.damage(p.getDamageSources().playerAttack(p), dmg);
        double dx = p.getX() - l.getX(), dz = p.getZ() - l.getZ();
        if (dx * dx + dz * dz < 1e-6) dx = 0.01;
        l.takeKnockback(2.2, dx, dz);
        l.addVelocity(0, 0.35, 0);
        l.velocityModified = true;
        l.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50, 3), p);
        l.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 50, 1), p);
        ServerWorld w = p.getServerWorld();
        Vec3d c = l.getBoundingBox().getCenter();
        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 30, 0.3, 0.4, 0.3, 0.8);
    }

    private static void tickQuick(ServerWorld w, long now) {
        if (QUICK.isEmpty()) return;
        for (Iterator<Quick> it = QUICK.iterator(); it.hasNext(); ) {
            Quick q = it.next();
            if (q.world() != w.getRegistryKey()) continue;
            it.remove();
            ProjectileEntity e = q.e();
            if (e.isRemoved()) continue;
            if (!(w.getPlayerByUuid(q.player()) instanceof ServerPlayerEntity p)) continue;
            Duelist d = duelist(p);
            Vec3d eye = p.getEyePos();
            Vec3d face = e.getPos().subtract(eye);
            face = face.lengthSquared() < 1e-6 ? p.getRotationVector() : face.normalize();
            Vec3d blade = eye.add(face.multiply(1.0)).add(0, -0.25, 0);
            Entity owner = e.getOwner();
            Vec3d origin = owner != null ? owner.getBoundingBox().getCenter() : e.getPos().subtract(q.vel().normalize().multiply(24));
            double[] pw = e instanceof ExplosiveProjectileEntity x ? new double[]{x.powerX, x.powerY, x.powerZ} : null;
            int life = e instanceof FireworkRocketEntity fw ? ((FireworkRocketAccessor) fw).orbitalRailgun$life() : -1;
            sendBack(w, p, d, e, q.vel(), pw, life, origin, blade, now, true);
            w.playSound(null, blade.x, blade.y, blade.z, Hangeki.S_REFLECT, SoundCategory.PLAYERS, 1.4f, 1.15f);
        }
    }

    private static void tickHoming(ServerWorld w, long now) {
        if (HOMING.isEmpty()) return;
        for (Iterator<Homing> it = HOMING.iterator(); it.hasNext(); ) {
            Homing h = it.next();
            if (h.world() != w.getRegistryKey()) continue;
            double len = h.to().distanceTo(h.from());
            if ((now - h.t0()) * h.speed() < len) continue;
            it.remove();
            w.getChunk(BlockPos.ofFloored(h.to()));
            KrlImpact.explodeReflected(w, h.to(), h.owner(), h.missile());
        }
    }
}
