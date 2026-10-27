package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The missile's arrival, as plain vanilla explosions shaped by its warhead ({@link KrlLoadout}):
 * kinetic (just the impact), high explosive, thermobaric (wider and burning), incendiary (sets the area alight),
 * bunker buster (a small surface blast, then the real one deep underground) and cluster (a ring of bomblets).
 */
public final class KrlImpact {
    private KrlImpact() {}

    /** Ticks after impact during which follow-up detonations happen (bunker buster, bomblets). */
    public static final int FOLLOW = 30;

    private static DamageSource source(ServerWorld w, @Nullable UUID owner) {
        Entity attacker = owner == null ? null : w.getEntity(owner);
        return new DamageSource(w.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(Krl.PLASMA), null, attacker);
    }

    private static void boom(ServerWorld w, double x, double y, double z, float power, boolean fire, @Nullable UUID owner) {
        w.createExplosion(null, source(w, owner), null, x, y, z, power, fire, World.ExplosionSourceType.TNT);
    }

    public static void explode(ServerWorld w, Vec3d at, @Nullable UUID owner, KrlLoadout m) {
        switch (m.warhead()) {
            case KrlLoadout.BUNKER -> boom(w, at.x, at.y - 0.5, at.z, 5f, false, owner);
            case KrlLoadout.CLUSTER -> boom(w, at.x, at.y - 0.5, at.z, 6f, false, owner);
            case KrlLoadout.INCENDIARY -> {
                boom(w, at.x, at.y - 0.5, at.z, m.power(), true, owner);
                ignite(w, BlockPos.ofFloored(at), 10 + 2 * m.charge(), w.random);
            }
            default -> boom(w, at.x, at.y - 0.5, at.z, m.power(), m.fire(), owner);
        }
    }

    /** Follow-up detonations, `step` ticks after impact. */
    public static void follow(ServerWorld w, Vec3d at, @Nullable UUID owner, KrlLoadout m, int step, int seed) {
        if (m.warhead() == KrlLoadout.BUNKER && step == 6) {
            double depth = 8 + 3 * m.charge();
            boom(w, at.x, at.y - depth, at.z, m.power(), false, owner);
        }
        if (m.warhead() == KrlLoadout.CLUSTER) {
            int n = 6 + m.charge();
            Random r = Random.create(seed * 31L + 7);
            double radius = 10 + 3 * m.charge();
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + r.nextDouble() * 0.5;
                double d = radius * (0.55 + r.nextDouble() * 0.45);
                int when = 2 + i * 2 + r.nextInt(2);
                if (when != step) continue;
                int x = (int) Math.floor(at.x + Math.cos(a) * d), z = (int) Math.floor(at.z + Math.sin(a) * d);
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                boom(w, x + 0.5, y, z + 0.5, m.power(), false, owner);
            }
        }
    }

    /** Light fires on the surface round the impact. */
    private static void ignite(ServerWorld w, BlockPos c, int radius, Random r) {
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int i = 0; i < radius * radius; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * radius;
            int x = c.getX() + (int) Math.round(Math.cos(a) * d), z = c.getZ() + (int) Math.round(Math.sin(a) * d);
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            p.set(x, y, z);
            if (!w.getBlockState(p).isAir()) continue;
            BlockState fire = AbstractFireBlock.getState(w, p);
            if (fire.canPlaceAt(w, p)) w.setBlockState(p, fire);
        }
    }
}
