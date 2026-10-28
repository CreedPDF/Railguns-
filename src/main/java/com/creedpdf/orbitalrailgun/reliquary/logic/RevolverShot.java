package com.creedpdf.orbitalrailgun.reliquary.logic;

import com.creedpdf.orbitalrailgun.reliquary.ModBlocks;
import com.creedpdf.orbitalrailgun.reliquary.ModDamageTypes;
import com.creedpdf.orbitalrailgun.reliquary.ModItems;
import com.creedpdf.orbitalrailgun.reliquary.ModSounds;
import com.creedpdf.orbitalrailgun.reliquary.block.MoltenScarBlock;
import com.creedpdf.orbitalrailgun.reliquary.block.MoltenScarBlockEntity;
import com.creedpdf.orbitalrailgun.reliquary.item.CoreRevolverItem;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.Set;

/**
 * A Core-Infused Revolver round. The ray runs in four 16-block stages: each stage collects every living thing whose
 * box the stage crosses (entities never stop it - it pierces them all, as many as there are) and checks the blocks;
 * the first solid surface ends it and gets a molten scar on the face it hit.
 */
public final class RevolverShot {
    private RevolverShot() {}

    private static final int STAGES = 4;

    public static void fire(ServerPlayerEntity p) {
        if (!p.getMainHandStack().isOf(ModItems.CORE_REVOLVER) || p.getItemCooldownManager().isCoolingDown(ModItems.CORE_REVOLVER)) return;
        p.getItemCooldownManager().set(ModItems.CORE_REVOLVER, CoreRevolverItem.COOLDOWN);
        p.swingHand(Hand.MAIN_HAND, true);
        ServerWorld w = p.getServerWorld();
        Vec3d eye = p.getEyePos(), dir = p.getRotationVec(1f);
        double stage = CoreRevolverItem.RANGE / STAGES;
        Set<Entity> hit = new HashSet<>();
        Vec3d from = eye, end = eye.add(dir.multiply(CoreRevolverItem.RANGE));
        BlockHitResult wall = null;
        for (int s = 0; s < STAGES; s++) {
            Vec3d to = from.add(dir.multiply(stage));
            BlockHitResult bh = w.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
            Vec3d segEnd = bh.getType() == HitResult.Type.MISS ? to : bh.getPos();
            for (Entity e : w.getOtherEntities(p, new Box(from, segEnd).expand(1), x -> x instanceof LivingEntity && x.isAlive())) {
                if (e.getBoundingBox().expand(0.3).raycast(from, segEnd).isPresent()) hit.add(e);
            }
            if (bh.getType() != HitResult.Type.MISS) {
                wall = bh;
                end = segEnd;
                break;
            }
            from = to;
        }
        for (Entity e : hit) ModDamageTypes.hurt(w, (LivingEntity) e, ModDamageTypes.CORE_ROUND, p, p, CoreRevolverItem.DAMAGE);
        if (wall != null) scar(w, wall, p);
        w.playSound(null, p.getX(), p.getEyeY(), p.getZ(), ModSounds.CORE_REVOLVER_FIRE, SoundCategory.PLAYERS, 3.5f, 0.95f + w.random.nextFloat() * 0.1f);
        ReliquaryNet.broadcastShot(w, p, end, wall != null);
    }

    private static void scar(ServerWorld w, BlockHitResult hit, ServerPlayerEntity owner) {
        Direction face = hit.getSide();
        BlockPos at = hit.getBlockPos().offset(face);
        BlockState here = w.getBlockState(at);
        if (here.isOf(ModBlocks.MOLTEN_SCAR)) {
            if (w.getBlockEntity(at) instanceof MoltenScarBlockEntity be) be.reheat(owner);
            return;
        }
        if (!here.isAir() && !here.isReplaceable()) return;
        BlockState scar = ModBlocks.MOLTEN_SCAR.getDefaultState().with(MoltenScarBlock.FACING, face);
        if (!scar.canPlaceAt(w, at)) return;
        w.setBlockState(at, scar);
        if (w.getBlockEntity(at) instanceof MoltenScarBlockEntity be) be.reheat(owner);
        w.playSound(null, at, ModSounds.MOLTEN_SIZZLE, SoundCategory.BLOCKS, 1f, 1f);
    }
}
