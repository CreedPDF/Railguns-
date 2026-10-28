package com.creedpdf.orbitalrailgun.reliquary;

import com.creedpdf.orbitalrailgun.reliquary.block.MoltenScarBlock;
import com.creedpdf.orbitalrailgun.reliquary.block.MoltenScarBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/** The molten scar the revolver leaves on what it hits (no item: it only ever appears in the world, then cools away). */
public final class ModBlocks {
    private ModBlocks() {}

    public static final MoltenScarBlock MOLTEN_SCAR = Registry.register(Registries.BLOCK, CosmicReliquary.id("molten_scar"),
            new MoltenScarBlock(AbstractBlock.Settings.create().mapColor(MapColor.ORANGE).noCollision().nonOpaque().breakInstantly()
                    .dropsNothing().luminance(s -> 11).pistonBehavior(PistonBehavior.DESTROY).sounds(BlockSoundGroup.BASALT)));
    public static final BlockEntityType<MoltenScarBlockEntity> MOLTEN_SCAR_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            CosmicReliquary.id("molten_scar"), FabricBlockEntityTypeBuilder.create(MoltenScarBlockEntity::new, MOLTEN_SCAR).build());

    static void init() {}
}
