package com.creedpdf.orbitalrailgun.util;

import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.Explosion;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side effect helpers.
 *
 * <p>{@link #detonate} runs a real {@link Explosion} (block destruction, entity damage and
 * knockback) but never calls {@code ServerWorld.createExplosion}, which would broadcast an
 * explosion packet that makes every client play the vanilla explosion sound and particles. All
 * of the mod's blast visuals and audio are its own; this keeps vanilla's out.</p>
 */
public final class RailgunFx {
    private RailgunFx() {}

    public static void detonate(ServerWorld world, @Nullable Entity source, Vec3d pos, float power, boolean breakBlocks) {
        Explosion explosion = new Explosion(world, source, pos.x, pos.y, pos.z, power, false,
                breakBlocks ? Explosion.DestructionType.DESTROY_WITH_DECAY : Explosion.DestructionType.KEEP);
        explosion.collectBlocksAndDamageEntities();
        explosion.affectWorld(false); // server side: no sound, no particles

        // Vanilla ships player knockback inside the explosion packet; send it ourselves instead.
        for (var entry : explosion.getAffectedPlayers().entrySet()) {
            if (entry.getKey() instanceof ServerPlayerEntity player) {
                Vec3d push = entry.getValue();
                player.setVelocity(player.getVelocity().add(push));
                player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
            }
        }
    }

    /**
     * Spawns particles with long-distance visibility (the "force" flag lets players up to 512
     * blocks away see them, instead of the default 32).
     */
    public static void burst(ServerWorld world, ParticleEffect particle, Vec3d pos, int count,
                             double spreadXZ, double spreadY, double speed) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            world.spawnParticles(player, particle, true, pos.x, pos.y, pos.z, count, spreadXZ, spreadY, spreadXZ, speed);
        }
    }

    /** One particle with an exact velocity (count 0 makes the deltas a velocity vector). */
    public static void launch(ServerWorld world, ParticleEffect particle, Vec3d pos, Vec3d velocity) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            world.spawnParticles(player, particle, true, pos.x, pos.y, pos.z, 0, velocity.x, velocity.y, velocity.z, 1.0);
        }
    }

    /** Vaporises every breakable block within {@code radius} of the segment a..b (no drops). */
    public static void carveLine(ServerWorld world, Vec3d a, Vec3d b, double radius) {
        Vec3d ab = b.subtract(a);
        double len = ab.length();
        if (len < 1e-4) return;
        Vec3d dir = ab.multiply(1.0 / len);
        net.minecraft.util.math.BlockPos min = net.minecraft.util.math.BlockPos.ofFloored(Math.min(a.x, b.x) - radius, Math.min(a.y, b.y) - radius, Math.min(a.z, b.z) - radius);
        net.minecraft.util.math.BlockPos max = net.minecraft.util.math.BlockPos.ofFloored(Math.max(a.x, b.x) + radius, Math.max(a.y, b.y) + radius, Math.max(a.z, b.z) + radius);
        int bottom = world.getBottomY();
        double r2 = radius * radius;
        int budget = 80000;
        for (net.minecraft.util.math.BlockPos pos : net.minecraft.util.math.BlockPos.iterate(min, max)) {
            if (pos.getY() < bottom || pos.getY() >= world.getTopY()) continue;
            Vec3d c = Vec3d.ofCenter(pos);
            double along = net.minecraft.util.math.MathHelper.clamp(c.subtract(a).dotProduct(dir), 0, len);
            if (c.squaredDistanceTo(a.add(dir.multiply(along))) > r2) continue;
            net.minecraft.block.BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.getHardness(world, pos) < 0) continue;
            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState(),
                    net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.SKIP_DROPS);
            if (--budget <= 0) return;
        }
    }
}
