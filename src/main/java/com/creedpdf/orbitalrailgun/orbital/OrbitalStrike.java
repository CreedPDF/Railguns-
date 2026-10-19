package com.creedpdf.orbitalrailgun.orbital;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.loadout.AmmoFx;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.loadout.Loadout.Option;
import com.creedpdf.orbitalrailgun.loadout.LoadoutEffects;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.creedpdf.orbitalrailgun.util.RailgunFx;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * One orbital shot, ticked on the server. The beam leaves orbit at {@link OrbitalConfig#WORLD_PHASE_T},
 * its head travels down a ~1100 block column and hits at {@link OrbitalConfig#IMPACT_T}: a heavier
 * blast than the ground railgun, a deep vertical bore, two rings of secondary blasts and the
 * installed ammunition's effects. Everyone in the dimension sees the beam (orbital_beam packet).
 */
public final class OrbitalStrike {
    private static final List<OrbitalStrike> ACTIVE = new ArrayList<>();
    private static final ChunkTicketType<ChunkPos> TICKET =
            ChunkTicketType.create("orbital_railgun_strike", Comparator.comparingLong(ChunkPos::toLong), 400);

    private final ServerWorld world;
    private final UUID owner;
    private final Vec3d surface;
    private final BlockPos target;
    private final Loadout loadout;
    private final Vec3d dir = OrbitalConfig.BEAM_DIR;
    private final Vec3d muzzle;
    private final long seed;
    private int t;

    private OrbitalStrike(ServerWorld world, UUID owner, BlockPos target, Loadout loadout) {
        this.world = world;
        this.owner = owner;
        this.target = target;
        this.surface = Vec3d.ofCenter(target).add(0, 0.5, 0);
        this.loadout = loadout;
        this.muzzle = OrbitalConfig.muzzleFor(surface);
        this.seed = target.asLong() ^ world.getTime();
    }

    public static void start(ServerWorld world, UUID owner, BlockPos target, Loadout loadout) {
        OrbitalStrike s = new OrbitalStrike(world, owner, target, loadout);
        ChunkPos cp = new ChunkPos(target);
        world.getChunkManager().addTicket(TICKET, cp, 4, cp);
        ACTIVE.add(s);
    }

    public static void tick(ServerWorld world) {
        for (Iterator<OrbitalStrike> it = ACTIVE.iterator(); it.hasNext(); ) {
            OrbitalStrike s = it.next();
            if (s.world != world) continue;
            if (s.tickOnce()) {
                ChunkPos cp = new ChunkPos(s.target);
                world.getChunkManager().removeTicket(TICKET, cp, 4, cp);
                it.remove();
            }
        }
    }

    public static void clear() {
        ACTIVE.clear();
    }

    /** @return true when finished */
    private boolean tickOnce() {
        int now = t++;
        if (now == OrbitalConfig.WORLD_PHASE_T) launch();
        if (now == OrbitalConfig.WORLD_PHASE_T + 6) farSound(ModSounds.ORBITAL_INCOMING, 1400, 1f);
        if (now == OrbitalConfig.IMPACT_T) impact();
        int b = now - OrbitalConfig.IMPACT_T;
        if (b > 0 && b <= OrbitalConfig.BURN_TICKS) burn(b);
        if (b == 8 || b == 16) rings(b == 8 ? 0.75 : 1.3, b == 16);
        return b > OrbitalConfig.BURN_TICKS + 40;
    }

    // ---------------------------------------------------------------------------------------------

    private void launch() {
        OrbitalPackets.broadcastOrbitalBeam(world, muzzle, target, loadout.raw(),
                OrbitalConfig.IMPACT_T - OrbitalConfig.WORLD_PHASE_T);
        // Clear anything standing in the beam's column above the target (trees, roofs).
        if (RailgunConfig.BREAK_BLOCKS && loadout.breaksBlocks()) {
            RailgunFx.carveLine(world, surface.subtract(dir.multiply(120)), surface, 2.5);
        }
    }

    private void impact() {
        boolean breaks = RailgunConfig.BREAK_BLOCKS && loadout.breaksBlocks();
        world.getChunk(target);
        RailgunFx.detonate(world, null, surface, RailgunConfig.IMPACT_EXPLOSION_POWER * OrbitalConfig.IMPACT_POWER_MUL * loadout.blastMul(), breaks);
        if (breaks) LoadoutEffects.carveSphere(world, surface.add(0, -2, 0), 7);
        damageArea(surface, 14, RailgunConfig.BEAM_DAMAGE * 2f * loadout.damageMul());

        RailgunFx.burst(world, AmmoFx.flash(loadout), surface.add(0, 3, 0), 14, 4.0, 3.0, 0);
        RailgunFx.burst(world, AmmoFx.smoke(loadout), surface, 420, 12.0, 7.0, 0.22);
        RailgunFx.burst(world, ModParticles.DEBRIS, surface.add(0, 1, 0), breaks ? 500 : 60, 5.0, 3.0, 1.8);
        RailgunFx.burst(world, AmmoFx.ember(loadout), surface.add(0, 1, 0), 320, 6.0, 4.0, 1.5);
        RailgunFx.burst(world, AmmoFx.spark(loadout), surface.add(0, 1, 0), 400, 4.0, 4.0, 2.4);
        ring(AmmoFx.dust(loadout), surface, 4.0, 128, 2.2);
        ring(AmmoFx.dust(loadout), surface, 9.0, 128, 1.5);
        ring(ModParticles.SHOCK_DUST, surface, 14.0, 128, 1.0);

        farSound(ModSounds.ORBITAL_IMPACT, 2000, 1f);
        Option ammo = loadout.ammo();
        world.playSound(null, surface.x, surface.y, surface.z,
                ammo == null ? ModSounds.RAILGUN_IMPACT : ModSounds.impact(ammo), SoundCategory.BLOCKS, 24f, 0.85f);
        ammoImpact(ammo);
    }

    private void burn(int b) {
        boolean breaks = RailgunConfig.BREAK_BLOCKS && loadout.breaksBlocks();
        double max = OrbitalConfig.BORE_DEPTH * loadout.boreDepthMul();
        double d0 = Math.min(max, (b - 1) * OrbitalConfig.BORE_SPEED * loadout.boreDepthMul());
        double d1 = Math.min(max, b * OrbitalConfig.BORE_SPEED * loadout.boreDepthMul());
        Vec3d tip = surface.add(dir.multiply(d1));
        if (breaks && d1 > d0) {
            RailgunFx.carveLine(world, surface.add(dir.multiply(d0)), tip, OrbitalConfig.BORE_RADIUS * loadout.boreRadiusMul());
        }
        if (b % RailgunConfig.BORE_BLAST_EVERY == 0 && d1 > d0) {
            RailgunFx.detonate(world, null, tip, RailgunConfig.BORE_BLAST_POWER * 1.4f * (float) loadout.boreRadiusMul(), breaks);
            RailgunFx.burst(world, AmmoFx.flash(loadout), tip, 1, 0.5, 0.5, 0);
        }
        RailgunFx.burst(world, AmmoFx.spark(loadout), tip, 20, 2.0, 2.0, 1.0);
        if (b % 2 == 0) {
            RailgunFx.burst(world, AmmoFx.ember(loadout), surface.add(0, 1, 0), 24, 3.5, 1.0, 1.1);
            RailgunFx.burst(world, AmmoFx.smoke(loadout), surface.add(0, 2, 0), 10, 3.0, 1.5, 0.18);
        }
        if (b % 4 == 0) damageColumn();
        ammoBurn(b);
    }

    private void rings(double scale, boolean offset) {
        boolean breaks = RailgunConfig.BREAK_BLOCKS && loadout.breaksBlocks();
        double rr = RailgunConfig.RING_BLAST_RADIUS * 1.5 * scale;
        int n = RailgunConfig.RING_BLASTS + 4;
        for (int i = 0; i < n; i++) {
            double a = (i + (offset ? 0.5 : 0)) * Math.PI * 2 / n;
            Vec3d p = LoadoutEffects.surface(world, surface.add(Math.cos(a) * rr, 0, Math.sin(a) * rr));
            RailgunFx.detonate(world, null, p, RailgunConfig.RING_BLAST_POWER * 1.3f * loadout.ringBlastMul(), breaks);
            RailgunFx.burst(world, AmmoFx.smoke(loadout), p.add(0, 1, 0), 22, 2.5, 1.8, 0.12);
            RailgunFx.burst(world, AmmoFx.ember(loadout), p.add(0, 1, 0), 16, 1.8, 1.0, 0.7);
        }
        ring(AmmoFx.dust(loadout), surface, rr, 64, 1.0);
    }

    // ---- ammunition -----------------------------------------------------------------------------

    private void ammoImpact(Option ammo) {
        if (ammo == null) return;
        Vec3d c = surface;
        switch (ammo) {
            case TUNGSTEN -> RailgunFx.burst(world, ModParticles.SPALL, c.add(0, 1, 0), 400, 3.5, 2.5, 2.0);
            case THERMITE -> {
                LoadoutEffects.ignite(world, c, 20);
                if (RailgunConfig.BREAK_BLOCKS) LoadoutEffects.magma(world, c, 10);
                for (LivingEntity e : living(c, 20)) e.setOnFireFor(15);
                RailgunFx.burst(world, ModParticles.THERMITE_FLAME, c.add(0, 1, 0), 500, 11, 4, 0.3);
            }
            case CRYO -> {
                LoadoutEffects.freeze(world, c, 22);
                for (LivingEntity e : living(c, 22)) {
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 300, 3));
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 300, 2));
                    e.setFrozenTicks(Math.max(e.getFrozenTicks(), 500));
                    e.extinguish();
                }
                RailgunFx.burst(world, ModParticles.FROST, c.add(0, 2, 0), 600, 13, 4, 0.4);
            }
            case ION -> chainLightning(c);
            case SINGULARITY -> RailgunFx.burst(world, ModParticles.SINGULARITY_MOTE, c.add(0, 3, 0), 90, 1.5, 1.5, 0.05);
            case SUNBURST -> {
                for (LivingEntity e : living(c, 55)) {
                    Vec3d d = e.getPos().subtract(c);
                    double len = Math.max(1, d.length());
                    e.addVelocity(d.multiply(3.0 / len / Math.max(1, len / 12)).add(0, 1.0, 0));
                    e.velocityModified = true;
                    e.setOnFireFor(10);
                }
            }
            default -> { }
        }
    }

    private void ammoBurn(int b) {
        Option ammo = loadout.ammo();
        if (ammo == null) return;
        Random r = world.random;
        switch (ammo) {
            case THERMITE -> {
                if (b % 2 == 0) RailgunFx.burst(world, ModParticles.THERMITE_FLAME, surface.add(0, 1, 0), 40, 8, 2, 0.18);
                if (b % 10 == 0) LoadoutEffects.ignite(world, surface, 14 + b / 4);
            }
            case CRYO -> {
                if (b % 2 == 0) RailgunFx.burst(world, ModParticles.FROST, surface.add(0, 2, 0), 40, 11, 3, 0.25);
                if (b % 10 == 0) LoadoutEffects.freeze(world, surface, 14 + b / 3);
            }
            case ION -> {
                if (b % 4 == 0) {
                    for (int i = 0; i < 6; i++) {
                        Vec3d a = surface.add((r.nextDouble() - 0.5) * 24, r.nextDouble() * 9, (r.nextDouble() - 0.5) * 24);
                        LoadoutEffects.arc(world, surface.add(0, 1, 0), a);
                    }
                }
                if (b % 15 == 0) chainLightning(surface);
            }
            case CLUSTER -> cluster(b);
            case SINGULARITY -> singularity(b);
            case SUNBURST -> LoadoutEffects.sunburstTick(world, b, surface);
            case TUNGSTEN -> RailgunFx.burst(world, ModParticles.SPALL, surface.add(dir.multiply(-2)), 20, 2, 2, 1.2);
            default -> { }
        }
    }

    private void cluster(int b) {
        Random r = Random.create(seed);
        for (int i = 0; i < 24; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 7 + r.nextDouble() * 22;
            int due = 10 + i * 2 + r.nextInt(3);
            Vec3d spot = LoadoutEffects.surface(world, surface.add(Math.cos(a) * d, 0, Math.sin(a) * d));
            if (b == due - 10) RailgunFx.launch(world, ModParticles.BOMBLET, spot.add(0, 18, 0), new Vec3d(0, -1.8, 0));
            if (b == due) {
                RailgunFx.detonate(world, null, spot, 6.5f, RailgunConfig.BREAK_BLOCKS);
                RailgunFx.burst(world, AmmoFx.flash(loadout), spot.add(0, 1, 0), 1, 0.5, 0.5, 0);
                RailgunFx.burst(world, AmmoFx.smoke(loadout), spot, 30, 2, 1.5, 0.1);
                RailgunFx.burst(world, AmmoFx.spark(loadout), spot, 20, 1.5, 1, 0.5);
            }
        }
    }

    private void singularity(int b) {
        Vec3d core = surface.add(0, 4, 0);
        Random r = world.random;
        if (b < 50) {
            for (Entity e : world.getOtherEntities(null, new Box(core, core).expand(34), Entity::isAlive)) {
                if (e instanceof PlayerEntity p && p.isSpectator()) continue;
                Vec3d d = core.subtract(e.getPos());
                double len = Math.max(1, d.length());
                e.addVelocity(d.multiply(0.14 / len * (1 + b / 25.0)));
                e.velocityModified = true;
            }
            for (int i = 0; i < 14; i++) {
                double a = r.nextDouble() * Math.PI * 2, rad = 8 + r.nextDouble() * 18, y = (r.nextDouble() - 0.3) * 10;
                Vec3d p = core.add(Math.cos(a) * rad, y, Math.sin(a) * rad);
                Vec3d v = core.subtract(p).multiply(0.06).add(-Math.sin(a) * 0.3, 0, Math.cos(a) * 0.3);
                RailgunFx.launch(world, i % 3 == 0 ? ModParticles.DEBRIS : ModParticles.SINGULARITY_MOTE, p, v);
            }
        } else if (b == 50) {
            if (RailgunConfig.BREAK_BLOCKS) LoadoutEffects.carveSphere(world, surface, 12);
            RailgunFx.detonate(world, null, surface, 15f, RailgunConfig.BREAK_BLOCKS);
            damageArea(core, 13, 70f);
            RailgunFx.burst(world, AmmoFx.flash(loadout), core, 8, 2, 2, 0);
            RailgunFx.burst(world, ModParticles.SINGULARITY_MOTE, core, 400, 2, 2, 1.8);
            RailgunFx.burst(world, AmmoFx.spark(loadout), core, 200, 2, 2, 1.4);
            ring(AmmoFx.smoke(loadout), surface, 5, 160, 2.4);
        }
    }

    private void chainLightning(Vec3d c) {
        List<LivingEntity> pool = new ArrayList<>(living(c, 34));
        pool.removeIf(e -> e.getUuid().equals(owner));
        Vec3d from = c.add(0, 1, 0);
        float dmg = 36f * loadout.damageMul();
        for (int hop = 0; hop < 10 && !pool.isEmpty(); hop++) {
            final Vec3d f = from;
            LivingEntity next = pool.stream().min(Comparator.comparingDouble(e -> e.getPos().squaredDistanceTo(f))).get();
            pool.remove(next);
            Vec3d to = next.getBoundingBox().getCenter();
            LoadoutEffects.arc(world, from, to);
            next.damage(damage(), dmg);
            RailgunFx.burst(world, ModParticles.ION_ARC, to, 6, 0.6, 0.6, 0.1);
            from = to;
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private DamageSource damage() {
        Entity attacker = world.getServer() == null ? null : world.getServer().getPlayerManager().getPlayer(owner);
        return new DamageSource(world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(RailgunMod.BEAM_DAMAGE),
                null, attacker);
    }

    private List<LivingEntity> living(Vec3d c, double r) {
        return world.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(r),
                e -> e.isAlive() && !(e instanceof PlayerEntity p && p.isSpectator()));
    }

    private void damageArea(Vec3d c, double r, float dmg) {
        for (LivingEntity e : living(c, r)) {
            double d = e.getPos().distanceTo(c);
            if (d <= r) e.damage(damage(), dmg * (float) (1.0 - 0.6 * d / r));
        }
    }

    private void damageColumn() {
        Vec3d top = surface.subtract(dir.multiply(40));
        double hit = RailgunConfig.BEAM_HIT_RADIUS * 1.6 * loadout.hitRadiusMul();
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(top, surface).expand(hit + 1),
                x -> x.isAlive() && !(x instanceof PlayerEntity p && p.isSpectator()))) {
            e.damage(damage(), RailgunConfig.BEAM_DAMAGE * 0.4f * loadout.damageMul());
        }
    }

    private void ring(net.minecraft.particle.ParticleEffect particle, Vec3d center, double radius, int count, double speed) {
        Random r = world.random;
        for (int i = 0; i < count; i++) {
            double a = i / (double) count * Math.PI * 2;
            Vec3d d = new Vec3d(Math.cos(a), 0, Math.sin(a));
            RailgunFx.launch(world, particle, center.add(d.multiply(radius)), d.multiply(speed * (0.8 + r.nextDouble() * 0.4)).add(0, 0.02, 0));
        }
    }

    /** A distant roar every player within {@code range} hears, wherever they are. */
    private void farSound(SoundEvent sound, double range, float pitch) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            double d = p.getPos().distanceTo(surface);
            if (d > range) continue;
            float vol = (float) Math.max(0.25, 1.0 - d / range) * 1.4f;
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
                    net.minecraft.registry.Registries.SOUND_EVENT.getEntry(sound), SoundCategory.BLOCKS,
                    p.getX(), p.getY(), p.getZ(), vol, pitch, world.random.nextLong()));
        }
    }
}
