package com.creedpdf.orbitalrailgun.reliquary;

import com.creedpdf.orbitalrailgun.reliquary.entity.CustomSingularityEntity;
import com.creedpdf.orbitalrailgun.reliquary.entity.VoidRiftEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/** The Star-Eater's singularity and the Void-Carver's rift. Neither is saved in the world (both are short-lived). */
public final class ModEntities {
    private ModEntities() {}

    public static final EntityType<CustomSingularityEntity> SINGULARITY = Registry.register(Registries.ENTITY_TYPE, CosmicReliquary.id("singularity"),
            FabricEntityTypeBuilder.<CustomSingularityEntity>create(SpawnGroup.MISC, CustomSingularityEntity::new)
                    .dimensions(EntityDimensions.fixed(1.5f, 1.5f)).trackRangeBlocks(128).trackedUpdateRate(2)
                    .disableSaving().disableSummon().fireImmune().build());
    public static final EntityType<VoidRiftEntity> VOID_RIFT = Registry.register(Registries.ENTITY_TYPE, CosmicReliquary.id("void_rift"),
            FabricEntityTypeBuilder.<VoidRiftEntity>create(SpawnGroup.MISC, VoidRiftEntity::new)
                    .dimensions(EntityDimensions.fixed(0.8f, 2.2f)).trackRangeBlocks(96).trackedUpdateRate(10)
                    .disableSaving().disableSummon().fireImmune().build());

    static void init() {}
}
