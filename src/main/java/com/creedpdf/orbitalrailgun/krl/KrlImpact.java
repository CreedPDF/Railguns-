package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The missile's arrival, shaped by its fit-out ({@link KrlLoadout}): vanilla explosions for the blast, plus a dug crater
 * with a scorched rim, and the warhead's own effect - kinetic, high explosive, thermobaric, incendiary, bunker buster,
 * cluster bomblets, a shaped-charge shaft, MIRV sub-strikes or a seismic collapse. The fuze moves the detonation point
 * (airburst above, delayed below) and the casing adds fragments or a denser penetrator.
 */
public final class KrlImpact {
    private KrlImpact() {}

    /** Ticks after impact during which follow-up detonations happen. */
    public static final int FOLLOW = 30;

    private static DamageSource source(ServerWorld w, @Nullable UUID owner) {
        Entity attacker = owner == null ? null : w.getEntity(owner);
        return new DamageSource(w.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(Krl.PLASMA), null, attacker);
    }

    static void boom(ServerWorld w, double x, double y, double z, float power, boolean fire, @Nullable UUID owner) {
        w.createExplosion(null, source(w, owner), null, x, y, z, power, fire, World.ExplosionSourceType.TNT);
    }

    private static void blastOnly(ServerWorld w, double x, double y, double z, float power, @Nullable UUID owner) {
        w.createExplosion(null, source(w, owner), null, x, y, z, power, false, World.ExplosionSourceType.NONE);
    }

