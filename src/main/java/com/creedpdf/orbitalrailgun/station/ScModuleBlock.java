package com.creedpdf.orbitalrailgun.station;

import com.creedpdf.orbitalrailgun.krl.KrlText;
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
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** A one-block station module (solar array, station railgun, strike satellite); its model is far bigger than the block. */
public class ScModuleBlock extends BlockWithEntity {
    public enum Kind { SOLAR, RAILGUN, SATELLITE }

    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 16, 16);
    public final Kind kind;

    public ScModuleBlock(Settings settings, Kind kind) {
        super(settings);
        this.kind = kind;
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
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (world.isClient) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ScSolarBlockEntity s) s.placed();
        if (be instanceof ScSatelliteBlockEntity s && placer instanceof PlayerEntity p) s.setOwner(p.getUuid());
        world.playSound(null, pos, kind == Kind.SOLAR ? Sc.S_DEPLOY : Sc.S_LINK, SoundCategory.BLOCKS, 1f, 1f);
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
        return switch (kind) {
            case SOLAR -> new ScSolarBlockEntity(pos, state);
            case RAILGUN -> new ScRailgunBlockEntity(pos, state);
            case SATELLITE -> new ScSatelliteBlockEntity(pos, state);
        };
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return (w, p, s, be) -> {
            if (be instanceof ScModuleBlockEntity m) m.tick();
        };
    }

    /** Using a module tells you its status. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (world.isClient) return ActionResult.SUCCESS;
        if (!(world.getBlockEntity(pos) instanceof ScModuleBlockEntity m)) return ActionResult.PASS;
        String name = switch (kind) {
            case SOLAR -> "SOLAR ARRAY";
            case RAILGUN -> "STATION RAILGUN";
            case SATELLITE -> "STRIKE SATELLITE";
        };
        String s;
        int col;
        if (!m.linked()) {
            s = name + "  //  NO HUB WITHIN " + Sc.LINK + " BLOCKS";
            col = KrlText.RED;
        } else {
            String extra = switch (kind) {
                case SOLAR -> m.supply() > 0 ? String.format(Locale.ROOT, "GENERATING %.0f kW", m.supply()) : ((ScSolarBlockEntity) m).deployed() ? "NO SUN" : "DEPLOYING";
                case RAILGUN -> m.power() >= Sc.RAILGUN_KW ? "ONLINE  //  WEAPONS FREE" : "UNPOWERED  (NEEDS " + (int) Sc.RAILGUN_KW + " kW)";
                case SATELLITE -> String.format(Locale.ROOT, "CHARGE %d%%  //  %.0f kW", (int) (((ScSatelliteBlockEntity) m).charge() * 100), m.power());
            };
            s = name + "  //  LINKED  //  " + extra;
            col = KrlText.AMBER;
        }
        player.sendMessage(KrlText.t(s, col), true);
        return ActionResult.CONSUME;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock()) && !world.isClient && kind == Kind.SATELLITE)
            ScSatellites.get(world.getServer()).remove(world.getRegistryKey(), pos);
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
