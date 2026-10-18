package com.creedpdf.orbitalrailgun.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * Invisible, indestructible block that gives the deployed railgun real collision.
 *
 * <p>An entity can only collide as one box, which for an 11-block fortress with stairs is
 * useless. Instead, when the railgun finishes deploying it fills its footprint with these blocks,
 * column by column, following a heightfield exported from the Blender model
 * ({@code orbital_railgun_collision.json}), so you can walk up the stairs and stand on each
 * tier. {@link #HEIGHT} (1..16 px) lets the top block of each column match the model's height.</p>
 *
 * <p>The outline shape is empty, so the block can never be targeted: clicks and the tablet's
 * raycast pass through to the railgun and the world behind it. It renders nothing, drops
 * nothing, cannot be pushed, and is removed again when the railgun packs up or is removed.</p>
 */
public class RailgunColliderBlock extends Block {
    public static final IntProperty HEIGHT = IntProperty.of("height", 1, 16);
    private static final VoxelShape[] SHAPES = new VoxelShape[17];

    static {
        for (int h = 1; h <= 16; h++) SHAPES[h] = Block.createCuboidShape(0, 0, 0, 16, h, 16);
    }

    public RailgunColliderBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(HEIGHT, 16));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(HEIGHT);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPES[state.get(HEIGHT)];
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
        return true;
    }

    @Override
    public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
        return 1.0f;
    }

}
