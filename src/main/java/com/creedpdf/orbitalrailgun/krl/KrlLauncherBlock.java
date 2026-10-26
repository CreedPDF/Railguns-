package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The KRL-1 launch pad. Right-click it holding a kinetic spear to load the rail; sneak-right-click it with an empty
 * hand to pick it for linking, then sneak-right-click a fire-control console to link them (a console placed within a
 * few blocks links on its own). The pad's model is 3 x 3 with a 10-block tower; the block itself is the pad's centre.
 */
public class KrlLauncherBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
    private static final VoxelShape SHAPE = VoxelShapes.union(Block.createCuboidShape(0, 0, 0, 16, 6, 16));

    public KrlLauncherBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.SOUTH));
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
        return new KrlLauncherBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return checkType(type, Krl.LAUNCHER_BE, world.isClient ? KrlLauncherBlockEntity::clientTick : KrlLauncherBlockEntity::serverTick);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (world.isClient) return;
        // a console standing within a few blocks takes the new launcher straight away
        for (BlockPos p : BlockPos.iterate(pos.add(-4, -2, -4), pos.add(4, 2, 4))) {
            if (world.getBlockEntity(p) instanceof KrlConsoleBlockEntity c && c.link(pos)) {
                if (placer instanceof PlayerEntity pl) pl.sendMessage(Text.literal("KRL-1 linked to fire control at " + p.toShortString()).formatted(Formatting.RED), true);
                world.playSound(null, p, Krl.S_LINK, SoundCategory.BLOCKS, 1f, 1f);
                break;
            }
        }
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!(world.getBlockEntity(pos) instanceof KrlLauncherBlockEntity be)) return ActionResult.PASS;
        ItemStack held = player.getStackInHand(hand);
        if (held.isOf(Krl.SPEAR)) {
            if (world.isClient) return ActionResult.SUCCESS;
            if (be.loaded()) {
                player.sendMessage(Text.literal("The rail is already loaded.").formatted(Formatting.GRAY), true);
                return ActionResult.CONSUME;
            }
            be.load();
            if (!player.getAbilities().creativeMode) held.decrement(1);
            world.playSound(null, pos, Krl.S_LOAD, SoundCategory.BLOCKS, 1.4f, 1f);
            player.sendMessage(Text.literal("Kinetic spear seated in the clamps. Rail armed for fire control.").formatted(Formatting.RED), true);
            return ActionResult.CONSUME;
        }
        if (player.isSneaking() && held.isEmpty()) {
            if (world.isClient) return ActionResult.SUCCESS;
            KrlLinking.select((ServerPlayerEntity) player, pos);
            world.playSound(null, pos, Krl.S_CLICK, SoundCategory.BLOCKS, 1f, 1.2f);
            player.sendMessage(Text.literal("KRL-1 selected - sneak-right-click a fire-control console to link it.").formatted(Formatting.RED), true);
            return ActionResult.CONSUME;
        }
        if (!world.isClient) player.sendMessage(Text.literal(be.statusLine()).formatted(Formatting.GRAY), true);
        return ActionResult.success(world.isClient);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock()) && !world.isClient && world.getBlockEntity(pos) instanceof KrlLauncherBlockEntity be) {
            if (be.igniting()) KrlServer.abort(be.flight());
            if (be.loaded()) Block.dropStack(world, pos, new ItemStack(Krl.SPEAR));
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
