package com.creedpdf.orbitalrailgun.block;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * The starship's flight console: a real block standing in the cabin of a parked ship. Using it
 * opens the navigation terminal (destination, launch, stow). The ship places it when it parks
 * and removes it when it lifts off.
 */
public class FlightConsoleBlock extends HorizontalFacingBlock {
    private static final VoxelShape SHAPE = VoxelShapes.union(
            Block.createCuboidShape(3, 0, 3, 13, 9, 13),
            Block.createCuboidShape(1, 9, 1, 15, 14, 15));

    public FlightConsoleBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, net.minecraft.util.math.Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        ShipEntity ship = ShipEntity.near(world, pos);
        if (world.isClient) {
            if (ship != null && !ship.inFlight()) RailgunMod.client().openShip(ship);
            return ActionResult.SUCCESS;
        }
        if (ship == null) {
            player.sendMessage(Text.translatable("message.orbital_railgun.ship.none").formatted(Formatting.RED), true);
        }
        return ActionResult.CONSUME;
    }
}
