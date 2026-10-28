package com.creedpdf.orbitalrailgun.reliquary.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import com.creedpdf.orbitalrailgun.reliquary.ModBlocks;
import org.jetbrains.annotations.Nullable;

/**
 * A molten scar: a thin glowing sheet of fused surface, stuck to the face the revolver round hit. FACING points away
 * from that surface (UP for a floor). No collision; it cools away on its own ({@link MoltenScarBlockEntity#LIFE}) and
 * falls off if the surface under it goes.
 */
public class MoltenScarBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = Properties.FACING;
    private static final VoxelShape[] SHAPES = new VoxelShape[6];

    static {
        for (Direction d : Direction.values()) {
            double t = 1;
            VoxelShape s = switch (d) {
                case UP -> Block.createCuboidShape(0, 0, 0, 16, t, 16);
                case DOWN -> Block.createCuboidShape(0, 16 - t, 0, 16, 16, 16);
                case NORTH -> Block.createCuboidShape(0, 0, 16 - t, 16, 16, 16);
                case SOUTH -> Block.createCuboidShape(0, 0, 0, 16, 16, t);
                case WEST -> Block.createCuboidShape(16 - t, 0, 0, 16, 16, 16);
                case EAST -> Block.createCuboidShape(0, 0, 0, t, 16, 16);
            };
            SHAPES[d.getId()] = s;
        }
    }

    public MoltenScarBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.UP));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return SHAPES[state.get(FACING).getId()];
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        Direction f = state.get(FACING);
        BlockPos on = pos.offset(f.getOpposite());
        return world.getBlockState(on).isSideSolidFullSquare(world, on, f);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction dir, BlockState neighbor, WorldAccess world, BlockPos pos, BlockPos npos) {
        return dir == state.get(FACING).getOpposite() && !canPlaceAt(state, world, pos) ? net.minecraft.block.Blocks.AIR.getDefaultState() : state;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new MoltenScarBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : checkType(type, ModBlocks.MOLTEN_SCAR_BE, (w, p, s, be) -> be.serverTick());
    }
}
