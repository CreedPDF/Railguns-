package com.creedpdf.orbitalrailgun.krl;

import com.creedpdf.orbitalrailgun.entity.MeteorEntity;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KRL air defence. A fire-control console with its radar on sweeps a {@link #RADIUS}-block radius (and up to
 * {@link #CEILING} blocks overhead) twice a second for threats in the air: hostile flyers (phantoms, ghasts, blazes,
 * vexes, the wither, the dragon), any hostile mob caught airborne, fireballs and skulls, and falling meteorites. Each
 * threat is assigned to one of the console's launchers with a loaded magazine beside it; the launcher fires an
 * interceptor that climbs off the rail, turns, and flies a lead-pursuit course at up to {@link #TOP_SPEED} blocks per
 * tick (hypersonic), detonating by proximity fuze.
 */
public final class KrlDefense {
    private KrlDefense() {}

    public static final double RADIUS = 700, CEILING = 900;
    /** Interceptor top speed, blocks per tick. */
    public static final double TOP_SPEED = 28;
    public static final int KIND_FLYER = 0, KIND_PROJECTILE = 1, KIND_METEOR = 2;
    private static final int SCAN_EVERY = 10, FIRE_GAP = 8, MAX_AGE = 240;

    public record Contact(int id, double x, double y, double z, int kind, boolean engaged) {}

    /** One interceptor in flight. */
    static final class Missile {
        final int id;
        final RegistryKey<World> world;
        Vec3d pos, vel;
        int target, age, lost;
        int kind;

        Missile(int id, RegistryKey<World> world, Vec3d pos, Vec3d vel, int target, int kind) {
            this.id = id;
            this.world = world;
            this.pos = pos;
            this.vel = vel;
            this.target = target;
            this.kind = kind;
        }
    }

    private record Boom(Vec3d at, int kind) {}

    private static final Map<RegistryKey<World>, Set<BlockPos>> ACTIVE = new HashMap<>();
    private static final Map<Long, List<Contact>> CONTACTS = new HashMap<>();
    private static final List<Missile> MISSILES = new ArrayList<>();
    private static final Map<RegistryKey<World>, List<Boom>> BOOMS = new HashMap<>();
    private static final Map<Long, Long> LAST_FIRE = new HashMap<>();
    private static final Set<RegistryKey<World>> SENT = new HashSet<>();
    private static int nextId = 1;

    public static void register(World w, BlockPos console) {
        ACTIVE.computeIfAbsent(w.getRegistryKey(), k -> new HashSet<>()).add(console.toImmutable());
    }

    public static void unregister(World w, BlockPos console) {
        Set<BlockPos> s = ACTIVE.get(w.getRegistryKey());
        if (s != null) s.remove(console);
        CONTACTS.remove(console.asLong());
    }

    public static void clear() {
        ACTIVE.clear();
        CONTACTS.clear();
        MISSILES.clear();
        BOOMS.clear();
        LAST_FIRE.clear();
        SENT.clear();
    }

    /** The last radar picture of a console (empty when its radar is off). */
    public static List<Contact> contacts(BlockPos console) {
        return CONTACTS.getOrDefault(console.asLong(), List.of());
    }

    /** Interceptors in the magazines standing within two blocks of a launcher. */
    public static int rounds(World w, BlockPos launcher) {
        int n = 0;
        for (BlockPos p : BlockPos.iterate(launcher.add(-2, -1, -2), launcher.add(2, 1, 2)))
            if (w.getBlockEntity(p) instanceof KrlMagazineBlockEntity m) n += m.rounds();
        return n;
    }

    private static KrlMagazineBlockEntity magazine(World w, BlockPos launcher) {
        for (BlockPos p : BlockPos.iterate(launcher.add(-2, -1, -2), launcher.add(2, 1, 2)))
            if (w.getBlockEntity(p) instanceof KrlMagazineBlockEntity m && m.rounds() > 0) return m;
        return null;
    }

    // ------------------------------------------------------------------------------------------- threats

    static boolean threat(ServerWorld w, Entity e) {
        if (!e.isAlive() || e.isRemoved()) return false;
        if (e instanceof MeteorEntity) return true;
        if (e instanceof ExplosiveProjectileEntity || e instanceof ShulkerBulletEntity) return true;
        if (e instanceof EnderDragonPart) return false;
        if (e instanceof EnderDragonEntity || e instanceof WitherEntity) return true;
        if (!(e instanceof LivingEntity) || !(e instanceof Monster)) return false;
        if (e instanceof FlyingEntity || e instanceof BlazeEntity || e instanceof VexEntity) return true;
        // any other hostile caught in the air, well clear of the ground
        if (e.isOnGround() || e.isTouchingWater()) return false;
        int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, e.getBlockX(), e.getBlockZ());
        return e.getY() - top > 5;
    }

    static int kind(Entity e) {
        if (e instanceof MeteorEntity) return KIND_METEOR;
        if (e instanceof ExplosiveProjectileEntity || e instanceof ShulkerBulletEntity) return KIND_PROJECTILE;
        return KIND_FLYER;
    }

    // ------------------------------------------------------------------------------------------- tick

    public static void tick(ServerWorld w) {
        RegistryKey<World> key = w.getRegistryKey();
        long now = w.getTime();
        Set<BlockPos> consoles = ACTIVE.get(key);
        if (consoles != null && !consoles.isEmpty() && now % SCAN_EVERY == 0) {
            for (BlockPos c : new ArrayList<>(consoles)) {
                if (!w.isChunkLoaded(c) || !(w.getBlockEntity(c) instanceof KrlConsoleBlockEntity be) || !be.radar()) {
                    consoles.remove(c);
                    CONTACTS.remove(c.asLong());
                    continue;
                }
                scan(w, be, now);
            }
        }
        boolean any = false;
        for (Iterator<Missile> it = MISSILES.iterator(); it.hasNext(); ) {
            Missile m = it.next();
            if (m.world != key) continue;
            any = true;
            if (step(w, m)) it.remove();
        }
        List<Boom> booms = BOOMS.remove(key);
        if (any || booms != null || SENT.contains(key)) {
            send(w, booms);
            if (any) SENT.add(key);
            else SENT.remove(key);
        }
    }

    private static void scan(ServerWorld w, KrlConsoleBlockEntity be, long now) {
        BlockPos c = be.getPos();
        Box box = new Box(c.getX() - RADIUS, c.getY() - 48, c.getZ() - RADIUS, c.getX() + RADIUS, c.getY() + CEILING, c.getZ() + RADIUS);
        List<Entity> found = w.getEntitiesByClass(Entity.class, box, e -> {
            double dx = e.getX() - c.getX() - 0.5, dz = e.getZ() - c.getZ() - 0.5;
            return dx * dx + dz * dz <= RADIUS * RADIUS && threat(w, e);
        });
        Set<Integer> engaged = new HashSet<>();
        for (Missile m : MISSILES) if (m.world == w.getRegistryKey()) engaged.add(m.target);
        List<Contact> out = new ArrayList<>();
        // nearest threats first
        found.sort((a, b) -> Double.compare(a.squaredDistanceTo(c.getX(), c.getY(), c.getZ()), b.squaredDistanceTo(c.getX(), c.getY(), c.getZ())));
        for (Entity e : found) {
            boolean eng = engaged.contains(e.getId());
            if (!eng && fire(w, be, e, now)) {
                eng = true;
                engaged.add(e.getId());
            }
            if (out.size() < 64) out.add(new Contact(e.getId(), e.getX(), e.getY(), e.getZ(), kind(e), eng));
        }
        CONTACTS.put(c.asLong(), out);
    }

    /** Fires an interceptor at a threat from the first ready launcher; false if none could. */
    private static boolean fire(ServerWorld w, KrlConsoleBlockEntity be, Entity target, long now) {
        for (BlockPos lp : be.links()) {
            if (!w.isChunkLoaded(lp) || !(w.getBlockEntity(lp) instanceof KrlLauncherBlockEntity l) || l.igniting()) continue;
            Long last = LAST_FIRE.get(lp.asLong());
            if (last != null && now - last < FIRE_GAP) continue;
            KrlMagazineBlockEntity mag = magazine(w, lp);
            if (mag == null) continue;
            mag.add(-1);
            LAST_FIRE.put(lp.asLong(), now);
            Vec3d start = new Vec3d(lp.getX() + 0.5, lp.getY() + 2.6, lp.getZ() + 0.5);
            Vec3d to = target.getPos().subtract(start);
            Vec3d h = new Vec3d(to.x, 0, to.z);
            h = h.lengthSquared() < 1e-6 ? Vec3d.ZERO : h.normalize();
            Vec3d vel = new Vec3d(h.x * 0.35, 1.6, h.z * 0.35);
            MISSILES.add(new Missile(nextId++, w.getRegistryKey(), start, vel, target.getId(), kind(target)));
            w.playSound(null, lp, Krl.S_IGNITE, SoundCategory.BLOCKS, 3.5f, 1.5f);
            w.playSound(null, lp, SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.BLOCKS, 4f, 0.5f);
            return true;
        }
        return false;
    }

    /** Advances one interceptor; true when it is done. */
    private static boolean step(ServerWorld w, Missile m) {
        m.age++;
        Entity t = m.target >= 0 ? w.getEntityById(m.target) : null;
        if (t == null || !t.isAlive() || t.isRemoved()) {
            // the target is gone: pick up the nearest unengaged threat within reach, else self-destruct shortly
            t = reacquire(w, m);
            if (t == null) {
                if (++m.lost > 12) {
                    detonate(w, m, m.pos, null);
                    return true;
                }
            } else {
                m.target = t.getId();
                m.lost = 0;
            }
        }
        double speed = Math.min(TOP_SPEED, 1.6 + m.age * 1.45);
        Vec3d d = m.vel.lengthSquared() < 1e-9 ? new Vec3d(0, 1, 0) : m.vel.normalize();
        Vec3d tc = null, tv = Vec3d.ZERO;
        if (t != null) {
            tc = t.getPos().add(0, t.getHeight() * 0.5, 0);
            tv = t.getVelocity();
            if (m.age > 5) {
                // lead pursuit: aim where the target will be when we get there
                double tgo = Math.min(40, tc.distanceTo(m.pos) / Math.max(4, speed));
                Vec3d aim = tc.add(tv.multiply(tgo));
                Vec3d want = aim.subtract(m.pos).normalize();
                double maxTurn = m.age < 12 ? 0.16 : 0.42;
                double ang = Math.acos(MathHelper.clamp(d.dotProduct(want), -1, 1));
                if (ang > 1e-4) {
                    double k = Math.min(1, maxTurn / ang);
                    d = d.multiply(1 - k).add(want.multiply(k)).normalize();
                }
            }
        }
        Vec3d v = d.multiply(speed);
        // proximity fuze: closest approach during this tick, in the target's frame
        if (tc != null) {
            Vec3d rel = tc.subtract(m.pos), rv = v.subtract(tv);
            double rv2 = rv.lengthSquared();
            double k = rv2 < 1e-9 ? 0 : MathHelper.clamp(rel.dotProduct(rv) / rv2, 0, 1);
            double miss = rel.subtract(rv.multiply(k)).length();
            double fuze = Math.max(3.0, Math.max(t.getWidth(), t.getHeight()) * 0.8);
            if (t instanceof MeteorEntity me) fuze = Math.max(fuze, 2.5 + me.size() * 1.5);
            if (miss < fuze) {
                detonate(w, m, m.pos.add(v.multiply(k)), t);
                return true;
            }
        }
        Vec3d next = m.pos.add(v);
        if (m.age > 4) {
            BlockHitResult hit = w.raycast(new RaycastContext(m.pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE,
                    (Entity) null));
            if (hit.getType() == HitResult.Type.BLOCK) {
                detonate(w, m, hit.getPos(), null);
                return true;
            }
        }
        m.pos = next;
        m.vel = v;
        if (m.age > MAX_AGE || m.pos.y > w.getTopY() + 1200 || m.pos.y < w.getBottomY()) {
            detonate(w, m, m.pos, null);
            return true;
        }
        return false;
    }

    private static Entity reacquire(ServerWorld w, Missile m) {
        Box box = new Box(m.pos, m.pos).expand(160);
        Set<Integer> engaged = new HashSet<>();
        for (Missile o : MISSILES) if (o != m) engaged.add(o.target);
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : w.getEntitiesByClass(Entity.class, box, e -> threat(w, e) && !engaged.contains(e.getId()))) {
            double d2 = e.squaredDistanceTo(m.pos);
            if (d2 < bd) {
                bd = d2;
                best = e;
            }
        }
        return best;
    }

    private static void detonate(ServerWorld w, Missile m, Vec3d at, Entity t) {
        DamageSource src = new DamageSource(w.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(Krl.PLASMA));
        // an air burst: it shreds what is round it but leaves the ground alone
        w.createExplosion(null, src, null, at.x, at.y, at.z, 3.5f, false, World.ExplosionSourceType.NONE);
        w.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 10f, 0.75f + w.random.nextFloat() * 0.2f);
        int kind = 0;
        if (t instanceof MeteorEntity me) {
            me.intercept(w);
            kind = 2;
        } else if (t instanceof ExplosiveProjectileEntity || t instanceof ShulkerBulletEntity) {
            t.discard();
            kind = 1;
        } else if (t instanceof LivingEntity le) {
            float dmg = t instanceof EnderDragonEntity || t instanceof WitherEntity ? 45f : 120f;
            if (t instanceof EnderDragonEntity dragon) dragon.damagePart(dragon.head, src, dmg);
            else le.damage(src, dmg);
            kind = 1;
        }
        BOOMS.computeIfAbsent(m.world, k -> new ArrayList<>()).add(new Boom(at, kind));
    }

    // ------------------------------------------------------------------------------------------- network

    private static void send(ServerWorld w, List<Boom> booms) {
        PacketByteBuf b = PacketByteBufs.create();
        List<Missile> here = new ArrayList<>();
        for (Missile m : MISSILES) if (m.world == w.getRegistryKey()) here.add(m);
        b.writeVarInt(here.size());
        for (Missile m : here) {
            b.writeVarInt(m.id);
            b.writeDouble(m.pos.x);
            b.writeDouble(m.pos.y);
            b.writeDouble(m.pos.z);
            b.writeFloat((float) m.vel.x);
            b.writeFloat((float) m.vel.y);
            b.writeFloat((float) m.vel.z);
        }
        b.writeVarInt(booms == null ? 0 : booms.size());
        if (booms != null) for (Boom bo : booms) {
            b.writeDouble(bo.at().x);
            b.writeDouble(bo.at().y);
            b.writeDouble(bo.at().z);
            b.writeByte(bo.kind());
        }
        for (ServerPlayerEntity p : w.getPlayers()) ServerPlayNetworking.send(p, KrlNet.AA, PacketByteBufs.copy(b));
    }
}
