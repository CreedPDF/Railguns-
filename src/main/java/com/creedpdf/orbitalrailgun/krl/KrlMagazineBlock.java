package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * KRL-M30 interceptor magazine. Stand it next to a KRL-1 launcher (within two blocks) and load it with up to 30
 * kinetic missiles: use one on it to load it (sneak to load the whole stack), use it empty-handed to take one out.
 * When the linked fire-control console's radar is on, the launcher fires them from here at anything hostile in the air.
 */
public class KrlMagazineBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 15.5, 16);

    public KrlMagazineBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.SOUTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
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
        return new KrlMagazineBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (world.isClient) return ActionResult.SUCCESS;
        if (!(world.getBlockEntity(pos) instanceof KrlMagazineBlockEntity m)) return ActionResult.PASS;
        ItemStack held = player.getMainHandStack();
        if (held.isOf(Krl.SPEAR)) {
            int want = player.isSneaking() ? held.getCount() : 1;
            int moved = m.add(want);
            if (moved == 0) {
                player.sendMessage(KrlText.t("MAGAZINE FULL  " + m.rounds() + "/" + Krl.MAG_CAP, KrlText.GREY), true);
                return ActionResult.CONSUME;
            }
            if (!player.getAbilities().creativeMode) held.decrement(moved);
            world.playSound(null, pos, Krl.S_LOAD, SoundCategory.BLOCKS, 0.9f, 1.15f);
            player.sendMessage(KrlText.t("INTERCEPTORS LOADED  " + m.rounds() + "/" + Krl.MAG_CAP, KrlText.AMBER), true);
            return ActionResult.CONSUME;
        }
        if (held.isEmpty() && !player.isSneaking() && m.rounds() > 0) {
            m.add(-1);
            player.giveItemStack(new ItemStack(Krl.SPEAR));
            world.playSound(null, pos, Krl.S_CLICK, SoundCategory.BLOCKS, 0.8f, 0.8f);
        }
        player.sendMessage(KrlText.t("MAGAZINE  " + m.rounds() + "/" + Krl.MAG_CAP + "  INTERCEPTORS", KrlText.GREY), true);
        return ActionResult.CONSUME;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock()) && world.getBlockEntity(pos) instanceof KrlMagazineBlockEntity m && !world.isClient) {
            int n = m.rounds();
            while (n > 0) {
                int k = Math.min(4, n);
                ItemScatterer.spawn(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(Krl.SPEAR, k));
                n -= k;
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
