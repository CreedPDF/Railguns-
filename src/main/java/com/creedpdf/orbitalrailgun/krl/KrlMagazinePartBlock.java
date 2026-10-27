package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * One of the 17 filler blocks of the 3 x 3 x 2 interceptor magazine. It remembers where it sits in the rack (DX, DY,
 * DZ, the centre block being 1, 0, 1); it is drawn by the centre, and using or breaking it goes to the centre.
 */
public class KrlMagazinePartBlock extends Block {
    public static final IntProperty DX = IntProperty.of("dx", 0, 2), DY = IntProperty.of("dy", 0, 1), DZ = IntProperty.of("dz", 0, 2);
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 16, 16);

    public KrlMagazinePartBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(DX, 0).with(DY, 0).with(DZ, 0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(DX, DY, DZ);
    }

    public static BlockPos center(BlockState state, BlockPos pos) {
        return pos.add(1 - state.get(DX), -state.get(DY), 1 - state.get(DZ));
    }

    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        BlockPos c = center(state, pos);
        if (!world.isClient && world.getBlockState(c).isOf(Krl.MAGAZINE)) {
            if (!player.getAbilities().creativeMode) Block.dropStack(world, c, new net.minecraft.item.ItemStack(Krl.MAGAZINE_ITEM));
            world.setBlockState(c, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        super.onBreak(world, pos, state, player);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockPos c = center(state, pos);
        BlockState cs = world.getBlockState(c);
        if (!cs.isOf(Krl.MAGAZINE)) return ActionResult.PASS;
        return cs.onUse(world, player, hand, hit.withBlockPos(c));
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }
}
