package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
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
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * The KRL fire-control console: three blocks wide, one deep - this block is its centre and holds the state; the two
 * side blocks ({@link KrlConsolePartBlock}) are placed and removed with it. Using any of them opens fire control;
 * sneak-using it right after picking a launcher links that launcher.
 */
public class KrlConsoleBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 16, 16);

    public KrlConsoleBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.SOUTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** The direction the console widens to its right (seen by the operator standing in front of it). */
    public static Direction across(Direction facing) {
        return facing.rotateYCounterclockwise();
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction f = ctx.getHorizontalPlayerFacing().getOpposite();
        BlockPos p = ctx.getBlockPos();
        Direction a = across(f);
        World w = ctx.getWorld();
        if (!w.getBlockState(p.offset(a)).canReplace(ctx) || !w.getBlockState(p.offset(a.getOpposite())).canReplace(ctx)) return null;
        return getDefaultState().with(FACING, f);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (world.isClient) return;
        Direction f = state.get(FACING), a = across(f);
        world.setBlockState(pos.offset(a), Krl.CONSOLE_PART.getDefaultState().with(KrlConsolePartBlock.FACING, f).with(KrlConsolePartBlock.RIGHT, true), Block.NOTIFY_ALL);
        world.setBlockState(pos.offset(a.getOpposite()), Krl.CONSOLE_PART.getDefaultState().with(KrlConsolePartBlock.FACING, f).with(KrlConsolePartBlock.RIGHT, false), Block.NOTIFY_ALL);
        // launchers standing near the new console link to it straight away
        if (world.getBlockEntity(pos) instanceof KrlConsoleBlockEntity c) {
            int n = 0;
            for (BlockPos p : BlockPos.iterate(pos.add(-5, -2, -5), pos.add(5, 2, 5))) {
                if (world.getBlockState(p).isOf(Krl.LAUNCHER) && c.link(p)) n++;
            }
            if (n > 0) {
                world.playSound(null, pos, Krl.S_LINK, SoundCategory.BLOCKS, 1f, 1f);
                if (placer instanceof PlayerEntity pl) pl.sendMessage(Text.literal("Fire control linked " + n + " launcher" + (n > 1 ? "s" : "") + ".").formatted(Formatting.RED), true);
            }
        }
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction dir, BlockState neighbor, WorldAccess world, BlockPos pos, BlockPos npos) {
        Direction a = across(state.get(FACING));
        if ((dir == a || dir == a.getOpposite()) && !neighbor.isOf(Krl.CONSOLE_PART)) return net.minecraft.block.Blocks.AIR.getDefaultState();
        return super.getStateForNeighborUpdate(state, dir, neighbor, world, pos, npos);
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
        return new KrlConsoleBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        return use(world, pos, player);
    }

    /** Shared by the centre and the side blocks. */
    static ActionResult use(World world, BlockPos center, PlayerEntity player) {
        if (world.isClient) return ActionResult.SUCCESS;
        if (!(world.getBlockEntity(center) instanceof KrlConsoleBlockEntity c)) return ActionResult.PASS;
        ServerPlayerEntity sp = (ServerPlayerEntity) player;
        BlockPos pending = KrlLinking.pending(sp);
        if (player.isSneaking() && pending != null) {
            KrlLinking.clear(sp);
            if (!pending.isWithinDistance(center, Krl.LINK_RANGE)) {
                player.sendMessage(Text.literal("Launcher out of link range (" + (int) Krl.LINK_RANGE + " blocks).").formatted(Formatting.GRAY), true);
            } else if (c.links().contains(pending)) {
                player.sendMessage(Text.literal("That launcher is already linked.").formatted(Formatting.GRAY), true);
            } else if (!c.link(pending)) {
                player.sendMessage(Text.literal("Fire control holds " + Krl.MAX_LINKS + " launchers at most.").formatted(Formatting.GRAY), true);
            } else {
                world.playSound(null, center, Krl.S_LINK, SoundCategory.BLOCKS, 1f, 1f);
                player.sendMessage(Text.literal("Launcher linked (" + c.links().size() + "/" + Krl.MAX_LINKS + ").").formatted(Formatting.RED), true);
            }
            return ActionResult.CONSUME;
        }
        world.playSound(null, center, Krl.S_CLICK, SoundCategory.BLOCKS, 0.8f, 1f);
        KrlNet.open(sp, c);
        return ActionResult.CONSUME;
    }
}
