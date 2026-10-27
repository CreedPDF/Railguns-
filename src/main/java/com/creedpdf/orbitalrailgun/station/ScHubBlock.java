package com.creedpdf.orbitalrailgun.station;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * The Command Hub: 3 x 3 x 3 blocks (this is the bottom centre; the rest are {@link ScHubPartBlock}s). Using any part
 * opens the station console.
 */
public class ScHubBlock extends BlockWithEntity {
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 16, 16);

    public ScHubBlock(Settings settings) {
        super(settings);
    }

    static void forParts(BlockPos c, BiConsumer<BlockPos, int[]> f) {
        for (int dy = 0; dy <= 2; dy++)
            for (int dx = 0; dx <= 2; dx++)
                for (int dz = 0; dz <= 2; dz++) {
                    if (dx == 1 && dy == 0 && dz == 1) continue;
                    f.accept(c.add(dx - 1, dy, dz - 1), new int[]{dx, dy, dz});
                }
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockPos c = ctx.getBlockPos();
        World w = ctx.getWorld();
        boolean[] ok = {true};
        forParts(c, (p, d) -> {
            if (!w.getBlockState(p).canReplace(ctx) || w.isOutOfHeightLimit(p.getY())) ok[0] = false;
        });
        return ok[0] ? getDefaultState() : null;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (world.isClient) return;
        forParts(pos, (p, d) -> world.setBlockState(p, Sc.HUB_PART.getDefaultState().with(ScHubPartBlock.DX, d[0])
                .with(ScHubPartBlock.DY, d[1]).with(ScHubPartBlock.DZ, d[2]), Block.NOTIFY_ALL));
        world.playSound(null, pos, Sc.S_LINK, net.minecraft.sound.SoundCategory.BLOCKS, 1.2f, 0.8f);
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
        return new ScHubBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return checkType(type, Sc.HUB_BE, (w, p, s, be) -> be.tick());
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (world.isClient) return ActionResult.SUCCESS;
        if (world.getBlockEntity(pos) instanceof ScHubBlockEntity hub && player instanceof ServerPlayerEntity sp) ScNet.sendHub(sp, hub, true);
        return ActionResult.CONSUME;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock()) && !world.isClient) {
            forParts(pos, (p, d) -> {
                BlockState ps = world.getBlockState(p);
                if (ps.isOf(Sc.HUB_PART) && ScHubPartBlock.center(ps, p).equals(pos)) world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            });
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
