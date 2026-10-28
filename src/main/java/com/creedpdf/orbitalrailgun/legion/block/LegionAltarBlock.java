package com.creedpdf.orbitalrailgun.legion.block;

import com.creedpdf.orbitalrailgun.krl.KrlText;
import com.creedpdf.orbitalrailgun.legion.ArsenalLegion;
import com.creedpdf.orbitalrailgun.legion.capability.IWeaponSwarm;
import com.creedpdf.orbitalrailgun.legion.capability.SwarmCapability;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The Legion Altar: where weapons are bound to (and released from) a player's swarm. It is the only way in - there are
 * no commands or shortcuts.
 * <ul>
 *   <li>Use it holding a sword, axe, trident or shield (or anything in {@code arsenal_legion:swarm_weapons}): the weapon
 *   leaves your hand and joins your swarm, if you have room (your swarm capacity, 6 unless raised).</li>
 *   <li>Sneak-use it empty-handed: every bound weapon returns to your inventory (any that do not fit drop at your feet).</li>
 *   <li>Use it empty-handed: your swarm's status.</li>
 * </ul>
 * Stowing and redeploying the swarm in the field is a key (G by default), not done here.
 */
public class LegionAltarBlock extends BlockWithEntity {
    private static final VoxelShape SHAPE = VoxelShapes.union(Block.createCuboidShape(0, 0, 0, 16, 3, 16),
            Block.createCuboidShape(3, 3, 3, 13, 14, 13));

    public LegionAltarBlock(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (world.isClient) return ActionResult.SUCCESS;
        IWeaponSwarm swarm = SwarmCapability.of(player);
        int cap = SwarmCapability.capacity(player);
        ItemStack held = player.getMainHandStack();
        if (!held.isEmpty()) {
            if (!SwarmCapability.eligible(held)) {
                player.sendMessage(KrlText.t("THE ALTAR ANSWERS ONLY SWORDS, AXES, TRIDENTS AND SHIELDS", KrlText.RED), true);
                return ActionResult.CONSUME;
            }
            ItemStack one = held.copyWithCount(1);
            int slot = swarm.bind(one, cap);
            if (slot < 0) {
                player.sendMessage(KrlText.t("SWARM FULL  " + swarm.count() + " / " + cap, KrlText.RED), true);
                return ActionResult.CONSUME;
            }
            held.decrement(1);
            swarm.setActive(true);
            world.playSound(null, pos, ArsenalLegion.ALTAR_BIND, SoundCategory.BLOCKS, 1f, 1f);
            if (world instanceof ServerWorld sw)
                sw.spawnParticles(ArsenalLegion.KINETIC_GAZE, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 24, 0.3, 0.3, 0.3, 0.15);
            player.sendMessage(KrlText.t("WEAPON BOUND TO THE LEGION  " + swarm.count() + " / " + cap, KrlText.AMBER), true);
            return ActionResult.CONSUME;
        }
        if (player.isSneaking()) {
            List<ItemStack> all = swarm.takeAll();
            for (ItemStack s : all) if (!player.getInventory().insertStack(s)) player.dropItem(s, false);
            world.playSound(null, pos, ArsenalLegion.ALTAR_BIND, SoundCategory.BLOCKS, 1f, 0.6f);
            player.sendMessage(KrlText.t(all.isEmpty() ? "NO WEAPONS ARE BOUND" : "THE LEGION RELEASES " + all.size() + " WEAPON" + (all.size() == 1 ? "" : "S"),
                    KrlText.GREY), true);
            return ActionResult.CONSUME;
        }
        player.sendMessage(KrlText.t("LEGION  " + swarm.count() + " / " + cap + "  BOUND  //  " + (swarm.isActive() ? "DEPLOYED" : "STOWED")
                + "  //  SNEAK-USE TO RELEASE", KrlText.GREY), true);
        return ActionResult.CONSUME;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new LegionAltarBlockEntity(pos, state);
    }
}
