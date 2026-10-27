package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/** The Missile Integration Station keeps no data; the block entity is only there so the bench can be drawn as a mesh. */
public class KrlStationBlockEntity extends BlockEntity {
    public KrlStationBlockEntity(BlockPos pos, BlockState state) {
        super(Krl.STATION_BE, pos, state);
    }
}