    public static void explode(ServerWorld w, Vec3d at, @Nullable UUID owner, KrlLoadout m) {
        double y = at.y - 0.5;
        if (m.fuze() == 1) y += 9;
        if (m.fuze() == 2) y -= 6;
        float pw = m.power();
        switch (m.warhead()) {
            case KrlLoadout.BUNKER, KrlLoadout.SEISMIC -> boom(w, at.x, y, at.z, 5f, false, owner);
            case KrlLoadout.CLUSTER -> boom(w, at.x, y, at.z, 6f, false, owner);
            case KrlLoadout.MIRV -> boom(w, at.x, y, at.z, pw, false, owner);
            case KrlLoadout.SHAPED -> {
                boom(w, at.x, y, at.z, pw * 0.6f, false, owner);
                shaft(w, BlockPos.ofFloored(at.x, y, at.z), 2.6f + 0.25f * m.charge(), 34 + 9 * m.charge());
            }
            case KrlLoadout.INCENDIARY -> {
                boom(w, at.x, y, at.z, pw, true, owner);
                crater(w, new Vec3d(at.x, y, at.z), m.crater(), m.craterDepth(), Blocks.MAGMA_BLOCK, w.random);
                ignite(w, BlockPos.ofFloored(at), 14 + 3 * m.charge(), w.random);
            }
            default -> {
                boom(w, at.x, y, at.z, pw, m.fire(), owner);
                // a second, wider ring of blasts throws the debris further out
                if (pw >= 14) {
                    for (int i = 0; i < 6; i++) {
                        double a = i * Math.PI / 3 + w.random.nextDouble() * 0.4, d = pw * 0.55;
                        double bx = at.x + Math.cos(a) * d, bz = at.z + Math.sin(a) * d;
                        int by = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(bx), (int) Math.floor(bz));
                        boom(w, bx, Math.min(by, y + 4), bz, pw * 0.42f, m.fire(), owner);
                    }
                }
                crater(w, new Vec3d(at.x, y, at.z), m.crater(), m.craterDepth(),
                        m.warhead() == KrlLoadout.THERMOBARIC ? Blocks.MAGMA_BLOCK : null, w.random);
                if (m.warhead() == KrlLoadout.THERMOBARIC) ignite(w, BlockPos.ofFloored(at), (int) (pw * 0.7f), w.random);
            }
        }
        if (m.casing() == 1) fragments(w, new Vec3d(at.x, y, at.z), pw * 1.6f, owner);
    }

    /** Follow-up detonations, `step` ticks after impact. */
    public static void follow(ServerWorld w, Vec3d at, @Nullable UUID owner, KrlLoadout m, int step, int seed) {
        if ((m.warhead() == KrlLoadout.BUNKER || m.warhead() == KrlLoadout.SEISMIC) && step == 6) {
            double depth = m.warhead() == KrlLoadout.BUNKER ? 10 + 4 * m.charge() : 12 + 2 * m.charge();
            Vec3d c = new Vec3d(at.x, at.y - depth, at.z);
            boom(w, c.x, c.y, c.z, m.power(), false, owner);
            if (m.warhead() == KrlLoadout.BUNKER) crater(w, c, m.power() * 0.6f, 1.1f, null, w.random);
        }
        if (m.warhead() == KrlLoadout.SEISMIC && step == 10) {
            // the collapse: the ground over the cavity falls in across a wide bowl
            crater(w, new Vec3d(at.x, at.y + 1, at.z), m.crater(), m.craterDepth(), null, w.random);
            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(at, at).expand(m.crater() * 1.6), LivingEntity::isOnGround))
                e.addVelocity(0, 0.9 + w.random.nextDouble() * 0.5, 0);
        }
        if (m.warhead() == KrlLoadout.CLUSTER) {
            int n = 8 + 2 * m.charge();
            Random r = Random.create(seed * 31L + 7);
            double radius = 12 + 4 * m.charge();
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + r.nextDouble() * 0.5;
                double d = radius * (0.5 + r.nextDouble() * 0.5);
                int when = 2 + i * 2 + r.nextInt(2);
                if (when != step) continue;
                int x = (int) Math.floor(at.x + Math.cos(a) * d), z = (int) Math.floor(at.z + Math.sin(a) * d);
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                boom(w, x + 0.5, y, z + 0.5, m.power(), false, owner);
            }
        }
        if (m.warhead() == KrlLoadout.MIRV && step % 4 == 0 && step >= 4 && step <= 16) {
            int i = step / 4 - 1;
            Random r = Random.create(seed * 17L + i);
            double a = i * Math.PI / 2 + r.nextDouble() * 0.6, d = 20 + 4 * m.charge();
            int x = (int) Math.floor(at.x + Math.cos(a) * d), z = (int) Math.floor(at.z + Math.sin(a) * d);
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            boom(w, x + 0.5, y, z + 0.5, m.power(), false, owner);
            crater(w, new Vec3d(x + 0.5, y, z + 0.5), m.power() * 0.75f, 0.8f, null, w.random);
        }
    }

    /**
     * Digs a bowl: an ellipsoid of radius r (vertical r*depth below the burst, a third of that above), ragged at the
     * edge; the rim is left scorched (blackstone, basalt, coal, or the given melt block).
     */
    static void crater(ServerWorld w, Vec3d c, float r, float depth, @Nullable Block melt, Random rnd) {
        if (r < 2) return;
        int ri = (int) Math.ceil(r);
        float down = r * depth, up = Math.max(3, r * 0.35f);
        BlockPos.Mutable p = new BlockPos.Mutable();
        int cx = (int) Math.floor(c.x), cy = (int) Math.floor(c.y), cz = (int) Math.floor(c.z);
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        for (int dy = -(int) Math.ceil(down); dy <= (int) Math.ceil(up); dy++) {
            int y = cy + dy;
            if (w.isOutOfHeightLimit(y)) continue;
            float vr = dy < 0 ? down : up;
            for (int dx = -ri; dx <= ri; dx++)
                for (int dz = -ri; dz <= ri; dz++) {
                    float n = (dx * dx + dz * dz) / (r * r) + (dy * dy) / (vr * vr);
                    // a ragged edge: a little noise on the boundary
                    float edge = 1f + (float) (MathHelper.sin(dx * 0.9f + dz * 1.3f) * 0.05 + (rnd.nextFloat() - 0.5f) * 0.08);
                    if (n > edge * 1.12f) continue;
                    p.set(cx + dx, y, cz + dz);
                    BlockState s = w.getBlockState(p);
                    if (s.isAir() || s.getHardness(w, p) < 0) continue;
                    if (n <= edge) {
                        w.setBlockState(p, Blocks.AIR.getDefaultState(), flags);
                    } else if (dy <= 1 && !s.getFluidState().isStill() && rnd.nextFloat() < 0.55f) {
                        float k = rnd.nextFloat();
                        Block b = melt != null && k < 0.3f ? melt : k < 0.55f ? Blocks.BLACKSTONE : k < 0.8f ? Blocks.BASALT : k < 0.9f ? Blocks.COAL_BLOCK
                                : Blocks.COBBLED_DEEPSLATE;
                        w.setBlockState(p, b.getDefaultState(), flags);
                    }
                }
        }
    }

    /** A narrow shaft bored straight down (shaped charge jet). */
    private static void shaft(ServerWorld w, BlockPos top, float radius, int depth) {
        BlockPos.Mutable p = new BlockPos.Mutable();
        int ri = (int) Math.ceil(radius);
        for (int dy = 2; dy >= -depth; dy--) {
            float rr = radius * (1 - 0.5f * (-dy) / (float) depth);
            for (int dx = -ri; dx <= ri; dx++)
                for (int dz = -ri; dz <= ri; dz++) {
                    if (dx * dx + dz * dz > rr * rr) continue;
                    p.set(top.getX() + dx, top.getY() + dy, top.getZ() + dz);
                    if (w.isOutOfHeightLimit(p.getY())) continue;
                    BlockState s = w.getBlockState(p);
                    if (s.isAir() || s.getHardness(w, p) < 0) continue;
                    w.setBlockState(p, (dx * dx + dz * dz > (rr - 1) * (rr - 1) ? Blocks.MAGMA_BLOCK : Blocks.AIR).getDefaultState(),
                            Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }
        }
    }

    /** Fragmentation casing: shrapnel blasts (no block damage) in a ring round the detonation. */
    private static void fragments(ServerWorld w, Vec3d c, float radius, @Nullable UUID owner) {
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI / 5 + w.random.nextDouble() * 0.3, d = radius * (0.6 + w.random.nextDouble() * 0.4);
            double x = c.x + Math.cos(a) * d, z = c.z + Math.sin(a) * d;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
            blastOnly(w, x, y + 1, z, 4f, owner);
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
