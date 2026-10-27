package com.creedpdf.orbitalrailgun.station;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * An orbital strike from a Space Command satellite. Timeline in ticks from the order (the client's effects and
 * cutscene use the same numbers):
 * <pre>
 *   0      the satellite slews onto the target and its emitter charges   (a red reticle spins up on the ground)
 *   FIRE   it fires: the beam leaves the emitter and tears down through the sky
 *   IMPACT the beam reaches the ground: a huge blast, a shockwave, and the beam keeps boring into the crater
 *   BEAM_END  the beam cuts out, leaving a glowing crater, fire and smoke
 * </pre>
 */
public final class ScStrike {
    private ScStrike() {}

    public static final int FIRE = 50, IMPACT = 62, BEAM_END = 112, END = 130;
    public static final double RADIUS = 7;
    private static final ChunkTicketType<ChunkPos> TICKET = ChunkTicketType.create("orbital_railgun_strike", Comparator.comparingLong(ChunkPos::toLong), END + 40);

    static final class Strike {
        final int id;
        final RegistryKey<World> world;
        final Vec3d target;
        final UUID owner;
        int age;

        Strike(int id, RegistryKey<World> world, Vec3d target, UUID owner) {
            this.id = id;
            this.world = world;
            this.target = target;
            this.owner = owner;
        }
    }

    private static final List<Strike> ACTIVE = new ArrayList<>();
    private static int nextId = 1;

    static void clear() {
        ACTIVE.clear();
    }

    public static boolean busy(UUID owner) {
        for (Strike s : ACTIVE) if (s.owner.equals(owner)) return true;
        return false;
    }

    /** Validate and start a strike for a player at a ground point. Returns a denial reason, or null when fired. */
    static String order(ServerPlayerEntity p, BlockPos aim) {
        MinecraftServer server = p.getServer();
        ScSatellites store = ScSatellites.get(server);
        long now = ScSatellites.now(server);
        ScSatellites.Rec rec = store.best(p.getUuid(), now);
        if (rec == null) return "no_satellite";
        if (rec.chargeAt(now) < 1f) return "charging";
        if (busy(p.getUuid())) return "busy";
        ServerWorld w = (ServerWorld) p.getWorld();
        double dx = aim.getX() + 0.5 - p.getX(), dz = aim.getZ() + 0.5 - p.getZ();
        if (dx * dx + dz * dz > Sc.STRIKE_RANGE * Sc.STRIKE_RANGE) return "range";
        ChunkPos cp = new ChunkPos(aim);
        w.getChunkManager().addTicket(TICKET, cp, 2, cp);
        int y = w.isChunkLoaded(cp.x, cp.z) ? w.getTopY(Heightmap.Type.MOTION_BLOCKING, aim.getX(), aim.getZ()) : aim.getY();
        Vec3d target = new Vec3d(aim.getX() + 0.5, y, aim.getZ() + 0.5);
        store.spend(rec, now);
        ServerWorld satWorld = server.getWorld(rec.dim);
        if (satWorld != null && satWorld.isChunkLoaded(rec.pos) && satWorld.getBlockEntity(rec.pos) instanceof ScSatelliteBlockEntity be) be.firedNow(satWorld);
        Strike s = new Strike(nextId++, w.getRegistryKey(), target, p.getUuid());
        ACTIVE.add(s);
        ScNet.broadcastStrike(w, s.id, target, p);
        p.playSound(Sc.S_SAT_UPLINK, SoundCategory.PLAYERS, 1f, 1f);
        return null;
    }

    public static void tick(MinecraftServer server) {
        for (Iterator<Strike> it = ACTIVE.iterator(); it.hasNext(); ) {
            Strike s = it.next();
            ServerWorld w = server.getWorld(s.world);
            if (w == null || ++s.age > END) {
                it.remove();
                continue;
            }
            step(w, s);
        }
    }

    private static void step(ServerWorld w, Strike s) {
        Vec3d t = s.target;
        Random r = w.random;
        int a = s.age;
        // the shooter hears their own soundtrack (client side, timed to the cutscene)
        net.minecraft.entity.player.PlayerEntity shooter = w.getServer().getPlayerManager().getPlayer(s.owner);
        if (a == FIRE) w.playSound(shooter, t.x, t.y + 20, t.z, Sc.S_STRIKE_BEAM, SoundCategory.BLOCKS, 24f, 1f);
        if (a == IMPACT) {
            w.playSound(shooter, t.x, t.y, t.z, Sc.S_STRIKE_IMPACT, SoundCategory.BLOCKS, 40f, 1f);
            w.playSound(shooter, t.x, t.y, t.z, Sc.S_STRIKE_RUMBLE, SoundCategory.BLOCKS, 30f, 1f);
            w.createExplosion(null, t.x, t.y, t.z, 9f, true, World.ExplosionSourceType.TNT);
            burn(w, t, 18, 34f);
        }
        if (a > IMPACT && a < BEAM_END) {
            // the beam keeps boring down into the crater
            if ((a - IMPACT) % 6 == 0) {
                double ang = r.nextDouble() * Math.PI * 2, rr = r.nextDouble() * RADIUS * 0.6;
                Vec3d p = new Vec3d(t.x + Math.cos(ang) * rr, t.y, t.z + Math.sin(ang) * rr);
                int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(p.x), MathHelper.floor(p.z));
                w.createExplosion(null, p.x, Math.min(top, t.y), p.z, 4f, false, World.ExplosionSourceType.TNT);
            }
            if (a % 2 == 0) burn(w, t, 6, 6f);
        }
        if (a == BEAM_END) scorch(w, t, r);
    }

    /** Heat damage and fire for everything within radius of the beam (falls off with distance). */
    private static void burn(ServerWorld w, Vec3d t, double radius, float dmg) {
        Box box = new Box(t.x - radius, t.y - 12, t.z - radius, t.x + radius, t.y + 64, t.z + radius);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            double dx = e.getX() - t.x, dz = e.getZ() - t.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > radius) continue;
            float k = (float) (1 - d / radius);
            e.damage(w.getDamageSources().inFire(), dmg * k + 1f);
            e.setOnFireFor(4 + (int) (6 * k));
        }
    }

    /** The beam cuts out: the bottom of the crater glows (magma), its rim burns. */
    private static void scorch(ServerWorld w, Vec3d t, Random r) {
        int cx = MathHelper.floor(t.x), cz = MathHelper.floor(t.z);
        int rad = (int) RADIUS + 2;
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > rad) continue;
                int x = cx + dx, z = cz + dz;
                int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                BlockPos below = new BlockPos(x, top - 1, z);
                BlockState bs = w.getBlockState(below);
                if (bs.isAir() || bs.getHardness(w, below) < 0) continue;
                if (d < RADIUS * 0.55 && r.nextFloat() < 0.7f) w.setBlockState(below, Blocks.MAGMA_BLOCK.getDefaultState());
                else if (d < RADIUS && r.nextFloat() < 0.35f) w.setBlockState(below, r.nextBoolean() ? Blocks.BLACKSTONE.getDefaultState() : Blocks.BASALT.getDefaultState());
                if (d > RADIUS * 0.5 && r.nextFloat() < 0.25f && w.getBlockState(below.up()).isAir()) w.setBlockState(below.up(), Blocks.FIRE.getDefaultState());
            }
    }
}
