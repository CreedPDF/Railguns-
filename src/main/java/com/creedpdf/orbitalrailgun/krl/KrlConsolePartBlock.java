package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/** One of the console's two side blocks. Drawn by the centre; uses and breaking go to the centre. */
public class KrlConsolePartBlock extends Block {
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 16, 16);

    public KrlConsolePartBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.SOUTH).with(RIGHT, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, RIGHT);
    }

    public static BlockPos center(BlockState state, BlockPos pos) {
        Direction a = KrlConsoleBlock.across(state.get(FACING));
        return state.get(RIGHT) ? pos.offset(a.getOpposite()) : pos.offset(a);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction dir, BlockState neighbor, WorldAccess world, BlockPos pos, BlockPos npos) {
        if (npos.equals(center(state, pos)) && !neighbor.isOf(Krl.CONSOLE)) return Blocks.AIR.getDefaultState();
        return super.getStateForNeighborUpdate(state, dir, neighbor, world, pos, npos);
    }

    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        BlockPos c = center(state, pos);
        if (!world.isClient && world.getBlockState(c).isOf(Krl.CONSOLE)) {
            if (!player.getAbilities().creativeMode) Block.dropStack(world, c, new ItemStack(Krl.CONSOLE_ITEM));
            world.setBlockState(c, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        super.onBreak(world, pos, state, player);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        return KrlConsoleBlock.use(world, center(state, pos), player);
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
