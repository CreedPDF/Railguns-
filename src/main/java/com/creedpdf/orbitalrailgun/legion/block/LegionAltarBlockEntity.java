package com.creedpdf.orbitalrailgun.legion.block;

import com.creedpdf.orbitalrailgun.legion.ArsenalLegion;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/** The altar holds no data; its block entity exists so its Blender model can be drawn and animated. */
public class LegionAltarBlockEntity extends BlockEntity {
    public LegionAltarBlockEntity(BlockPos pos, BlockState state) {
        super(ArsenalLegion.ALTAR_BE, pos, state);
    }
}
